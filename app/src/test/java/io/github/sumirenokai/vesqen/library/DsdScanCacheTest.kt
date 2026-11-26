package io.github.sumirenokai.vesqen.library

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #249: DSD rows stored before Vesqen could read DSD are read again, and no other track's scan
 * cache key changes.
 */
@RunWith(RobolectricTestRunner::class)
class DsdScanCacheTest {
    @Test
    fun `only DSD files carry the DSD revision`() {
        assertTrue(dsdFingerprintFields("album.flac", "audio/flac").isEmpty())
        assertTrue(dsdFingerprintFields("notes.txt", "application/octet-stream").isEmpty())
        for ((name, mime) in listOf("a.dsf" to "audio/dsd", "a.DFF" to "", "a.bin" to "audio/x-dsf", "a.dff" to null)) {
            assertEquals("$name $mime", listOf("dsd", DSD_METADATA_REVISION), dsdFingerprintFields(name, mime).toList())
        }
    }

    @Test
    fun `a folder track keeps its key unless it is DSD`() {
        val flac = document("album.flac", "audio/flac")
        assertEquals(plainKey(flac), flac.fingerprint)
        val dsf = document("album.dsf", "audio/dsd")
        assertNotEquals(plainKey(dsf), dsf.fingerprint)
        assertEquals(libraryFingerprint(*plainFields(dsf), "dsd", DSD_METADATA_REVISION), dsf.fingerprint)
    }

    /**
     * On Android 11 and later an unchanged MediaStore generation ends the device scan before any
     * track key is compared, so the DSD revision is part of the generation as well.
     */
    @Test
    fun `the device library is scanned once more for the DSD revision`() {
        val volume = MediaStoreVolumeVersion("external_primary", "db-v1", 42)
        val stored = libraryFingerprint(LIBRARY_METADATA_REVISION, libraryFingerprint(volume.volumeName, volume.databaseVersion, volume.generation))
        val current = libraryScanGeneration(listOf(volume))
        assertNotEquals(stored, current)
        // The volumes' database versions still come out of the stored and the new generation alike.
        assertEquals(mapOf("external_primary" to "db-v1"), libraryScanDatabaseVersions(stored))
        assertEquals(mapOf("external_primary" to "db-v1"), libraryScanDatabaseVersions(current))
    }

    private fun document(name: String, mime: String) = TreeAudioDocument(
        documentId = "primary:Music/$name",
        contentUri = Uri.parse("content://provider/document/$name"),
        displayName = name,
        folderName = "Music",
        mimeType = mime,
        lastModifiedMs = 1_700_000_000_000,
        sizeBytes = 1_000,
    )

    /** The key every folder track had before #249. */
    private fun plainFields(document: TreeAudioDocument): Array<Any?> = with(document) {
        arrayOf("tree", LIBRARY_METADATA_REVISION, documentId, contentUri, displayName, mimeType, lastModifiedMs, sizeBytes, albumArtworkUri, albumArtworkFingerprint)
    }

    private fun plainKey(document: TreeAudioDocument) = libraryFingerprint(*plainFields(document))
}
