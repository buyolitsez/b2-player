package dev.buyolitsez.b2player

import android.content.Context
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
    Queue("Очередь"), Now("Играет"), Folders("Папки"), Albums("Альбомы"), Artists("Исполнители"),
    Liked("Любимые"), Search("Поиск")
}

sealed interface Screen {
    data object Root : Screen
    data class AlbumView(val album: Album) : Screen
    data class ArtistView(val name: String) : Screen
    data class FolderView(val path: String) : Screen
    data object Downloads : Screen
    data object Settings : Screen
}

/** All UI state and actions, shared by the composables. */
@UnstableApi
class Ui(val context: Context, val app: App, val scope: CoroutineScope) {
    val covers = Covers(app)
    val snackbar = SnackbarHostState()
    val library get() = app.library

    var tab by mutableStateOf(Tab.Albums)
    val stacks = mutableStateMapOf<Tab, List<Screen>>()
    var status by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    /** Bumped when likes or downloads change, so rows re-read them. */
    var version by mutableIntStateOf(0)
    var colors by mutableStateOf(DefaultColors)

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

    val offlineOnly get() = app.prefs.offlineOnly.value
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
        if (list.isEmpty()) { toast("Нет треков, доступных офлайн"); return }
        val start = tracks.getOrNull(index)?.let { list.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val before = snapshot()
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(list.map { library.mediaItem(it) }, if (shuffled) list.indices.random() else start, 0)
        c.prepare(); c.play()
        if (before != null && before.ids != list.map { it.id }) {
            scope.launch {
                if (snackbar.showSnackbar("Очередь заменена", "Отменить", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    restore(before, play = true)
                }
            }
        }
    }

    fun playNext(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(tracks, 0); return }
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.filter { visible(it) }.map { library.mediaItem(it) })
        toast(if (tracks.size == 1) "Следующим: ${tracks[0].title}" else "Следующими: ${tracks.size} тр.")
    }

    fun addToEnd(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(tracks, 0); return }
        c.addMediaItems(tracks.filter { visible(it) }.map { library.mediaItem(it) })
        toast(if (tracks.size == 1) "В конец очереди: ${tracks[0].title}" else "В конец очереди: ${tracks.size} тр.")
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

    fun putQueueAside() {
        val q = snapshot() ?: return
        val cat = app.catalog.value
        val first = cat?.byId?.get(q.ids[q.index.coerceIn(0, q.ids.size - 1)])
        val name = first?.let { "${it.album.artist} — ${it.album.title}" } ?: "Очередь"
        library.savedQueues = listOf(q.copy(name = name)) + library.savedQueues.take(9)
        version++
        toast("Очередь отложена")
    }

    fun resumeSaved(i: Int) {
        val list = library.savedQueues.toMutableList()
        val q = list.removeAt(i)
        snapshot()?.let { cur -> list.add(0, cur.copy(name = "Прошлая очередь")) }
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
        toast(if (app.stopAfterId.value != null) "Остановлю после «${t.title}»" else "Без остановки")
    }

    // ---------- likes and downloads ----------

    fun toggleLike(t: Track) {
        val liked = !library.isLiked(t)
        library.setLiked(t, liked)
        if (app.prefs.autoDownloadLiked) {
            if (liked) DownloadWorker.enqueue(context, "like:${t.id}", t.title, listOf(t), wifiOnly = true)
            else library.deleteDownload(t)
        }
        version++
        scope.launch {
            val err = withContext(Dispatchers.IO) { runCatching { library.syncLikes(app.b2) }.exceptionOrNull() }
            if (err != null) status = "Лайки не синхронизировались: ${err.message}"
        }
    }

    fun download(name: String, title: String, tracks: List<Track>) {
        val todo = tracks.filter { !library.isDownloaded(it) }
        if (todo.isEmpty()) { toast("Уже скачано"); return }
        DownloadWorker.enqueue(context, name, title, todo)
        toast("Скачиваю: ${todo.size} тр., ${formatSize(todo.sumOf { it.size })}")
    }

    fun deleteDownloads(tracks: List<Track>) {
        scope.launch(Dispatchers.IO) { tracks.forEach { library.deleteDownload(it) } }.invokeOnCompletion {
            scope.launch { version++ }
        }
    }

    fun toast(s: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(s, duration = SnackbarDuration.Short) } }

    // ---------- catalog ----------

    fun refreshCatalog() = scope.launch {
        if (!app.b2.settings.complete) return@launch
        status = "Обновляю каталог…"
        status = try {
            val bytes = withContext(Dispatchers.IO) { app.b2.open("catalog.json.gz").inputStream.use { it.readBytes() } }
            app.catalog.value = withContext(Dispatchers.Default) { Catalog.parse(bytes) }
            withContext(Dispatchers.IO) {
                library.catalogFile.writeBytes(bytes)
                library.syncLikes(app.b2)
            }
            version++
            null
        } catch (e: Exception) {
            "Каталог не загрузился: ${e.message}"
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%d МБ".format(bytes shr 20)
    else -> "%d КБ".format(bytes shr 10)
}

fun formatTime(sec: Double): String {
    val s = sec.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
