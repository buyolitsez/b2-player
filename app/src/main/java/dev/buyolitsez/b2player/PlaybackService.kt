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
    private var bufferingSince = 0L
    private var retriedAuth = false

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
            override fun onAudioSessionIdChanged(audioSessionId: Int) { app.audioFx.attach(audioSessionId) }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                if (finished && lastId != null && lastId == app.stopAfterId.value) {
                    player.pause(); app.stopAfterId.value = null
                    AppLog.i("player", "Stopped after $lastId as asked")
                }
                val album = app.sleepAfterAlbum.value
                if (album != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && track(item)?.album?.id != album) {
                    player.pause(); app.sleepAfterAlbum.value = null
                    AppLog.i("player", "Sleep timer: album finished")
                }
                AppLog.d("player", "Now ${item?.mediaId} (reason $reason)")
                lastId = item?.mediaId
                listenedMs = 0; counted = false; startedAt = System.currentTimeMillis()
                if (player.isPlaying) sendNowPlaying(item)
                prefetch(player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && listenedMs == 0L) { startedAt = System.currentTimeMillis(); sendNowPlaying(player.currentMediaItem) }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                AppLog.e("player", "${error.errorCodeName} on ${player.currentMediaItem?.mediaId}", error)
                // The saved B2 token can be revoked before it expires: sign in again and retry once.
                val http = error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                if (http?.responseCode == 401 && !retriedAuth) {
                    retriedAuth = true; app.b2.invalidate(); player.prepare()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                val now = System.currentTimeMillis()
                if (state == Player.STATE_BUFFERING && player.playWhenReady) bufferingSince = now
                else if (bufferingSince > 0) {
                    val ms = now - bufferingSince; bufferingSince = 0
                    if (ms > 3000) AppLog.w("player", "Buffered ${ms / 1000} s on ${player.currentMediaItem?.mediaId}")
                }
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
                override fun onMediaButtonEvent(s: MediaSession, c: MediaSession.ControllerInfo, intent: Intent): Boolean {
                    if (!app.prefs.headsetGestures.value) return false
                    @Suppress("DEPRECATION")
                    val ev = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    if (ev.keyCode != android.view.KeyEvent.KEYCODE_HEADSETHOOK && ev.keyCode != android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return false
                    if (ev.action == android.view.KeyEvent.ACTION_DOWN && ev.repeatCount == 0) headsetPress(player)
                    return true
                }
                // Items from the controller arrive without their URI; restore it from request metadata.
                override fun onAddMediaItems(
                    s: MediaSession, c: MediaSession.ControllerInfo, items: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    items.map { it.buildUpon().setUri(it.requestMetadata.mediaUri).build() }.toMutableList()
                )
            })
            .build()
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) app.audioFx.attach(player.audioSessionId)
        if (app.prefs.restoreQueue.value) restoreQueue(player)
        listenJob = trackListening(player)
    }

    private var presses = 0
    private var pressJob: Job? = null

    /** One press: play/pause; two: next track; three: previous track. */
    private fun headsetPress(player: Player) {
        presses++
        pressJob?.cancel()
        pressJob = app.scope.launch(Dispatchers.Main) {
            delay(450)
            val n = presses; presses = 0
            AppLog.d("player", "Headset button x$n")
            when (n) {
                1 -> if (player.isPlaying) player.pause() else player.play()
                2 -> player.seekToNextMediaItem()
                else -> player.seekToPrevious()
            }
        }
    }

    private fun track(item: MediaItem?) = item?.mediaId?.let { app.catalog.value?.byId?.get(it) }

    private fun sendNowPlaying(item: MediaItem?) {
        val t = track(item) ?: return
        app.scope.launch(Dispatchers.IO) { runCatching { app.lastFm.nowPlaying(t) }.onFailure { AppLog.w("lastfm", "Now playing failed", it) } }
    }

    /** Counts a play once half the track or 4 minutes were heard (Last.fm rules; tracks under 30 s don't count). */
    private fun trackListening(player: ExoPlayer) = app.scope.launch(Dispatchers.Main) {
        var tick = 0
        while (true) {
            delay(1000)
            app.sleepAt.value?.let { at ->
                val left = at - System.currentTimeMillis()
                when {
                    left <= 0 -> { player.pause(); player.volume = 1f; app.sleepAt.value = null; AppLog.i("player", "Sleep timer: paused") }
                    left < 10_000 -> player.volume = left / 10_000f   // fade out over the last 10 s
                }
            }
            if (app.sleepAt.value == null && player.volume < 1f) player.volume = 1f
            if (!player.isPlaying) continue
            retriedAuth = false
            // Keep the position fresh, so a killed process resumes close to where it was.
            if (++tick % 10 == 0) saveQueue(player)
            player.skipSilenceEnabled = app.prefs.skipSilence.value
            listenedMs += 1000
            val t = track(player.currentMediaItem) ?: continue
            if (counted || t.duration < 30 || listenedMs < minOf(t.duration * 500, 240_000.0)) continue
            counted = true
            val at = startedAt
            app.scope.launch(Dispatchers.IO) {
                if (app.prefs.recordHistory.value) app.history.record(t.id, at)
                app.lastFm.enqueue(t, at)
                runCatching { app.lastFm.flush() }.onFailure { AppLog.w("lastfm", "Scrobble failed, ${app.lastFm.pendingCount} pending", it) }
                if (System.currentTimeMillis() - lastHistorySync > 10 * 60_000) {
                    lastHistorySync = System.currentTimeMillis()
                    runCatching { app.history.sync(app.b2) }.onFailure { AppLog.w("sync", "History sync failed", it) }
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
        val specs = ArrayList<DataSpec>()
        var i = player.currentMediaItemIndex
        for (n in 0 until app.prefs.prefetchCount.value) {
            if (i == C.INDEX_UNSET) break
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) break
            val item = player.getMediaItemAt(i)
            val uri = item.localConfiguration?.uri?.takeIf { it.scheme == "b2" } ?: continue
            specs.add(prefetchSpec(uri, track(item)))
        }
        val writers = specs.distinct().map { spec -> CacheWriter(app.cachedB2Factory.createDataSource(), spec, null, null) }
        prefetching = writers
        if (writers.isNotEmpty()) prefetchExecutor.execute { writers.forEach { runCatching { it.cache() } } }
    }

    /**
     * The whole file, or for a CUE track only its share of the album image (estimated from the
     * times, with a margin), so preloading one track does not download a 200 MB album file.
     */
    private fun prefetchSpec(uri: android.net.Uri, t: Track?): DataSpec {
        val start = t?.start ?: return DataSpec(uri)
        val total = t.album.tracks.filter { it.path == t.path }.maxOf { (it.start ?: 0.0) + it.duration }
        if (total <= 0 || t.size <= 0) return DataSpec(uri)
        val margin = 1L shl 20
        val from = (t.size * (start / total)).toLong() - margin
        val length = (t.size * (t.duration / total)).toLong() + 2 * margin
        val pos = from.coerceAtLeast(0)
        return DataSpec.Builder().setUri(uri).setPosition(pos).setLength(minOf(length, t.size - pos)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        listenJob?.cancel()
        app.audioFx.release()
        prefetching.forEach { it.cancel() }
        prefetchExecutor.shutdown()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

}
