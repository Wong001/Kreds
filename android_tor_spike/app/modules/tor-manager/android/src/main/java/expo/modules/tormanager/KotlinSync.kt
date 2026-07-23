package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject

sealed class SyncResult {
    data class Ok(val messages: Int, val blobs: Int, val identities: Int) : SyncResult()
    object SelfRevoked : SyncResult()
    data class Failed(val stage: String, val reason: String) : SyncResult()
}

private const val MAX_BLOB_BYTES = 10 * 1024 * 1024   // hearth/messages.py:56

/** Give-side per-round byte budget for the BLOBS phase (gossip server Task
 *  3, `serve`) -- hearth's own `BLOB_GIVE_BUDGET = MAX_FRAME - 1024 * 1024`
 *  (sync.py:50): leave the rest for the next sync round rather than trying
 *  to cram every wanted blob into one frame (`KotlinWire.MAX_FRAME` bounds
 *  a single frame; this stays a safety margin under it). NOT previously
 *  present anywhere on the Kotlin side -- `run`'s own BLOBS push (the
 *  initiator direction, Task 8 outbound) has NO frame-size cap at all
 *  today, a known gap flagged in BRICK_OUTBOUND1_REPORT.md ("Push-side
 *  BLOB_GIVE_BUDGET"), left unfixed there and out of THIS task's scope
 *  (`run` stays byte-unchanged -- regression). Declared as a mutable
 *  top-level `internal var`, not a `const val`, specifically so a test can
 *  override it to a small value and force the smallest-first cutoff
 *  deterministically -- mirrors hearth's own test-time override
 *  (`monkeypatch.setattr(sync_mod, "BLOB_GIVE_BUDGET", ...)`,
 *  tests/test_sync_session.py:366), since the production value (~15 MiB)
 *  is impractical to actually exceed inside a JVM unit test. Any test that
 *  reassigns this MUST restore it in a `finally` -- it is a real
 *  process-wide singleton (a top-level Kotlin property compiles to a
 *  static field), not per-test state. */
internal var BLOB_GIVE_BUDGET = KotlinWire.MAX_FRAME - 1024 * 1024

/** RFC-4648 standard-alphabet base64 codec, hand-rolled instead of either
 *  android.util.Base64 (Android-only, breaks the BB-5 JVM desk gate) or
 *  java.util.Base64 (API 26+, this module's minSdkVersion is 24 -- would
 *  NoSuchMethodError on API 24/25 devices, uncaught by assembleDebug since
 *  lintOptions.abortOnError is false). Works on any API level and on a
 *  plain JVM. The node encodes/decodes blobs with Python's
 *  base64.b64encode/b64decode, which uses this same standard alphabet
 *  (with `=` padding) -- `encode` (Task 8, outbound blob push) is the
 *  exact mirror of `decode` below, so round-tripping through either side
 *  (this <-> this, or this <-> hearth's base64.b64encode) is symmetric. */
object Base64Portable {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DEC = IntArray(128) { -1 }.also {
        for (i in ALPHABET.indices) it[ALPHABET[i].code] = i
    }
    fun decode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buf = 0; var bits = 0
        for (c in s) {
            if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue
            val v = if (c.code < 128) DEC[c.code] else -1
            require(v >= 0) { "bad base64 char" }
            buf = (buf shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xff) }
        }
        return out.toByteArray()
    }

    /** Standard-alphabet base64 encode, WITH `=` padding -- byte-for-byte
     *  what Python's base64.b64encode(data).decode() produces (hearth/
     *  sync.py:654), and what `decode` above accepts. Used by the BLOBS
     *  phase (KotlinSync.run) to push held blobs the node wants. */
    fun encode(data: ByteArray): String {
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x3) shl 4) or (b1 shr 4)])
            out.append(if (i + 1 < data.size) ALPHABET[((b1 and 0xf) shl 2) or (b2 shr 6)] else '=')
            out.append(if (i + 2 < data.size) ALPHABET[b2 and 0x3f] else '=')
            i += 3
        }
        return out.toString()
    }
}

