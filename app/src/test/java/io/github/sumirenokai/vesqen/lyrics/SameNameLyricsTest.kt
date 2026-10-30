package io.github.sumirenokai.vesqen.lyrics

import io.github.sumirenokai.vesqen.library.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SameNameLyricsTest {
    @Test
    fun theExactNameWinsThenAnyCase() {
        val candidates = listOf(
            LyricsFileCandidate("a", "aira.LRC"),
            LyricsFileCandidate("b", "Aira.lrc"),
            LyricsFileCandidate("c", "Other.lrc"),
        )

        assertEquals("b", sameNameLyricsDocumentId("Aira.flac", candidates))
        // Several matches in other cases: the first by name, so the choice never depends on the provider.
        assertEquals("b", sameNameLyricsDocumentId("AIRA.mp3", candidates))
        assertEquals("b", sameNameLyricsDocumentId("AIRA.mp3", candidates.reversed()))
        assertEquals("c", sameNameLyricsDocumentId("Other.m4a", candidates))
    }

    @Test
    fun onlyTheExtensionOfTheAudioFileIsReplaced() {
        val candidates = listOf(
            LyricsFileCandidate("dots", "01. Song.live.lrc"),
            LyricsFileCandidate("prefix", "Song.lrc.lrc"),
        )

        assertEquals("dots", sameNameLyricsDocumentId("01. Song.live.flac", candidates))
        assertNull(sameNameLyricsDocumentId("Song.flac", candidates))
        assertNull(sameNameLyricsDocumentId("no-extension", listOf(LyricsFileCandidate("x", "no-extension.lrc"))))
        assertNull(sameNameLyricsDocumentId("", candidates))
    }

    @Test
    fun theLyricsKeyIgnoresPlaysAndFavorites() {
        val track = AudioTrack(1, "content://tree/1", "Title", "Artist", "Album", 1_000, fileName = "Title.flac")

        assertEquals(track.lyricsKey(3), track.copy(playCount = 9, isFavorite = true, lastPlayedAtMs = 5).lyricsKey(3))
        assertNotEquals(track.lyricsKey(3), track.lyricsKey(4))
        assertNotEquals(track.lyricsKey(3), track.copy(dateModifiedSeconds = 2).lyricsKey(3))
        assertNotEquals(track.lyricsKey(3), track.copy(contentUri = "content://tree/2").lyricsKey(3))
    }
}
