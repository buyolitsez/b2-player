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
import java.io.File

/** User preferences that are not B2 credentials. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    var cacheGb: Int
        get() = p.getInt("cacheGb", 4)
        set(v) = p.edit().putInt("cacheGb", v).apply()
    var autoDownloadLiked: Boolean
        get() = p.getBoolean("autoDownloadLiked", true)
        set(v) = p.edit().putBoolean("autoDownloadLiked", v).apply()
    var albumGrid: Boolean
        get() = p.getBoolean("albumGrid", true)
        set(v) = p.edit().putBoolean("albumGrid", v).apply()
    var albumSortRecent: Boolean
        get() = p.getBoolean("albumSortRecent", false)
        set(v) = p.edit().putBoolean("albumSortRecent", v).apply()
    val offlineOnly = MutableStateFlow(p.getBoolean("offlineOnly", false))
    fun setOfflineOnly(v: Boolean) { offlineOnly.value = v; p.edit().putBoolean("offlineOnly", v).apply() }
}

@UnstableApi
class App : Application() {
    lateinit var b2: B2
    lateinit var library: Library
    lateinit var prefs: Prefs
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
        cache = SimpleCache(
            File(cacheDir, "media"), LeastRecentlyUsedCacheEvictor(prefs.cacheGb.toLong() shl 30),
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
