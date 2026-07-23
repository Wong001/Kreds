package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import org.junit.Test

/** Task 5 (kotlin-gossip-server arc 1): the loopback FIDELITY GATE for the
 *  whole slice, and the FIRST gate in this codebase where the direction is
 *  INVERTED -- every prior loopback gate (SyncLoopbackTest, SyncDmLoopbackTest,
 *  GossipServerTest's own in-process client, ...) has the phone/Kotlin side
 *  DIAL a python node. Here a REAL hearth node DIALS the phone: the Kotlin
 *  test starts `GossipServer` (arc 1 Task 4) on an ephemeral loopback port
 *  over a store IT seeds, then spawns `sync_loopback_node.py`'s new "dial"
 *  scenario (Task 5, `_run_dial`) telling it to `node._dial("127.0.0.1:
 *  <port>")` -- hearth's REAL initiator `_sync_session` (the exact bound
 *  method `Node.deliver_defriends` uses in production, SyncService.__init__
 *  wires it as `node._dial`, sync.py:149) against `KotlinHandshake.
 *  respondHandshake` + `KotlinSync.serve` (arc 1 Tasks 2-3) answering on the
 *  other end. If this passes, the phone's ANSWERING side is proven against
 *  the real protocol from the OTHER direction too -- not just "a Kotlin
 *  client can talk to a real node" (already proven) but "a real node can
 *  talk to this Kotlin server", closing the loop.
 *
 *  THE ORDERING PROBLEM AND HOW IT'S SOLVED: `GossipServer.start()` returns
 *  an OS-assigned ephemeral port only once bound, so the port is knowable
 *  only AFTER the Kotlin test has already built its `store`/fixture and
 *  started serving -- but the friend/own-sibling sub-scenarios below need
 *  the DIALING NODE's identity_pub/device_pub BEFORE that seeding happens
 *  (to compute wrap audiences, register deviceViews, and set the right
 *  isKnown/store.addIdentity gates). Every OTHER scenario in
 *  sync_loopback_node.py resolves an analogous ordering question by having
 *  PYTHON mint the identity and print it first -- impossible to reuse
 *  here, since python is not even spawned until the port is known. The
 *  resolution: identity minting moves entirely to the KOTLIN side for this
 *  one gate. `genKeypair`/`signedCert` (hoisted from GossipServerTest.kt --
 *  see that file's own hoist doc) mint a REAL Ed25519 identity + device +
 *  signed `EnrollmentCert` here, in Kotlin, and the raw key material is
 *  handed to the spawned python process as a JSON blob (`sys.argv[3]`);
 *  `sync_loopback_node.py`'s new `_node_from_external_keys` (Task 5) adopts
 *  it byte-for-byte into a REAL `HearthNode` (keys.json write + store
 *  seeding, mirroring what `HearthNode.create()` does internally) instead
 *  of generating its own. This works because KotlinWire's Ed25519 signing
 *  and canonical-JSON cert body (`certBody`/`signRaw`) are ALREADY
 *  byte-matched to hearth/identity.py (KotlinWire.kt's own module doc: "byte-
 *  matched to hearth/identity.py via the committed wire_vectors.json") --
 *  this gate is simply the first time that fact is exercised in the
 *  KOTLIN-SIGNS/PYTHON-VERIFIES direction (every prior scenario only ever
 *  had python mint certs for Kotlin to verify) rather than a new interop
 *  claim. Verified against a real python responder (not yet Kotlin) via a
 *  throwaway standalone script before this file was written; the assertions
 *  below are what actually pins the claim against the real Kotlin
 *  GossipServer/KotlinSync.serve path.
 *
 *  The spec blob is built with `KotlinWire.dumps` (`dialSpec` below), NOT
 *  `org.json.JSONObject` -- load-bearing, not a style choice:
 *  `EnrollmentCert.body()`'s canonical form requires `enrolled_at` to
 *  render as a Python-`repr()`-compatible float literal (e.g.
 *  "1752900000.0"), which is exactly what `KotlinWire.PyFloat` +
 *  `pyFloatRepr` guarantee (already proven -- it's the SAME mechanism the
 *  real wire frames use). `org.json.JSONObject.put(String, Double)` instead
 *  uses `JSONObject.numberToString`, which STRIPS the trailing ".0" from a
 *  whole-number double (rendering "1752900000", a JSON integer) -- since
 *  the cert's own signature was computed over the ".0"-suffixed canonical
 *  bytes, a round trip through `org.json` before python ever sees it would
 *  silently reproduce DIFFERENT canonical bytes on python's re-derivation,
 *  and `EnrollmentCert.verify()` would fail closed -- a real interop bug
 *  masquerading as a mysterious cert-rejection failure. `org.json.
 *  JSONObject` is still used (safely) to PARSE the single reply line each
 *  dial prints -- reading untyped/already-produced JSON has no such
 *  formatting hazard, only PRODUCING JSON containing a signed float does.
 *
 *  Sub-scenarios (each its own @Test, matching task-5-brief.md exactly):
 *   1. ownSiblingPullsPhoneContentAndPhoneIngestsNodesPush -- the node's
 *      identity IS the phone fixture's identity (a genuine second/sibling
 *      device of the SAME identity, minted here exactly the way every
 *      other scenario's `mint_fixture` mints a phone device under a node's
 *      own identity -- just with the roles of "who dials" and "who serves"
 *      swapped). The phone's seeded store holds one message signed by the
 *      PHONE's own device (a plaintext KIND_PROFILE, "PushedFromPhone" --
 *      profile has no audience gate in either store's messages_not_in
 *      port, keeping this test about ROUTING, not content-decrypt fidelity,
 *      which is already covered elsewhere e.g. phoneDecryptsRealBackfilled
 *      Content); the node publishes its own plaintext marker
 *      ("PushedFromNode") before dialing. Asserts: the node's reported
 *      `received_ids` contains the phone's seeded msgId (a REAL pull
 *      through `KotlinSync.serve`'s MESSAGES-phase give side); AND,
 *      independently, the phone's OWN store (still in this JVM process --
 *      `GossipServer` holds the same `InMemorySyncStore` reference this
 *      test built) now contains the node's pushed marker (a REAL ingest
 *      through `KotlinSync.serve`'s MESSAGES-phase read side). Both
 *      directions of one bidirectional sync round, both proven for real.
 *   2. friendReceivesOnlyEntitledContentOverServeNegative -- THE SECURITY
 *      PROOF. The node's identity is a FRIEND identity the phone already
 *      knows (`store.addIdentity` + a registrar profile message so
 *      `deviceViews(friendIdentityPub)` resolves the friend's real device,
 *      the exact device the connecting node will authenticate as -- see
 *      SyncStore.kt's `messagesNotIn` doc for why peerDevices must come
 *      from a stored, verified message rather than a live claim). Seeds,
 *      under the PHONE's OWN identity+device: (a) one kreds POST wrapped
 *      to the friend's device (entitled); (b) an inner-ring RING record
 *      (author-private -- never routes to anyone but the author, wraps
 *      irrelevant); (c) a kreds POST with an EMPTY wraps dict (present,
 *      but the friend's device is not named -- the direct over-serve
 *      shape); (d) a DM addressed to an unrelated third party (DMs relay
 *      only to (author, recipient), never a mutual friend). Asserts the
 *      node's `received_ids` CONTAINS (a)'s msgId and CONTAINS NONE of
 *      (b)/(c)/(d)'s -- the entitlement filter (`KotlinSync.serve` ->
 *      `SyncStore.messagesNotIn` -> `filterMessagesNotIn`, arc 1 Task 1)
 *      proven to under-serve correctly against a REAL cryptographically
 *      authenticated peer on the real wire, not a mock/fake Stream.
 *   3. strangerIsRefusedAtAuthNothingServed -- the node's identity is
 *      NEVER added to the phone's store. `respondHandshake`'s isKnown gate
 *      (arc 1 Task 2) writes `{"t":"refused"}` right after AUTH succeeds,
 *      landing in the wire slot a real initiator's REVOCATIONS-phase read
 *      expects (see KotlinHandshake.kt's own doc on respondHandshake) --
 *      hearth's real `_session`, dialing as initiator, receives exactly
 *      that frame and raises `PeerRefused`. Asserted via the event shape
 *      `_run_dial` reports on any dial failure: `peer_identity` set means
 *      PeerRefused specifically (not a generic I/O failure) -- and, per
 *      `PeerRefused`'s own contract (verified empirically against a real
 *      python responder while building the harness side of this gate --
 *      see `_run_dial`'s own doc in sync_loopback_node.py), that field
 *      carries the identity of the PARTY THAT REFUSED (the phone), not the
 *      stranger's own dialing identity -- asserted equal to the phone's
 *      identity_pub, the strongest available proof that it was genuinely
 *      THIS phone's refusal and not some coincidental other failure.
 */

