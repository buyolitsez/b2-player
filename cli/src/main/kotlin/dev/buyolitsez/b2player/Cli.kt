package dev.buyolitsez.b2player

import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.system.exitProcess

/*
 * The assistant of the app in a terminal, playing in cmus. Tracks are played as contrib/cmus-b2 adds
 * them to cmus, likes and playlists are changed in the bucket on top of its copy, as cmus-b2-like does.
 */

private const val REMOTE = "b2music:buyolitsez-music"
private val OUT = File(System.getProperty("user.home"), ".cache/cmus-b2")
private val ENV_FILE = File(System.getProperty("user.home"), ".config/cmus-b2/env")
private val LOG = File(OUT, "assistant.log")
private val mapper = jsonMapper()

private fun exec(vararg cmd: String, input: ByteArray? = null): String {
    val p = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    p.outputStream.use { if (input != null) it.write(input) }
    val out = p.inputStream.readBytes().decodeToString()
    if (p.waitFor() != 0) error("${cmd.take(2).joinToString(" ")} failed")
    return out
}

private fun cmus(vararg commands: String) = exec("cmus-remote", "-C", *commands)

/** cmus-remote -Q: status, file, and options as "set <name>". */
private fun query(): Map<String, String> = runCatching { exec("cmus-remote", "-Q") }.getOrDefault("").lines().associate { line ->
    if (line.startsWith("set ")) line.substring(4).substringBefore(' ').let { "set $it" } to line.substring(4).substringAfter(' ', "")
    else line.substringBefore(' ') to line.substringAfter(' ', "")
}

// rclone cat prints nothing for a missing file; a failed read throws instead of passing for an empty state
private fun bucketFile(name: String): String? = exec("rclone", "cat", "$REMOTE/$name").takeIf { it.isNotBlank() }

private fun upload(name: String, data: String) { exec("rclone", "rcat", "$REMOTE/$name", input = data.toByteArray()) }

fun Catalog.Companion.parse(file: File): Catalog {
    val root = GZIPInputStream(file.inputStream()).use { mapper.readTree(it) }
    fun JsonNode.str(k: String) = get(k)?.takeIf { !it.isNull }?.asText()
    fun JsonNode.int(k: String) = get(k)?.takeIf { !it.isNull }?.asInt()
    return Catalog(root["albums"].map { a ->
        val album = Album(a["id"].asText(), a["dir"].asText(), a["title"].asText(), a["artist"].asText(), a.int("year"),
            a.str("cover"), a.path("added").asLong(), a.str("genre"))
        album.tracks = a["tracks"].map { t ->
            Track(t["path"].asText(), t["title"].asText(), t.str("artist") ?: album.artist, album, t.int("track"), t.int("disc"),
                t.path("duration").asDouble(), t.get("start")?.asDouble(), t.path("size").asLong(), t.path("liked").asBoolean(),
                t.path("ly").asBoolean())
        }
        album
    })
}

class Cmus(private val cat: Catalog) : AssistantHost {
    /** A track by its cmus key: the bucket path, or the album dir and track number of a CUE track. */
    private val byKey = cat.tracks.associateBy { key(it) }
    private val opts = query()
    private val bucketDir = opts["set bucket_dir"].orEmpty()
    private val downloadDir = opts["set download_dir"].orEmpty()
    /** The bucket's state files, fetched once per message. */
    private val state = HashMap<String, String?>()

    private var likes: Pair<Set<String>, Set<String>>? = null
    private var ratings: JsonNode? = null

    fun refresh() { state.clear(); likes = null; ratings = null }
    private fun state(name: String) = state.getOrPut(name) { bucketFile(name) }

    private fun key(t: Track): Any = if (t.start == null) t.path else t.path.substringBeforeLast('/') to t.number

    private fun trackOf(filename: String): Track? {
        val cue = filename.startsWith("cue://")
        val f = if (cue) filename.removePrefix("cue://").substringBeforeLast('/') else filename
        for (root in listOf(bucketDir, downloadDir).filter { it.isNotEmpty() }) {
            if (!f.startsWith(root.trimEnd('/') + "/")) continue
            val rel = f.substring(root.trimEnd('/').length + 1)
            return byKey[if (cue) rel.substringBeforeLast('/') to filename.substringAfterLast('/').toIntOrNull() else rel]
        }
        return null
    }

