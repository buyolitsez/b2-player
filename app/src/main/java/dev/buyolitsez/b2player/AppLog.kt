package dev.buyolitsez.b2player

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small rotating log in filesDir/logs: app.log, rotated to app.1.log and app.2.log at 512 KB, so it
 * never takes more than about 1.5 MB. Mirrored to logcat. Never log keys or tokens.
 */
object AppLog {
    private const val MAX = 512 * 1024L
    private lateinit var dir: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    var verbose = false
    /** Set when a warning or error was written since the last upload. */
    @Volatile var hasNewProblems = false

    fun init(context: Context, verbose: Boolean) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        this.verbose = verbose
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            e("crash", "Uncaught exception in thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        i("app", "Start ${info.versionName} (${info.longVersionCode}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }

    fun d(tag: String, msg: String) { if (verbose) write("D", tag, msg, null) }
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, e: Throwable? = null) = write("W", tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = write("E", tag, msg, e)

    @Synchronized
    private fun write(level: String, tag: String, msg: String, e: Throwable?) {
        when (level) {
            "E" -> Log.e("B2P/$tag", msg, e)
            "W" -> Log.w("B2P/$tag", msg, e)
            else -> Log.i("B2P/$tag", msg)
        }
        if (!::dir.isInitialized) return
        if (level == "W" || level == "E") hasNewProblems = true
        val f = File(dir, "app.log")
        if (f.length() > MAX) {
            File(dir, "app.1.log").let { one -> if (one.exists()) one.renameTo(File(dir, "app.2.log")) }
            f.renameTo(File(dir, "app.1.log"))
        }
        val trace = e?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString().trimEnd() }
        runCatching {
            File(dir, "app.log").appendText(
                "${fmt.format(Date())} $level/$tag: $msg" + (trace?.let { "\n" + it.prependIndent("    ") } ?: "") + "\n"
            )
        }
    }

    /** All rotated files, oldest first. */
    @Synchronized
    fun text(): String = listOf("app.2.log", "app.1.log", "app.log").map { File(dir, it) }.filter { it.exists() }
        .joinToString("") { it.readText() }

    fun clear() = synchronized(this) { dir.listFiles()?.forEach { it.delete() } }
    fun sizeBytes() = dir.listFiles()?.sumOf { it.length() } ?: 0L
}
