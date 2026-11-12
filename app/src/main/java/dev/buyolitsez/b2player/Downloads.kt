package dev.buyolitsez.b2player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.media3.common.util.UnstableApi
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Downloads whole files for offline listening into Library.offlineDir. */
@UnstableApi
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as App
        val paths = inputData.getStringArray("paths").orEmpty().distinct()
        val title = inputData.getString("title").orEmpty()
        var failed = 0
        paths.forEachIndexed { i, p ->
            setProgress(workDataOf("title" to title, "done" to i, "total" to paths.size))
            val dst = app.library.offlineFile(p)
            if (!dst.exists()) {
                try { app.b2.download(Library.MUSIC + p, dst) } catch (e: Exception) { failed++; AppLog.w("download", "Failed: $p", e) }
            }
        }
        AppLog.i("download", "$title: ${paths.size - failed} of ${paths.size} done, attempt ${runAttemptCount + 1}")
        if (failed > 0 && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        const val TAG = "download"

        /** [wifiOnly] is for automatic downloads (liked tracks); manual ones use any network. */
        fun enqueue(context: Context, name: String, title: String, tracks: List<Track>, wifiOnly: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("paths" to tracks.map { it.path }.distinct().toTypedArray(), "title" to title))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("dl:$name", ExistingWorkPolicy.KEEP, req)
        }
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
