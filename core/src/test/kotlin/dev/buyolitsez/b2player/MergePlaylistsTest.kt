package dev.buyolitsez.b2player

import kotlin.test.Test
import kotlin.test.assertEquals

class MergePlaylistsTest {
    private fun p(id: String, vararg tracks: String, name: String = id) = Playlist(id, name, tracks.toList(), 0)

    @Test fun keepsEditsFromBothSides() {
        val base = listOf(p("a", "1", "2", "3"))
        val local = listOf(p("a", "3", "1", "2", "4"))       // reordered and added 4 on the phone
        val remote = listOf(p("a", "1", "3", "5", name = "A")) // renamed, removed 2, added 5 in cmus
        assertEquals(listOf(p("a", "3", "1", "4", "5", name = "A")), mergePlaylists(base, local, remote))
    }

    @Test fun deletionWinsOnlyOverAnUnchangedPlaylist() {
        val base = listOf(p("a", "1"), p("b", "1"))
        val local = listOf(p("a", "1"), p("b", "1", "2"))
        val remote = listOf(p("c", "9"))                      // cmus deleted a and b and created c
        assertEquals(listOf(p("b", "1", "2"), p("c", "9")), mergePlaylists(base, local, remote))
    }
}
