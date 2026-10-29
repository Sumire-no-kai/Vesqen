package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `a generation gives back each volume's database version`() {
        // MediaStore's versions look like "1234:uuid"; the colon must not split them.
        val primary = MediaStoreVolumeVersion("external_primary", "1301:2f9c-41aa", 100)
        val sdCard = MediaStoreVolumeVersion("1234-5678", "1301:77b0-0c12", 42)

        assertEquals(
            mapOf("external_primary" to "1301:2f9c-41aa", "1234-5678" to "1301:77b0-0c12"),
            libraryScanDatabaseVersions(libraryScanGeneration(listOf(primary, sdCard))),
        )
        assertEquals(emptyMap<String, String>(), libraryScanDatabaseVersions(libraryScanGeneration(emptyList())))
        assertNull(libraryScanDatabaseVersions("legacy-generation"))
        assertNull(libraryScanDatabaseVersions("1:69:x"))
        assertNull(libraryScanDatabaseVersions(libraryFingerprint(LIBRARY_METADATA_REVISION, "volume")))
    }
}