    private fun filename(t: Track): String {
        if (t.start != null) {
            val dir = File(bucketDir, t.path.substringBeforeLast('/'))
            val cue = dir.list()?.filter { it.lowercase().endsWith(".cue") }?.minOrNull()
            return "cue://${File(dir, cue ?: "")}/${t.number}"
        }
        return File(downloadDir, t.path).takeIf { downloadDir.isNotEmpty() && it.exists() }?.path ?: File(bucketDir, t.path).path
    }

    override val intro = """
        You are the assistant of cmus, the terminal music player on the user's Arch Linux computer. It plays the same library
        as B2 Player on the user's phone, with the same likes, ratings, playlists and listening history. Replies are shown as
        plain text in a terminal: no Markdown except simple lists.
    """.trimIndent()

    override val currentId get() = query()["file"]?.let { trackOf(it) }?.id
    override val playing get() = query()["status"] == "playing"
    /** The playing track, then the cmus queue. */
    override val queueIds: List<String> get() {
        // cmus refuses to save the queue while tracks are being added to it
        var saved = cmus("save -q -")
        repeat(50) { if (saved.startsWith("Error:")) { Thread.sleep(100); saved = cmus("save -q -") } }
        val queued = saved.lines().filter { it.startsWith("/") || it.startsWith("cue://") }.mapNotNull { trackOf(it)?.id }
        return listOfNotNull(currentId) + queued
    }
    override val currentIndex = 0

    override fun play(tracks: List<Track>, shuffled: Boolean) {
        val list = if (shuffled) tracks.shuffled() else tracks
        exec("cmus-remote", "-c", "-q")
        if (list.size > 1) cmus(*list.drop(1).map { "add -q ${filename(it)}" }.toTypedArray())
        cmus("player-play ${filename(list[0])}")
    }

    // add -Q puts a track at the head of the queue, so the tracks go in reverse.
    override fun playNext(tracks: List<Track>) { cmus(*tracks.reversed().map { "add -Q ${filename(it)}" }.toTypedArray()) }
    override fun addToEnd(tracks: List<Track>) { cmus(*tracks.map { "add -q ${filename(it)}" }.toTypedArray()) }
    // Each question is a new process: there is no earlier queue to bring back.
    override fun restoreQueue() = false

    /** Added and removed likes, on top of the catalog's flags. */
    private fun likes() = likes ?: state("state/likes.json")?.let { mapper.readTree(it) }.let { j ->
        j?.path("added")?.map { it.asText() }.orEmpty().toSet() to j?.path("removed")?.map { it.asText() }.orEmpty().toSet()
    }.also { likes = it }

    override fun isLiked(t: Track): Boolean {
        val (added, removed) = likes()
        return (t.likedInCatalog || t.id in added) && t.id !in removed
    }

