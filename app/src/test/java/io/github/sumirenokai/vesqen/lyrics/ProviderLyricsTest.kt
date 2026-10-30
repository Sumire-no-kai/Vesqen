package io.github.sumirenokai.vesqen.lyrics

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #191 D3: a documents provider's failure, whatever it throws, means "could not be read". */
class ProviderLyricsTest {
    @Test
    fun whatAProviderThrowsWhenOpenedReadsAsNothing() {
        listOf(
            IllegalStateException("provider"),
            UnsupportedOperationException("provider"),
            IllegalArgumentException("provider"),
            NullPointerException("provider died"),
            SecurityException("no grant"),
            IOException("gone"),
        ).forEach { failure ->
            assertNull(failure.toString(), readProviderLyrics { throw failure })
        }
        assertNull(readProviderLyrics { null })
    }

    @Test
    fun whatAProviderThrowsWhileReadReadsAsNothing() {
        assertNull(readProviderLyrics { FailingInputStream(IllegalStateException("provider")) })
        assertNull(readProviderLyrics { FailingInputStream(IOException("pipe closed")) })
    }

    @Test
    fun aReadableFileIsReadUpToOneBytePastTheLimit() {
        val lyrics = "[00:01.00]line".toByteArray()
        assertArrayEquals(lyrics, readProviderLyrics { ByteArrayInputStream(lyrics) })
        val oversized = ByteArray(MAX_LYRICS_TEXT_BYTES * 2)
        assertEquals(MAX_LYRICS_TEXT_BYTES + 1, readProviderLyrics { ByteArrayInputStream(oversized) }?.size)
    }

    private class FailingInputStream(private val failure: Exception) : InputStream() {
        override fun read(): Int = throw failure
    }
}
