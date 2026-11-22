package dev.buyolitsez.b2player

import android.app.Application
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** User preferences that are not B2 credentials. Compose-observable, so settings rows update live. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    inner class Bool(private val key: String, def: Boolean) {
        var value by mutableStateOf(p.getBoolean(key, def))
        fun set(v: Boolean) { value = v; p.edit().putBoolean(key, v).apply() }
    }
    inner class Num(private val key: String, def: Int) {
        var value by mutableIntStateOf(p.getInt(key, def))
        fun set(v: Int) { value = v; p.edit().putInt(key, v).apply() }
    }
    inner class Str(private val key: String, def: String) {
        var value by mutableStateOf(p.getString(key, def)!!)
        fun set(v: String) { value = v; p.edit().putString(key, v).apply() }
    }

    // Playback
    val prefetchCount = Num("prefetchCount", 3)
    val restoreQueue = Bool("restoreQueue", true)
    val undoReplace = Bool("undoReplace", false)
    /** A [LikeFilter] name. */
    val queueFilter = Str("queueFilter", "All")
    /** A [LikeFilter] name for the library, see [Ui.libraryFilter]. */
    val libraryFilter = Str("libraryFilter", "All")
    /** Stream the light Opus copies of tracks that have them (the button in Now playing). */
    val liteStreaming = Bool("liteStreaming", false)
    // Downloads
    val autoDownloadLiked = Bool("autoDownloadLiked", true)
    val autoWifiOnly = Bool("autoWifiOnly", true)
    val deleteOnUnlike = Bool("deleteOnUnlike", true)
    val manualWifiOnly = Bool("manualWifiOnly", false)
    /** Kept when the app is removed; see [Library.offlineDir]. */
    val downloadDir = Str("downloadDir", File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "B2 Player").path)
    /** The all-files access screen was opened once on start. */
    val askedStorage = Bool("askedStorage", false)
    val askedBattery = Bool("askedBattery", false)
    // Library
    val albumGrid = Bool("albumGrid", true)
    /** An artist opens on the list of all their tracks instead of their albums. */
    val artistTracks = Bool("artistTracks", false)
    /** An [AlbumSort] name. */
    val albumSort = Str("albumSort", "Artist")
    /** A [TrackSort] name. */
    val trackSort = Str("trackSort", "Artist")
    /** "By rating" shows albums rated at least this; 0 shows all, -1 only the unrated ones. */
    val minRating = Num("minRating", 0)
    /** "Recently added" and "Recently liked" show what came in this many days; 0 shows all. */
    val recentDays = Num("recentDays", 0)
    val gridColumns = Num("gridColumns", 3)
    /** A [Tab] name; older names of library sections or player tabs open the library. */
    val startTab = Str("startTab", "Library")
    /** The [LibrarySection] shown in the Library tab, kept from the last visit. */
    val librarySection = Str("librarySection", "Albums")
    val swipeActions = Bool("swipeActions", true)
    val coverTint = Bool("coverTint", true)
    /** Address of cmus-b2-add --serve on the computer, over Tailscale. */
    val soulseekServer = Str("soulseekServer", "arch:5031")
    // History
    val recordHistory = Bool("recordHistory", true)
    // Lyrics, controls, diagnostics
    val onlineLyrics = Bool("onlineLyrics", true)
    val headsetGestures = Bool("headsetGestures", true)
    val verboseLogs = Bool("verboseLogs", false)
    val uploadLogs = Bool("uploadLogs", true)
    var lastLogUpload: Long
        get() = p.getLong("lastLogUpload", 0)
        set(v) = p.edit().putLong("lastLogUpload", v).apply()
    /** "key fileId" of the last uploaded log version, deleted when the same day's log is uploaded again. */
    var lastLogFile: String
        get() = p.getString("lastLogFile", "")!!
        set(v) = p.edit().putString("lastLogFile", v).apply()
    // Storage
    val cacheGb = Num("cacheGb", 4)
}

