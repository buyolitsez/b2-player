package dev.buyolitsez.b2player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

data class Track(
    val path: String, val title: String, val artist: String, val album: Album,
    val number: Int?, val disc: Int?, val duration: Double, val start: Double?, val size: Long,
    val likedInCatalog: Boolean, val hasLyrics: Boolean = false,
) {
    /** Unique per track; CUE tracks share a file, so the start offset is part of the id. */
    val id get() = if (start == null) path else "$path#$start"
    val ext get() = path.substringAfterLast('.', "").lowercase()
}

class Album(
    val id: String, val dir: String, val title: String, val artist: String, val year: Int?, val cover: String?,
    val added: Long,
) {
    lateinit var tracks: List<Track>
    val duration get() = tracks.sumOf { it.duration }
}

class Folder(val name: String, val path: String) {
    val folders = sortedMapOf<String, Folder>(String.CASE_INSENSITIVE_ORDER)
    val tracks = ArrayList<Track>()
    val allTracks: List<Track> by lazy { tracks + folders.values.flatMap { it.allTracks } }
}

class Catalog(val albums: List<Album>) {
    val tracks = albums.flatMap { it.tracks }
    val byId = tracks.associateBy { it.id }
    val artists: List<Pair<String, List<Album>>> =
        albums.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() }
    val root = Folder("Folders", "").also { root ->
        for (a in albums) {
            var f = root
            for (part in a.dir.split('/')) {
                val parent = f
                f = parent.folders.getOrPut(part) { Folder(part, if (parent.path.isEmpty()) part else "${parent.path}/$part") }
            }
            f.tracks.addAll(a.tracks)
        }
    }

    fun folder(path: String): Folder? {
        var f = root
        if (path.isEmpty()) return f
        for (part in path.split('/')) f = f.folders[part] ?: return null
        return f
    }

    companion object {
        fun parse(bytes: ByteArray): Catalog {
            val text = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
                GZIPInputStream(bytes.inputStream()).readBytes().decodeToString() else bytes.decodeToString()
            val arr = JSONObject(text).getJSONArray("albums")
            val albums = ArrayList<Album>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val album = Album(
                    a.getString("id"), a.getString("dir"), a.getString("title"), a.getString("artist"),
                    if (a.isNull("year")) null else a.getInt("year"), if (a.isNull("cover")) null else a.getString("cover"),
                    a.optLong("added", 0),
                )
                val tr = a.getJSONArray("tracks")
                album.tracks = (0 until tr.length()).map { k ->
                    val t = tr.getJSONObject(k)
                    Track(
                        t.getString("path"), t.getString("title"), t.optString("artist", album.artist), album,
                        if (t.has("track")) t.getInt("track") else null, if (t.has("disc")) t.getInt("disc") else null,
                        t.optDouble("duration", 0.0), if (t.has("start")) t.getDouble("start") else null,
                        t.optLong("size", 0), t.optBoolean("liked", false), t.optBoolean("ly", false),
                    )
                }
                albums.add(album)
            }
            return Catalog(albums)
        }
    }
}

data class SavedQueue(val name: String, val ids: List<String>, val index: Int, val positionMs: Long)

