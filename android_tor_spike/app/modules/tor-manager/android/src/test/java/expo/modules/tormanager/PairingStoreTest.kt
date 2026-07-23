package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Task 6 (phone-onion-reachability): PairingStore.wipe -- the phone-side
 *  half of the self-revoke -> full wipe to First-Load flow (node.py:3144's
 *  `enter_revoked_state` is the desktop analog). Dir-based JVM tests, same
 *  seam KotlinPairingTest's own PairingStore section already uses (File
 *  dir, not Context) -- see PairingStore.kt's own comment on why this stays
 *  pure-JVM-testable. The Context-based wrapper (`wipe(ctx)`) and the
 *  rest of the self-revoke flow (SqliteSyncStore.wipe, TorNodeService.
 *  enterRevokedState, the service-stop, the "revoked" broadcast/event) all
 *  need a real android.content.Context/Service and are on-device only
 *  (Task 9) -- not exercised here.
 */
class PairingStoreTest {

    private fun sampleIdentity(): PairingStore.Identity = PairingStore.Identity(
        device_priv = "dpriv-wipe", device_pub = "dpub-wipe",
        cert = KotlinWire.CertDict("22".repeat(32), "dpub-wipe", "My Phone", 1752900000.0, "ef".repeat(64)),
        identity_priv = "identpriv-SECRET",
        onion_addr = "homenode.onion:9997",
    )

    private fun legacyFixtureJson(): String = JSONObject().apply {
        put("device_priv", "legacy-dpriv-wipe"); put("device_pub", "legacy-dpub-wipe")
        put("cert", JSONObject().apply {
            put("identity_pub", "88".repeat(32)); put("device_pub", "legacy-dpub-wipe")
            put("device_name", "Legacy Phone"); put("enrolled_at", 1752900000.0)
            put("signature", "12".repeat(64))
        })
        put("onion_addr", "legacyhome.onion:9997")
    }.toString()

    @Test fun wipeDeletesInternalPairingJsonAndHasIdentityBecomesFalse() {
        val internalDir = Files.createTempDirectory("pairingstore-wipe-internal").toFile()
        val legacyFile = File(Files.createTempDirectory("pairingstore-wipe-legacy").toFile(), "spike_phone_fixture.json")

        PairingStore.save(internalDir, sampleIdentity())
        assertTrue("sanity: identity present before wipe", PairingStore.hasIdentity(internalDir, legacyFile))
        assertTrue(File(internalDir, "pairing.json").exists())

        PairingStore.wipe(internalDir, legacyFile)

        assertFalse("pairing.json must be gone", File(internalDir, "pairing.json").exists())
        assertFalse("hasIdentity must be false after wipe", PairingStore.hasIdentity(internalDir, legacyFile))
    }

    @Test fun wipeDeletesLegacyFixtureAndHasIdentityBecomesFalse() {
        val internalDir = Files.createTempDirectory("pairingstore-wipe-internal2").toFile()
        val legacyDir = Files.createTempDirectory("pairingstore-wipe-legacy2").toFile()
        val legacyFile = File(legacyDir, "spike_phone_fixture.json")
        legacyFile.writeText(legacyFixtureJson())

        assertTrue("sanity: legacy-only identity present before wipe",
            PairingStore.hasIdentity(internalDir, legacyFile))

        PairingStore.wipe(internalDir, legacyFile)

        assertFalse("the legacy fixture must be gone -- a revoked phone must never be able " +
            "to fall back to it via readFixture's dual-read", legacyFile.exists())
        assertFalse(PairingStore.hasIdentity(internalDir, legacyFile))
    }

    @Test fun wipeDeletesBothInternalAndLegacyWhenBothPresent() {
        val internalDir = Files.createTempDirectory("pairingstore-wipe-internal3").toFile()
        val legacyDir = Files.createTempDirectory("pairingstore-wipe-legacy3").toFile()
        val legacyFile = File(legacyDir, "spike_phone_fixture.json")

        PairingStore.save(internalDir, sampleIdentity())
        legacyFile.writeText(legacyFixtureJson())
        assertTrue(PairingStore.hasIdentity(internalDir, legacyFile))

        PairingStore.wipe(internalDir, legacyFile)

        assertFalse(File(internalDir, "pairing.json").exists())
        assertFalse(legacyFile.exists())
        assertFalse(PairingStore.hasIdentity(internalDir, legacyFile))
    }

    /** A crash between `save`'s `tmp.writeText` and its atomic move can in
     *  principle leave a stray `pairing.json.tmp` holding key material
     *  around -- wipe must clear that too, not just the final target, so
     *  a wipe leaves NO trace of key material behind (see PairingStore.
     *  wipe's own doc comment). */
    @Test fun wipeDeletesStrayAtomicWriteTmpFile() {
        val internalDir = Files.createTempDirectory("pairingstore-wipe-internal4").toFile()
        val legacyFile = File(Files.createTempDirectory("pairingstore-wipe-legacy4").toFile(), "spike_phone_fixture.json")
        val tmp = File(internalDir, "pairing.json.tmp")
        tmp.writeText(PairingStore.toJson(sampleIdentity()), Charsets.UTF_8)
        assertTrue(tmp.exists())

        PairingStore.wipe(internalDir, legacyFile)

        assertFalse("the atomic-write temp file must be wiped too", tmp.exists())
    }

    /** Idempotency (brief requirement): a second SelfRevoked observed after
     *  the identity is already gone (e.g. the inbound serve path noticing
     *  moments after an outbound sync already wiped) must be a safe no-op --
     *  File.delete() on an absent target returns false but never throws. */
    @Test fun wipeIsIdempotent() {
        val internalDir = Files.createTempDirectory("pairingstore-wipe-internal5").toFile()
        val legacyDir = Files.createTempDirectory("pairingstore-wipe-legacy5").toFile()
        val legacyFile = File(legacyDir, "spike_phone_fixture.json")

        PairingStore.save(internalDir, sampleIdentity())
        legacyFile.writeText(legacyFixtureJson())

        PairingStore.wipe(internalDir, legacyFile)   // first wipe
        assertFalse(PairingStore.hasIdentity(internalDir, legacyFile))

        PairingStore.wipe(internalDir, legacyFile)   // second wipe -- must not throw
        assertFalse(PairingStore.hasIdentity(internalDir, legacyFile))
        assertFalse(File(internalDir, "pairing.json").exists())
        assertFalse(legacyFile.exists())
    }

    /** Wiping a dir/legacy file that never held anything (nothing was ever
     *  saved -- e.g. a First-Load-only device, or the pairing ceremony
     *  itself was interrupted before save() ever ran) must also be a
     *  no-throw no-op. */
    @Test fun wipeOnAlreadyEmptyDirsDoesNotThrow() {
        val internalDir = Files.createTempDirectory("pairingstore-wipe-internal6").toFile()
        val legacyFile = File(Files.createTempDirectory("pairingstore-wipe-legacy6").toFile(), "spike_phone_fixture.json")
        assertFalse(PairingStore.hasIdentity(internalDir, legacyFile))

        PairingStore.wipe(internalDir, legacyFile)   // must not throw

        assertFalse(PairingStore.hasIdentity(internalDir, legacyFile))
    }
}
