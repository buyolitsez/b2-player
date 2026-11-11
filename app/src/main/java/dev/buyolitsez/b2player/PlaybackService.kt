package dev.buyolitsez.b2player

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

@UnstableApi
class App : Application() {
    lateinit var b2: B2
    lateinit var library: Library
    lateinit var dataSourceFactory: DataSource.Factory

    override fun onCreate() {
        super.onCreate()
        b2 = B2(this)
        library = Library(this)
        val cache = SimpleCache(
            File(cacheDir, "media"), LeastRecentlyUsedCacheEvictor(CACHE_BYTES), StandaloneDatabaseProvider(this),
        )
        // b2://bucket/<key> -> signed HTTPS request. The cache key is the b2:// URI, so it stays
        // stable although the auth token changes.
        val b2Upstream = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()) { spec ->
            if (spec.uri.scheme != "b2") return@Factory spec
            val (url, token) = b2.request(spec.uri.path!!.removePrefix("/"))
            spec.buildUpon().setUri(url).setHttpRequestHeaders(mapOf("Authorization" to token)).build()
        }
        val b2Cached = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(b2Upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val local = DefaultDataSource.Factory(this)
        dataSourceFactory = DataSource.Factory { RoutingDataSource(local.createDataSource(), b2Cached.createDataSource()) }
    }

    companion object { const val CACHE_BYTES = 4L shl 30 }
}

/** Sends b2:// URIs through the cache, everything else (offline files) straight to disk. */
@UnstableApi
private class RoutingDataSource(private val local: DataSource, private val remote: DataSource) : DataSource {
    private var current: DataSource? = null
    override fun addTransferListener(l: androidx.media3.datasource.TransferListener) {
        local.addTransferListener(l); remote.addTransferListener(l)
    }
    override fun open(dataSpec: DataSpec): Long =
        (if (dataSpec.uri.scheme == "b2") remote else local).also { current = it }.open(dataSpec)
    override fun read(buffer: ByteArray, offset: Int, length: Int) = current!!.read(buffer, offset, length)
    override fun getUri() = current?.uri
    override fun getResponseHeaders() = current?.responseHeaders ?: emptyMap()
    override fun close() { current?.close(); current = null }
}

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(
                DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(app.dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).setDataSourceFactory(app.dataSourceFactory).build()))
            .setCallback(object : MediaSession.Callback {
                // Items from the controller arrive without their URI; restore it from request metadata.
                override fun onAddMediaItems(
                    s: MediaSession, c: MediaSession.ControllerInfo, items: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    items.map { it.buildUpon().setUri(it.requestMetadata.mediaUri).build() }.toMutableList()
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
