@file:Suppress("DEPRECATION") // MetadataRetriever: its replacement lives in media3-inspector, a whole module for one class

package dev.buyolitsez.b2player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** ID3 frames (MP3) and iTunes atoms (M4A, which Media3 reports as ID3 frames) under Vorbis comment names, as FLAC has them. */
private val ID3_NAMES = mapOf(
    "TIT2" to "TITLE", "TPE1" to "ARTIST", "TPE2" to "ALBUMARTIST", "TALB" to "ALBUM", "TCON" to "GENRE",
    "TRCK" to "TRACKNUMBER", "TPOS" to "DISCNUMBER", "TDRC" to "DATE", "TYER" to "DATE", "TCOM" to "COMPOSER",
    "TBPM" to "BPM", "TCMP" to "COMPILATION", "TSSE" to "ENCODER", "TPUB" to "LABEL", "TCOP" to "COPYRIGHT",
    "TIT1" to "GROUPING", "TSOT" to "TITLESORT", "TSOP" to "ARTISTSORT", "TSOA" to "ALBUMSORT", "TSO2" to "ALBUMARTISTSORT",
    "TSOC" to "COMPOSERSORT", "TEXT" to "LYRICIST", "TLAN" to "LANGUAGE", "TSRC" to "ISRC", "TMED" to "MEDIA", "TMOO" to "MOOD",
)

/** Audio format line and the file's tags by name; repeated tags (two ARTIST comments) are joined with "; ". */
class TrackTags(val format: String?, val tags: Map<String, String>)

/** Every tag in the track's file, read by Media3 from the file itself: downloaded, cached or streamed from B2. */
fun readTags(app: App, t: Track): TrackTags {
    val groups = MetadataRetriever.Builder(app, app.library.mediaItem(t))
        .setMediaSourceFactory(DefaultMediaSourceFactory(app.dataSourceFactory)).build()
        .use { it.retrieveTrackGroups().get(30, TimeUnit.SECONDS) }
    var format: String? = null
    val tags = LinkedHashMap<String, MutableList<String>>()
    fun add(k: String, v: String) { tags.getOrPut(k) { ArrayList() }.let { if (v !in it) it += v } }
    for (g in 0 until groups.length) {
        val f = groups[g].getFormat(0)
        if (format == null && MimeTypes.isAudio(f.sampleMimeType)) format = listOfNotNull(
            f.codecs?.takeIf { it.isNotEmpty() } ?: f.sampleMimeType?.removePrefix("audio/"),
            f.sampleRate.takeIf { it > 0 }?.let { "%.1f kHz".format(it / 1000.0) },
            f.channelCount.takeIf { it > 0 }?.let { if (it == 1) "mono" else if (it == 2) "stereo" else "$it ch" },
            f.averageBitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
        ).joinToString(" · ")
        val m = f.metadata ?: continue
        for (i in 0 until m.length()) when (val e = m[i]) {
            is androidx.media3.extractor.metadata.flac.VorbisComment -> add(e.key.uppercase(), e.value)
            is TextInformationFrame -> add(e.description?.uppercase() ?: ID3_NAMES[e.id] ?: e.id, e.values.joinToString("; "))
            is CommentFrame -> add(if (e.description.isEmpty()) "COMMENT" else e.description.uppercase(), e.text)
            is InternalFrame -> add(e.description.uppercase(), e.text)
        }
    }
    return TrackTags(format, tags.mapValues { it.value.joinToString("; ") })
}

/**
 * Tag edits made on the phone: state/tags.json in the bucket, {"tracks": {path: {TAG: value}}},
 * an empty value removes the tag. tools/apply-tags.py on the arch writes them into ~/Music and
 * drops what it wrote; until then the app shows them over the file's own tags.
 */
object TagEdits {
    const val KEY = "state/tags.json"

    private fun all(b2: B2) = b2.fetchOrNull(KEY)?.let { JSONObject(it.decodeToString()).optJSONObject("tracks") } ?: JSONObject()

    fun get(b2: B2, path: String): Map<String, String> =
        all(b2).optJSONObject(path)?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap()

    fun set(b2: B2, path: String, edits: Map<String, String>) {
        val tracks = all(b2)
        if (edits.isEmpty()) tracks.remove(path) else tracks.put(path, JSONObject(edits))
        b2.upload(KEY, JSONObject().put("version", 1).put("tracks", tracks).toString(1).toByteArray(), "application/json")
    }
}

private class InfoState(val file: TrackTags, val edits: Map<String, String>) {
    /** What the file will have once the edits are written. */
    val shown: Map<String, String> = (file.tags + edits).filterValues { it.isNotEmpty() }
}

