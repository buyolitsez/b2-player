package dev.buyolitsez.b2player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------- lyrics ----------------

// Shown in place of the cover on Now Playing. A tap anywhere calls [toggle], back to the cover.
// Translate puts Claude's translation next to the original, line by line.
@UnstableApi
@Composable
fun LyricsPanel(ui: Ui, t: Track, modifier: Modifier, toggle: () -> Unit) {
    val lyrics by produceState<Lyrics?>(null, t.id) { value = withContext(Dispatchers.IO) { ui.app.lyrics.get(t) } }
    val l = lyrics
    var language by remember(t.id) { mutableStateOf<String?>(null) }
    // null while translating; an error message when it failed.
    var translation by remember(t.id) { mutableStateOf<Result<List<String>>?>(null) }
    val lines = l?.let { it.synced?.map { x -> x.text } ?: it.plain?.lines() }
    LaunchedEffect(t.id, language, l) {
        translation = null
        val lang = language ?: return@LaunchedEffect
        if (lines == null || ui.assistant.apiKey.isEmpty()) return@LaunchedEffect
        translation = withContext(Dispatchers.IO) {
            runCatching { ui.app.lyrics.translate(t, lines, lang, ui.assistant.apiKey) }
                .onFailure { AppLog.w("lyrics", "Translation failed for ${t.id}", it) }
        }
    }
    val tr = translation
    val translated = tr?.getOrNull()?.takeIf { language != null && it.any(String::isNotEmpty) }
    val note = when {
        language == null -> null
        ui.assistant.apiKey.isEmpty() -> "Add a Claude API key in Settings to translate"
        tr == null -> "Translating into $language…"
        tr.isFailure -> "Translation failed: ${tr.exceptionOrNull()?.message}"
        translated == null -> "The lyrics are already in $language"
        else -> null
    }
    Box(modifier) {
        when {
            l == null -> Placeholder("Looking for lyrics…")
            l.empty -> Placeholder(if (ui.prefs.onlineLyrics.value) "No lyrics found" else "No lyrics in the file. Online lyrics are off in settings")
            l.synced != null -> SyncedLyrics(ui, l.synced, translated, l.source, note, toggle)
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                note?.let { Text(it, Modifier.padding(bottom = 12.dp, end = 40.dp), style = MaterialTheme.typography.bodySmall, color = Muted) }
                if (translated == null) Text(l.plain!!, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp))
                else lines!!.forEachIndexed { i, line -> LyricRow(line, translated.getOrNull(i), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f), FontWeight.Normal, Modifier.padding(vertical = 3.dp)) }
                Text("Source: ${l.source}", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        if (l != null && !l.empty) Box(Modifier.align(Alignment.TopEnd)) {
            var open by remember { mutableStateOf(false) }
            IconButton({ open = true }) { Icon(Icons.Default.Translate, "Translate", tint = if (language != null) MaterialTheme.colorScheme.primary else Muted) }
            DropdownMenu(open, { open = false }, containerColor = Gray2) {
                listOf("English", "Russian").forEach { lang -> DropdownMenuItem({ Text(lang) }, { open = false; language = lang }) }
                if (language != null) DropdownMenuItem({ Text("Original only") }, { open = false; language = null })
            }
        }
    }
}

