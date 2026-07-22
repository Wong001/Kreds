package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject

/** Pure JVM (no Android/DB dependency) Map<->JSON bridge -- extracted out of
 *  `SqliteSyncStore` (outbound review wave, FIX 2) so the write-side
 *  (`serialize`/`jsonSafe`) and read-side (`toMap`/`jsonToMap`/`unwrapJson`)
 *  can be unit-tested directly on a plain JVM (`MsgJsonTest`), with no
 *  Robolectric dependency -- `SqliteSyncStore` itself cannot be instantiated
 *  in a plain JVM test (`SQLiteOpenHelper`'s Android stub throws), which is
 *  why this logic had no direct automated coverage before this extraction.
 *  `SqliteSyncStore` now delegates to this object for both directions; this
 *  file is a pure move, no behavior change. */
object MsgJson {

    /** Re-derives the node's dict shape (cert/seq/payload/signature) from a
     *  parsed SignedMessage and serializes it via org.json -- used for
     *  `messages.msg_json` (EVERY stored message: both messages ingested off
     *  the wire and this device's own composed-and-locally-ingested ones).
     *  `ingestMessage` only ever receives a parsed SignedMessage (KotlinSync
     *  parses frames straight into one), never the node's raw JSON string,
     *  so this is the faithful re-serialization available.
     *
     *  Deliberately NOT `KotlinWire.dumps`: dumps routes any Double (incl.
     *  `cert.enrolled_at`) through `pyFloatRepr`, which THROWS outside a
     *  narrow supported range (|n|>=1e16, <1e-4, or non-finite).
     *  `enrolled_at` is part of the enrollment cert, not covered by THIS
     *  message's device signature -- `verifyDeviceSignature()` never touches
     *  it -- so a validly-signed message from a FRIEND carrying a
     *  hostile/odd `enrolled_at` would throw here, uncaught, and abort
     *  `KotlinSync.run`'s whole session (its top-level catch turns it into
     *  `SyncResult.Failed`), dropping every other good message in the same
     *  sync. `org.json` has no such range restriction, and `msg_json` is
     *  only ever read back via `org.json` (`missingBlobs` etc.), so this
     *  round-trips fine. (Contrast: `SqliteSyncStore.addPendingOutbound`'s
     *  `wire_json` DOES use `KotlinWire.dumps` directly -- that table is
     *  populated ONLY from this device's own `Compose.post`-signed messages,
     *  whose `enrolled_at` is this device's own enrollment cert, never a
     *  friend/hostile one, so the range restriction is not a live risk
     *  there. Two different serializers for two different trust domains.) */
    @Suppress("UNCHECKED_CAST")
    fun serialize(m: SignedMessage): String {
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
     *  Both corruptions are silent (no exception at write time). This fix is
     *  REQUIRED regardless of the pending-outbound queue's own fidelity fix
     *  (FIX 1, which stores its own canonical `wire_json` and no longer
     *  round-trips through `messages.msg_json` at all): without `jsonSafe`,
     *  a composed post's `created_at` would read back as the nested object
     *  `{"value": ...}` instead of a Number, which `DecryptPass` (and
     *  `postAad`/`dmAad` reconstruction, keyed off `payload["created_at"]`
     *  being a Number) cannot read -- so a locally-composed post would
     *  silently VANISH from the composer's OWN feed, not just fail to
     *  re-send. Applied unconditionally in `serialize()` (not just for
     *  Compose-authored messages): messages parsed off the wire never carry
     *  a `PyFloat` (KotlinSync's own bridge already normalizes to plain
     *  Double before `ingestMessage` ever sees them), so this is a no-op for
     *  them, and running it uniformly is simpler than threading an origin
     *  flag through `ingestMessage`. */
    fun jsonSafe(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is KotlinWire.PyFloat -> v.value
        is Map<*, *> -> v.entries.associate { (k, vv) -> (k as String) to jsonSafe(vv) }
        is List<*> -> v.map { jsonSafe(it) }
        else -> v
    }

    /** Parses a full JSON object string (e.g. a stored `msg_json`/
     *  `wire_json` row) into a plain Kotlin Map, recursively unwrapping
     *  nested objects/arrays via `jsonToMap`/`unwrapJson` below -- the
     *  read-side counterpart to `serialize`/`jsonSafe` above. */
    fun toMap(json: String): Map<String, Any?> = jsonToMap(JSONObject(json))

    // org.json -> plain Kotlin bridge (JSONObject -> Map, JSONArray -> List),
    // same idiom as KotlinSync's private toMap/unwrap and DecryptPassTest's
    // (each keeps its own copy -- see KotlinSync.kt's comment on why: values
    // are consumed downstream via Map/List casts -- e.g.
    // KotlinDmcrypt.unwrapKey's `wrap["eph_pub"] as String` -- which throws
    // on a raw org.json type). This copy is `SqliteSyncStore`'s, extracted
    // here so it's usable both on a full JSON string (`toMap`) and on an
    // already-parsed `JSONObject` sub-object (`jsonToMap`, e.g. a `payload`
    // extracted via `optJSONObject`).
    fun jsonToMap(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { unwrapJson(o.get(it)) }

    fun unwrapJson(v: Any?): Any? = when (v) {
        is JSONObject -> jsonToMap(v)
        is JSONArray -> (0 until v.length()).map { unwrapJson(v.get(it)) }
        JSONObject.NULL -> null
        is java.math.BigDecimal -> v.toDouble()
        else -> v
    }
}
