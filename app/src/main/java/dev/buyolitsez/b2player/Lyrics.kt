package dev.buyolitsez.b2player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class LyricLine(val ms: Long, val text: String)

/** Either time-synced lines or plain text, and where they came from. */
data class Lyrics(val synced: List<LyricLine>?, val plain: String?, val source: String) {
    val empty get() = synced.isNullOrEmpty() && plain.isNullOrBlank()
}

/**
 * Lyrics for a track: synced lyrics from lrclib.net when allowed, otherwise the text from the
 * file's tags (published by the catalog as lyrics/<album-id>.json), otherwise plain lrclib text.
 * Results, including "nothing found", are cached in filesDir/lyrics.
 */
@UnstableApi
class LyricsRepo(private val app: App) {
    private val dir = File(app.filesDir, "lyrics").apply { mkdirs() }

    fun get(t: Track): Lyrics {
        val online = app.prefs.onlineLyrics.value
        val cacheFile = File(dir, hash(t.id + if (online) "|on" else "|off") + ".json")
        if (cacheFile.exists()) runCatching { return fromJson(JSONObject(cacheFile.readText())) }

        val tag = if (t.hasLyrics) runCatching { tagLyrics(t) }.onFailure { AppLog.w("lyrics", "Tag lyrics failed for ${t.id}", it) }.getOrNull() else null
        val remote = if (online) runCatching { lrclib(t) }.onFailure { AppLog.w("lyrics", "lrclib failed for ${t.id}", it) }.getOrNull() else null
        val result = when {
            remote?.synced?.isNotEmpty() == true -> remote
            tag != null -> tag
            remote != null && !remote.empty -> remote
            else -> Lyrics(null, null, "none")
        }
        // Don't cache network failures, only real answers.
        if (!online || remote != null || tag != null) cacheFile.writeText(toJson(result).toString())
        return result
    }

    private fun tagLyrics(t: Track): Lyrics? {
        val f = File(app.cacheDir, "lyrics-${t.album.id}.json")
        if (!f.exists()) app.b2.download("lyrics/${t.album.id}.json", f)
        val text = JSONObject(f.readText()).optString(t.path).takeIf { it.isNotBlank() } ?: return null
        val synced = parseLrc(text)
        return Lyrics(synced, if (synced == null) text else null, "file tags")
    }

    /** Exact match by artist, title, album and duration first; then a search with a duration check. */
    private fun lrclib(t: Track): Lyrics? {
        val q = "artist_name=${enc(t.artist)}&track_name=${enc(t.title)}&album_name=${enc(t.album.title)}&duration=${t.duration.toInt()}"
        val exact = request("https://lrclib.net/api/get?$q")?.let { JSONObject(it) }
        val hit = exact ?: request("https://lrclib.net/api/search?track_name=${enc(t.title)}&artist_name=${enc(t.artist)}")
            ?.let { JSONArray(it) }?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { o -> kotlin.math.abs(o.optDouble("duration", 0.0) - t.duration) < 5 }
            }
            ?: return Lyrics(null, null, "lrclib.net")
        val synced = hit.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { parseLrc(it) }
        val plain = hit.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        return Lyrics(synced, plain, "lrclib.net")
    }

    /** Body for 200, null for 404; other statuses throw so the result is not cached. */
    private fun request(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "b2-player/0.4")
        c.connectTimeout = 10000; c.readTimeout = 15000
        return when (c.responseCode) {
            200 -> c.inputStream.bufferedReader().readText()
            404 -> null
            else -> throw java.io.IOException("lrclib HTTP ${c.responseCode}")
        }
    }

    private fun enc(s: String) = Uri.encode(s)
    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    private fun toJson(l: Lyrics) = JSONObject().put("source", l.source).put("plain", l.plain ?: JSONObject.NULL)
        .put("synced", l.synced?.let { JSONArray(it.map { x -> JSONObject().put("ms", x.ms).put("t", x.text) }) } ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject) = Lyrics(
        o.optJSONArray("synced")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { x -> LyricLine(x.getLong("ms"), x.getString("t")) } } },
        o.optString("plain").takeIf { !o.isNull("plain") && it.isNotBlank() },
        o.optString("source"),
    )

    fun clearCache() { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        private val TIME = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")

        /** Parses "[mm:ss.xx] text" lines; null when the text has no time tags. */
        fun parseLrc(text: String): List<LyricLine>? {
            val out = ArrayList<LyricLine>()
            for (line in text.lines()) {
                val stamps = TIME.findAll(line).toList()
                if (stamps.isEmpty()) continue
                val words = line.substring(stamps.last().range.last + 1).trim()
                for (m in stamps) {
                    val (mm, ss, frac) = m.destructured
                    val f = frac.takeIf { it.isNotEmpty() }?.let { it.padEnd(3, '0').take(3).toLong() } ?: 0
                    out.add(LyricLine(mm.toLong() * 60_000 + ss.toLong() * 1000 + f, words))
                }
            }
            return out.sortedBy { it.ms }.takeIf { it.size >= 3 }
        }
    }
}
