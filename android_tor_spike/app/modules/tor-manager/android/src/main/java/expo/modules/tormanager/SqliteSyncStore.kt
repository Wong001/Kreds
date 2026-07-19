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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS identities")
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS blobs")
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
}
