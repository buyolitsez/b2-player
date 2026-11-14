package dev.buyolitsez.b2player

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

/** Height of the mini player and tabs drawn over the bottom of every list; lists pad their end by it. */
val LocalListBottom = compositionLocalOf { 0.dp }

@Composable
fun listPadding(top: Dp = 0.dp) = PaddingValues(top = top, bottom = LocalListBottom.current)

@UnstableApi
@Composable
fun Cover(ui: Ui, key: String?, size: Dp?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(if (size != null && size <= 64.dp) 4.dp else 6.dp)) {
    // produceState keeps the old value when the key changes, so always reset it to the new cover.
    val small = size != null && size <= 64.dp
    val bmp by produceState(key?.let { ui.covers.cached(it, small) }, key) { value = key?.let { ui.covers.load(it, small) } }
    val m = (if (size != null) modifier.size(size) else modifier.aspectRatio(1f)).clip(shape).background(Gray2)
    Box(m, Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Album, null, tint = Muted)
    }
}

@Composable
fun SmallIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, size: Dp = 20.dp, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), Alignment.Center) {
        Icon(icon, null, Modifier.size(size), tint = tint)
    }
}

/** Download progress of [tracks] (by size; downloaded ones count as done), or null when none of them is queued. */
@UnstableApi
@Composable
fun downloadProgress(ui: Ui, tracks: List<Track>): Float? {
    val queued by DownloadWorker.progress.collectAsState()
    val files = tracks.distinctBy { it.path }
    if (files.none { it.path in queued }) return null
    val done = files.sumOf { t -> t.size * (queued[t.path] ?: if (ui.library.isDownloaded(t)) 1f else 0f).toDouble() }
    return (done / files.sumOf { it.size }.coerceAtLeast(1)).toFloat()
}

/** A ring that fills with [progress] around a download arrow. */
@Composable
fun DownloadRing(progress: Float, size: Dp, color: Color) {
    val shown by animateFloatAsState(progress, label = "download")
    Box(Modifier.size(size), Alignment.Center) {
        CircularProgressIndicator({ shown }, Modifier.fillMaxSize(), color = color, strokeWidth = size / 10, trackColor = color.copy(alpha = 0.25f), gapSize = 0.dp)
        Icon(Icons.Default.ArrowDownward, null, Modifier.size(size * 0.55f), tint = color)
    }
}

/** Small mark in track rows: a ring while the track downloads, a check once it is on the phone. */
@UnstableApi
@Composable
fun DownloadMark(ui: Ui, t: Track) {
    val p = downloadProgress(ui, listOf(t))
    if (p != null) Box(Modifier.padding(start = 6.dp)) { DownloadRing(p, 14.dp, Muted) }
    else if (ui.downloaded(t)) Icon(Icons.Default.DownloadDone, null, Modifier.padding(start = 6.dp).size(14.dp), tint = Muted)
}

/** A rounded chip; [active] fills it light. With [progress], a download ring stands in place of the icon. */
@Composable
fun Pill(text: String, icon: ImageVector? = null, active: Boolean = false, progress: Float? = null, onClick: () -> Unit) {
    val c = if (active) Color.Black else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(50)).background(if (active) Color(0xFFF2F2F2) else Gray2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress != null) { DownloadRing(progress, 16.dp, c); Spacer(Modifier.width(6.dp)) }
        else if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = c); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = c)
    }
}

/** Filled rounded search field without an outline. */
@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    TextField(
        value, onChange, modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), singleLine = true,
        placeholder = { Text(placeholder, color = Faint) }, leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
        trailingIcon = { if (value.isNotEmpty()) SmallIcon(Icons.Default.Close) { onChange("") } },
        shape = RoundedCornerShape(12.dp), textStyle = MaterialTheme.typography.bodyLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Gray2, unfocusedContainerColor = Gray2,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

/**
 * Search field above a list, opened with the search button in the top bar; the screen filters its
 * own list with [searchFold] of the text. A filled-in filter stays visible.
 */
@UnstableApi
@Composable
fun FilterField(ui: Ui, value: String, onChange: (String) -> Unit) {
    if (!ui.filterOpen && value.isEmpty()) return
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (value.isEmpty()) runCatching { focus.requestFocus() } }
    SearchBox(value, onChange, "Search", Modifier.focusRequester(focus))
}

/** A small grey label in capitals above a group of rows. */
@Composable
fun SectionTitle(s: String) =
    Text(s.uppercase(), Modifier.padding(20.dp, 22.dp, 20.dp, 6.dp), style = MaterialTheme.typography.labelMedium, color = Muted)

/** Large title at the top of a screen's content. */
@Composable
fun ScreenTitle(s: String, subtitle: String? = null) {
    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(s, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, Modifier.padding(start = 10.dp, bottom = 5.dp), style = MaterialTheme.typography.bodyMedium, color = Muted, maxLines = 1)
    }
}

