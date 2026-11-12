@file:Suppress("DEPRECATION") // MetadataRetriever: its replacement lives in media3-inspector, a whole module for one class

package dev.buyolitsez.b2player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** ID3 frames (MP3) and iTunes atoms (M4A, which Media3 reports as ID3 frames) under Vorbis comment names, as FLAC has them. */
private val ID3_NAMES = mapOf(
    "TIT2" to "TITLE", "TPE1" to "ARTIST", "TPE2" to "ALBUMARTIST", "TALB" to "ALBUM", "TCON" to "GENRE",
    "TRCK" to "TRACKNUMBER", "TPOS" to "DISCNUMBER", "TDRC" to "DATE", "TYER" to "DATE", "TCOM" to "COMPOSER",
    "TBPM" to "BPM", "TCMP" to "COMPILATION", "TSSE" to "ENCODER", "TPUB" to "LABEL", "TCOP" to "COPYRIGHT",
    "TIT1" to "GROUPING", "TSOT" to "TITLESORT", "TSOP" to "ARTISTSORT", "TSOA" to "ALBUMSORT", "TSO2" to "ALBUMARTISTSORT",
    "TSOC" to "COMPOSERSORT", "TEXT" to "LYRICIST", "TLAN" to "LANGUAGE", "TSRC" to "ISRC", "TMED" to "MEDIA",
)

class TrackTags(val format: String?, val tags: List<Pair<String, String>>)

/** Every tag in the track's file, read by Media3 from the file itself: downloaded, cached or streamed from B2. */
fun readTags(app: App, t: Track): TrackTags {
    val groups = MetadataRetriever.Builder(app, app.library.mediaItem(t))
        .setMediaSourceFactory(DefaultMediaSourceFactory(app.dataSourceFactory)).build()
        .use { it.retrieveTrackGroups().get(30, TimeUnit.SECONDS) }
    var format: String? = null
    val tags = ArrayList<Pair<String, String>>()
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
            is androidx.media3.extractor.metadata.flac.VorbisComment -> tags += e.key.uppercase() to e.value
            is TextInformationFrame -> tags += (e.description?.uppercase() ?: ID3_NAMES[e.id] ?: e.id) to e.values.joinToString("; ")
            is CommentFrame -> tags += (if (e.description.isEmpty()) "COMMENT" else e.description.uppercase()) to e.text
            is InternalFrame -> tags += e.description.uppercase() to e.text
        }
    }
    return TrackTags(format, tags.distinct())
}

/** "Track info" from a track's menu: where the file is and all its tags. */
@UnstableApi
@Composable
fun TrackInfoDialog(ui: Ui) {
    val t = ui.trackInfo ?: return
    val info by produceState<Result<TrackTags>?>(null, t.id) {
        value = withContext(Dispatchers.IO) { runCatching { readTags(ui.app, t) }.onFailure { AppLog.w("tags", "Reading tags failed for ${t.id}", it) } }
    }
    AlertDialog({ ui.trackInfo = null }, confirmButton = { TextButton({ ui.trackInfo = null }) { Text("OK") } },
        title = { Text("Track info") }, containerColor = Gray2,
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val small = MaterialTheme.typography.bodySmall
                    Text(
                        listOfNotNull(
                            t.path, "${t.ext.uppercase()} · ${formatSize(t.size)} · ${formatTime(t.duration)}",
                            info?.getOrNull()?.format,
                            t.start?.let { "CUE track from ${formatTime(it)}" },
                            if (ui.downloaded(t)) "Downloaded to the phone" else if (ui.library.isCached(t)) "In the stream cache" else "Streamed from B2",
                        ).joinToString("\n"), style = small, color = Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    val r = info
                    when {
                        r == null -> Text("Reading tags…", style = small, color = Muted)
                        r.isFailure -> Text("Couldn't read tags: ${r.exceptionOrNull()?.message}", style = small, color = Muted)
                        r.getOrThrow().tags.isEmpty() -> Text("No tags in the file", style = small, color = Muted)
                        else -> r.getOrThrow().tags.forEach { (k, v) ->
                            Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(v, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        })
}
