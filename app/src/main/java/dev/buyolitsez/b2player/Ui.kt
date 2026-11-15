package dev.buyolitsez.b2player

import android.content.Context
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val title: String) { Library("Library"), Playlists("Playlists"), Search("Search") }

/** What the Library tab lists. */
enum class LibrarySection { Albums, Artists, Genres, Folders }

/** Which tracks the queue (see [Ui.setQueueFilter]) and the library (see [Ui.libraryFilter]) keep. */
enum class LikeFilter(val title: String) { All("All"), Liked("Liked"), NotLiked("Not liked") }

enum class AlbumSort(val title: String) { Artist("By artist"), Recent("Recently added"), Rating("By rating") }

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
    /** Tracks the "Delete from bucket" dialog asks about; [back] leaves the screen of the album or artist after. */
    class DeleteRequest(val title: String, val tracks: List<Track>, val back: Boolean)
    var deleting by mutableStateOf<DeleteRequest?>(null)
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
    /** The queue and play order [moveInQueue] expects, see [sync]. */
    private var movedOrder: Pair<List<String>, List<Int>>? = null

    fun sync(p: Player) {
        currentId = p.currentMediaItem?.mediaId
        currentIndex = p.currentMediaItemIndex
        queueIds = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        nextId = p.nextMediaItemIndex.takeIf { it >= 0 }?.let { p.getMediaItemAt(it).mediaId }
        val tl = p.currentTimeline
        val order = if (!p.shuffleModeEnabled || tl.isEmpty) queueIds.indices.toList()
        else generateSequence(tl.getFirstWindowIndex(true).takeIf { it != C.INDEX_UNSET }) {
            tl.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, true).takeIf { i -> i != C.INDEX_UNSET }
        }.toList().takeIf { it.size == queueIds.size } ?: queueIds.indices.toList()
        // After a move the controller shows the moved track at a random place in the play order until the
        // player's order arrives; a dragged row would jump there and back.
        val moved = movedOrder?.takeIf { it.first == queueIds && p.shuffleModeEnabled }
        if (moved == null || moved.second == order) movedOrder = null
        playOrder = moved?.second ?: order
    }

    // ---------- navigation ----------

    fun stack(t: Tab = tab) = stacks[t] ?: listOf(Screen.Root)
    /** Opening a screen closes Now playing; [section] also switches the Library tab to it. */
    fun open(s: Screen, inTab: Tab? = null, section: LibrarySection? = null) {
        nowOpen = false; queueOpen = false
        val from = tab
        if (inTab != null) tab = inTab
        if (section != null) showSection(section)
        stacks[tab] = stack() + s
        if (tab != from) returnTo = Triple(tab, stack().size, from)
    }
    /** A screen opened from another tab (e.g. an artist from Search): back from it returns to that tab. */
    private var returnTo: Triple<Tab, Int, Tab>? = null
    fun back(): Boolean {
        if (queueOpen) { queueOpen = false; return true }
        if (nowOpen) { nowOpen = false; return true }
        val st = stack()
        if (st.size > 1) {
            stacks[tab] = st.dropLast(1)
            returnTo?.let { (t, size, from) -> if (t == tab && size == st.size) { tab = from; returnTo = null } }
            return true
        }
        if (tab == Tab.Search && query.isNotEmpty()) { query = ""; return true }
        if (tab != startTab()) { tab = startTab(); return true }
        return false
    }
    fun selectTab(t: Tab) {
        returnTo = null
        if (t == tab && stack().size > 1) stacks[t] = listOf(Screen.Root) else tab = t
    }

    // ---------- library helpers ----------

    private fun LikeFilter.keeps(t: Track) = when (this) {
        LikeFilter.All -> true
        LikeFilter.Liked -> library.isLiked(t)
        LikeFilter.NotLiked -> !library.isLiked(t)
    }

    /** Which tracks the queue keeps, see [setQueueFilter]. */
    val queueFilter get() = LikeFilter.entries.firstOrNull { it.name == prefs.queueFilter.value } ?: LikeFilter.All
    private fun passes(t: Track) = queueFilter.keeps(t)

    /** Which tracks the library, search and playlists show; liked ones are the downloaded ones, so this is what plays on a bad network. */
    val libraryFilter get() = LikeFilter.entries.firstOrNull { it.name == prefs.libraryFilter.value } ?: LikeFilter.All
    fun shows(t: Track) = libraryFilter.keeps(t)
    fun setLibraryFilter(f: LikeFilter) {
        prefs.libraryFilter.set(f.name)
        toast(when (f) { LikeFilter.All -> "Showing all tracks"; LikeFilter.Liked -> "Showing liked tracks only"; LikeFilter.NotLiked -> "Showing tracks that aren't liked" })
    }

    /** [cat] with only the tracks [libraryFilter] keeps; albums left without tracks are dropped. */
    fun libraryView(cat: Catalog): Catalog {
        val f = libraryFilter
        if (f == LikeFilter.All) return cat
        return Catalog(cat.albums.mapNotNull { a ->
            val kept = a.tracks.filter { f.keeps(it) }
            when (kept.size) {
                0 -> null
                a.tracks.size -> a
                else -> Album(a.id, a.dir, a.title, a.artist, a.year, a.cover, a.added, a.genre).also { it.tracks = kept }
            }
        })
    }

    fun liked(t: Track): Boolean { version; return library.isLiked(t) }
    fun downloaded(t: Track): Boolean { version; return library.isDownloaded(t) }
    fun rating(a: Album): Double? { version; return app.ratings.get(a.id) }

    fun setRating(a: Album, rating: Int) {
        app.ratings.set(a.id, rating)
        version++
        app.scope.launch(Dispatchers.IO) { runCatching { app.syncRatings() }.onFailure { AppLog.w("sync", "Ratings sync failed", it) } }
    }

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
     * track; without it the queue starts at the top. A new queue drops the queue filter.
     */
    fun play(tracks: List<Track>, index: Int = -1, shuffled: Boolean = false, positionMs: Long = 0) = scope.launch {
        val c = controller ?: return@launch
        if (tracks.isEmpty()) return@launch
        filterJob?.cancel()
        prefs.queueFilter.set(LikeFilter.All.name)
        val start = index.coerceIn(0, tracks.size - 1)
        val before = snapshot()
        val wasShuffled = c.shuffleModeEnabled
        // "Shuffle" turns shuffle on; a plain play keeps whatever mode the player is in.
        if (shuffled) c.shuffleModeEnabled = true
        AppLog.d("ui", "Play ${tracks.size} tracks from ${tracks.first().album.dir}, start $start, shuffle $shuffled")
        // A file check per track: for a 2600-track list that is too slow for the main thread.
        val items = withContext(Dispatchers.IO) { tracks.map { library.mediaItem(it) } }
        if (controller !== c) return@launch
        library.queueSource = tracks.map { it.id }
        c.setMediaItems(items, if (shuffled) tracks.indices.random() else start, if (shuffled) 0 else positionMs)
        c.prepare(); c.play()
        if (prefs.undoReplace.value && before != null && before.ids != tracks.map { it.id }) {
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
     * Without the current track it is stale, e.g. from before an update, and the queue itself is used.
     */
    private fun source() = library.queueSource.takeIf { currentId in it } ?: queueIds

    /** With shuffle on, [source] in play order, so a filter change keeps the order; see [setQueueFilter]. */
    private var shuffledSource = listOf<String>()

    private var filterJob: Job? = null

    /**
     * Keeps only the tracks of [source] that pass [f]; the current track plays on either way. A quick
     * second tap cancels the first one while it still prepares the tracks: both would edit the queue.
     */
    fun setQueueFilter(f: LikeFilter) {
        prefs.queueFilter.set(f.name)
        filterJob?.cancel()
        filterJob = scope.launch { applyQueueFilter() }
    }

    private suspend fun applyQueueFilter() {
        val c = controller ?: return
        val cat = app.catalog.value ?: return
        if (c.mediaItemCount == 0) return
        val cur = c.currentMediaItemIndex
        val src = source()
        library.queueSource = src
        val at = src.indexOf(c.currentMediaItem?.mediaId).takeIf { it >= 0 }
        fun items(ids: List<String>) = ids.mapNotNull { cat.byId[it] }.filter { passes(it) }.map { library.mediaItem(it) }
        val (before, after) = withContext(Dispatchers.IO) {
            if (at == null) emptyList<androidx.media3.common.MediaItem>() to items(src)
            else items(src.take(at)) to items(src.drop(at + 1))
        }
        if (controller !== c || c.currentMediaItemIndex != cur) return
        AppLog.d("ui", "Queue filter ${queueFilter.name}: ${before.size + after.size + 1} of ${src.size} tracks")
        // With shuffle on, the same play order as before; tracks the filter left out before go at random places after the current one.
        val order = if (!c.shuffleModeEnabled) null else {
            val inSrc = src.toSet()
            val sh = keepOrder(playOrder.map { queueIds[it] }, shuffledSource.filter { it in inSrc }).toMutableList()
            val from = sh.indexOf(currentId) + 1
            (src - sh.toSet()).forEach { sh.add((from..sh.size).random(), it) }
            shuffledSource = sh
            val indices = (before + after).withIndex().groupBy({ it.value.mediaId }, { if (it.index < before.size) it.index else it.index + 1 })
                .mapValues { it.value.toMutableList() }.toMutableMap()
            indices[currentId ?: ""] = mutableListOf(before.size)
            sh.mapNotNull { indices[it]?.removeFirstOrNull() }.takeIf { it.size == before.size + after.size + 1 }
        }
        // The current track is not touched, so it doesn't stop.
        c.removeMediaItems(cur + 1, c.mediaItemCount)
        c.removeMediaItems(0, cur)
        c.addMediaItems(0, before)
        c.addMediaItems(after)
        if (order != null) c.sendCustomCommand(SHUFFLE_ORDER, Bundle().apply {
            putIntArray("order", order.toIntArray())
            putInt("ids", (before.map { it.mediaId } + c.currentMediaItem?.mediaId + after.map { it.mediaId }).hashCode())
        })
        // A new shuffle order with the current track first; added tracks would otherwise go in order.
        else if (c.shuffleModeEnabled) { c.shuffleModeEnabled = false; c.shuffleModeEnabled = true }
    }

    /**
     * [all] with the ids of [kept] in kept's order; every other id stays right before the id that came
     * after it in [all]. Ids of [kept] that are not in [all] are added.
     */
    private fun keepOrder(kept: List<String>, all: List<String>): List<String> {
        val left = kept.groupingBy { it }.eachCount().toMutableMap()
        val groups = HashMap<String, ArrayDeque<List<String>>>()
        var group = mutableListOf<String>()
        for (id in all) {
            if ((left[id] ?: 0) > 0) {
                left[id] = left[id]!! - 1
                groups.getOrPut(id) { ArrayDeque() }.addLast(group)
                group = mutableListOf()
            } else group.add(id)
        }
        return kept.flatMap { (groups[it]?.removeFirstOrNull() ?: emptyList()) + it } + group
    }

    /** With shuffle on, the moved track plays right after the track above it at [to], see [moveInOrder]. */
    fun moveInQueue(from: Int, to: Int) {
        val id = queueIds.getOrNull(from) ?: return
        queueIds = queueIds.toMutableList().apply { add(to, removeAt(from)) }
        // The filter keeps the move: the track stays after the track it was put after, in either order.
        fun moved(ids: List<String>) = ids.toMutableList().apply {
            remove(id)
            add(queueIds.getOrNull(to - 1)?.let { indexOf(it) + 1 } ?: 0, id)
        }
        library.queueSource = moved(source())
        if (shuffle && shuffledSource.isNotEmpty()) shuffledSource = moved(shuffledSource)
        // The current track moves too when the moved one passes it, which with shuffle on is often.
        currentIndex = when (currentIndex) {
            from -> to
            in from + 1..to -> currentIndex - 1
            in to until from -> currentIndex + 1
            else -> currentIndex
        }
        if (shuffle && playOrder.size == queueIds.size) {
            playOrder = moveInOrder(playOrder, from, to)
            movedOrder = queueIds to playOrder
        }
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

    // ---------- likes and downloads ----------

    /** Lite streaming: light Opus copies instead of the originals, for a slow network. The queue switches at once. */
    fun toggleLite() {
        val on = !prefs.liteStreaming.value
        prefs.liteStreaming.set(on)
        controller?.sendCustomCommand(LITE, Bundle.EMPTY)
        toast(if (on) "Lite streaming: Opus 128 kbps" else "Original quality")
    }

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

    fun deleteFromBucket(tracks: List<Track>) = scope.launch {
        toast("Deleting…")
        runCatching { withContext(Dispatchers.IO) { app.deleteFromBucket(tracks) } }
            .onSuccess { n -> version++; toast(if (n == 1) "Deleted 1 track" else "Deleted $n tracks") }
            .onFailure { AppLog.e("delete", "Delete from bucket failed", it); toast("Couldn't delete: ${it.message}") }
    }

    fun saveQueueAsPlaylist() {
        val cat = app.catalog.value ?: return
        val tracks = queueIds.mapNotNull { cat.byId[it] }
        if (tracks.isEmpty()) return
        val first = tracks[currentIndex.coerceIn(0, tracks.size - 1)]
        createPlaylist("Queue: ${first.album.artist}", tracks)
        toast("Saved as playlist")
    }

    private var playlistSync: Job? = null

    /** 2 s after the last edit, so that dragging a track uploads once and not on every step; unsent edits stay dirty. */
    private fun syncPlaylists() {
        playlistSync?.cancel()
        playlistSync = scope.launch { delay(2000); syncInBackground { app.playlists.sync(app.b2) } }
    }

    /** Imports Musicolet-style .m3u files; tracks are matched by file name and folder, then by tags. */
    fun importM3u(uris: List<Uri>) = scope.launch {
        val cat = app.catalog.value ?: return@launch
        val result = withContext(Dispatchers.IO) {
            val m3u = M3uMatcher(cat)
            uris.map { uri ->
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }?.substringBeforeLast('.') ?: "Imported"
                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                val (ids, missed) = m3u.match(text)
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
        status = try {
            app.refreshCatalog()
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
            app.syncLikes()
            app.ratings.sync(app.b2)
            app.playlists.sync(app.b2)
            app.history.sync(app.b2)
            runCatching { app.lastFm.flush() }
            AppLog.i("sync", "Likes, ratings, playlists and history synced in ${System.currentTimeMillis() - syncStarted} ms")
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
