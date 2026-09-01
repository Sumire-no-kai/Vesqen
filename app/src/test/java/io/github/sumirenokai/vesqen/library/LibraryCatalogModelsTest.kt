package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogModelsTest {
    @Test
    fun `SAF audio recognition accepts provider MIME types and conservative filename fallbacks`() {
        assertTrue(isSupportedAudioDocument("audio/flac", "track"))
        assertTrue(isSupportedAudioDocument("application/octet-stream", "track.OPUS"))
        assertTrue(isSupportedAudioDocument("application/ogg", "track.ogg"))
        assertFalse(isSupportedAudioDocument("text/plain", "notes.txt"))
    }

    @Test
    fun `folder source identity preserves the complete tree URI`() {
        val primary = LibrarySourceId.forTree("content://provider/tree/primary%3AMusic")
        val sdCard = LibrarySourceId.forTree("content://provider/tree/1234-5678%3AMusic")

        assertNotEquals(primary, sdCard)
        assertTrue(primary.startsWith("tree:content://"))
    }

    @Test
    fun `catalog fingerprints retain field boundaries without hashing`() {
        assertNotEquals(
            libraryFingerprint("a", "bc"),
            libraryFingerprint("ab", "c"),
        )
    }
}
