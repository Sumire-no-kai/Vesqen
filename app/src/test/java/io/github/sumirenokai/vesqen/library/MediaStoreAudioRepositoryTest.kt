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
    fun `completed scan can commit only while volumes and their databases remain`() {
        val initialScope = MediaStoreScanScope(
            volumeNames = listOf("external_primary"),
            generation = "generation-1",
            databaseVersions = mapOf("external_primary" to "version-1"),
        )
        val completed = ScanIterationResult(completed = true, processedTrackCount = 3)

        assertTrue(mediaStoreScanCanCommit(completed, initialScope, initialScope))
        // A photo or a screenshot during a long scan no longer interrupts it (#136 R15).
        assertTrue(
            mediaStoreScanCanCommit(
                completed,
                initialScope,
                initialScope.copy(generation = "generation-2"),
            ),
        )
        // A rebuilt MediaStore can list nothing yet for files it still holds.
        assertFalse(
            mediaStoreScanCanCommit(
                completed,
                initialScope,
                initialScope.copy(generation = "generation-2", databaseVersions = mapOf("external_primary" to "version-2")),
            ),
        )
        // Before Android 11 only the volumes are known.
        val legacyScope = MediaStoreScanScope(volumeNames = listOf("external"), generation = null)
        assertTrue(mediaStoreScanCanCommit(completed, legacyScope, legacyScope))
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
    fun `_IDs stay stable only on volumes whose database the last completed scan read`() {
        val primary = MediaStoreVolumeVersion("external_primary", "primary-v1", 100)
        val sdCard = MediaStoreVolumeVersion("1234-5678", "card-v1", 42)
        val completed = libraryScanGeneration(listOf(primary, sdCard))
        fun scope(vararg volumes: MediaStoreVolumeVersion) = MediaStoreScanScope(
            volumeNames = volumes.map(MediaStoreVolumeVersion::volumeName),
            generation = libraryScanGeneration(volumes.toList()),
            databaseVersions = volumes.associate { it.volumeName to it.databaseVersion },
        )

        // New files move only the generation.
        assertEquals(
            setOf("external_primary", "1234-5678"),
            mediaStoreVolumesWithStableIds(completed, scope(primary.copy(generation = 101), sdCard)),
        )
        // The card's MediaStore was rebuilt, and another card was not part of the last scan.
        assertEquals(
            setOf("external_primary"),
            mediaStoreVolumesWithStableIds(
                completed,
                scope(primary, sdCard.copy(databaseVersion = "card-v2"), MediaStoreVolumeVersion("9999-0000", "other-v1", 1)),
            ),
        )
        // No scan has completed, or the device reports no versions (before Android 11).
        assertEquals(emptySet<String>(), mediaStoreVolumesWithStableIds(null, scope(primary)))
        assertEquals(
            emptySet<String>(),
            mediaStoreVolumesWithStableIds(null, MediaStoreScanScope(volumeNames = listOf("external"), generation = null)),
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
