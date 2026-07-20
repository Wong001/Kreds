package expo.modules.tormanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Brick C Task 2: messages/blobs/identities are the pulled counts from a full
// content sync (SyncRunner.SyncOutcome), additive to Brick A's bare-handshake
// Beat -- defaulted to 0 so every existing call site (bootstrap-error beats,
// the "skipped"/"sync failed" mappings in syncCycle, and the JVM test's
// 4-arg constructions) keeps compiling and round-tripping unchanged.
// `skipped` (Brick C Task 3 fix): true iff this Beat represents a mutex skip
// (SyncRunner.SyncOutcome.ran == false) rather than a real failure -- a
// dedicated flag, NOT string-matched off `reason`, so a future wording
// change to the "skipped (in progress)"/"sync already in progress" text
// can never silently regress a benign skip back into looking like a red
// failure. Defaulted false so every pre-existing call site (real
// successes/failures) is unaffected.
data class Beat(
    val ts: Long,
    val ok: Boolean,
    val latencyMs: Long,
    val reason: String?,
    val messages: Int = 0,
    val blobs: Int = 0,
    val identities: Int = 0,
    val skipped: Boolean = false,
)

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
            put("messages", b.messages); put("blobs", b.blobs); put("identities", b.identities)
            put("skipped", b.skipped)
        })
        return arr.toString()
    }

    fun fromJsonArray(s: String): List<Beat> {
        val arr = JSONArray(s)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            // optInt/optBoolean (not getInt/getBoolean): a Beat persisted by a
            // pre-Brick-C (or pre-skipped-flag) build won't have these keys --
            // default them to 0/false rather than throw on upgrade.
            Beat(o.getLong("ts"), o.getBoolean("ok"), o.getLong("latencyMs"),
                if (o.isNull("reason")) null else o.getString("reason"),
                o.optInt("messages", 0), o.optInt("blobs", 0), o.optInt("identities", 0),
                o.optBoolean("skipped", false))
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
