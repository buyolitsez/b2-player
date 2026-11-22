package dev.buyolitsez.b2player

data class Track(
    val path: String, val title: String, val artist: String, val album: Album,
    val number: Int?, val disc: Int?, val duration: Double, val start: Double?, val size: Long,
    val likedInCatalog: Boolean, val hasLyrics: Boolean = false,
    /** Size of the light Opus copy of the file (lite/<path>.opus in the bucket), 0 when there is none. */
    val liteSize: Long = 0,
) {
    /** Unique per track; CUE tracks share a file, so the start offset is part of the id. */
    val id get() = if (start == null) path else "$path#$start"
    val ext get() = path.substringAfterLast('.', "").lowercase()
    val searchKey by lazy { searchFold("$title\u0000$artist") }
}

private val MARKS = Regex("\\p{Mn}+")
private val CYRILLIC = "абвгдезиклмнопрстуфхцчшщъыьэюя".toList().zip(
    listOf("a", "b", "v", "g", "d", "e", "z", "i", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya")
).toMap() + mapOf('ж' to "zh")

/**
 * Lowercase without accents and in Latin letters, so "bjork" finds "Björk", "ежик" finds "ёжик" and "kino" finds "Кино".
 * Display text stays as it is; this only builds keys to compare.
 */
fun searchFold(s: String): String {
    val plain = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "")
    if (plain.none { it in CYRILLIC }) return plain
    return buildString { for (c in plain) CYRILLIC[c]?.let(::append) ?: append(c) }
}

/** The words of [s]: runs of letters and digits. A regex split here took most of the time of filtering 18k tracks. */
private fun splitWords(s: String): List<String> {
    val out = ArrayList<String>()
    var start = -1
    for (i in 0..s.length) {
        val inWord = i < s.length && s[i].isLetterOrDigit()
        if (inWord && start < 0) start = i
        if (!inWord && start >= 0) { out.add(s.substring(start, i)); start = -1 }
    }
    return out
}

/**
 * What was typed in a search field. A key matches when each typed word is found in it: as is, or as the start of
 * one of its words with a typo or two (a missed, extra, wrong or swapped letter), so "metalica" finds "Metallica".
 */
class Query(text: String) {
    val folded = searchFold(text.trim())
    private val words = splitWords(folded)

    fun isEmpty() = words.isEmpty()

    /** 0 the key starts with the query, 1 every word starts a word of the key, 2 found inside, 3 with typos; null no match. */
    fun rank(key: String): Int? {
        if (words.isEmpty() || key.startsWith(folded)) return 0
        var keyWords: List<String>? = null
        var squeezed: String? = null
        var best = 1
        for (w in words) {
            val kw = keyWords ?: splitWords(key).also { keyWords = it }
            // Without spaces and punctuation, so "darkside" finds "Dark Side"; the fields (title, artist…) stay apart.
            val sq = squeezed ?: key.filter { it.isLetterOrDigit() || it == '\u0000' }.also { squeezed = it }
            best = maxOf(best, when {
                kw.any { it.startsWith(w) } -> 1
                w in key || w in sq -> 2
                kw.any { typos(w, it) <= w.length / 4 } -> 3
                else -> return null
            })
        }
        return best
    }

    fun matches(key: String) = rank(key) != null
}