/** "Track info" from a track's menu: where the file is and all its tags, which can be edited. */
@UnstableApi
@Composable
fun TrackInfoDialog(ui: Ui) {
    val t = ui.trackInfo ?: return
    var reload by remember(t.id) { mutableIntStateOf(0) }
    var editing by remember(t.id) { mutableStateOf<SnapshotStateList<Pair<String, String>>?>(null) }
    var newKey by remember(t.id) { mutableStateOf("") }
    var newValue by remember(t.id) { mutableStateOf("") }
    var saving by remember(t.id) { mutableStateOf(false) }
    val info by produceState<Result<InfoState>?>(null, t.id, reload) {
        value = withContext(Dispatchers.IO) {
            runCatching { InfoState(readTags(ui.app, t), TagEdits.get(ui.app.b2, t.path)) }
                .onFailure { AppLog.w("tags", "Reading tags failed for ${t.id}", it) }
        }
    }
    val close = { ui.trackInfo = null }
    val loaded = info?.getOrNull()
    val edit = editing

    fun save(s: InfoState, fields: List<Pair<String, String>>) {
        val final = fields.associate { (k, v) -> k to v.trim() }.toMutableMap()
        for (k in s.file.tags.keys) if (k !in final) final[k] = ""   // removed earlier and still pending
        if (newKey.isNotBlank() && newValue.isNotBlank()) final[newKey.trim().uppercase()] = newValue.trim()
        // Keep only what differs from the file; an emptied field removes the tag.
        val edits = final.filter { (k, v) -> v != (s.file.tags[k] ?: "") }
        saving = true
        ui.scope.launch {
            val err = withContext(Dispatchers.IO) { runCatching { TagEdits.set(ui.app.b2, t.path, edits) }.exceptionOrNull() }
            saving = false
            if (err != null) { AppLog.w("tags", "Saving tag edits failed for ${t.id}", err); ui.toast("Couldn't save: ${err.message}"); return@launch }
            editing = null; newKey = ""; newValue = ""; reload++
            ui.toast(if (edits.isEmpty()) "No changes waiting" else "Saved. tools/apply-tags.py writes them to the file")
        }
    }

    AlertDialog(close, containerColor = Gray2, title = { Text(if (edit != null) "Edit tags" else "Track info") },
        confirmButton = {
            if (edit != null && loaded != null) TextButton({ save(loaded, edit) }, enabled = !saving) { Text("Save") }
            else TextButton(close) { Text("OK") }
        },
        dismissButton = {
            if (edit != null) TextButton({ editing = null; newKey = ""; newValue = "" }) { Text("Cancel") }
            else if (loaded != null) TextButton({ editing = loaded.shown.toList().toMutableStateList() }) { Text("Edit") }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val small = MaterialTheme.typography.bodySmall
                if (edit != null) {
                    Text("Empty a field to remove the tag", style = small, color = Muted)
                    edit.forEachIndexed { i, (k, v) ->
                        OutlinedTextField(v, { edit[i] = k to it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text(k) })
                    }
                    OutlinedTextField(newKey, { newKey = it }, Modifier.fillMaxWidth().padding(top = 14.dp), singleLine = true, label = { Text("New tag name, e.g. MOOD") })
                    OutlinedTextField(newValue, { newValue = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Value") })
                    return@Column
                }
                Text(
                    listOfNotNull(
                        t.path, "${t.ext.uppercase()} · ${formatSize(t.size)} · ${formatTime(t.duration)}",
                        loaded?.file?.format,
                        t.start?.let { "CUE track from ${formatTime(it)}, tags are the whole file's" },
                        if (ui.downloaded(t)) "Downloaded to the phone" else if (ui.library.isCached(t)) "In the stream cache" else "Streamed from B2",
                    ).joinToString("\n"), style = small, color = Muted,
                )
                Spacer(Modifier.height(12.dp))
                val r = info
                when {
                    r == null -> Text("Reading tags…", style = small, color = Muted)
                    loaded == null -> Text("Couldn't read tags: ${r.exceptionOrNull()?.message}", style = small, color = Muted)
                    else -> SelectionContainer {
                        Column {
                            if (loaded.edits.isNotEmpty()) Text(
                                "Edited tags (•) are saved in the bucket and not yet written to the file",
                                Modifier.padding(bottom = 8.dp), style = small, color = MaterialTheme.colorScheme.primary,
                            )
                            if (loaded.shown.isEmpty()) Text("No tags in the file", style = small, color = Muted)
                            loaded.shown.forEach { (k, v) ->
                                Text(if (k in loaded.edits) "$k •" else k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(v, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                            loaded.edits.filterValues { it.isEmpty() }.keys.forEach { k ->
                                Text("$k • removed", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        }
                    }
                }
            }
        })
}
