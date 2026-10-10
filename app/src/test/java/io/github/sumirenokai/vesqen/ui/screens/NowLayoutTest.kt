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
    fun tallPhoneShowsACoverAsWideAsTheContentAndTheShrunkCoverWithNotes() {
        // A 360 dp phone with 24 dp side margins has 312 dp of content width.
        val layout = nowPortraitLayout(700.dp, 312.dp, 1f)
        assertFalse(layout.compact)
        assertEquals(312.dp, layout.artwork)
        assertTrue(layout.notesArtwork in 96.dp..164.dp)
    }

    @Test
    fun coverNeverExceedsTheSpecifiedSizes() {
        val layout = nowPortraitLayout(2_000.dp, 600.dp, 1f)
        assertEquals(360.dp, layout.artwork)
        assertEquals(164.dp, layout.notesArtwork)
    }

    @Test
    fun coverFollowsWhicheverOfHeightAndWidthIsTighter() {
        // A short phone: the height budget limits the cover below the content width.
        val short = nowPortraitLayout(560.dp, 312.dp, 1f)
        assertTrue(short.artwork < 312.dp)
        // A narrow phone: the content width limits the cover below the height budget.
        assertEquals(272.dp, nowPortraitLayout(900.dp, 272.dp, 1f).artwork)
    }

    @Test
    fun shortWindowsAndHugeTextDropTheCoverInsteadOfOverflowing() {
        val tiny = nowPortraitLayout(300.dp, 272.dp, 2f)
        assertTrue(tiny.compact)
        assertEquals(0.dp, tiny.artwork)
        assertEquals(0.dp, tiny.notesArtwork)
        // Large text still keeps a real cover when the compact layout leaves room for one.
        val largeText = nowPortraitLayout(480.dp, 272.dp, 2f)
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
