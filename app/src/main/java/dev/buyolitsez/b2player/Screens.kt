package dev.buyolitsez.b2player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// ---------------- library tabs ----------------

@UnstableApi
@Composable
fun AlbumsTab(ui: Ui, cat: Catalog) {
    val sort = AlbumSort.entries.firstOrNull { it.name == ui.prefs.albumSort.value } ?: AlbumSort.Artist
    val days = ui.prefs.recentDays
    val minRating = ui.prefs.minRating
    var ratingMenu by remember { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val fq = remember(q) { Query(q) }
    val albums = remember(cat, sort, days.value, minRating.value, fq, ui.version) { sortAlbums(ui, cat.albums.filterBy(fq) { it.searchKey }) }
    fun ratingLabel(r: Int) = when (r) { 0 -> "All"; -1 -> "Unrated"; 10 -> "10"; else -> "$r and up" }
    Column {
        FilterField(ui, q) { q = it }
        AlbumGrid(ui, albums, header = {
            // Sort order, the period of "Recently added" or "Recently liked", grid or list.
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                SortButton(sort.title, AlbumSort.entries.map { it.title }) { ui.prefs.albumSort.set(AlbumSort.entries[it].name) }
                Spacer(Modifier.weight(1f))
                if (sort == AlbumSort.Rating) Box {
                    Text(ratingLabel(minRating.value), Modifier.clip(RoundedCornerShape(8.dp)).clickable { ratingMenu = true }.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                    DropdownMenu(ratingMenu, { ratingMenu = false }, containerColor = Gray2) {
                        listOf(0, 10, 9, 8, 7, 6, 5, -1).forEach { r -> DropdownMenuItem({ Text(ratingLabel(r)) }, { ratingMenu = false; minRating.set(r) }) }
                    }
                }
                if (sort == AlbumSort.Recent || sort == AlbumSort.Liked) PeriodButton(ui)
                SmallIcon(if (ui.prefs.albumGrid.value) Icons.Default.ViewList else Icons.Default.GridView, size = 18.dp) {
                    ui.prefs.albumGrid.set(!ui.prefs.albumGrid.value)
                }
            }
        })
    }
}

/** Epoch ms from which "Recently added" and "Recently liked" keep things, for a period of [days]; 0 keeps all. */
private fun since(days: Int) = if (days > 0) System.currentTimeMillis() - days * 86_400_000L else 0L

/** [list] in the albums' sort order, with only the albums its period or rating keeps. */
fun sortAlbums(ui: Ui, list: List<Album>): List<Album> {
    val since = since(ui.prefs.recentDays.value)
    val minRating = ui.prefs.minRating.value
    return when (AlbumSort.entries.firstOrNull { it.name == ui.prefs.albumSort.value } ?: AlbumSort.Artist) {
        AlbumSort.Artist -> list
        AlbumSort.Recent -> list.filter { it.added * 1000 >= since }.sortedByDescending { it.added }
        AlbumSort.Liked -> list.map { a -> a to a.tracks.maxOf { ui.likeOrder(it) } }.filter { since == 0L || it.second >= since }
            .sortedWith(compareByDescending<Pair<Album, Long>> { it.second }.thenByDescending { it.first.added }).map { it.first }
        AlbumSort.Rating -> list.map { it to ui.rating(it) }
            .filter { (_, r) -> when (minRating) { 0 -> true; -1 -> r == null; else -> r != null && r >= minRating } }
            .sortedByDescending { it.second ?: 0.0 }.map { it.first }
    }
}

/** [list] in the tracks' sort order, with only the tracks its period keeps. */
fun sortTracks(ui: Ui, list: List<Track>): List<Track> {
    val since = since(ui.prefs.recentDays.value)
    return when (TrackSort.entries.firstOrNull { it.name == ui.prefs.trackSort.value } ?: TrackSort.Artist) {
        TrackSort.Artist -> list
        TrackSort.Recent -> list.filter { it.album.added * 1000 >= since }.sortedByDescending { it.album.added }
        // Tracks liked together keep their album order.
        TrackSort.Liked -> list.map { it to ui.likeOrder(it) }.filter { since == 0L || it.second >= since }
            .sortedWith(compareByDescending<Pair<Track, Long>> { it.second }.thenByDescending { it.first.album.added }).map { it.first }
    }
}

/** The period of "Recently added" and "Recently liked", shared by albums and tracks. */
@UnstableApi
@Composable
fun PeriodButton(ui: Ui) = Box {
    val days = ui.prefs.recentDays
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    fun label(d: Int) = when (d) { 0 -> "All time"; 1 -> "1 day"; else -> "$d days" }
    Text(label(days.value), Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(8.dp),
        style = MaterialTheme.typography.bodySmall, color = Muted)
    DropdownMenu(open, { open = false }, containerColor = Gray2) {
        listOf(1, 3, 7, 30, 365, 0).forEach { d -> DropdownMenuItem({ Text(label(d)) }, { open = false; days.set(d) }) }
        DropdownMenuItem({ Text("Custom…") }, { open = false; custom = true })
    }
    if (custom) {
        var text by remember { mutableStateOf(days.value.takeIf { it > 0 }?.toString() ?: "") }
        val n = text.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(
            { custom = false }, containerColor = Gray2, title = { Text("In the last") },
            text = {
                OutlinedTextField(text, { text = it.filter(Char::isDigit).take(5) }, singleLine = true, suffix = { Text("days") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = { TextButton({ days.set(n!!); custom = false }, enabled = n != null) { Text("OK") } },
            dismissButton = { TextButton({ custom = false }) { Text("Cancel") } },
        )
    }
}

/** The current sort order; a tap opens a menu of [options] and picks one by its index. */
@Composable
fun SortButton(title: String, options: List<String>, onPick: (Int) -> Unit) = Box {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp))
        Text(title, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
    DropdownMenu(open, { open = false }, containerColor = Gray2) {
        options.forEachIndexed { i, s -> DropdownMenuItem({ Text(s) }, { open = false; onPick(i) }) }
    }
}

@UnstableApi
@Composable
fun TracksTab(ui: Ui, cat: Catalog) {
    val sort = TrackSort.entries.firstOrNull { it.name == ui.prefs.trackSort.value } ?: TrackSort.Artist
    var q by rememberSaveable { mutableStateOf("") }
    val fq = remember(q) { Query(q) }
    val days = ui.prefs.recentDays.value
    val tracks = remember(cat, sort, days, fq, ui.version) { sortTracks(ui, cat.tracks.filterBy(fq) { it.searchKey }) }
    Column {
        FilterField(ui, q) { q = it }
        LazyColumn(contentPadding = listPadding()) {
            item {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    SortButton(sort.title, TrackSort.entries.map { it.title }) { ui.prefs.trackSort.set(TrackSort.entries[it].name) }
                    Spacer(Modifier.weight(1f))
                    Text("${tracks.size} tracks", style = MaterialTheme.typography.bodySmall, color = Muted)
                    if (sort != TrackSort.Artist) PeriodButton(ui)
                }
            }
            if (tracks.isEmpty() && q.isNotEmpty()) item { Text("Nothing found for “$q”", Modifier.padding(20.dp), color = Muted) }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(ui, t, showCover = true, onClick = { ui.play(tracks, i) }) }
        }
    }
}

@UnstableApi
@Composable
fun ArtistsTab(ui: Ui, cat: Catalog) {
    var q by rememberSaveable { mutableStateOf("") }
    val fq = remember(q) { Query(q) }
    val artists = remember(cat, fq) { cat.artists.filterBy(fq) { searchFold(it.first) } }
    Column {
        FilterField(ui, q) { q = it }
        LazyColumn(contentPadding = listPadding()) {
            if (artists.isEmpty() && q.isNotEmpty()) item { Text("Nothing found for “$q”", Modifier.padding(20.dp), color = Muted) }
            items(artists, key = { it.first }) { (name, albums) -> ArtistRow(ui, name, albums) }
        }
    }
}

@UnstableApi
@Composable
fun ArtistScreen(ui: Ui, cat: Catalog, name: String) {
    val albums = remember(cat, name) { cat.albums.filter { it.artist.equals(name, ignoreCase = true) }.sortedBy { it.year ?: 0 } }
    val tracks = remember(albums) { albums.flatMap { it.tracks } }
    var q by rememberSaveable { mutableStateOf("") }
    val fq = remember(q) { Query(q) }
    val shown = remember(albums, fq) { albums.filterBy(fq) { it.searchKey } }
    Column {
        FilterField(ui, q) { q = it }
        AlbumGrid(ui, shown, header = {
            PlayButtons(ui, "artist:$name", name, tracks, count = true, menu = { close ->
                DropdownMenuItem({ Text("Delete from bucket") }, { close(); ui.deleting = Ui.DeleteRequest(name, tracks, true) },
                    leadingIcon = { Icon(Icons.Default.DeleteForever, null) })
            })
        }, showArtist = false)
    }
}

const val NO_GENRE = "No genre"

@UnstableApi
@Composable
fun GenresTab(ui: Ui, cat: Catalog) {
    var q by rememberSaveable { mutableStateOf("") }
    val fq = remember(q) { Query(q) }
    val genres = remember(cat, fq) { cat.genres.filterBy(fq) { searchFold(it.first.ifEmpty { NO_GENRE }) } }
    Column {
        FilterField(ui, q) { q = it }
        LazyColumn(contentPadding = listPadding()) {
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
    val fq = remember(q) { Query(q) }
    val shown = remember(albums, fq) { albums.filterBy(fq) { it.searchKey } }
    Column {
        FilterField(ui, q) { q = it }
        AlbumGrid(ui, shown, header = { PlayButtons(ui, "genre:$name", name.ifEmpty { NO_GENRE }, tracks, count = true) })
    }
}

@UnstableApi
@Composable
fun FolderScreen(ui: Ui, cat: Catalog, path: String) {
    val f = remember(cat, path) { cat.folder(path) } ?: return
    var q by rememberSaveable { mutableStateOf("") }
    val fq = remember(q) { Query(q) }
    val subs = remember(f, fq) { f.folders.values.toList().filterBy(fq) { searchFold(it.name) } }
    val tracks = remember(f, fq) { f.tracks.filterBy(fq) { it.searchKey } }
    Column {
        FilterField(ui, q) { q = it }
        LazyColumn(contentPadding = listPadding()) {
            if (path.isNotEmpty()) item { PlayButtons(ui, "folder:$path", f.name, f.allTracks, count = true) }
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
    val fq = remember(q) { Query(q) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (q.isEmpty()) runCatching { focus.requestFocus() } }
    // Filtering 18k tracks runs off the main thread; the previous results stay until the new ones are ready.
    val found by produceState(Triple(emptyList<Pair<String, List<Album>>>(), emptyList<Album>(), emptyList<Track>()), cat, fq) {
        value = if (fq.folded.length < 2) Triple(emptyList(), emptyList(), emptyList()) else withContext(Dispatchers.Default) {
            Triple(cat.artists.search(fq) { searchFold(it.first) }, cat.albums.search(fq) { it.searchKey }, cat.tracks.search(fq) { it.searchKey })
        }
    }
    val (artists, albums, allTracks) = found
    val tracks = remember(allTracks) { allTracks.take(200) }
    @Composable fun more(n: Int, what: String) {
        if (n > 0) Text("$n more $what. Refine the search to see them", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    Column {
        SearchBox(ui.query, { ui.query = it }, "Artist, album or track", Modifier.focusRequester(focus))
        LazyColumn(contentPadding = listPadding()) {
            if (q.length == 1) item { Text("Type at least 2 letters", Modifier.padding(20.dp), color = Muted) }
            else if (q.length >= 2 && artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()) item { Text("Nothing found for “$q”", Modifier.padding(20.dp), color = Muted) }
            if (artists.isNotEmpty()) item { SectionTitle("Artists") }
            items(artists.take(10), key = { "r" + it.first }) { (name, albums) -> ArtistRow(ui, name, albums, inTab = Tab.Library) }
            item { more(artists.size - 10, "artists") }
            if (albums.isNotEmpty()) item { SectionTitle("Albums") }
            items(albums.take(30), key = { "a" + it.id }) { AlbumRow(ui, it) }
            item { more(albums.size - 30, "albums") }
            if (tracks.isNotEmpty()) item { SectionTitle("Tracks") }
            itemsIndexed(tracks, key = { _, t -> "t" + t.id }) { i, t ->
                TrackRow(ui, t, showCover = true, onClick = { focusManager.clearFocus(); ui.play(tracks, i) })
            }
            item { more(allTracks.size - tracks.size, "tracks") }
            if (q.length >= 2) item {
                ListRow(Icons.Default.TravelExplore, "Search Soulseek for “$q”", "Download albums through the computer") {
                    focusManager.clearFocus(); ui.open(Screen.SoulseekView(q))
                }
            }
        }
    }
}

/**
 * Download, add to playlist and a menu on the left, shuffle and a large play button on the right.
 * [count] shows the number of tracks; [menu] adds rows to the menu after "Add to queue".
 */
@UnstableApi
@Composable
fun PlayButtons(
    ui: Ui, key: String, title: String, tracks: List<Track>, count: Boolean = false,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    val dl = downloadProgress(ui, tracks)
    val allDownloaded = remember(tracks, ui.version, dl == null) { tracks.isNotEmpty() && tracks.all { ui.library.isDownloaded(it) } }
    var open by remember { mutableStateOf(false) }
    val close = { open = false }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dl != null) Box(Modifier.size(44.dp).clip(CircleShape).clickable { ui.open(Screen.Downloads) }, Alignment.Center) { DownloadRing(dl, 24.dp, MaterialTheme.colorScheme.primary) }
        else if (allDownloaded) SmallIcon(Icons.Default.DownloadDone, MaterialTheme.colorScheme.primary, 24.dp) { ui.deleteDownloads(tracks) }
        else SmallIcon(Icons.Default.Download, Color(0xFFC9C9C9), 24.dp) { ui.download(key, title, tracks) }
        SmallIcon(Icons.AutoMirrored.Filled.PlaylistAdd, Color(0xFFC9C9C9), 24.dp) { ui.addToPlaylist = tracks }
        Box {
            SmallIcon(Icons.Default.MoreHoriz, Color(0xFFC9C9C9), 24.dp) { open = true }
            DropdownMenu(open, close, containerColor = Gray2) {
                DropdownMenuItem({ Text("Add to queue") }, { close(); ui.addToEnd(tracks) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) })
                menu?.invoke(this, close)
            }
        }
        if (count) Text(if (tracks.size == 1) "1 track" else "${tracks.size} tracks", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
        Spacer(Modifier.weight(1f))
        SmallIcon(Icons.Default.Shuffle, MaterialTheme.colorScheme.onSurface, 26.dp) { ui.play(tracks, shuffled = true) }
        Box(
            Modifier.padding(start = 4.dp).size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { ui.play(tracks) },
            Alignment.Center,
        ) { Icon(Icons.Default.PlayArrow, "Play", Modifier.size(30.dp), tint = Color.Black) }
    }
}

@UnstableApi
@Composable
fun AlbumScreen(ui: Ui, a: Album) {
    LazyColumn(contentPadding = listPadding()) {
        item {
            Column {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), Alignment.Center) {
                    Cover(ui, a.cover, 232.dp, Modifier.shadow(16.dp, RoundedCornerShape(8.dp)), RoundedCornerShape(8.dp))
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                    Text(a.title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        a.artist, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.clickable { ui.open(Screen.ArtistView(a.artist), Tab.Library) }.padding(vertical = 4.dp),
                    )
                    Text(
                        listOfNotNull(a.year?.toString(), "${a.tracks.size} tracks", formatTime(a.duration)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                    RatingPicker(ui.rating(a)) { ui.setRating(a, it) }
                }
                Box(Modifier.padding(horizontal = 12.dp)) { PlayButtons(ui, a.id, a.title, a.tracks, menu = { close ->
                    DropdownMenuItem({ Text("Delete from bucket") }, { close(); ui.deleting = Ui.DeleteRequest(a.title, a.tracks, true) },
                        leadingIcon = { Icon(Icons.Default.DeleteForever, null) })
                }) }
            }
        }
        itemsIndexed(a.tracks, key = { _, t -> t.id }) { i, t -> TrackRow(ui, t, showCover = false, onClick = { ui.play(a.tracks, i) }) }
    }
}

// ---------------- playlists, liked, history ----------------

/** Covers of the first four albums of a playlist in a square, or the first cover when there are fewer. */
@UnstableApi
@Composable
fun PlaylistArt(ui: Ui, covers: List<String?>) {
    if (covers.size < 4) { Cover(ui, covers.firstOrNull(), 56.dp); return }
    Column(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))) {
        covers.chunked(2).forEach { row -> Row { row.forEach { Cover(ui, it, 28.dp, shape = RectangleShape) } } }
    }
}

@UnstableApi
@Composable
fun PlaylistsTab(ui: Ui, cat: Catalog) {
    val lists = remember(ui.version) { ui.app.playlists.all }
    val likedCount = remember(cat, ui.version) { cat.tracks.count { ui.library.isLiked(it) } }
    var q by rememberSaveable { mutableStateOf("") }
    val fq = remember(q) { Query(q) }
    val shown = remember(lists, fq) { lists.filterBy(fq) { searchFold(it.name) } }
    // Covers and the total time of each playlist, for its row.
    val details = remember(lists, cat) {
        lists.associate { p ->
            val tracks = ui.playlistTracks(p, cat)
            p.id to (tracks.map { it.album }.distinct().take(4).map { it.cover } to tracks.sumOf { it.duration })
        }
    }
    Column {
        FilterField(ui, q) { q = it }
        LazyColumn(contentPadding = listPadding()) {
            if (fq.isEmpty()) item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(LikedTint, Color(0xFF2A1D22)))).clickable { ui.open(Screen.LikedView) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)), Alignment.Center) {
                            Icon(Icons.Default.Favorite, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.padding(start = 14.dp)) {
                            Text("Liked", style = MaterialTheme.typography.titleLarge)
                            Text("$likedCount tracks", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9C9C9))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        @Composable fun tile(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) = Row(
                            Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF161616)).clickable(onClick = onClick).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, Modifier.size(22.dp), tint = Color(0xFFC9C9C9))
                            Text(text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        tile("History", Icons.Default.History) { ui.open(Screen.HistoryView) }
                        tile("Recently added", Icons.Default.LibraryAdd) {
                            ui.prefs.albumSort.set(AlbumSort.Recent.name); ui.showSection(LibrarySection.Albums); ui.stacks[Tab.Library] = listOf(Screen.Root); ui.tab = Tab.Library
                        }
                    }
                }
                if (lists.isNotEmpty()) Text("Your playlists", Modifier.padding(20.dp, 18.dp, 20.dp, 6.dp), style = MaterialTheme.typography.titleLarge)
            }
            items(shown, key = { it.id }) { p ->
                val (covers, time) = details[p.id] ?: (emptyList<String?>() to 0.0)
                Row(
                    Modifier.fillMaxWidth().clickable { ui.open(Screen.PlaylistView(p.id)) }.padding(horizontal = 20.dp).height(72.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistArt(ui, covers)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.trackIds.size} tracks · ${formatTime(time)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
        }
    }
    if (ui.newPlaylist) NameDialog("New playlist", "", { ui.newPlaylist = false }) { ui.createPlaylist(it) }
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
    val fq = remember(q) { Query(q) }
    // Rows keep their playlist index so remove works on the filtered list; dragging is off while filtering.
    val likeFilter = ui.libraryFilter
    val shown = remember(tracks, fq, likeFilter, ui.version) { tracks.indices.filter { ui.shows(tracks[it]) }.filterBy(fq) { tracks[it].searchKey } }
    val drag = fq.isEmpty() && likeFilter == LikeFilter.All
    // The header counts what the like filter shows.
    val counted = remember(tracks, likeFilter, ui.version) { tracks.filter { ui.shows(it) } }
    val shownTracks = remember(shown) { shown.map { tracks[it] } }
    Column {
        FilterField(ui, q) { q = it }
        LazyColumn(state = listState, contentPadding = listPadding()) {
            // One header item: the reorder callback counts track rows from index 1.
            item(key = "header") {
                Column {
                    ScreenTitle(p.name, "${counted.size} tracks · ${formatTime(counted.sumOf { it.duration })}")
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        PlayButtons(ui, "playlist:$id", p.name, shownTracks, menu = { close ->
                            DropdownMenuItem({ Text("Rename") }, { close(); renaming = true }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                            DropdownMenuItem({ Text("Delete") }, { close(); confirmDelete = true }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                        })
                    }
                }
            }
            itemsIndexed(shown, key = { _, i -> keys[i] }) { pos, i ->
                val t = tracks[i]
                ReorderableItem(reorder, key = keys[i], enabled = drag) {
                    TrackRow(
                        ui, t, showCover = true, onClick = { ui.play(shownTracks, pos) },
                        dragHandle = if (drag) ({ DragHandle(Modifier.draggableHandle()) }) else null,
                        dragModifier = if (drag) longPressDrag() else Modifier,
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

/** Long press anywhere on a row picks it up for dragging, with a buzz so it is clear the row is held. */
@Composable
fun ReorderableCollectionItemScope.longPressDrag(): Modifier {
    val haptic = LocalHapticFeedback.current
    return Modifier.longPressDraggableHandle(onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
}

@Composable
fun DragHandle(modifier: Modifier) =
    Box(modifier.size(40.dp, 60.dp), Alignment.Center) { Icon(Icons.Default.DragHandle, null, Modifier.size(20.dp), tint = Color(0xFF6E6E6E)) }

@UnstableApi
@Composable
fun LikedScreen(ui: Ui, cat: Catalog) {
    var byRecent by remember { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val fq = remember(q) { Query(q) }
    val liked = remember(cat, ui.version, byRecent, fq) {
        val l = cat.tracks.filter { ui.library.isLiked(it) }.filterBy(fq) { it.searchKey }
        if (byRecent) l.sortedWith(compareByDescending<Track> { ui.likeOrder(it) }.thenByDescending { it.album.added }) else l
    }
    Column {
        FilterField(ui, q) { q = it }
        LazyColumn(contentPadding = listPadding()) {
            item {
                ScreenTitle("Liked", "${liked.size} tracks")
                Box(Modifier.padding(horizontal = 12.dp)) {
                    PlayButtons(ui, "liked", "Liked", liked, menu = { close ->
                        DropdownMenuItem({ Text(if (byRecent) "Sort A–Z" else "Recently liked first") }, { close(); byRecent = !byRecent },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) })
                    })
                }
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
    val albumsOf = remember(cat) { cat.artists.toMap() }
    LazyColumn(contentPadding = listPadding()) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p -> Pill(p.label, active = p == period) { period = p } }
            }
            Text(
                "${plays(inPeriod.size)} · ${formatTime(inPeriod.sumOf { cat.byId[it.trackId]?.duration ?: 0.0 })} listened",
                Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium,
            )
            if (plays.isEmpty()) Text("Nothing yet. A play counts after half the track or 4 minutes.", Modifier.padding(20.dp), color = Muted)
        }
        if (topTracks.isNotEmpty()) item { SectionTitle("Top tracks") }
        itemsIndexed(topTracks, key = { _, p -> "t" + p.first.id }) { i, (t, n) ->
            TrackRow(ui, t, showCover = true, onClick = { ui.play(topTracks.map { it.first }, i) }, subtitle = "${plays(n)} · ${t.artist}")
        }
        if (topArtists.isNotEmpty()) item { SectionTitle("Top artists") }
        items(topArtists, key = { "a" + it.key }) { (name, n) -> ArtistRow(ui, name, albumsOf[name].orEmpty(), plays(n), Tab.Library) }
        if (forgotten.isNotEmpty()) item { SectionTitle("Haven't played in a while") }
        items(forgotten, key = { "f" + it.id }) { a -> AlbumRow(ui, a) }
        if (recent.isNotEmpty()) item { SectionTitle("Recently played") }
        itemsIndexed(recent.take(10), key = { _, t -> "r" + t.id }) { i, t -> TrackRow(ui, t, showCover = true, onClick = { ui.play(recent, i) }) }
    }
}

fun plays(n: Int) = if (n == 1) "1 play" else "$n plays"

@Composable
fun Placeholder(text: String) = Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text, color = Muted) }