// -- Key/cert/message minting + process-spawn helpers (Task 5) --------------

/** Builds a `SignedMessage`, REALLY signed (unlike GossipServerTest's own
 *  `identityMsg`, whose embedded cert carries a placeholder "00" signature
 *  -- fine there, since both ends of that file's tests are Kotlin and
 *  `InMemorySyncStore.ingestMessage` never re-verifies an embedded cert's
 *  OWN enrollment signature, only the message's device signature). Content
 *  seeded here can transit the REAL wire to a REAL python `hearth` node,
 *  whose `Store.ingest_message` -> `Verifier.verify_message` DOES call
 *  `msg.cert.verify()` (identity.py:562) -- so `cert` must be a genuinely
 *  valid `EnrollmentCert` (i.e. produced by `signedCert`, not hand-rolled),
 *  or a message that SHOULD be entitled would be silently dropped by the
 *  real node's own admission gate, masquerading as an over-serve-negative
 *  false pass. */
private fun signedMsg(
    cert: KotlinWire.CertDict, devicePriv: String, seq: Int, payload: Map<String, Any?>,
): SignedMessage {
    val unsigned = SignedMessage(cert, seq, payload, "")
    return unsigned.copy(signature = KotlinWire.signRaw(devicePriv, unsigned.body()))
}

