package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
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

    @Test
    fun `provider generations include the app metadata revision`() {
        val volume = MediaStoreVolumeVersion("external_primary", "db-v1", 42)
        assertEquals(
            libraryFingerprint(LIBRARY_METADATA_REVISION, libraryFingerprint("external_primary", "db-v1", 42)),
            libraryScanGeneration(listOf(volume)),
        )
    }

    @Test
    fun `database rebuild invalidates a coincidentally equal generation`() {
        val volume = MediaStoreVolumeVersion("external_primary", "db-v1", 42)
        assertNotEquals(
            libraryScanGeneration(listOf(volume)),
            libraryScanGeneration(listOf(volume.copy(databaseVersion = "db-v2"))),
        )
    }

    @Test
    fun `mounted volume set and each volume generation invalidate the merged library`() {
        val primary = MediaStoreVolumeVersion("external_primary", "primary-v1", 100)
        val sdCard = MediaStoreVolumeVersion("sd-card", "card-v1", 42)
        val baseline = libraryScanGeneration(listOf(primary, sdCard))

        assertNotEquals(baseline, libraryScanGeneration(listOf(primary)))
        assertNotEquals(baseline, libraryScanGeneration(listOf(primary, sdCard.copy(generation = 43))))
        assertNotEquals(baseline, libraryScanGeneration(listOf(primary, sdCard.copy(volumeName = "other-card"))))
        assertEquals(baseline, libraryScanGeneration(listOf(sdCard, primary)))
    }
}
