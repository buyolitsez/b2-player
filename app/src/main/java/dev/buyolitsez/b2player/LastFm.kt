package dev.buyolitsez.b2player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Last.fm scrobbling. Scrobbles wait in a local queue until they are accepted. */
class LastFm(context: Context) {
    private val p = context.getSharedPreferences("lastfm", Context.MODE_PRIVATE)

    var apiKey: String
        get() = p.getString("apiKey", "")!!
        set(v) = p.edit().putString("apiKey", v.trim()).apply()
    var secret: String
        get() = p.getString("secret", "")!!
        set(v) = p.edit().putString("secret", v.trim()).apply()
    val user: String? get() = p.getString("user", null)
    private val session: String? get() = p.getString("session", null)
    val connected get() = session != null && apiKey.isNotEmpty() && secret.isNotEmpty()

    var scrobbleEnabled: Boolean
        get() = p.getBoolean("scrobble", true)
        set(v) = p.edit().putBoolean("scrobble", v).apply()
    var nowPlayingEnabled: Boolean
        get() = p.getBoolean("nowPlaying", true)
        set(v) = p.edit().putBoolean("nowPlaying", v).apply()

    val pendingCount get() = pending().length()

    /** Exchanges the password for a session key; the password itself is not stored. */
    fun login(username: String, password: String) {
        val j = call(mapOf("method" to "auth.getMobileSession", "username" to username.trim(), "password" to password))
        val s = j.getJSONObject("session")
        p.edit().putString("session", s.getString("key")).putString("user", s.getString("name")).apply()
    }

    fun logout() = p.edit().remove("session").remove("user").apply()

    fun nowPlaying(t: Track) {
        if (!connected || !nowPlayingEnabled) return
        call(
            mapOf(
                "method" to "track.updateNowPlaying", "artist" to t.artist, "track" to t.title,
                "album" to t.album.title, "duration" to t.duration.toInt().toString(), "sk" to session!!,
            )
        )
    }

    @Synchronized
    fun enqueue(t: Track, startedAt: Long) {
        if (!connected || !scrobbleEnabled) return
        val arr = pending().put(
            JSONObject().put("artist", t.artist).put("track", t.title).put("album", t.album.title)
                .put("duration", t.duration.toInt()).put("ts", startedAt / 1000)
        )
        p.edit().putString("pending", arr.toString()).apply()
    }

    /** Sends queued scrobbles, 50 per request. */
    @Synchronized
    fun flush() {
        if (!connected) return
        while (true) {
            val arr = pending()
            if (arr.length() == 0) return
            val n = minOf(50, arr.length())
            val params = mutableMapOf("method" to "track.scrobble", "sk" to session!!)
            for (i in 0 until n) {
                val o = arr.getJSONObject(i)
                params["artist[$i]"] = o.getString("artist"); params["track[$i]"] = o.getString("track")
                params["album[$i]"] = o.getString("album"); params["duration[$i]"] = o.getInt("duration").toString()
                params["timestamp[$i]"] = o.getLong("ts").toString()
            }
            call(params)
            val rest = JSONArray(); for (i in n until arr.length()) rest.put(arr.get(i))
            p.edit().putString("pending", rest.toString()).apply()
        }
    }

    private fun pending() = JSONArray(p.getString("pending", "[]"))

    private fun call(params: Map<String, String>): JSONObject {
        if (apiKey.isEmpty() || secret.isEmpty()) throw IOException("Last.fm API key is not set")
        val all = params + ("api_key" to apiKey)
        val sig = MessageDigest.getInstance("MD5")
            .digest((all.toSortedMap().entries.joinToString("") { it.key + it.value } + secret).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val body = (all + mapOf("api_sig" to sig, "format" to "json")).entries
            .joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val c = URL("https://ws.audioscrobbler.com/2.0/").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        val j = runCatching { JSONObject(text) }.getOrElse { throw IOException("Last.fm: HTTP ${c.responseCode}") }
        if (j.has("error")) throw IOException("Last.fm: ${j.optString("message")}")
        return j
    }
}
