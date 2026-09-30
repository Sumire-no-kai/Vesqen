package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTrackTest {
    @Test
    fun `display subtitle omits empty and unknown metadata`() {
        val track = AudioTrack(
            id = 1,
            contentUri = "content://media/external/audio/media/1",
            title = "Track",
            artist = "<unknown>",
            album = "Album",
            durationMs = 1_000,
        )

        assertEquals("Album", track.displaySubtitle())
    }

    @Test
    fun `media store placeholder and null tags read as missing`() {
        assertEquals("", normalizedTag("<unknown>"))
        assertEquals("", normalizedTag(null))
        assertEquals("", normalizedTag(""))
        // Only MediaStore's exact placeholder is missing; real names that merely look similar are kept.
        assertEquals("Unknown", normalizedTag("Unknown"))
        assertEquals("<Unknown>", normalizedTag("<Unknown>"))
        assertEquals("Maren Holt", normalizedTag("Maren Holt"))
    }
}
