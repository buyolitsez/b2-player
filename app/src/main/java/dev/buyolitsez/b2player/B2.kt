package dev.buyolitsez.b2player

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Read-only access to the bucket through the native B2 API (one authorize call, then plain GETs). */
class B2(private val context: Context) {
    data class Settings(val keyId: String, val appKey: String, val bucket: String) {
        val complete get() = keyId.isNotBlank() && appKey.isNotBlank() && bucket.isNotBlank()
    }

    private class Auth(val downloadUrl: String, val token: String, val at: Long)

    private val prefs = context.getSharedPreferences("b2", Context.MODE_PRIVATE)
    @Volatile private var auth: Auth? = null

    var settings: Settings
        get() = Settings(
            prefs.getString("keyId", "")!!, prefs.getString("appKey", "")!!,
            prefs.getString("bucket", DEFAULT_BUCKET)!!,
        )
        set(v) {
            prefs.edit().putString("keyId", v.keyId.trim()).putString("appKey", v.appKey.trim())
                .putString("bucket", v.bucket.trim()).apply()
            auth = null
        }

    @Synchronized
    private fun auth(): Auth {
        auth?.let { if (System.currentTimeMillis() - it.at < 20 * 3600_000L) return it }
        val s = settings
        if (!s.complete) throw IOException("B2 key is not set")
        val c = URL("https://api.backblazeb2.com/b2api/v2/b2_authorize_account").openConnection() as HttpURLConnection
        val basic = Base64.encodeToString("${s.keyId}:${s.appKey}".toByteArray(), Base64.NO_WRAP)
        c.setRequestProperty("Authorization", "Basic $basic")
        c.connectTimeout = 15000; c.readTimeout = 15000
        if (c.responseCode != 200) {
            val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
            throw IOException("B2 authorize failed: HTTP ${c.responseCode} $err")
        }
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        return Auth(j.getString("downloadUrl"), j.getString("authorizationToken"), System.currentTimeMillis())
            .also { auth = it }
    }

    fun invalidate() { auth = null }

    /** HTTPS URL and Authorization header value for an object in the bucket. */
    fun request(key: String): Pair<String, String> {
        val a = auth()
        return "${a.downloadUrl}/file/${settings.bucket}/${Uri.encode(key, "/")}" to a.token
    }

    fun open(key: String): HttpURLConnection {
        repeat(2) { attempt ->
            val (url, token) = request(key)
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", token)
            c.connectTimeout = 15000; c.readTimeout = 30000
            when (c.responseCode) {
                200 -> return c
                401 -> if (attempt == 0) { invalidate(); return@repeat }
            }
            throw IOException("GET $key: HTTP ${c.responseCode}")
        }
        throw IOException("GET $key: unauthorized")
    }

    fun download(key: String, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".part")
        open(key).inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(dst)) throw IOException("rename failed: $dst")
    }

    companion object { const val DEFAULT_BUCKET = "buyolitsez-music" }
}
