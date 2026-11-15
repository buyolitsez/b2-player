package dev.buyolitsez.b2player

import com.fasterxml.jackson.databind.JsonNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the assistant can do with the player. Tracks and albums are named by short handles
 * ("t123", "a45": indices in the catalog) so tool results stay small.
 */
class AssistantTools(private val host: AssistantHost, val cat: Catalog) {
    private val trackIndex = cat.tracks.withIndex().associate { (i, t) -> t.id to i }
    private val albumIndex = cat.albums.withIndex().associate { (i, a) -> a.id to i }

    private fun handle(t: Track) = "t${trackIndex[t.id]}"
    private fun handle(a: Album) = "a${albumIndex[a.id]}"
    private fun genre(a: Album) = a.dir.substringBefore('/')

    private fun line(t: Track) = buildString {
        append(handle(t)).append(" | ").append(t.artist).append(" — ").append(t.title)
        append(" | ").append(t.album.title); t.album.year?.let { append(" (").append(it).append(')') }
        append(" | ").append(genre(t.album)).append(" | ").append(formatTime(t.duration))
        if (host.isLiked(t)) append(" | liked")
        host.rating(t.album)?.let { append(" | album rated ").append(formatRating(it)) }
    }

    private fun line(a: Album) = buildString {
        append(handle(a)).append(" | ").append(a.artist).append(" — ").append(a.title)
        a.year?.let { append(" (").append(it).append(')') }
        append(" | ").append(genre(a)).append(" | ").append(a.tracks.size).append(" tracks")
        val liked = a.tracks.count { host.isLiked(it) }
        if (liked > 0) append(", ").append(liked).append(" liked")
        host.rating(a)?.let { append(" | rated ").append(formatRating(it)) }
    }

    /** Tracks named by handles; an album handle stands for all its tracks. */
    fun resolve(arr: JsonNode): List<Track> =
        arr.flatMap { node ->
            val h = node.asText().trim()
            val n = h.drop(1).toIntOrNull()
            when {
                n == null -> emptyList()
                h.startsWith("t") -> listOfNotNull(cat.tracks.getOrNull(n))
                h.startsWith("a") -> cat.albums.getOrNull(n)?.tracks.orEmpty()
                else -> emptyList()
            }
        }.distinctBy { it.id }

    /** One line about the library for the system prompt: genres and artists with album counts. */
    fun librarySummary(): String {
        val genres = cat.albums.groupingBy { genre(it) }.eachCount().entries.sortedByDescending { it.value }
            .joinToString { "${it.key} (${it.value})" }
        val artists = cat.artists.joinToString { (name, albums) -> "$name (${albums.size})" }
        return "${cat.albums.size} albums, ${cat.tracks.size} tracks.\nGenres (top folders, album counts): $genres\n" +
            "Artists (album counts): $artists"
    }

    /** Short state of the player, added to each user message. */
    fun playerState(): String {
        val now = SimpleDateFormat("yyyy-MM-dd EEE HH:mm", Locale.US).format(Date())
        val cur = host.currentId?.let { cat.byId[it] }
        val queue = host.queueIds.size
        return "[$now. " + (if (cur != null) "Now ${if (host.playing) "playing" else "paused"}: ${line(cur)}. Queue: $queue tracks, at ${host.currentIndex + 1}" else "Nothing is playing") + "]"
    }

    /** [fq] is already folded, once per call: the filters run over every track. */
    private fun matches(field: String, fq: String?) = fq == null || fq in searchFold(field)

    private fun JsonNode.str(k: String) = path(k).asText("").takeIf { it.isNotBlank() }