@UnstableApi
class App : Application() {
    lateinit var b2: B2
    lateinit var library: Library
    lateinit var prefs: Prefs
    lateinit var playlists: Playlists
    lateinit var ratings: Ratings
    lateinit var history: History
    lateinit var handoffs: Handoffs
    lateinit var lastFm: LastFm
    lateinit var audioFx: AudioFx
    lateinit var lyrics: LyricsRepo
    lateinit var updates: Updates
    lateinit var covers: Covers
    lateinit var cache: SimpleCache
    lateinit var dataSourceFactory: DataSource.Factory
    /** Cache-backed b2:// source, also used to prefetch upcoming tracks. */
    lateinit var cachedB2Factory: CacheDataSource.Factory

    val catalog = MutableStateFlow<Catalog?>(null)
    /** Reads the catalog saved by the last launch so the library shows before the network answers. */
    lateinit var cachedCatalogLoad: kotlinx.coroutines.Job
    /** Pause when the track with this id ends ("stop after this track"). */
    val stopAfterId = MutableStateFlow<String?>(null)
    /** Sleep timer: pause at this time (epoch ms), or when the album changes after the one in [sleepAfterAlbum],
     *  or after this many more tracks finish past the current one (0 = when the current one ends). */
    val sleepAt = MutableStateFlow<Long?>(null)
    val sleepAfterAlbum = MutableStateFlow<String?>(null)
    val sleepAfterTracks = MutableStateFlow<Int?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Bumped on every like or unlike, from the app or the media notification, and on ratings from elsewhere. */
    val likesChanged = MutableStateFlow(0)

    /**
     * Syncs likes with the bucket; likes made in cmus re-read the lists and download or delete those tracks.
     * Returns whether likes changed on tracks this catalog doesn't have: the bucket has a newer one. Renames on the
     * computer change the likes before the phone has the new paths and has moved its downloads to them, so the old
     * ids look unliked then; their downloads are kept for [applyRenames] to move.
     */
    fun syncLikes(): Boolean {
        val ids = library.syncLikes(b2)
        if (ids.isEmpty()) return false
        likesChanged.update { it + 1 }
        val cat = catalog.value ?: return false
        val (liked, unliked) = cat.tracks.filter { it.id in ids }.partition { library.isLiked(it) }
        val stale = ids.any { it !in cat.byId }
        followLikes(liked, true)
        if (!stale) followLikes(unliked, false)
        return stale
    }

    /**
     * Liked tracks are kept on the phone: downloads that failed for good or were lost get another go once per
     * library load. Not while downloads run or wait: two workers on one file would write the same part file.
     */
    fun downloadMissingLiked() {
        if (!prefs.autoDownloadLiked.value) return
        val cat = catalog.value ?: return
        if (WorkManager.getInstance(this).getWorkInfosByTag(DownloadWorker.TAG).get().any { !it.state.isFinished }) return
        val downloaded = library.downloadedCheck()
        val missing = cat.tracks.filter { library.isLiked(it) && !downloaded(it) }
        if (missing.isEmpty()) return
        AppLog.i("download", "${missing.size} liked tracks aren't on the phone")
        DownloadWorker.enqueue(this, "liked-missing", "${missing.size} liked tracks", missing, wifiOnly = prefs.autoWifiOnly.value)
    }

    /**
     * Folders and files renamed on the computer are listed in state/renames.tsv as "old<TAB>new" paths, and the
     * bucket's likes, playlists and history already have the new ids: the local copies of those, the queues and the
     * downloads get them here, before they sync. Runs the lines not applied yet.
     */
    @Synchronized fun applyRenames(): Boolean {
        val lines = b2.fetchOrNull("state/renames.tsv")?.decodeToString()?.lines().orEmpty().filter { '\t' in it }
        if (lines.size <= library.renamesSeen) return false
        val pairs = lines.drop(library.renamesSeen).map { it.substringBefore('\t') to it.substringAfter('\t') }
        fun moved(id: String): String {
            for ((old, new) in pairs) if (id == old || id.startsWith("$old/") || id.startsWith("$old#")) return new + id.substring(old.length)
            return id
        }
        library.rename(::moved, pairs)
        playlists.rename(::moved)
        history.rename(::moved)
        library.renamesSeen = lines.size
        AppLog.i("rename", "Applied ${pairs.size} renames")
        return true
    }

