package dev.buyolitsez.b2player

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Downloads whole files for offline listening into Library.offlineDir, as a foreground service with the progress
 * in a notification, so Android doesn't stop it in the background.
 */
@UnstableApi
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as App
        val paths = inputData.getStringArray("paths").orEmpty().distinct()
        val title = inputData.getString("title").orEmpty()
        val first = inputData.getInt("first", 0); val all = inputData.getInt("all", paths.size)
        val failed = mutableListOf<String>()
        _progress.update { m -> m + paths.filter { it !in m && !app.library.offlineFile(it).exists() }.associateWith { 0f } }
        // Android doesn't let a worker that starts in the background (a like synced from the computer) become a
        // foreground service: then it runs as a plain job, without the notification.
        val foreground = !hidden && runCatching {
            setForeground(ForegroundInfo(NOTIFICATION, notification(title, first, all, 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        }.onFailure { AppLog.w("download", "No foreground service", it) }.isSuccess
        var shown = 0L
        try {
            paths.forEachIndexed { i, p ->
                setProgress(workDataOf("title" to title, "done" to first + i, "total" to all))
                val dst = app.library.offlineFile(p)
                if (!dst.exists() && !isStopped) {
                    try {
                        // Steps of 10 %: each step copies the map of every queued path and the rings animate between steps.
                        if (!app.library.copyFromCache(p, dst)) app.b2.download(Library.MUSIC + p, dst, resume = true) { f ->
                            // A cancelled worker must stop writing: a new one may continue the same part file.
                            if (isStopped) throw IOException("Stopped")
                            val pct = (f * 10).toInt() / 10f
                            if (_progress.value[p] != pct) _progress.update { it + (p to pct) }
                            // The system drops notification updates that come too often.
                            val now = System.currentTimeMillis()
                            if (foreground && !hidden && now - shown > 1000) {
                                shown = now; notify(applicationContext, NOTIFICATION, notification(title, first + i, all, f))
                            }
                        }
                    } catch (e: Exception) { if (!isStopped) failed += p; AppLog.w("download", "Failed: $p", e) }
                }
                _progress.update { it - p }
            }
        } finally {
            _progress.update { it - paths.toSet() }
        }
        AppLog.i("download", "$title: ${paths.size - failed.size} of ${paths.size} done, attempt ${runAttemptCount + 1}")
        val retry = failed.isNotEmpty() && runAttemptCount < 3
        if (failed.isNotEmpty() && !retry) notifyFailed(title, failed)
        // A swiped notification stays away also while the work waits for the network to come back.
        if (!retry && _progress.value.isEmpty()) hidden = false
        if (retry) Result.retry() else Result.success()
    }

    /** The last attempt left tracks undownloaded: says so, with Retry, instead of leaving them silently. */
    private fun notifyFailed(title: String, paths: List<String>) {
        val ctx = applicationContext
        val retry = PendingIntent.getBroadcast(
            ctx, FAILED, Intent(ctx, RetryReceiver::class.java).putExtra("title", title).putExtra("paths", paths.toTypedArray()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Couldn't download $title")
            .setContentText(if (paths.size == 1) "1 track failed" else "${paths.size} tracks failed")
            .setContentIntent(PendingIntent.getActivity(ctx, FAILED, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Retry", retry).setAutoCancel(true)
            .build()
        notify(ctx, FAILED, n)
    }

    private fun notification(title: String, done: Int, all: Int, share: Float): Notification {
        val ctx = applicationContext
        NotificationManagerCompat.from(ctx).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName("Downloads").build()
        )
        return NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $title").setContentText(if (all > 1) "${done + 1} of $all" else null)
            .setProgress(1000, ((done + share) / all * 1000).toInt(), false)
            .setOngoing(true).setOnlyAlertOnce(true).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(ctx, NOTIFICATION, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setDeleteIntent(dismissIntent(ctx, NOTIFICATION))
            .build()
    }

    companion object {
        const val TAG = "download"
        private const val CHANNEL = "downloads"
        const val NOTIFICATION = 3
        const val FAILED = 4

        /** The notification was swiped away: it isn't shown again until the downloads in progress end. */
        @Volatile var hidden = false

        private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
        /** Track files waiting for or being downloaded, by path, with the share done (0..1). */
        val progress: StateFlow<Map<String, Float>> = _progress

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            _progress.value = emptyMap()
            hidden = false
        }

        /** Drops a download that hasn't finished, e.g. the one a like started when the track is unliked. */
        fun cancel(context: Context, name: String, tracks: List<Track>) {
            WorkManager.getInstance(context).cancelUniqueWork("dl:$name")
            _progress.update { it - tracks.map { t -> t.path }.toSet() }
        }

        /**
         * [wifiOnly] is for automatic downloads (liked tracks); manual ones use any network. Tracks the player has
         * cached whole are copied from the cache, so these need no network at all.
         */
        fun enqueue(context: Context, name: String, title: String, tracks: List<Track>, wifiOnly: Boolean = false) {
            val library = (context.applicationContext as App).library
            val network = when {
                tracks.all { library.isCached(it) } -> NetworkType.NOT_REQUIRED
                wifiOnly -> NetworkType.UNMETERED
                else -> NetworkType.CONNECTED
            }
            // Work input is limited to 10 KB, so a long list goes as a chain of parts that run one after another.
            val paths = tracks.map { it.path }.distinct()
            val parts = mutableListOf(mutableListOf<String>()); var bytes = 0
            for (p in paths) {
                val n = p.toByteArray().size + 8
                if (bytes + n > 8000 && parts.last().isNotEmpty()) { parts += mutableListOf<String>(); bytes = 0 }
                parts.last() += p; bytes += n
            }
            var first = 0
            val reqs = parts.map { part ->
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(workDataOf("paths" to part.toTypedArray(), "title" to title, "first" to first, "all" to paths.size))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                    .addTag(TAG)
                    .build()
                    .also { first += part.size }
            }
            reqs.drop(1).fold(WorkManager.getInstance(context).beginUniqueWork("dl:$name", ExistingWorkPolicy.KEEP, reqs[0])) { c, r -> c.then(r) }.enqueue()
            // The buttons turn into rings right away, also while the work waits for a network.
            _progress.update { m -> m + tracks.map { it.path }.filter { it !in m }.associateWith { 0f } }
        }
    }
}

fun notify(context: Context, id: Int, n: Notification) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

fun dismissIntent(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
    context, id, Intent(context, DismissReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

/** Hears that a download notification was swiped away, so that it isn't posted again. */
@UnstableApi
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra("id", 0) == DownloadWorker.NOTIFICATION) DownloadWorker.hidden = true
        else (context.applicationContext as App).updates.hidden = true
    }
}

/** Retry on the "Couldn't download" notification: downloads the tracks that failed again. */
@UnstableApi
class RetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationManagerCompat.from(context).cancel(DownloadWorker.FAILED)
        val paths = intent.getStringArrayExtra("paths").orEmpty().toSet()
        val title = intent.getStringExtra("title").orEmpty()
        val tracks = (context.applicationContext as App).catalog.value?.tracks?.filter { it.path in paths }.orEmpty()
        if (tracks.isNotEmpty()) DownloadWorker.enqueue(context, "retry:$title", title, tracks)
    }
}

/** Album thumbnails: memory LRU on top of a disk copy in cacheDir/covers. */
@UnstableApi
class Covers(private val app: App) {
    // Sized in bytes: ~80 thumbnails at 512 px in RGB_565, instead of 120 in ARGB (~120 MB).
    private val mem = object : LruCache<String, Bitmap>(40 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val decode = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    // Rows in lists show covers at ~44 dp: a quarter-size copy (128 px) decodes faster and many more of them fit in memory.
    private val decodeSmall = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = 4 }

    /** Drops the decoded thumbnails, e.g. when the app goes to the background. */
    fun trim() = mem.evictAll()
    private val dir = File(app.cacheDir, "covers")

    private fun memKey(key: String, small: Boolean) = if (small) "small:$key" else key

    fun cached(key: String, small: Boolean = false): Bitmap? = mem.get(memKey(key, small))

    suspend fun load(key: String, small: Boolean = false): Bitmap? = cached(key, small) ?: withContext(Dispatchers.IO) {
        val f = File(dir, key.substringAfterLast('/'))
        try {
            if (!f.exists()) app.b2.download(key, f)
            BitmapFactory.decodeFile(f.path, if (small) decodeSmall else decode)?.also { mem.put(memKey(key, small), it) }
        } catch (e: Exception) { null }
    }
}
