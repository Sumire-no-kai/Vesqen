package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreAudioRepositoryTest {
    @Test
    fun `api 29 and newer scan every concrete external volume in stable order`() {
        assertEquals(
            listOf("1234-5678", "external_primary"),
            mediaStoreScanVolumeNames(
                sdkInt = 29,
                externalVolumeNames = setOf("external_primary", "1234-5678"),
            ),
        )
        assertEquals(
            listOf("external"),
            mediaStoreScanVolumeNames(
                sdkInt = 28,
                externalVolumeNames = setOf("ignored-on-legacy"),
            ),
        )
    }

    @Test
    fun `every concrete volume identity is scoped while pre api 29 keeps legacy ids`() {
        assertEquals("42", mediaStoreRemoteId("external", 42))
        assertFalse(mediaStoreRemoteId("external_primary", 42) == "42")
        assertFalse(mediaStoreRemoteId("1234-5678", 42) == "42")
        assertFalse(
            mediaStoreRemoteId("1234-5678", 42) == mediaStoreRemoteId("8765-4321", 42),
        )
        assertFalse(
            mediaStoreRemoteId("external_primary", 42) == mediaStoreRemoteId("1234-5678", 42),
        )
    }

    @Test
    fun `legacy migration is attempted only for concrete volume scans`() {
        assertFalse(
            mediaStoreScanUsesConcreteVolumes(
                MediaStoreScanScope(listOf("external"), generation = null),
            ),
        )
        assertTrue(
            mediaStoreScanUsesConcreteVolumes(
                MediaStoreScanScope(listOf("external_primary"), generation = "1"),
            ),
        )
    }

    @Test
    fun `pruning covers only concrete volumes scanned in this pass`() {
        assertEquals(
            setOf("external_primary", "1234-5678"),
            mediaStorePrunableVolumeNames(listOf("external_primary", "1234-5678")),
        )
        assertEquals(
            setOf("1234-5678"),
            mediaStorePrunableVolumeNames(listOf("1234-5678")),
        )
    }

    @Test
    fun `retained rows are visible only while their MediaStore volume is mounted`() {
        val mounted = listOf("external_primary", "1234-5678")

        assertTrue(
            mediaStoreTrackIsMounted(
                "content://media/external_primary/audio/media/1",
                mounted,
            ),
        )
        assertTrue(
            mediaStoreTrackIsMounted(
                "content://media/external/audio/media/2",
                listOf("external"),
            ),
        )
        assertFalse(mediaStoreTrackIsMounted("content://media/external/audio/media/2", mounted))
        assertTrue(
            mediaStoreTrackIsMounted("content://media/1234-5678/audio/media/3", mounted),
        )
        assertFalse(
            mediaStoreTrackIsMounted("content://media/8765-4321/audio/media/4", mounted),
        )
        assertTrue(
            mediaStoreTrackIsMounted(
                "content://com.android.providers.media.documents/document/audio%3A5",
                mounted,
            ),
        )
    }

    @Test
    fun `completed scan can commit only while volume generations remain unchanged`() {
        val initialScope = MediaStoreScanScope(
            volumeNames = listOf("external_primary"),
            generation = "generation-1",
        )
        val completed = ScanIterationResult(completed = true, processedTrackCount = 3)

        assertTrue(mediaStoreScanCanCommit(completed, initialScope, initialScope))
        assertFalse(
            mediaStoreScanCanCommit(
                completed,
                initialScope,
                initialScope.copy(generation = "generation-2"),
            ),
        )
        assertFalse(
            mediaStoreScanCanCommit(
                completed,
                initialScope,
                initialScope.copy(volumeNames = listOf("external_primary", "1234-5678")),
            ),
        )
        assertFalse(
            mediaStoreScanCanCommit(
                completed.copy(completed = false),
                initialScope,
                initialScope,
            ),
        )
    }

    @Test
    fun `legacy folder fallback keeps only the immediate parent label`() {
        assertEquals(
            "Album",
            legacyMediaFolderName("/storage/emulated/0/Music/Artist/Album/song.flac"),
        )
        assertEquals("Music", legacyMediaFolderName("/storage/emulated/0/Music/song.mp3"))
        assertEquals("Album", legacyMediaFolderName("C:\\Music\\Album\\song.wav"))
        assertEquals("", legacyMediaFolderName("song.opus"))
    }
}
