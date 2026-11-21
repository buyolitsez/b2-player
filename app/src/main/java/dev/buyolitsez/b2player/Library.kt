package dev.buyolitsez.b2player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

/** Streaming parse (android.util.JsonReader): about twice as fast as building org.json trees. */
fun Catalog.Companion.parse(bytes: ByteArray): Catalog {
    val gz = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    val input = if (gz) GZIPInputStream(bytes.inputStream(), 1 shl 16) else bytes.inputStream()
    val albums = ArrayList<Album>(2048)
    android.util.JsonReader(input.bufferedReader()).use { r ->
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "albums") { r.skipValue(); continue }
            r.beginArray()
            while (r.hasNext()) albums.add(readAlbum(r))
            r.endArray()
        }
        r.endObject()
    }
    return Catalog(albums)
}

private fun android.util.JsonReader.str(): String? =
    if (peek() == android.util.JsonToken.NULL) { nextNull(); null } else nextString()
private fun android.util.JsonReader.int(): Int? =
    if (peek() == android.util.JsonToken.NULL) { nextNull(); null } else nextInt()

private class RawTrack(
    val path: String, val title: String, val artist: String?, val number: Int?, val disc: Int?,
    val duration: Double, val start: Double?, val size: Long, val liked: Boolean, val lyrics: Boolean, val lite: Long,
)

private fun readAlbum(r: android.util.JsonReader): Album {
    var id = ""; var dir = ""; var title = ""; var artist = ""; var year: Int? = null; var cover: String? = null; var added = 0L; var genre: String? = null
    val raw = ArrayList<RawTrack>()
    r.beginObject()
    while (r.hasNext()) when (r.nextName()) {
        "id" -> id = r.nextString()
        "dir" -> dir = r.nextString()
        "title" -> title = r.nextString()
        "artist" -> artist = r.nextString()
        "year" -> year = r.int()
        "cover" -> cover = r.str()
        "added" -> added = r.nextLong()
        "genre" -> genre = r.str()
        "tracks" -> {
            r.beginArray()
            while (r.hasNext()) {
                var path = ""; var tTitle = ""; var tArtist: String? = null; var number: Int? = null; var disc: Int? = null
                var duration = 0.0; var start: Double? = null; var size = 0L; var liked = false; var ly = false; var lite = 0L
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "path" -> path = r.nextString()
                    "title" -> tTitle = r.nextString()
                    "artist" -> tArtist = r.str()
                    "track" -> number = r.int()
                    "disc" -> disc = r.int()
                    "duration" -> duration = r.nextDouble()
                    "start" -> start = r.nextDouble()
                    "size" -> size = r.nextLong()
                    "liked" -> liked = r.nextBoolean()
                    "ly" -> ly = r.nextBoolean()
                    "lite" -> lite = r.nextLong()
                    else -> r.skipValue()
                }
                r.endObject()
                raw.add(RawTrack(path, tTitle, tArtist, number, disc, duration, start, size, liked, ly, lite))
            }
            r.endArray()
        }
        else -> r.skipValue()
    }
    r.endObject()
    val album = Album(id, dir, title, artist, year, cover, added, genre)
    album.tracks = raw.map { t ->
        Track(t.path, t.title, t.artist ?: artist, album, t.number, t.disc, t.duration, t.start, t.size, t.liked, t.lyrics, t.lite)
    }
    return album
}

data class SavedQueue(val name: String, val ids: List<String>, val index: Int, val positionMs: Long)

