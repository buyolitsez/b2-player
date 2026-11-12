package dev.buyolitsez.b2player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    val p = ui.app.prefs
    var recent by remember { mutableStateOf(p.albumSortRecent) }
    val albums = remember(cat, recent, ui.version) {
        val list = cat.albums.filter { a -> !ui.offlineOnly || a.tracks.any { ui.library.availableOffline(it) } }
        if (recent) list.sortedByDescending { it.added } else list
    }
    AlbumGrid(ui, albums, header = {
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("А–Я", active = !recent) { recent = false; p.albumSortRecent = false }
            Pill("Недавно добавленные", active = recent) { recent = true; p.albumSortRecent = true }
        }
    })
}

@UnstableApi
@Composable
fun ArtistsTab(ui: Ui, cat: Catalog) {
    LazyColumn {
        items(cat.artists, key = { it.first }) { (name, albums) ->
            ListRow(Icons.Default.Person, name, "альбомов: ${albums.size}") { ui.open(Screen.ArtistView(name)) }
        }
    }
}

@UnstableApi
@Composable
fun ArtistScreen(ui: Ui, cat: Catalog, name: String) {
    val albums = remember(cat, name) { cat.albums.filter { it.artist == name }.sortedBy { it.year ?: 0 } }
    val tracks = remember(albums) { albums.flatMap { it.tracks } }
    AlbumGrid(ui, albums, header = {
        PlayButtons(ui, "artist:$name", name, tracks)
    })
}

@UnstableApi
@Composable
fun FolderScreen(ui: Ui, cat: Catalog, path: String) {
    val f = remember(cat, path) { cat.folder(path) } ?: return
    val subs = f.folders.values.toList()
    LazyColumn {
        if (path.isNotEmpty()) item { PlayButtons(ui, "folder:$path", f.name, f.allTracks) }
        items(subs, key = { "f" + it.path }) { s ->
            ListRow(Icons.Default.Folder, s.name, "${s.allTracks.size} тр.") { ui.open(Screen.FolderView(s.path)) }
        }
        val tracks = f.tracks.filter { ui.visible(it) }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(ui, t, showCover = false, onClick = { ui.play(tracks, i) }) }
    }
}

@UnstableApi
@Composable
fun LikedTab(ui: Ui, cat: Catalog) {
    val liked = remember(cat, ui.version) { cat.tracks.filter { ui.library.isLiked(it) && ui.visible(it) } }
    LazyColumn {
        item { PlayButtons(ui, "liked", "Любимые", liked) }
        itemsIndexed(liked, key = { _, t -> t.id }) { i, t -> TrackRow(ui, t, showCover = true, onClick = { ui.play(liked, i) }) }
    }
}

@UnstableApi
@Composable
fun SearchTab(ui: Ui, cat: Catalog) {
    val q = ui.query.trim()
    val focus = remember { FocusRequester() }
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
            placeholder = { Text("Исполнитель, альбом, трек") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (ui.query.isNotEmpty()) SmallIcon(Icons.Default.Close) { ui.query = "" } },
        )
        LazyColumn {
            if (albums.isNotEmpty()) item { SectionTitle("Альбомы") }
            items(albums.take(30), key = { "a" + it.id }) { AlbumRow(ui, it) }
            if (tracks.isNotEmpty()) item { SectionTitle("Треки") }
            itemsIndexed(tracks, key = { _, t -> "t" + t.id }) { i, t -> TrackRow(ui, t, showCover = true, onClick = { ui.play(tracks, i) }) }
        }
    }
}