    fun searchTracks(a: JsonNode): String {
        val text = a.str("text")?.let(::searchFold); val artist = a.str("artist")?.let(::searchFold)
        val album = a.str("album")?.let(::searchFold); val genre = a.str("genre")?.let(::searchFold)
        val playlist = a.str("playlist")?.let { name -> host.playlists.firstOrNull { it.name.equals(name, true) } ?: return "No playlist named “$name”" }
        val inPlaylist = playlist?.trackIds?.toHashSet()
        val likedOnly = a.path("liked_only").asBoolean()
        val limit = a.path("limit").asInt(100).coerceIn(1, 400)
        val found = cat.tracks.filter { t ->
            (inPlaylist == null || t.id in inPlaylist) && (!likedOnly || host.isLiked(t)) &&
                matches(t.artist + " " + t.album.artist, artist) && matches(t.album.title, album) && matches(genre(t.album), genre) &&
                (text == null || text in t.searchKey || text in t.album.searchKey)
        }
        return result(found.size, found.take(limit).map { line(it) })
    }

    fun searchAlbums(a: JsonNode): String {
        val text = a.str("text")?.let(::searchFold); val artist = a.str("artist")?.let(::searchFold); val genre = a.str("genre")?.let(::searchFold)
        val from = a.path("year_from").asInt(0); val to = a.path("year_to").asInt(9999)
        val rated = a.path("rated").asText("any"); val minRating = a.path("min_rating").asDouble(0.0)
        val limit = a.path("limit").asInt(100).coerceIn(1, 400)
        val found = cat.albums.filter { al ->
            val r = host.rating(al)
            matches(al.artist, artist) && matches(genre(al), genre) && (text == null || text in al.searchKey) &&
                (al.year ?: 0) in from..to && (r ?: 0.0) >= minRating &&
                when (rated) { "rated" -> r != null; "unrated" -> r == null; else -> true }
        }.let { list -> if (a.path("sort").asText() == "rating") list.sortedByDescending { host.rating(it) ?: 0.0 } else list }
        return result(found.size, found.take(limit).map { line(it) })
    }

    private fun result(total: Int, lines: List<String>) =
        if (total == 0) "Nothing found" else "$total found" + (if (lines.size < total) ", showing ${lines.size}" else "") + "\n" + lines.joinToString("\n")

    /** Adding to the queue happens at once; replacing it waits for the user, who can also play the tracks next. */
    fun play(a: JsonNode): Any {
        val tracks = resolve(a.path("items"))
        if (tracks.isEmpty()) return "No such tracks or albums"
        when (a.path("mode").asText("replace")) {
            "next" -> { host.playNext(tracks); return "OK, ${tracks.size} tracks play next" }
            "end" -> { host.addToEnd(tracks); return "OK, ${tracks.size} tracks added to the end" }
        }
        val shuffle = a.path("shuffle").asBoolean()
        val album = tracks.first().album.takeIf { al -> tracks.all { it.album === al } }
        val what = album?.let { "${it.artist} — ${it.title} · " }.orEmpty() + count(tracks.size)
        return Change((if (shuffle) "Shuffle " else "Play ") + what, tracks, listOf(
            Action("Play", "Playing") { host.play(tracks, shuffle); "Playing: $what" },
            Action("Play next", "Playing next") { host.playNext(tracks); "The user chose to play them after the current track instead: $what" },
        ))
    }

    fun restoreQueue(): String =
        if (host.restoreQueue()) "Brought back the previous queue" else "There is no previous queue to bring back"

    fun queue(): String {
        val ids = host.queueIds
        if (ids.isEmpty()) return "The queue is empty"
        val from = host.currentIndex
        return "Queue of ${ids.size}, current is #${from + 1}. Upcoming:\n" +
            ids.drop(from).take(50).mapNotNull { cat.byId[it] }.joinToString("\n") { line(it) }
    }

    fun playlists(): String =
        host.playlists.joinToString("\n") { "${it.name} | ${it.trackIds.size} tracks" }.ifEmpty { "No playlists" }

    /** A change shown to the user before it is applied: they pick one of [actions] or cancel. */
    class Change(val title: String, val tracks: List<Track>, val actions: List<Action>) {
        constructor(title: String, tracks: List<Track>, apply: () -> String) : this(title, tracks, listOf(Action("Apply", "Applied", apply)))
    }

    /** A button of a [Change]: [done] is shown once it is tapped, [run] returns the tool result. */
    class Action(val label: String, val done: String, val run: () -> String)

