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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
    val days = ui.prefs.recentDays
    var periodMenu by remember { mutableStateOf(false) }
    var customDays by remember { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val fq = searchFold(q.trim())
    val albums = remember(cat, recent.value, days.value, ui.offlineOnly, ui.offlineIds, fq) {
        val since = if (recent.value && days.value > 0) System.currentTimeMillis() / 1000 - days.value * 86400L else 0L
        val list = cat.albums.filter { a -> fq in a.searchKey && a.added >= since && (!ui.offlineOnly || a.tracks.any { ui.visible(it) }) }
        if (recent.value) list.sortedByDescending { it.added } else list
    }
    fun label(d: Int) = when (d) { 0 -> "All time"; 1 -> "1 day"; else -> "$d days" }
    Column {
        FilterField(q) { q = it }
        AlbumGrid(ui, albums, header = {
            Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("By artist", active = !recent.value) { recent.set(false) }
                Pill("Recently added", active = recent.value) { recent.set(true) }
                if (recent.value) Box {
                    Pill(label(days.value), Icons.Default.DateRange) { periodMenu = true }
                    DropdownMenu(periodMenu, { periodMenu = false }, containerColor = Gray2) {
                        listOf(1, 3, 7, 30, 365, 0).forEach { d -> DropdownMenuItem({ Text(label(d)) }, { periodMenu = false; days.set(d) }) }
                        DropdownMenuItem({ Text("Custom…") }, { periodMenu = false; customDays = true })
                    }
                }
            }
        })
    }
    if (customDays) {
        var text by remember { mutableStateOf("") }
        val n = text.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(
            { customDays = false }, containerColor = Gray2, title = { Text("Added in the last") },
            text = {
                OutlinedTextField(text, { text = it.filter(Char::isDigit).take(5) }, singleLine = true, suffix = { Text("days") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = { TextButton({ days.set(n!!); customDays = false }, enabled = n != null) { Text("OK") } },
            dismissButton = { TextButton({ customDays = false }) { Text("Cancel") } },
        )
    }
}

@UnstableApi
@Composable
fun ArtistsTab(ui: Ui, cat: Catalog) {
    var q by rememberSaveable { mutableStateOf("") }
    val fq = searchFold(q.trim())
    val artists = remember(cat, fq) { cat.artists.filter { fq in searchFold(it.first) } }
    Column {
        FilterField(q) { q = it }
        LazyColumn {
            items(artists, key = { it.first }) { (name, albums) ->
                ListRow(Icons.Default.Person, name, if (albums.size == 1) "1 album" else "${albums.size} albums") { ui.open(Screen.ArtistView(name)) }
            }
        }
    }
}

@UnstableApi
@Composable
fun ArtistScreen(ui: Ui, cat: Catalog, name: String) {
    val albums = remember(cat, name) { cat.albums.filter { it.artist.equals(name, ignoreCase = true) }.sortedBy { it.year ?: 0 } }
    val tracks = remember(albums) { albums.flatMap { it.tracks } }
    var q by rememberSaveable { mutableStateOf("") }
    val fq = searchFold(q.trim())
    val shown = remember(albums, fq) { albums.filter { fq in it.searchKey } }
    Column {
        FilterField(q) { q = it }
        AlbumGrid(ui, shown, header = { PlayButtons(ui, "artist:$name", name, tracks) }, showArtist = false)
    }
}

const val NO_GENRE = "No genre"

@UnstableApi
@Composable
fun GenresTab(ui: Ui, cat: Catalog) {
    var q by rememberSaveable { mutableStateOf("") }
    val fq = searchFold(q.trim())
    val genres = remember(cat, fq) { cat.genres.filter { fq in searchFold(it.first.ifEmpty { NO_GENRE }) } }
    Column {
        FilterField(q) { q = it }
        LazyColumn {
            items(genres, key = { it.first }) { (name, albums) ->
                ListRow(Icons.Default.Category, name.ifEmpty { NO_GENRE }, if (albums.size == 1) "1 album" else "${albums.size} albums") { ui.open(Screen.GenreView(name)) }
            }
        }
    }
}

@UnstableApi
@Composable
fun GenreScreen(ui: Ui, cat: Catalog, name: String) {
    val albums = remember(cat, name) { cat.genres.firstOrNull { it.first == name }?.second.orEmpty() }
    val tracks = remember(albums) { albums.flatMap { it.tracks } }
    var q by rememberSaveable { mutableStateOf("") }
    val fq = searchFold(q.trim())
    val shown = remember(albums, fq) { albums.filter { fq in it.searchKey } }
    Column {
        FilterField(q) { q = it }
        AlbumGrid(ui, shown, header = { PlayButtons(ui, "genre:$name", name.ifEmpty { NO_GENRE }, tracks) })
    }
}

@UnstableApi
@Composable
fun FolderScreen(ui: Ui, cat: Catalog, path: String) {
    val f = remember(cat, path) { cat.folder(path) } ?: return
    var q by rememberSaveable { mutableStateOf("") }
    val fq = searchFold(q.trim())
    val subs = remember(f, fq) { f.folders.values.filter { fq in searchFold(it.name) } }
    val tracks = remember(f, fq, ui.offlineOnly, ui.offlineIds) { f.tracks.filter { fq in it.searchKey && ui.visible(it) } }
    Column {
        FilterField(q) { q = it }
        LazyColumn {
            if (path.isNotEmpty()) item { PlayButtons(ui, "folder:$path", f.name, f.allTracks) }
            items(subs, key = { "f" + it.path }) { s ->
                ListRow(Icons.Default.Folder, s.name, "${s.allTracks.size} tracks") { ui.open(Screen.FolderView(s.path)) }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(ui, t, showCover = false, onClick = { ui.play(tracks, i) }) }
        }
    }
}

@UnstableApi
@Composable
fun SearchTab(ui: Ui, cat: Catalog) {
    val q = ui.query.trim()
    val fq = remember(q) { searchFold(q) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (q.isEmpty()) runCatching { focus.requestFocus() } }
    // Filtering 18k tracks runs off the main thread; the previous results stay until the new ones are ready.
    val found by produceState(Triple(emptyList<Pair<String, List<Album>>>(), emptyList<Album>(), emptyList<Track>()), cat, fq, ui.offlineOnly, ui.offlineIds) {
        value = if (fq.length < 2) Triple(emptyList(), emptyList(), emptyList()) else withContext(Dispatchers.Default) {
            Triple(cat.artists.filter { fq in searchFold(it.first) }, cat.albums.filter { fq in it.searchKey }, cat.tracks.filter { fq in it.searchKey && ui.visible(it) })
        }
    }
    val (artists, albums, allTracks) = found
    val tracks = remember(allTracks) { allTracks.take(200) }
    @Composable fun more(n: Int, what: String) {
        if (n > 0) Text("$n more $what. Refine the search to see them", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
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
            if (q.length == 1) item { Text("Type at least 2 letters", Modifier.padding(16.dp), color = Muted) }
            else if (q.length >= 2 && artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()) item { Text("Nothing found for “$q”", Modifier.padding(16.dp), color = Muted) }
            if (artists.isNotEmpty()) item { SectionTitle("Artists") }
            items(artists.take(10), key = { "r" + it.first }) { (name, albums) ->
                ListRow(Icons.Default.Person, name, if (albums.size == 1) "1 album" else "${albums.size} albums") { ui.open(Screen.ArtistView(name), Tab.Artists) }
            }
            item { more(artists.size - 10, "artists") }
            if (albums.isNotEmpty()) item { SectionTitle("Albums") }
            items(albums.take(30), key = { "a" + it.id }) { AlbumRow(ui, it) }
            item { more(albums.size - 30, "albums") }
            if (tracks.isNotEmpty()) item { SectionTitle("Tracks") }
            itemsIndexed(tracks, key = { _, t -> "t" + t.id }) { i, t ->
                TrackRow(ui, t, showCover = true, onClick = { focusManager.clearFocus(); ui.play(tracks, i) })
            }
            item { more(allTracks.size - tracks.size, "tracks") }
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
        Text(if (tracks.size == 1) "1 track" else "${tracks.size} tracks", style = MaterialTheme.typography.bodySmall, color = Muted)
        Pill("Play", Icons.Default.PlayArrow, active = true) { ui.play(tracks, 0) }
        Pill("Shuffle", Icons.Default.Shuffle) { ui.play(tracks, 0, shuffled = true) }
        if (allDownloaded) Pill("Downloaded", Icons.Default.DownloadDone) { ui.deleteDownloads(tracks) }
        else Pill("Download", Icons.Default.Download) { ui.download(key, title, tracks) }
        Pill("Queue", Icons.AutoMirrored.Filled.QueueMusic) { ui.addToEnd(tracks) }
        extra?.invoke()
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
                        listOfNotNull(a.year?.toString(), if (tracks.size == a.tracks.size) "${a.tracks.size} tracks" else "${tracks.size} of ${a.tracks.size} tracks offline", formatTime(a.duration)).joinToString(" · "),
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
    var q by rememberSaveable { mutableStateOf("") }
    val fq = searchFold(q.trim())
    val shown = remember(lists, fq) { lists.filter { fq in searchFold(it.name) } }
    Column {
        FilterField(q) { q = it }
        LazyColumn {
            if (fq.isEmpty()) item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill("New playlist", Icons.Default.Add) { creating = true }
                    Pill("Import .m3u", Icons.Default.FileOpen) { importM3u() }
                }
                ListRow(Icons.Default.Favorite, "Liked", "$likedCount tracks") { ui.open(Screen.LikedView) }
                ListRow(Icons.Default.History, "History and stats", plays(ui.app.history.all().size)) { ui.open(Screen.HistoryView) }
                ListRow(Icons.Default.NewReleases, "Recently added", "Albums, newest first") {
                    ui.prefs.albumSortRecent.set(true); ui.stacks[Tab.Albums] = listOf(Screen.Root); ui.tab = Tab.Albums
                }
                if (lists.isNotEmpty()) SectionTitle("Your playlists")
            }
            items(shown, key = { it.id }) { p ->
                ListRow(Icons.AutoMirrored.Filled.QueueMusic, p.name, "${p.trackIds.size} tracks") { ui.open(Screen.PlaylistView(p.id)) }
            }
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
    val keys = remember(tracks) { stableKeys(tracks.map { it.id }) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        ui.editPlaylist(id) { pl -> pl.copy(trackIds = pl.trackIds.toMutableList().apply { add(to.index - 1, removeAt(from.index - 1)) }) }
    }
    fun dropAt(i: Int): Unit = ui.editPlaylist(id) { pl -> pl.copy(trackIds = pl.trackIds.toMutableList().apply { removeAt(i) }) }
    var q by rememberSaveable { mutableStateOf("") }
    val fq = searchFold(q.trim())
    // Rows keep their playlist index so remove works on the filtered list; dragging is off while filtering.
    val shown = remember(tracks, fq) { tracks.indices.filter { fq in tracks[it].searchKey } }
    val shownTracks = remember(shown) { shown.map { tracks[it] } }
    Column {
        FilterField(q) { q = it }
        LazyColumn(state = listState) {
            item(key = "header") {
                Column {
                    Text(
                        "${tracks.size} tracks · ${formatTime(tracks.sumOf { it.duration })}",
                        Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                    PlayButtons(ui, "playlist:$id", p.name, shownTracks, extra = {
                        Pill("Rename", Icons.Default.Edit) { renaming = true }
                        Pill("Delete", Icons.Default.Delete) { confirmDelete = true }
                    })
                }
            }
            itemsIndexed(shown, key = { _, i -> keys[i] }) { pos, i ->
                val t = tracks[i]
                ReorderableItem(reorder, key = keys[i], enabled = fq.isEmpty()) {
                    TrackRow(
                        ui, t, showCover = true, onClick = { ui.play(shownTracks, pos) },
                        dragHandle = if (fq.isEmpty()) ({ DragHandle(Modifier.draggableHandle()) }) else null,
                        onSwipeLeft = { dropAt(i) }, swipeLeftLabel = "Remove from playlist",
                        extraMenu = { close -> MenuItem("Remove from playlist", Icons.Default.RemoveCircleOutline, close) { dropAt(i) } },
                    )
                }
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

/**
 * List keys that stay with an item when it moves (an index in the key breaks dragging after the
 * first step); repeated ids get "#2", "#3"… so keys stay unique.
 */
fun stableKeys(ids: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return ids.map { id -> val n = seen.merge(id, 1, Int::plus)!!; if (n == 1) id else "$id#$n" }
}

@Composable
fun DragHandle(modifier: Modifier) =
    Box(modifier.size(36.dp, 52.dp), Alignment.Center) { Icon(Icons.Default.DragHandle, null, Modifier.size(18.dp), tint = Muted) }

@UnstableApi
@Composable
fun LikedScreen(ui: Ui, cat: Catalog) {
    var byRecent by remember { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val fq = searchFold(q.trim())
    val liked = remember(cat, ui.version, byRecent, ui.offlineOnly, ui.offlineIds, fq) {
        val l = cat.tracks.filter { fq in it.searchKey && ui.library.isLiked(it) && ui.visible(it) }
        if (byRecent) l.sortedByDescending { it.album.added } else l
    }
    Column {
        FilterField(q) { q = it }
        LazyColumn {
            item {
                PlayButtons(ui, "liked", "Liked", liked, extra = {
                    Pill(if (byRecent) "Newest" else "A–Z", Icons.AutoMirrored.Filled.Sort) { byRecent = !byRecent }
                })
            }
            itemsIndexed(liked, key = { _, t -> t.id }) { i, t -> TrackRow(ui, t, showCover = true, onClick = { ui.play(liked, i) }) }
        }
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
        inPeriod.groupingBy { it.trackId }.eachCount().entries.sortedByDescending { it.value }.take(10)
            .mapNotNull { (id, n) -> cat.byId[id]?.let { it to n } }
    }
    val topArtists = remember(inPeriod) {
        inPeriod.mapNotNull { cat.byId[it.trackId]?.album?.artist }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10)
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
                "${plays(inPeriod.size)} · ${formatTime(inPeriod.sumOf { cat.byId[it.trackId]?.duration ?: 0.0 })} listened",
                Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium,
            )
            if (plays.isEmpty()) Text("Nothing yet. A play counts after half the track or 4 minutes.", Modifier.padding(12.dp), color = Muted)
        }
        if (topTracks.isNotEmpty()) item { SectionTitle("Top tracks") }
        itemsIndexed(topTracks, key = { _, p -> "t" + p.first.id }) { i, (t, n) ->
            TrackRow(ui, t, showCover = true, onClick = { ui.play(topTracks.map { it.first }, i) }, subtitle = "${plays(n)} · ${t.artist}")
        }
        if (topArtists.isNotEmpty()) item { SectionTitle("Top artists") }
        items(topArtists, key = { "a" + it.key }) { (name, n) ->
            ListRow(Icons.Default.Person, name, plays(n)) { ui.open(Screen.ArtistView(name), Tab.Artists) }
        }
        if (forgotten.isNotEmpty()) item { SectionTitle("Haven't played in a while") }
        items(forgotten, key = { "f" + it.id }) { a -> AlbumRow(ui, a) }
        if (recent.isNotEmpty()) item { SectionTitle("Recently played") }
        itemsIndexed(recent.take(10), key = { _, t -> "r" + t.id }) { i, t -> TrackRow(ui, t, showCover = true, onClick = { ui.play(recent, i) }) }
    }
}

fun plays(n: Int) = if (n == 1) "1 play" else "$n plays"

// ---------------- queue ----------------

@UnstableApi
@Composable
fun QueueTab(ui: Ui, cat: Catalog) {
    val ids = ui.queueIds
    val tracks = ids.map { cat.byId[it] }
    // Rows follow the play order; with shuffle on that is not the queue order, so dragging is off then.
    val order = ui.playOrder.takeIf { it.size == ids.size } ?: ids.indices.toList()
    val keys = stableKeys(ids)
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to -> ui.moveInQueue(from.index - 1, to.index - 1) }
    val at = order.indexOf(ui.currentIndex).coerceAtLeast(0)
    LaunchedEffect(Unit) { if (at > 2) listState.scrollToItem(at - 1) }
    val saved = remember(ui.version) { ui.library.savedQueues }
    val left = order.drop(at).sumOf { tracks.getOrNull(it)?.duration ?: 0.0 }
    var showSaved by remember { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val fq = searchFold(q.trim())
    val shown = if (fq.isEmpty()) order else order.filter { tracks.getOrNull(it)?.searchKey?.contains(fq) == true }
    Column {
        FilterField(q) { q = it }
        LazyColumn(state = listState) {
            item(key = "header") {
                Column {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (ids.isNotEmpty()) Text("${at + 1} / ${ids.size} · ${formatTime(left)} left", style = MaterialTheme.typography.bodySmall, color = Muted)
                        Pill("Save queue for later", Icons.Default.Bookmark) { ui.putQueueAside() }
                        Pill("Saved queues · ${saved.size}", Icons.Default.Bookmarks, active = showSaved) { showSaved = !showSaved }
                        Pill("Save as playlist", Icons.AutoMirrored.Filled.QueueMusic) { ui.saveQueueAsPlaylist() }
                        Pill("Clear", Icons.Default.ClearAll) { ui.clearQueue() }
                    }
                    if (showSaved) {
                        if (saved.isEmpty()) Text("No saved queues", Modifier.padding(12.dp), color = Muted)
                        saved.forEachIndexed { i, s ->
                            ListRow(Icons.Default.Bookmark, s.name, "${s.index + 1} / ${s.ids.size}", trailing = { SmallIcon(Icons.Default.Close) { ui.deleteSaved(i) } }) {
                                showSaved = false; ui.resumeSaved(i)
                            }
                        }
                        HorizontalDivider(color = Gray2)
                    }
                    if (ids.isEmpty()) Text("The queue is empty. Play an album or a track.", Modifier.padding(16.dp), color = Muted)
                }
            }
            items(shown, key = { keys[it] }) { i ->
                val t = tracks[i]
                val drag = !ui.shuffle && fq.isEmpty()
                ReorderableItem(reorder, key = keys[i], enabled = drag) {
                    if (t == null) return@ReorderableItem
                    TrackRow(ui, t, showCover = true, onClick = { ui.controller?.run { seekTo(i, 0); resume() } }, queueIndex = i,
                        dragHandle = if (drag) ({ DragHandle(Modifier.draggableHandle()) }) else null)
                }
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
    var q by rememberSaveable { mutableStateOf("") }
    val fq = searchFold(q.trim())
    Column {
        FilterField(q) { q = it }
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
            val list = downloaded?.filter { fq in it.first.searchKey }
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
}

@Composable
fun Placeholder(text: String) = Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text, color = Muted) }
