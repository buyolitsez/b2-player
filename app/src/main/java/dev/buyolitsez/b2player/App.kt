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
    val gridColumns = Num("gridColumns", 3)
    val startTab = Str("startTab", "Albums")
    val swipeActions = Bool("swipeActions", true)
    val coverTint = Bool("coverTint", true)
    // History
    val recordHistory = Bool("recordHistory", true)
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
    lateinit var cache: SimpleCache
    lateinit var dataSourceFactory: DataSource.Factory
    /** Cache-backed b2:// source, also used to prefetch upcoming tracks. */
    lateinit var cachedB2Factory: CacheDataSource.Factory

    val catalog = MutableStateFlow<Catalog?>(null)
    /** Pause when the track with this id ends ("stop after this track"). */
    val stopAfterId = MutableStateFlow<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        b2 = B2(this)
        playlists = Playlists(this)
        history = History(this)
        lastFm = LastFm(this)
        cache = SimpleCache(
            File(cacheDir, "media"), LeastRecentlyUsedCacheEvictor(prefs.cacheGb.value.toLong() shl 30),
            StandaloneDatabaseProvider(this),
        )
        library = Library(this, cache)
        // b2://bucket/<key> -> authorized HTTPS request. The cache key is the b2:// URI, so it
        // stays stable although the auth token changes.
        val upstream = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()) { spec ->
            if (spec.uri.scheme != "b2") return@Factory spec
            val (url, token) = b2.request(spec.uri.path!!.removePrefix("/"))
            spec.buildUpon().setUri(url).setHttpRequestHeaders(mapOf("Authorization" to token)).build()
        }
        cachedB2Factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val local = DefaultDataSource.Factory(this)
        dataSourceFactory = DataSource.Factory { RoutingDataSource(local.createDataSource(), cachedB2Factory.createDataSource()) }
        scope.launch(Dispatchers.IO) {
            library.catalogFile.takeIf { it.exists() }?.let { f ->
                runCatching { Catalog.parse(f.readBytes()) }.getOrNull()?.let { if (catalog.value == null) catalog.value = it }
            }
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
