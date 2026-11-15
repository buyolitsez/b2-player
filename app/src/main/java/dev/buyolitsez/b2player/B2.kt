package dev.buyolitsez.b2player

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Access to the bucket through the native B2 API: one authorize call, then plain GETs (and small uploads). */
fun sha1Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

class B2(private val context: Context) {
    data class Settings(val keyId: String, val appKey: String, val bucket: String) {
        val complete get() = keyId.isNotBlank() && appKey.isNotBlank() && bucket.isNotBlank()
    }

    private class Auth(
        val apiUrl: String, val downloadUrl: String, val token: String, val accountId: String,
        val bucketId: String?, val at: Long,
    )

    private val prefs = context.getSharedPreferences("b2", Context.MODE_PRIVATE)
    // The token is kept across restarts too, so a launch does not wait for b2_authorize_account.
    @Volatile private var auth: Auth? = prefs.getString("auth", null)?.let { runCatching { JSONObject(it) }.getOrNull() }?.let {
        Auth(it.getString("apiUrl"), it.getString("downloadUrl"), it.getString("token"), it.getString("accountId"),
            it.optString("bucketId").takeIf { b -> b.isNotEmpty() }, it.getLong("at"))
    }
        set(v) {
            field = v
            prefs.edit().putString("auth", v?.let {
                JSONObject().put("apiUrl", it.apiUrl).put("downloadUrl", it.downloadUrl).put("token", it.token)
                    .put("accountId", it.accountId).put("bucketId", it.bucketId ?: "").put("at", it.at).toString()
            }).apply()
        }

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

    /** With [from] > 0 asks for the rest of the file from that byte: the answer is 206, or 200 with the whole file. */
    fun open(key: String, method: String = "GET", from: Long = 0): HttpURLConnection {
        repeat(2) { attempt ->
            val (url, token) = request(key)
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.setRequestProperty("Authorization", token)
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            c.connectTimeout = 15000; c.readTimeout = 30000
            when (c.responseCode) {
                200, 206 -> return c
                401 -> if (attempt == 0) { invalidate(); return@repeat }
            }
            AppLog.w("b2", "$method $key: HTTP ${c.responseCode}")
            throw IOException("$method $key: HTTP ${c.responseCode}")
        }
        throw IOException("GET $key: unauthorized")
    }

    private fun post(url: String, token: String, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", token)
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode == 401) throw Unauthorized("POST $url: HTTP 401")
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
    fun upload(key: String, bytes: ByteArray, contentType: String): String = reauthorizing {
        val a = auth()
        val up = post("${a.apiUrl}/b2api/v2/b2_get_upload_url", a.token, JSONObject().put("bucketId", bucketId(a)))
        val c = URL(up.getString("uploadUrl")).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", up.getString("authorizationToken"))
        c.setRequestProperty("X-Bz-File-Name", Uri.encode(key, "/"))
        c.setRequestProperty("Content-Type", contentType)
        c.setRequestProperty("X-Bz-Content-Sha1", sha1Hex(bytes))
        c.setFixedLengthStreamingMode(bytes.size)
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.outputStream.use { it.write(bytes) }
        if (c.responseCode != 200) throw IOException("upload $key: HTTP ${c.responseCode}")
        JSONObject(c.inputStream.bufferedReader().readText()).getString("fileId")
    }

    /** Removes one stored version of a file (the bucket keeps old versions otherwise). */
    fun deleteVersion(key: String, fileId: String) = reauthorizing {
        val a = auth()
        post("${a.apiUrl}/b2api/v2/b2_delete_file_version", a.token, JSONObject().put("fileName", key).put("fileId", fileId))
    }

    /** Hides a file: it is no longer listed or served, and the bucket's lifecycle rule removes it later. */
    fun hide(key: String) = reauthorizing {
        val a = auth()
        post("${a.apiUrl}/b2api/v2/b2_hide_file", a.token, JSONObject().put("bucketId", bucketId(a)).put("fileName", key))
    }

    /** Names of the files under [prefix]. */
    fun list(prefix: String): List<String> = reauthorizing {
        val a = auth()
        val names = ArrayList<String>()
        var start: String? = prefix
        while (start != null) {
            val r = post("${a.apiUrl}/b2api/v2/b2_list_file_names", a.token,
                JSONObject().put("bucketId", bucketId(a)).put("prefix", prefix).put("startFileName", start).put("maxFileCount", 1000))
            val files = r.getJSONArray("files")
            for (i in 0 until files.length()) names += files.getJSONObject(i).getString("fileName")
            start = r.optString("nextFileName").takeIf { !r.isNull("nextFileName") }
        }
        names
    }

    /** Small object as bytes, or null when it does not exist. */
    fun fetchOrNull(key: String): ByteArray? = reauthorizing {
        val (url, token) = request(key)
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", token)
        c.connectTimeout = 15000; c.readTimeout = 30000
        when (c.responseCode) {
            200 -> c.inputStream.use { it.readBytes() }
            404 -> null
            401 -> throw Unauthorized("GET $key: HTTP 401")
            else -> throw IOException("GET $key: HTTP ${c.responseCode}")
        }
    }

    /**
     * [onProgress] gets the share of the file written so far, 0..1. With [resume] a failed download keeps
     * what it got and the next call continues from there.
     */
    fun download(key: String, dst: File, resume: Boolean = false, onProgress: (Float) -> Unit = {}) {
        dst.parentFile?.mkdirs()
        // Otherwise an own temp name per call: two downloads of the same file (a cover shown twice) must not collide.
        val tmp = File(dst.parentFile, if (resume) "${dst.name}.part" else "${dst.name}.${java.util.UUID.randomUUID()}.part")
        var done = false
        try {
            val c = open(key, from = tmp.length())
            var written = if (c.responseCode == 206) tmp.length() else 0L
            val total = written + c.contentLengthLong
            c.inputStream.use { input ->
                FileOutputStream(tmp, written > 0).use { out ->
                    val buf = ByteArray(64 shl 10)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) onProgress(written.toFloat() / total)
                    }
                }
            }
            if (!tmp.renameTo(dst) && !dst.exists()) throw IOException("rename failed: $dst")
            done = true
        } finally {
            if (done || !resume) tmp.delete()
        }
    }

    private class Unauthorized(message: String) : IOException(message)

    /** The saved token can be revoked early; on 401 sign in again and retry once. */
    private fun <T> reauthorizing(block: () -> T): T =
        try { block() } catch (e: Unauthorized) { invalidate(); block() }

    companion object { const val DEFAULT_BUCKET = "buyolitsez-music" }
}
