package io.github.sumirenokai.vesqen.ui.screens

import io.github.sumirenokai.vesqen.library.LibraryPlaylist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistNameTest {
    private val playlists = listOf(LibraryPlaylist(1, "Rock", emptyList(), 0, 0), LibraryPlaylist(2, "Night", emptyList(), 0, 0))

    @Test fun `a name already used in any case is taken, the playlist's own name is not`() {
        assertTrue(playlistNameTaken("rock", playlists, except = null))
        assertTrue(playlistNameTaken(" Night ", playlists, except = 1))
        assertFalse(playlistNameTaken("Rock", playlists, except = 1))
        assertFalse(playlistNameTaken("Jazz", playlists, except = null))
    }
}
