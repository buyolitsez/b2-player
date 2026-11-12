package dev.buyolitsez.b2player

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Access to the bucket through the native B2 API: one authorize call, then plain GETs (and small uploads). */
class B2(private val context: Context) {
    data class Settings(val keyId: String, val appKey: String, val bucket: String) {
        val complete get() = keyId.isNotBlank() && appKey.isNotBlank() && bucket.isNotBlank()
    }

    private class Auth(
        val apiUrl: String, val downloadUrl: String, val token: String, val accountId: String,
        val bucketId: String?, val at: Long,
    )

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
            AppLog.e("b2", "Authorize failed: HTTP ${c.responseCode}")
            throw IOException("B2 authorize failed: HTTP ${c.responseCode} $err")
        }
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        val allowed = j.optJSONObject("allowed")
        val bucketId = allowed?.optString("bucketId")?.takeIf { it.isNotEmpty() && it != "null" }
        return Auth(
            j.getString("apiUrl"), j.getString("downloadUrl"), j.getString("authorizationToken"),
            j.getString("accountId"), bucketId, System.currentTimeMillis(),
        ).also { auth = it }
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
            AppLog.w("b2", "GET $key: HTTP ${c.responseCode}")
            throw IOException("GET $key: HTTP ${c.responseCode}")
        }
        throw IOException("GET $key: unauthorized")
    }

    private fun post(url: String, token: String, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", token)
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) {
            throw IOException("POST $url: HTTP ${c.responseCode} ${c.errorStream?.bufferedReader()?.readText().orEmpty()}")
        }
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    private fun bucketId(a: Auth): String = a.bucketId ?: post(
        "${a.apiUrl}/b2api/v2/b2_list_buckets", a.token,
        JSONObject().put("accountId", a.accountId).put("bucketName", settings.bucket),
    ).getJSONArray("buckets").getJSONObject(0).getString("bucketId")

    /** Uploads a small file (needs a key with writeFiles) and returns its fileId. */
    fun upload(key: String, bytes: ByteArray, contentType: String): String {
        val a = auth()
        val up = post("${a.apiUrl}/b2api/v2/b2_get_upload_url", a.token, JSONObject().put("bucketId", bucketId(a)))
        val c = URL(up.getString("uploadUrl")).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", up.getString("authorizationToken"))
        c.setRequestProperty("X-Bz-File-Name", Uri.encode(key, "/"))
        c.setRequestProperty("Content-Type", contentType)
        c.setRequestProperty("X-Bz-Content-Sha1", MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) })
        c.setFixedLengthStreamingMode(bytes.size)
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.outputStream.use { it.write(bytes) }
        if (c.responseCode != 200) throw IOException("upload $key: HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().readText()).getString("fileId")
    }

    /** Removes one stored version of a file (the bucket keeps old versions otherwise). */
    fun deleteVersion(key: String, fileId: String) {
        val a = auth()
        post("${a.apiUrl}/b2api/v2/b2_delete_file_version", a.token, JSONObject().put("fileName", key).put("fileId", fileId))
    }

    /** Small object as bytes, or null when it does not exist. */
    fun fetchOrNull(key: String): ByteArray? {
        val (url, token) = request(key)
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", token)
        c.connectTimeout = 15000; c.readTimeout = 30000
        return when (c.responseCode) {
            200 -> c.inputStream.use { it.readBytes() }
            404 -> null
            else -> throw IOException("GET $key: HTTP ${c.responseCode}")
        }
    }

    fun download(key: String, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".part")
        open(key).inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(dst)) throw IOException("rename failed: $dst")
    }

    companion object { const val DEFAULT_BUCKET = "buyolitsez-music" }
}
