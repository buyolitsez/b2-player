package dev.buyolitsez.b2player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

@UnstableApi
@Composable
fun Cover(ui: Ui, key: String?, size: Dp?, modifier: Modifier = Modifier) {
    // produceState keeps the old value when the key changes, so always reset it to the new cover.
    val small = size != null && size <= 64.dp
    val bmp by produceState(key?.let { ui.covers.cached(it, small) }, key) { value = key?.let { ui.covers.load(it, small) } }
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

/** Search field above a list; the screen filters its own list with [searchFold] of the text. */
@Composable
fun FilterField(value: String, onChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), singleLine = true,
        placeholder = { Text("Search") }, leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) SmallIcon(Icons.Default.Close) { onChange("") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

@Composable
fun SectionTitle(s: String) =
    Text(s, Modifier.padding(12.dp, 14.dp, 12.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
fun MenuItem(text: String, icon: ImageVector, close: () -> Unit, action: () -> Unit) =
    DropdownMenuItem({ Text(text) }, { close(); action() }, leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) })

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackRow(
    ui: Ui, t: Track, showCover: Boolean, onClick: () -> Unit,
    queueIndex: Int? = null, dragHandle: (@Composable () -> Unit)? = null, dragModifier: Modifier = Modifier,
    subtitle: String? = null, extraMenu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null, swipeLeftLabel: String? = null,
) {
    val active = if (queueIndex != null) queueIndex == ui.currentIndex else t.id == ui.currentId
    val row = @Composable {
        Row(
            Modifier.fillMaxWidth().then(dragModifier).background(if (active) ActiveRow else MaterialTheme.colorScheme.background)
                .clickable(onClick = onClick).padding(start = if (dragHandle != null) 0.dp else 12.dp).height(52.dp),
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
                Text(
                    subtitle ?: if (showCover) "${t.artist} · ${t.album.title}" else t.artist,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            }
            Text(formatTime(t.duration), style = MaterialTheme.typography.bodySmall, color = Muted)
            if (ui.downloaded(t)) Icon(Icons.Default.DownloadDone, null, Modifier.padding(start = 6.dp).size(14.dp), tint = Muted)
            if (ui.liked(t)) Icon(Icons.Default.Favorite, null, Modifier.padding(start = 6.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
            TrackMenu(ui, t, queueIndex, extraMenu)
        }
    }
    if (!ui.prefs.swipeActions.value) { row(); return }
    // The state is remembered with the lambda it was created with, so the action goes through
    // rememberUpdatedState: otherwise it uses the track and queue index of the row's first composition.
    val onSwipe by rememberUpdatedState { v: SwipeToDismissBoxValue ->
        when {
            v == SwipeToDismissBoxValue.StartToEnd && queueIndex != null ->
                ui.moveInQueue(queueIndex, (ui.currentIndex + if (queueIndex > ui.currentIndex) 1 else 0).coerceAtMost(ui.queueIds.size - 1))
            v == SwipeToDismissBoxValue.StartToEnd -> ui.playNext(listOf(t))
            queueIndex != null -> ui.removeFromQueue(queueIndex)
            onSwipeLeft != null -> onSwipeLeft()
            else -> ui.addToEnd(listOf(t))
        }
    }
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        // The box can report one swipe more than once; act only on the first report.
        if (v != SwipeToDismissBoxValue.Settled && ui.swipeAllowed()) onSwipe(v)
        false
    })
    SwipeToDismissBox(state, backgroundContent = {
        val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        val label = when {
            toRight -> "Play next"
            queueIndex != null -> "Remove from queue"
            swipeLeftLabel != null -> swipeLeftLabel
            else -> "Add to queue"
        }
        Box(
            Modifier.fillMaxSize().background(SwipeRow).padding(horizontal = 16.dp),
            if (toRight) Alignment.CenterStart else Alignment.CenterEnd,
        ) { Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
    }) { row() }
}

@UnstableApi
@Composable
fun TrackMenu(ui: Ui, t: Track, queueIndex: Int? = null, extra: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    val close = { open = false }
    Box {
        SmallIcon(Icons.Default.MoreVert) { open = true }
        DropdownMenu(open, close, containerColor = Gray2) {
            MenuItem("Play next", Icons.AutoMirrored.Filled.PlaylistPlay, close) { ui.playNext(listOf(t)) }
            if (queueIndex == null) MenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic, close) { ui.addToEnd(listOf(t)) }
            else MenuItem("Remove from queue", Icons.Default.RemoveCircleOutline, close) { ui.removeFromQueue(queueIndex) }
            MenuItem(if (ui.app.stopAfterId.value == t.id) "Don't stop after this" else "Stop after this track", Icons.Default.StopCircle, close) { ui.stopAfter(t) }
            MenuItem("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd, close) { ui.addToPlaylist = listOf(t) }
            MenuItem(if (ui.liked(t)) "Unlike" else "Like", Icons.Default.Favorite, close) { ui.toggleLike(t) }
            if (ui.downloaded(t)) MenuItem("Remove download", Icons.Default.Delete, close) { ui.deleteDownloads(listOf(t)) }
            else MenuItem("Download", Icons.Default.Download, close) { ui.download(t.id, t.title, listOf(t)) }
            extra?.invoke(this, close)
            // Hide the links to the screen that is already open.
            val here = ui.stack().last()
            if (here != Screen.AlbumView(t.album)) MenuItem("Go to album", Icons.Default.Album, close) { ui.open(Screen.AlbumView(t.album), Tab.Albums) }
            if (here != Screen.ArtistView(t.album.artist)) MenuItem("Go to artist", Icons.Default.Person, close) { ui.open(Screen.ArtistView(t.album.artist), Tab.Artists) }
            if (here != Screen.FolderView(t.album.dir)) MenuItem("Show folder", Icons.Default.Folder, close) { ui.open(Screen.FolderView(t.album.dir), Tab.Folders) }
            MenuItem("Track info", Icons.Default.Info, close) { ui.trackInfo = t }
        }
    }
}

/** Dialog for "Add to playlist…": pick a playlist or create one. */
@UnstableApi
@Composable
fun AddToPlaylistDialog(ui: Ui) {
    val tracks = ui.addToPlaylist ?: return
    var name by remember { mutableStateOf("") }
    val lists = remember(ui.version) { ui.app.playlists.all }
    AlertDialog(
        onDismissRequest = { ui.addToPlaylist = null }, containerColor = Gray2,
        title = { Text(if (tracks.size == 1) "Add “${tracks[0].title}” to" else "Add ${tracks.size} tracks to") },
        text = {
            Column {
                lists.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { ui.addToPlaylist(p, tracks); ui.addToPlaylist = null }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(20.dp), tint = Muted)
                        Text(p.name, Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.trackIds.size}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, placeholder = { Text("New playlist name") })
            }
        },
        confirmButton = {
            TextButton({ ui.createPlaylist(name, tracks); ui.toast("Playlist created"); ui.addToPlaylist = null }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton({ ui.addToPlaylist = null }) { Text("Cancel") } },
    )
}

@UnstableApi
@Composable
/** [showArtist] false shows the year under each album instead, for a screen of one artist. */
fun AlbumGrid(ui: Ui, albums: List<Album>, header: (@Composable () -> Unit)? = null, showArtist: Boolean = true) {
    val cols = ui.prefs.gridColumns.value
    if (ui.prefs.albumGrid.value) {
        LazyVerticalGrid(
            GridCells.Fixed(cols), contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (header != null) item(span = { GridItemSpan(cols) }) { header() }
            items(albums, key = { it.id }) { a ->
                Column(Modifier.clickable { ui.open(Screen.AlbumView(a)) }) {
                    Cover(ui, a.cover, null, Modifier.fillMaxWidth())
                    Text(a.title, Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(if (showArtist) a.artist else a.year?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    } else {
        LazyColumn {
            if (header != null) item { header() }
            items(albums, key = { it.id }) { AlbumRow(ui, it, if (showArtist) null else it.year?.toString() ?: "") }
        }
    }
}

@UnstableApi
@Composable
fun AlbumRow(ui: Ui, a: Album, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { ui.open(Screen.AlbumView(a)) }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(ui, a.cover, 44.dp)
        Column(Modifier.padding(start = 10.dp)) {
            Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle ?: listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted,
            )
        }
    }
}

@Composable
fun ListRow(icon: ImageVector, title: String, subtitle: String?, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Muted)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}
