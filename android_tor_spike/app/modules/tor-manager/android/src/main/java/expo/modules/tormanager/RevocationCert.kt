package expo.modules.tormanager

/** Kotlin port of hearth.identity.RevocationCert (identity.py:120-149) --
 *  a SELF-AUTHENTICATING cert (signed by the IDENTITY key, not a device
 *  key): "device_pub is revoked as of last_valid_seq". body()/verify()
 *  byte-match the Python dataclass exactly via KotlinWire.canonical/
 *  verifyRaw -- the same canonical()-matching primitives KotlinWire.CertDict/
 *  certBody/verifyCert already use for EnrollmentCert (identity.py:87-116),
 *  reused here rather than re-implemented, so this inherits that byte-
 *  fidelity guarantee instead of risking a second, subtly-divergent
 *  construction. phone-onion-reachability Task 3. */
data class RevocationCert(
    val identity_pub: String,
    val device_pub: String,
    val last_valid_seq: Int,
    val revoked_at: Double,
    val signature: String,
) {
    fun body(): ByteArray = KotlinWire.canonical(mapOf(
        "type" to "revocation", "protocol" to KotlinWire.PROTOCOL,
        "identity_pub" to identity_pub, "device_pub" to device_pub,
        "last_valid_seq" to last_valid_seq,
        "revoked_at" to KotlinWire.PyFloat(revoked_at),
    ))

    /** Self-authenticating: verifies the signature against `identity_pub`
     *  itself (identity.py:135-136's `_sig_ok(self.identity_pub, ...)`),
     *  NOT against any device key -- unlike SignedMessage.verifyDeviceSignature,
     *  which checks cert.device_pub. This is what lets a revocation be
     *  trusted independent of which device relayed it over the wire: the
     *  identity owner issued it, full stop. */
    fun verify(): Boolean = KotlinWire.verifyRaw(identity_pub, signature, body())

    fun toDict(): Map<String, Any?> = mapOf(
        "identity_pub" to identity_pub, "device_pub" to device_pub,
        "last_valid_seq" to last_valid_seq, "revoked_at" to revoked_at,
        "signature" to signature,
    )

    companion object {
        fun fromDict(d: Map<String, Any?>): RevocationCert = RevocationCert(
            d["identity_pub"] as String, d["device_pub"] as String,
            (d["last_valid_seq"] as Number).toInt(),
            (d["revoked_at"] as Number).toDouble(),
            d["signature"] as String,
        )
    }
}

/** Mirrors hearth Store.ingest_revocation (store.py:410-430) -- the
 *  ESSENTIAL gate only: `is_known(identity)` -> `verify()` -> `markRevoked`
 *  (Task 2's store primitive, which performs the retro-drop itself). Returns
 *  false (nothing ingested, `markRevoked` never called) for an unknown
 *  identity or a signature that fails `verify()`.
 *
 *  A free/extension function rather than a `SyncStore` interface member,
 *  deliberately: it needs no store-impl-specific state beyond the two
 *  already-public interface methods it calls (`knownIdentities`,
 *  `markRevoked`), so implementing it ONCE here -- rather than once per
 *  store impl -- follows the same precedent `SyncStore.kt`'s own
 *  `filterMessagesNotIn` sets for a security-critical algorithm: "factored
 *  out ... so they cannot diverge" (SyncStore.kt:398-404). Both
 *  `InMemorySyncStore` and `SqliteSyncStore` get identical behavior for
 *  free, with no changes to either file.
 *
 *  Deliberately NOT full hearth parity -- two Verifier nuances skipped:
 *
 *  1. hearth routes through `Verifier(identity, views).process_revocation`
 *     (identity.py:581-591), which re-checks `rev.identity_pub !=
 *     self.identity_pub`. At store.ingest_revocation's OWN call site
 *     (store.py:412: `identity = rev.identity_pub`, then
 *     `Verifier(identity, views)`), that comparison is `rev.identity_pub !=
 *     rev.identity_pub` -- always False, i.e. unreachable dead code via
 *     THIS call path specifically (it only ever fires when some other
 *     caller constructs a Verifier for a FIXED target identity and feeds it
 *     a revocation claiming a different one -- see
 *     test_verifier.py::test_forged_revocation_rejected, which calls
 *     `Verifier.process_revocation` directly, not through
 *     `store.ingest_revocation`). Skipping it here changes nothing
 *     observable: the `isKnown(rev.identity_pub)` gate below already keys
 *     off `rev.identity_pub` itself, the same value the skipped check would
 *     have compared against itself.
 *
 *  2. hearth's `process_revocation` additionally records the cert into a
 *     persistent per-device `DeviceView.revocation` (identity.py:590), which
 *     the SEPARATE `Verifier.verify_message` path (identity.py:574-576)
 *     later consults to reject any FUTURE message with `seq >
 *     last_valid_seq` from that device AT INGEST TIME -- not merely
 *     retro-drop what's already stored (see test_identity_core.py::
 *     test_post_revocation_seq_rejected). This store has no DeviceView/
 *     Verifier model at all (a pre-existing, already-flagged gap --
 *     SyncStore.kt's `enckeys` doc: "the Kotlin store models no
 *     revocations"); `ingestMessage` has no ongoing seq gate keyed off
 *     `isRevokedDevice`. Practically this is NARROWER than hearth, not
 *     unsound: a revoked device's future messages still arrive over
 *     `run`/`serve`'s MESSAGES phase and get stored, and only a SUBSEQUENT
 *     `markRevoked` call (e.g. a later revocation with a higher
 *     `last_valid_seq`) would retro-drop them. Flagged, not fixed --
 *     wiring `isRevokedDevice` into `ingestMessage`'s ongoing-seq gate is
 *     out of this task's scope. */
fun SyncStore.ingestRevocation(rev: RevocationCert): Boolean {
    if (!knownIdentities().contains(rev.identity_pub)) return false
    if (!rev.verify()) return false
    markRevoked(rev.device_pub, rev.last_valid_seq)
    return true
}
