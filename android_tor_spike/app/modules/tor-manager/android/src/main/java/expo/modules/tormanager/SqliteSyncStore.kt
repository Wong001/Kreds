package expo.modules.tormanager

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Android SQLite impl of SyncStore -- same gates/semantics as
 *  InMemorySyncStore (the JVM-tested reference in SyncStore.kt), but
 *  persisted so content survives app restarts. `messages` rows are never
 *  deleted, so the (identity_pub, device_pub, seq) -> msg_id mapping is
 *  1:1 once written -- that lets ingestMessage's seq-reuse gate query the
 *  messages table directly instead of maintaining a separate SeenSet table;
 *  summary() still rebuilds real SeenSets (fold of stored seqs) since that's
 *  the wire shape the HAVE phase sends. */
class SqliteSyncStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), SyncStore {

    companion object {
        private const val DB_NAME = "sync_store.db"
        private const val DB_VERSION = 1
        // Task 3 (B.2): this device's own X25519 enc keypair, keyed enc_priv/enc_pub.
        // Plaintext at rest -- accepted posture, matches desktop (see EncKeys.kt).
        // Shared by onCreate (fresh DBs) and onOpen (existing B.1-era DBs --
        // see onOpen below for why the latter is needed) so the two sites
        // can't drift out of sync.
        private const val CREATE_KEYS_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS keys (k TEXT PRIMARY KEY, v TEXT)"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE identities (identity_pub TEXT PRIMARY KEY)")
        db.execSQL(
            """
            CREATE TABLE messages (
                msg_id TEXT PRIMARY KEY,
                identity_pub TEXT NOT NULL,
                device_pub TEXT NOT NULL,
                seq INTEGER NOT NULL,
                kind TEXT,
                msg_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        // Backs both the ingestMessage seq-reuse gate and summary()'s fold.
        db.execSQL("CREATE INDEX idx_messages_idp_dp_seq ON messages(identity_pub, device_pub, seq)")
        db.execSQL("CREATE TABLE blobs (hash TEXT PRIMARY KEY, data BLOB NOT NULL)")
        // IF NOT EXISTS: onUpgrade calls onCreate after dropping the OTHER
        // tables but deliberately preserves this one (see onUpgrade comment) --
        // this must be a no-op when the table already survived an upgrade.
        db.execSQL(CREATE_KEYS_TABLE_SQL)
    }

    // Field bug (G20, B.2 live run): DB_VERSION was never bumped past 1, so
    // an existing B.1-era phone DB (created before the `keys` table existed)
    // never runs onCreate OR onUpgrade -- it just opens straight through,
    // and every keys-table query fails with "no such table: keys". onOpen
    // runs on every open regardless of version, so ensure the table here too;
    // IF NOT EXISTS makes this a no-op on DBs that already have it.
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL(CREATE_KEYS_TABLE_SQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS identities")
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS blobs")
        // `keys` is deliberately NOT dropped here, unlike the tables above:
        // identities/messages/blobs are all resyncable (the next sync
        // repopulates them from the home node), but `keys` holds this
        // device's own enc-key identity material, which is NOT recoverable
        // that way. hearth's maintain_own_device_grants (Task 2) may already
        // have minted server-side wrap_grants against this device's current
        // enc_pub -- dropping `keys` on a schema bump would silently mint a
        // new enc key next launch and orphan that already-granted history
        // (undecryptable until the new key propagates and is re-granted).
        // Never wipe this table on migration.
        onCreate(db)
    }

    private fun sha(b: ByteArray) =
        KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(b))

    override fun summary(): Map<String, Map<String, Map<String, Any>>> {
        val seen = linkedMapOf<Pair<String, String>, SeenSet>()
        readableDatabase.rawQuery(
            "SELECT identity_pub, device_pub, seq FROM messages", null
        ).use { c ->
            while (c.moveToNext()) {
                val ipub = c.getString(0); val dpub = c.getString(1); val seq = c.getInt(2)
                seen.getOrPut(ipub to dpub) { SeenSet() }.add(seq)
            }
        }
        val out = linkedMapOf<String, MutableMap<String, Map<String, Any>>>()
        for ((k, ss) in seen)
            out.getOrPut(k.first) { linkedMapOf() }[k.second] = ss.toJson()
        return out
    }

    override fun knownIdentities(): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT identity_pub FROM identities", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    override fun addIdentity(id: String) {
        val cv = ContentValues().apply { put("identity_pub", id) }
        writableDatabase.insertWithOnConflict(
            "identities", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    override fun ingestMessage(m: SignedMessage): Boolean {
        val db = writableDatabase

        // 1. is_known gate (mirrors hearth Store.ingest_message's first
        // check): accept only from an identity we already know -- own
        // identity is seeded before sync, friends are added during HAVE.
        // Do NOT auto-register senders.
        val known = db.rawQuery(
            "SELECT 1 FROM identities WHERE identity_pub = ? LIMIT 1",
            arrayOf(m.cert.identity_pub)
        ).use { it.moveToFirst() }
        if (!known) return false

        // 2. verify
        if (!m.verifyDeviceSignature()) return false

        val id = m.msgId()

        // 3. dedup by msg_id -- already have this exact message.
        val alreadyHave = db.rawQuery(
            "SELECT 1 FROM messages WHERE msg_id = ? LIMIT 1", arrayOf(id)
        ).use { it.moveToFirst() }
        if (alreadyHave) return false

        // 4a. seq<1 rejection -- SeenSet.add(seq) rejects seq<1
        // unconditionally (`if (seq < 1 || has(seq)) return false`), before
        // it even checks reuse. A row-existence query alone can't express
        // that (a first message at seq=0/negative has no existing row to
        // collide with), so it needs its own explicit gate here.
        if (m.seq < 1) return false

        // 4b. seq-reuse rejection -- SeenSet's whole purpose in the in-memory
        // reference (D2 Ambush 2; hearth Verifier.verify_message: `if not
        // seen.add(seq): reject`). Rows are never deleted, so a row here
        // with the same (identity_pub, device_pub, seq) can only have a
        // DIFFERENT msg_id -- the same msg_id would already have been
        // caught by the dedup check above. That's equivocation/reuse.
        val reused = db.rawQuery(
            "SELECT 1 FROM messages WHERE identity_pub = ? AND device_pub = ? AND seq = ? LIMIT 1",
            arrayOf(m.cert.identity_pub, m.cert.device_pub, m.seq.toString())
        ).use { it.moveToFirst() }
        if (reused) return false

        // 5. accept
        val cv = ContentValues().apply {
            put("msg_id", id)
            put("identity_pub", m.cert.identity_pub)
            put("device_pub", m.cert.device_pub)
            put("seq", m.seq)
            put("kind", m.kind)
            put("msg_json", serialize(m))
        }
        db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        return true
    }

    /** Re-derives the node's dict shape (cert/seq/payload/signature) from
     *  the parsed SignedMessage and serializes it via org.json -- ingestMessage
     *  only ever receives a parsed SignedMessage (KotlinSync parses frames
     *  straight into one), never the node's raw JSON string, so this is the
     *  faithful re-serialization available.
     *
     *  Deliberately NOT KotlinWire.dumps: dumps routes any Double (incl.
     *  cert.enrolled_at) through pyFloatRepr, which THROWS outside a narrow
     *  supported range (|n|>=1e16, <1e-4, or non-finite). enrolled_at is part
     *  of the enrollment cert, not covered by THIS message's device
     *  signature -- verifyDeviceSignature() never touches it -- so a
     *  validly-signed message carrying a hostile/odd enrolled_at would throw
     *  here, uncaught, and abort KotlinSync.run's whole session (its
     *  top-level catch turns it into SyncResult.Failed), dropping every
     *  other good message in the same sync. InMemorySyncStore never
     *  serializes enrolled_at at all, so it has no equivalent failure mode.
     *  org.json has no such range restriction, and msg_json is only ever
     *  read back via org.json (missingBlobs), so this round-trips fine. */
    private fun serialize(m: SignedMessage): String {
        val cert = JSONObject().apply {
            put("identity_pub", m.cert.identity_pub); put("device_pub", m.cert.device_pub)
            put("device_name", m.cert.device_name); put("enrolled_at", m.cert.enrolled_at)
            put("signature", m.cert.signature)
        }
        return JSONObject().apply {
            put("cert", cert); put("seq", m.seq)
            put("payload", JSONObject(m.payload))   // wraps nested Maps/Lists recursively
            put("signature", m.signature)
        }.toString()
    }

    /** Blob hashes referenced by stored POST/DM payloads, minus what we
     *  hold. Mirrors InMemorySyncStore.missingBlobs (hearth.store.
     *  referenced_blobs for the KIND_POST/KIND_DM fields -- blobs list +
     *  poster str + thumbs list, junk-guarded to strings) but reads the
     *  refs back out of the stored msg_json instead of an in-memory
     *  SignedMessage. */
    override fun missingBlobs(): List<String> {
        val refs = linkedSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT msg_json FROM messages WHERE kind IN ('post', 'dm')", null
        ).use { c ->
            while (c.moveToNext()) {
                val payload = JSONObject(c.getString(0)).optJSONObject("payload") ?: continue
                (payload.opt("blobs") as? JSONArray)?.let { arr ->
                    for (i in 0 until arr.length()) (arr.opt(i) as? String)?.let { refs.add(it) }
                }
                (payload.opt("poster") as? String)?.let { if (it.isNotEmpty()) refs.add(it) }
                (payload.opt("thumbs") as? JSONArray)?.let { arr ->
                    for (i in 0 until arr.length()) (arr.opt(i) as? String)?.let { refs.add(it) }
                }
            }
        }
        val held = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT hash FROM blobs", null).use { c ->
            while (c.moveToNext()) held.add(c.getString(0))
        }
        return refs.filterNot { it in held }
    }

    override fun putBlob(hash: String, data: ByteArray): Boolean {
        if (sha(data) != hash) return false
        val cv = ContentValues().apply { put("hash", hash); put("data", data) }
        writableDatabase.insertWithOnConflict("blobs", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        return true
    }

    override fun stats(): SyncStats {
        fun count(table: String): Int = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $table", null
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        return SyncStats(count("messages"), count("blobs"), count("identities"))
    }

    /** Reads the `keys` table row for `k`, or null if absent. */
    private fun readKey(db: SQLiteDatabase, k: String): String? =
        db.rawQuery("SELECT v FROM keys WHERE k = ? LIMIT 1", arrayOf(k)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    override fun getEncKey(): Pair<String, String>? {
        val db = readableDatabase
        val priv = readKey(db, "enc_priv") ?: return null
        val pub = readKey(db, "enc_pub") ?: return null
        return priv to pub
    }

    /** Writes both keys rows atomically -- a reader must never observe an
     *  enc_priv/enc_pub pair from two different writes (see EncKeys.getOrCreate,
     *  which also serializes same-process callers; this transaction protects
     *  against a crash/interleaving mid-write leaving a half-written row pair). */
    override fun setEncKey(priv: String, pub: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cvPriv = ContentValues().apply { put("k", "enc_priv"); put("v", priv) }
            val cvPub = ContentValues().apply { put("k", "enc_pub"); put("v", pub) }
            db.insertWithOnConflict("keys", null, cvPriv, SQLiteDatabase.CONFLICT_REPLACE)
            db.insertWithOnConflict("keys", null, cvPub, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** This device's next outbound message seq (Task 4, B.2), persisted in
     *  the SAME `keys` k/v table Task 3 added for enc_priv/enc_pub -- one
     *  more small durable value, not a reason for a new table. Absent row
     *  means "never sent a message yet" -> starts at 1 (mirrors hearth
     *  DeviceKeys.sign_message: seq starts at 0, incremented before first
     *  use, so a device's first-ever message is seq=1). Transacted like
     *  setEncKey above: a reader must never be able to observe the same seq
     *  handed out twice because a crash landed between reading the old value
     *  and persisting the incremented one. */
    override fun nextSeq(): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = readKey(db, "next_seq")?.toInt() ?: 1
            val cv = ContentValues().apply { put("k", "next_seq"); put("v", (current + 1).toString()) }
            db.insertWithOnConflict("keys", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return current
        } finally {
            db.endTransaction()
        }
    }

    /** Backed by the same `keys` table as enc_priv/enc_pub/next_seq, under
     *  its own row key -- see the SyncStore interface doc on
     *  getPublishedEncPub/setPublishedEncPub for why this is tracked
     *  separately from the enc keypair itself. Not transacted (unlike
     *  setEncKey/nextSeq): it's a single independent row with no paired
     *  write and no read-then-increment race to protect against. */
    override fun getPublishedEncPub(): String? = readKey(readableDatabase, "enckey_published")

    override fun setPublishedEncPub(pub: String) {
        val cv = ContentValues().apply { put("k", "enckey_published"); put("v", pub) }
        writableDatabase.insertWithOnConflict("keys", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // org.json -> plain Kotlin bridge (JSONObject -> Map, JSONArray -> List),
    // same idiom as KotlinSync's private toMap/unwrap (each file keeps its
    // own copy -- see KotlinSync.kt's comment on why: values are consumed
    // downstream via Map/List casts -- e.g. KotlinDmcrypt.unwrapKey's
    // `wrap["eph_pub"] as String` -- which throws on a raw org.json type).
    private fun jsonToMap(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { unwrapJson(o.get(it)) }

    private fun unwrapJson(v: Any?): Any? = when (v) {
        is JSONObject -> jsonToMap(v)
        is JSONArray -> (0 until v.length()).map { unwrapJson(v.get(it)) }
        JSONObject.NULL -> null
        is java.math.BigDecimal -> v.toDouble()
        else -> v
    }

    /** Every stored message (Task 5, B.2 DecryptPass) -- payload
     *  recursively converted from the stored JSON into plain Kotlin
     *  Map/List so callers can use ordinary casts, exactly like
     *  InMemorySyncStore's native-Kotlin payloads already allow.
     *  identity_pub is a real column, not re-parsed from JSON. */
    override fun allMessages(): List<StoredMsg> {
        val out = mutableListOf<StoredMsg>()
        readableDatabase.rawQuery(
            "SELECT msg_id, identity_pub, kind, msg_json FROM messages", null
        ).use { c ->
            while (c.moveToNext()) {
                val payload = JSONObject(c.getString(3)).optJSONObject("payload") ?: JSONObject()
                // Both identity_pub and kind guarded the same way (`?: ""`)
                // even though identity_pub is a NOT NULL column and should
                // never actually come back null -- StoredMsg.identityPub is
                // a non-null String, so this keeps the two columns'
                // null-handling visibly consistent rather than trusting the
                // schema constraint implicitly.
                out.add(StoredMsg(c.getString(0), c.getString(2) ?: "", c.getString(1) ?: "", jsonToMap(payload)))
            }
        }
        return out
    }

    /** wrap_grant rows targeting msgId AND signed by any identity in
     *  acceptedSigners, decoded from msg_json (no target_id column exists in
     *  this schema -- unlike hearth's store.py -- so the target match is
     *  done in application code, same style as missingBlobs() above; the
     *  signer match IS a real column, `identity_pub`, filtered directly in
     *  SQL via `IN (...)`). Returned oldest-to-newest by (created_at, seq) --
     *  see the SyncStore interface doc for why callers can fold newest-wins
     *  from that order, and why the signer-set filter is load-bearing, not
     *  optional (B.2c: DecryptPass passes {author} for posts, {author,
     *  ownIdentityPub} for a DM addressed to us, else {author}).
     *
     *  Empty acceptedSigners returns no rows without ever touching SQL --
     *  `IN ()` is invalid syntax in SQLite, and DecryptPass's entitlement
     *  rule never actually produces an empty set (every branch includes at
     *  least the message's own author), but a caller passing one legitimately
     *  means "trust nobody", not "trust everybody" (which an unguarded query
     *  with zero placeholders could otherwise be misread as). */
    override fun wrapGrantsFor(msgId: String, acceptedSigners: Set<String>): List<Map<String, Any?>> {
        if (acceptedSigners.isEmpty()) return emptyList()
        data class Row(val createdAt: Double, val seq: Int, val wraps: Map<String, Any?>)
        val rows = mutableListOf<Row>()
        val placeholders = acceptedSigners.joinToString(",") { "?" }
        val args = (listOf("wrap_grant") + acceptedSigners).toTypedArray()
        readableDatabase.rawQuery(
            "SELECT seq, msg_json FROM messages WHERE kind = ? AND identity_pub IN ($placeholders)",
            args
        ).use { c ->
            while (c.moveToNext()) {
                val seq = c.getInt(0)
                val payload = JSONObject(c.getString(1)).optJSONObject("payload") ?: continue
                if (payload.optString("target") != msgId) continue
                val wraps = payload.optJSONObject("wraps") ?: continue
                rows.add(Row(payload.optDouble("created_at", 0.0), seq, jsonToMap(wraps)))
            }
        }
        return rows.sortedWith(compareBy({ it.createdAt }, { it.seq })).map { it.wraps }
    }
}