    override fun setLiked(tracks: List<Track>, liked: Boolean) {
        state.remove("state/likes.json"); likes = null
        val (a, r) = likes()
        val added = a.toSortedSet(); val removed = r.toSortedSet()
        for (t in tracks) {
            if (liked) { removed.remove(t.id); if (!t.likedInCatalog) added.add(t.id) }
            else { added.remove(t.id); if (t.likedInCatalog) removed.add(t.id) }
        }
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("version" to 1, "added" to added, "removed" to removed))
        upload("state/likes.json", json)
        state["state/likes.json"] = json; likes = null
        // cmus shows the likes from the file cmus-b2 writes
        runCatching { exec("cmus-b2", "--likes") }
    }

    override val playlists: List<Playlist> get() =
        state("state/playlists.json")?.let { mapper.readTree(it) }?.path("playlists")?.map { p ->
            Playlist(p["id"].asText(), p["name"].asText(), p["tracks"].map { it.asText() }, p.path("updated").asLong())
        }.orEmpty()

    private fun savePlaylists(f: (List<Playlist>) -> List<Playlist>) {
        state.remove("state/playlists.json")
        val list = f(playlists)
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("version" to 1, "playlists" to list.map {
            mapOf("id" to it.id, "name" to it.name, "tracks" to it.trackIds, "updated" to it.updated)
        }))
        upload("state/playlists.json", json)
        state["state/playlists.json"] = json
    }

    override fun createPlaylist(name: String, tracks: List<Track>) =
        savePlaylists { it + Playlist(UUID.randomUUID().toString(), name, tracks.map { t -> t.id }, System.currentTimeMillis()) }

    override fun addToPlaylist(p: Playlist, tracks: List<Track>) = savePlaylists { list ->
        list.map { if (it.id == p.id) it.copy(trackIds = it.trackIds + tracks.map { t -> t.id }.filter { id -> id !in it.trackIds }, updated = System.currentTimeMillis()) else it }
    }

    override fun history(): List<Play> = parseHistory(state("state/history.tsv").orEmpty())

    /** Album id -> {"rating": …}. */
    private fun ratings() = ratings ?: (state("state/ratings.json")?.let { mapper.readTree(it) }?.path("albums") ?: mapper.createObjectNode())
        .also { ratings = it }

    override fun rating(a: Album): Double? = ratings().get(a.id)?.get("rating")?.asDouble()

    /** On top of the bucket copy as "pending", as cmus-b2-rate does; tools/sync-ratings.py writes it to the diary. */
    override fun setRating(a: Album, rating: Int) {
        state.remove("state/ratings.json"); ratings = null
        val albums = ratings().deepCopy<JsonNode>() as ObjectNode
        albums.putObject(a.id).put("rating", rating).put("time", System.currentTimeMillis()).put("pending", true)
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("version" to 1, "albums" to albums))
        upload("state/ratings.json", json)
        state["state/ratings.json"] = json; ratings = null
        // cmus shows the ratings from the file cmus-b2 writes
        runCatching { exec("cmus-b2", "--likes") }
    }

    override fun reply(text: String, error: Boolean) = println(if (error) "\u001b[31m$text\u001b[0m" else "$text\n")

    override suspend fun confirm(change: AssistantTools.Change): AssistantTools.Action? {
        println("\u001b[1m${change.title}\u001b[0m")
        change.tracks.take(12).forEach { println("  ${it.artist} — ${it.title}") }
        if (change.tracks.size > 12) println("  and ${change.tracks.size - 12} more")
        // y takes the first action (Apply, Play), 2 the second one (Play next).
        val more = change.actions.drop(1).withIndex().joinToString("") { (i, a) -> ", ${i + 2} ${a.label.lowercase()}" }
        print("${change.actions[0].label}? [y$more/N] ")
        val answer = readlnOrNull()?.trim()?.lowercase()
        return if (answer in setOf("y", "yes", "д", "да")) change.actions[0] else answer?.toIntOrNull()?.let { change.actions.getOrNull(it - 1) }
    }

    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    override fun log(message: String, error: Throwable?) {
        LOG.appendText("${time.format(Date())} $message${error?.let { "\n" + it.stackTraceToString() }.orEmpty()}\n")
    }
}

fun main(args: Array<String>) {
    // the key cmus-b2-add uses too
    val key = System.getenv("ANTHROPIC_API_KEY") ?: ENV_FILE.takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("ANTHROPIC_API_KEY=") }?.substringAfter('=')?.trim()?.trim('\'', '"')
    if (key.isNullOrEmpty()) {
        System.err.println("Put ANTHROPIC_API_KEY=... in $ENV_FILE")
        exitProcess(1)
    }
    OUT.mkdirs()
    val catalogFile = File(OUT, "catalog.json.gz")
    // rclone copies the catalog only when it changed
    exec("rclone", "copyto", "$REMOTE/catalog.json.gz", catalogFile.path)
    val cat = Catalog.parse(catalogFile)
    val host = Cmus(cat)
    val chat = AssistantChat(host).apply { apiKey = key }
    var next = args.joinToString(" ").takeIf { it.isNotBlank() }
    while (true) {
        val text = next ?: run { print("> "); readlnOrNull() ?: return }
        next = null
        if (text.isBlank()) continue
        host.refresh()
        runBlocking { runCatching { chat.send(cat, text.trim()) }.onFailure { host.reply(it.message ?: it.toString(), error = true) } }
    }
}
