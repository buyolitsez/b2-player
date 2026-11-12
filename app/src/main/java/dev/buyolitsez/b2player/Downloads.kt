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
                try { app.b2.download(Library.MUSIC + p, dst) } catch (e: Exception) { failed++ }
            }
        }
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
    private val mem = LruCache<String, Bitmap>(120)
    private val dir = File(app.cacheDir, "covers")

    fun cached(key: String): Bitmap? = mem.get(key)

    suspend fun load(key: String): Bitmap? = mem.get(key) ?: withContext(Dispatchers.IO) {
        val f = File(dir, key.substringAfterLast('/'))
        try {
            if (!f.exists()) app.b2.download(key, f)
            BitmapFactory.decodeFile(f.path)?.also { mem.put(key, it) }
        } catch (e: Exception) { null }
    }
}