/** A `wraps` map naming exactly `devicePubs`, shape-valid against hearth's
 *  `_valid_wraps` (messages.py: hex64 device key, `eph_pub` hex64, `nonce`
 *  hex24, non-empty-hex `wrapped_key`) but with DUMMY values throughout --
 *  this gate proves WIRE-LEVEL ROUTING (does a message transit at all),
 *  never decrypt fidelity (already covered by phoneDecryptsRealBackfilled
 *  Content/phoneReadsFriendContentEndToEnd in SyncLoopbackTest.kt), so the
 *  wrapped-key bytes never need to actually unwrap to anything. */
private fun wrapsFor(vararg devicePubs: String): Map<String, Any?> =
    devicePubs.associateWith {
        mapOf("eph_pub" to "00".repeat(32), "nonce" to "00".repeat(12), "wrapped_key" to "ab".repeat(16))
    }

private fun certWireMap(c: KotlinWire.CertDict): Map<String, Any?> = mapOf(
    "identity_pub" to c.identity_pub, "device_pub" to c.device_pub,
    "device_name" to c.device_name, "enrolled_at" to KotlinWire.PyFloat(c.enrolled_at),
    "signature" to c.signature,
)

/** Builds the JSON blob `sync_loopback_node.py`'s new "dial" scenario reads
 *  as `sys.argv[3]` (`_run_dial`'s `spec` parameter -- see that function's
 *  own doc for the exact shape). Serialized via `KotlinWire.dumps`, NOT
 *  `org.json` -- see this file's class doc for why that distinction is
 *  load-bearing for the embedded cert's `enrolled_at` float. */
private fun dialSpec(
    scenario: String, port: Int, identityPriv: String, devicePriv: String,
    devicePub: String, deviceName: String, cert: KotlinWire.CertDict,
    alsoKnown: List<String> = emptyList(),
    phoneDevicePub: String? = null, phoneIdentityPub: String? = null,
): String {
    val m = linkedMapOf<String, Any?>(
        "scenario" to scenario, "port" to port,
        "identity_priv" to identityPriv, "device_priv" to devicePriv,
        "device_pub" to devicePub, "device_name" to deviceName,
        "cert" to certWireMap(cert), "also_known" to alsoKnown,
    )
    if (phoneDevicePub != null) m["phone_device_pub"] = phoneDevicePub
    if (phoneIdentityPub != null) m["phone_identity_pub"] = phoneIdentityPub
    return KotlinWire.dumps(m)
}

