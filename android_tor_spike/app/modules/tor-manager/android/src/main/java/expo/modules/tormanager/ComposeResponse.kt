package expo.modules.tormanager

import java.security.SecureRandom

/** Kotlin port of hearth.node.Node.compose_response (Task 4, outbound-
 *  responses slice, node.py:2347-2495): compose + LOCALLY ingest a
 *  reaction/comment/retract on a journal post, encrypted to the TARGET
 *  POST'S AUTHOR's devices (never a general friend list), plus a self-
 *  readable wrap so this composing device's own "pending retract" UI can
 *  decrypt its own outbound response without a round trip. Sibling of
 *  Compose.post -- same fixture/ingest/enqueue idiom, different envelope
 *  (`kind:"response"`, not `kind:"post"`).
 *
 *  Engagement privacy (hearth spec 2026-07-18): the real identity travels
 *  ONLY inside the encrypted body's `mutual_box` -- an anonymous seal_slots
 *  box addressed to THIS DEVICE's own friends (never the target post
 *  author's friends -- see `friendPubs` below), so a stranger opening the
 *  response as the author sees only `alias_seed` unless a mutual friend
 *  later opens the box to vouch for the identity.
 *
 *  Scope note: hearth's `public_engagement` opt-in (node.py:2406, a
 *  plaintext-identity toggle read from `store.get_meta`) is NOT wired on
 *  this branch -- there is no Android settings surface for it yet (task-4
 *  brief). This orchestrator always composes the PRIVATE shape
 *  (`"public": false`, `mutual_box` always populated), which is also
 *  hearth's own default in the absence of that meta key
 *  (`get_meta("public_engagement") == "1"` is false when unset) -- so this
 *  is a narrowing of scope, not a behavior divergence from hearth's default
 *  path. See task-4-report.md for the full note. */
object ComposeResponse {
    data class Result(val msgId: String, val wireDict: Map<String, Any?>)

    // hearth/messages.py: REACTION_TOKENS + MAX_COMMENT. node.py:2372
    // additionally allows "clear" for reaction bodies (the "un-react" token)
    // ONLY at this validation gate -- REACTION_TOKENS itself does not
    // include it, and KotlinResponses' read-side REACTION_TOKENS
    // deliberately does not either (a folded record only ever tallies real
    // reactions; "clear" removes an entry rather than becoming one) -- the
    // two constants intentionally diverge and must not be unified.
    private val REACTION_TOKENS = setOf("heart", "laugh", "wow", "sad", "up", "fire")
    private const val MAX_COMMENT = 500

    // Strictly-increasing per-process clock (mirrors hearth's
    // `_last_response_ts`, node.py:2384-2405): the caller's wall-clock
    // reading can collide across two back-to-back composes (Windows' timer
    // granularity), and created_at is the per-responder key retract/fold
    // keys by -- a collision would make retracting one response silently
    // also drop a different one from the same responder. Per-process
    // (object-level), not per-call: this singleton IS "this one device
    // instance", the same role hearth's Node singleton plays. Does NOT
    // persist across process death, same as hearth.
    @Volatile private var lastTs = 0.0

