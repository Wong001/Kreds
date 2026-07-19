package expo.modules.tormanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Beat(val ts: Long, val ok: Boolean, val latencyMs: Long, val reason: String?)

object HeartbeatStore {
    private const val PREFS = "brick_a_beats"
    private const val KEY = "beats"
    const val MAX_BEATS = 50

    /** Pure: prepend newest, cap at MAX_BEATS. */
    fun append(list: List<Beat>, beat: Beat): List<Beat> =
        (listOf(beat) + list).take(MAX_BEATS)

    fun toJsonArray(list: List<Beat>): String {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().apply {
            put("ts", b.ts); put("ok", b.ok); put("latencyMs", b.latencyMs)
            put("reason", b.reason ?: JSONObject.NULL)
        })
        return arr.toString()
    }

    fun fromJsonArray(s: String): List<Beat> {
        val arr = JSONArray(s)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Beat(o.getLong("ts"), o.getBoolean("ok"), o.getLong("latencyMs"),
                if (o.isNull("reason")) null else o.getString("reason"))
        }
    }

    fun history(ctx: Context): List<Beat> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return if (s == null) emptyList() else fromJsonArray(s)
    }

    fun record(ctx: Context, beat: Beat) {
        val next = append(history(ctx), beat)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, toJsonArray(next)).apply()
    }
}