    fun setLiked(a: JsonNode): Any {
        val liked = a.path("liked").asBoolean(true)
        val tracks = resolve(a.path("items")).filter { host.isLiked(it) != liked }
        if (tracks.isEmpty()) return "Nothing to change: all of them are already ${if (liked) "liked" else "not liked"}"
        val title = (if (liked) "Like " else "Remove like from ") + count(tracks.size)
        return Change(title, tracks) { host.setLiked(tracks, liked); "Done: $title" }
    }

    fun createPlaylist(a: JsonNode): Any {
        val name = a.path("name").asText("").trim().ifEmpty { return "The playlist needs a name" }
        val tracks = resolve(a.path("items"))
        return Change("Create playlist “$name” with ${count(tracks.size)}", tracks) { host.createPlaylist(name, tracks); "Created “$name”" }
    }

    fun addToPlaylist(a: JsonNode): Any {
        val name = a.path("playlist").asText("")
        val p = host.playlists.firstOrNull { it.name.equals(name, true) } ?: return "No playlist named “$name”"
        val tracks = resolve(a.path("items")).filter { it.id !in p.trackIds }
        if (tracks.isEmpty()) return "They are all in “${p.name}” already"
        return Change("Add ${count(tracks.size)} to “${p.name}”", tracks) { host.addToPlaylist(p, tracks); "Added to “${p.name}”" }
    }

