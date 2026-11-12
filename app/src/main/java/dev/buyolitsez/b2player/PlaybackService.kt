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

    // Listening time of the current track, for history and Last.fm.
    private var listenedMs = 0L
    private var startedAt = 0L
    private var counted = false
    private var lastHistorySync = 0L
    private var listenJob: Job? = null

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
        player.skipSilenceEnabled = app.prefs.skipSilence.value
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && lastId != null && lastId == app.stopAfterId.value) {
                    player.pause(); app.stopAfterId.value = null
                }
                lastId = item?.mediaId
                listenedMs = 0; counted = false; startedAt = System.currentTimeMillis()
                if (player.isPlaying) sendNowPlaying(item)
                prefetch(player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && listenedMs == 0L) { startedAt = System.currentTimeMillis(); sendNowPlaying(player.currentMediaItem) }
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
        if (app.prefs.restoreQueue.value) restoreQueue(player)
        listenJob = trackListening(player)
    }

    private fun track(item: MediaItem?) = item?.mediaId?.let { app.catalog.value?.byId?.get(it) }

    private fun sendNowPlaying(item: MediaItem?) {
        val t = track(item) ?: return
        app.scope.launch(Dispatchers.IO) { runCatching { app.lastFm.nowPlaying(t) } }
    }

    /** Counts a play once half the track or 4 minutes were heard (Last.fm rules; tracks under 30 s don't count). */
    private fun trackListening(player: ExoPlayer) = app.scope.launch(Dispatchers.Main) {
        while (true) {
            delay(1000)
            if (!player.isPlaying) continue
            player.skipSilenceEnabled = app.prefs.skipSilence.value
            listenedMs += 1000
            val t = track(player.currentMediaItem) ?: continue
            if (counted || t.duration < 30 || listenedMs < minOf(t.duration * 500, 240_000.0)) continue
            counted = true
            val at = startedAt
            app.scope.launch(Dispatchers.IO) {
                if (app.prefs.recordHistory.value) app.history.record(t.id, at)
                app.lastFm.enqueue(t, at)
                runCatching { app.lastFm.flush() }
                if (System.currentTimeMillis() - lastHistorySync > 10 * 60_000) {
                    lastHistorySync = System.currentTimeMillis()
                    runCatching { app.history.sync(app.b2) }
                }
            }
        }
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
        for (n in 0 until app.prefs.prefetchCount.value) {
            if (i == C.INDEX_UNSET) break
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) break
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
        listenJob?.cancel()
        prefetching.forEach { it.cancel() }
        prefetchExecutor.shutdown()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

}
