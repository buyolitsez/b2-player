package dev.buyolitsez.b2player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Playlist(val id: String, val name: String, val trackIds: List<String>, val updated: Long)

/** User playlists, kept in prefs and synced to state/playlists.json in the bucket. */
class Playlists(context: Context) {
    private val prefs = context.getSharedPreferences("playlists", Context.MODE_PRIVATE)

    @get:Synchronized
    var all: List<Playlist>
        get() = parse(prefs.getString("data", null))
        private set(v) { prefs.edit().putString("data", toJson(v).toString()).putBoolean("dirty", true).apply() }

    @Synchronized fun get(id: String) = all.find { it.id == id }

    @Synchronized fun create(name: String, ids: List<String> = emptyList()): Playlist =
        Playlist(UUID.randomUUID().toString(), name, ids, System.currentTimeMillis()).also { all = all + it }

    @Synchronized fun update(id: String, f: (Playlist) -> Playlist) {
        all = all.map { if (it.id == id) f(it).copy(updated = System.currentTimeMillis()) else it }
    }

    @Synchronized fun delete(id: String) { all = all.filter { it.id != id } }

    fun add(id: String, trackIds: List<String>) = update(id) { p -> p.copy(trackIds = p.trackIds + trackIds.filter { it !in p.trackIds }) }

    /**
     * Same rule as likes: unsent local changes win, otherwise the bucket copy wins. The network
     * part runs without the lock, so edits made meanwhile are kept and sent next time.
     */
    fun sync(b2: B2) {
        val (dirty, data) = synchronized(this) { prefs.getBoolean("dirty", false) to prefs.getString("data", null) }
        if (dirty) {
            b2.upload(KEY, toJson(parse(data)).toString(1).toByteArray(), "application/json")
            synchronized(this) { if (prefs.getString("data", null) == data) prefs.edit().putBoolean("dirty", false).apply() }
        } else {
            val bytes = b2.fetchOrNull(KEY) ?: return
            synchronized(this) { if (!prefs.getBoolean("dirty", false)) prefs.edit().putString("data", bytes.decodeToString()).apply() }
        }
    }

    private fun toJson(list: List<Playlist>) = JSONObject().put("version", 1).put(
        "playlists",
        JSONArray(list.map { p ->
            JSONObject().put("id", p.id).put("name", p.name).put("tracks", JSONArray(p.trackIds)).put("updated", p.updated)
        }),
    )

    private fun parse(s: String?): List<Playlist> {
        if (s == null) return emptyList()
        val arr = JSONObject(s).optJSONArray("playlists") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val t = o.getJSONArray("tracks")
            Playlist(o.getString("id"), o.getString("name"), (0 until t.length()).map { t.getString(it) }, o.optLong("updated"))
        }
    }

    companion object { const val KEY = "state/playlists.json" }
}

data class Play(val trackId: String, val time: Long)

/**
 * Listening history: one line per counted play in filesDir/history.tsv, merged with
 * state/history.tsv in the bucket (union of both).
 */
class History(context: Context) {
    private val file = File(context.filesDir, "history.tsv")
    private var cache: List<Play>? = null

    @Synchronized
    fun all(): List<Play> = cache ?: (if (file.exists()) parse(file.readText()) else emptyList()).also { cache = it }

    @Synchronized
    fun record(trackId: String, time: Long = System.currentTimeMillis()) {
        file.appendText("$time\t$trackId\n")
        cache = all() + Play(trackId, time)
    }

    @Synchronized
    fun clear() { file.delete(); cache = emptyList() }

    @Synchronized
    fun sync(b2: B2) {
        val local = all()
        val remote = b2.fetchOrNull(KEY)?.decodeToString()?.let { parse(it) } ?: emptyList()
        val merged = (local + remote).distinct().sortedBy { it.time }
        if (merged.size != local.size) { file.writeText(format(merged)); cache = merged }
        if (merged.size != remote.size) b2.upload(KEY, format(merged).toByteArray(), "text/tab-separated-values")
    }

    private fun format(list: List<Play>) = list.joinToString("") { "${it.time}\t${it.trackId}\n" }
    private fun parse(s: String) = s.lineSequence().mapNotNull { l ->
        val tab = l.indexOf('\t'); if (tab <= 0) null else l.substring(0, tab).toLongOrNull()?.let { Play(l.substring(tab + 1), it) }
    }.toList()

    companion object { const val KEY = "state/history.tsv" }
}