/** Likes (synced to state/likes.json in the bucket), offline files, cache state and saved queues. */
@UnstableApi
class Library(context: Context, private val cache: Cache) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    val offlineDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "offline")
    val catalogFile = File(context.filesDir, "catalog.json.gz")

    private fun set(name: String) = prefs.getStringSet(name, emptySet())!!

    // ---------- likes ----------

    fun isLiked(t: Track) = (t.likedInCatalog || t.id in set("liked+")) && t.id !in set("liked-")

    fun setLiked(t: Track, liked: Boolean) {
        val plus = set("liked+").toMutableSet(); val minus = set("liked-").toMutableSet()
        if (liked) { minus.remove(t.id); if (!t.likedInCatalog) plus.add(t.id) }
        else { plus.remove(t.id); if (t.likedInCatalog) minus.add(t.id) }
        prefs.edit().putStringSet("liked+", plus).putStringSet("liked-", minus).putBoolean("likesDirty", true).apply()
    }

    /**
     * Local changes not yet uploaded win; otherwise the bucket copy wins. Likes are stored as
     * differences from the catalog's "liked" flags, so catalog rebuilds keep them.
     */
    @Synchronized
    fun syncLikes(b2: B2) {
        // Installs from before syncing have local likes but no flag: upload them first.
        if (prefs.getBoolean("likesDirty", set("liked+").isNotEmpty() || set("liked-").isNotEmpty())) {
            val j = JSONObject().put("version", 1)
                .put("added", JSONArray(set("liked+").sorted())).put("removed", JSONArray(set("liked-").sorted()))
            b2.upload(LIKES, j.toString(1).toByteArray(), "application/json")
            prefs.edit().putBoolean("likesDirty", false).apply()
        } else {
            val bytes = b2.fetchOrNull(LIKES) ?: return
            val j = JSONObject(bytes.decodeToString())
            fun arr(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            prefs.edit().putStringSet("liked+", arr("added")).putStringSet("liked-", arr("removed")).apply()
        }
    }

    // ---------- offline and cache ----------

    fun offlineFile(path: String) = File(offlineDir, path)
    fun isDownloaded(t: Track) = offlineFile(t.path).exists()
    fun isCached(t: Track) = t.size > 0 && cache.getCachedBytes(uriFor(t.path).toString(), 0, t.size) >= t.size
    fun availableOffline(t: Track) = isDownloaded(t) || isCached(t)

    fun deleteDownload(t: Track) {
        val f = offlineFile(t.path)
        f.delete()
        var d = f.parentFile
        while (d != null && d != offlineDir && d.list()?.isEmpty() == true) { d.delete(); d = d.parentFile }
    }

    fun clearCache() { cache.keys.toList().forEach { cache.removeResource(it) } }
    fun cacheBytes() = cache.cacheSpace

    // ---------- queues ----------

    private fun queueJson(q: SavedQueue) =
        JSONObject().put("name", q.name).put("ids", JSONArray(q.ids)).put("index", q.index).put("pos", q.positionMs)

    private fun queueFrom(o: JSONObject): SavedQueue {
        val ids = o.getJSONArray("ids")
        return SavedQueue(o.optString("name"), (0 until ids.length()).map { ids.getString(it) }, o.getInt("index"), o.getLong("pos"))
    }

    /** Queues put aside with "Отложить". */
    var savedQueues: List<SavedQueue>
        get() = JSONArray(prefs.getString("savedQueues", "[]")).let { a -> (0 until a.length()).map { queueFrom(a.getJSONObject(it)) } }
        set(list) = prefs.edit().putString("savedQueues", JSONArray(list.map { queueJson(it) }).toString()).apply()

    /** The live queue, so it survives restarts. */
    var currentQueue: SavedQueue?
        get() = prefs.getString("currentQueue", null)?.let { queueFrom(JSONObject(it)) }
        set(q) = prefs.edit().putString("currentQueue", q?.let { queueJson(it).toString() }).apply()

    // ---------- media items ----------

    fun mediaItem(t: Track): MediaItem {
        val local = offlineFile(t.path)
        val uri = if (local.exists()) Uri.fromFile(local) else uriFor(t.path)
        val b = MediaItem.Builder().setMediaId(t.id).setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album.title)
                    .setTrackNumber(t.number).setIsPlayable(true).setIsBrowsable(false)
                    .setArtworkUri(t.album.cover?.let { Uri.parse("b2://bucket/" + Uri.encode(it, "/")) })
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

    companion object {
        const val MUSIC = "music/"
        const val LIKES = "state/likes.json"
        fun uriFor(path: String): Uri = Uri.parse("b2://bucket/" + Uri.encode(MUSIC + path, "/"))
    }
}
