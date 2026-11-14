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
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val title: String) { Library("Library"), Playlists("Playlists"), Search("Search") }

/** What the Library tab lists. */
enum class LibrarySection { Albums, Artists, Genres, Folders }

/** Which tracks of what was played the queue keeps. */
enum class QueueFilter(val title: String) { All("All"), Liked("Liked"), NotLiked("Not liked") }

sealed interface Screen {
    data object Root : Screen
    data class AlbumView(val album: Album) : Screen
    data class ArtistView(val name: String) : Screen
    data class GenreView(val name: String) : Screen
    data class FolderView(val path: String) : Screen
    data class PlaylistView(val id: String) : Screen
    data object LikedView : Screen
    data object HistoryView : Screen
    data object Downloads : Screen
    data object EqualizerView : Screen
    data object Settings : Screen
    data object Accounts : Screen
    data object Assistant : Screen
}

/** All UI state and actions, shared by the composables. */
@UnstableApi
class Ui(val context: Context, val app: App, val scope: CoroutineScope) {
    val covers = Covers(app)
    val assistant = Assistant(this)
    val snackbar = SnackbarHostState()
    val library get() = app.library
    val prefs get() = app.prefs

    private fun startTab() = Tab.entries.firstOrNull { it.name == app.prefs.startTab.value } ?: Tab.Library
    var tab by mutableStateOf(startTab())
    var section by mutableStateOf(LibrarySection.entries.firstOrNull { it.name == prefs.librarySection.value } ?: LibrarySection.Albums)
        private set
    fun showSection(s: LibrarySection) { section = s; prefs.librarySection.set(s.name) }
    /** Now playing, and the queue on top of it, cover the tabs. */
    var nowOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    /** The search field above a list is shown; it closes when the screen changes. */
    var filterOpen by mutableStateOf(false)
    val stacks = mutableStateMapOf<Tab, List<Screen>>()
    var status by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    /** Bumped when likes, playlists or downloads change, so lists re-read them. */
    var version by mutableIntStateOf(0)
    var colors by mutableStateOf(DefaultColors)
    /** Tracks waiting for the "Add to playlist" dialog. */
    var addToPlaylist by mutableStateOf<List<Track>?>(null)
    /** Track shown in the "Track info" dialog. */
    var trackInfo by mutableStateOf<Track?>(null)
    /** The track whose actions sheet is open; [extra] adds rows of the screen it was opened from. */
    class TrackMenuRequest(val track: Track, val queueIndex: Int?, val extra: (@androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.(close: () -> Unit) -> Unit)?)
    var trackMenu by mutableStateOf<TrackMenuRequest?>(null)
    /** The "New playlist" dialog is open. */
    var newPlaylist by mutableStateOf(false)

    init {
        // Likes from here and from the media notification re-read the lists.
        scope.launch { app.likesChanged.collect { version++ } }
    }

    var controller by mutableStateOf<MediaController?>(null)
    var currentId by mutableStateOf<String?>(null)
    var currentIndex by mutableIntStateOf(0)
    var queueIds by mutableStateOf(listOf<String>())
    var playing by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var nextId by mutableStateOf<String?>(null)
    /** Queue indices in play order: the shuffle order when shuffle is on. */
    var playOrder by mutableStateOf(listOf<Int>())

