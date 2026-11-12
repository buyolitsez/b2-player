package dev.buyolitsez.b2player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val title: String) {
    Queue("Queue"), Now("Now playing"), Folders("Folders"), Albums("Albums"), Artists("Artists"),
    Playlists("Playlists"), Search("Search")
}

sealed interface Screen {
    data object Root : Screen
    data class AlbumView(val album: Album) : Screen
    data class ArtistView(val name: String) : Screen
    data class FolderView(val path: String) : Screen
    data class PlaylistView(val id: String) : Screen
    data object LikedView : Screen
    data object HistoryView : Screen
    data object Downloads : Screen
    data object LyricsView : Screen
    data object EqualizerView : Screen
    data object Settings : Screen
}

/** All UI state and actions, shared by the composables. */
@UnstableApi
class Ui(val context: Context, val app: App, val scope: CoroutineScope) {
    val covers = Covers(app)
    val snackbar = SnackbarHostState()
    val library get() = app.library
    val prefs get() = app.prefs

    var tab by mutableStateOf(runCatching { Tab.valueOf(app.prefs.startTab.value) }.getOrDefault(Tab.Albums))
    val stacks = mutableStateMapOf<Tab, List<Screen>>()
    var status by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    /** Bumped when likes, playlists or downloads change, so lists re-read them. */
    var version by mutableIntStateOf(0)
    var colors by mutableStateOf(DefaultColors)
    /** Tracks waiting for the "Add to playlist" dialog. */
    var addToPlaylist by mutableStateOf<List<Track>?>(null)

    var controller by mutableStateOf<MediaController?>(null)
    var currentId by mutableStateOf<String?>(null)
    var currentIndex by mutableIntStateOf(0)
    var queueIds by mutableStateOf(listOf<String>())
    var playing by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var nextId by mutableStateOf<String?>(null)

    fun sync(p: Player) {
        currentId = p.currentMediaItem?.mediaId
        currentIndex = p.currentMediaItemIndex
        queueIds = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        nextId = p.nextMediaItemIndex.takeIf { it >= 0 }?.let { p.getMediaItemAt(it).mediaId }
    }

    // ---------- navigation ----------

    fun stack(t: Tab = tab) = stacks[t] ?: listOf(Screen.Root)
    fun open(s: Screen, inTab: Tab? = null) {
        if (inTab != null) tab = inTab
        stacks[tab] = stack() + s
    }
    fun back(): Boolean {
        val st = stack()
        if (st.size > 1) { stacks[tab] = st.dropLast(1); return true }
        if (tab == Tab.Search && query.isNotEmpty()) { query = ""; return true }
        return false
    }
    fun selectTab(t: Tab) {
        if (t == tab && stack().size > 1) stacks[t] = listOf(Screen.Root) else tab = t
    }

    // ---------- library helpers ----------

    val offlineOnly get() = prefs.offlineOnly.value
    fun visible(t: Track) = !offlineOnly || library.availableOffline(t)
    fun liked(t: Track): Boolean { version; return library.isLiked(t) }
    fun downloaded(t: Track): Boolean { version; return library.isDownloaded(t) }

    // ---------- queue ----------

    private fun snapshot(): SavedQueue? {
        val c = controller ?: return null
        if (c.mediaItemCount == 0) return null
        return SavedQueue("", queueIds, c.currentMediaItemIndex, c.currentPosition)
    }

    private fun restore(q: SavedQueue, play: Boolean) {
        val c = controller ?: return
        val cat = app.catalog.value ?: return
        val items = q.ids.mapNotNull { cat.byId[it] }.map { library.mediaItem(it) }
        if (items.isEmpty()) return
        c.setMediaItems(items, q.index.coerceIn(0, items.size - 1), q.positionMs)
        c.prepare(); if (play) c.play()
    }

    /** Replaces the queue; the old one can be brought back from the snackbar. */
    fun play(tracks: List<Track>, index: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        val list = tracks.filter { visible(it) }
        if (list.isEmpty()) { toast("Nothing here is available offline"); return }
        val start = tracks.getOrNull(index)?.let { list.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val before = snapshot()
        c.shuffleModeEnabled = shuffled
        AppLog.d("ui", "Play ${list.size} tracks from ${list.first().album.dir}, start $start, shuffle $shuffled")
        c.setMediaItems(list.map { library.mediaItem(it) }, if (shuffled) list.indices.random() else start, 0)
        c.prepare(); c.play()
        if (prefs.undoReplace.value && before != null && before.ids != list.map { it.id }) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                if (snackbar.showSnackbar("Queue replaced", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    restore(before, play = true)
                }
            }
        }
    }

