package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class KotlinPairingTest {

    // =======================================================================
    // KotlinPairing.decodeLink
    // =======================================================================

    /** Cross-language pins (seal_vector lesson -- self-consistency alone
     *  can't catch a byte-format divergence from hearth's own codec).
     *  Generated via:
     *    .venv/Scripts/python.exe -c "from hearth.invitecodec import
     *    encode_pair, onion_join; pub=bytes(range(32));
     *    addr1=onion_join(pub,9997); print(encode_pair(addr1,'THECODE1'))"
     *  Vector 1 exercises pack_addr's onion branch (flag=0): a real
     *  Tor-v3-SHAPED onion address (onion_join(bytes(range(32)), 9997)),
     *  which round-trips through decodeLink's onionJoin/SHA3-256/base32
     *  port, not just a canned string. Vector 2 exercises the plain
     *  host:port branch (flag=1) with a longer code, covering both
     *  pack_addr encodings and two different code lengths. */
    @Test fun decodeLinkPythonVector1_onionAddr() {
        val link = "VPtXhJi8UoAu6bnpvCr7VcwMXeJdGCdVFPwfg1uQtgceG6FMQkDJko3aAiRULLY"
        val decoded = KotlinPairing.decodeLink(link)
        assertNotNull(decoded)
        assertEquals("aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp3kead.onion:9997", decoded!!.first)
        assertEquals("THECODE1", decoded.second)
    }

    @Test fun decodeLinkPythonVector2_plainHostPort() {
        val link = "3AL8mxN8DV459gfJ7VMMZvxoZYDnup9hDMqE9d1uKmUUEb3tpk6kzGfgkBfV675wf"
        val decoded = KotlinPairing.decodeLink(link)
        assertNotNull(decoded)
        assertEquals("192.168.1.50:9997", decoded!!.first)
        assertEquals("A-LONGER-PAIRING-CODE-42", decoded.second)
    }

    /** Real truncation-boundary + corruption vectors, generated from the
     *  Python codec itself (not hand-guessed): starting from Vector 1's raw
     *  bytes, truncated at several offsets and re-b58-encoded, PLUS the
     *  version byte and type byte separately flipped -- each confirmed
     *  against hearth's own `decode()` to raise ValueError before being
     *  pinned here. decodeLink must return null (never throw) for every one. */
    @Test fun decodeLinkMalformedVectorsReturnNull() {
        val cases = listOf(
            "" to "empty string (cut=0, decode(): unrecognized invite)",
            "2" to "single-byte body, no type tag (cut=1: unrecognized invite)",
            "5V" to "version+type only, no address (cut=2: truncated address)",
            "7eTnGg" to "partial onion flag+pubkey (cut=5: truncated onion address)",
            "4Jx2Nqrmvq1Lh" to "partial onion pubkey (cut=10: truncated onion address)",
            "pbiDMtdVHj5zp8PU66ac33sySP" to "onion pubkey present, port missing (cut=20: truncated onion address)",
            "7S89KCGD7kYF4zGpZSo223hrxt3dmE4HNPRAfkFbQwjy29tjgpQBNFWao9w2fN"
                to "full address, code length/bytes missing (cut=46: truncated pair code)",
            "yMSXaX1nupnYcmvLMPnPtPg8JrXqNNxWn6qcez4CsFVrGA32ghuyvp4PHH7tW8p"
                to "version byte flipped 0x01->0x02 (decode(): unrecognized invite)",
            "VWDuu4V4KqDXijzBefrKnvnTbSoDyTd7H6tFC9NdkyWVvcAmoh2pMx7Rk3ah7gt"
                to "type byte flipped 4->5 (decode(): unknown message type)",
        )
        for ((link, why) in cases) assertNull(why, KotlinPairing.decodeLink(link))
    }

    @Test fun decodeLinkBadBase58CharsReturnNull() {
        assertNull(KotlinPairing.decodeLink("0"))              // '0' is not in the b58 alphabet (looks-alike excluded)
        assertNull(KotlinPairing.decodeLink("Il1O0"))           // several looks-alike-excluded chars
        assertNull(KotlinPairing.decodeLink("not valid base58!"))
    }

    /** encode_pair(None, "NOADDR") is a VALID hearth encoding (pack_addr's
     *  flag=2 "no address" case) -- Python's decode() happily returns
     *  ("pair", None, "NOADDR"). decodeLink's Pair<String,String> return
     *  shape has no way to express a null address, and an address-less
     *  "pair" link is useless for this feature's actual purpose (it exists
     *  to carry a dialable address) -- so decodeLink deliberately treats it
     *  as malformed (null), not as "successfully decoded, addr known
     *  absent". Vector via
     *  .venv/Scripts/python.exe -c "from hearth.invitecodec import
     *  encode_pair; print(encode_pair(None,'NOADDR'))" */
    @Test fun decodeLinkNoAddressEncodingReturnsNull() {
        assertNull(KotlinPairing.decodeLink("FcFLiehDcKeMtm"))
    }

    // =======================================================================
    // KotlinPairing.buildRequestFrame
    // =======================================================================

    /** The Task 2 wire contract (task-2-report.md "Wire contract"): client
     *  -> server is exactly `{"t":"hearth-pair-request","protocol":...,
     *  "device_pub":...,"device_name":...,"code":...}` -- 5 keys, no more
     *  no fewer, PROTOCOL reused from KotlinWire (the same constant every
     *  other wire frame builder in this module uses). */
    @Test fun buildRequestFrameShape() {
        val frame = KotlinPairing.buildRequestFrame("dpub123", "My Phone", "CODE99")
        assertEquals(setOf("t", "protocol", "device_pub", "device_name", "code"), frame.keys)
        assertEquals("hearth-pair-request", frame["t"])
        assertEquals(KotlinWire.PROTOCOL, frame["protocol"])
        assertEquals("dpub123", frame["device_pub"])
        assertEquals("My Phone", frame["device_name"])
        assertEquals("CODE99", frame["code"])
    }

    // =======================================================================
    // KotlinPairing.installPackage
    // =======================================================================

    private fun certJson(identityPub: String, devicePub: String, name: String = "Desktop"): JSONObject =
        JSONObject().apply {
            put("identity_pub", identityPub); put("device_pub", devicePub)
            put("device_name", name); put("enrolled_at", 1752900000.0)
            put("signature", "ab".repeat(64))
        }

    private fun packageJson(
        identityPub: String = "11".repeat(32),
        devicePub: String = "22".repeat(32),
        identityPriv: String = "33".repeat(32),
        friends: List<String> = listOf("44".repeat(32), "55".repeat(32)),
        myAddr: String? = "abcexampleaddr.onion:9997",
        t: String = "hearth-pair-package",
    ): String {
        val o = JSONObject()
        o.put("t", t)
        o.put("protocol", KotlinWire.PROTOCOL)
        o.put("cert", certJson(identityPub, devicePub))
        o.put("identity_priv", identityPriv)
        o.put("friends", JSONArray(friends))
        // Real hearth peers shape (node.py store.list_peers()): [{address,
        // identity_pub}, ...] -- included to prove installPackage tolerates
        // (and deliberately drops) it, not merely that it's absent.
        val peers = JSONArray()
        if (friends.isNotEmpty())
            peers.put(JSONObject().apply { put("address", "friendnode.onion:9997"); put("identity_pub", friends[0]) })
        o.put("peers", peers)
        if (myAddr != null) o.put("my_addr", myAddr) else o.put("my_addr", JSONObject.NULL)
        return o.toString()
    }

    @Test fun installPackageHappyPath() {
        val store = InMemorySyncStore()
        val identityPub = "11".repeat(32)
        val devicePubInCert = "22".repeat(32)
        val friendA = "44".repeat(32)
        val friendB = "55".repeat(32)
        val pkg = packageJson(identityPub = identityPub, devicePub = devicePubInCert,
            identityPriv = "33".repeat(32), friends = listOf(friendA, friendB),
            myAddr = "homenode.onion:9997")

        val identity = KotlinPairing.installPackage(
            store, pkg, devicePriv = "dpriv-local", devicePub = "dpub-local", deviceName = "My Phone")

        // -- returned Identity record -----------------------------------
        assertEquals("dpriv-local", identity.device_priv)
        assertEquals("dpub-local", identity.device_pub)
        assertEquals(identityPub, identity.cert.identity_pub)
        assertEquals(devicePubInCert, identity.cert.device_pub)
        assertEquals("33".repeat(32), identity.identity_priv)
        assertEquals("homenode.onion:9997", identity.onion_addr)

        // -- store.addIdentity(self) -- mirrors add_identity(..., is_self=True) --
        assertTrue(store.knownIdentities().contains(identityPub))
        // -- store.addIdentity per friends entry --
        assertTrue(store.knownIdentities().contains(friendA))
        assertTrue(store.knownIdentities().contains(friendB))
        // Nothing beyond self+friends got added (peers dropped, no phantom entries).
        assertEquals(setOf(identityPub, friendA, friendB), store.knownIdentities().toSet())
    }

    @Test fun installPackageWrongTThrows() {
        val store = InMemorySyncStore()
        val pkg = packageJson(t = "hearth-pair-request")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            KotlinPairing.installPackage(store, pkg, "dpriv", "dpub", "name")
        }
        assertTrue(ex.message?.contains("pairing package") == true)
        // Nothing mutated on rejection.
        assertTrue(store.knownIdentities().isEmpty())
    }

    @Test fun installPackageNoFriendsNoMyAddr() {
        val store = InMemorySyncStore()
        val identityPub = "11".repeat(32)
        val pkg = packageJson(identityPub = identityPub, friends = emptyList(), myAddr = null)
        val identity = KotlinPairing.installPackage(store, pkg, "dpriv", "dpub", "name")
        assertEquals("", identity.onion_addr)
        assertEquals(setOf(identityPub), store.knownIdentities().toSet())
    }

    // =======================================================================
    // PairingStore
    // =======================================================================

    private fun sampleIdentity(): PairingStore.Identity = PairingStore.Identity(
        device_priv = "dpriv-abc", device_pub = "dpub-abc",
        cert = KotlinWire.CertDict("11".repeat(32), "dpub-abc", "My Phone", 1752900000.0, "ab".repeat(64)),
        identity_priv = "identpriv-xyz",
        onion_addr = "homenode.onion:9997",
    )

    @Test fun pairingStoreSaveLoadRoundTrip() {
        val dir = Files.createTempDirectory("pairingstore").toFile()
        val identity = sampleIdentity()
        assertNull(PairingStore.load(dir))                 // nothing saved yet

        PairingStore.save(dir, identity)
        val loaded = PairingStore.load(dir)
        assertNotNull(loaded)
        assertEquals(identity, loaded)                      // full round trip, INCLUDING identity_priv
        assertEquals("identpriv-xyz", loaded!!.identity_priv)
    }

    @Test fun pairingStoreSaveOverwritesExisting() {
        // Exercises the atomic-write path when the target ALREADY exists
        // (the re-pair case) -- this is exactly the scenario java.io.File
        // .renameTo is unreliable for on Windows; save() must still succeed.
        val dir = Files.createTempDirectory("pairingstore").toFile()
        PairingStore.save(dir, sampleIdentity())
        val second = sampleIdentity().copy(identity_priv = "identpriv-SECOND")
        PairingStore.save(dir, second)
        assertEquals(second, PairingStore.load(dir))
    }

    @Test fun pairingStoreHasIdentityFalseToTrue_internal() {
        val internalDir = Files.createTempDirectory("pairingstore-internal").toFile()
        val legacyFile = java.io.File(Files.createTempDirectory("pairingstore-legacy").toFile(), "spike_phone_fixture.json")
        assertFalse(PairingStore.hasIdentity(internalDir, legacyFile))   // neither internal nor legacy present

        PairingStore.save(internalDir, sampleIdentity())
        assertTrue(PairingStore.hasIdentity(internalDir, legacyFile))    // internal alone is enough
    }

    @Test fun pairingStoreHasIdentityTrue_legacyOnly() {
        val internalDir = Files.createTempDirectory("pairingstore-internal2").toFile()
        val legacyDir = Files.createTempDirectory("pairingstore-legacy2").toFile()
        val legacyFile = java.io.File(legacyDir, "spike_phone_fixture.json")
        assertFalse(PairingStore.hasIdentity(internalDir, legacyFile))

        legacyFile.writeText(legacyFixtureJson())
        assertTrue(PairingStore.hasIdentity(internalDir, legacyFile))    // legacy alone is enough, no internal file
    }

    @Test fun pairingStoreHasIdentityFalse_legacyUnparseable() {
        // A present-but-garbage legacy file must NOT count as "has identity"
        // (mirrors the pre-existing fixtureOrNull()'s try/catch-null
        // behavior for a corrupt fixture).
        val internalDir = Files.createTempDirectory("pairingstore-internal3").toFile()
        val legacyDir = Files.createTempDirectory("pairingstore-legacy3").toFile()
        val legacyFile = java.io.File(legacyDir, "spike_phone_fixture.json")
        legacyFile.writeText("{ not valid json")
        assertFalse(PairingStore.hasIdentity(internalDir, legacyFile))
    }

    /** Fixture mapping excludes identity_priv: PairingStore.Identity carries
     *  identity_priv, KotlinHandshake.Fixture (a data class with exactly
     *  device_priv/device_pub/cert/onion_addr, see KotlinHandshake.kt:7-10)
     *  structurally CANNOT carry it -- toFixture's return type alone is a
     *  compile-time proof. This test pins the runtime mapping: the 4 fields
     *  Fixture DOES carry must match Identity's corresponding 4 fields
     *  exactly. */
    @Test fun toFixtureExcludesIdentityPrivMapsRemainingFourFields() {
        val identity = sampleIdentity()
        val fx = PairingStore.toFixture(identity)
        assertEquals(identity.device_priv, fx.device_priv)
        assertEquals(identity.device_pub, fx.device_pub)
        assertEquals(identity.cert, fx.cert)
        assertEquals(identity.onion_addr, fx.onion_addr)
        assertEquals(
            KotlinHandshake.Fixture(identity.device_priv, identity.device_pub, identity.cert, identity.onion_addr),
            fx)
    }

    private fun legacyFixtureJson(): String = JSONObject().apply {
        put("device_priv", "legacy-dpriv"); put("device_pub", "legacy-dpub")
        put("cert", JSONObject().apply {
            put("identity_pub", "99".repeat(32)); put("device_pub", "legacy-dpub")
            put("device_name", "Legacy Phone"); put("enrolled_at", 1752900000.0)
            put("signature", "cd".repeat(64))
        })
        put("onion_addr", "legacyhome.onion:9997")
    }.toString()

    @Test fun readFixturePrefersInternalOverLegacy() {
        val internalDir = Files.createTempDirectory("pairingstore-internal4").toFile()
        val legacyDir = Files.createTempDirectory("pairingstore-legacy4").toFile()
        val legacyFile = java.io.File(legacyDir, "spike_phone_fixture.json")
        legacyFile.writeText(legacyFixtureJson())
        val internalIdentity = sampleIdentity()
        PairingStore.save(internalDir, internalIdentity)

        val fx = PairingStore.readFixture(internalDir, legacyFile)
        assertEquals(internalIdentity.device_priv, fx.device_priv)       // NOT the legacy file's values
        assertEquals(internalIdentity.onion_addr, fx.onion_addr)
    }

    @Test fun readFixtureFallsBackToLegacyWhenNoInternal() {
        val internalDir = Files.createTempDirectory("pairingstore-internal5").toFile()
        val legacyDir = Files.createTempDirectory("pairingstore-legacy5").toFile()
        val legacyFile = java.io.File(legacyDir, "spike_phone_fixture.json")
        legacyFile.writeText(legacyFixtureJson())

        val fx = PairingStore.readFixture(internalDir, legacyFile)
        assertEquals("legacy-dpriv", fx.device_priv)
        assertEquals("legacyhome.onion:9997", fx.onion_addr)
        assertEquals(PairingStore.readFixtureOrNull(internalDir, legacyFile), fx)
    }

    @Test fun readFixtureThrowsAndOrNullReturnsNullWhenNeitherPresent() {
        val internalDir = Files.createTempDirectory("pairingstore-internal6").toFile()
        val legacyFile = java.io.File(Files.createTempDirectory("pairingstore-legacy6").toFile(), "spike_phone_fixture.json")

        assertThrows(Exception::class.java) { PairingStore.readFixture(internalDir, legacyFile) }
        assertNull(PairingStore.readFixtureOrNull(internalDir, legacyFile))
    }
}