// One line of lyrics, with its translation on the right when there is one.
@Composable
private fun LyricRow(text: String, translation: String?, color: Color, weight: FontWeight, modifier: Modifier) {
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (translation == null) 19.sp else 16.sp, lineHeight = if (translation == null) 26.sp else 22.sp)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text.ifBlank { "♪" }, Modifier.weight(1f), style = style, fontWeight = weight, color = color)
        if (translation != null) Text(translation, Modifier.weight(1f), style = style, fontWeight = weight, color = color.copy(alpha = 0.75f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@UnstableApi
@Composable
private fun SyncedLyrics(ui: Ui, lines: List<LyricLine>, translation: List<String>?, source: String, note: String?, toggle: () -> Unit) {
    val c = ui.controller ?: return
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(c) { while (true) { pos = c.currentPosition; delay(200) } }
    val current = lines.indexOfLast { it.ms <= pos }
    val state = rememberLazyListState()
    LaunchedEffect(current) { if (current > 2) state.animateScrollToItem(current - 2 + if (note != null) 1 else 0) }
    LazyColumn(state = state, contentPadding = PaddingValues(16.dp)) {
        note?.let { item { Text(it, Modifier.padding(bottom = 8.dp, end = 40.dp), style = MaterialTheme.typography.bodySmall, color = Muted) } }
        itemsIndexed(lines) { i, line ->
            LyricRow(
                line.text, translation?.getOrNull(i),
                color = if (i == current) MaterialTheme.colorScheme.primary else if (i < current) Muted else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                weight = if (i == current) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.combinedClickable(onLongClick = { c.seekTo(line.ms) }, onClick = toggle).padding(vertical = 6.dp),
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
    // null: the main list; otherwise the "After N minutes" or "After N tracks" picker.
    var picker by remember { mutableStateOf<String?>(null) }
    var custom by remember(picker) { mutableStateOf("") }
    fun set(toast: String, apply: () -> Unit) {
        app.sleepAt.value = null; app.sleepAfterAlbum.value = null; app.sleepAfterTracks.value = null; app.stopAfterId.value = null
        apply(); ui.toast(toast); dismiss()
    }
    fun minutes(m: Int) = set("Will pause in $m min") { app.sleepAt.value = System.currentTimeMillis() + m * 60_000L }
    fun tracks(n: Int) = set(if (n == 0) "Will pause after this track" else "Will pause after ${moreTracks(n)}") { app.sleepAfterTracks.value = n }
    @Composable fun Option(text: String, onClick: () -> Unit) =
        Text(text, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp))
    AlertDialog(
        dismiss, containerColor = Gray2,
        title = { Text(when (picker) { "minutes" -> "Pause after minutes"; "tracks" -> "Pause after tracks"; else -> "Sleep timer" }) },
        text = {
            Column {
                when (picker) {
                    null -> {
                        Option("After N minutes…") { picker = "minutes" }
                        Option("After N tracks…") { picker = "tracks" }
                        if (t != null) {
                            Option("End of this track") { set("Will pause after “${t.title}”") { app.stopAfterId.value = t.id } }
                            Option("End of this album") { set("Will pause after “${t.album.title}”") { app.sleepAfterAlbum.value = t.album.id } }
                        }
                    }
                    "minutes" -> listOf(10, 15, 30, 45, 60, 90).forEach { m -> Option("$m minutes") { minutes(m) } }
                    else -> {
                        Option("0 · after this track") { tracks(0) }
                        Option("1 · after the next one") { tracks(1) }
                        listOf(3, 5).forEach { n -> Option(moreTracks(n)) { tracks(n) } }
                    }
                }
                if (picker != null) {
                    val n = custom.toIntOrNull()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true,
                            label = { Text("Custom") }, suffix = { Text(if (picker == "minutes") "min" else "tracks") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TextButton({ if (picker == "minutes") minutes(n!!) else tracks(n!!) }, enabled = n != null && (n > 0 || picker == "tracks")) { Text("Set") }
                    }
                }
            }
        },
        confirmButton = {
            if (picker == null && (app.sleepAt.value != null || app.sleepAfterAlbum.value != null || app.sleepAfterTracks.value != null || app.stopAfterId.value != null))
                TextButton({ set("Sleep timer off") {} }) { Text("Turn off") }
        },
        dismissButton = { TextButton({ if (picker != null) picker = null else dismiss() }) { Text(if (picker != null) "Back" else "Close") } },
    )
}

private fun moreTracks(n: Int) = if (n == 1) "1 more track" else "$n more tracks"

/** "Pausing in 12:30" / "Pausing after this album", or null when no timer is set. */
@UnstableApi
@Composable
fun sleepLabel(ui: Ui): String? {
    val at by ui.app.sleepAt.collectAsState()
    val album by ui.app.sleepAfterAlbum.collectAsState()
    val stopAfter by ui.app.stopAfterId.collectAsState()
    val tracks by ui.app.sleepAfterTracks.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) { while (at != null) { now = System.currentTimeMillis(); delay(1000) } }
    return when {
        at != null -> "Pausing in ${formatTime(((at!! - now) / 1000.0).coerceAtLeast(0.0))}"
        album != null -> "Pausing after this album"
        tracks == 0 -> "Pausing after this track"
        tracks != null -> "Pausing after ${moreTracks(tracks!!)}"
        stopAfter != null && stopAfter == ui.currentId -> "Pausing after this track"
        else -> null
    }
}
