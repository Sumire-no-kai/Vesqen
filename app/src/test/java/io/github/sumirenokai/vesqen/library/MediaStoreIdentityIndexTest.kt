package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaStoreIdentityIndexTest {
    private val songA = TestFile(fileName = "a.flac", sizeBytes = 10_000, durationMs = 200_000)
    private val songB = TestFile(fileName = "b.flac", sizeBytes = 20_000, durationMs = 300_000)
    private val songC = TestFile(fileName = "c.flac", sizeBytes = 30_000, durationMs = 400_000)

    @Test
    fun `a rebuild that swaps two _IDs keeps each row on its own file`() {
        val index = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA), row(2, mediaId = 2, songB)))

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 2, displacedTrackId = 1),
            index.plan(candidate(mediaId = 1, songB)),
        )
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            index.plan(candidate(mediaId = 2, songA)),
        )
    }

    @Test
    fun `a rename keeps the row`() {
        val index = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)))

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            index.plan(candidate(mediaId = 1, songA.copy(fileName = "renamed.flac"))),
        )
    }

    @Test
    fun `a move to another folder keeps the row`() {
        val index = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)))

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            index.plan(candidate(mediaId = 1, songA, folderName = "Music/Moved")),
        )
    }

    @Test
    fun `another song with the same file name in another folder is another file`() {
        // Generic names repeat in every album folder, and a rebuild can shift numbers onto them.
        val firstTrackOfA = songA.copy(fileName = "01.flac")
        val firstTrackOfB = songB.copy(fileName = "01.flac")
        val index = MediaStoreIdentityIndex(
            listOf(
                row(1, mediaId = 1, firstTrackOfA, folderName = "Music/Album A"),
                row(2, mediaId = 2, firstTrackOfB, folderName = "Music/Album B"),
            ),
        )

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 2, displacedTrackId = 1),
            index.plan(candidate(mediaId = 1, firstTrackOfB, folderName = "Music/Album B")),
        )
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            index.plan(candidate(mediaId = 2, firstTrackOfA, folderName = "Music/Album A")),
        )
    }

    @Test
    fun `a tag edit keeps the row`() {
        val index = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)))

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            index.plan(candidate(mediaId = 1, songA.copy(sizeBytes = 12_345))),
        )
    }

    @Test
    fun `a rename together with a tag edit keeps the row`() {
        val index = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)))

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            index.plan(
                candidate(
                    mediaId = 1,
                    songA.copy(fileName = "renamed.flac", sizeBytes = 12_345, durationMs = 200_400),
                ),
            ),
        )
    }

    @Test
    fun `another song on an old _ID becomes a new row and the old row waits for its file`() {
        val index = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)))

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = null, displacedTrackId = 1),
            index.plan(candidate(mediaId = 1, songC)),
        )
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            index.plan(candidate(mediaId = 7, songA)),
        )
    }

    @Test
    fun `a file deleted and copied back keeps its row`() {
        val index = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA), row(2, mediaId = 2, songB)))

        assertEquals(1L, index.unchangedTrackId(candidate(mediaId = 1, songA)))
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 2),
            index.plan(candidate(mediaId = 9, songB)),
        )
    }

    @Test
    fun `an ambiguous match becomes a new row`() {
        // Before Android 10 the folder is only the parent directory's name.
        val index = MediaStoreIdentityIndex(
            listOf(
                row(1, mediaId = 1, songA, folderName = "Album", volumeName = "external"),
                row(2, mediaId = 2, songA, folderName = "Album", volumeName = "external"),
            ),
        )

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = null),
            index.plan(candidate(mediaId = 3, songA, folderName = "Album", volumeName = "external")),
        )
    }

    @Test
    fun `a row seen earlier in the scan or stored on another volume is never reattached`() {
        val index = MediaStoreIdentityIndex(
            listOf(
                row(1, mediaId = 1, songA, folderName = "Album", volumeName = "external"),
                row(2, mediaId = 2, songB, volumeName = "1234-5678"),
            ),
        )

        assertEquals(
            1L,
            index.unchangedTrackId(candidate(mediaId = 1, songA, folderName = "Album", volumeName = "external")),
        )
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = null),
            index.plan(candidate(mediaId = 3, songA, folderName = "Album", volumeName = "external")),
        )
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = null),
            index.plan(candidate(mediaId = 4, songB, volumeName = "external_primary")),
        )
    }

    @Test
    fun `an interrupted scan resumes from the displaced row`() {
        val firstPass = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA), row(2, mediaId = 2, songB)))
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 2, displacedTrackId = 1),
            firstPass.plan(candidate(mediaId = 1, songB)),
        )

        // The scan stops here; the next one starts from what the store committed.
        val committed = listOf(
            row(1, mediaId = 1, songA).copy(remoteId = "mediastore-displaced:1"),
            row(2, mediaId = 1, songB),
        )
        val nextPass = MediaStoreIdentityIndex(committed)

        assertEquals(2L, nextPass.unchangedTrackId(candidate(mediaId = 1, songB)))
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            nextPass.plan(candidate(mediaId = 2, songA)),
        )
    }

    @Test
    fun `only a duration change of a second or more counts toward another file`() {
        val renamedAndEdited = songA.copy(fileName = "other.flac", sizeBytes = 99_999)

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)))
                .plan(candidate(mediaId = 1, renamedAndEdited.copy(durationMs = songA.durationMs + 999))),
        )
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = null, displacedTrackId = 1),
            MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)))
                .plan(candidate(mediaId = 1, renamedAndEdited.copy(durationMs = songA.durationMs - 1_000))),
        )
    }

    @Test
    fun `a changed fingerprint is left to the plan`() {
        val index = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)))

        assertNull(index.unchangedTrackId(candidate(mediaId = 1, songA, fingerprint = "edited")))
        assertNull(index.unchangedTrackId(candidate(mediaId = 2, songA)))
    }

    private data class TestFile(
        val fileName: String,
        val sizeBytes: Long,
        val durationMs: Long,
    )

    private fun row(
        trackId: Long,
        mediaId: Long,
        file: TestFile,
        folderName: String = "Music/Album",
        volumeName: String = "external_primary",
    ) = StoredTrackIdentity(
        trackId = trackId,
        remoteId = mediaStoreRemoteId(volumeName, mediaId),
        contentUri = "content://media/$volumeName/audio/media/$mediaId",
        folderName = folderName,
        fileName = file.fileName,
        sizeBytes = file.sizeBytes,
        durationMs = file.durationMs,
        fingerprint = fingerprint(volumeName, mediaId, file),
    )

    private fun candidate(
        mediaId: Long,
        file: TestFile,
        folderName: String = "Music/Album",
        volumeName: String = "external_primary",
        fingerprint: String = fingerprint(volumeName, mediaId, file),
    ) = LibraryTrackCandidate(
        remoteId = mediaStoreRemoteId(volumeName, mediaId),
        contentUri = "content://media/$volumeName/audio/media/$mediaId",
        title = file.fileName,
        artist = "",
        album = "",
        durationMs = file.durationMs,
        sizeBytes = file.sizeBytes,
        fileName = file.fileName,
        folderName = folderName,
        fingerprint = fingerprint,
    )

    private fun fingerprint(volumeName: String, mediaId: Long, file: TestFile) =
        libraryFingerprint(volumeName, mediaId, file.fileName, file.sizeBytes, file.durationMs)
}
