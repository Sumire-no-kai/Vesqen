package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CodecLabelTest {
    @Test
    fun `codec labels cover every M1 core and common lossy format`() {
        assertEquals("FLAC", codecLabel("audio/flac", "song.bin"))
        assertEquals("ALAC", codecLabel("audio/alac", "song.m4a"))
        assertEquals("WAV", codecLabel("audio/wav", "song.wav"))
        assertEquals("AIFF", codecLabel("audio/aiff", "song.aiff"))
        assertEquals("MP3", codecLabel("audio/mpeg", "song.mp3"))
        assertEquals("AAC", codecLabel("audio/mp4a-latm", "song.m4a"))
        assertEquals("Ogg Vorbis", codecLabel("audio/vorbis", "song.ogg"))
        assertEquals("Opus", codecLabel("audio/opus", "song.opus"))
    }

    @Test
    fun `an m4a file whose codec the system cannot read is not called AAC`() {
        // Android 9 reads no codec from an ALAC .m4a, and the system type says only the container.
        assertEquals("M4A", codecLabel("audio/mp4", "song.m4a"))
        assertEquals("M4A", codecLabel("audio/quicktime", "song.m4a"))
        assertEquals("M4A", codecLabel("", "song.m4a"))
        assertEquals("AAC", codecLabel("", "song.aac"))
    }
}
