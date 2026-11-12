package dev.buyolitsez.b2player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// ---------------- library tabs ----------------

@UnstableApi
@Composable
fun AlbumsTab(ui: Ui, cat: Catalog) {
    val recent = ui.prefs.albumSortRecent
    val albums = remember(cat, recent.value, ui.version) {
        val list = cat.albums.filter { a -> !ui.offlineOnly || a.tracks.any { ui.library.availableOffline(it) } }
        if (recent.value) list.sortedByDescending { it.added } else list
    }
    AlbumGrid(ui, albums, header = {
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("A–Z", active = !recent.value) { recent.set(false) }
            Pill("Recently added", active = recent.value) { recent.set(true) }
        }
    })
}

@UnstableApi
@Composable
fun ArtistsTab(ui: Ui, cat: Catalog) {
    LazyColumn {
        items(cat.artists, key = { it.first }) { (name, albums) ->
            ListRow(Icons.Default.Person, name, if (albums.size == 1) "1 album" else "${albums.size} albums") { ui.open(Screen.ArtistView(name)) }
        }
    }
}

@UnstableApi
@Composable
fun ArtistScreen(ui: Ui, cat: Catalog, name: String) {
    val albums = remember(cat, name) { cat.albums.filter { it.artist == name }.sortedBy { it.year ?: 0 } }
    val tracks = remember(albums) { albums.flatMap { it.tracks } }
    AlbumGrid(ui, albums, header = { PlayButtons(ui, "artist:$name", name, tracks) })
}

@UnstableApi
@Composable
fun FolderScreen(ui: Ui, cat: Catalog, path: String) {
    val f = remember(cat, path) { cat.folder(path) } ?: return
    val subs = f.folders.values.toList()
    LazyColumn {
        if (path.isNotEmpty()) item { PlayButtons(ui, "folder:$path", f.name, f.allTracks) }
        items(subs, key = { "f" + it.path }) { s ->
            ListRow(Icons.Default.Folder, s.name, "${s.allTracks.size} tracks") { ui.open(Screen.FolderView(s.path)) }
        }
        val tracks = f.tracks.filter { ui.visible(it) }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(ui, t, showCover = false, onClick = { ui.play(tracks, i) }) }
    }
}