/** Runs the post-AUTH sync phases (hearth/sync.py _session) as INITIATOR,
 *  over an already-authenticated Stream. Sends empty revs/notices/blobs, and
 *  (since B.2 Task 4) the caller-supplied `outbound` messages -- for B.2, at
 *  most one: the phone's device-signed enckey (see composeEncKey below).
 *  `outbound` defaults to empty, so a bare pull (B.1's original shape) is
 *  still `run(stream, store, ownDevicePub)`. Always ingests the node's
 *  own-identity messages + blobs.
 *
 *  `ownIdentity` (phone-onion-reachability Task 4): this device's own
 *  identity_pub, consulted by the DEFRIENDS phase's
 *  `store.applyDefriendNotice` (target==own / author!=own gates) AND
 *  (remote-wipe fix, Task 6 review) the REVOCATIONS phase's SelfRevoked
 *  gate -- see that phase's doc comment below for the full exploit/fix
 *  writeup. Defaults to `""` so every pre-existing call site that never
 *  exercises DEFRIENDS/REVOCATIONS content compiles and behaves identically
 *  unchanged -- an empty-string identity can never equal a real notice's
 *  `target_identity` or a real revocation's `identity_pub`, so both gates
 *  simply never match for those callers (a no-op for DEFRIENDS; FAILS
 *  CLOSED, never self-revokes, for REVOCATIONS -- the safe direction for a
 *  caller that omits this). The ONE production call site,
 *  `SyncRunner.runTransport`, passes the real `fx.cert.identity_pub`.
 *
 *  Blocking, like KotlinHandshake.run -- callers (BB-7) must invoke this
 *  off the main thread (Dispatchers.IO), same reason TorEngine's own
 *  send/recv must be. */
object KotlinSync {

    private fun writeFrame(s: Stream, obj: Map<String, Any?>) =
        s.write(KotlinWire.writeFrameBytes(obj))

    // Mirrors KotlinHandshake's private readFrame exactly (4-byte big-endian
    // length prefix, ASCII-only body -- the node always sends ensure_ascii
    // JSON) but over a Stream instead of a bare TorEngine connId, so the
    // same phase logic can run over TorStream (phone) or the desk gate's
    // SocketStream (BB-5).
    private fun readFrame(s: Stream): JSONObject {
        val header = s.readExactSync(4)
        val n = (((header[0].toLong() and 0xff) shl 24) or ((header[1].toLong() and 0xff) shl 16) or
                 ((header[2].toLong() and 0xff) shl 8) or (header[3].toLong() and 0xff))
        require(n <= KotlinWire.MAX_FRAME) { "frame too large" }
        val body = s.readExactSync(n.toInt())
        for (b in body) require((b.toInt() and 0xff) <= 0x7e) { "non-ascii frame byte" }
        return JSONObject(String(body, Charsets.US_ASCII))
    }

