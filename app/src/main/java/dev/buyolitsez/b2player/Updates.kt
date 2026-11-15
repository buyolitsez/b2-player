package dev.buyolitsez.b2player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Updates from the GitHub releases that CI publishes on every push to main; each release's notes are its commit title. */
class Updates(private val app: App) {
    class Release(val version: String, val notes: String, val apk: String, val size: Long)

    val installed: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    /** Releases newer than the installed version, newest first. */
    var newer by mutableStateOf(listOf<Release>())
    var dialog by mutableStateOf(false)
    /** Download progress from 0 to 1 while the update downloads. */
    var progress by mutableStateOf<Float?>(null)
    /** Why the last attempt didn't install, or what to do first. */
    var note by mutableStateOf<String?>(null)
    /** The install session with the downloaded update, waiting for a tap on Install. */
    var ready by mutableStateOf<Int?>(null)
    private val notifications = NotificationManagerCompat.from(app)

    init {
        notifications.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName("App updates").build())
        // Downloads from an earlier run of the app that were never installed.
        app.packageManager.packageInstaller.mySessions.forEach { runCatching { app.packageManager.packageInstaller.abandonSession(it.sessionId) } }
    }

    suspend fun check(): List<Release> {
        val found = withContext(Dispatchers.IO) {
            val c = URL("https://api.github.com/repos/buyolitsez/b2-player/releases?per_page=30").openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000
            if (c.responseCode != 200) throw IOException("GitHub HTTP ${c.responseCode}")
            val releases = JSONArray(c.inputStream.bufferedReader().readText())
            (0 until releases.length()).map { releases.getJSONObject(it) }.mapNotNull { r ->
                val assets = r.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
                if (apk == null || r.optBoolean("draft") || r.optBoolean("prerelease")) null
                else Release(r.getString("tag_name").removePrefix("v"), r.optString("body"), apk.getString("browser_download_url"), apk.getLong("size"))
            }.filter { isNewer(it.version, installed) }
        }
        newer = found
        return found
    }

    /**
     * Streams the newest APK into a system install session in a worker: it goes on in the background, with its progress
     * in a notification, and after a lost network it continues from where it stopped.
     */
    fun download() {
        val r = newer.firstOrNull() ?: return
        // Asking for the permission from the install session drops the session, so it is asked before the download.
        if (!app.packageManager.canRequestPackageInstalls()) {
            note = "Allow installs from B2 Player, then tap Update again"
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (progress != null) return
        dialog = false; progress = 0f; note = null; ready = null; hidden = false
        WorkManager.getInstance(app).enqueueUniqueWork(
            "update", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateWorker>()
                .setInputData(workDataOf("version" to r.version, "apk" to r.apk, "size" to r.size))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build(),
        )
    }

    /** The install session being written and how many bytes it has, kept between attempts of the download. */
    private var partial: Pair<Int, Long>? = null
    /** The progress notification was swiped away: it isn't shown again during this download. */
    @Volatile var hidden = false

    suspend fun download(w: UpdateWorker): ListenableWorker.Result {
        val version = w.inputData.getString("version").orEmpty()
        val apk = w.inputData.getString("apk").orEmpty()
        val size = w.inputData.getLong("size", 0)
        val installer = app.packageManager.packageInstaller
        if (progress == null) progress = 0f
        if (!hidden) runCatching {
            w.setForeground(ForegroundInfo(PROGRESS, progressNotification(version, progress ?: 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        }.onFailure { AppLog.w("update", "No foreground service", it) }
        return try {
            AppLog.i("update", "Downloading $version from ${partial?.second ?: 0}, attempt ${w.runAttemptCount + 1}")
            val id = partial?.first ?: installer.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(app.packageName) }
            )
            var done = partial?.second ?: 0L
            partial = id to done
            installer.openSession(id).use { s ->
                val c = URL(apk).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 30000
                if (done > 0) c.setRequestProperty("Range", "bytes=$done-")
                when (c.responseCode) {
                    206 -> {}
                    200 -> done = 0
                    else -> throw IOException("Download HTTP ${c.responseCode}")
                }
                s.openWrite("base.apk", done, size - done).use { out ->
                    c.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        var shown = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); done += n
                            partial = id to done
                            val p = (done * 100 / size) / 100f
                            if (p != progress) progress = p
                            // The system drops notification updates that come too often.
                            val now = System.currentTimeMillis()
                            if (!hidden && now - shown > 1000) { shown = now; notify(app, PROGRESS, progressNotification(version, p)) }
                        }
                    }
                    s.fsync(out)
                }
            }
            if (done != size) throw IOException("Got $done of $size bytes")
            AppLog.i("update", "Downloaded $version")
            partial = null; progress = null
            ready = id
            show(NotificationCompat.Builder(app, CHANNEL).setOnlyAlertOnce(true)
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("B2 Player $version is ready")
                .setContentText("Tap to install").setContentIntent(openApp(true)).setAutoCancel(true))
            ListenableWorker.Result.success()
        } catch (e: Exception) {
            AppLog.w("update", "Update download failed", e)
            // A lost network: the worker runs again once it is back.
            if (w.runAttemptCount < 5) return ListenableWorker.Result.retry()
            partial?.let { runCatching { installer.abandonSession(it.first) } }
            partial = null; progress = null
            fail("Update failed: ${e.message}")
            ListenableWorker.Result.failure()
        }
    }

    /** Hands the downloaded update to the system, which asks to confirm the install. */
    fun install() {
        val id = ready ?: return
        ready = null
        notifications.cancel(NOTIFICATION)
        runCatching {
            app.packageManager.packageInstaller.openSession(id).use { s ->
                val done = Intent(app, UpdateReceiver::class.java)
                s.commit(PendingIntent.getBroadcast(app, 0, done, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
            }
        }.onFailure { AppLog.w("update", "Install failed", it); fail("Install failed: ${it.message}") }
    }

    fun fail(message: String) {
        note = message
        show(NotificationCompat.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(message).setContentIntent(openApp(false)).setAutoCancel(true))
    }

    private fun progressNotification(version: String, progress: Float) = NotificationCompat.Builder(app, CHANNEL).setOnlyAlertOnce(true)
        .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Downloading B2 Player $version")
        .setProgress(100, (progress * 100).toInt(), false).setOngoing(true).setContentIntent(openApp(false))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).setDeleteIntent(dismissIntent(app, PROGRESS))
        .build()

    private fun show(b: NotificationCompat.Builder) = notify(app, NOTIFICATION, b.build())

    // The install starts from the app: the system's confirmation can't be opened from the background.
    private fun openApp(install: Boolean) = PendingIntent.getActivity(
        app, if (install) 1 else 0, Intent(app, MainActivity::class.java).putExtra(INSTALL, install), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    companion object {
        const val INSTALL = "installUpdate"
        private const val CHANNEL = "updates"
        // The progress has its own notification: WorkManager removes it when the download ends.
        private const val NOTIFICATION = 2
        private const val PROGRESS = 4
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) { (applicationContext as App).updates.download(this@UpdateWorker) }
}

/** Gets the install session's result: shows the system's confirmation, or the error. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            AppLog.w("update", "Install failed: $status $msg")
            (context.applicationContext as App).updates.fail("Install failed: $msg")
        }
    }
}

/** What changed in the releases since the installed one, and the button that installs the newest. */
@Composable
fun UpdateDialog(u: Updates) {
    val latest = u.newer.firstOrNull()
    if (!u.dialog || latest == null) return
    val p = u.progress
    AlertDialog(
        { u.dialog = false }, containerColor = Gray2, title = { Text("Update to ${latest.version}") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Installed: ${u.installed}. What's new:", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (p != null) LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                u.note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                u.newer.forEach { Text("• ${it.notes}", style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            when {
                u.ready != null -> TextButton({ u.dialog = false; u.install() }) { Text("Install") }
                p != null -> TextButton({ u.dialog = false }) { Text("Downloading ${(p * 100).toInt()}%") }
                else -> TextButton({ u.download() }) { Text("Update, ${formatSize(latest.size)}") }
            }
        },
        dismissButton = { TextButton({ u.dialog = false }) { Text("Later") } },
    )
}
