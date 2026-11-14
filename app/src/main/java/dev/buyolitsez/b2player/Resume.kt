package dev.buyolitsez.b2player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import org.json.JSONObject
import java.util.concurrent.Executors

/** A track and position written by the phone or by cmus on the computer ("arch"). */
data class Handoff(val trackId: String, val positionMs: Long, val playing: Boolean, val time: Long, val device: String) {
    /** A track still playing on the other device has moved on since it was written. */
    fun positionNow() = if (playing) positionMs + (System.currentTimeMillis() - time).coerceAtLeast(0) else positionMs
}

/**
 * Continue listening across devices: whoever plays writes state/now-playing.json, and the app
 * offers to continue what cmus played after this phone did.
 */
class Handoffs(context: Context) {
    private val prefs = context.getSharedPreferences("handoff", Context.MODE_PRIVATE)
    private val writer = Executors.newSingleThreadExecutor()   // uploads land in the order they were made

    /** What the other device played, shown as the Resume banner. */
    var offer by mutableStateOf<Handoff?>(null)

    /** On play, pause and track change. Only the newest version of the file is kept in the bucket. */
    fun write(b2: B2, trackId: String, positionMs: Long, playing: Boolean) {
        val now = System.currentTimeMillis()
        offer = null
        prefs.edit().putLong("written", now).apply()
        val json = JSONObject().put("version", 1).put("track", trackId).put("position_ms", positionMs)
            .put("playing", playing).put("time", now).put("device", "phone")
        writer.execute {
            runCatching {
                val id = b2.upload(KEY, json.toString(1).toByteArray(), "application/json")
                val old = prefs.getString("file", null)
                prefs.edit().putString("file", id).apply()
                if (old != null && old != id) runCatching { b2.deleteVersion(KEY, old) }
            }.onFailure { AppLog.w("handoff", "Now playing upload failed", it) }
        }
    }

    /** Reads the bucket copy; off the main thread. */
    fun check(b2: B2): Handoff? {
        val o = b2.fetchOrNull(KEY)?.let { JSONObject(it.decodeToString()) } ?: return null
        val h = Handoff(o.getString("track"), o.getLong("position_ms"), o.optBoolean("playing"), o.getLong("time"), o.getString("device"))
        return h.takeIf { it.device != "phone" && it.time > prefs.getLong("written", 0) && it.time > prefs.getLong("dismissed", 0) }
    }

    fun dismiss(h: Handoff) { prefs.edit().putLong("dismissed", h.time).apply(); offer = null }

    companion object { const val KEY = "state/now-playing.json" }
}

@UnstableApi
@Composable
fun ResumeBanner(ui: Ui, cat: Catalog?) {
    val handoffs = ui.app.handoffs
    val h = handoffs.offer ?: return
    val t = cat?.byId?.get(h.trackId) ?: return
    if (ui.playing) return
    val pos = h.positionNow().coerceAtMost((t.duration * 1000).toLong())
    Row(Modifier.fillMaxWidth().background(Gray1).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Continue from ${h.device}", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text("${t.title} · ${formatTime(pos / 1000.0)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton({ handoffs.dismiss(h); ui.play(t.album.tracks, t.album.tracks.indexOf(t), positionMs = pos) }) { Text("Resume") }
        IconButton({ handoffs.dismiss(h) }) { Icon(Icons.Default.Close, "Dismiss") }
    }
}