/** Play / shuffle / download row for a list of tracks. */
@UnstableApi
@Composable
fun PlayButtons(ui: Ui, key: String, title: String, tracks: List<Track>) {
    val allDownloaded = remember(tracks, ui.version) { tracks.isNotEmpty() && tracks.all { ui.library.isDownloaded(it) } }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill("Слушать", Icons.Default.PlayArrow, active = true) { ui.play(tracks, 0) }
        Pill("Перемешать", Icons.Default.Shuffle) { ui.play(tracks, 0, shuffled = true) }
        if (allDownloaded) Pill("Скачано", Icons.Default.DownloadDone) { ui.deleteDownloads(tracks) }
        else Pill("Скачать", Icons.Default.Download) { ui.download(key, title, tracks) }
        Text("${tracks.size} тр.", style = MaterialTheme.typography.bodySmall, color = Muted)
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
                        listOfNotNull(a.year?.toString(), "${a.tracks.size} тр.", formatTime(a.duration)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
            }
            PlayButtons(ui, a.id, a.title, a.tracks)
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(ui, t, showCover = false, onClick = { ui.play(tracks, i) }) }
    }
}

// ---------------- queue ----------------

@UnstableApi
@Composable
fun QueueTab(ui: Ui, cat: Catalog) {
    val ids = ui.queueIds
    val tracks = ids.map { cat.byId[it] }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        ui.moveInQueue(from.index - 1, to.index - 1)
    }
    LaunchedEffect(Unit) { if (ui.currentIndex > 2) listState.scrollToItem(ui.currentIndex - 1) }
    val saved = remember(ui.version) { ui.library.savedQueues }
    val left = tracks.drop(ui.currentIndex).sumOf { it?.duration ?: 0.0 }
    var showSaved by remember { mutableStateOf(false) }
    LazyColumn(state = listState) {
        item(key = "header") {
            Column {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Отложить", Icons.Default.Bookmark) { ui.putQueueAside() }
                    Pill("Отложенные · ${saved.size}", Icons.Default.Bookmarks, active = showSaved) { showSaved = !showSaved }
                    Spacer(Modifier.weight(1f))
                    if (ids.isNotEmpty()) Text("${ui.currentIndex + 1} / ${ids.size} · ${formatTime(left)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                if (showSaved) {
                    if (saved.isEmpty()) Text("Нет отложенных очередей", Modifier.padding(12.dp), color = Muted)
                    saved.forEachIndexed { i, q ->
                        Row(
                            Modifier.fillMaxWidth().clickable { showSaved = false; ui.resumeSaved(i) }.padding(start = 12.dp).height(48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Bookmark, null, Modifier.size(18.dp), tint = Muted)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(q.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${q.index + 1} / ${q.ids.size}", style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                            SmallIcon(Icons.Default.Close) { ui.deleteSaved(i) }
                        }
                    }
                    HorizontalDivider(color = Gray2)
                }
                if (ids.isEmpty()) Text("Очередь пуста. Запустите альбом или трек.", Modifier.padding(16.dp), color = Muted)
            }
        }
        itemsIndexed(tracks, key = { i, _ -> "$i:${ids[i]}" }) { i, t ->
            ReorderableItem(reorder, key = "$i:${ids[i]}") {
                if (t == null) return@ReorderableItem
                TrackRow(ui, t, showCover = true, onClick = { ui.controller?.run { seekTo(i, 0); play() } }, queueIndex = i, dragHandle = {
                    Box(Modifier.draggableHandle().size(36.dp, 52.dp), Alignment.Center) {
                        Icon(Icons.Default.DragHandle, null, Modifier.size(18.dp), tint = Muted)
                    }
                })
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
                "Скачано: ${formatSize(total)} · кэш: ${formatSize(ui.library.cacheBytes())} из ${ui.app.prefs.cacheGb} ГБ",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (active.isNotEmpty()) item { SectionTitle("Качается") }
        items(active, key = { it.id }) { w ->
            val title = w.progress.getString("title") ?: "В очереди"
            val done = w.progress.getInt("done", 0); val all = w.progress.getInt("total", 0)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row {
                    Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (w.state == WorkInfo.State.ENQUEUED) "ждёт сеть" else "$done / $all", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                LinearProgressIndicator({ if (all > 0) done / all.toFloat() else 0f }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        item { SectionTitle("На телефоне") }
        val list = downloaded
        if (list == null) item { Text("Считаю…", Modifier.padding(12.dp), color = Muted) }
        else if (list.isEmpty()) item { Text("Пока ничего не скачано", Modifier.padding(12.dp), color = Muted) }
        else items(list, key = { it.first.id }) { (a, t) ->
            Row(
                Modifier.fillMaxWidth().clickable { ui.open(Screen.AlbumView(a)) }.padding(start = 12.dp).height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(ui, a.cover, 40.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.artist} · ${t.size} из ${a.tracks.size} · ${formatSize(t.distinctBy { it.path }.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
                }
                SmallIcon(Icons.Default.Delete) { ui.deleteDownloads(t) }
            }
        }
    }
}

// ---------------- settings ----------------

@UnstableApi
@Composable
fun SettingsScreen(ui: Ui, cat: Catalog?) {
    val app = ui.app
    val s = remember { app.b2.settings }
    var keyId by remember { mutableStateOf(s.keyId) }
    var appKey by remember { mutableStateOf(s.appKey) }
    var bucket by remember { mutableStateOf(s.bucket) }
    var cacheGb by remember { mutableFloatStateOf(app.prefs.cacheGb.toFloat()) }
    var autoLiked by remember { mutableStateOf(app.prefs.autoDownloadLiked) }
    val offline by app.prefs.offlineOnly.collectAsState()
    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Backblaze B2", style = MaterialTheme.typography.titleSmall, color = Muted)
        OutlinedTextField(keyId, { keyId = it }, Modifier.fillMaxWidth(), label = { Text("keyID") }, singleLine = true)
        OutlinedTextField(
            appKey, { appKey = it }, Modifier.fillMaxWidth(), label = { Text("applicationKey") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
        OutlinedTextField(bucket, { bucket = it }, Modifier.fillMaxWidth(), label = { Text("Бакет") }, singleLine = true)
        Pill("Сохранить и обновить каталог", Icons.Default.Sync, active = true) {
            app.b2.settings = B2.Settings(keyId, appKey, bucket); ui.refreshCatalog(); ui.toast("Сохранено")
        }
        HorizontalDivider(color = Gray2)
        Text("Офлайн", style = MaterialTheme.typography.titleSmall, color = Muted)
        SwitchRow("Только офлайн", "Показывать только скачанное и закэшированное", offline) { app.prefs.setOfflineOnly(it); ui.version++ }
        SwitchRow("Скачивать любимые", "Лайк скачивает трек по Wi-Fi, снятый лайк удаляет копию", autoLiked) {
            autoLiked = it; app.prefs.autoDownloadLiked = it
        }
        if (cat != null) {
            val liked = remember(cat, ui.version) { cat.tracks.filter { ui.library.isLiked(it) && !ui.library.isDownloaded(it) } }
            if (liked.isNotEmpty()) Pill("Скачать все любимые: ${liked.size} тр., ${formatSize(liked.sumOf { it.size })}", Icons.Default.Download) {
                DownloadWorker.enqueue(ui.context, "liked-all", "Любимые", liked, wifiOnly = true); ui.toast("Скачаю по Wi-Fi")
            }
        }
        Text("Кэш стрима: ${cacheGb.toInt()} ГБ (сейчас занято ${formatSize(ui.library.cacheBytes())})")
        Slider(cacheGb, { cacheGb = it }, valueRange = 1f..50f, steps = 48, onValueChangeFinished = {
            app.prefs.cacheGb = cacheGb.toInt(); ui.toast("Новый размер кэша заработает после перезапуска приложения")
        })
        Pill("Очистить кэш", Icons.Default.DeleteSweep) { ui.library.clearCache(); ui.version++; ui.toast("Кэш очищен") }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Switch(value, onChange)
    }
}

@Composable
fun Placeholder(text: String) = Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text, color = Muted) }
