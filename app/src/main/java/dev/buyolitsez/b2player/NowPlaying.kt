package dev.buyolitsez.b2player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

@UnstableApi
@Composable
fun NowPlayingTab(ui: Ui, cat: Catalog) {
    val c = ui.controller
    val t = ui.currentId?.let { cat.byId[it] }
    if (c == null || t == null) { Placeholder("Nothing is playing"); return }
    var pos by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var sleep by remember { mutableStateOf(false) }
    LaunchedEffect(c) { while (true) { pos = c.currentPosition; delay(500) } }
    val dur = (t.duration * 1000).toLong().coerceAtLeast(1)
    val accent = MaterialTheme.colorScheme.primary
    val background = if (ui.prefs.coverTint.value) ui.colors.background else Color.Black
    // Title, buttons, seek bar; [fill] pushes "Next" to the bottom in portrait.
    @Composable
    fun ColumnScope.details(fill: Boolean) {
        Text(t.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(t.artist, color = Color(0xFFCCCCCC), modifier = Modifier.clickable { ui.open(Screen.ArtistView(t.album.artist), Tab.Artists) })
        Text(t.album.title, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1,
            modifier = Modifier.clickable { ui.open(Screen.AlbumView(t.album), Tab.Albums) })
        // The line is always there, so the controls don't jump when a timer is set.
        val label = sleepLabel(ui)
        Text(label ?: "", Modifier.padding(top = 4.dp).then(if (label != null) Modifier.clickable { sleep = true } else Modifier),
            style = MaterialTheme.typography.bodySmall, color = accent)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(if (ui.liked(t)) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, if (ui.liked(t)) accent else Color.White) { ui.toggleLike(t) }
            if (ui.downloaded(t)) SmallIcon(Icons.Default.DownloadDone, accent) { ui.deleteDownloads(listOf(t)) }
            else SmallIcon(Icons.Default.Download, Color.White) { ui.download(t.id, t.title, listOf(t)) }
            SmallIcon(Icons.Default.Shuffle, if (ui.shuffle) accent else Color.White) { c.shuffleModeEnabled = !ui.shuffle }
            SmallIcon(
                if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                if (ui.repeat != Player.REPEAT_MODE_OFF) accent else Color.White,
            ) {
                c.repeatMode = when (ui.repeat) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                SmallIcon(Icons.Default.MoreHoriz, Color.White) { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = Gray2) {
                    @Composable
                    fun item(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) =
                        DropdownMenuItem({ Text(text) }, { menu = false; action() }, leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) })
                    item("Lyrics", Icons.Default.Lyrics) { ui.open(Screen.LyricsView) }
                    item("Sleep timer", Icons.Default.Bedtime) { sleep = true }
                    item("Equalizer", Icons.Default.Equalizer) { ui.open(Screen.EqualizerView) }
                    item(if (ui.app.stopAfterId.value == t.id) "Don't stop after this" else "Stop after this track", Icons.Default.StopCircle) { ui.stopAfter(t) }
                    item("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) { ui.addToPlaylist = listOf(t) }
                    item("Go to album", Icons.Default.Album) { ui.open(Screen.AlbumView(t.album), Tab.Albums) }
                    item("Go to artist", Icons.Default.Person) { ui.open(Screen.ArtistView(t.album.artist), Tab.Artists) }
                    item("Show folder", Icons.Default.Folder) { ui.open(Screen.FolderView(t.album.dir), Tab.Folders) }
                    item("Track info", Icons.Default.Info) { ui.trackInfo = t }
                }
            }
        }
        Slider(
            value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { c.seekTo((it * dur).toLong()) }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0x33FFFFFF)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((dragging?.let { it * dur } ?: pos.toFloat()) / 1000.0), style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(formatTime(t.duration), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            IconButton({ c.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(36.dp)) }
            Box(Modifier.size(68.dp).clip(CircleShape).background(accent).clickable { if (ui.playing) c.pause() else c.resume() }, Alignment.Center) {
                Icon(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(38.dp), tint = Color.Black)
            }
            IconButton({ c.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, Modifier.size(36.dp)) }
        }
        if (fill) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(8.dp))
        val next = ui.nextId?.let { cat.byId[it] }
        if (next != null) {
            Row(
                Modifier.fillMaxWidth().clickable { ui.selectTab(Tab.Queue) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.KeyboardArrowUp, null, tint = Muted)
                Text("Next: ${next.title} — ${next.artist}", color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val coverSize = maxHeight - 16.dp
        if (maxWidth > maxHeight) {
            // Landscape: cover on the left, everything else scrolls on the right.
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(ui, t.album.cover, coverSize)
                Column(Modifier.weight(1f).padding(start = 24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    details(fill = false)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                Cover(ui, t.album.cover, null, Modifier.fillMaxWidth())
                Spacer(Modifier.height(18.dp))
                details(fill = true)
            }
        }
    }
    if (sleep) SleepTimerDialog(ui) { sleep = false }
}

@UnstableApi
@Composable
fun MiniPlayer(ui: Ui, cat: Catalog?) {
    val t = ui.currentId?.let { cat?.byId?.get(it) } ?: return
    val c = ui.controller
    var drag by remember { mutableFloatStateOf(0f) }
    Row(
        Modifier.fillMaxWidth().background(Gray1)
            .pointerInput(c) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag < -120) c?.seekToNextMediaItem() else if (drag > 120) c?.seekToPreviousMediaItem()
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, dx -> drag += dx }
            }
            .clickable { ui.selectTab(Tab.Now) }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(ui, t.album.cover, 40.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        SmallIcon(if (ui.liked(t)) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
            if (ui.liked(t)) MaterialTheme.colorScheme.primary else Color.White) { ui.toggleLike(t) }
        SmallIcon(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow, Color.White) { c?.run { if (ui.playing) pause() else resume() } }
    }
}