    /** Syncs album ratings with the bucket; ratings from cmus or the diary re-read the lists. */
    fun syncRatings() { if (ratings.sync(b2)) likesChanged.update { it + 1 } }

    /** Likes or unlikes tracks: downloads or deletes them as set, syncs likes and mirrors them to Last.fm. */
    fun setLiked(tracks: List<Track>, liked: Boolean) {
        val changed = tracks.filter { library.isLiked(it) != liked }
        if (changed.isEmpty()) return
        val now = System.currentTimeMillis()
        changed.forEach { library.setLiked(it, liked, now) }
        followLikes(changed, liked)
        likesChanged.value++
        scope.launch(Dispatchers.IO) { runCatching { syncLikes() }.onFailure { AppLog.w("sync", "Likes sync failed", it) } }
        if (lastFm.connected) scope.launch(Dispatchers.IO) {
            changed.forEach { t -> runCatching { lastFm.love(t, liked) }.onFailure { AppLog.w("lastfm", "Love failed: ${t.title}", it) } }
        }
    }

    /** Downloads newly liked tracks or deletes unliked ones, as set in Settings. */
    private fun followLikes(changed: List<Track>, liked: Boolean) {
        if (changed.isEmpty()) return
        if (liked && prefs.autoDownloadLiked.value) {
            val one = changed.singleOrNull()
            DownloadWorker.enqueue(this, one?.let { "like:${it.id}" } ?: "likes:${System.currentTimeMillis()}",
                one?.title ?: "${changed.size} liked tracks", changed, wifiOnly = prefs.autoWifiOnly.value)
        }
        if (!liked) changed.forEach { DownloadWorker.cancel(this, "like:${it.id}", listOf(it)) }
        if (!liked && prefs.deleteOnUnlike.value) {
            // CUE tracks share a file: keep it while another of its tracks is still liked.
            changed.filter { t -> t.album.tracks.none { it.path == t.path && library.isLiked(it) } }
                .distinctBy { it.path }.forEach { library.deleteDownload(it) }
        }
    }

    /** Loads the bucket's catalog, unless a HEAD request shows the saved one is the same: most launches skip the download. */
    suspend fun refreshCatalog() {
        val started = System.currentTimeMillis()
        val sha = withContext(Dispatchers.IO) { b2.open("catalog.json.gz", "HEAD").getHeaderField("x-bz-content-sha1") }
        if (sha != null && sha == library.catalogSha && library.catalogFile.exists()) {
            cachedCatalogLoad.join()
            AppLog.i("catalog", "Unchanged, checked in ${System.currentTimeMillis() - started} ms")
        } else {
            val bytes = withContext(Dispatchers.IO) { b2.open("catalog.json.gz").inputStream.use { it.readBytes() } }
            catalog.value = withContext(Dispatchers.Default) { Catalog.parse(bytes) }
            withContext(Dispatchers.IO) { library.catalogFile.writeBytes(bytes); library.catalogSha = sha }
            AppLog.i("catalog", "Loaded ${catalog.value?.albums?.size} albums, ${bytes.size / 1024} KB, ${System.currentTimeMillis() - started} ms")
        }
    }