    fun sync(p: Player) {
        currentId = p.currentMediaItem?.mediaId
        currentIndex = p.currentMediaItemIndex
        queueIds = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        nextId = p.nextMediaItemIndex.takeIf { it >= 0 }?.let { p.getMediaItemAt(it).mediaId }
        val tl = p.currentTimeline
        playOrder = if (!p.shuffleModeEnabled || tl.isEmpty) queueIds.indices.toList()
        else generateSequence(tl.getFirstWindowIndex(true).takeIf { it != C.INDEX_UNSET }) {
            tl.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, true).takeIf { i -> i != C.INDEX_UNSET }
        }.toList().takeIf { it.size == queueIds.size } ?: queueIds.indices.toList()
    }

    // ---------- navigation ----------

    fun stack(t: Tab = tab) = stacks[t] ?: listOf(Screen.Root)
    /** Opening a screen closes Now playing; [section] also switches the Library tab to it. */
    fun open(s: Screen, inTab: Tab? = null, section: LibrarySection? = null) {
        nowOpen = false; queueOpen = false
        if (inTab != null) tab = inTab
        if (section != null) showSection(section)
        stacks[tab] = stack() + s
    }
    fun back(): Boolean {
        if (queueOpen) { queueOpen = false; return true }
        if (nowOpen) { nowOpen = false; return true }
        val st = stack()
        if (st.size > 1) { stacks[tab] = st.dropLast(1); return true }
        if (tab == Tab.Search && query.isNotEmpty()) { query = ""; return true }
        if (tab != startTab()) { tab = startTab(); return true }
        return false
    }
    fun selectTab(t: Tab) {
        if (t == tab && stack().size > 1) stacks[t] = listOf(Screen.Root) else tab = t
    }

    // ---------- library helpers ----------

    /** Which tracks the queue keeps, see [setQueueFilter]. */
    val queueFilter get() = QueueFilter.entries.firstOrNull { it.name == prefs.queueFilter.value } ?: QueueFilter.All
    private fun passes(t: Track) = when (queueFilter) {
        QueueFilter.All -> true
        QueueFilter.Liked -> library.isLiked(t)
        QueueFilter.NotLiked -> !library.isLiked(t)
    }

    fun liked(t: Track): Boolean { version; return library.isLiked(t) }
    fun downloaded(t: Track): Boolean { version; return library.isDownloaded(t) }

    // ---------- queue ----------

    private fun snapshot(): SavedQueue? {
        val c = controller ?: return null
        if (c.mediaItemCount == 0) return null
        return SavedQueue("", queueIds, c.currentMediaItemIndex, c.currentPosition)
    }

    private fun restore(q: SavedQueue, play: Boolean, shuffle: Boolean? = null) {
        val c = controller ?: return
        val cat = app.catalog.value ?: return
        val items = q.ids.mapNotNull { cat.byId[it] }.map { library.mediaItem(it) }
        if (items.isEmpty()) return
        library.queueSource = q.ids
        if (shuffle != null) c.shuffleModeEnabled = shuffle
        c.setMediaItems(items, q.index.coerceIn(0, items.size - 1), q.positionMs)
        c.playWhenReady = play
        c.prepare()
    }

    /**
     * Replaces the queue; the old one can be brought back from the snackbar. [index] is the tapped
     * track, which plays even when the queue filter leaves it out; without it the queue starts at the top.
     */
    fun play(tracks: List<Track>, index: Int = -1, shuffled: Boolean = false) = scope.launch {
        val c = controller ?: return@launch
        val tapped = tracks.getOrNull(index)
        val list = tracks.filter { it == tapped || passes(it) }
        if (list.isEmpty()) { toast("No ${queueFilter.title.lowercase()} tracks here"); return@launch }
        val start = tapped?.let { list.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val before = snapshot()
        val wasShuffled = c.shuffleModeEnabled
        // "Shuffle" turns shuffle on; a plain play keeps whatever mode the player is in.
        if (shuffled) c.shuffleModeEnabled = true
        AppLog.d("ui", "Play ${list.size} tracks from ${list.first().album.dir}, start $start, shuffle $shuffled")
        // A file check per track: for a 2600-track list that is too slow for the main thread.
        val items = withContext(Dispatchers.IO) { list.map { library.mediaItem(it) } }
        if (controller !== c) return@launch
        library.queueSource = tracks.map { it.id }
        c.setMediaItems(items, if (shuffled) list.indices.random() else start, 0)
        c.prepare(); c.play()
        if (prefs.undoReplace.value && before != null && before.ids != list.map { it.id }) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                if (snackbar.showSnackbar("Queue replaced", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    restore(before, play = true, shuffle = wasShuffled)
                }
            }
        }
    }

    fun playNext(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(tracks); return }
        val src = source()
        val at = src.indexOf(currentId) + 1
        library.queueSource = src.take(at) + tracks.map { it.id } + src.drop(at)
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.filter { passes(it) }.map { library.mediaItem(it) })
        toast(if (tracks.size == 1) "Playing next: ${tracks[0].title}" else "Playing next: ${tracks.size} tracks")
    }

    fun addToEnd(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(tracks); return }
        library.queueSource = source() + tracks.map { it.id }
        c.addMediaItems(tracks.filter { passes(it) }.map { library.mediaItem(it) })
        toast(if (tracks.size == 1) "Added to queue: ${tracks[0].title}" else "Added to queue: ${tracks.size} tracks")
    }

    private var lastSwipe = 0L
    fun swipeAllowed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSwipe < 700) return false
        lastSwipe = now; return true
    }

    /**
     * Everything that was played or added, before the filter: the filter can be switched back.
     * Moves and removals made in the queue are not kept here.
     */
    private fun source() = library.queueSource.ifEmpty { queueIds }

    /** Keeps only the tracks of [source] that pass [f]; the current track plays on either way. */
    fun setQueueFilter(f: QueueFilter) = scope.launch {
        prefs.queueFilter.set(f.name)
        val c = controller ?: return@launch
        val cat = app.catalog.value ?: return@launch
        if (c.mediaItemCount == 0) return@launch
        val cur = c.currentMediaItemIndex
        val src = source()
        val at = src.indexOf(c.currentMediaItem?.mediaId).takeIf { it >= 0 }
        fun items(ids: List<String>) = ids.mapNotNull { cat.byId[it] }.filter { passes(it) }.map { library.mediaItem(it) }
        val (before, after) = withContext(Dispatchers.IO) {
            if (at == null) emptyList<androidx.media3.common.MediaItem>() to items(src)
            else items(src.take(at)) to items(src.drop(at + 1))
        }
        if (controller !== c || c.currentMediaItemIndex != cur) return@launch
        AppLog.d("ui", "Queue filter ${f.name}: ${before.size + after.size + 1} of ${src.size} tracks")
        // The current track is not touched, so it doesn't stop.
        c.removeMediaItems(cur + 1, c.mediaItemCount)
        c.removeMediaItems(0, cur)
        c.addMediaItems(0, before)
        c.addMediaItems(after)
        // A new shuffle order with the current track first; added tracks would otherwise go in order.
        if (c.shuffleModeEnabled) { c.shuffleModeEnabled = false; c.shuffleModeEnabled = true }
    }

    fun moveInQueue(from: Int, to: Int) {
        queueIds = queueIds.toMutableList().apply { add(to, removeAt(from)) }
        controller?.moveMediaItem(from, to)
    }

    fun removeFromQueue(index: Int) {
        queueIds.getOrNull(index)?.let { id -> library.queueSource = source().toMutableList().apply { remove(id) } }
        controller?.removeMediaItem(index)
    }
    fun clearQueue() {
        val before = snapshot() ?: return
        controller?.clearMediaItems()
        library.queueSource = emptyList()
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
        toast("Queue saved for later")
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

    fun toggleLike(t: Track) = setLiked(listOf(t), !library.isLiked(t))

    fun setLiked(tracks: List<Track>, liked: Boolean) = app.setLiked(tracks, liked)

    fun download(name: String, title: String, tracks: List<Track>) {
        val todo = tracks.filter { !library.isDownloaded(it) }
        if (todo.isEmpty()) { toast("Already downloaded"); return }
        DownloadWorker.enqueue(context, name, title, todo, wifiOnly = prefs.manualWifiOnly.value)
        toast("Downloading ${todo.size} tracks, ${formatSize(todo.distinctBy { it.path }.sumOf { it.size })}")
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

    companion object { const val UPDATING = "Updating library…"; const val SYNC_FAILED = "Sync failed: " }

    // ---------- sync and catalog ----------

    private fun syncInBackground(block: () -> Unit) = scope.launch {
        val err = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
        if (err != null) { status = SYNC_FAILED + err.message; AppLog.w("sync", "Sync failed", err) }
        else if (status?.startsWith(SYNC_FAILED) == true) status = null
    }

    fun toast(s: String) {
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(s, duration = SnackbarDuration.Short) }
    }

    fun refreshCatalog() = scope.launch {
        if (!app.b2.settings.complete) return@launch
        status = UPDATING
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

/** Play, preparing first: a queue restored at start is left unprepared until it is played. */
fun Player.resume() { if (playbackState == Player.STATE_IDLE) prepare(); play() }

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%d MB".format(bytes shr 20)
    else -> "%d KB".format(bytes shr 10)
}

fun formatTime(sec: Double): String {
    val s = sec.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