/** A row of the track actions sheet. */
@Composable
fun MenuItem(text: String, icon: ImageVector, close: () -> Unit, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { close(); action() }.padding(horizontal = 24.dp).height(50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Muted)
        Text(text, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

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
    val accent = MaterialTheme.colorScheme.primary
    val row = @Composable {
        Row(
            Modifier.fillMaxWidth().then(dragModifier).clickable(onClick = onClick).padding(start = if (dragHandle != null) 0.dp else 20.dp, end = 4.dp).height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dragHandle?.invoke()
            if (showCover) Cover(ui, t.album.cover, 44.dp)
            else Text(t.number?.toString() ?: "", Modifier.width(22.dp), style = MaterialTheme.typography.bodyMedium, color = if (active) accent else Faint)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                    color = if (active) accent else MaterialTheme.colorScheme.onSurface,
                )
                // In an album the artist line only shows for tracks by someone else.
                val sub = subtitle ?: if (showCover) "${t.artist} · ${t.album.title}" else t.artist.takeIf { it != t.album.artist }
                if (sub != null) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            // Marks go before the duration, so durations line up at the right edge.
            if (ui.liked(t)) Icon(Icons.Default.Favorite, null, Modifier.padding(start = 6.dp).size(14.dp), tint = accent)
            DownloadMark(ui, t)
            Text(formatTime(t.duration), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
            SmallIcon(Icons.Default.MoreVert, size = 18.dp) { ui.trackMenu = Ui.TrackMenuRequest(t, queueIndex, extraMenu) }
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
        // Rows are transparent over the screen's color, so the label shows only while swiping.
        if (state.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
        val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        val label = when {
            toRight -> "Play next"
            queueIndex != null -> "Remove from queue"
            swipeLeftLabel != null -> swipeLeftLabel
            else -> "Add to queue"
        }
        Box(
            Modifier.fillMaxSize().background(SwipeRow).padding(horizontal = 20.dp),
            if (toRight) Alignment.CenterStart else Alignment.CenterEnd,
        ) { Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
    }) { row() }
}

/** The actions sheet of [Ui.trackMenu], one for the whole app. */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSheet(ui: Ui) {
    val req = ui.trackMenu ?: return
    val t = req.track
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = { ui.trackMenu = null }
    ModalBottomSheet(close, sheetState = sheet, containerColor = Color(0xFF1A1A1A)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(ui, t.album.cover, 52.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${t.artist} · ${t.album.title}", style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val liked = ui.liked(t)
                SmallIcon(if (liked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, if (liked) MaterialTheme.colorScheme.primary else Muted, 24.dp) { ui.toggleLike(t) }
            }
            Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun tile(text: String, icon: ImageVector, action: () -> Unit) = Column(
                    Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF262626)).clickable { close(); action() },
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, null, Modifier.size(22.dp))
                    Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                tile("Play next", Icons.AutoMirrored.Filled.PlaylistPlay) { ui.playNext(listOf(t)) }
                if (req.queueIndex == null) tile("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { ui.addToEnd(listOf(t)) }
                else tile("Remove", Icons.Default.RemoveCircleOutline) { ui.removeFromQueue(req.queueIndex) }
                tile("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd) { ui.addToPlaylist = listOf(t) }
            }
            req.extra?.invoke(this, close)
            MenuItem(if (ui.app.stopAfterId.value == t.id) "Don't stop after this" else "Stop after this track", Icons.Default.StopCircle, close) { ui.stopAfter(t) }
            if (ui.downloaded(t)) MenuItem("Remove download", Icons.Default.Delete, close) { ui.deleteDownloads(listOf(t)) }
            else MenuItem("Download", Icons.Default.Download, close) { ui.download(t.id, t.title, listOf(t)) }
            // Hide the links to the screen that is already open.
            val here = if (ui.nowOpen) null else ui.stack().last()
            if (here != Screen.AlbumView(t.album)) MenuItem("Go to album", Icons.Default.Album, close) { ui.open(Screen.AlbumView(t.album), Tab.Library) }
            if (here != Screen.ArtistView(t.album.artist)) MenuItem("Go to artist", Icons.Default.Person, close) { ui.open(Screen.ArtistView(t.album.artist), Tab.Library) }
            if (here != Screen.FolderView(t.album.dir)) MenuItem("Show folder", Icons.Default.Folder, close) { ui.open(Screen.FolderView(t.album.dir), Tab.Library) }
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
            GridCells.Fixed(cols), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = LocalListBottom.current + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (header != null) item(span = { GridItemSpan(cols) }) { header() }
            items(albums, key = { it.id }) { a ->
                Column(Modifier.clickable { ui.open(Screen.AlbumView(a)) }) {
                    Cover(ui, a.cover, null, Modifier.fillMaxWidth())
                    Text(a.title, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(if (showArtist) a.artist else a.year?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    } else {
        LazyColumn(contentPadding = listPadding()) {
            if (header != null) item { Box(Modifier.padding(horizontal = 20.dp)) { header() } }
            items(albums, key = { it.id }) { AlbumRow(ui, it, if (showArtist) null else it.year?.toString() ?: "") }
        }
    }
}

@UnstableApi
@Composable
fun AlbumRow(ui: Ui, a: Album, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { ui.open(Screen.AlbumView(a)) }.padding(horizontal = 20.dp).height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(ui, a.cover, 48.dp)
        Column(Modifier.padding(start = 14.dp)) {
            Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle ?: listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted,
            )
        }
    }
}

/** An artist with the cover of one of their albums in a circle. */
@UnstableApi
@Composable
fun ArtistRow(ui: Ui, name: String, albums: List<Album>, subtitle: String? = null, inTab: Tab? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { ui.open(Screen.ArtistView(name), inTab) }.padding(start = 20.dp, end = 12.dp).height(68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(ui, albums.firstOrNull { it.cover != null }?.cover, 52.dp, shape = CircleShape)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(subtitle ?: if (albums.size == 1) "1 album" else "${albums.size} albums", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFF5C5C5C))
    }
}

@Composable
fun ListRow(icon: ImageVector?, title: String, subtitle: String?, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp).height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Gray2), Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = Muted)
        }
        Column(Modifier.padding(start = if (icon != null) 14.dp else 0.dp).weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}
