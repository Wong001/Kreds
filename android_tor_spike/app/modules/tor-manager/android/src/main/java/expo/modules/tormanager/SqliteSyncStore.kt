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
        // outbound Task 2: bumped 1 -> 2 to add the pending_outbound table
        // (see CREATE_PENDING_OUTBOUND_TABLE_SQL + onUpgrade below). This is
        // the first REAL version bump this store has ever shipped -- DB_VERSION
        // sat at 1 through the whole B.2 `keys`-table field bug (see onOpen's
        // comment), so onUpgrade below, despite existing since B.2, has never
        // actually RUN on a real device before this change.
        private const val DB_VERSION = 2
        // Task 3 (B.2): this device's own X25519 enc keypair, keyed enc_priv/enc_pub.
        // Plaintext at rest -- accepted posture, matches desktop (see EncKeys.kt).
        // Shared by onCreate (fresh DBs) and onOpen (existing B.1-era DBs --
        // see onOpen below for why the latter is needed) so the two sites
        // can't drift out of sync.
        private const val CREATE_KEYS_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS keys (k TEXT PRIMARY KEY, v TEXT)"
        // outbound Task 2: the pending-outbound push queue -- msg_id ONLY,
        // the message body already lives in `messages` (pendingOutbound()
        // joins to it). IF NOT EXISTS so onCreate (fresh DBs) and onUpgrade
        // (existing DBs, via the onCreate(db) call at the end of onUpgrade)
        // share one definition without either site needing to know which
        // case it's in -- same pattern as CREATE_KEYS_TABLE_SQL above.
        private const val CREATE_PENDING_OUTBOUND_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS pending_outbound (msg_id TEXT PRIMARY KEY)"
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
        // Same IF NOT EXISTS reasoning as keys -- also deliberately not in
        // onUpgrade's drop list (see onUpgrade comment).
        db.execSQL(CREATE_PENDING_OUTBOUND_TABLE_SQL)
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
        //
        // `pending_outbound` (outbound Task 2) is likewise NOT dropped here.
        // Its rows are meaningless without the `messages` rows they
        // reference, which the DROP above just wiped -- but leaving the now-
        // orphaned msg_id rows in place is harmless (pendingOutbound()'s JOIN
        // to `messages` simply yields nothing for them) and simpler than
        // reasoning about whether a queued-but-unsynced compose should
        // survive a schema migration. onCreate's IF NOT EXISTS below is a
        // no-op here since the table already exists.
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

    override fun addPendingOutbound(msgId: String) {
        val cv = ContentValues().apply { put("msg_id", msgId) }
        writableDatabase.insertWithOnConflict(
            "pending_outbound", null, cv, SQLiteDatabase.CONFLICT_IGNORE)  // idempotent
    }

    /** See the SyncStore interface doc: the wire dicts of every queued
     *  message, joined from `messages` and parsed via the SAME jsonToMap
     *  bridge `allMessages()`/etc. use below -- but applied to the WHOLE
     *  parsed msg_json object (not just its `payload` sub-object, unlike
     *  those other accessors), because msg_json's top-level shape IS
     *  already `{cert, seq, payload, signature}` (see `serialize()`) --
     *  exactly the wire dict shape `pendingOutbound()` must return, with no
     *  further reshaping needed. Ordered by `rowid` (insertion order),
     *  matching InMemorySyncStore's LinkedHashSet insertion-order
     *  guarantee. An id in `pending_outbound` with no matching `messages`
     *  row (should not happen -- see the interface doc) is simply excluded
     *  by the JOIN, not an error. */
    override fun pendingOutbound(): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        readableDatabase.rawQuery(
            """
            SELECT m.msg_json FROM messages m
            JOIN pending_outbound p ON m.msg_id = p.msg_id
            ORDER BY m.rowid
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) out.add(jsonToMap(JSONObject(c.getString(0))))
        }
        return out
    }

    override fun clearPendingOutbound(msgIds: List<String>) {
        if (msgIds.isEmpty()) return
        val placeholders = msgIds.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "DELETE FROM pending_outbound WHERE msg_id IN ($placeholders)", msgIds.toTypedArray())
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
    @Suppress("UNCHECKED_CAST")
    private fun serialize(m: SignedMessage): String {
        val cert = JSONObject().apply {
            put("identity_pub", m.cert.identity_pub); put("device_pub", m.cert.device_pub)
            put("device_name", m.cert.device_name); put("enrolled_at", m.cert.enrolled_at)
            put("signature", m.cert.signature)
        }
        return JSONObject().apply {
            put("cert", cert); put("seq", m.seq)
            // jsonSafe FIRST, not JSONObject(m.payload) directly -- see its
            // doc for the two silent corruptions org.json's Map/List
            // constructors would otherwise apply to a locally-composed
            // payload (Compose.post's `KotlinWire.PyFloat`-wrapped
            // created_at and its several explicit-null fields).
            put("payload", JSONObject(jsonSafe(m.payload) as Map<String, Any?>))
            put("signature", m.signature)
        }.toString()
    }

    /** Recursively prepares a payload value tree for org.json's Map/List-
     *  based JSONObject/JSONArray constructors, which -- confirmed
     *  empirically against this exact org.json version (20240303) -- corrupt
     *  a locally-composed payload two DIFFERENT silent ways:
     *
     *  1. `JSONObject(Map)`'s constructor SKIPS any entry whose value is
     *  Kotlin/Java `null` instead of writing a JSON `null` (`if (value !=
     *  null) { map.put(...) }`) -- so a payload field like Compose.post's
     *  `"expires_at" to null` simply VANISHES from the stored msg_json
     *  instead of round-tripping as a present-but-null key. Every existing
     *  reader of msg_json (missingBlobs/profileNames/wrapGrantsFor/etc.)
     *  never noticed because `JSONObject.opt(field)` treats "key absent"
     *  and "key present, value null" identically. Fix: swap Kotlin `null`
     *  for the `org.json.JSONObject.NULL` sentinel, which the SAME
     *  constructor's `wrap()` call preserves correctly once past the
     *  null-value skip (`NULL.equals(object)` is one of `wrap()`'s
     *  recognized passthrough cases).
     *
     *  2. An unrecognized POJO -- e.g. `KotlinWire.PyFloat`, which
     *  Compose.post wraps `created_at` in so `KotlinWire.dumps` renders it
     *  via Python-float-repr rules at SIGN time -- falls through org.json's
     *  `wrap()` to its bean-introspection fallback (`new JSONObject(bean)`),
     *  which reflects `PyFloat`'s public `getValue()` getter and produces
     *  the nested object `{"value": 1752900000.5}` instead of the bare
     *  number `1752900000.5`. Fix: unwrap to `.value` (a plain Double)
     *  before it ever reaches `JSONObject`.
     *
     *  Both corruptions are silent (no exception at write time) and, for a
     *  payload round-tripped back out through `pendingOutbound()` for
     *  RE-SIGNING VERIFICATION (the outbound task), change the canonical
     *  bytes hashed at sign time -- breaking `msgId()` equality with the
     *  originally-signed message and, more importantly, the RECEIVING
     *  NODE's device-signature check on the pushed message (a corrupted
     *  `created_at` shape also breaks `postAad`/`dmAad` reconstruction,
     *  since those key off `payload["created_at"]` being a Number).
     *  `pendingOutbound()` is the first msg_json consumer that needs
     *  byte-exact round-tripping -- every other accessor only ever reads
     *  individual fields for display/filtering, never re-derives signed
     *  bytes -- which is why this gap went unnoticed until now. Applied
     *  unconditionally in `serialize()` (not just for Compose-authored
     *  messages): messages parsed off the wire never carry a `PyFloat`
     *  (KotlinSync's own bridge already normalizes to plain Double before
     *  `ingestMessage` ever sees them), so this is a no-op for them, and
     *  running it uniformly is simpler than threading an origin flag
     *  through `ingestMessage`. */
    private fun jsonSafe(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is KotlinWire.PyFloat -> v.value
        is Map<*, *> -> v.entries.associate { (k, vv) -> (k as String) to jsonSafe(vv) }
        is List<*> -> v.map { jsonSafe(it) }
        else -> v
    }

    /** Blob hashes referenced by stored POST/DM/STORY/PROFILE payloads, minus
     *  what we hold. Mirrors InMemorySyncStore.missingBlobs (hearth.store.
     *  referenced_blobs for the KIND_POST/KIND_DM fields -- blobs list +
     *  poster str + thumbs list, junk-guarded to strings), WIDENED (B.2d-3
     *  Task 1) to also scan `story` rows and (vp3 profile-blob fix) `profile`
     *  rows for avatar/banner, but reads the refs back out of the stored
     *  msg_json instead of an in-memory SignedMessage.
     *
     *  `kind` is selected alongside `msg_json` so the `media` extraction
     *  below can be guarded per-row: a story's `media` is a blob hash, but
     *  a post/dm's `media` is the "photo"/"video" DISCRIMINATOR -- reading
     *  it unconditionally would add that literal string to missingBlobs as
     *  a bogus hash (the field-shape trap). `poster` is already extracted
     *  generically across kinds, so adding "story" to the IN-clause makes a
     *  story's poster flow through it for free -- no extra guard needed
     *  there. */
    override fun missingBlobs(): List<String> {
        val refs = linkedSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT kind, msg_json FROM messages WHERE kind IN ('post', 'dm', 'story', 'profile')", null
        ).use { c ->
            while (c.moveToNext()) {
                val kind = c.getString(0)
                val payload = JSONObject(c.getString(1)).optJSONObject("payload") ?: continue
                (payload.opt("blobs") as? JSONArray)?.let { arr ->
                    for (i in 0 until arr.length()) (arr.opt(i) as? String)?.let { refs.add(it) }
                }
                (payload.opt("poster") as? String)?.let { if (it.isNotEmpty()) refs.add(it) }
                (payload.opt("thumbs") as? JSONArray)?.let { arr ->
                    for (i in 0 until arr.length()) (arr.opt(i) as? String)?.let { refs.add(it) }
                }
                if (kind == "story")
                    (payload.opt("media") as? String)?.let { if (it.isNotEmpty()) refs.add(it) }
                // vp3 profile-blob fix: a KIND_PROFILE's avatar/banner are
                // blob-hash references (hearth referenced_blobs scans
                // KIND_PROFILE for exactly these); without this the profile
                // header images never sync and render broken.
                if (kind == "profile")
                    for (f in listOf("avatar", "banner"))
                        (payload.opt(f) as? String)?.let { if (it.isNotEmpty()) refs.add(it) }
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

    /** Read counterpart to putBlob (Task 4, B.2d) -- see the SyncStore
     *  interface doc for why a missing hash returns null rather than
     *  throwing. */
    // Android's SQLite CursorWindow caps a single fetched row at ~2 MiB, so
    // `SELECT data ...` THROWS "Row too big to fit into CursorWindow" for any
    // blob over that size -- exactly the profile banner (2.07 MiB) and large
    // photos (up to the 10 MiB blob cap). Read the length first, then pull the
    // BLOB in <=1 MiB slices via SQL substr (1-indexed) so no single row ever
    // exceeds the window, and stitch them back together.
    override fun getBlob(hash: String): ByteArray? {
        val db = readableDatabase
        val len = db.rawQuery(
            "SELECT length(data) FROM blobs WHERE hash = ? LIMIT 1", arrayOf(hash)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else return null }
        val out = ByteArray(len)
        var off = 0
        val chunk = 1024 * 1024   // 1 MiB, comfortably under the CursorWindow limit
        while (off < len) {
            val n = minOf(chunk, len - off)
            val slice = db.rawQuery(
                "SELECT substr(data, ?, ?) FROM blobs WHERE hash = ? LIMIT 1",
                arrayOf((off + 1).toString(), n.toString(), hash)
            ).use { c -> if (c.moveToFirst()) c.getBlob(0) else return null }
            System.arraycopy(slice, 0, out, off, slice.size)
            off += slice.size
        }
        return out
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

    /** See the SyncStore interface doc: identity_pub -> the latest stored
     *  KIND_PROFILE message's `name`, by (created_at, seq). Reads msg_json
     *  the same way missingBlobs()/wrapGrantsFor() above do (no dedicated
     *  columns for a profile message's payload fields) -- `seq` IS a real
     *  column here (unlike wrapGrantsFor's target, which isn't), so it's
     *  read directly rather than decoded from JSON. */
    override fun profileNames(): Map<String, String> {
        data class Candidate(val createdAt: Double, val seq: Int, val name: String)
        val best = linkedMapOf<String, Candidate>()
        readableDatabase.rawQuery(
            "SELECT identity_pub, seq, msg_json FROM messages WHERE kind = ?", arrayOf("profile")
        ).use { c ->
            while (c.moveToNext()) {
                val identityPub = c.getString(0)
                val seq = c.getInt(1)
                val payload = JSONObject(c.getString(2)).optJSONObject("payload") ?: continue
                // Blank stored names are treated as absent -- see
                // InMemorySyncStore.profileNames' matching comment.
                val name = (payload.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: continue
                val createdAt = payload.optDouble("created_at", 0.0)
                val cur = best[identityPub]
                if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && seq > cur.seq))
                    best[identityPub] = Candidate(createdAt, seq, name)
            }
        }
        return best.mapValues { it.value.name }
    }

    /** See the SyncStore interface doc: device_pub -> enc_pub over
     *  `identityPub`'s KIND_ENCKEY messages, latest-wins per device by
     *  (created_at, seq). Reads msg_json + the real `device_pub`/`seq`
     *  columns the same way profileNames() above does; identity_pub and
     *  kind are filtered in SQL. */
    override fun enckeys(identityPub: String): Map<String, String> {
        data class Cand(val createdAt: Double, val seq: Int, val encPub: String)
        val best = linkedMapOf<String, Cand>()
        readableDatabase.rawQuery(
            "SELECT device_pub, seq, msg_json FROM messages WHERE kind = ? AND identity_pub = ?",
            arrayOf("enckey", identityPub)
        ).use { c ->
            while (c.moveToNext()) {
                val dev = c.getString(0); val seq = c.getInt(1)
                val payload = JSONObject(c.getString(2)).optJSONObject("payload") ?: continue
                val enc = payload.opt("enc_pub") as? String ?: continue
                val ca = (payload.opt("created_at") as? Number)?.toDouble() ?: continue
                val cur = best[dev]
                if (cur == null || ca > cur.createdAt || (ca == cur.createdAt && seq > cur.seq))
                    best[dev] = Cand(ca, seq, enc)
            }
        }
        return best.mapValues { it.value.encPub }
    }

    /** Distinct device_pubs of `identity`'s stored (verified-at-ingest)
     *  messages (B.2d-4 Task 2) -- the SQLite mirror of InMemorySyncStore.
     *  deviceViews and of hearth's store.load_views; see the SyncStore
     *  interface doc for why this is the device-binding source and why an
     *  empty result is permissive at the caller. `device_pub` is a real
     *  indexed column (idx_messages_idp_dp_seq), so this is a direct query,
     *  not a JSON scan. */
    override fun deviceViews(identity: String): Set<String> {
        val out = linkedSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT device_pub FROM messages WHERE identity_pub = ?", arrayOf(identity)
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /** Unexpired KIND_STORY rows (B.2d-3 Task 1) -- see the SyncStore
     *  interface doc for the strict `expires_at > nowSeconds` filter (a row
     *  with expires_at == nowSeconds is expired, not active). Reads
     *  msg_json the same way missingBlobs()/wrapGrantsFor()/profileNames()
     *  above do; `identity_pub` IS a real column, read directly as
     *  `author` rather than re-parsed from JSON (a story payload carries no
     *  author field of its own). Sorted newest-first by created_at in
     *  application code (no index needed -- this scans only `story` rows,
     *  a small, TTL-bounded set). */
    override fun activeStories(nowSeconds: Double): List<StoredStory> {
        val out = mutableListOf<StoredStory>()
        readableDatabase.rawQuery(
            "SELECT msg_id, identity_pub, msg_json FROM messages WHERE kind = ?", arrayOf("story")
        ).use { c ->
            while (c.moveToNext()) {
                val msgId = c.getString(0)
                val author = c.getString(1)
                val payload = JSONObject(c.getString(2)).optJSONObject("payload") ?: continue
                if (!payload.has("expires_at")) continue
                val expiresAt = payload.optDouble("expires_at", Double.NEGATIVE_INFINITY)
                if (expiresAt <= nowSeconds) continue
                val mediaKind = payload.opt("media_kind") as? String ?: continue
                val media = payload.opt("media") as? String ?: continue
                out.add(StoredStory(
                    msgId = msgId, author = author, mediaKind = mediaKind, media = media,
                    poster = payload.opt("poster") as? String,
                    caption = (payload.opt("caption") as? String) ?: "",
                    createdAt = payload.optDouble("created_at", 0.0)))
            }
        }
        return out.sortedByDescending { it.createdAt }
    }

    /** See the SyncStore interface doc: latest KIND_PROFILE payload by
     *  (created_at, seq). Reads msg_json + the real `seq` column the same way
     *  profileNames() above does; identity_pub is filtered in SQL. */
    override fun profileRecord(identityPub: String): Map<String, Any?>? {
        data class Cand(val createdAt: Double, val seq: Int, val payload: Map<String, Any?>)
        var best: Cand? = null
        readableDatabase.rawQuery(
            "SELECT seq, msg_json FROM messages WHERE kind = ? AND identity_pub = ?",
            arrayOf("profile", identityPub)
        ).use { c ->
            while (c.moveToNext()) {
                val seq = c.getInt(0)
                val payload = JSONObject(c.getString(1)).optJSONObject("payload") ?: continue
                val createdAt = payload.optDouble("created_at", 0.0)
                val cur = best
                if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && seq > cur.seq))
                    best = Cand(createdAt, seq, jsonToMap(payload))
            }
        }
        return best?.payload
    }

    /** Latest KIND_PROFILE_LAYOUT by (created_at, seq), reduced to
     *  pins/spans/sizes/texts (order/grids dropped). Empty maps when none. */
    override fun profileLayout(identityPub: String): ProfileLayout {
        data class Cand(val createdAt: Double, val seq: Int, val payload: Map<String, Any?>)
        var best: Cand? = null
        readableDatabase.rawQuery(
            "SELECT seq, msg_json FROM messages WHERE kind = ? AND identity_pub = ?",
            arrayOf("profile_layout", identityPub)
        ).use { c ->
            while (c.moveToNext()) {
                val seq = c.getInt(0)
                val payload = JSONObject(c.getString(1)).optJSONObject("payload") ?: continue
                val createdAt = payload.optDouble("created_at", 0.0)
                val cur = best
                if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && seq > cur.seq))
                    best = Cand(createdAt, seq, jsonToMap(payload))
            }
        }
        val p = best?.payload ?: return ProfileLayout(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        return ProfileLayout(
            pins = layoutSubMaps(p["pins"]), spans = layoutSubMaps(p["spans"]),
            sizes = layoutSizes(p["sizes"]), texts = layoutSubMaps(p["texts"]))
    }

    /** album_id -> members for `identityPub`, latest-wins PER album_id by
     *  (created_at, seq). Same per-key newest fold pattern as wrapGrantsFor,
     *  keyed by album_id. */
    override fun albums(identityPub: String): Map<String, List<String>> {
        data class Cand(val createdAt: Double, val seq: Int, val members: List<String>)
        val best = linkedMapOf<String, Cand>()
        readableDatabase.rawQuery(
            "SELECT seq, msg_json FROM messages WHERE kind = ? AND identity_pub = ?",
            arrayOf("album", identityPub)
        ).use { c ->
            while (c.moveToNext()) {
                val seq = c.getInt(0)
                val payload = JSONObject(c.getString(1)).optJSONObject("payload") ?: continue
                val albumId = payload.opt("album_id") as? String ?: continue
                val membersArr = payload.optJSONArray("members") ?: continue
                val members = (0 until membersArr.length()).mapNotNull { membersArr.opt(it) as? String }
                val createdAt = payload.optDouble("created_at", 0.0)
                val cur = best[albumId]
                if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && seq > cur.seq))
                    best[albumId] = Cand(createdAt, seq, members)
            }
        }
        return best.mapValues { it.value.members }
    }
}