/** Likes (synced to state/likes.json in the bucket), offline files, cache state and saved queues. */
@UnstableApi
class Library(private val context: Context, private val cache: Cache, private val settings: Prefs) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    /** The app's own folder: downloads lived there before, and go away with the app. */
    private val appDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "offline")
    /**
     * The chosen folder (Music/B2 Player by default) needs all-files access; until it is granted downloads go to [appDir].
     * Checked again by [moveDownloads], not on each use: lists ask it for every track.
     */
    @Volatile var offlineDir = currentDir(); private set
    private fun currentDir() = if (hasStorageAccess(context)) File(settings.downloadDir.value) else appDir
    val catalogFile = File(context.filesDir, "catalog.json.gz")
    /** SHA-1 of the saved catalog as B2 reports it; an unchanged catalog is not downloaded again. */
    var catalogSha: String?
        get() = prefs.getString("catalogSha", null)
        set(v) { prefs.edit().putString("catalogSha", v).apply() }

    private fun set(name: String) = prefs.getStringSet(name, emptySet())!!

    // ---------- likes ----------

    fun isLiked(t: Track) = (t.likedInCatalog || t.id in set("liked+")) && t.id !in set("liked-")

    /** Guards the like sets; the network part of a sync runs outside it so taps never wait for it. */
    private val likesLock = Any()

    /**
     * When each track in "liked+" was liked (epoch ms), synced as "times" in likes.json. Likes older than
     * this and the catalog's own likes have none.
     */
    @Volatile private var likeTimes: Map<String, Long> = prefs.getString("likeTimes", null)?.let { times(JSONObject(it)) } ?: emptyMap()
    fun likedAt(t: Track) = likeTimes[t.id]

    private fun times(j: JSONObject?) = j?.keys()?.asSequence()?.associateWith { j.getLong(it) } ?: emptyMap()
    private fun saveTimes(e: SharedPreferences.Editor, times: Map<String, Long>) {
        likeTimes = times
        e.putString("likeTimes", JSONObject(times).toString())
    }

    fun setLiked(t: Track, liked: Boolean, at: Long) = synchronized(likesLock) {
        val plus = set("liked+").toMutableSet(); val minus = set("liked-").toMutableSet()
        if (liked) { minus.remove(t.id); if (!t.likedInCatalog) plus.add(t.id) }
        else { plus.remove(t.id); if (t.likedInCatalog) minus.add(t.id) }
        val e = prefs.edit().putStringSet("liked+", plus).putStringSet("liked-", minus)
        saveTimes(e, if (t.id in plus) likeTimes + (t.id to at) else likeTimes - t.id)
        e.apply()
    }

    /**
     * Puts the local changes since the last sync on top of the bucket copy, so likes made in cmus
     * meanwhile are kept, and uploads the result if it differs. Likes are stored as differences
     * from the catalog's "liked" flags, so catalog rebuilds keep them. Returns the ids whose like changed here.
     */
    // An own lock: a sync waits on the network, and moveDownloads must not wait for it.
    private val syncLock = Any()

    fun syncLikes(b2: B2): Set<String> = synchronized(syncLock) {
        val (plus, minus) = synchronized(likesLock) { set("liked+").toSet() to set("liked-").toSet() }
        val localTimes = likeTimes
        val j = b2.fetchOrNull(LIKES)?.let { JSONObject(it.decodeToString()) }
        fun arr(k: String) = j?.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        val remotePlus = arr("added"); val remoteMinus = arr("removed"); val remoteTimes = times(j?.optJSONObject("times"))
        fun merge(remote: Set<String>, base: Set<String>, local: Set<String>) = remote + (local - base) - (base - local)
        val newPlus = merge(remotePlus, set("synced+").toSet(), plus)
        val newMinus = merge(remoteMinus, set("synced-").toSet(), minus)
        // cmus writes no times, and a writer that doesn't know them drops them: likes new since the last
        // sync get the time they are seen (on the first sync all are old), and known times are put back.
        val now = System.currentTimeMillis()
        val fresh = if (prefs.contains("synced+")) newPlus - set("synced+") else emptySet()
        val newTimes = newPlus.mapNotNull { id ->
            (maxOf(remoteTimes[id] ?: 0, localTimes[id] ?: 0).takeIf { it > 0 } ?: now.takeIf { id in fresh })?.let { id to it }
        }.toMap()
        if (newPlus != remotePlus || newMinus != remoteMinus || newTimes != remoteTimes) {
            val out = JSONObject().put("version", 1).put("added", JSONArray(newPlus.sorted())).put("removed", JSONArray(newMinus.sorted()))
                .put("times", JSONObject(newTimes.toSortedMap()))
            try { b2.upload(LIKES, out.toString(1).toByteArray(), "application/json") }
            // With only times to fill in, a read-only key still takes the likes from the bucket.
            catch (e: B2.ReadOnlyKey) { if (newPlus != remotePlus || newMinus != remoteMinus) throw e }
        }
        return synchronized(likesLock) {
            // A like made during the sync stays on top; the sync it started uploads it.
            val localPlus = merge(newPlus, plus, set("liked+")); val localMinus = merge(newMinus, minus, set("liked-"))
            fun diff(a: Set<String>, b: Set<String>) = (a - b) + (b - a)
            val changed = diff(localPlus, set("liked+")) + diff(localMinus, set("liked-"))
            val e = prefs.edit().putStringSet("liked+", localPlus).putStringSet("liked-", localMinus)
                .putStringSet("synced+", newPlus).putStringSet("synced-", newMinus)
            saveTimes(e, (likeTimes + newTimes).filterKeys { it in localPlus })
            e.apply()
            changed
        }
    }

    // ---------- offline and cache ----------

    fun offlineFile(path: String) = File(offlineDir, path)
    fun isDownloaded(t: Track) = offlineFile(t.path).exists()
    fun isCached(t: Track) = t.size > 0 && cache.getCachedBytes(uriFor(t.path).toString(), 0, t.size) >= t.size

    /**
     * Copies the original from the player's cache to [dst] when it is cached whole (a liked track that was just
     * played), so it isn't downloaded again. Returns false when it isn't, or the cache lost it meanwhile.
     */
    fun copyFromCache(path: String, dst: File): Boolean {
        val key = uriFor(path).toString()
        val size = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (size <= 0 || !cache.isCached(key, 0, size)) return false
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".part")
        return try {
            // Without an upstream the source reads only the cache, and fails instead of going to the network.
            DataSourceInputStream(CacheDataSource(cache, null), DataSpec(Uri.parse(key))).use { input ->
                tmp.outputStream().use { input.copyTo(it, 64 shl 10) }
            }
            tmp.length() == size && tmp.renameTo(dst)
        } catch (e: java.io.IOException) {
            AppLog.w("download", "Copy from cache failed: $path", e); false
        } finally { tmp.delete() }
    }

    /**
     * Applies renames made on the computer (contrib/cmus-b2-rename of the cmus fork) to the likes, the queues
     * and the downloads here; [moved] maps an old track id or path to its new one, [pairs] are the old and new paths.
     */
    fun rename(moved: (String) -> String, pairs: List<Pair<String, String>>) {
        synchronized(syncLock) {
            synchronized(likesLock) {
                val e = prefs.edit()
                for (k in listOf("liked+", "liked-", "synced+", "synced-")) if (prefs.contains(k)) e.putStringSet(k, set(k).map(moved).toSet())
                saveTimes(e, likeTimes.mapKeys { moved(it.key) })
                e.apply()
            }
        }
        savedQueues = savedQueues.map { q -> q.copy(ids = q.ids.map(moved)) }
        currentQueue?.let { q -> currentQueue = q.copy(ids = q.ids.map(moved)) }
        synchronized(this) {
            for ((old, new) in pairs) {
                val from = offlineFile(old); val to = offlineFile(new)
                if (!from.exists() || to.exists()) continue
                to.parentFile?.mkdirs()
                if (from.renameTo(to)) pruneDirs(from.parentFile, offlineDir) else AppLog.w("rename", "Couldn't move the download $old")
            }
        }
    }

    /** How many lines of state/renames.tsv were applied here. */
    var renamesSeen: Int
        get() = prefs.getInt("renamesSeen", 0)
        set(v) { prefs.edit().putInt("renamesSeen", v).apply() }

    fun deleteDownload(t: Track) {
        val f = offlineFile(t.path)
        f.delete()
        pruneDirs(f.parentFile, offlineDir)
    }

    /**
     * Updates [offlineDir] and moves the downloads of [c]'s tracks into it from the app's folder and from the folder chosen before.
     * Only catalog tracks are moved: a chosen folder may hold other files. A folder the app creates gets a
     * .nomedia file, so other players don't list the downloads twice. Returns whether anything changed.
     */
    @Synchronized
    fun moveDownloads(c: Catalog?): Boolean {
        val to = currentDir()
        val changed = to != offlineDir
        offlineDir = to
        if (c == null) return changed
        val sources = setOf(appDir, File(prefs.getString("downloadsAt", appDir.path)!!)).filter { it != to && it.exists() }
        if (sources.isEmpty()) return changed
        if (!to.exists() && to.mkdirs()) File(to, ".nomedia").createNewFile()
        for (from in sources) {
            var moved = 0
            for (path in c.tracks.map { it.path }.distinct()) {
                val src = File(from, path)
                if (!src.exists()) continue
                val dst = File(to, path)
                if (!dst.exists()) {
                    dst.parentFile?.mkdirs()
                    // Two volumes, or the app's folder on some phones: rename fails, copy instead.
                    if (!src.renameTo(dst)) {
                        val tmp = File(dst.path + ".part")
                        src.copyTo(tmp, overwrite = true)
                        tmp.renameTo(dst)
                    }
                    moved++
                }
                src.delete()
                pruneDirs(src.parentFile, from)
            }
            if (from == appDir) appDir.deleteRecursively()
            AppLog.i("download", "Moved $moved downloads from $from to $to")
        }
        prefs.edit().putString("downloadsAt", to.path).apply()
        return true
    }

    private fun pruneDirs(dir: File?, root: File) {
        var d = dir
        while (d != null && d != root && d.list()?.isEmpty() == true) { d.delete(); d = d.parentFile }
    }

    /**
     * Drops cached files whose size no longer matches the catalog: the file in the bucket was
     * replaced (tags rewritten by apply-tags.py), and old cached bytes must not mix with new ones.
     */
    fun dropStaleCache(c: Catalog) {
        val sizes = c.tracks.associate { uriFor(it.path).toString() to it.size } +
            c.tracks.filter { it.liteSize > 0 }.associate { liteUriFor(it.path).toString() to it.liteSize }
        for (key in cache.keys.toList()) {
            val cached = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            val size = sizes[key] ?: continue
            if (cached > 0 && size > 0 && cached != size) { cache.removeResource(key); AppLog.i("cache", "Dropped stale $key") }
        }
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

    /** Queues saved with "Save queue for later". */
    var savedQueues: List<SavedQueue>
        get() = JSONArray(prefs.getString("savedQueues", "[]")).let { a -> (0 until a.length()).map { queueFrom(a.getJSONObject(it)) } }
        set(list) = prefs.edit().putString("savedQueues", JSONArray(list.map { queueJson(it) }).toString()).apply()

    /** The live queue, so it survives restarts. */
    /**
     * The queue is saved every 10 s while playing, but usually only the position changes: that goes to its own
     * small prefs file, and the ids are rewritten (with the whole "library" file) only when they change.
     */
    var currentQueue: SavedQueue?
        get() = prefs.getString("currentQueue", null)?.let { queueFrom(JSONObject(it)) }
            ?.let { q -> q.copy(index = position.getInt("index", q.index), positionMs = position.getLong("pos", q.positionMs)) }
        set(q) {
            if (q?.ids != savedIds) { prefs.edit().putString("currentQueue", q?.let { queueJson(it).toString() }).apply(); savedIds = q?.ids }
            if (q != null) position.edit().putInt("index", q.index).putLong("pos", q.positionMs).apply()
        }
    private val position = context.getSharedPreferences("queuePosition", Context.MODE_PRIVATE)
    private var savedIds: List<String>? = null

    /** Ids of the queue before the queue filter, see [Ui.setQueueFilter]. */
    /** Read on every queue edit (each drag step too), so it is kept in memory, and saved in its own prefs file. */
    var queueSource: List<String>
        get() = source ?: sourcePrefs.getString("ids", null).orEmpty().split('\n').filter { it.isNotEmpty() }.also { source = it }
        set(ids) { if (ids != source) { source = ids; sourcePrefs.edit().putString("ids", ids.joinToString("\n")).apply() } }
    private val sourcePrefs = context.getSharedPreferences("queueSource", Context.MODE_PRIVATE)
    private var source: List<String>? = null

    // ---------- media items ----------

    /**
     * Plays the download if there is one; with Lite streaming on, the light copy, unless the
     * original is cached whole; otherwise the original.
     */
    fun mediaItem(t: Track, lite: Boolean = settings.liteStreaming.value, downloaded: Boolean = isDownloaded(t)): MediaItem {
        val uri = when {
            downloaded -> Uri.fromFile(offlineFile(t.path))
            lite && t.liteSize > 0 && !isCached(t) -> liteUriFor(t.path)
            else -> uriFor(t.path)
        }
        // A MIME type spares the player guessing the type from the decoded path of each item, on the main thread.
        val b = MediaItem.Builder().setMediaId(t.id).setUri(uri).setMimeType(MimeTypes.AUDIO_UNKNOWN)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album.title)
                    .setTrackNumber(t.number).setIsPlayable(true).setIsBrowsable(false)
                    .setArtworkUri(t.album.cover?.let { Uri.parse("b2://bucket/" + Uri.encode(it, "/")) })
                    .build()
            )
        t.start?.let { start ->
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((start * 1000).toLong())
                    .setEndPositionMs(((start + t.duration) * 1000).toLong()).build()
            )
        }
        return b.build()
    }

    /** [mediaItem] for many tracks: a queue of the whole library. */
    fun mediaItems(tracks: List<Track>): List<MediaItem> {
        val downloaded = downloadedCheck()
        return tracks.map { mediaItem(it, downloaded = downloaded(it)) }
    }

    /**
     * [isDownloaded] for many tracks: one listing per album folder instead of a check per file, which on
     * shared storage costs ≈0.25 ms, seconds for the whole library. The listings are not updated afterwards.
     */
    fun downloadedCheck(): (Track) -> Boolean {
        val dirs = HashMap<String, Set<String>>()
        return { t ->
            val f = offlineFile(t.path)
            f.name in dirs.getOrPut(f.parent.orEmpty()) { f.parentFile?.list()?.toHashSet().orEmpty() }
        }
    }

    companion object {
        const val MUSIC = "music/"
        const val LIKES = "state/likes.json"
        /** Light Opus copies made by tools/make_lite.py. */
        const val LITE = "lite/"
        fun uriFor(path: String): Uri = Uri.parse("b2://bucket/" + Uri.encode(MUSIC + path, "/"))
        fun liteUriFor(path: String): Uri = Uri.parse("b2://bucket/" + Uri.encode("$LITE$path.opus", "/"))
        fun isLite(uri: Uri) = uri.path?.startsWith("/$LITE") == true
    }
}

/** Access to shared storage for the download folder: all-files access, or the storage permission on Android 10. */
fun hasStorageAccess(c: Context) =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/** The system screen that grants [hasStorageAccess]. */
fun storageAccessIntent(c: Context) = Intent(
    if (Build.VERSION.SDK_INT >= 30) Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION else Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.parse("package:${c.packageName}"),
)

/** Exempt from battery optimization: downloads that start in the background still run with the screen off. */
fun ignoresBatteryOptimizations(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

/** The system dialog that grants [ignoresBatteryOptimizations]. */
fun batteryExemptionIntent(c: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))
