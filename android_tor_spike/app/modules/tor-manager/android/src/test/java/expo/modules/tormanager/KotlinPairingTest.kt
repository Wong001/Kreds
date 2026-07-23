package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
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
import java.security.SecureRandom

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

    /** A fresh real Ed25519 keypair (privHex, pubHex) -- installPackage now
     *  verifies actual crypto (cert signature + identity_priv derivation),
     *  so hand-rolled "11".repeat(32)-style fixtures no longer pass; every
     *  test below mints real keys the same way ComposeTest/DecryptPassTest/
     *  SyncStoreTest already do. */
    private fun genKeypair(): Pair<String, String> {
        val p = Ed25519PrivateKeyParameters(SecureRandom())
        return KotlinWire.toHex(p.encoded) to KotlinWire.toHex(p.generatePublicKey().encoded)
    }

    /** A REALLY signed CertDict: `identityPriv` signs `certBody` (the exact
     *  body KotlinWire.verifyCert checks), same two-step "unsigned then
     *  .copy(signature=...)" idiom certBody's own callers use (it excludes
     *  the signature field itself, so the placeholder value doesn't matter). */
    private fun signedCert(
        identityPriv: String, identityPub: String, devicePub: String,
        name: String = "Desktop", enrolledAt: Double = 1752900000.0,
    ): KotlinWire.CertDict {
        val unsigned = KotlinWire.CertDict(identityPub, devicePub, name, enrolledAt, "")
        return unsigned.copy(signature = KotlinWire.signRaw(identityPriv, KotlinWire.certBody(unsigned)))
    }

    private fun certJson(c: KotlinWire.CertDict): JSONObject = JSONObject().apply {
        put("identity_pub", c.identity_pub); put("device_pub", c.device_pub)
        put("device_name", c.device_name); put("enrolled_at", c.enrolled_at)
        put("signature", c.signature)
    }

    /** `peers` (friend-peering Task 3): null keeps the original default
     *  shape (one entry naming `friends[0]`, when `friends` is non-empty) so
     *  every pre-existing call site is unaffected; pass an explicit list
     *  (including `emptyList()`) to control exactly which `{identity_pub,
     *  address}` entries the package carries, for the address-learning
     *  tests below. */
    private fun packageJson(
        cert: KotlinWire.CertDict,
        identityPriv: String,
        friends: List<String> = listOf("44".repeat(32), "55".repeat(32)),
        myAddr: String? = "abcexampleaddr.onion:9997",
        t: String = "hearth-pair-package",
        peers: List<Pair<String, String>>? = null,
    ): String {
        val o = JSONObject()
        o.put("t", t)
        o.put("protocol", KotlinWire.PROTOCOL)
        o.put("cert", certJson(cert))
        o.put("identity_priv", identityPriv)
        o.put("friends", JSONArray(friends))
        // Real hearth peers shape (node.py store.list_peers()): [{address,
        // identity_pub}, ...].
        val effectivePeers = peers ?: (if (friends.isNotEmpty())
            listOf(friends[0] to "friendnode.onion:9997") else emptyList())
        val peersArr = JSONArray()
        for ((identityPub, address) in effectivePeers)
            peersArr.put(JSONObject().apply { put("address", address); put("identity_pub", identityPub) })
        o.put("peers", peersArr)
        if (myAddr != null) o.put("my_addr", myAddr) else o.put("my_addr", JSONObject.NULL)
        return o.toString()
    }

    @Test fun installPackageHappyPath() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val (localDevicePriv, localDevicePub) = genKeypair()   // the keypair WE generated (pair_request side)
        val cert = signedCert(identityPriv, identityPub, localDevicePub)
        val friendA = "44".repeat(32)
        val friendB = "55".repeat(32)
        val pkg = packageJson(cert, identityPriv, friends = listOf(friendA, friendB), myAddr = "homenode.onion:9997")

        val identity = KotlinPairing.installPackage(
            store, pkg, devicePriv = localDevicePriv, devicePub = localDevicePub, deviceName = "My Phone")

        // -- returned Identity record -----------------------------------
        assertEquals(localDevicePriv, identity.device_priv)
        assertEquals(localDevicePub, identity.device_pub)
        assertEquals(identityPub, identity.cert.identity_pub)
        assertEquals(localDevicePub, identity.cert.device_pub)
        assertEquals(identityPriv, identity.identity_priv)
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
        val (identityPriv, identityPub) = genKeypair()
        val (localDevicePriv, localDevicePub) = genKeypair()
        val cert = signedCert(identityPriv, identityPub, localDevicePub)
        val pkg = packageJson(cert, identityPriv, t = "hearth-pair-request")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            KotlinPairing.installPackage(store, pkg, localDevicePriv, localDevicePub, "name")
        }
        assertTrue(ex.message?.contains("pairing package") == true)
        // Nothing mutated on rejection.
        assertTrue(store.knownIdentities().isEmpty())
    }

    @Test fun installPackageNoFriendsNoMyAddr() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val (localDevicePriv, localDevicePub) = genKeypair()
        val cert = signedCert(identityPriv, identityPub, localDevicePub)
        val pkg = packageJson(cert, identityPriv, friends = emptyList(), myAddr = null)
        val identity = KotlinPairing.installPackage(store, pkg, localDevicePriv, localDevicePub, "name")
        assertEquals("", identity.onion_addr)
        assertEquals(setOf(identityPub), store.knownIdentities().toSet())
        assertTrue("no my_addr -- nothing to seed as the home node peer", store.listPeers().isEmpty())
    }

    // =======================================================================
    // KotlinPairing.installPackage -- friend-peering Task 3: address
    // learning at pairing install (peers[] merge + home-node seed).
    // =======================================================================

    /** A `peers[]` entry naming a KNOWN friend (added to `store` via the
     *  `friends[]` loop that runs just above the peers merge in
     *  installPackage) gets merged into the peer table -- `friends` must be
     *  added BEFORE peers are consulted, or the is_known gate below would
     *  never pass for a legitimately-known friend. */
    @Test fun installPackageMergesPeerAddressForKnownFriend() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val (localDevicePriv, localDevicePub) = genKeypair()
        val cert = signedCert(identityPriv, identityPub, localDevicePub)
        val friendA = "44".repeat(32)
        val pkg = packageJson(cert, identityPriv, friends = listOf(friendA),
            peers = listOf(friendA to "friendnode.onion:9997"))

        KotlinPairing.installPackage(store, pkg, localDevicePriv, localDevicePub, "My Phone")

        assertTrue(store.knownIdentities().contains(friendA))
        assertEquals("friendnode.onion:9997", store.addressFor(friendA))
    }

    /** A `peers[]` entry naming an identity NOT in `friends[]` (so
     *  `store.knownIdentities()` never contains it) must NOT be merged --
     *  the is_known gate, defense-in-depth against a malformed/hostile
     *  package injecting an address for an identity this device has no
     *  other reason to trust or dial. */
    @Test fun installPackageDoesNotMergePeerAddressForUnknownIdentity() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val (localDevicePriv, localDevicePub) = genKeypair()
        val cert = signedCert(identityPriv, identityPub, localDevicePub)
        val strangerPub = "99".repeat(32)   // deliberately NOT in friends[]
        val pkg = packageJson(cert, identityPriv, friends = emptyList(), myAddr = null,
            peers = listOf(strangerPub to "stranger.onion:9997"))

        KotlinPairing.installPackage(store, pkg, localDevicePriv, localDevicePub, "My Phone")

        assertFalse(store.knownIdentities().contains(strangerPub))
        assertNull("unknown identity's address must not be added -- is_known gate",
            store.addressFor(strangerPub))
        // myAddr=null here (isolates this test to the peers[]-gate only, not
        // the separate home-node seed, which has its own dedicated test below).
        assertTrue("nothing else should have landed in the peer table either", store.listPeers().isEmpty())
    }

    /** `my_addr` (the home node's own address) is seeded as a peer of the
     *  OWN identity -- `mergePeerAddress(store, cert.identity_pub, my_addr)`
     *  -- so the peer-loop (Task 4) dials the home node like any other
     *  peer, instead of relying solely on the returned Identity.onion_addr
     *  passthrough. */
    @Test fun installPackageSeedsHomeNodeAsPeerForOwnIdentity() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val (localDevicePriv, localDevicePub) = genKeypair()
        val cert = signedCert(identityPriv, identityPub, localDevicePub)
        val pkg = packageJson(cert, identityPriv, friends = emptyList(),
            myAddr = "homenode.onion:9997", peers = emptyList())

        val identity = KotlinPairing.installPackage(store, pkg, localDevicePriv, localDevicePub, "My Phone")

        assertEquals("homenode.onion:9997", identity.onion_addr)   // unchanged passthrough
        assertEquals("homenode.onion:9997", store.addressFor(identityPub))
        assertTrue(store.listPeers().any {
            it.address == "homenode.onion:9997" && it.identityPub == identityPub
        })
    }

    /** Code review fix (LOW): a `peers[]` entry with a missing OR explicit-
     *  null `identity_pub` must be SKIPPED, not reject the whole package --
     *  mirrors hearth's own schema (store.py's peers.identity_pub is
     *  `Optional[str] = None`; node.py:2056's pair_install reads it via
     *  `p.get("identity_pub")`, not `p["identity_pub"]`). Built directly
     *  with `JSONObject`/`JSONArray` rather than via `packageJson`'s
     *  `Pair<String,String>` peers param, which can't express an entry
     *  missing a field entirely. A well-formed entry for a known friend, in
     *  the SAME package, still installs and merges -- proving one bad entry
     *  doesn't poison the rest. */
    @Test fun installPackageSkipsPeersEntryWithMissingOrNullIdentityPubButInstallsRestOfPackage() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val (localDevicePriv, localDevicePub) = genKeypair()
        val cert = signedCert(identityPriv, identityPub, localDevicePub)
        val friendA = "44".repeat(32)

        val o = JSONObject()
        o.put("t", "hearth-pair-package")
        o.put("protocol", KotlinWire.PROTOCOL)
        o.put("cert", certJson(cert))
        o.put("identity_priv", identityPriv)
        o.put("friends", JSONArray(listOf(friendA)))
        val peersArr = JSONArray()
        peersArr.put(JSONObject().apply { put("address", "noidentitykey.onion:9997") })            // identity_pub key entirely absent
        peersArr.put(JSONObject().apply { put("address", "nullidentity.onion:9997"); put("identity_pub", JSONObject.NULL) })   // explicit JSON null
        peersArr.put(JSONObject().apply { put("address", "friendnode.onion:9997"); put("identity_pub", friendA) })             // well-formed, known friend
        o.put("peers", peersArr)
        o.put("my_addr", JSONObject.NULL)
        val pkg = o.toString()

        val identity = KotlinPairing.installPackage(store, pkg, localDevicePriv, localDevicePub, "My Phone")

        // The whole package still installs -- not rejected over the two bad entries.
        assertEquals(identityPub, identity.cert.identity_pub)
        assertTrue(store.knownIdentities().contains(friendA))
        // The well-formed entry (known friend) still merges.
        assertEquals("friendnode.onion:9997", store.addressFor(friendA))
        // Nothing landed under either malformed entry's address.
        assertTrue(store.listPeers().none { it.address == "noidentitykey.onion:9997" })
        assertTrue(store.listPeers().none { it.address == "nullidentity.onion:9997" })
        assertEquals("only the one well-formed peer landed", 1, store.listPeers().size)
    }

    /** Post-review Important fix: installPackage must VERIFY BEFORE
     *  MUTATING, mirroring hearth's device.install (identity.py:295-303,
     *  called by pair_install BEFORE its store writes, node.py:2044-2050). */
    @Test fun installPackageTamperedCertSignatureThrows() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val (localDevicePriv, localDevicePub) = genKeypair()
        val goodCert = signedCert(identityPriv, identityPub, localDevicePub)
        // Flip a signed field after signing -- signature no longer matches certBody.
        val tampered = goodCert.copy(device_name = "Tampered Name")
        val pkg = packageJson(tampered, identityPriv)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            KotlinPairing.installPackage(store, pkg, localDevicePriv, localDevicePub, "name")
        }
        assertEquals("cert signature invalid", ex.message)
        assertTrue("store must be untouched on a rejected install", store.knownIdentities().isEmpty())
    }

    @Test fun installPackageDevicePubMismatchThrows() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val (localDevicePriv, localDevicePub) = genKeypair()
        val (_, someOtherDevicePub) = genKeypair()
        // Validly signed, but for a DIFFERENT device than the one devicePub names.
        val cert = signedCert(identityPriv, identityPub, someOtherDevicePub)
        val pkg = packageJson(cert, identityPriv)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            KotlinPairing.installPackage(store, pkg, localDevicePriv, localDevicePub, "name")
        }
        assertTrue(ex.message?.contains("device_pub") == true)
        assertTrue(store.knownIdentities().isEmpty())
    }

    @Test fun installPackageIdentityPrivMismatchThrows() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val (wrongIdentityPriv, _) = genKeypair()   // a DIFFERENT identity's priv
        val (localDevicePriv, localDevicePub) = genKeypair()
        // Cert is validly signed by the REAL identity...
        val cert = signedCert(identityPriv, identityPub, localDevicePub)
        // ...but the package hands us a DIFFERENT identity_priv (doesn't derive cert.identity_pub).
        val pkg = packageJson(cert, wrongIdentityPriv)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            KotlinPairing.installPackage(store, pkg, localDevicePriv, localDevicePub, "name")
        }
        assertTrue(ex.message?.contains("identity_priv") == true)
        assertTrue(store.knownIdentities().isEmpty())
    }

    @Test fun installPackageMissingFieldOrGarbageJsonThrows() {
        val store = InMemorySyncStore()
        val (_, localDevicePub) = genKeypair()
        val localDevicePriv = genKeypair().first

        assertThrows(IllegalArgumentException::class.java) {
            KotlinPairing.installPackage(store, "{ not valid json", localDevicePriv, localDevicePub, "name")
        }
        assertTrue(store.knownIdentities().isEmpty())

        // Well-formed JSON, correct t, but "cert" is missing entirely.
        val missingCert = JSONObject().apply {
            put("t", "hearth-pair-package"); put("protocol", KotlinWire.PROTOCOL)
            put("identity_priv", "aa".repeat(32))
        }.toString()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            KotlinPairing.installPackage(store, missingCert, localDevicePriv, localDevicePub, "name")
        }
        assertEquals("bad package", ex.message)
        assertTrue(store.knownIdentities().isEmpty())
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

    // =======================================================================
    // KotlinPairing.runCeremony
    // =======================================================================
    //
    // No pre-existing "fake Stream" precedent in this module's tests to
    // mirror: KotlinSyncTest exercises composeEncKey only (no Stream at
    // all), and SyncLoopbackTest's SocketStream drives a REAL spawned hearth
    // node over a REAL TCP socket (a desk gate, not a JVM-only fake). So
    // ScriptedStream/HangingStream below are a fresh, minimal fake built
    // for exactly runCeremony's one-request/one-reply contract, not a port
    // of anything already in the tree.

    /** Captures the single request frame runCeremony writes (there is
     *  exactly one -- buildRequestFrame's hearth-pair-request), decodes it,
     *  and hands it to `onRequest` to build the reply frame bytes on the
     *  spot -- needed because the reply's cert.device_pub must match the
     *  Ed25519 keypair runCeremony generates INTERNALLY (installPackage
     *  requires an exact match, see installPackageDevicePubMismatchThrows),
     *  which no test-side code can predict ahead of time. */
    private class ScriptedStream(private val onRequest: (JSONObject) -> Map<String, Any?>) : Stream {
        lateinit var request: JSONObject
            private set
        private var replyBytes: ByteArray = ByteArray(0)
        private var pos = 0
        var closed = false

        override fun write(bytes: ByteArray) {
            val n = (((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
                     ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff))
            request = JSONObject(String(bytes, 4, n, Charsets.US_ASCII))
            replyBytes = KotlinWire.writeFrameBytes(onRequest(request))
        }
        override fun readExactSync(n: Int): ByteArray {
            check(pos + n <= replyBytes.size) { "ScriptedStream exhausted (wanted $n more bytes)" }
            val out = replyBytes.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
        override fun close() { closed = true }
    }

    /** A reply frame whose body is well-formed JSON but not a recognized
     *  pairing reply -- runCeremony's "garbage reply" case (Task 4 brief).
     *  Reused (rather than a one-off literal) so both the plain-garbage test
     *  and any future variant build it the same way. */
    private class FixedReplyStream(reply: Map<String, Any?>) : Stream {
        private val replyBytes = KotlinWire.writeFrameBytes(reply)
        private var pos = 0
        var closed = false
        override fun write(bytes: ByteArray) {}
        override fun readExactSync(n: Int): ByteArray {
            check(pos + n <= replyBytes.size) { "FixedReplyStream exhausted" }
            val out = replyBytes.copyOfRange(pos, pos + n); pos += n; return out
        }
        override fun close() { closed = true }
    }

    /** A reply frame with a VALID 4-byte length header but a body that
     *  isn't JSON at all -- the "truly malformed bytes" garbage case (MINOR,
     *  post-review), distinct from FixedReplyStream's well-formed-JSON/
     *  wrong-`t` garbage: this one drives KotlinPairing.readFrame's
     *  `JSONObject(...)` parse itself into throwing. */
    private class RawFramedBytesStream(body: String) : Stream {
        private val replyBytes: ByteArray = body.toByteArray(Charsets.US_ASCII).let { b ->
            val out = ByteArray(4 + b.size)
            out[0] = (b.size ushr 24).toByte(); out[1] = (b.size ushr 16).toByte()
            out[2] = (b.size ushr 8).toByte(); out[3] = b.size.toByte()
            b.copyInto(out, 4)
            out
        }
        private var pos = 0
        var closed = false
        override fun write(bytes: ByteArray) {}
        override fun readExactSync(n: Int): ByteArray {
            check(pos + n <= replyBytes.size) { "RawFramedBytesStream exhausted" }
            val out = replyBytes.copyOfRange(pos, pos + n); pos += n; return out
        }
        override fun close() { closed = true }
    }

    /** Never produces enough reply bytes -- sleeps well past any reasonable
     *  test timeoutMs on every read. Cheap simulated hang: the test uses a
     *  tiny timeoutMs (tens of ms), so runCeremony's Future.get times out
     *  and returns long before this finishes sleeping; executor.shutdownNow()
     *  then interrupts the still-sleeping background thread immediately
     *  (Thread.sleep is interruptible), so the hang never actually costs the
     *  test suite real wall-clock time beyond the timeoutMs itself. */
    private class HangingStream(private val sleepMs: Long) : Stream {
        var closed = false
        override fun write(bytes: ByteArray) {}
        override fun readExactSync(n: Int): ByteArray {
            Thread.sleep(sleepMs)
            throw IllegalStateException("HangingStream: should never actually reach here in a passing test")
        }
        override fun close() { closed = true }
    }

    private fun packageReplyMap(
        cert: KotlinWire.CertDict, identityPriv: String,
        friends: List<String> = emptyList(), myAddr: String? = "selfreported.onion:9999",
    ): Map<String, Any?> = mapOf(
        "t" to "hearth-pair-package",
        "protocol" to KotlinWire.PROTOCOL,
        "cert" to mapOf(
            "identity_pub" to cert.identity_pub, "device_pub" to cert.device_pub,
            "device_name" to cert.device_name, "enrolled_at" to cert.enrolled_at,
            "signature" to cert.signature,
        ),
        "identity_priv" to identityPriv,
        "friends" to friends,
        "my_addr" to myAddr,
    )

    // Vector 2 from the decodeLink tests above: a real hearth-encoded pair
    // link, decodes to addr "192.168.1.50:9997", code "A-LONGER-PAIRING-CODE-42".
    private val VALID_LINK = "3AL8mxN8DV459gfJ7VMMZvxoZYDnup9hDMqE9d1uKmUUEb3tpk6kzGfgkBfV675wf"

    @Test fun runCeremonyPackageReplyLinksAndPersists() {
        val store = InMemorySyncStore()
        val dir = Files.createTempDirectory("ceremony-linked").toFile()
        val (identityPriv, identityPub) = genKeypair()
        val friend = "77".repeat(32)
        var dialedHost: String? = null; var dialedPort: Int? = null
        var stream: ScriptedStream? = null

        val dial: (String, Int) -> Stream = { host, port ->
            dialedHost = host; dialedPort = port
            ScriptedStream { req ->
                assertEquals("hearth-pair-request", req.getString("t"))
                assertEquals(KotlinWire.PROTOCOL, req.getString("protocol"))
                assertEquals("My Phone", req.getString("device_name"))
                assertEquals("A-LONGER-PAIRING-CODE-42", req.getString("code"))
                val cert = signedCert(identityPriv, identityPub, req.getString("device_pub"))
                packageReplyMap(cert, identityPriv, friends = listOf(friend), myAddr = "selfreported.onion:9999")
            }.also { stream = it }
        }

        val result = KotlinPairing.runCeremony(dial, VALID_LINK, "My Phone", 5000, store, dir)

        assertEquals("192.168.1.50", dialedHost); assertEquals(9997, dialedPort)
        assertTrue("expected Linked, got $result", result is KotlinPairing.CeremonyResult.Linked)
        val identity = (result as KotlinPairing.CeremonyResult.Linked).identity
        assertEquals(identityPub, identity.cert.identity_pub)
        assertEquals(stream!!.request.getString("device_pub"), identity.device_pub)
        // Pinned to the DIALED address (the link's own addr), NOT the
        // package's self-reported my_addr ("selfreported.onion:9999").
        assertEquals("192.168.1.50:9997", identity.onion_addr)

        assertTrue(store.knownIdentities().contains(identityPub))
        assertTrue(store.knownIdentities().contains(friend))
        val loaded = PairingStore.load(dir)
        assertEquals(identity, loaded)
        assertTrue("stream must be closed", stream!!.closed)
    }

    @Test fun runCeremonyDeniedReplyReturnsDenied() {
        val store = InMemorySyncStore()
        val dir = Files.createTempDirectory("ceremony-denied").toFile()
        val stream = FixedReplyStream(mapOf("t" to "pair-denied"))
        val result = KotlinPairing.runCeremony({ _, _ -> stream }, VALID_LINK, "My Phone", 5000, store, dir)
        assertEquals(KotlinPairing.CeremonyResult.Denied, result)
        assertTrue(stream.closed)
        assertNull("must not persist anything on denial", PairingStore.load(dir))
        assertTrue(store.knownIdentities().isEmpty())
    }

    @Test fun runCeremonyExpiredReplyReturnsExpired() {
        val store = InMemorySyncStore()
        val dir = Files.createTempDirectory("ceremony-expired").toFile()
        val stream = FixedReplyStream(mapOf("t" to "pair-expired"))
        val result = KotlinPairing.runCeremony({ _, _ -> stream }, VALID_LINK, "My Phone", 5000, store, dir)
        assertEquals(KotlinPairing.CeremonyResult.Expired, result)
        assertTrue(stream.closed)
        assertNull(PairingStore.load(dir))
    }

    @Test fun runCeremonyUnrecognizedReplyIsUnreachable() {
        val store = InMemorySyncStore()
        val dir = Files.createTempDirectory("ceremony-garbage").toFile()
        val stream = FixedReplyStream(mapOf("t" to "some-nonsense-reply"))
        val result = KotlinPairing.runCeremony({ _, _ -> stream }, VALID_LINK, "My Phone", 5000, store, dir)
        assertTrue("expected Unreachable, got $result", result is KotlinPairing.CeremonyResult.Unreachable)
        assertTrue(stream.closed)
        assertNull(PairingStore.load(dir))
    }

    /** MINOR (post-review): a reply frame whose body isn't JSON at all
     *  (as opposed to runCeremonyUnrecognizedReplyIsUnreachable's
     *  well-formed-JSON-but-wrong-`t` case) -- exercises the OTHER path to
     *  Unreachable, KotlinPairing.readFrame's `JSONObject(...)` parse
     *  throwing, caught by runCeremony's generic `catch (e: Exception)`
     *  rather than falling through the `when`'s `else` branch. */
    @Test fun runCeremonyNonJsonReplyBytesIsUnreachable() {
        val store = InMemorySyncStore()
        val dir = Files.createTempDirectory("ceremony-nonjson").toFile()
        val stream = RawFramedBytesStream("this is not json at all")
        val result = KotlinPairing.runCeremony({ _, _ -> stream }, VALID_LINK, "My Phone", 5000, store, dir)
        assertTrue("expected Unreachable, got $result", result is KotlinPairing.CeremonyResult.Unreachable)
        assertTrue(stream.closed)
        assertNull(PairingStore.load(dir))
    }

    /** IMPORTANT 1 (post-review): the "dialing" -> "waiting" pairProgress
     *  sequence, driven directly on runCeremony's onProgress callback (the
     *  bridge just forwards each stage string to sendEvent -- no bridge-
     *  level behavior to test beyond that direct forward). "dialing" runs
     *  on this test thread; "waiting" runs on runCeremony's internal
     *  executor thread (after the request frame is written, before the
     *  blocking read) -- Collections.synchronizedList so both writers are
     *  safe, and the assertion happens after runCeremony has already
     *  returned (so no data race on the read side either). */
    @Test fun runCeremonyEmitsDialingThenWaitingProgress() {
        val store = InMemorySyncStore()
        val dir = Files.createTempDirectory("ceremony-progress").toFile()
        val stages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val stream = FixedReplyStream(mapOf("t" to "pair-denied"))

        val result = KotlinPairing.runCeremony(
            dial = { _, _ -> stream }, link = VALID_LINK, deviceName = "My Phone",
            timeoutMs = 5000, store = store, dir = dir,
            onProgress = { stage -> stages.add(stage) },
        )

        assertEquals(KotlinPairing.CeremonyResult.Denied, result)
        assertEquals(listOf("dialing", "waiting"), stages)
    }

    /** A malformed link must not even emit "dialing" -- BadLink returns
     *  before onProgress is invoked at all (same "dial never called"
     *  short-circuit runCeremonyMalformedLinkIsBadLinkAndNeverDials pins). */
    @Test fun runCeremonyMalformedLinkEmitsNoProgress() {
        val store = InMemorySyncStore()
        val dir = Files.createTempDirectory("ceremony-progress-badlink").toFile()
        val stages = mutableListOf<String>()
        val result = KotlinPairing.runCeremony(
            dial = { _, _ -> throw AssertionError("must not dial") }, link = "not a valid pairing link!",
            deviceName = "My Phone", timeoutMs = 5000, store = store, dir = dir,
            onProgress = { stage -> stages.add(stage) },
        )
        assertEquals(KotlinPairing.CeremonyResult.BadLink, result)
        assertTrue("no progress for a link that never dials", stages.isEmpty())
    }

    @Test fun runCeremonyRejectedPackageIsUnreachableStoreUntouched() {
        // A validly-framed hearth-pair-package whose cert is tampered
        // (signature won't verify) -- installPackage throws
        // IllegalArgumentException, which runCeremony must fold to
        // Unreachable rather than letting it escape or claiming Linked.
        val store = InMemorySyncStore()
        val dir = Files.createTempDirectory("ceremony-rejected").toFile()
        val (identityPriv, identityPub) = genKeypair()
        val stream = ScriptedStream { req ->
            val goodCert = signedCert(identityPriv, identityPub, req.getString("device_pub"))
            val tampered = goodCert.copy(device_name = "Tampered")
            packageReplyMap(tampered, identityPriv)
        }
        val result = KotlinPairing.runCeremony({ _, _ -> stream }, VALID_LINK, "My Phone", 5000, store, dir)
        assertTrue("expected Unreachable, got $result", result is KotlinPairing.CeremonyResult.Unreachable)
        assertTrue(store.knownIdentities().isEmpty())
        assertNull(PairingStore.load(dir))
    }

    @Test fun runCeremonyMalformedLinkIsBadLinkAndNeverDials() {
        val store = InMemorySyncStore()
        val dir = Files.createTempDirectory("ceremony-badlink").toFile()
        var dialed = false
        val dial: (String, Int) -> Stream = { _, _ -> dialed = true; throw AssertionError("dial must not be called for a malformed link") }
        val result = KotlinPairing.runCeremony(dial, "not a valid pairing link!", "My Phone", 5000, store, dir)
        assertEquals(KotlinPairing.CeremonyResult.BadLink, result)
        assertFalse("dial lambda must never run for a malformed link", dialed)
    }

    @Test fun runCeremonyTimeoutIsUnreachable() {
        val store = InMemorySyncStore()
        val dir = Files.createTempDirectory("ceremony-timeout").toFile()
        val stream = HangingStream(sleepMs = 5000)
        val start = System.currentTimeMillis()
        val result = KotlinPairing.runCeremony({ _, _ -> stream }, VALID_LINK, "My Phone", 50, store, dir)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("expected Unreachable, got $result", result is KotlinPairing.CeremonyResult.Unreachable)
        assertTrue("must return promptly after timeoutMs, not wait for the hang (took ${elapsed}ms)", elapsed < 3000)
        assertTrue(stream.closed)
    }

    @Test fun runCeremonyDialExceptionIsUnreachable() {
        val store = InMemorySyncStore()
        val dir = Files.createTempDirectory("ceremony-dialfail").toFile()
        val dial: (String, Int) -> Stream = { _, _ -> throw java.io.IOException("connection refused") }
        val result = KotlinPairing.runCeremony(dial, VALID_LINK, "My Phone", 5000, store, dir)
        assertTrue("expected Unreachable, got $result", result is KotlinPairing.CeremonyResult.Unreachable)
    }
}
