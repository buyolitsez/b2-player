package dev.buyolitsez.b2player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

data class Track(
    val path: String, val title: String, val artist: String, val album: Album?,
    val number: Int?, val disc: Int?, val duration: Double, val start: Double?, val likedInCatalog: Boolean,
) {
    /** Unique per track; CUE tracks share a file, so the start offset is part of the id. */
    val id get() = if (start == null) path else "$path#$start"
}

class Album(
    val id: String, val dir: String, val title: String, val artist: String, val year: Int?, val cover: String?,
) {
    lateinit var tracks: List<Track>
}

class Catalog(val albums: List<Album>) {
    val tracks = albums.flatMap { it.tracks }
    val byId = tracks.associateBy { it.id }
    val artists: List<Pair<String, List<Album>>> =
        albums.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() }

    companion object {
        fun parse(bytes: ByteArray): Catalog {
            val text = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
                GZIPInputStream(bytes.inputStream()).readBytes().decodeToString() else bytes.decodeToString()
            val root = JSONObject(text)
            val arr = root.getJSONArray("albums")
            val albums = ArrayList<Album>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val album = Album(
                    a.getString("id"), a.getString("dir"), a.getString("title"), a.getString("artist"),
                    if (a.isNull("year")) null else a.getInt("year"), if (a.isNull("cover")) null else a.getString("cover"),
                )
                val tr = a.getJSONArray("tracks")
                album.tracks = (0 until tr.length()).map { k ->
                    val t = tr.getJSONObject(k)
                    Track(
                        t.getString("path"), t.getString("title"), t.optString("artist", album.artist), album,
                        if (t.has("track")) t.getInt("track") else null, if (t.has("disc")) t.getInt("disc") else null,
                        t.optDouble("duration", 0.0), if (t.has("start")) t.getDouble("start") else null,
                        t.optBoolean("liked", false),
                    )
                }
                albums.add(album)
            }
            return Catalog(albums)
        }
    }
}

/** Likes and offline files live on the phone: the B2 key is read-only. */
class Library(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    val offlineDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "offline")
    val catalogFile = File(context.filesDir, "catalog.json.gz")

    private fun set(name: String) = prefs.getStringSet(name, emptySet())!!

    fun isLiked(t: Track) = (t.likedInCatalog || t.id in set("liked+")) && t.id !in set("liked-")

    fun setLiked(t: Track, liked: Boolean) {
        val plus = set("liked+").toMutableSet(); val minus = set("liked-").toMutableSet()
        if (liked) { minus.remove(t.id); if (!t.likedInCatalog) plus.add(t.id) }
        else { plus.remove(t.id); if (t.likedInCatalog) minus.add(t.id) }
        prefs.edit().putStringSet("liked+", plus).putStringSet("liked-", minus).apply()
    }

    fun offlineFile(path: String) = File(offlineDir, path)
    fun isOffline(t: Track) = offlineFile(t.path).exists()

    fun mediaItem(t: Track): MediaItem {
        val local = offlineFile(t.path)
        val uri = if (local.exists()) Uri.fromFile(local) else Uri.parse("b2://bucket/" + Uri.encode(MUSIC + t.path, "/"))
        val b = MediaItem.Builder().setMediaId(t.id).setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album?.title)
                    .setTrackNumber(t.number).setIsPlayable(true).setIsBrowsable(false)
                    .setArtworkUri(t.album?.cover?.let { Uri.parse("b2://bucket/" + Uri.encode(it, "/")) })
                    .setExtras(Bundle().apply { putString("path", t.path) })
                    .build()
            )
        if (t.start != null) {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((t.start * 1000).toLong())
                    .setEndPositionMs(((t.start + t.duration) * 1000).toLong()).build()
            )
        }
        return b.build()
    }

    companion object { const val MUSIC = "music/" }
}
