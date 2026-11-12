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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

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
    val skipSilence = Bool("skipSilence", false)
    val restoreQueue = Bool("restoreQueue", true)
    val undoReplace = Bool("undoReplace", true)
    // Downloads
    val autoDownloadLiked = Bool("autoDownloadLiked", true)
    val autoWifiOnly = Bool("autoWifiOnly", true)
    val deleteOnUnlike = Bool("deleteOnUnlike", true)
    val manualWifiOnly = Bool("manualWifiOnly", false)
    // Library
    val albumGrid = Bool("albumGrid", true)
    val albumSortRecent = Bool("albumSortRecent", false)
    /** "Recently added" shows albums added in this many days; 0 shows all. */
    val recentDays = Num("recentDays", 0)
    val gridColumns = Num("gridColumns", 3)
    val startTab = Str("startTab", "Albums")
    val swipeActions = Bool("swipeActions", true)
    val coverTint = Bool("coverTint", true)
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

    val offlineOnly = MutableStateFlow(p.getBoolean("offlineOnly", false))
    fun setOfflineOnly(v: Boolean) { offlineOnly.value = v; p.edit().putBoolean("offlineOnly", v).apply() }
}

@UnstableApi
class App : Application() {
    lateinit var b2: B2
    lateinit var library: Library
    lateinit var prefs: Prefs
    lateinit var playlists: Playlists
    lateinit var history: History
    lateinit var lastFm: LastFm
    lateinit var audioFx: AudioFx
    lateinit var lyrics: LyricsRepo
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
        history = History(this)
        lastFm = LastFm(this)
        audioFx = AudioFx(this)
        lyrics = LyricsRepo(this)
        cache = SimpleCache(
            File(cacheDir, "media"), LeastRecentlyUsedCacheEvictor(prefs.cacheGb.value.toLong() shl 30),
            StandaloneDatabaseProvider(this),
        )
        library = Library(this, cache)
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