    // org.json -> plain Kotlin bridge (JSONObject -> Map, JSONArray -> List).
    // SignedMessageKt.fromDict and SeenSet.fromJson take Map/List, not
    // org.json types -- a raw JSONObject/JSONArray fed to them throws
    // ClassCastException. Needed only on the read path (parsing frames the
    // node sent us); frames we build ourselves are already plain Kotlin
    // (store.summary()/knownIdentities()/missingBlobs() all return Kotlin
    // Map/List), so KotlinWire.writeFrameBytes serializes those directly.
    private fun toMap(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { unwrap(o.get(it)) }

    private fun unwrap(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        JSONObject.NULL -> null
        // BB-5 desk-gate finding: a real node's created_at (time.time(),
        // ~17 significant decimal digits) parses through org.json as
        // java.math.BigDecimal, not Double -- org.json's number parser
        // keeps the exact-value type whenever Java's own Double.toString()
        // doesn't echo the original literal character-for-character (a
        // FORMATTING mismatch, e.g. scientific-notation threshold, the same
        // Java/Python repr() divergence KotlinWire.pyFloatRepr already
        // works around elsewhere -- not an actual precision loss). Every
        // hand-built fixture/vector used a "clean" literal that happens to
        // round-trip through Double.toString() exactly, so this never
        // surfaced before a real node's wall-clock timestamps hit it here.
        // Normalize to a plain Double (not KotlinWire.PyFloat): the SAME
        // parsed value also flows through plain `as Number` casts elsewhere
        // (SignedMessageKt.fromDict reads a message's embedded cert.
        // enrolled_at that way), which a PyFloat wrapper would break.
        // KotlinWire.dumps gained a matching `is Double` case (this same
        // BB-5 fix) so re-serialization (message body/msgId/signature
        // verification) still reproduces the exact canonical bytes the node
        // signed -- BigDecimal.toDouble() is a correctly-rounded decimal ->
        // IEEE754 conversion (same contract java.lang.Double.parseDouble
        // uses), recovering the identical double bit pattern Python's
        // repr() encoded.
        is java.math.BigDecimal -> v.toDouble()
        else -> v
    }

    /** Builds a `SignedMessage.to_dict()`-shaped map for a KIND_ENCKEY payload
     *  ({"kind":"enckey","enc_pub":...,"created_at":...}), signed by this
     *  device -- byte-matches hearth's `messages.make_enckey` +
     *  `identity.DeviceKeys.sign_message` (hearth/messages.py:126-131,
     *  hearth/identity.py:304-316): the canonical signed body is
     *  {"type":"message","protocol":PROTOCOL,"identity_pub":...,
     *  "device_pub":...,"seq":...,"payload":...}, which is exactly what
     *  `SignedMessage.body()` (SignedMessageKt.kt) already builds and
     *  `SignedMessageTest` already vector-proves byte-correct against real
     *  hearth output for other payload kinds (post/dm) -- reused here
     *  (via a throwaway-signature `SignedMessage` + `.copy`) rather than
     *  re-implemented, so this inherits that byte-fidelity proof instead of
     *  risking a second, subtly-divergent construction.
     *
     *  `createdAt` and `seq` are supplied by the caller, not computed here:
     *  the phone needs a real wall-clock reading (`System.currentTimeMillis()
     *  /1000.0`, no `Date.now()`-style call inside this object) and its own
     *  persisted next-seq counter (`SyncStore.nextSeq()`, starts at 1 -- see
     *  that method's doc comment). */
    fun composeEncKey(
        fixture: KotlinHandshake.Fixture,
        encPub: String,
        seq: Int,
        createdAt: Double,
    ): Map<String, Any?> {
        val payload: Map<String, Any?> = mapOf(
            "kind" to "enckey", "enc_pub" to encPub, "created_at" to createdAt,
        )
        val unsigned = SignedMessage(fixture.cert, seq, payload, "")
        val signed = unsigned.copy(signature = KotlinWire.signRaw(fixture.device_priv, unsigned.body()))
        return signed.toDict()
    }

    /** `onProgress` (Task 6, B.2d): purely additive observability -- fired at
     *  the phase boundaries already present below (connecting/handshake/
     *  messages/blobs/decrypting) so a caller (the module) can surface live
     *  sync status while the ~1-2 min sync runs. Defaults to a no-op so
     *  every pre-existing caller/test (KotlinSyncTest, SyncLoopbackTest's
     *  earlier tests) compiles and behaves identically without passing it.
     *  Never affects control flow -- the phases/order/data below are
     *  unchanged; this only observes them. The trailing "done" phase is
     *  emitted by the MODULE after DecryptPass, not here (this object has
     *  no knowledge of DecryptPass).
     *
     *  Every call site below goes through the local `progress` wrapper, NOT
     *  `onProgress` directly (code review fix): this whole function body
     *  runs inside a `try` whose `catch (e: Exception)` turns ANY thrown
     *  exception into `SyncResult.Failed("io", ...)` -- in production,
     *  `onProgress` is the module's `{ phase, count -> sendEvent(...) }`
     *  (TorManagerModule.syncNow), and `sendEvent` reaching across a
     *  possible lifecycle/teardown race on a 1-2 min background sync is
     *  exactly the kind of thing that can throw for reasons that have
     *  nothing to do with whether the sync itself succeeded. A side
     *  channel (observability) must never be able to flip the main
     *  channel's (sync correctness) result -- `progress` swallows so an
     *  `onProgress` failure can never surface as a false sync failure. */
    fun run(stream: Stream, store: SyncStore, ownDevicePub: String,
            outbound: List<Map<String, Any?>> = emptyList(),
            onProgress: (phase: String, count: Int) -> Unit = { _, _ -> },
            ownIdentity: String = ""): SyncResult {
        fun progress(phase: String, count: Int) {
            try { onProgress(phase, count) } catch (_: Throwable) {}
        }
        try {
            progress("connecting", 0)
            // -- REVOCATIONS -- (initiator writes then reads). Own list is
            // always empty (the phone authors none). Each peer revocation is
            // parsed + ingested via store.ingestRevocation (Task 3: is_known
            // + verify() + markRevoked, which performs the retro-drop) --
            // this marks FRIEND devices revoked, independent of the
            // SelfRevoked gate just below.
            //
            // SECURITY (remote-wipe fix, phone-onion-reachability Task 6
            // review): the SelfRevoked check below used to be a RAW
            // `rev.device_pub == ownDevicePub` comparison, run
            // UNCONDITIONALLY after ingestRevocation and NOT gated on its
            // result. That was exploitable: ingestRevocation's is_known+
            // verify() gate only decides whether the cert gets INGESTED
            // (recorded as revoking some FRIEND device) -- it does nothing
            // to gate the SelfRevoked return, and RevocationCert.verify()
            // only proves internal self-consistency (the cert was signed by
            // WHOEVER'S key matches the cert's OWN, attacker-chosen,
            // `identity_pub` field), not that the signer is us or anyone we
            // trust. So ANY authenticated known peer could self-sign a
            // RevocationCert with a THROWAWAY keypair, naming the VICTIM's
            // device_pub (learnable from AUTH's exchanged CertDict), and the
            // raw comparison would fire SelfRevoked regardless -- which,
            // since Task 6, triggers an irreversible full identity+store
            // wipe (TorNodeService.enterRevokedState). The fix: SelfRevoked
            // now additionally requires `rev.identity_pub == ownIdentity`
            // (the revocation claims to be issued by OUR OWN identity, not
            // an attacker-chosen one) AND `rev.verify()` (that claim is
            // actually signed by that identity's real private key) -- i.e.
            // the revocation must be signed by OUR OWN identity_priv, the
            // secret only we and our other paired devices hold. An attacker
            // without that key cannot forge this, no matter which identity
            // they authenticate as. `ownIdentity` defaults to `""` (see this
            // fun's own param doc) so no real hex64 `rev.identity_pub` can
            // ever accidentally match it -- a caller that never passes
            // `ownIdentity` (an old/incomplete integration) fails CLOSED
            // (never self-revokes), not open.
            //
            // This is STRICTER than hearth's own is_known+device-match gate
            // (sync.py's session has no equivalent extra identity-match
            // requirement for its own is_self row) -- deliberately so: it
            // closes even a KNOWN-FRIEND-forges-it gap hearth's model
            // doesn't need to worry about the same way (hearth's revocation
            // model assumes ingest's is_known+verify is sufficient because a
            // friend has no reason to send a cert naming a device that
            // isn't theirs; the phone's SelfRevoked->wipe coupling makes
            // that assumption load-bearing in a way hearth's model never
            // was, so the phone closes it explicitly instead of inheriting
            // the assumption).
            //
            // CORRECTNESS NOTE (own identity IS known -- do not assume
            // otherwise): our own identity_pub is seeded into
            // knownIdentities() both at pairing (KotlinPairing.kt's
            // installPackage: `store.addIdentity(cert.identity_pub)`) and on
            // every sync (SyncRunner.kt's runTransport:
            // `SqliteSyncStore(ctx).also { it.addIdentity(fx.cert.identity_pub) }`),
            // and serve() reads/writes the SAME on-disk store. So a GENUINE
            // self-revocation (real own identity_pub, validly identity-signed,
            // device_pub == our own) PASSES ingestRevocation's is_known gate
            // and verify(), and DOES call markRevoked(ownDevicePub,
            // lastValidSeq) -- marking our own device revoked and retro-
            // dropping our own prior messages with seq > lastValidSeq --
            // BEFORE the loop reaches the tightened check below and returns
            // SelfRevoked. This mirrors hearth exactly: hearth's own identity
            // row has is_self=1, and sync.py's session (sync.py:664-671)
            // runs ingest_revocation on a self-revocation the same as any
            // other -- there is no is_self short-circuit that skips
            // ingestion. The end state is correct/safe either way: Task 6's
            // wipe clears the whole store on SelfRevoked, so markRevoked's
            // effects (now-redundant revoked-device bookkeeping and
            // retro-dropped own messages) are wiped again moments later
            // regardless of whether they ran.
            writeFrame(stream, mapOf("t" to "revocations", "revs" to emptyList<Any>()))
            val revs = readFrame(stream)
            if (revs.optString("t") == "refused") return SyncResult.Failed("revocations", "refused")
            val revArr = revs.optJSONArray("revs") ?: JSONArray()
            for (i in 0 until revArr.length()) {
                val r = revArr.getJSONObject(i)
                val rev = RevocationCert.fromDict(toMap(r))
                store.ingestRevocation(rev)
                if (rev.identity_pub == ownIdentity && rev.device_pub == ownDevicePub && rev.verify()) {
                    return SyncResult.SelfRevoked
                }
            }

            // -- DEFRIENDS -- (initiator: write then read, same _swap order
            // as REVOCATIONS above -- hearth's initiator branch, sync.py:
            // 697-704: write my_defriends first (nothing to apply yet,
            // since nothing has been read), THEN read the peer's frame and
            // apply what targets me from it. Own notices are always empty
            // -- the phone has no unfriend/outbox model yet (out of this
            // task's scope, phone-onion-reachability Task 4). Each peer
            // notice is parsed + applied via store.applyDefriendNotice
            // (Task 4 -- the same 4-gate order node.apply_defriend_notice
            // uses: target==own, author!=own self-guard, verify(),
            // isKnown(author) -> purge+remove). No ack write here -- matches
            // hearth's own initiator branch exactly: it never sends a
            // second frame reporting what IT applied, only reads
            // peer_df.get("applied", []) for ITS OWN sent notices (always
            // empty for us, so there is nothing to consult).
            writeFrame(stream, mapOf("t" to "defriends", "notices" to emptyList<Any>()))
            val defriends = readFrame(stream)
            val noticeArr = defriends.optJSONArray("notices") ?: JSONArray()
            for (i in 0 until noticeArr.length()) {
                val notice = DefriendNotice.fromDict(toMap(noticeArr.getJSONObject(i)))
                store.applyDefriendNotice(notice, ownIdentity)
            }

            // -- HAVE --
            writeFrame(stream, mapOf("t" to "have",
                "summary" to store.summary(), "known" to store.knownIdentities(),
                "peers" to emptyList<Any>(), "addr" to null))
            val have = readFrame(stream)
            val known = have.optJSONArray("known") ?: JSONArray()
            for (i in 0 until known.length()) store.addIdentity(known.getString(i))
            progress("handshake", 0)

            // -- MESSAGES -- (push outbound -- for B.2, at most the phone's
            // device-signed enckey, see composeEncKey -- and ingest node's)
            writeFrame(stream, mapOf("t" to "messages", "msgs" to outbound))
            val msgs = readFrame(stream)
            val msgArr = msgs.optJSONArray("msgs") ?: JSONArray()
            for (i in 0 until msgArr.length()) {
                val m = SignedMessageKt.fromDict(toMap(msgArr.getJSONObject(i)))
                store.ingestMessage(m)   // verifies + dedups internally
                progress("messages", i + 1)
            }

            // -- BLOBS -- (want swap, then blobs swap)
            writeFrame(stream, mapOf("t" to "blob_want", "hashes" to store.missingBlobs()))
            val peerWant = readFrame(stream)   // node's want -- NOW honored (Task 8, outbound push)
            val give = linkedMapOf<String, String>()
            peerWant.optJSONArray("hashes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val h = arr.optString(i)
                    val data = store.getBlob(h) ?: continue
                    give[h] = Base64Portable.encode(data)
                }
            }
            writeFrame(stream, mapOf("t" to "blobs", "blobs" to give))
            val blobs = readFrame(stream)
            val given = blobs.optJSONObject("blobs") ?: JSONObject()
            var storedBlobs = 0
            for (h in given.keys()) {
                val data = Base64Portable.decode(given.getString(h))
                // Size bound mirrors hearth/sync.py:661 (len(data) <=
                // MAX_BLOB_BYTES and blob_hash==h) -- store.putBlob only
                // hash-verifies; an oversized blob (bounded only by
                // MAX_FRAME, ~16 MiB > MAX_BLOB_BYTES's 10 MiB) from a
                // hostile/buggy node must be rejected before it's stored.
                if (data.size <= MAX_BLOB_BYTES) {
                    store.putBlob(h, data)   // store.putBlob does the hash check
                    storedBlobs++
                    progress("blobs", storedBlobs)
                }
            }

            progress("decrypting", 0)
            val st = store.stats()
            return SyncResult.Ok(st.messages, st.blobs, st.identities)
        } catch (e: Exception) {
            return SyncResult.Failed("io", e.toString())
        } finally {
            stream.close()
        }
    }