    /**
     * Deletes the files of [tracks] from the bucket, with the other tracks of a CUE file and, when
     * nothing is left of an album's folder, its other files. They are hidden (the bucket's lifecycle
     * rule removes them after 30 days), dropped from the catalog and listed in state/deleted.tsv,
     * from which cmus-b2 on the computer trashes their local copies. Returns the number of tracks deleted.
     */
    fun deleteFromBucket(tracks: List<Track>): Int {
        val paths = tracks.map { it.path }.toSet()
        // the published catalog, not the loaded one, so that nothing published since is lost
        val json = JSONObject(GZIPInputStream(b2.open("catalog.json.gz").inputStream).use { it.readBytes().decodeToString() })
        val prefix = json.optString("musicPrefix", "music/")
        val albums = json.getJSONArray("albums")
        val kept = JSONArray()
        val dirs = HashSet<String>(); val left = HashSet<String>()
        var deleted = 0
        for (i in 0 until albums.length()) {
            val al = albums.getJSONObject(i)
            val all = al.getJSONArray("tracks")
            val keep = JSONArray()
            for (j in 0 until all.length()) {
                val path = all.getJSONObject(j).getString("path")
                if (path in paths) { dirs += al.getString("dir"); deleted++ } else { keep.put(all.getJSONObject(j)); left += path }
            }
            if (keep.length() > 0) kept.put(al.put("tracks", keep))
        }
        json.put("albums", kept)
        val hide = paths.toMutableSet()
        for (d in dirs) if (left.none { it.startsWith("$d/") }) hide += b2.list("$prefix$d/").map { it.removePrefix(prefix) }
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(json.toString().toByteArray()) }
        }.toByteArray()
        // the catalog goes first: hidden files that it still lists would not play
        b2.upload("catalog.json.gz", bytes, "application/gzip")
        hide.forEach { b2.hide(prefix + it) }
        catalog.value?.tracks?.filter { it.path in paths && it.liteSize > 0 }?.distinctBy { it.path }
            ?.forEach { b2.hide("${Library.LITE}${it.path}.opus") }
        val listed = b2.fetchOrNull("state/deleted.tsv")?.decodeToString().orEmpty()
        b2.upload("state/deleted.tsv", (listed + hide.joinToString("") { "$it\n" }).toByteArray(), "text/tab-separated-values")
        library.catalogFile.writeBytes(bytes)
        library.catalogSha = sha1Hex(bytes)
        catalog.value?.tracks?.filter { it.path in paths }?.forEach { library.deleteDownload(it) }
        catalog.value = Catalog.parse(bytes)
        return deleted
    }

    /**
     * Copies the log to logs/<date>.log in the bucket when there were problems since the last
     * upload (or when forced). Only the newest version of each day's file is kept.
     */
    fun uploadLogs(force: Boolean = false) = scope.launch(Dispatchers.IO) {
        if (!b2.settings.complete || (!force && (!prefs.uploadLogs.value || !AppLog.hasNewProblems))) return@launch
        if (!force && System.currentTimeMillis() - prefs.lastLogUpload < 10 * 60_000) return@launch
        try {
            val key = "logs/" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".log"
            val text = AppLog.text().takeLast(1_000_000)
            AppLog.hasNewProblems = false
            val id = b2.upload(key, text.toByteArray(), "text/plain")
            val (oldKey, oldId) = prefs.lastLogFile.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            if (oldKey == key && oldId.isNotEmpty() && oldId != id) runCatching { b2.deleteVersion(oldKey, oldId) }
            prefs.lastLogFile = "$key $id"
            prefs.lastLogUpload = System.currentTimeMillis()
        } catch (e: Exception) {
            AppLog.hasNewProblems = true
            android.util.Log.w("B2P/log", "Log upload failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        AppLog.init(this, prefs.verboseLogs.value)
        b2 = B2(this)
        playlists = Playlists(this)
        ratings = Ratings(this)
        history = History(this)
        handoffs = Handoffs(this)
        lastFm = LastFm(this)
        audioFx = AudioFx(this)
        lyrics = LyricsRepo(this)
        updates = Updates(this)
        // A newer release opens the update dialog once per start of the app.
        scope.launch(Dispatchers.Main) {
            runCatching { updates.check() }.onSuccess { updates.dialog = it.isNotEmpty() }.onFailure { AppLog.w("update", "Update check failed", it) }
        }
        cache = SimpleCache(
            File(cacheDir, "media"), LeastRecentlyUsedCacheEvictor(prefs.cacheGb.value.toLong() shl 30),
            StandaloneDatabaseProvider(this),
        )
        library = Library(this, cache, prefs)
        covers = Covers(this)
        // b2://bucket/<key> -> authorized HTTPS request. The cache key is the b2:// URI, so it
        // stays stable although the auth token changes.
        val upstream = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory().setTransferListener(NetTimings)) { spec ->
            if (spec.uri.scheme != "b2") return@Factory spec
            val (url, token) = b2.request(spec.uri.path!!.removePrefix("/"))
            spec.buildUpon().setUri(url).setHttpRequestHeaders(mapOf("Authorization" to token)).build()
        }
        cachedB2Factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val local = DefaultDataSource.Factory(this)
        dataSourceFactory = DataSource.Factory { RoutingDataSource(local.createDataSource(), cachedB2Factory.createDataSource()) }
        // Search keys (accent folding of 18k titles) are built here, not on the first keystroke.
        scope.launch(Dispatchers.Default) {
            catalog.collect { c -> c?.run { albums.forEach { it.searchKey }; tracks.forEach { it.searchKey }; library.dropStaleCache(this) } }
        }
        scope.launch {
            combine(catalog.filterNotNull(), likesChanged) { c, _ -> c }.collectLatest {
                covers.fetchLiked(it)
                withContext(Dispatchers.IO) { lyrics.fetchLiked(it) }
            }
        }
        scope.launch(Dispatchers.IO) {
            catalog.collect { c -> c?.let { runCatching { library.moveDownloads(it) }.onFailure { AppLog.w("download", "Moving downloads failed", it) } } }
        }
        cachedCatalogLoad = scope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            library.catalogFile.takeIf { it.exists() }?.let { f ->
                runCatching { Catalog.parse(f.readBytes()) }.getOrNull()?.let { if (catalog.value == null) catalog.value = it }
            }
            AppLog.i("catalog", "Saved catalog read in ${System.currentTimeMillis() - started} ms")
        }
    }
}

