package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.library.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowLayoutTest {
    @Test
    fun tallPhoneShowsTheFullCoverAndTheShrunkCoverWithNotes() {
        val layout = nowPortraitLayout(700.dp, 1f)
        assertFalse(layout.compact)
        assertEquals(280.dp, layout.artwork)
        assertTrue(layout.notesArtwork in 96.dp..164.dp)
    }

    @Test
    fun coverNeverExceedsTheSpecifiedSizes() {
        val layout = nowPortraitLayout(2_000.dp, 1f)
        assertEquals(280.dp, layout.artwork)
        assertEquals(164.dp, layout.notesArtwork)
    }

    @Test
    fun shortWindowsAndHugeTextDropTheCoverInsteadOfOverflowing() {
        val tiny = nowPortraitLayout(300.dp, 2f)
        assertTrue(tiny.compact)
        assertEquals(0.dp, tiny.artwork)
        assertEquals(0.dp, tiny.notesArtwork)
        // Large text still keeps a real cover when the compact layout leaves room for one.
        val largeText = nowPortraitLayout(480.dp, 2f)
        assertTrue(largeText.compact)
        assertTrue(largeText.artwork >= 64.dp)
    }

    @Test
    fun formatSummaryUsesCatalogMetadataOnly() {
        val flac = track(codec = "FLAC", bitDepth = 24, sampleRateHz = 96_000)
        assertEquals("FLAC 24/96", nowFormatSummary(flac))
        assertEquals("ALAC 16/44.1", nowFormatSummary(track(codec = "ALAC", bitDepth = 16, sampleRateHz = 44_100)))
        assertEquals("MP3 44.1 kHz", nowFormatSummary(track(codec = "MP3", sampleRateHz = 44_100)))
        assertEquals("Opus", nowFormatSummary(track(codec = "Opus")))
        assertNull(nowFormatSummary(track(codec = "")))
        assertNull(nowFormatSummary(null))
    }

    private fun track(codec: String, bitDepth: Int? = null, sampleRateHz: Int? = null) = AudioTrack(
        id = 1,
        contentUri = "content://media/1",
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 1_000,
        codec = codec,
        bitDepth = bitDepth,
        sampleRateHz = sampleRateHz,
    )
}
