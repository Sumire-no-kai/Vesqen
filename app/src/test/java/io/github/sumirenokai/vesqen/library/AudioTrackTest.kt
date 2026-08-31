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
}
