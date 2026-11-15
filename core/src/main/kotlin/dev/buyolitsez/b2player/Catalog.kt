package dev.buyolitsez.b2player

data class Track(
    val path: String, val title: String, val artist: String, val album: Album,
    val number: Int?, val disc: Int?, val duration: Double, val start: Double?, val size: Long,
    val likedInCatalog: Boolean, val hasLyrics: Boolean = false,
) {
    /** Unique per track; CUE tracks share a file, so the start offset is part of the id. */
    val id get() = if (start == null) path else "$path#$start"
    val ext get() = path.substringAfterLast('.', "").lowercase()
    val searchKey by lazy { searchFold("$title\u0000$artist") }
}

private val MARKS = Regex("\\p{Mn}+")

/** Lowercase without accents, so "bjork" finds "Björk" and "ежик" finds "ёжик". */
fun searchFold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "")

class Album(
    val id: String, val dir: String, val title: String, val artist: String, val year: Int?, val cover: String?,
    val added: Long, val genre: String?,
) {
    lateinit var tracks: List<Track>
    val duration get() = tracks.sumOf { it.duration }
    val searchKey by lazy { searchFold("$title\u0000$artist") }
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
        albums.groupBy { it.artist.lowercase() }.values
            .map { list -> list.groupingBy { it.artist }.eachCount().maxBy { it.value }.key to list }
            .sortedBy { it.first.lowercase() }
    /** Albums by genre tag; albums without one come last under an empty name. */
    val genres: List<Pair<String, List<Album>>> =
        albums.groupBy { it.genre?.lowercase().orEmpty() }.values
            .map { list -> list.groupingBy { it.genre.orEmpty() }.eachCount().maxBy { it.value }.key to list }
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