/** Spawns `sync_loopback_node.py dial <specFile>` -- mirrors `spawnNode`'s
 *  (SyncLoopbackTest.kt) repo-root discovery + real venv python idiom, but
 *  a DIFFERENT invocation shape (`"dial"` needs a whole JSON spec, not a
 *  plain scenario NAME) and a different lifecycle: every OTHER spawned
 *  node in this test suite serves until killed (`NodeProcess.kill()`);
 *  THIS process is a pure CLIENT that dials once, prints exactly one JSON
 *  event line, and exits on its own -- no accept loop, nothing to kill
 *  mid-test.
 *
 *  `jsonSpec` is written to a FILE in the node's own temp data dir and
 *  the PATH is passed as the argv element, NOT the JSON text itself --
 *  found empirically while building this gate: Java's `ProcessBuilder` on
 *  Windows silently STRIPS every embedded double-quote character when it
 *  assembles the native command line (confirmed with a standalone probe:
 *  an argv element `{"a":"b"}` arrives at the child process as `{a:b}`),
 *  so a raw JSON string is unusable as a Windows argv element here --  not
 *  even a loud failure at this end, since the process still launches and
 *  only fails deep inside python's `json.loads` on the mangled text. A
 *  file path contains no quote characters and sidesteps the whole problem. */
private fun spawnDialNode(jsonSpec: String): Pair<Process, BufferedReader> {
    val repo = findRepoRoot()
    val venvPy = File(repo, ".venv/Scripts/python.exe")
    val script = File(repo, "android_tor_spike/tools/sync_loopback_node.py")
    val tmp = Files.createTempDirectory("syncservegate").toFile()
    val specFile = File(tmp, "spec.json")
    specFile.writeText(jsonSpec, Charsets.UTF_8)
    val proc = ProcessBuilder(venvPy.absolutePath, script.absolutePath, tmp.absolutePath, "dial", specFile.absolutePath)
        .redirectErrorStream(false)
        .start()
    val stdout = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
    return proc to stdout
}

/** Blocks for the dial node's single reply line ("served"/"refused"/
 *  "failed" -- see `_run_dial`'s doc). On EOF-before-a-line (the process
 *  crashed before printing anything), dumps stderr for diagnostics and
 *  fails loudly -- mirrors `spawnNode`'s own "no line -> dump stderr"
 *  failure shape (SyncLoopbackTest.kt). */
private fun readDialEvent(proc: Process, stdout: BufferedReader): JSONObject {
    val line = stdout.readLine()
        ?: run {
            val err = proc.errorStream.bufferedReader().readText()
            throw RuntimeException(
                "dial node produced no output before closing stdout; stderr:\n$err")
        }
    return JSONObject(line)
}

/** The dial process exits on its own once it has printed its one event
 *  line -- this just bounds how long the test waits for that exit before
 *  giving up and forcing it, so a hung child process can never wedge the
 *  JVM test runner. */
private fun awaitDialExit(proc: Process) {
    if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroyForcibly()
}

class SyncServeLoopbackTest {

    @Test fun ownSiblingPullsPhoneContentAndPhoneIngestsNodesPush() {
        val (identityPriv, identityPub) = genKeypair()
        val (nodeDevicePriv, nodeDevicePub) = genKeypair()
        val nodeCert = signedCert(identityPriv, identityPub, nodeDevicePub, "Node Device")
        val (phoneDevicePriv, phoneDevicePub) = genKeypair()
        val phoneCert = signedCert(identityPriv, identityPub, phoneDevicePub, "Phone Device")
        val phoneFixture = KotlinHandshake.Fixture(phoneDevicePriv, phoneDevicePub, phoneCert, "unused.onion:9997")

        val store = InMemorySyncStore()
        store.addIdentity(identityPub)
        // The phone's own seeded content the node should PULL -- plaintext
        // (KIND_PROFILE, no audience gate anywhere in messagesNotIn), so
        // this test stays about ROUTING, not decrypt fidelity.
        val phonePush = signedMsg(phoneCert, phoneDevicePriv, 1,
            mapOf("kind" to "profile", "name" to "PushedFromPhone", "created_at" to KotlinWire.PyFloat(1.0)))
        assertTrue(store.ingestMessage(phonePush))

        val gossipServer = GossipServer(store, { phoneFixture }, ReentrantLock(), 0)
        val port = gossipServer.start()
        try {
            val spec = dialSpec(
                scenario = "own_sibling", port = port, identityPriv = identityPriv,
                devicePriv = nodeDevicePriv, devicePub = nodeDevicePub, deviceName = "Node Device",
                cert = nodeCert, phoneDevicePub = phoneDevicePub)
            val (proc, stdout) = spawnDialNode(spec)
            try {
                val event = readDialEvent(proc, stdout)
                assertEquals("dial event: $event", "served", event.optString("event"))
                assertTrue("sync must have completed ok: $event", event.getBoolean("ok"))
                val receivedIds = event.getJSONArray("received_ids")
                val receivedSet = (0 until receivedIds.length()).map { receivedIds.getString(it) }.toSet()
                assertTrue("node must have pulled the phone's seeded content: $event",
                    receivedSet.contains(phonePush.msgId()))
            } finally {
                awaitDialExit(proc)
            }

            // Independent check, on the PHONE's OWN store (still this JVM
            // process -- GossipServer holds the same `store` reference):
            // the node's push landed via a REAL ingest through
            // KotlinSync.serve's MESSAGES-phase read side.
            assertTrue("phone must have ingested the node's pushed profile marker",
                store.allMessages().any { it.payload["name"] == "PushedFromNode" })
        } finally {
            gossipServer.stop()
        }
    }

