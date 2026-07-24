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
        // outbound review wave (FIX 3): DELIBERATELY NOT bumped for the
        // pending_outbound table. onUpgrade (below) is DESTRUCTIVE -- it
        // DROPs identities/messages/blobs unconditionally -- so bumping
        // DB_VERSION here would wipe every existing device's synced store
        // the moment it updates, just to add one new table. Same fix as the
        // B.2 `keys`-table field bug (see onOpen's comment): ensure the
        // table via onOpen's IF NOT EXISTS, which runs on every open
        // regardless of version and is a no-op once the table exists. This
        // stays non-destructive on both fresh installs (onCreate) and
        // existing DBs (onOpen), and DB_VERSION sitting at 1 means onUpgrade
        // still has never actually run on a real device.
        private const val DB_VERSION = 1
        // Task 3 (B.2): this device's own X25519 enc keypair, keyed enc_priv/enc_pub.
        // Plaintext at rest -- accepted posture, matches desktop (see EncKeys.kt).
        // Shared by onCreate (fresh DBs) and onOpen (existing B.1-era DBs --
        // see onOpen below for why the latter is needed) so the two sites
        // can't drift out of sync.
        private const val CREATE_KEYS_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS keys (k TEXT PRIMARY KEY, v TEXT)"
        // outbound review wave (FIX 1): the pending-outbound push queue now
        // stores its OWN canonical wire_json per row, not just a msg_id that
        // joins back to `messages`. `messages.msg_json` is written via
        // MsgJson.serialize()/org.json, which is lossy for round-tripping an
        // INTEGRAL Double (e.g. created_at=150.0 reads back as Kotlin Int
        // 150 -- KotlinWire.dumps then renders "150", not "150.0", a
        // canonical-byte mismatch the receiving node's device-signature
        // check rejects). `wire_json` here is instead `KotlinWire.dumps(
        // wireDict)` at ENQUEUE time -- the SAME canonical serializer
        // `writeFrameBytes` uses on the wire -- so re-send is byte-exact by
        // construction, for any magnitude, independent of `messages`'
        // storage format. IF NOT EXISTS so onCreate (fresh DBs) and onOpen
        // (existing DBs -- see onOpen below) share one definition without
        // either site needing to know which case it's in -- same pattern as
        // CREATE_KEYS_TABLE_SQL above.
        private const val CREATE_PENDING_OUTBOUND_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS pending_outbound (msg_id TEXT PRIMARY KEY, wire_json TEXT NOT NULL)"
        // phone-onion-reachability Task 2: arbitrary key/value store, mirrors
        // hearth store.py's `meta` table (store.py:121-133, set_meta/get_meta).
        // Same IF NOT EXISTS / onCreate+onOpen pattern as keys/pending_outbound
        // above -- non-destructive, no DB_VERSION bump (see that comment for
        // why a bump would be unacceptable here).
        private const val CREATE_META_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)"
        // phone-onion-reachability Task 2: the per-device revoked set
        // (markRevoked/isRevokedDevice) -- one row per revoked device_pub,
        // holding the last_valid_seq threshold markRevoked's retro-drop
        // already applied at write time (mirrors the boundary hearth's
        // ingest_revocation retro-drop uses, store.py:421-427). Same
        // non-destructive IF NOT EXISTS pattern as the tables above.
        private const val CREATE_REVOKED_DEVICES_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS revoked_devices (device_pub TEXT PRIMARY KEY, last_valid_seq INTEGER NOT NULL)"
        // friend-peering Task 1: peer addresses this device knows how to
        // dial, mirrors hearth store.py's `peers` table (store.py:39-40:
        // address TEXT PRIMARY KEY, identity_pub TEXT). Same non-destructive
        // IF NOT EXISTS / onCreate+onOpen pattern as keys/pending_outbound/
        // meta/revoked_devices above -- no DB_VERSION bump (see the
        // DB_VERSION comment for why a bump would be unacceptable here).
        private const val CREATE_PEERS_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS peers (address TEXT PRIMARY KEY, identity_pub TEXT)"

        /** Task 6 (phone-onion-reachability): revocation wipe -- drops the
         *  WHOLE on-disk sync_store.db (identities/messages/blobs/keys --
         *  INCLUDING the enc keypair, EncKeys.getOrCreate's persisted
         *  enc_priv/enc_pub, which lives in this DB's `keys` table --
         *  /pending_outbound/meta/revoked_devices), not a per-table clear.
         *  `Context.deleteDatabase`, not a bare `File.delete` on the .db
         *  path alone: it also removes the SQLite journal siblings
         *  (-wal/-shm/-journal, depending on journal mode) that can sit
         *  next to the main file, so no fragment of the wiped store is left
         *  behind. Idempotent: deleteDatabase on an already-absent DB
         *  simply returns false, never throws -- a second wipe (e.g. a
         *  second SelfRevoked observed after the first already deleted it)
         *  is a safe no-op. Any already-open SqliteSyncStore/
         *  SQLiteOpenHelper connection (e.g. TorNodeService's own
         *  long-lived gossipStore) is unaffected mid-call -- Android
         *  (Linux) allows unlinking a file an existing handle still has
         *  open; that handle's data is freed once it eventually closes, and
         *  every NEW SqliteSyncStore(ctx) opened after this call gets a
         *  fresh, empty database (onCreate reruns, since the file is
         *  gone). No separate on-disk blob cache exists to clear beyond
         *  this DB -- blob bytes live in its `blobs` table only
         *  (getBlobImage/MediaServer resolve straight from the DB into
         *  memory; nothing is ever written back out to a filesystem
         *  cache). */
        fun wipe(ctx: Context) {
            ctx.deleteDatabase(DB_NAME)
        }
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
        // Same IF NOT EXISTS reasoning as keys/pending_outbound above --
        // also deliberately not in onUpgrade's drop list (see onUpgrade
        // comment): meta/revoked_devices hold durable bookkeeping (onion-
        // reachability meta, revocation enforcement state) that a schema
        // bump must not silently wipe, same as keys' enc-key material.
        db.execSQL(CREATE_META_TABLE_SQL)
        db.execSQL(CREATE_REVOKED_DEVICES_TABLE_SQL)
        // Same IF NOT EXISTS reasoning as keys/pending_outbound/meta/
        // revoked_devices above -- also deliberately not in onUpgrade's
        // drop list (see onUpgrade comment): peers holds durable dial-
        // address bookkeeping that a schema bump must not silently wipe.
        db.execSQL(CREATE_PEERS_TABLE_SQL)
    }

    // Field bug (G20, B.2 live run): DB_VERSION was never bumped past 1, so
    // an existing B.1-era phone DB (created before the `keys` table existed)
    // never runs onCreate OR onUpgrade -- it just opens straight through,
    // and every keys-table query fails with "no such table: keys". onOpen
    // runs on every open regardless of version, so ensure the table here too;
    // IF NOT EXISTS makes this a no-op on DBs that already have it.
    //
    // outbound review wave (FIX 3): pending_outbound follows the SAME
    // pattern, for the SAME reason -- ensured here instead of via a
    // DB_VERSION bump (see the companion object's DB_VERSION comment for why
    // a bump is unacceptable: onUpgrade is destructive). This makes an
    // existing device's DB gain the table non-destructively on its next open,
    // no migration required.
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL(CREATE_KEYS_TABLE_SQL)
        db.execSQL(CREATE_PENDING_OUTBOUND_TABLE_SQL)
        db.execSQL(CREATE_META_TABLE_SQL)
        db.execSQL(CREATE_REVOKED_DEVICES_TABLE_SQL)
        db.execSQL(CREATE_PEERS_TABLE_SQL)
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
        // Since the review wave's FIX 1, its rows are SELF-CONTAINED
        // (`wire_json` holds the full canonical wire dict, not just a msg_id
        // that joins back to `messages` -- see addPendingOutbound's doc), so
        // a row here actually SURVIVES the DROP above with its content
        // intact, unlike the old msg_id-only design (where an orphaned row
        // would have gone silently unresolvable). This is presently moot in
        // practice -- DB_VERSION sits at 1 (see the companion object's
        // comment), so onUpgrade is not known to have ever run on a real
        // device -- but if it ever does, a queued-but-unsynced compose still
        // gets pushed on the next sync rather than silently lost. onCreate's
        // IF NOT EXISTS below is a no-op here since the table already exists.
        //
        // `meta`/`revoked_devices` (phone-onion-reachability Task 2) are likewise
        // NOT dropped here, for the same reason as `keys`: meta holds durable
        // bookkeeping and revoked_devices holds revocation-enforcement state,
        // neither of which resyncs from the home node the way identities/
        // messages/blobs do -- dropping revoked_devices in particular would
        // silently un-revoke a device until the next revocation re-arrives.
        //
        // `peers` (friend-peering Task 1) is likewise NOT dropped here: it
        // holds durable dial-address bookkeeping with no server-side resync
        // path at all (unlike identities/messages/blobs, which the next
        // sync repopulates) -- dropping it would silently strand this
        // device with no way to reach an already-paired friend.
        onCreate(db)
    }

    // Defensive: SQLiteOpenHelper's DEFAULT onDowngrade throws, crashing every
    // store open on a version skew (e.g. a DB_VERSION briefly bumped then
    // reverted during development). The satellite store resyncs from the home
    // node and ensures all tables in onOpen (CREATE TABLE IF NOT EXISTS), so a
    // downgrade needs no destructive action -- tolerate it silently rather than
    // crash. (A real forward migration still uses onUpgrade.)
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // no-op: onOpen re-ensures the schema
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

    /** See the SyncStore interface doc: mirrors hearth store.py:162
     *  remove_identity's plain `DELETE FROM identities WHERE
     *  identity_pub=?`. `SQLiteDatabase.delete` is used (not a raw execSQL)
     *  purely for terseness -- its return value (rows affected) isn't
     *  needed here, unlike purgeAuthoredBy below. */
    override fun removeIdentity(id: String) {
        writableDatabase.delete("identities", "identity_pub = ?", arrayOf(id))
    }

    /** See the SyncStore interface doc: mirrors hearth store.py:1036
     *  purge_authored_by's `DELETE FROM messages WHERE identity_pub=?`,
     *  including its exact semantics of returning the affected row count
     *  (hearth returns `cur.rowcount`; `SQLiteDatabase.delete` returns the
     *  same "rows affected" count directly). hearth additionally cleans its
     *  `dm_keys`/`undecryptable` side tables for the deleted msg_ids -- this
     *  schema has neither table, so there is nothing further to clean. */
    override fun purgeAuthoredBy(id: String): Int {
        return writableDatabase.delete("messages", "identity_pub = ?", arrayOf(id))
    }

    /** See the SyncStore interface doc: records `devicePub` in
     *  `revoked_devices` (INSERT OR REPLACE -- a later call for the same
     *  device overwrites its lastValidSeq rather than erroring or
     *  duplicating), then retro-drops that device's messages with `seq >
     *  lastValidSeq` -- the exact boundary of hearth's ingest_revocation
     *  retro-drop query (store.py:421-427: `WHERE device_pub=? AND
     *  seq>?`). Deletes outright rather than tombstoning, same posture and
     *  reasoning as purgeAuthoredBy above (no tombstones table here). */
    override fun markRevoked(devicePub: String, lastValidSeq: Int) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("device_pub", devicePub)
            put("last_valid_seq", lastValidSeq)
        }
        db.insertWithOnConflict("revoked_devices", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        db.delete("messages", "device_pub = ? AND seq > ?", arrayOf(devicePub, lastValidSeq.toString()))
    }

    /** See the SyncStore interface doc: a pure membership check against
     *  `revoked_devices`, mirroring InMemorySyncStore's `devicePub in
     *  revokedDevices` -- no seq comparison here (that gate already ran,
     *  once, inside markRevoked's retro-drop above). */
    override fun isRevokedDevice(devicePub: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM revoked_devices WHERE device_pub = ? LIMIT 1", arrayOf(devicePub)
        ).use { it.moveToFirst() }

    /** See the SyncStore interface doc: mirrors hearth store.py's
     *  get_meta/set_meta over the `meta` k/v table (store.py:123-133) --
     *  same INSERT OR REPLACE-on-write, SELECT-or-null-on-read shape as
     *  hearth's own `INSERT OR REPLACE INTO meta VALUES(?,?)` /
     *  `SELECT v FROM meta WHERE k=?`. */
    override fun getMeta(k: String): String? =
        readableDatabase.rawQuery(
            "SELECT v FROM meta WHERE k = ? LIMIT 1", arrayOf(k)
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun setMeta(k: String, v: String) {
        val cv = ContentValues().apply { put("k", k); put("v", v) }
        writableDatabase.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // -- friend-peering Task 1: peer table (addPeer/listPeers/removePeer/
    //    addressFor) -- mirrors hearth store.py's peers table (schema
    //    store.py:39-40; add_peer store.py:217-221; list_peers store.py:
    //    223-227; remove_peer store.py:229-232; address_for store.py:
    //    234-239).

    /** See the SyncStore interface doc: INSERT OR REPLACE keyed on
     *  `address` (the peers table's PRIMARY KEY), same CONFLICT_REPLACE
     *  pattern as setMeta/markRevoked above -- a second addPeer for the
     *  same address overwrites its identityPub rather than duplicating. */
    override fun addPeer(address: String, identityPub: String?) {
        val cv = ContentValues().apply {
            put("address", address)
            put("identity_pub", identityPub)
        }
        writableDatabase.insertWithOnConflict("peers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** See the SyncStore interface doc: every stored peer, mirroring hearth
     *  store.py:223-227 list_peers's `SELECT address, identity_pub FROM
     *  peers` (no ORDER BY -- order is unspecified here too). */
    override fun listPeers(): List<Peer> {
        val out = mutableListOf<Peer>()
        readableDatabase.rawQuery("SELECT address, identity_pub FROM peers", null).use { c ->
            while (c.moveToNext()) out.add(Peer(c.getString(0), c.getString(1)))
        }
        return out
    }

    override fun removePeer(address: String) {
        writableDatabase.delete("peers", "address = ?", arrayOf(address))
    }

    /** See the SyncStore interface doc: mirrors hearth store.py:234-239
     *  address_for's `SELECT address FROM peers WHERE identity_pub=?` +
     *  `fetchone()` -- LIMIT 1 gives the same first-match-or-null shape. */
    override fun addressFor(identityPub: String): String? =
        readableDatabase.rawQuery(
            "SELECT address FROM peers WHERE identity_pub = ? LIMIT 1", arrayOf(identityPub)
        ).use { if (it.moveToFirst()) it.getString(0) else null }

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
            put("msg_json", MsgJson.serialize(m))
        }
        db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        return true
    }

    /** See the SyncStore interface doc (FIX 1): `wire_json` is
     *  `KotlinWire.dumps(wireDict)` -- the CANONICAL wire serializer, the
     *  SAME one `writeFrameBytes` uses to actually send a message -- NOT
     *  `MsgJson.serialize()`/org.json (what `messages.msg_json` uses below).
     *  That distinction is the whole point: canonical JSON keeps an
     *  integral Double's decimal (e.g. "150.0"), so org.json parses it back
     *  as BigDecimal (not Int) when this row is later read via
     *  `pendingOutbound()`, `MsgJson`'s unwrap normalizes BigDecimal->Double,
     *  and `KotlinWire.dumps` re-renders "150.0" -- byte-exact for ANY
     *  magnitude, not just the large-magnitude values that happen to dodge
     *  msg_json's Int-truncation bug. CONFLICT_IGNORE keeps this idempotent:
     *  a re-queue of an already-pending msgId is a no-op (same signed
     *  message -> same wireDict -> no need to overwrite). */
    override fun addPendingOutbound(msgId: String, wireDict: Map<String, Any?>) {
        val cv = ContentValues().apply {
            put("msg_id", msgId)
            put("wire_json", KotlinWire.dumps(wireDict))
        }
        writableDatabase.insertWithOnConflict(
            "pending_outbound", null, cv, SQLiteDatabase.CONFLICT_IGNORE)  // idempotent
    }

    /** See the SyncStore interface doc: the wire dicts of every queued
     *  message, read directly off `pending_outbound.wire_json` (see
     *  `addPendingOutbound`'s doc for why this is the canonical form, not a
     *  join back to `messages.msg_json`) and parsed via `MsgJson.toMap` --
     *  the same org.json Map bridge `allMessages()`/etc. use below, just
     *  applied to the WHOLE parsed object (not a `payload` sub-object),
     *  since `wire_json`'s top-level shape already IS `{cert, seq, payload,
     *  signature}` -- exactly what `pendingOutbound()` must return, no
     *  further reshaping needed. Ordered by `rowid` (insertion order),
     *  matching InMemorySyncStore's LinkedHashMap insertion-order
     *  guarantee. */
    override fun pendingOutbound(): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        readableDatabase.rawQuery(
            "SELECT wire_json FROM pending_outbound ORDER BY rowid", null
        ).use { c ->
            while (c.moveToNext()) out.add(MsgJson.toMap(c.getString(0)))
        }
        return out
    }

    override fun clearPendingOutbound(msgIds: List<String>) {
        if (msgIds.isEmpty()) return
        val placeholders = msgIds.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "DELETE FROM pending_outbound WHERE msg_id IN ($placeholders)", msgIds.toTypedArray())
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
                out.add(StoredMsg(c.getString(0), c.getString(2) ?: "", c.getString(1) ?: "", MsgJson.jsonToMap(payload)))
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
                rows.add(Row(payload.optDouble("created_at", 0.0), seq, MsgJson.jsonToMap(wraps)))
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
                    best = Cand(createdAt, seq, MsgJson.jsonToMap(payload))
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
                    best = Cand(createdAt, seq, MsgJson.jsonToMap(payload))
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

    override fun messageById(msgId: String): SignedMessage? {
        val msgJson = readableDatabase.rawQuery(
            "SELECT msg_json FROM messages WHERE msg_id = ? LIMIT 1", arrayOf(msgId)
        ).use { c ->
            if (c.moveToFirst()) c.getString(0) else return null
        }
        return SignedMessageKt.fromDict(MsgJson.toMap(msgJson))
    }

    /** See the SyncStore interface doc: the SQLite mirror of
     *  InMemorySyncStore.messagesNotIn, differing only in how `GossipRow`s
     *  are built (SQL scan + msg_json parse here, vs. native in-memory
     *  SignedMessage fields there) -- the actual entitlement algorithm is
     *  the shared `filterMessagesNotIn`, so the two impls cannot diverge on
     *  this security-critical path. `msg_json` is parsed once per row (a
     *  single `JSONObject(msgJson)`) and reused for both the row's `payload`
     *  sub-map (`filterMessagesNotIn`'s gates) and its full dict (`raw`,
     *  reconstructed via `SignedMessageKt.fromDict`) -- avoids the
     *  double-parse a naive `MsgJson.toMap(msgJson)` + separate
     *  `optJSONObject("payload")` re-read would cost per row. */
    override fun messagesNotIn(
        summaries: Map<Pair<String, String>, SeenSet>, entitled: Set<String>, peerIdentity: String
    ): List<SignedMessage> {
        val rows = mutableListOf<GossipRow>()
        readableDatabase.rawQuery(
            "SELECT msg_id, identity_pub, device_pub, seq, kind, msg_json FROM messages ORDER BY seq ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                val msgId = c.getString(0)
                val ipub = c.getString(1)
                val dpub = c.getString(2)
                val seq = c.getInt(3)
                val kind = c.getString(4) ?: ""
                val msgJson = c.getString(5)
                val obj = JSONObject(msgJson)
                val payload = MsgJson.jsonToMap(obj.optJSONObject("payload") ?: JSONObject())
                rows.add(GossipRow(msgId, ipub, dpub, seq, kind, payload, SignedMessageKt.fromDict(MsgJson.jsonToMap(obj))))
            }
        }
        return filterMessagesNotIn(rows, summaries, entitled, peerIdentity, deviceViews(peerIdentity))
    }

    /** See the SyncStore interface doc: byte length of each stored blob in
     *  `hashes`, via SQL `length(data)` (no full-blob read -- avoids the
     *  CursorWindow concern `getBlob` guards against, since only an
     *  aggregate length is fetched here, never row data). Empty `hashes`
     *  returns without touching SQL -- same `IN ()`-is-invalid-syntax
     *  reasoning as `wrapGrantsFor`'s empty-`acceptedSigners` guard. */
    override fun blobSizes(hashes: List<String>): Map<String, Long> {
        if (hashes.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, Long>()
        val placeholders = hashes.joinToString(",") { "?" }
        readableDatabase.rawQuery(
            "SELECT hash, length(data) FROM blobs WHERE hash IN ($placeholders)", hashes.toTypedArray()
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getLong(1) }
        return out
    }
}
