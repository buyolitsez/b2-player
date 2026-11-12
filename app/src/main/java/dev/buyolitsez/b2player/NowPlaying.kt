package dev.buyolitsez.b2player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
    if (c == null || t == null) { Placeholder("Ничего не играет"); return }
    var pos by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var info by remember { mutableStateOf(false) }
    LaunchedEffect(c) { while (true) { pos = c.currentPosition; delay(500) } }
    val dur = (t.duration * 1000).toLong().coerceAtLeast(1)
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxSize().background(ui.colors.background).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Cover(ui, t.album.cover, null, Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Text(t.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(t.artist, color = Color(0xFFCCCCCC), modifier = Modifier.clickable { ui.open(Screen.ArtistView(t.album.artist), Tab.Artists) })
        Text(t.album.title, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1,
            modifier = Modifier.clickable { ui.open(Screen.AlbumView(t.album), Tab.Albums) })
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
                    item(if (ui.app.stopAfterId.value == t.id) "Не останавливаться" else "Остановить после трека", Icons.Default.StopCircle) { ui.stopAfter(t) }
                    item("К альбому", Icons.Default.Album) { ui.open(Screen.AlbumView(t.album), Tab.Albums) }
                    item("К исполнителю", Icons.Default.Person) { ui.open(Screen.ArtistView(t.album.artist), Tab.Artists) }
                    item("Папка", Icons.Default.Folder) { ui.open(Screen.FolderView(t.album.dir), Tab.Folders) }
                    item("О файле", Icons.Default.Info) { info = true }
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
            Box(Modifier.size(68.dp).clip(CircleShape).background(accent).clickable { if (ui.playing) c.pause() else c.play() }, Alignment.Center) {
                Icon(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(38.dp), tint = Color.Black)
            }
            IconButton({ c.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, Modifier.size(36.dp)) }
        }
        Spacer(Modifier.weight(1f))
        val next = ui.nextId?.let { cat.byId[it] }
        if (next != null) {
            Row(
                Modifier.fillMaxWidth().clickable { ui.selectTab(Tab.Queue) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.KeyboardArrowUp, null, tint = Muted)
                Text("Далее: ${next.title} — ${next.artist}", color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (info) {
        AlertDialog({ info = false }, confirmButton = { TextButton({ info = false }) { Text("OK") } },
            title = { Text("О файле") }, containerColor = Gray2,
            text = {
                Text(
                    listOfNotNull(
                        t.path, "${t.ext.uppercase()} · ${formatSize(t.size)}",
                        t.start?.let { "CUE: с ${formatTime(it)}" },
                        if (ui.downloaded(t)) "Скачан на телефон" else if (ui.library.isCached(t)) "В кэше" else "Стримится из B2",
                    ).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                )
            })
    }
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
        SmallIcon(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow, Color.White) { c?.run { if (ui.playing) pause() else play() } }
    }
}