    /** Compose + LOCALLY ingest a response (reaction/comment/retract) to
     *  `target`'s journal post. `createdAt` is the caller-supplied wall-
     *  clock reading (seconds); this call may bump it past the last
     *  composed timestamp to preserve strict monotonicity -- callers must
     *  read the ACTUAL timestamp used back out of `Result.wireDict`'s
     *  payload (`created_at`), not assume the input value survived
     *  unchanged. `encPriv` is accepted for signature symmetry with
     *  `Compose.post` but, like there, is not read by this function --
     *  only `encPub` (this device's own published enc key) is needed to
     *  build the self-readable wrap. */
    fun compose(
        store: SyncStore, fx: KotlinHandshake.Fixture, encPriv: String, encPub: String,
        target: String, rkind: String, body: String, createdAt: Double,
    ): Result {
        require(rkind == "comment" || rkind == "reaction" || rkind == "retract") { "bad response kind" }
        if (rkind == "comment") {
            // Python len() counts code points, not UTF-16 units -- match at
            // the boundary (KotlinResponses.validEntry's read-side precedent).
            val len = body.codePointCount(0, body.length)
            require(len in 1..MAX_COMMENT) { "comment must be 1-500 characters" }
        }
        if (rkind == "reaction")
            require(body in REACTION_TOKENS || body == "clear") { "unknown reaction" }

        val own = fx.cert.identity_pub
        val msg = store.messageById(target)
        val placement = (msg?.payload?.get("placement") as? String) ?: "journal"
        if (msg == null || msg.kind != "post" || placement != "journal")
            throw IllegalArgumentException("not a journal post")

        val author = msg.cert.identity_pub
        val authorDevs = store.enckeys(author).toMutableMap()
        if (author == own) authorDevs[fx.device_pub] = encPub
        require(authorDevs.isNotEmpty()) { "no reachable devices for the author" }

        // Strictly-increasing createdAt (see `lastTs`'s doc above).
        val effectiveCreatedAt = synchronized(this) {
            val ts = if (createdAt <= lastTs) lastTs + 1e-6 else createdAt
            lastTs = ts
            ts
        }

        // _response_sig_payload (node.py:1389-1397) via KotlinResponses'
        // already-proven port -- the exact 5-field canonical form every
        // viewer (KotlinResponses.resolveDisplay) re-derives to verify this
        // signature, so compose and verify are byte-identical by
        // construction rather than by two independent transcriptions.
        val sigPayload = KotlinResponses.responseSigPayload(target, rkind, body, effectiveCreatedAt, own)
        val responderSig = KotlinWire.signRaw(fx.device_priv, sigPayload)

        // Mutual-box audience is THIS device's OWN friends -- store.
        // knownIdentities() is, by construction, only ever this phone's own
        // synced friend graph (there is no other node's graph visible here)
        // -- never the target post's author's friends, so the author cannot
        // use their own graph to shrink the anonymity set of who might be
        // behind the box (node.py:2417-2420's self-review point).
        val friendPubs = store.knownIdentities().filter { it != own }
            .flatMap { store.enckeys(it).values }
        val box = KotlinSeal.sealSlots(
            KotlinWire.canonical(mapOf(
                "identity" to own, "device_pub" to fx.device_pub, "sig" to responderSig)),
            friendPubs)

        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val aad = KotlinDmcrypt.responseAad(own, target, effectiveCreatedAt)
        val (nonceHex, ctHex) = KotlinDmcrypt.encryptBody(key, mapOf(
            "rkind" to rkind, "body" to body,
            // Same deterministic per-device-per-post seed derive_alias_seed
            // uses (node.py:2472-2473's PRIVATE branch -- this orchestrator
            // never composes the public/random-seed branch, see this
            // object's class doc).
            "alias_seed" to KotlinDmcrypt.deriveAliasSeed(fx.device_priv, target),
            "public" to false,
            "responder" to own, "responder_sig" to responderSig,
            "mutual_box" to box,
            "created_at" to KotlinWire.PyFloat(effectiveCreatedAt)), aad)

        val wraps = KotlinDmcrypt.wrapKey(key, authorDevs, aad).toMutableMap()
        val ownDevs = store.enckeys(own).toMutableMap()
        ownDevs[fx.device_pub] = encPub
        wraps.putAll(KotlinDmcrypt.wrapKey(key, ownDevs, aad))   // self-readable (retract UI)

        val payload: Map<String, Any?> = mapOf(
            "kind" to "response", "target" to target,
            "body_nonce" to nonceHex, "body_ct" to ctHex, "wraps" to wraps,
            "created_at" to KotlinWire.PyFloat(effectiveCreatedAt))

        val unsigned = SignedMessage(fx.cert, store.nextSeq(), payload, "")
        val signed = unsigned.copy(signature = KotlinWire.signRaw(fx.device_priv, unsigned.body()))
        store.ingestMessage(signed)
        // outbound Task 3 idiom (see Compose.post): queue this composed
        // response so the NEXT sync actually pushes it onward.
        store.addPendingOutbound(signed.msgId(), signed.toDict())
        return Result(signed.msgId(), signed.toDict())
    }
}
