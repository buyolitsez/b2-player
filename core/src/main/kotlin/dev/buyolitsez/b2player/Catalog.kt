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

private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

/**
 * What was typed in a search field. A key matches when each typed word is found in it: as is, or as the start of
 * one of its words with a typo or two (a missed, extra, wrong or swapped letter), so "metalica" finds "Metallica".
 */
class Query(text: String) {
    val folded = searchFold(text.trim())
    private val words = folded.split(NON_WORD).filter { it.isNotEmpty() }

    fun isEmpty() = words.isEmpty()

    /** 0 the key starts with the query, 1 every word starts a word of the key, 2 found inside, 3 with typos; null no match. */
    fun rank(key: String): Int? {
        if (words.isEmpty() || key.startsWith(folded)) return 0
        var keyWords: List<String>? = null
        var best = 1
        for (w in words) {
            val kw = keyWords ?: key.split(NON_WORD).also { keyWords = it }
            best = maxOf(best, when {
                kw.any { it.startsWith(w) } -> 1
                w in key || w in kw.joinToString("") -> 2
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

/** Items matching [q] in their own order. Matches with typos are left out when there are ones without, so "love" does not also list "live". */
fun <T> List<T>.filterBy(q: Query, key: (T) -> String): List<T> {
    if (q.isEmpty()) return this
    val ranked = mapNotNull { x -> q.rank(key(x))?.let { x to it } }
    val exact = ranked.filter { it.second < 3 }
    return (exact.ifEmpty { ranked }).map { it.first }
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

data class Play(val trackId: String, val time: Long)

fun formatTime(sec: Double): String {
    val s = sec.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** 7.0 as "7", 7.5 as "7.5". */
fun formatRating(r: Double) = if (r % 1 == 0.0) r.toInt().toString() else r.toString()