    fun stats(a: JsonNode): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val since = a.str("since")?.let { runCatching { day.parse(it)!!.time }.getOrNull() } ?: 0L
        val end = a.str("until")?.let { runCatching { day.parse(it)!!.time + 86_400_000 }.getOrNull() } ?: Long.MAX_VALUE
        val by = a.path("group_by").asText("track")
        val limit = a.path("limit").asInt(50).coerceIn(1, 300)
        val plays = host.history().filter { it.time >= since && it.time < end }.mapNotNull { p -> cat.byId[p.trackId]?.let { it to p.time } }
        if (plays.isEmpty()) return "No plays in this period"
        val groups = plays.groupBy { (t, _) ->
            when (by) { "artist" -> t.artist; "album" -> handle(t.album) + " | " + t.album.artist + " — " + t.album.title + (host.rating(t.album)?.let { " | rated " + formatRating(it) }.orEmpty()); else -> line(t) }
        }.map { (k, v) -> Triple(k, v.size, v.maxOf { it.second }) }
        val sorted = if (a.path("sort").asText() == "oldest") groups.sortedBy { it.third } else groups.sortedByDescending { it.second }
        return "${plays.size} plays\n" + sorted.take(limit).joinToString("\n") { (k, n, last) -> "$k | $n plays, last ${day.format(Date(last))}" }
    }

    fun setRating(a: JsonNode): Any {
        val h = a.path("album").asText("").trim()
        val album = h.drop(1).toIntOrNull()?.let { n -> if (h.startsWith("a")) cat.albums.getOrNull(n) else cat.tracks.getOrNull(n)?.album }
            ?: return "No such album"
        val rating = a.path("rating").asInt(0).takeIf { it in 1..10 } ?: return "The rating is a whole number from 1 to 10"
        if (host.rating(album) == rating.toDouble()) return "It is rated $rating already"
        val title = "Rate ${album.artist} — ${album.title}${album.year?.let { " ($it)" }.orEmpty()}: $rating"
        return Change(title, emptyList()) { host.setRating(album, rating); "Done: $title" }
    }

    private fun count(n: Int) = if (n == 1) "1 track" else "$n tracks"

    /** Runs a tool: returns its text result, or a [Change] that waits for the user's answer. */
    fun run(name: String, input: JsonNode): Any = when (name) {
        "search_tracks" -> searchTracks(input)
        "search_albums" -> searchAlbums(input)
        "play" -> play(input)
        "get_queue" -> queue()
        "restore_queue" -> restoreQueue()
        "list_playlists" -> playlists()
        "set_liked" -> setLiked(input)
        "create_playlist" -> createPlaylist(input)
        "add_to_playlist" -> addToPlaylist(input)
        "listening_stats" -> stats(input)
        "set_rating" -> setRating(input)
        else -> "Unknown tool $name"
    }

    companion object {
        private const val ITEMS = """"items": {"type": "array", "items": {"type": "string"}, "description": "Track handles (t…) and album handles (a…, meaning all its tracks)"}"""

        /** name, description, JSON schema of the input. */
        val DEFINITIONS = listOf(
            Triple("search_tracks", "Find tracks in the library. All filters are optional, case- and accent-insensitive substrings; combine them. Returns handles to use with other tools.",
                """{"type": "object", "properties": {
                    "text": {"type": "string", "description": "Matches title, artist or album"},
                    "artist": {"type": "string"}, "album": {"type": "string"},
                    "genre": {"type": "string", "description": "Top folder of the library"},
                    "playlist": {"type": "string", "description": "Only tracks of this playlist (exact name)"},
                    "liked_only": {"type": "boolean", "description": "Only when the user asks for liked tracks"},
                    "limit": {"type": "integer", "description": "Default 100, max 400"}}}"""),
            Triple("search_albums", "Find albums. Filters are optional substrings. Rated albums show the user's rating.",
                """{"type": "object", "properties": {
                    "text": {"type": "string", "description": "Matches album title or artist"},
                    "artist": {"type": "string"}, "genre": {"type": "string"},
                    "year_from": {"type": "integer"}, "year_to": {"type": "integer"},
                    "rated": {"type": "string", "enum": ["any", "rated", "unrated"], "description": "Default any"},
                    "min_rating": {"type": "number", "description": "Only albums rated at least this"},
                    "sort": {"type": "string", "enum": ["library", "rating"], "description": "rating: highest rated first"},
                    "limit": {"type": "integer", "description": "Default 100, max 400"}}}"""),
            Triple("play", "Play tracks or albums. mode replace (default) replaces the queue: the user sees what you picked and taps Play, Play next or Cancel, and the result says which; next inserts after the current track and end appends to the queue, both at once.",
                """{"type": "object", "properties": {$ITEMS,
                    "mode": {"type": "string", "enum": ["replace", "next", "end"]},
                    "shuffle": {"type": "boolean", "description": "Only with replace"}}, "required": ["items"]}"""),
            Triple("get_queue", "The current queue from the playing track on (up to 50).", """{"type": "object", "properties": {}}"""),
            Triple("restore_queue", "Bring back the queue that was playing before the last time it was replaced, at the same track and position.",
                """{"type": "object", "properties": {}}"""),
            Triple("list_playlists", "The user's playlists with track counts.", """{"type": "object", "properties": {}}"""),
            Triple("set_liked", "Like or unlike tracks. The user sees the list and confirms or cancels; the result says which.",
                """{"type": "object", "properties": {$ITEMS, "liked": {"type": "boolean"}}, "required": ["items", "liked"]}"""),
            Triple("create_playlist", "Create a playlist. The user confirms first.",
                """{"type": "object", "properties": {"name": {"type": "string"}, $ITEMS}, "required": ["name", "items"]}"""),
            Triple("add_to_playlist", "Add tracks to an existing playlist (exact name). The user confirms first.",
                """{"type": "object", "properties": {"playlist": {"type": "string"}, $ITEMS}, "required": ["playlist", "items"]}"""),
            Triple("listening_stats", "Listening history grouped by track, album or artist: play counts and last play date. Dates are yyyy-MM-dd, both optional. sort plays (most played first, default) or oldest (least recently played first). Items never played are not listed.",
                """{"type": "object", "properties": {
                    "group_by": {"type": "string", "enum": ["track", "album", "artist"]},
                    "since": {"type": "string"}, "until": {"type": "string"},
                    "sort": {"type": "string", "enum": ["plays", "oldest"]},
                    "limit": {"type": "integer", "description": "Default 50, max 300"}}}"""),
            Triple("set_rating", "Rate an album 1 to 10, when the user gives it a score. The user confirms first. The rating goes to the user's listening diary.",
                """{"type": "object", "properties": {
                    "album": {"type": "string", "description": "Album handle (a…), or a track handle for its album"},
                    "rating": {"type": "integer", "minimum": 1, "maximum": 10}}, "required": ["album", "rating"]}"""),
        )
    }
}