    @Test fun friendReceivesOnlyEntitledContentOverServeNegative() {
        val (phoneIdentityPriv, phoneIdentityPub) = genKeypair()
        val (phoneDevicePriv, phoneDevicePub) = genKeypair()
        val phoneCert = signedCert(phoneIdentityPriv, phoneIdentityPub, phoneDevicePub, "Phone Device")
        val phoneFixture = KotlinHandshake.Fixture(phoneDevicePriv, phoneDevicePub, phoneCert, "unused.onion:9997")

        val (friendIdentityPriv, friendIdentityPub) = genKeypair()
        val (friendDevicePriv, friendDevicePub) = genKeypair()
        val friendCert = signedCert(friendIdentityPriv, friendIdentityPub, friendDevicePub, "Friend Device")

        val thirdPartyPub = genKeypair().second   // arbitrary hex64 identity, never registered anywhere

        val store = InMemorySyncStore()
        store.addIdentity(phoneIdentityPub)
        store.addIdentity(friendIdentityPub)   // isKnown gate: the friend IS known

        // Registers deviceViews(friendIdentityPub) = {friendDevicePub} --
        // the exact device the REAL connecting node will authenticate as
        // (Kotlin minted friendCert/friendDevicePriv for both). PLAINTEXT
        // (KIND_PROFILE, no audience gate), signed by the friend's own device.
        val friendRegistrar = signedMsg(friendCert, friendDevicePriv, 1,
            mapOf("kind" to "profile", "name" to "Friend", "created_at" to KotlinWire.PyFloat(1.0)))
        assertTrue(store.ingestMessage(friendRegistrar))

        // (a) ENTITLED: a kreds post wrapped to the friend's device.
        val entitledPost = signedMsg(phoneCert, phoneDevicePriv, 2, mapOf(
            "kind" to "post", "scope" to "kreds", "placement" to "journal",
            "body_nonce" to "11".repeat(12), "body_ct" to "ab".repeat(8),
            "wraps" to wrapsFor(friendDevicePub), "blobs" to emptyList<String>(),
            "created_at" to KotlinWire.PyFloat(2.0), "media" to "photo"))
        assertTrue(store.ingestMessage(entitledPost))

        // (b) NOT entitled: an inner-ring record -- author-private, routes
        // to nobody but the author regardless of wraps.
        val ringRecord = signedMsg(phoneCert, phoneDevicePriv, 3, mapOf(
            "kind" to "ring", "member" to friendIdentityPub, "ring" to "inner",
            "created_at" to KotlinWire.PyFloat(3.0)))
        assertTrue(store.ingestMessage(ringRecord))

        // (c) NOT entitled: a kreds post whose wraps dict does NOT name the
        // friend's device -- the direct over-serve shape.
        val unwrappedPost = signedMsg(phoneCert, phoneDevicePriv, 4, mapOf(
            "kind" to "post", "scope" to "kreds", "placement" to "journal",
            "body_nonce" to "22".repeat(12), "body_ct" to "cd".repeat(8),
            "wraps" to emptyMap<String, Any?>(), "blobs" to emptyList<String>(),
            "created_at" to KotlinWire.PyFloat(4.0), "media" to "photo"))
        assertTrue(store.ingestMessage(unwrappedPost))

        // (d) NOT entitled: a DM addressed to an unrelated third party --
        // DMs relay only to (author, recipient), never a mutual friend.
        val thirdPartyDm = signedMsg(phoneCert, phoneDevicePriv, 5, mapOf(
            "kind" to "dm", "to" to thirdPartyPub, "body_nonce" to "33".repeat(12),
            "body_ct" to "ef".repeat(8), "wraps" to emptyMap<String, Any?>(),
            "blobs" to emptyList<String>(), "created_at" to KotlinWire.PyFloat(5.0)))
        assertTrue(store.ingestMessage(thirdPartyDm))

        val gossipServer = GossipServer(store, { phoneFixture }, ReentrantLock(), 0)
        val port = gossipServer.start()
        try {
            val spec = dialSpec(
                scenario = "friend", port = port, identityPriv = friendIdentityPriv,
                devicePriv = friendDevicePriv, devicePub = friendDevicePub, deviceName = "Friend Device",
                cert = friendCert, alsoKnown = listOf(phoneIdentityPub), phoneIdentityPub = phoneIdentityPub)
            val (proc, stdout) = spawnDialNode(spec)
            try {
                val event = readDialEvent(proc, stdout)
                assertEquals("dial event: $event", "served", event.optString("event"))
                assertTrue("sync must have completed ok: $event", event.getBoolean("ok"))
                val receivedIds = event.getJSONArray("received_ids")
                val receivedSet = (0 until receivedIds.length()).map { receivedIds.getString(it) }.toSet()

                assertTrue("friend must receive the entitled kreds post: $event",
                    receivedSet.contains(entitledPost.msgId()))
                assertFalse("friend must NOT receive the inner-ring record (author-private): $event",
                    receivedSet.contains(ringRecord.msgId()))
                assertFalse("friend must NOT receive the post not wrapped to it -- OVER-SERVE: $event",
                    receivedSet.contains(unwrappedPost.msgId()))
                assertFalse("friend must NOT receive a DM addressed to a third party: $event",
                    receivedSet.contains(thirdPartyDm.msgId()))
            } finally {
                awaitDialExit(proc)
            }
        } finally {
            gossipServer.stop()
        }
    }

