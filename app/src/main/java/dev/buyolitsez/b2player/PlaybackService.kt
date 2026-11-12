package dev.buyolitsez.b2player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val app get() = application as App
    private val prefetchExecutor = Executors.newSingleThreadExecutor()
    private var prefetching = listOf<CacheWriter>()
    private var saveJob: Job? = null
    private var lastId: String? = null

    override fun onCreate() {
        super.onCreate()
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
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && lastId != null && lastId == app.stopAfterId.value) {
                    player.pause(); app.stopAfterId.value = null
                }
                lastId = item?.mediaId
                prefetch(player)
            }
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) {
                    saveQueue(player)
                }
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) prefetch(player)
            }
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
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
        restoreQueue(player)
    }

    private fun restoreQueue(player: ExoPlayer) = app.scope.launch {
        val q = app.library.currentQueue ?: return@launch
        val catalog = app.catalog.filterNotNull().first()
        val tracks = q.ids.mapNotNull { catalog.byId[it] }
        if (tracks.isEmpty()) return@launch
        val items = tracks.map { app.library.mediaItem(it) }
        withContext(Dispatchers.Main) {
            if (player.mediaItemCount > 0) return@withContext
            player.setMediaItems(items, q.index.coerceIn(0, items.size - 1), q.positionMs)
            player.prepare()
        }
    }

    /** Debounced: remembers the queue and position so it survives app restarts. */
    private fun saveQueue(player: Player) {
        saveJob?.cancel()
        saveJob = app.scope.launch(Dispatchers.Main) {
            delay(500)
            val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            val q = SavedQueue("", ids, player.currentMediaItemIndex, player.currentPosition)
            withContext(Dispatchers.IO) { app.library.currentQueue = if (ids.isEmpty()) null else q }
        }
    }

    /** Downloads the next tracks of the queue into the cache, so a weak network does not stall playback. */
    private fun prefetch(player: Player) {
        prefetching.forEach { it.cancel() }
        val tl = player.currentTimeline
        if (tl.isEmpty) return
        val uris = ArrayList<android.net.Uri>()
        var i = player.currentMediaItemIndex
        repeat(PREFETCH) {
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) return@repeat
            player.getMediaItemAt(i).localConfiguration?.uri?.takeIf { it.scheme == "b2" }?.let { uris.add(it) }
        }
        val writers = uris.distinct().map { uri -> CacheWriter(app.cachedB2Factory.createDataSource(), DataSpec(uri), null, null) }
        prefetching = writers
        if (writers.isNotEmpty()) prefetchExecutor.execute { writers.forEach { runCatching { it.cache() } } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        prefetching.forEach { it.cancel() }
        prefetchExecutor.shutdown()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object { const val PREFETCH = 3 }
}