/** Edits (a letter inserted, deleted, replaced, or two neighbours swapped) to turn [w] into the start of [key]. */
private fun typos(w: String, key: String): Int {
    if (w.length < 4) return Int.MAX_VALUE
    // d[i][j]: edits from the first i letters of w to the first j letters of key.
    val d = Array(w.length + 1) { i -> IntArray(key.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
    for (i in 1..w.length) for (j in 1..key.length) {
        val same = if (w[i - 1] == key[j - 1]) 0 else 1
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + same)
        if (i > 1 && j > 1 && w[i - 1] == key[j - 2] && w[i - 2] == key[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
    }
    return d[w.length].min()
}

/** Items matching [q], best first; with an empty query, all of them. */
fun <T> List<T>.search(q: Query, key: (T) -> String): List<T> =
    if (q.isEmpty()) this else mapNotNull { x -> q.rank(key(x))?.let { x to it } }.sortedBy { it.second }.map { it.first }

/** Items matching [q] in their own order. Matches with typos come after the others, so "love" lists "live" only at the end. */
fun <T> List<T>.filterBy(q: Query, key: (T) -> String): List<T> {
    if (q.isEmpty()) return this
    val ranked = mapNotNull { x -> q.rank(key(x))?.let { x to it } }
    return ranked.sortedBy { it.second == 3 }.map { it.first }
}

class Album(
    val id: String, val dir: String, val title: String, val artist: String, val year: Int?, val cover: String?,
    val added: Long, val genre: String?,
) {
    lateinit var tracks: List<Track>
    val duration get() = tracks.sumOf { it.duration }
    /** "Rock | Hard Rock", "Indie / Pop" and "IDM, Experimental" are several genres; the album is in each. */
    val genres = genre?.split(GENRE_SEPARATORS)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    /** With the genre, so searching "jazz" also finds the jazz albums. */
    val searchKey by lazy { searchFold("$title\u0000$artist\u0000${genre.orEmpty()}") }
}

private val GENRE_SEPARATORS = Regex("[|/,;]")
private val GENRE_SPELLING = Regex("[-\\s]")

class Folder(val name: String, val path: String) {
    val folders = sortedMapOf<String, Folder>(String.CASE_INSENSITIVE_ORDER)
    val tracks = ArrayList<Track>()
    val allTracks: List<Track> by lazy { tracks + folders.values.flatMap { it.allTracks } }
}

class Catalog(val albums: List<Album>) {
    val tracks = albums.flatMap { it.tracks }
    val byId = tracks.associateBy { it.id }
    val artists: List<Pair<String, List<Album>>> =
        albums.groupBy { it.artist.lowercase() }.values
            .map { list -> list.groupingBy { it.artist }.eachCount().maxBy { it.value }.key to list }
            .sortedBy { it.first.lowercase() }
    /**
     * Albums by genre tag; albums without one come last under an empty name. Spellings that differ only in case,
     * hyphens and spaces ("Post Punk", "Post-punk") are one genre, named by the most common of them.
     */
    val genres: List<Pair<String, List<Album>>> =
        albums.flatMap { a -> a.genres.ifEmpty { listOf("") }.map { it to a } }.groupBy { it.first.lowercase().replace(GENRE_SPELLING, "") }.values
            .map { list -> list.groupingBy { it.first }.eachCount().maxBy { it.value }.key to list.map { it.second }.distinct() }
            .sortedWith(compareBy({ it.first.isEmpty() }, { it.first.lowercase() }))
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

    /** Catalog.parse is the app's and the cli's own: Android has a faster JSON reader. */
    companion object
}

data class Playlist(val id: String, val name: String, val trackIds: List<String>, val updated: Long)

/**
 * Three-way merge of playlists: the phone's unsent edits meet the ones made elsewhere (cmus) since the last sync,
 * [base]. A playlist changed on one side takes that side, also when it was deleted there. Changed on both, it keeps
 * the local name and order, minus the tracks the other side removed, plus the ones it added. A playlist deleted on
 * one side and edited on the other stays.
 */
fun mergePlaylists(base: List<Playlist>, local: List<Playlist>, remote: List<Playlist>): List<Playlist> {
    val b = base.associateBy { it.id }; val l = local.associateBy { it.id }; val r = remote.associateBy { it.id }
    fun same(x: Playlist?, y: Playlist?) = x?.name == y?.name && x?.trackIds == y?.trackIds
    return (local.map { it.id } + remote.map { it.id }).distinct().mapNotNull { id ->
        val was = b[id]; val mine = l[id]; val theirs = r[id]
        when {
            same(mine, was) -> theirs
            same(theirs, was) -> mine
            mine == null -> theirs
            theirs == null -> mine
            else -> {
                val before = was?.trackIds.orEmpty()
                val removed = before.toSet() - theirs.trackIds.toSet()
                val added = theirs.trackIds.filter { it !in before && it !in mine.trackIds }
                mine.copy(
                    name = if (mine.name != was?.name) mine.name else theirs.name,
                    trackIds = mine.trackIds.filter { it !in removed } + added,
                    updated = maxOf(mine.updated, theirs.updated),
                )
            }
        }
    }
}

/** A counted play. [device] is where it was played: "phone", "mac", "arch", or "" for plays from before it was kept. */
data class Play(val trackId: String, val time: Long, val device: String = "")

/**
 * state/history.tsv: "ms<TAB>track id", and since the device was kept, "<TAB>device" after it. Apps from before that
 * read the third column as part of the track id, so such lines don't count there but survive their merges.
 */
fun parseHistory(s: String): List<Play> = s.lineSequence().mapNotNull { l ->
    val f = l.split('\t'); if (f.size < 2 || f[1].isEmpty()) null else f[0].toLongOrNull()?.let { Play(f[1], it, f.getOrElse(2) { "" }) }
}.toList()

fun formatHistory(list: List<Play>) = list.joinToString("") { "${it.time}\t${it.trackId}${if (it.device.isEmpty()) "" else "\t" + it.device}\n" }

/** Both lists in time order, each play once; a play known with its device and without it keeps the device. */
fun mergeHistory(a: List<Play>, b: List<Play>) =
    (a + b).sortedWith(compareBy<Play>({ it.time }, { it.trackId }, { it.device.isEmpty() })).distinctBy { it.time to it.trackId }

fun formatTime(sec: Double): String {
    val s = sec.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** 7.0 as "7", 7.5 as "7.5". */
fun formatRating(r: Double) = if (r % 1 == 0.0) r.toInt().toString() else r.toString()