    @Test fun strangerIsRefusedAtAuthNothingServed() {
        val (phoneIdentityPriv, phoneIdentityPub) = genKeypair()
        val (phoneDevicePriv, phoneDevicePub) = genKeypair()
        val phoneCert = signedCert(phoneIdentityPriv, phoneIdentityPub, phoneDevicePub, "Phone Device")
        val phoneFixture = KotlinHandshake.Fixture(phoneDevicePriv, phoneDevicePub, phoneCert, "unused.onion:9997")

        val (strangerIdentityPriv, strangerIdentityPub) = genKeypair()
        val (strangerDevicePriv, strangerDevicePub) = genKeypair()
        val strangerCert = signedCert(strangerIdentityPriv, strangerIdentityPub, strangerDevicePub, "Stranger Device")

        val store = InMemorySyncStore()
        store.addIdentity(phoneIdentityPub)
        // Deliberately NOT store.addIdentity(strangerIdentityPub) -- the
        // phone must not know this identity, so respondHandshake's isKnown
        // gate refuses it.

        val gossipServer = GossipServer(store, { phoneFixture }, ReentrantLock(), 0)
        val port = gossipServer.start()
        try {
            val spec = dialSpec(
                scenario = "stranger", port = port, identityPriv = strangerIdentityPriv,
                devicePriv = strangerDevicePriv, devicePub = strangerDevicePub, deviceName = "Stranger Device",
                cert = strangerCert, alsoKnown = listOf(phoneIdentityPub))
            val (proc, stdout) = spawnDialNode(spec)
            try {
                val event = readDialEvent(proc, stdout)
                assertEquals("dial event: $event -- a divergence here (over-serve, or a wrong " +
                    "failure shape) is a REAL parity bug, not a harness issue", "refused", event.optString("event"))
                // PeerRefused.peer_identity names the party that refused US
                // (the phone), not the stranger's own dialing identity --
                // see _run_dial's own doc in sync_loopback_node.py for the
                // empirical trace pinning this down.
                assertEquals("phone must be the identity that refused -- proves this really was " +
                    "the phone's own AUTH-gate refusal, not an unrelated failure",
                    phoneIdentityPub, event.optString("peer_identity"))
            } finally {
                awaitDialExit(proc)
            }
        } finally {
            gossipServer.stop()
        }
    }
}
