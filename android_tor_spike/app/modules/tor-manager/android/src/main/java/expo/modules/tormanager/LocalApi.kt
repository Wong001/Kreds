package expo.modules.tormanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only /api provider for the WebView shell (slice 1). Pure JSON
 *  builders live in the companion (JVM-testable); the instance methods do the
 *  Android store I/O. A fresh SqliteSyncStore(ctx) is constructed per request
 *  (the existing "new instance per op, cheap at ~253 msgs" pattern). Token is
 *  already validated by LocalWebServer before handle() runs. */
class LocalApi(private val ctx: Context) {

    fun handle(method: String, path: String): HttpResponse? {
        if (method != "GET") return null
        return when (path) {
            "/api/bootstrap" -> json(bootstrapJson())
            "/api/applock" -> json(applockJson())
            "/api/state" -> json(state())
            else -> null
        }
    }

    private fun state(): String {
        val fx = fixtureOrNull()
        val store = SqliteSyncStore(ctx)
        val names = store.profileNames()
        val own = fx?.cert?.identity_pub ?: ""
        val friends = store.knownIdentities().filter { it != own }.map { it to (names[it] ?: it.take(8)) }
        return stateJson(
            identityPub = own,
            devicePub = fx?.device_pub ?: "",
            deviceName = fx?.cert?.device_name ?: "phone",
            profileName = names[own] ?: "",
            friends = friends)
    }

    private fun fixtureOrNull(): KotlinHandshake.Fixture? = try {
        KotlinHandshake.parseFixture(File(TorEngine.externalDir(), "spike_phone_fixture.json").readText())
    } catch (e: Exception) { null }

    companion object {
        fun json(body: String) =
            HttpResponse(200, mapOf("Content-Type" to "application/json; charset=utf-8"), body.toByteArray())

        fun bootstrapJson(): String =
            JSONObject().put("initialized", true).put("onboarding_done", true).toString()

        fun applockJson(): String =
            JSONObject()
                .put("enabled", false).put("locked", false).put("cred_type", JSONObject.NULL)
                .put("settings", JSONObject().put("idle_minutes", 0).put("lock_on_sleep", false))
                .put("throttle_wait", 0).toString()

        fun stateJson(identityPub: String, devicePub: String, deviceName: String,
                      profileName: String, friends: List<Pair<String, String>>): String {
            val friendsArr = JSONArray()
            for ((ipub, name) in friends)
                friendsArr.put(JSONObject().put("identity_pub", ipub).put("name", name))
            return JSONObject()
                .put("identity_pub", identityPub)
                .put("device_pub", devicePub)
                .put("device_name", deviceName)
                .put("profile_name", profileName)
                .put("devices", JSONArray())
                .put("friends", friendsArr)
                .put("peers", JSONArray())
                .put("disconnected", JSONArray())
                .put("revoked", false)
                .put("accent", "#2743d6")
                .put("update_status",
                    JSONObject().put("available", false).put("kind", JSONObject.NULL).put("version", JSONObject.NULL))
                .put("readonly", true)
                .toString()
        }
    }
}