    /** Decodes a HAVE frame's wire-shaped `summary` field (identity_pub ->
     *  device_pub -> `{"contiguous":Int,"sparse":[Int,...]}`, the exact
     *  JSON shape `store.summary()` produces, see SyncStore.kt:73) into the
     *  flattened `Map<Pair<String,String>, SeenSet>` `messagesNotIn`
     *  requires (SyncStore.kt:303). No prior wire-side decoder existed for
     *  this shape -- `SeenSet.fromJson`'s only previous caller was
     *  SeenSetTest's pure in-memory round-trip (SeenSetTest.kt:36), never
     *  org.json-parsed wire bytes -- so this composes two already-proven
     *  pieces (this file's own `toMap` org.json bridge + `SeenSet.fromJson`)
     *  rather than adding a new primitive. A missing/malformed `summary`
     *  (absent key, non-object entry) is tolerated the same way hearth's
     *  own `peer_have.get("summary", {})` is -- skipped, not thrown. */
    private fun parseSummary(o: JSONObject): Map<Pair<String, String>, SeenSet> {
        val out = linkedMapOf<Pair<String, String>, SeenSet>()
        for (identityPub in o.keys()) {
            val devices = o.optJSONObject(identityPub) ?: continue
            for (devicePub in devices.keys()) {
                val seen = devices.optJSONObject(devicePub) ?: continue
                out[identityPub to devicePub] = SeenSet.fromJson(toMap(seen))
            }
        }
        return out
    }