@UnstableApi
@Composable
fun SearchTab(ui: Ui, cat: Catalog) {
    val q = ui.query.trim()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (q.isEmpty()) runCatching { focus.requestFocus() } }
    val albums = remember(cat, q) {
        if (q.length < 2) emptyList() else cat.albums.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
    }
    val tracks = remember(cat, q, ui.version) {
        if (q.length < 2) emptyList() else cat.tracks.filter { (it.title.contains(q, true) || it.artist.contains(q, true)) && ui.visible(it) }.take(200)
    }
    Column {
        OutlinedTextField(
            ui.query, { ui.query = it }, Modifier.fillMaxWidth().padding(10.dp).focusRequester(focus), singleLine = true,
            placeholder = { Text("Artist, album or track") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (ui.query.isNotEmpty()) SmallIcon(Icons.Default.Close) { ui.query = "" } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        LazyColumn {
            if (albums.isNotEmpty()) item { SectionTitle("Albums") }
            items(albums.take(30), key = { "a" + it.id }) { AlbumRow(ui, it) }
            if (tracks.isNotEmpty()) item { SectionTitle("Tracks") }
            itemsIndexed(tracks, key = { _, t -> "t" + t.id }) { i, t ->
                TrackRow(ui, t, showCover = true, onClick = { focusManager.clearFocus(); ui.play(tracks, i) })
            }
        }
    }
}

/** Play / shuffle / download row for a list of tracks. */
@UnstableApi
@Composable
fun PlayButtons(ui: Ui, key: String, title: String, tracks: List<Track>, extra: (@Composable () -> Unit)? = null) {
    val allDownloaded = remember(tracks, ui.version) { tracks.isNotEmpty() && tracks.all { ui.library.isDownloaded(it) } }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill("Play", Icons.Default.PlayArrow, active = true) { ui.play(tracks, 0) }
        Pill("Shuffle", Icons.Default.Shuffle) { ui.play(tracks, 0, shuffled = true) }
        if (allDownloaded) Pill("Downloaded", Icons.Default.DownloadDone) { ui.deleteDownloads(tracks) }
        else Pill("Download", Icons.Default.Download) { ui.download(key, title, tracks) }
        Pill("Queue", Icons.AutoMirrored.Filled.QueueMusic) { ui.addToEnd(tracks) }
        extra?.invoke()
        Text("${tracks.size} tracks", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@UnstableApi
@Composable
fun AlbumScreen(ui: Ui, a: Album) {
    val tracks = a.tracks.filter { ui.visible(it) }
    LazyColumn {
        item {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(ui, a.cover, 112.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        a.artist, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { ui.open(Screen.ArtistView(a.artist), Tab.Artists) }.padding(vertical = 2.dp),
                    )
                    Text(
                        listOfNotNull(a.year?.toString(), "${a.tracks.size} tracks", formatTime(a.duration)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
            }
            PlayButtons(ui, a.id, a.title, a.tracks, extra = { Pill("Playlist", Icons.Default.Add) { ui.addToPlaylist = a.tracks } })
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(ui, t, showCover = false, onClick = { ui.play(tracks, i) }) }
    }
}

// ---------------- playlists, liked, history ----------------

@UnstableApi
@Composable
fun PlaylistsTab(ui: Ui, cat: Catalog, importM3u: () -> Unit) {
    val lists = remember(ui.version) { ui.app.playlists.all }
    val likedCount = remember(cat, ui.version) { cat.tracks.count { ui.library.isLiked(it) } }
    var creating by remember { mutableStateOf(false) }
    LazyColumn {
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill("New playlist", Icons.Default.Add) { creating = true }
                Pill("Import .m3u", Icons.Default.FileOpen) { importM3u() }
            }
            ListRow(Icons.Default.Favorite, "Liked", "$likedCount tracks") { ui.open(Screen.LikedView) }
            ListRow(Icons.Default.History, "History and stats", "${ui.app.history.all().size} plays") { ui.open(Screen.HistoryView) }
            ListRow(Icons.Default.NewReleases, "Recently added", "Albums, newest first") {
                ui.prefs.albumSortRecent.set(true); ui.selectTab(Tab.Albums)
            }
            if (lists.isNotEmpty()) SectionTitle("Your playlists")
        }
        items(lists, key = { it.id }) { p ->
            ListRow(Icons.AutoMirrored.Filled.QueueMusic, p.name, "${p.trackIds.size} tracks") { ui.open(Screen.PlaylistView(p.id)) }
        }
    }
    if (creating) NameDialog("New playlist", "", { creating = false }) { ui.createPlaylist(it) }
}

@Composable
fun NameDialog(title: String, initial: String, dismiss: () -> Unit, done: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        dismiss, containerColor = Gray2, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton({ done(name); dismiss() }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}

@UnstableApi
@Composable
fun PlaylistScreen(ui: Ui, cat: Catalog, id: String) {
    val p = remember(ui.version, id) { ui.app.playlists.get(id) }
    if (p == null) { Placeholder("Playlist was deleted"); return }
    val tracks = remember(p, cat) { ui.playlistTracks(p, cat) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        ui.editPlaylist(id) { pl -> pl.copy(trackIds = pl.trackIds.toMutableList().apply { add(to.index - 1, removeAt(from.index - 1)) }) }
    }
    fun dropAt(i: Int): Unit = ui.editPlaylist(id) { pl -> pl.copy(trackIds = pl.trackIds.toMutableList().apply { removeAt(i) }) }
    LazyColumn(state = listState) {
        item(key = "header") {
            Column {
                Text(
                    "${tracks.size} tracks · ${formatTime(tracks.sumOf { it.duration })}",
                    Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = Muted,
                )
                PlayButtons(ui, "playlist:$id", p.name, tracks, extra = {
                    Pill("Rename", Icons.Default.Edit) { renaming = true }
                    Pill("Delete", Icons.Default.Delete) { confirmDelete = true }
                })
            }
        }
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
            ReorderableItem(reorder, key = "$i:${t.id}") {
                TrackRow(
                    ui, t, showCover = true, onClick = { ui.play(tracks, i) },
                    dragHandle = { DragHandle(Modifier.draggableHandle()) },
                    onSwipeLeft = { dropAt(i) }, swipeLeftLabel = "Remove from playlist",
                    extraMenu = { close -> MenuItem("Remove from playlist", Icons.Default.RemoveCircleOutline, close) { dropAt(i) } },
                )
            }
        }
    }
    if (renaming) NameDialog("Rename playlist", p.name, { renaming = false }) { n -> ui.editPlaylist(id) { it.copy(name = n.trim()) } }
    if (confirmDelete) AlertDialog(
        { confirmDelete = false }, containerColor = Gray2, title = { Text("Delete “${p.name}”?") },
        text = { Text("The tracks stay in your library.") },
        confirmButton = { TextButton({ confirmDelete = false; ui.back(); ui.deletePlaylist(id) }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
fun DragHandle(modifier: Modifier) =
    Box(modifier.size(36.dp, 52.dp), Alignment.Center) { Icon(Icons.Default.DragHandle, null, Modifier.size(18.dp), tint = Muted) }

@UnstableApi
@Composable
fun LikedScreen(ui: Ui, cat: Catalog) {
    var byRecent by remember { mutableStateOf(false) }
    val liked = remember(cat, ui.version, byRecent) {
        val l = cat.tracks.filter { ui.library.isLiked(it) && ui.visible(it) }
        if (byRecent) l.sortedByDescending { it.album.added } else l
    }
    LazyColumn {
        item {
            PlayButtons(ui, "liked", "Liked", liked, extra = {
                Pill(if (byRecent) "Newest" else "A–Z", Icons.AutoMirrored.Filled.Sort) { byRecent = !byRecent }
            })
        }
        itemsIndexed(liked, key = { _, t -> t.id }) { i, t -> TrackRow(ui, t, showCover = true, onClick = { ui.play(liked, i) }) }
    }
}

private enum class Period(val label: String, val days: Int?) { Week("7 days", 7), Month("30 days", 30), Year("Year", 365), All("All time", null) }

@UnstableApi
@Composable
fun HistoryScreen(ui: Ui, cat: Catalog) {
    var period by remember { mutableStateOf(Period.Month) }
    val plays = remember(ui.version) { ui.app.history.all() }
    val since = period.days?.let { System.currentTimeMillis() - it * 86_400_000L } ?: 0L
    val inPeriod = remember(plays, period) { plays.filter { it.time >= since } }
    val recent = remember(plays) { plays.asReversed().map { it.trackId }.distinct().take(30).mapNotNull { cat.byId[it] } }
    val topTracks = remember(inPeriod) {
        inPeriod.groupingBy { it.trackId }.eachCount().entries.sortedByDescending { it.value }.take(25)
            .mapNotNull { (id, n) -> cat.byId[id]?.let { it to n } }
    }
    val topArtists = remember(inPeriod) {
        inPeriod.mapNotNull { cat.byId[it.trackId]?.album?.artist }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(15)
    }
    val forgotten = remember(plays) {
        val halfYear = System.currentTimeMillis() - 180 * 86_400_000L
        plays.mapNotNull { p -> cat.byId[p.trackId]?.album?.let { it to p.time } }
            .groupBy({ it.first }, { it.second })
            .filter { (_, times) -> times.size >= 3 && times.max() < halfYear }
            .keys.take(20)
    }
    LazyColumn {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p -> Pill(p.label, active = p == period) { period = p } }
            }
            Text(
                "${inPeriod.size} plays · ${formatTime(inPeriod.sumOf { cat.byId[it.trackId]?.duration ?: 0.0 })} listened",
                Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium,
            )
            if (plays.isEmpty()) Text("Nothing yet. A play counts after half the track or 4 minutes.", Modifier.padding(12.dp), color = Muted)
        }
        if (recent.isNotEmpty()) item { SectionTitle("Recently played") }
        itemsIndexed(recent.take(10), key = { _, t -> "r" + t.id }) { i, t -> TrackRow(ui, t, showCover = true, onClick = { ui.play(recent, i) }) }
        if (topTracks.isNotEmpty()) item { SectionTitle("Top tracks") }
        itemsIndexed(topTracks, key = { _, p -> "t" + p.first.id }) { i, (t, n) ->
            TrackRow(ui, t, showCover = true, onClick = { ui.play(topTracks.map { it.first }, i) }, subtitle = "$n plays · ${t.artist}")
        }
        if (topArtists.isNotEmpty()) item { SectionTitle("Top artists") }
        items(topArtists, key = { "a" + it.key }) { (name, n) ->
            ListRow(Icons.Default.Person, name, "$n plays") { ui.open(Screen.ArtistView(name), Tab.Artists) }
        }
        if (forgotten.isNotEmpty()) item { SectionTitle("Haven't played in a while") }
        items(forgotten, key = { "f" + it.id }) { a -> AlbumRow(ui, a) }
    }
}

// ---------------- queue ----------------

@UnstableApi
@Composable
fun QueueTab(ui: Ui, cat: Catalog) {
    val ids = ui.queueIds
    val tracks = ids.map { cat.byId[it] }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to -> ui.moveInQueue(from.index - 1, to.index - 1) }
    LaunchedEffect(Unit) { if (ui.currentIndex > 2) listState.scrollToItem(ui.currentIndex - 1) }
    val saved = remember(ui.version) { ui.library.savedQueues }
    val left = tracks.drop(ui.currentIndex).sumOf { it?.duration ?: 0.0 }
    var showSaved by remember { mutableStateOf(false) }
    LazyColumn(state = listState) {
        item(key = "header") {
            Column {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ids.isNotEmpty()) Text("${ui.currentIndex + 1} / ${ids.size} · ${formatTime(left)} left", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Pill("Put aside", Icons.Default.Bookmark) { ui.putQueueAside() }
                    Pill("Set aside · ${saved.size}", Icons.Default.Bookmarks, active = showSaved) { showSaved = !showSaved }
                    Pill("Save as playlist", Icons.AutoMirrored.Filled.QueueMusic) { ui.saveQueueAsPlaylist() }
                    Pill("Clear", Icons.Default.ClearAll) { ui.clearQueue() }
                }
                if (showSaved) {
                    if (saved.isEmpty()) Text("No queues set aside", Modifier.padding(12.dp), color = Muted)
                    saved.forEachIndexed { i, q ->
                        ListRow(Icons.Default.Bookmark, q.name, "${q.index + 1} / ${q.ids.size}", trailing = { SmallIcon(Icons.Default.Close) { ui.deleteSaved(i) } }) {
                            showSaved = false; ui.resumeSaved(i)
                        }
                    }
                    HorizontalDivider(color = Gray2)
                }
                if (ids.isEmpty()) Text("The queue is empty. Play an album or a track.", Modifier.padding(16.dp), color = Muted)
            }
        }
        itemsIndexed(tracks, key = { i, _ -> "$i:${ids[i]}" }) { i, t ->
            ReorderableItem(reorder, key = "$i:${ids[i]}") {
                if (t == null) return@ReorderableItem
                TrackRow(ui, t, showCover = true, onClick = { ui.controller?.run { seekTo(i, 0); play() } }, queueIndex = i,
                    dragHandle = { DragHandle(Modifier.draggableHandle()) })
            }
        }
    }
}

// ---------------- downloads ----------------

@UnstableApi
@Composable
fun DownloadsScreen(ui: Ui, cat: Catalog) {
    val works by WorkManager.getInstance(ui.context).getWorkInfosByTagFlow(DownloadWorker.TAG).collectAsState(emptyList())
    val active = works.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val downloaded by produceState<List<Pair<Album, List<Track>>>?>(null, cat, ui.version, active.size) {
        value = withContext(Dispatchers.IO) {
            cat.albums.mapNotNull { a -> a.tracks.filter { ui.library.isDownloaded(it) }.takeIf { it.isNotEmpty() }?.let { a to it } }
        }
    }
    val total = downloaded?.sumOf { (_, t) -> t.distinctBy { it.path }.sumOf { it.size } } ?: 0
    LazyColumn {
        item {
            Text(
                "Downloaded: ${formatSize(total)} · stream cache: ${formatSize(ui.library.cacheBytes())} of ${ui.prefs.cacheGb.value} GB",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
            )
            if (active.isNotEmpty()) Row(Modifier.padding(horizontal = 12.dp)) {
                Pill("Cancel all", Icons.Default.Cancel) { WorkManager.getInstance(ui.context).cancelAllWorkByTag(DownloadWorker.TAG) }
            }
        }
        if (active.isNotEmpty()) item { SectionTitle("In progress") }
        items(active, key = { it.id }) { w ->
            val title = w.progress.getString("title") ?: "Queued"
            val done = w.progress.getInt("done", 0); val all = w.progress.getInt("total", 0)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row {
                    Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (w.state == WorkInfo.State.ENQUEUED) "waiting for network" else "$done / $all", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                LinearProgressIndicator({ if (all > 0) done / all.toFloat() else 0f }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        item { SectionTitle("On this phone") }
        val list = downloaded
        if (list == null) item { Text("Counting…", Modifier.padding(12.dp), color = Muted) }
        else if (list.isEmpty()) item { Text("Nothing downloaded yet", Modifier.padding(12.dp), color = Muted) }
        else items(list, key = { it.first.id }) { (a, t) ->
            Row(
                Modifier.fillMaxWidth().clickable { ui.open(Screen.AlbumView(a)) }.padding(start = 12.dp).height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(ui, a.cover, 40.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.artist} · ${t.size} of ${a.tracks.size} · ${formatSize(t.distinctBy { it.path }.sumOf { it.size })}",
                        style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
                }
                SmallIcon(Icons.Default.Delete) { ui.deleteDownloads(t) }
            }
        }
    }
}

@Composable
fun Placeholder(text: String) = Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text, color = Muted) }
