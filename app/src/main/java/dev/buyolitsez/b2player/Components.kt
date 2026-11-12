package dev.buyolitsez.b2player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

@UnstableApi
@Composable
fun Cover(ui: Ui, key: String?, size: Dp?, modifier: Modifier = Modifier) {
    val bmp by produceState(key?.let { ui.covers.cached(it) }, key) { if (key != null && value == null) value = ui.covers.load(key) }
    val m = (if (size != null) modifier.size(size) else modifier.aspectRatio(1f)).clip(RoundedCornerShape(3.dp)).background(Gray2)
    Box(m, Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Album, null, tint = Muted)
    }
}

@Composable
fun SmallIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick), Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
    }
}

@Composable
fun Pill(text: String, icon: ImageVector? = null, active: Boolean = false, onClick: () -> Unit) {
    val c = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Gray2).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = c); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = c)
    }
}

@Composable
fun SectionTitle(s: String) =
    Text(s, Modifier.padding(12.dp, 12.dp, 12.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = Muted)

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackRow(
    ui: Ui, t: Track, showCover: Boolean, onClick: () -> Unit,
    queueIndex: Int? = null, dragHandle: (@Composable () -> Unit)? = null,
) {
    val active = if (queueIndex != null) queueIndex == ui.currentIndex else t.id == ui.currentId
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        // The box can report one swipe more than once; act only on the first report.
        if (v != SwipeToDismissBoxValue.Settled && !ui.swipeAllowed()) return@rememberSwipeToDismissBoxState false
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> if (queueIndex != null) ui.moveInQueue(queueIndex, (ui.currentIndex + if (queueIndex > ui.currentIndex) 1 else 0).coerceAtMost(ui.queueIds.size - 1)) else ui.playNext(listOf(t))
            SwipeToDismissBoxValue.EndToStart -> if (queueIndex != null) ui.removeFromQueue(queueIndex) else ui.addToEnd(listOf(t))
            else -> {}
        }
        false
    })
    SwipeToDismissBox(state, backgroundContent = {
        val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        val label = when {
            queueIndex != null && toRight -> "Играть следующим"
            queueIndex != null -> "Убрать из очереди"
            toRight -> "Играть следующим"
            else -> "В конец очереди"
        }
        Box(
            Modifier.fillMaxSize().background(Color(0xFF0F2A1C)).padding(horizontal = 16.dp),
            if (toRight) Alignment.CenterStart else Alignment.CenterEnd,
        ) { Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
    }) {
        Row(
            Modifier.fillMaxWidth().background(if (active) Color(0xFF0D1A12) else Color.Black)
                .clickable(onClick = onClick).padding(start = if (dragHandle != null) 0.dp else 12.dp, end = 0.dp).height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dragHandle?.invoke()
            if (showCover) Cover(ui, t.album.cover, 40.dp)
            else Text(t.number?.toString() ?: "", Modifier.width(26.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                val sub = if (showCover) "${t.artist} · ${t.album.title}" else t.artist
                Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text(formatTime(t.duration), style = MaterialTheme.typography.bodySmall, color = Muted)
            if (ui.downloaded(t)) Icon(Icons.Default.DownloadDone, null, Modifier.padding(start = 6.dp).size(14.dp), tint = Muted)
            if (ui.liked(t)) Icon(Icons.Default.Favorite, null, Modifier.padding(start = 6.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
            TrackMenu(ui, t, queueIndex)
        }
    }
}

@UnstableApi
@Composable
fun TrackMenu(ui: Ui, t: Track, queueIndex: Int? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        SmallIcon(Icons.Default.MoreVert) { open = true }
        DropdownMenu(open, { open = false }, containerColor = Gray2) {
            @Composable
            fun item(text: String, icon: ImageVector, action: () -> Unit) =
                DropdownMenuItem({ Text(text) }, { open = false; action() }, leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) })
            item("Играть следующим", Icons.AutoMirrored.Filled.PlaylistAdd) { ui.playNext(listOf(t)) }
            if (queueIndex == null) item("В конец очереди", Icons.Default.QueueMusic) { ui.addToEnd(listOf(t)) }
            else item("Убрать из очереди", Icons.Default.RemoveCircleOutline) { ui.removeFromQueue(queueIndex) }
            item(
                if (ui.app.stopAfterId.value == t.id) "Не останавливаться" else "Остановить после трека", Icons.Default.StopCircle,
            ) { ui.stopAfter(t) }
            item(if (ui.liked(t)) "Убрать из любимых" else "В любимые", Icons.Default.Favorite) { ui.toggleLike(t) }
            if (ui.downloaded(t)) item("Удалить с телефона", Icons.Default.Delete) { ui.deleteDownloads(listOf(t)) }
            else item("Скачать", Icons.Default.Download) { ui.download(t.id, t.title, listOf(t)) }
            item("К альбому", Icons.Default.Album) { ui.open(Screen.AlbumView(t.album), Tab.Albums) }
            item("К исполнителю", Icons.Default.Person) { ui.open(Screen.ArtistView(t.album.artist), Tab.Artists) }
            item("Папка", Icons.Default.Folder) { ui.open(Screen.FolderView(t.album.dir), Tab.Folders) }
        }
    }
}

@UnstableApi
@Composable
fun AlbumGrid(ui: Ui, albums: List<Album>, header: (@Composable () -> Unit)? = null) {
    if (ui.app.prefs.albumGrid) {
        LazyVerticalGrid(
            GridCells.Fixed(3), contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (header != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) { header() }
            items(albums, key = { it.id }) { a ->
                Column(Modifier.clickable { ui.open(Screen.AlbumView(a)) }) {
                    Cover(ui, a.cover, null, Modifier.fillMaxWidth())
                    Text(a.title, Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(a.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    } else {
        LazyColumn {
            if (header != null) item { header() }
            items(albums, key = { it.id }) { AlbumRow(ui, it) }
        }
    }
}

@UnstableApi
@Composable
fun AlbumRow(ui: Ui, a: Album) {
    Row(
        Modifier.fillMaxWidth().clickable { ui.open(Screen.AlbumView(a)) }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(ui, a.cover, 44.dp)
        Column(Modifier.padding(start = 10.dp)) {
            Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted,
            )
        }
    }
}

@Composable
fun ListRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp).height(50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Muted)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}
