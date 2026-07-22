package expo.modules.tormanager

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** This device's own paired identity (Android first-load pairing, Task 3):
 *  the phone-side counterpart to hearth's keys.json (device_priv/device_pub)
 *  plus the identity_priv + cert a `hearth-pair-package` installs
 *  (node.py:2044-2048's `DeviceKeys.install`). `cert`/`onion_addr` are the
 *  same two fields KotlinHandshake.Fixture already carries (device_priv,
 *  device_pub, cert, onion_addr) -- see toFixture -- plus `identity_priv`,
 *  which Fixture deliberately never carries (Fixture is handed to
 *  HELLO/AUTH code paths that only ever need to prove control of the
 *  DEVICE key, never the identity key). */
object PairingStore {
    private const val FILE_NAME = "pairing.json"
    const val LEGACY_FIXTURE_NAME = "spike_phone_fixture.json"

    data class Identity(
        val device_priv: String, val device_pub: String,
        val cert: KotlinWire.CertDict, val identity_priv: String, val onion_addr: String,
    )

    // -- JSON shape (mirrors KotlinHandshake.parseFixture's cert sub-object) --
    private fun certToJson(c: KotlinWire.CertDict): JSONObject = JSONObject().apply {
        put("identity_pub", c.identity_pub); put("device_pub", c.device_pub)
        put("device_name", c.device_name); put("enrolled_at", c.enrolled_at)
        put("signature", c.signature)
    }

    private fun certFromJson(o: JSONObject) = KotlinWire.CertDict(
        o.getString("identity_pub"), o.getString("device_pub"),
        o.getString("device_name"), o.getDouble("enrolled_at"), o.getString("signature"))

    fun toJson(identity: Identity): String = JSONObject().apply {
        put("device_priv", identity.device_priv)
        put("device_pub", identity.device_pub)
        put("cert", certToJson(identity.cert))
        put("identity_priv", identity.identity_priv)
        put("onion_addr", identity.onion_addr)
    }.toString()

    fun fromJson(json: String): Identity {
        val o = JSONObject(json)
        return Identity(
            o.getString("device_priv"), o.getString("device_pub"),
            certFromJson(o.getJSONObject("cert")),
            o.getString("identity_priv"), o.getString("onion_addr"))
    }

    /** identity_priv is NEVER copied into a Fixture -- Fixture is the
     *  device-key-only shape every HELLO/AUTH path (KotlinHandshake) takes;
     *  the identity key never needs to leave PairingStore. */
    fun toFixture(identity: Identity): KotlinHandshake.Fixture =
        KotlinHandshake.Fixture(identity.device_priv, identity.device_pub, identity.cert, identity.onion_addr)

    // -- persistence (pure JVM: File dir, not Context -- keeps this testable
    //    off-device, same seam InMemorySyncStore gives SqliteSyncStore) ------

    /** Atomic write: write to a temp file in `dir`, then move it over the
     *  target. Uses java.nio.file.Files.move with ATOMIC_MOVE+REPLACE_EXISTING
     *  rather than java.io.File.renameTo -- renameTo is unreliable on Windows
     *  when the target already exists (a real risk here: JVM unit tests run
     *  on a Windows dev box, and a re-pair overwrites an existing
     *  pairing.json), whereas NIO's move uses each platform's native atomic
     *  rename primitive (POSIX rename(2) on Android/Linux; MoveFileEx with
     *  MOVEFILE_REPLACE_EXISTING on Windows), so a crash/kill mid-write
     *  always leaves either the old file intact or nothing -- never a
     *  half-written pairing.json (the file carrying this device's
     *  identity_priv; corrupting it is unrecoverable without re-pairing). */
    fun save(dir: File, identity: Identity) {
        dir.mkdirs()
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(toJson(identity), Charsets.UTF_8)
        Files.move(
            tmp.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun load(dir: File): Identity? = try {
        fromJson(File(dir, FILE_NAME).readText())
    } catch (e: Exception) { null }

    fun hasIdentity(internalDir: File, legacyFile: File): Boolean =
        load(internalDir) != null || (legacyFile.exists() && runCatching {
            KotlinHandshake.parseFixture(legacyFile.readText())
        }.isSuccess)

    // -- shared fixture reader (dual-read, Task 3 brief) --------------------
    // Every fixture-read call site (LocalApi.fixtureOrNull, TorManagerModule's
    // two direct-parse sites, TorNodeService.fixture) now routes through one
    // of these two: internal pairing.json first, else the legacy external
    // spike_phone_fixture.json exactly as today.

    fun readFixture(internalDir: File, legacyFile: File): KotlinHandshake.Fixture {
        val internal = load(internalDir)
        if (internal != null) return toFixture(internal)
        return KotlinHandshake.parseFixture(legacyFile.readText())
    }

    fun readFixtureOrNull(internalDir: File, legacyFile: File): KotlinHandshake.Fixture? = try {
        readFixture(internalDir, legacyFile)
    } catch (e: Exception) { null }

    // -- Context-based convenience wrappers (real Android call sites) -------
    // filesDir/pairing.json internal, TorEngine.externalDir()/
    // spike_phone_fixture.json legacy -- the exact same two locations every
    // pre-refactor call site read.

    private fun legacyFile(): File = File(TorEngine.externalDir(), LEGACY_FIXTURE_NAME)

    fun save(ctx: Context, identity: Identity) = save(ctx.filesDir, identity)
    fun load(ctx: Context): Identity? = load(ctx.filesDir)
    fun hasIdentity(ctx: Context): Boolean = hasIdentity(ctx.filesDir, legacyFile())
    fun readFixture(ctx: Context): KotlinHandshake.Fixture = readFixture(ctx.filesDir, legacyFile())
    fun readFixtureOrNull(ctx: Context): KotlinHandshake.Fixture? = readFixtureOrNull(ctx.filesDir, legacyFile())
}