    /** RESPONDER counterpart to `run` (arc 1, kotlin-gossip-server Task 3)
     *  -- the content phases of hearth's `_session` (sync.py:643-825),
     *  answering an inbound connection instead of dialing out. Called
     *  AFTER `KotlinHandshake.respondHandshake` has already completed
     *  HELLO+AUTH and returned the peer's authenticated `CertDict` -- this
     *  function never re-runs or re-verifies AUTH, and `peerCert` (never
     *  any claim inside a frame this phase reads) is what scopes the give
     *  side: MESSAGES' `entitled` set, HAVE's own-device-trust check, and
     *  BLOBS' give order all key off `peerCert.identity_pub`/`device_pub`,
     *  never off anything the peer's frames themselves assert about who
     *  they are. `fixture` is THIS device's own identity/keys -- read for
     *  the REVOCATIONS self-revoked check (`fixture.device_pub`) and the
     *  HAVE own-device-trust comparison (`fixture.cert.identity_pub`).
     *
     *  Mirror of `run`, REVERSED I/O order per phase: `run` (initiator) is
     *  write-then-read at each phase (`_swap`'s initiator=True branch,
     *  sync.py:572-574); the responder is read-then-write (`_swap`'s
     *  initiator=False branch, sync.py:575-577 -- already documented at
     *  KotlinHandshake.kt:76 and exercised by `respondHandshake`). Phase
     *  order is identical to `run`'s: REVOCATIONS -> DEFRIENDS -> HAVE ->
     *  MESSAGES -> BLOBS.
     *
     *  Wraps the whole body in the SAME try/catch-to-Failed("io",...)
     *  contract `run` uses (both return the same `SyncResult` sealed type,
     *  built around exactly that contract) -- but, UNLIKE `run`, never
     *  closes `stream`: `run` owns a standalone connection end-to-end, but
     *  here the caller (`GossipServer`, Task 4) owns the accepted socket's
     *  lifecycle in its own `finally`, exactly the precedent
     *  `respondHandshake` already set (KotlinHandshake.kt's doc comment on
     *  respondHandshake explains why: a stray close here would break a
     *  caller -- this one -- that continues on the same connection).
     *
     *  Deliberately NOT full hearth parity in two places, each documented
     *  at its phase below: DEFRIENDS (phone-onion-reachability Task 4) now
     *  DOES apply-then-ack, mirroring hearth's responder branch
     *  (sync.py:705-721), but as a SUBSET -- store.applyDefriendNotice
     *  skips the peer-table/device-views/disconnected-list cleanup
     *  node.apply_defriend_notice also does (see DefriendNotice.kt's doc
     *  comment for the full list; the phone has no peer table yet). And
     *  peer-address/peer-table merging is dropped entirely (HAVE's
     *  `peers`/`addr` fields are read but never consulted -- arc 3, no peer
     *  table exists on the phone yet, the same "read it, drop it" shape
     *  `KotlinPairing.installPackage` already set for `peers`,
     *  KotlinPairing.kt:176-183).
     *  REVOCATIONS (Task 3, phone-onion-reachability) is now a REAL
     *  ingest, matching `run`'s own REVOCATIONS phase -- see that phase's
     *  doc below and `RevocationCert.kt`'s `SyncStore.ingestRevocation`. */
    fun serve(stream: Stream, store: SyncStore, fixture: KotlinHandshake.Fixture, peerCert: KotlinWire.CertDict): SyncResult {
        try {
            // -- REVOCATIONS -- (responder: read peer's first, THEN write
            // ours -- _swap's read-then-write responder branch). Each peer
            // revocation is parsed + ingested via store.ingestRevocation
            // (Task 3, phone-onion-reachability -- mirrors `run`'s own
            // REVOCATIONS phase, just reached via the opposite I/O order);
            // own `list_revocations` is unconditionally empty, the phone
            // has none to offer.
            //
            // SECURITY (remote-wipe fix, phone-onion-reachability Task 6
            // review): the SelfRevoked check below used to be a RAW
            // `rev.device_pub == fixture.device_pub` comparison -- see
            // `run`'s own REVOCATIONS phase doc comment (KotlinSync.kt) for
            // the full writeup of the exploit this closes (any authenticated
            // known peer could self-sign a RevocationCert with a throwaway
            // keypair naming the VICTIM's device_pub, and the raw comparison
            // fired SelfRevoked -- which Task 6 wired to an irreversible
            // full wipe). Fixed identically here: SelfRevoked now requires
            // `rev.identity_pub == fixture.cert.identity_pub` (the
            // revocation claims to be issued by OUR OWN identity) AND
            // `rev.verify()` (that claim is actually signed by that
            // identity's real private key) -- i.e. genuinely signed by our
            // own identity_priv, which only we and our other paired devices
            // hold. STRICTER than hearth's own is_known+device-match gate,
            // deliberately -- see `run`'s doc comment for the full
            // hearth-parity note (this closes even a known-friend-forges-it
            // gap hearth's model doesn't need to worry about the same way).
            //
            // CORRECTNESS NOTE (own identity IS known -- do not assume
            // otherwise): our own identity IS seeded into knownIdentities()
            // at pairing/every sync, so a genuine self-revocation genuinely
            // runs through ingestRevocation -- markRevoked(ownDevice) +
            // retro-drop of our own messages -- BEFORE this loop reaches the
            // tightened check below, exactly mirroring hearth's own
            // is_self=1 identity also running ingest_revocation
            // (sync.py:664-671). Safe either way: Task 6's wipe clears the
            // whole store on SelfRevoked regardless.
            val revs = readFrame(stream)
            writeFrame(stream, mapOf("t" to "revocations", "revs" to emptyList<Any>()))
            if (revs.optString("t") == "refused") return SyncResult.Failed("revocations", "refused")
            val revArr = revs.optJSONArray("revs") ?: JSONArray()
            for (i in 0 until revArr.length()) {
                val r = revArr.getJSONObject(i)
                val rev = RevocationCert.fromDict(toMap(r))
                store.ingestRevocation(rev)
                if (rev.identity_pub == fixture.cert.identity_pub && rev.device_pub == fixture.device_pub && rev.verify()) {
                    return SyncResult.SelfRevoked
                }
            }

            // -- DEFRIENDS -- (responder: read peer's first, apply what
            // targets me, THEN write my own frame -- hearth's responder
            // branch, sync.py:705-721: apply-before-write, folding the
            // apply step between the REVOCATIONS-style read and write
            // above. Task 4, phone-onion-reachability: each peer notice is
            // parsed + applied via store.applyDefriendNotice (the same
            // 4-gate order node.apply_defriend_notice uses: target==own,
            // author!=own self-guard, verify(), isKnown(author) ->
            // purge+remove). `appliedHere` reports back only notices whose
            // author_identity IS the peer authenticated THIS session --
            // hearth's own belt-and-braces Fix 1 (sync.py:712-718): a
            // notice's signature already makes it self-authenticating
            // regardless of who relayed it, but the ack is only meaningful
            // to its true author, so it must never be credited to a
            // different relaying peer. Own notices are unconditionally
            // empty -- the phone has no defriend outbox to offer either.)
            val defriends = readFrame(stream)
            val appliedHere = mutableListOf<String>()
            val noticeArr = defriends.optJSONArray("notices") ?: JSONArray()
            for (i in 0 until noticeArr.length()) {
                val notice = DefriendNotice.fromDict(toMap(noticeArr.getJSONObject(i)))
                if (store.applyDefriendNotice(notice, fixture.cert.identity_pub) &&
                    notice.author_identity == peerCert.identity_pub) {
                    appliedHere.add(notice.author_identity)
                }
            }
            writeFrame(stream, mapOf("t" to "defriends", "notices" to emptyList<Any>(), "applied" to appliedHere))

            // Mid-session re-check (phone-onion-reachability Task 5, closes
            // the arc-1 whole-branch-review blocking finding -- mirrors
            // sync.py:741-758): re-consult BOTH the revoked-device set and
            // knownIdentities() AFTER REVOCATIONS and DEFRIENDS (both just
            // above) have already run -- either phase may have just changed
            // the answer THIS round: a peer revocation cert ingested during
            // REVOCATIONS can mark peerCert.device_pub revoked, and a
            // defriend notice applied during DEFRIENDS (just above) can
            // remove peerCert.identity_pub from knownIdentities() (this is
            // exactly the "defriended mid-session" case -- the peer's own
            // notice, applied a few lines up, is what makes isKnown go
            // false right here). Deliberately NO own-identity exemption
            // here, unlike respondHandshake's AUTH-phase gate: that
            // exemption exists ONLY so a revoked sibling can still receive
            // the REVOCATIONS/DEFRIENDS exchange above and learn of its own
            // revocation; once that exchange has happened, a revoked or
            // defriended peer gets nothing further -- no HAVE/MESSAGES/
            // BLOBS -- sibling or not, exactly like hearth. Ends the session
            // as an ordinary Ok, not a Failed: the session so far completed
            // successfully, it simply has nothing left to serve (mirrors
            // sync.py's own plain `return peer_identity, applied_by_peer`,
            // not an exception).
            if (!store.knownIdentities().contains(peerCert.identity_pub) || store.isRevokedDevice(peerCert.device_pub)) {
                val st = store.stats()
                return SyncResult.Ok(st.messages, st.blobs, st.identities)
            }

            // -- HAVE -- (responder: read peer's first, THEN write ours)
            val have = readFrame(stream)
            writeFrame(stream, mapOf("t" to "have",
                "summary" to store.summary(), "known" to store.knownIdentities(),
                "peers" to emptyList<Any>(), "addr" to ""))
            // peers / addr (the peer's own advertised address + its peer
            // table): read above as part of `have`, but intentionally never
            // consulted below -- arc 3 (friend peering / address merge), no
            // peer table exists on the phone yet. Same "read it, drop it"
            // shape KotlinPairing.installPackage already set for `peers`
            // (KotlinPairing.kt:176-183). Our own `addr` is written as ""
            // (not null, unlike `run`'s own HAVE write) -- the phone has no
            // gossip_addr concept yet at this arc (no SyncStore.getMeta
            // equivalent), so there is no real loopback address to report.
            val knownArr = have.optJSONArray("known") ?: JSONArray()
            val peerKnown = (0 until knownArr.length()).map { knownArr.getString(it) }.toSet()
            // Own-device trust (sync.py:768-772): only a verified SIBLING
            // device (peerCert.identity_pub == OUR OWN identity, never a
            // frame claim) may widen our known-identities set from what it
            // reports -- an ordinary friend's `known` list must never do
            // this, or a hostile friend could inject arbitrary identities
            // into our friend graph.
            if (peerCert.identity_pub == fixture.cert.identity_pub) {
                for (ident in peerKnown) store.addIdentity(ident)
            }

            // -- MESSAGES -- (responder: read peer's first, THEN write ours
            // -- _swap's read-then-write branch, same as every other phase.
            // FIX (code review, HIGH): this previously wrote toSend before
            // reading, the INITIATOR's order -- against a real hearth
            // initiator (write-then-read), that put both sides writing
            // before either read, deadlocking once the frame outgrew the
            // socket/Tor buffer (masked by the buffered test fake, which
            // has no such buffer limit). toSend is computed entirely from
            // data already exchanged during HAVE (peerKnown/peerSummary,
            // both already read above), so it does not depend on this read
            // -- safe to compute before the read and only defer the WRITE.
            val entitled = store.knownIdentities().filter { it in peerKnown }.toSet()
            val peerSummary = parseSummary(have.optJSONObject("summary") ?: JSONObject())
            val toSend = store.messagesNotIn(peerSummary, entitled, peerCert.identity_pub)
            val msgs = readFrame(stream)
            writeFrame(stream, mapOf("t" to "messages", "msgs" to toSend.map { it.toDict() }))
            val msgArr = msgs.optJSONArray("msgs") ?: JSONArray()
            for (i in 0 until msgArr.length()) {
                val m = SignedMessageKt.fromDict(toMap(msgArr.getJSONObject(i)))
                store.ingestMessage(m)   // verifies + dedups internally, same gates as `run`'s ingest side
            }

            // -- BLOBS -- (want swap, then blobs swap, each read-then-write)
            val peerWant = readFrame(stream)
            writeFrame(stream, mapOf("t" to "blob_want", "hashes" to store.missingBlobs()))
            val wantedArr = peerWant.optJSONArray("hashes") ?: JSONArray()
            val wanted = (0 until wantedArr.length()).map { wantedArr.getString(it) }
            // Smallest-first (sync.py:794-815, spec 2026-07-18): sizes come
            // from blobSizes (Task 1); an unsized (unknown/never-stored)
            // hash sorts last via the huge sentinel, same as hearth's
            // `sizes.get(x, 1 << 62)`, and simply drops out at the
            // getBlob==null continue below.
            val sizes = store.blobSizes(wanted)
            val give = linkedMapOf<String, String>()
            var giveSize = 0
            for (h in wanted.sortedWith(compareBy({ sizes[it] ?: (1L shl 62) }, { it }))) {
                val data = store.getBlob(h) ?: continue
                val b64 = Base64Portable.encode(data)
                if (give.isNotEmpty() && giveSize + b64.length > BLOB_GIVE_BUDGET) break   // leave the rest for next round
                give[h] = b64
                giveSize += b64.length
            }
            val peerBlobs = readFrame(stream)
            writeFrame(stream, mapOf("t" to "blobs", "blobs" to give))
            val given = peerBlobs.optJSONObject("blobs") ?: JSONObject()
            for (h in given.keys()) {
                val data = Base64Portable.decode(given.getString(h))
                // Mirrors `run`'s own ingest-side size bound (sync.py:661) --
                // store.putBlob does the hash check, this only bounds size
                // before it's ever stored.
                if (data.size <= MAX_BLOB_BYTES) store.putBlob(h, data)
            }

            val st = store.stats()
            return SyncResult.Ok(st.messages, st.blobs, st.identities)
        } catch (e: Exception) {
            return SyncResult.Failed("io", e.toString())
        }
    }
}