    fun playNext(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(tracks, 0); return }
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.filter { visible(it) }.map { library.mediaItem(it) })
        toast(if (tracks.size == 1) "Playing next: ${tracks[0].title}" else "Playing next: ${tracks.size} tracks")
    }

    fun addToEnd(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(tracks, 0); return }
        c.addMediaItems(tracks.filter { visible(it) }.map { library.mediaItem(it) })
        toast(if (tracks.size == 1) "Added to queue: ${tracks[0].title}" else "Added to queue: ${tracks.size} tracks")
    }

    private var lastSwipe = 0L
    fun swipeAllowed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSwipe < 700) return false
        lastSwipe = now; return true
    }

    fun moveInQueue(from: Int, to: Int) {
        queueIds = queueIds.toMutableList().apply { add(to, removeAt(from)) }
        controller?.moveMediaItem(from, to)
    }

    fun removeFromQueue(index: Int) { controller?.removeMediaItem(index) }
    fun clearQueue() {
        val before = snapshot() ?: return
        controller?.clearMediaItems()
        scope.launch {
            if (snackbar.showSnackbar("Queue cleared", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) restore(before, false)
        }
    }

    fun putQueueAside() {
        val q = snapshot() ?: return
        val first = app.catalog.value?.byId?.get(q.ids[q.index.coerceIn(0, q.ids.size - 1)])
        val name = first?.let { "${it.album.artist} — ${it.album.title}" } ?: "Queue"
        library.savedQueues = listOf(q.copy(name = name)) + library.savedQueues.take(9)
        version++
        toast("Queue put aside")
    }

    fun resumeSaved(i: Int) {
        val list = library.savedQueues.toMutableList()
        val q = list.removeAt(i)
        snapshot()?.let { cur -> list.add(0, cur.copy(name = "Previous queue")) }
        library.savedQueues = list.take(10)
        version++
        restore(q, play = true)
    }

    fun deleteSaved(i: Int) {
        library.savedQueues = library.savedQueues.toMutableList().apply { removeAt(i) }
        version++
    }

    fun stopAfter(t: Track) {
        app.stopAfterId.value = if (app.stopAfterId.value == t.id) null else t.id
        version++
        toast(if (app.stopAfterId.value != null) "Will stop after “${t.title}”" else "Won't stop")
    }

    // ---------- likes and downloads ----------

    fun toggleLike(t: Track) {
        val liked = !library.isLiked(t)
        library.setLiked(t, liked)
        if (liked && prefs.autoDownloadLiked.value) {
            DownloadWorker.enqueue(context, "like:${t.id}", t.title, listOf(t), wifiOnly = prefs.autoWifiOnly.value)
        }
        if (!liked && prefs.deleteOnUnlike.value) library.deleteDownload(t)
        version++
        syncInBackground { library.syncLikes(app.b2) }
    }

    fun download(name: String, title: String, tracks: List<Track>) {
        val todo = tracks.filter { !library.isDownloaded(it) }
        if (todo.isEmpty()) { toast("Already downloaded"); return }
        DownloadWorker.enqueue(context, name, title, todo, wifiOnly = prefs.manualWifiOnly.value)
        toast("Downloading ${todo.size} tracks, ${formatSize(todo.sumOf { it.size })}")
    }

    fun deleteDownloads(tracks: List<Track>) {
        scope.launch {
            withContext(Dispatchers.IO) { tracks.forEach { library.deleteDownload(it) } }
            version++
        }
    }

    // ---------- playlists ----------

    fun playlistTracks(p: Playlist, cat: Catalog) = p.trackIds.mapNotNull { cat.byId[it] }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Playlist {
        val p = app.playlists.create(name.trim().ifEmpty { "New playlist" }, tracks.map { it.id })
        version++; syncPlaylists()
        return p
    }

    fun addToPlaylist(p: Playlist, tracks: List<Track>) {
        app.playlists.add(p.id, tracks.map { it.id })
        version++; syncPlaylists()
        toast("Added to ${p.name}")
    }

    fun editPlaylist(id: String, f: (Playlist) -> Playlist) { app.playlists.update(id, f); version++; syncPlaylists() }
    fun deletePlaylist(id: String) { app.playlists.delete(id); version++; syncPlaylists() }

    fun saveQueueAsPlaylist() {
        val cat = app.catalog.value ?: return
        val tracks = queueIds.mapNotNull { cat.byId[it] }
        if (tracks.isEmpty()) return
        val first = tracks[currentIndex.coerceIn(0, tracks.size - 1)]
        createPlaylist("Queue: ${first.album.artist}", tracks)
        toast("Saved as playlist")
    }

    private fun syncPlaylists() = syncInBackground { app.playlists.sync(app.b2) }

    /** Imports Musicolet-style .m3u files; tracks are matched by file name and folder, then by tags. */
    fun importM3u(uris: List<Uri>) = scope.launch {
        val cat = app.catalog.value ?: return@launch
        val result = withContext(Dispatchers.IO) {
            val byName = cat.tracks.groupBy { it.path.substringAfterLast('/').lowercase() }
            val byTags = cat.tracks.associateBy { "${it.artist}\u0000${it.title}".lowercase() }
            uris.map { uri ->
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }?.substringBeforeLast('.') ?: "Imported"
                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                var extinf: String? = null
                var missed = 0
                val ids = ArrayList<String>()
                for (raw in text.lineSequence()) {
                    val line = raw.trim().removePrefix("﻿")
                    if (line.isEmpty()) continue
                    if (line.startsWith("#EXTINF", true)) { extinf = line.substringAfter(',', ""); continue }
                    if (line.startsWith("#")) continue
                    val parts = line.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                    val file = parts.lastOrNull()?.lowercase()
                    val dir = parts.getOrNull(parts.size - 2)?.lowercase()
                    val candidates = file?.let { byName[it] }.orEmpty()
                    val t = candidates.firstOrNull { it.path.split('/').dropLast(1).lastOrNull()?.lowercase() == dir }
                        ?: candidates.firstOrNull()
                        ?: extinf?.let { e -> e.split(" - ", limit = 2).takeIf { it.size == 2 }?.let { byTags["${it[0].trim()}\u0000${it[1].trim()}".lowercase()] } }
                    if (t != null) ids.add(t.id) else missed++
                    extinf = null
                }
                app.playlists.create(name, ids.distinct())
                Triple(name, ids.size, missed)
            }
        }
        version++; syncPlaylists()
        AppLog.i("m3u", "Imported " + result.joinToString { (n, ok, miss) -> "$n: $ok found, $miss missing" })
        toast(result.joinToString("; ") { (n, ok, miss) -> "$n: $ok tracks" + if (miss > 0) ", $miss not found" else "" })
    }

    // ---------- Last.fm ----------

    fun lastFmLogin(apiKey: String, secret: String, user: String, password: String) = scope.launch {
        app.lastFm.apiKey = apiKey; app.lastFm.secret = secret
        val err = withContext(Dispatchers.IO) { runCatching { app.lastFm.login(user, password) }.exceptionOrNull() }
        version++
        if (err != null) AppLog.w("lastfm", "Login failed", err) else AppLog.i("lastfm", "Connected")
        toast(if (err == null) "Connected to Last.fm as ${app.lastFm.user}" else err.message ?: "Last.fm login failed")
    }

    fun lastFmLogout() { app.lastFm.logout(); version++ }

    // ---------- sync and catalog ----------

    private fun syncInBackground(block: () -> Unit) = scope.launch {
        val err = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
        if (err != null) { status = "Sync failed: ${err.message}"; AppLog.w("sync", "Sync failed", err) }
    }

    fun toast(s: String) {
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(s, duration = SnackbarDuration.Short) }
    }

    fun refreshCatalog() = scope.launch {
        if (!app.b2.settings.complete) return@launch
        status = "Updating library…"
        val started = System.currentTimeMillis()
        status = try {
            // A HEAD request tells whether the catalog changed; most launches skip the download.
            val sha = withContext(Dispatchers.IO) { app.b2.open("catalog.json.gz", "HEAD").getHeaderField("x-bz-content-sha1") }
            if (sha != null && sha == library.catalogSha && library.catalogFile.exists()) {
                app.cachedCatalogLoad.join()
                AppLog.i("catalog", "Unchanged, checked in ${System.currentTimeMillis() - started} ms")
            } else {
                val bytes = withContext(Dispatchers.IO) { app.b2.open("catalog.json.gz").inputStream.use { it.readBytes() } }
                app.catalog.value = withContext(Dispatchers.Default) { Catalog.parse(bytes) }
                withContext(Dispatchers.IO) { library.catalogFile.writeBytes(bytes); library.catalogSha = sha }
                AppLog.i("catalog", "Loaded ${app.catalog.value?.albums?.size} albums, ${bytes.size / 1024} KB, ${System.currentTimeMillis() - started} ms")
            }
            version++
            null
        } catch (e: Exception) {
            AppLog.e("catalog", "Library update failed", e)
            "Couldn't load the library: ${e.message}"
        }
        if (status != null) return@launch
        // Likes, playlists and history sync after the library is already usable.
        val syncStarted = System.currentTimeMillis()
        syncInBackground {
            library.syncLikes(app.b2)
            app.playlists.sync(app.b2)
            app.history.sync(app.b2)
            runCatching { app.lastFm.flush() }
            AppLog.i("sync", "Likes, playlists and history synced in ${System.currentTimeMillis() - syncStarted} ms")
        }.join()
        version++
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%d MB".format(bytes shr 20)
    else -> "%d KB".format(bytes shr 10)
}

fun formatTime(sec: Double): String {
    val s = sec.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
