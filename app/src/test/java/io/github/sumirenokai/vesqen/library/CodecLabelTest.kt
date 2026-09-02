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
}
