package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M1FormatMatrixTest {
    @Test
    fun `matrix contains every M1 family and PCM depth contract`() {
        assertEquals(
            setOf("flac", "alac", "wav", "aiff", "mp3", "aac", "vorbis", "opus"),
            M1_AUDIO_FORMAT_MATRIX.map(M1AudioFormat::id).toSet(),
        )
        M1_AUDIO_FORMAT_MATRIX.filter(M1AudioFormat::lossless).forEach { format ->
            assertTrue("${format.id} must cover the M1 integer PCM depths", format.requiredPcmBitDepths.containsAll(setOf(16, 24, 32)))
        }
    }

    @Test
    fun `format lookup uses MIME first and conservative extension fallback`() {
        assertEquals("FLAC", m1FormatFor("audio/flac", "unknown.bin")?.displayName)
        assertEquals("AIFF", m1FormatFor("application/octet-stream", "track.AIFF")?.displayName)
        assertEquals(null, m1FormatFor("text/plain", "notes.txt"))
    }
}
