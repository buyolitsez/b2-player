package dev.buyolitsez.b2player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The shared queue, written by the phone or by cmus on the computer: the tracks played before the current one,
 * the current one and the ones after it, and the position. "queue" starts at the current track, as before "past" was added.
 */
data class Handoff(val past: List<String>, val queue: List<String>, val positionMs: Long, val playing: Boolean, val time: Long, val device: String) {
    val trackId get() = queue.first()

    /**
     * A track still playing on the other device has moved on since it was written. Past its end, the
     * other device stopped without saying so (cmus quit while playing): the written position is the better guess.
     */
    fun positionNow(durationMs: Long): Long {
        val moved = positionMs + (System.currentTimeMillis() - time).coerceAtLeast(0)
        return if (playing && moved < durationMs) moved else positionMs
    }
}

/**
 * One queue across devices: whoever plays writes state/now-playing.json, and the app, while it does not play,
 * takes over what cmus played after this phone did, paused at the same track and position.
 */
class Handoffs(context: Context) {
    private val prefs = context.getSharedPreferences("handoff", Context.MODE_PRIVATE)
    private val writer = Executors.newSingleThreadExecutor()   // uploads land in the order they were made

    /** What the other device played and the phone has not taken over yet. */
    var offer by mutableStateOf<Handoff?>(null)

    /** On play, pause and track change. Only the newest version of the file is kept in the bucket. */
    fun write(b2: B2, past: List<String>, queue: List<String>, positionMs: Long, playing: Boolean) {
        val now = System.currentTimeMillis()
        offer = null
        prefs.edit().putLong("written", now).apply()
        val json = JSONObject().put("version", 1).put("track", queue.first()).put("past", JSONArray(past)).put("queue", JSONArray(queue))
            .put("position_ms", positionMs).put("playing", playing).put("time", now).put("device", "phone")
        writer.execute {
            runCatching {
                val id = b2.upload(KEY, json.toString(1).toByteArray(), "application/json")
                val old = prefs.getString("file", null)
                prefs.edit().putString("file", id).apply()
                if (old != null && old != id) runCatching { b2.deleteVersion(KEY, old) }
            }.onFailure { AppLog.w("handoff", "Now playing upload failed", it) }
        }
    }

    /**
     * Reads the bucket copy; off the main thread. One still playing there is taken over again on each
     * check, so the paused phone keeps up with its position.
     */
    fun check(b2: B2): Handoff? {
        val o = b2.fetchOrNull(KEY)?.let { JSONObject(it.decodeToString()) } ?: return null
        fun ids(name: String) = o.optJSONArray(name)?.let { a -> List(a.length()) { a.getString(it) } }
        val h = Handoff(ids("past").orEmpty(), ids("queue") ?: listOf(o.getString("track")), o.getLong("position_ms"),
            o.optBoolean("playing"), o.getLong("time"), o.getString("device"))
        return h.takeIf { it.device != "phone" && it.time > prefs.getLong("written", 0) && (it.playing || it.time > prefs.getLong("dismissed", 0)) }
    }

    fun taken(h: Handoff) { prefs.edit().putLong("dismissed", h.time).apply(); offer = null }

    companion object {
        const val KEY = "state/now-playing.json"
        /** Enough for a long listen; a 2600-track shuffle would make every upload large. */
        const val QUEUE_MAX = 200
        const val PAST_MAX = 100
    }
}
