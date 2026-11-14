package dev.buyolitsez.b2player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
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
    private var switchedAt = 0L   // track change not yet audible, for the "Sound after" log line

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
                val tracks = app.sleepAfterTracks.value
                if (finished && tracks != null) {
                    app.sleepAfterTracks.value = if (tracks > 0) tracks - 1 else null
                    if (tracks == 0) { player.pause(); AppLog.i("player", "Sleep timer: tracks finished") }
                }
                val album = app.sleepAfterAlbum.value
                if (album != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && track(item)?.album?.id != album) {
                    player.pause(); app.sleepAfterAlbum.value = null
                    AppLog.i("player", "Sleep timer: album finished")
                }
                AppLog.d("player", "Now ${item?.mediaId} (reason $reason)")
                lastId = item?.mediaId
                updateButtons()
                switchedAt = if (player.isPlaying) 0 else System.currentTimeMillis()
                listenedMs = 0; counted = false; startedAt = System.currentTimeMillis()
                if (player.isPlaying) { sendNowPlaying(item); prefetch(player) }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && listenedMs == 0L) { startedAt = System.currentTimeMillis(); sendNowPlaying(player.currentMediaItem) }
                // Only once the current track plays, so preloading never competes with its start.
                if (isPlaying) prefetch(player)
                if (isPlaying && switchedAt > 0) { AppLog.d("player", "Sound after ${System.currentTimeMillis() - switchedAt} ms"); switchedAt = 0 }
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
                if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) updateButtons()
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) {
                    saveQueue(player)
                }
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) && p.isPlaying) prefetch(player)
            }
        })
        // A tap on the notification opens Now playing.
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.OPEN_NOW_PLAYING, true)
            // Without these the running app is only brought to the front and never sees the extra.
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // A new queue or shuffle turned on: the current track goes first in the shuffle order.
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun setMediaItems(items: MutableList<MediaItem>) { super.setMediaItems(items); shuffleFromCurrent(player) }
            override fun setMediaItems(items: MutableList<MediaItem>, resetPosition: Boolean) {
                super.setMediaItems(items, resetPosition); shuffleFromCurrent(player)
            }
            override fun setMediaItems(items: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
                super.setMediaItems(items, startIndex, startPositionMs); shuffleFromCurrent(player)
            }
            override fun setShuffleModeEnabled(enabled: Boolean) {
                if (enabled) shuffleFromCurrent(player)
                super.setShuffleModeEnabled(enabled)
            }
            // With shuffle on, ExoPlayer puts added or moved tracks at random places in the play order.
            // "Play next" must play them next, and "Add to queue" must play them last.
            override fun addMediaItems(index: Int, items: MutableList<MediaItem>) {
                val next = index == player.currentMediaItemIndex + 1
                val last = index >= player.mediaItemCount
                super.addMediaItems(index, items)
                if (next || last) placeInShuffle(player, index, items.size, next)
            }
            override fun addMediaItems(items: MutableList<MediaItem>) {
                val at = player.mediaItemCount
                super.addMediaItems(items)
                placeInShuffle(player, at, items.size, next = false)
            }
            override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
                super.moveMediaItem(currentIndex, newIndex)
                if (newIndex == player.currentMediaItemIndex + 1) placeInShuffle(player, newIndex, 1, next = true)
            }
        }
        session = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(open)
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).setDataSourceFactory(app.dataSourceFactory).build()))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(s: MediaSession, c: MediaSession.ControllerInfo) =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(LIKE).add(SHUFFLE).build())
                        .build()
                override fun onCustomCommand(
                    s: MediaSession, c: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (command == LIKE) track(player.currentMediaItem)?.let { app.setLiked(listOf(it), !app.library.isLiked(it)) }
                    if (command == SHUFFLE) player.shuffleModeEnabled = !player.shuffleModeEnabled
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
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
        setMediaNotificationProvider(KeepForegroundProvider())
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) app.audioFx.attach(player.audioSessionId)
        if (app.prefs.restoreQueue.value) restoreQueue(player)
        listenJob = trackListening(player)
        // Likes made in the app, and the catalog arriving after the session, redraw the heart too.
        likeJob = app.scope.launch(Dispatchers.Main) { combine(app.likesChanged, app.catalog) { _, _ -> }.collect { updateButtons() } }
    }

    private var likeJob: Job? = null

    /**
     * Shuffle and the heart around the playback buttons in the media controls: the system puts one
     * extra button on each side, so pause stays in the middle. The heart is drawn smaller.
     */
    private fun updateButtons() {
        val s = session ?: return
        val t = track(s.player.currentMediaItem)
        s.setMediaButtonPreferences(if (t == null) ImmutableList.of() else {
            val liked = app.library.isLiked(t)
            val shuffle = s.player.shuffleModeEnabled
            ImmutableList.of(
                CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                    .setDisplayName(if (shuffle) "Shuffle off" else "Shuffle").setSessionCommand(SHUFFLE).build(),
                CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setCustomIconResId(if (liked) R.drawable.ic_heart_small else R.drawable.ic_heart_small_outline)
                    .setDisplayName(if (liked) "Unlike" else "Like").setSessionCommand(LIKE).build(),
            )
        })
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
                1 -> if (player.isPlaying) player.pause() else player.resume()
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
            // Not prepared here: the service is recreated on every app start, and preparing would load
            // the track (and its neighbours) each time. Play prepares it (MediaSession does that).
            player.setMediaItems(items, q.index.coerceIn(0, items.size - 1), q.positionMs)
            shuffleFromCurrent(player)
        }
    }

    /** Shuffle order that starts with the current track, the rest in random order. */
    private fun shuffleFromCurrent(player: ExoPlayer) {
        val n = player.mediaItemCount
        if (n == 0) return
        val first = player.currentMediaItemIndex.coerceIn(0, n - 1)
        val order = intArrayOf(first) + (0 until n).filter { it != first }.shuffled().toIntArray()
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
    }

    /** With shuffle on: puts queue items [from, from + count) right after the current track in the play order, or last. */
    private fun placeInShuffle(player: ExoPlayer, from: Int, count: Int, next: Boolean) {
        if (!player.shuffleModeEnabled || count == 0) return
        val tl = player.currentTimeline
        val order = generateSequence(tl.getFirstWindowIndex(true).takeIf { it != C.INDEX_UNSET }) {
            tl.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, true).takeIf { i -> i != C.INDEX_UNSET }
        }.toList()
        if (order.size != player.mediaItemCount) return
        val added = (from until from + count).toList()
        val rest = order.filter { it !in added }
        val at = if (next) rest.indexOf(player.currentMediaItemIndex) + 1 else rest.size
        val newOrder = rest.take(at) + added + rest.drop(at)
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(newOrder.toIntArray(), System.nanoTime()))
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
            // The next track whole; later ones only their first minute or so, enough to start at once.
            specs.add(prefetchSpec(uri, track(item)).let { if (n == 0) it else it.subrange(0, minOf(it.length.takeIf { l -> l > 0 } ?: HEAD, HEAD)) })
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

    private companion object {
        const val HEAD = 8L shl 20
        val LIKE = SessionCommand("dev.buyolitsez.b2player.LIKE", Bundle.EMPTY)
        val SHUFFLE = SessionCommand("dev.buyolitsez.b2player.SHUFFLE", Bundle.EMPTY)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    // Stay in the foreground while paused, so the notification stays in the shade. Media3 leaves the
    // foreground 10 min after a pause, and soon after that the system stops the service and the
    // notification goes with it. Swiping the app away from recents still stops a paused player.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired || session.player.mediaItemCount > 0)
    }

    // A cover that loads after the notification was built comes back through Media3's own callback,
    // which leaves the foreground once the pause is over 10 min old. While paused, rebuild through
    // onUpdateNotification instead: the cover is cached by then.
    private inner class KeepForegroundProvider : MediaNotification.Provider {
        private val default = DefaultMediaNotificationProvider(this@PlaybackService)
        override fun createNotification(
            session: MediaSession, buttons: ImmutableList<CommandButton>,
            actions: MediaNotification.ActionFactory, callback: MediaNotification.Provider.Callback,
        ) = default.createNotification(session, buttons, actions) { n ->
            if (session.player.playWhenReady) callback.onNotificationChanged(n) else onUpdateNotification(session, true)
        }
        override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle) =
            default.handleCustomCommand(session, action, extras)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        listenJob?.cancel()
        likeJob?.cancel()
        app.audioFx.release()
        prefetching.forEach { it.cancel() }
        prefetchExecutor.shutdown()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

}