/** Sends b2:// URIs through the cache, everything else (offline files) straight to disk. */
@UnstableApi
private class RoutingDataSource(private val local: DataSource, private val remote: DataSource) : DataSource {
    private var current: DataSource? = null
    override fun addTransferListener(l: TransferListener) { local.addTransferListener(l); remote.addTransferListener(l) }
    override fun open(dataSpec: DataSpec): Long =
        (if (dataSpec.uri.scheme == "b2") remote else local).also { current = it }.open(dataSpec)
    override fun read(buffer: ByteArray, offset: Int, length: Int) = current!!.read(buffer, offset, length)
    override fun getUri() = current?.uri
    override fun getResponseHeaders() = current?.responseHeaders ?: emptyMap()
    override fun close() { current?.close(); current = null }
}

/** Detailed logs only: how long each B2 request takes to answer and how much it reads. */
@UnstableApi
private object NetTimings : androidx.media3.datasource.TransferListener {
    private val started = java.util.concurrent.ConcurrentHashMap<androidx.media3.datasource.DataSource, Long>()
    private val opened = java.util.concurrent.ConcurrentHashMap<androidx.media3.datasource.DataSource, Long>()
    private val bytes = java.util.concurrent.ConcurrentHashMap<androidx.media3.datasource.DataSource, Long>()
    override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, spec: DataSpec, isNetwork: Boolean) {
        started[source] = System.nanoTime()
    }
    override fun onTransferStart(source: androidx.media3.datasource.DataSource, spec: DataSpec, isNetwork: Boolean) {
        val t = System.nanoTime(); opened[source] = t; bytes[source] = 0
        val ms = (t - (started[source] ?: t)) / 1_000_000
        AppLog.d("net", "Open ${spec.uri.lastPathSegment} @${spec.position}+${if (spec.length < 0) "end" else spec.length}: $ms ms")
    }
    override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, spec: DataSpec, isNetwork: Boolean, n: Int) {
        bytes.merge(source, n.toLong(), Long::plus)
    }
    override fun onTransferEnd(source: androidx.media3.datasource.DataSource, spec: DataSpec, isNetwork: Boolean) {
        val ms = (System.nanoTime() - (opened.remove(source) ?: return)) / 1_000_000
        AppLog.d("net", "Closed ${spec.uri.lastPathSegment}: ${(bytes.remove(source) ?: 0) / 1024} KB in $ms ms")
        started.remove(source)
    }
}
