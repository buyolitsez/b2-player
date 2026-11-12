package dev.buyolitsez.b2player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------- lyrics ----------------

// Shown in place of the cover on Now Playing. A tap anywhere calls [toggle], back to the cover.
@UnstableApi
@Composable
fun LyricsPanel(ui: Ui, t: Track, modifier: Modifier, toggle: () -> Unit) {
    val lyrics by produceState<Lyrics?>(null, t.id) { value = withContext(Dispatchers.IO) { ui.app.lyrics.get(t) } }
    val l = lyrics
    Box(modifier) {
        when {
            l == null -> Placeholder("Looking for lyrics…")
            l.empty -> Placeholder(if (ui.prefs.onlineLyrics.value) "No lyrics found" else "No lyrics in the file. Online lyrics are off in settings")
            l.synced != null -> SyncedLyrics(ui, l.synced, l.source, toggle)
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(l.plain!!, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp))
                Text("Source: ${l.source}", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@UnstableApi
@Composable
private fun SyncedLyrics(ui: Ui, lines: List<LyricLine>, source: String, toggle: () -> Unit) {
    val c = ui.controller ?: return
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(c) { while (true) { pos = c.currentPosition; delay(200) } }
    val current = lines.indexOfLast { it.ms <= pos }
    val state = rememberLazyListState()
    LaunchedEffect(current) { if (current > 2) state.animateScrollToItem(current - 2) }
    LazyColumn(state = state, contentPadding = PaddingValues(16.dp)) {
        itemsIndexed(lines) { i, line ->
            Text(
                line.text.ifBlank { "♪" },
                Modifier.fillMaxWidth().combinedClickable(onLongClick = { c.seekTo(line.ms) }, onClick = toggle).padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 26.sp),
                fontWeight = if (i == current) FontWeight.Medium else FontWeight.Normal,
                color = if (i == current) MaterialTheme.colorScheme.primary else if (i < current) Muted else Color(0xFFDDDDDD),
            )
        }
        item { Text("Source: $source · hold a line to jump", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted) }
    }
}

// ---------------- equalizer ----------------

@UnstableApi
@Composable
fun EqualizerScreen(ui: Ui) {
    val fx = ui.app.audioFx
    if (!fx.available) { Placeholder("Start playback to use the equalizer"); return }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingSwitch("Equalizer", "Applies to this app only", fx.enabled) { fx.switch(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var open by remember { mutableStateOf(false) }
            Box {
                Pill(if (fx.preset >= 0) fx.presets.getOrElse(fx.preset) { "Preset" } else "Custom") { open = true }
                DropdownMenu(open, { open = false }, containerColor = Gray2) {
                    fx.presets.forEachIndexed { i, name -> DropdownMenuItem({ Text(name) }, { open = false; fx.usePreset(i) }) }
                }
            }
            Pill("Reset") { fx.reset() }
        }
        fx.bands.forEachIndexed { i, hz ->
            val level = fx.levels.getOrElse(i) { 0 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                Slider(
                    level.toFloat(), { fx.setLevel(i, it.toInt()) }, Modifier.weight(1f),
                    valueRange = fx.range.first.toFloat()..fx.range.last.toFloat(), enabled = fx.enabled,
                )
                Text("%+.1f dB".format(level / 100f), Modifier.width(64.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        Text("Bass boost: ${fx.bassStrength / 10}%")
        Slider(fx.bassStrength.toFloat(), { fx.setBass(it.toInt()) }, valueRange = 0f..1000f, enabled = fx.enabled)
    }
}

// ---------------- sleep timer ----------------

@UnstableApi
@Composable
fun SleepTimerDialog(ui: Ui, dismiss: () -> Unit) {
    val app = ui.app
    val t = ui.currentId?.let { app.catalog.value?.byId?.get(it) }
    fun set(minutes: Int) {
        app.sleepAt.value = System.currentTimeMillis() + minutes * 60_000L; app.sleepAfterAlbum.value = null
        ui.toast("Will pause in $minutes min"); dismiss()
    }
    AlertDialog(
        dismiss, containerColor = Gray2, title = { Text("Sleep timer") },
        text = {
            Column {
                listOf(10, 15, 30, 45, 60, 90).forEach { m ->
                    Text("$m minutes", Modifier.fillMaxWidth().clickable { set(m) }.padding(vertical = 10.dp))
                }
                if (t != null) {
                    Text("End of this track", Modifier.fillMaxWidth().clickable {
                        app.sleepAt.value = null; app.sleepAfterAlbum.value = null; app.stopAfterId.value = t.id
                        ui.toast("Will pause after “${t.title}”"); dismiss()
                    }.padding(vertical = 10.dp))
                    Text("End of this album", Modifier.fillMaxWidth().clickable {
                        app.sleepAt.value = null; app.sleepAfterAlbum.value = t.album.id
                        ui.toast("Will pause after “${t.album.title}”"); dismiss()
                    }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = {
            TextButton({
                app.sleepAt.value = null; app.sleepAfterAlbum.value = null; app.stopAfterId.value = null
                ui.toast("Sleep timer off"); dismiss()
            }) { Text("Turn off") }
        },
        dismissButton = { TextButton(dismiss) { Text("Close") } },
    )
}

/** "Pausing in 12:30" / "Pausing after this album", or null when no timer is set. */
@UnstableApi
@Composable
fun sleepLabel(ui: Ui): String? {
    val at by ui.app.sleepAt.collectAsState()
    val album by ui.app.sleepAfterAlbum.collectAsState()
    val stopAfter by ui.app.stopAfterId.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) { while (at != null) { now = System.currentTimeMillis(); delay(1000) } }
    return when {
        at != null -> "Pausing in ${formatTime(((at!! - now) / 1000.0).coerceAtLeast(0.0))}"
        album != null -> "Pausing after this album"
        stopAfter != null && stopAfter == ui.currentId -> "Pausing after this track"
        else -> null
    }
}
