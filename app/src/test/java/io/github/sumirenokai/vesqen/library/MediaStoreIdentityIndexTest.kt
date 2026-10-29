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
    fun `a rename together with a tag edit keeps the row while MediaStore keeps its database`() {
        val index = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)), volumesWithStableIds = PRIMARY)

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
            MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)), volumesWithStableIds = PRIMARY)
                .plan(candidate(mediaId = 1, renamedAndEdited.copy(durationMs = songA.durationMs + 999))),
        )
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = null, displacedTrackId = 1),
            MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)), volumesWithStableIds = PRIMARY)
                .plan(candidate(mediaId = 1, renamedAndEdited.copy(durationMs = songA.durationMs - 1_000))),
        )
    }

    @Test
    fun `where _IDs may be renumbered the same _ID needs the path or two of path, size and duration`() {
        fun plan(file: TestFile, folderName: String = "Music/Album") =
            MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)))
                .plan(candidate(mediaId = 1, file, folderName = folderName))

        assertEquals(MediaStoreTrackPlan(targetTrackId = 1), plan(songA.copy(sizeBytes = 12_345, durationMs = 260_000)))
        assertEquals(MediaStoreTrackPlan(targetTrackId = 1), plan(songA.copy(fileName = "renamed.flac")))
        assertEquals(MediaStoreTrackPlan(targetTrackId = 1), plan(songA, folderName = "Music/Moved"))
        // Only the duration is left after a rename together with a tag edit, as with another song.
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = null, displacedTrackId = 1),
            plan(songA.copy(fileName = "renamed.flac", sizeBytes = 12_345, durationMs = 200_400)),
        )
    }

    @Test
    fun `an _ID is trusted only on a volume whose database is unchanged`() {
        val x = TestFile(fileName = "x.flac", sizeBytes = 10_000, durationMs = 200_000)
        val y = TestFile(fileName = "y.flac", sizeBytes = 20_000, durationMs = 200_400)
        val index = MediaStoreIdentityIndex(
            rows = listOf(row(1, mediaId = 1, x), row(2, mediaId = 1, x, volumeName = "1234-5678")),
            volumesWithStableIds = PRIMARY,
        )

        assertEquals(MediaStoreTrackPlan(targetTrackId = 1), index.plan(candidate(mediaId = 1, y)))
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = null, displacedTrackId = 2),
            index.plan(candidate(mediaId = 1, y, volumeName = "1234-5678")),
        )
    }

    @Test
    fun `an _ID listed at the end keeps an unseen row only for its own file where _IDs may be renumbered`() {
        // A rebuilding MediaStore lists new files during the scan; b's old _ID now names one of them.
        val rows = listOf(row(1, mediaId = 1, songA), row(2, mediaId = 2, songB))
        val atEnd = listOf(listed(mediaId = 1, songA), listed(mediaId = 2, songC))

        assertEquals(
            setOf(mediaStoreRemoteId("external_primary", 1)),
            MediaStoreIdentityIndex(rows).idsStillNamingTheirRows(atEnd),
        )
        // In the same database an _ID names one entry, whose file may have been edited.
        assertEquals(
            atEnd.mapTo(HashSet(), ListedMediaStoreFile::remoteId),
            MediaStoreIdentityIndex(rows, volumesWithStableIds = PRIMARY).idsStillNamingTheirRows(atEnd),
        )
    }

    @Test
    fun `a changed fingerprint is left to the plan`() {
        val index = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, songA)))

        assertNull(index.unchangedTrackId(candidate(mediaId = 1, songA, fingerprint = "edited")))
        assertNull(index.unchangedTrackId(candidate(mediaId = 2, songA)))
    }

    @Test
    fun `the exact file wins over a near duration on the same _ID`() {
        // Two songs within a second of each other swap _IDs in a rebuild (#143 Q1).
        val x = TestFile(fileName = "x.flac", sizeBytes = 10_000, durationMs = 200_000)
        val y = TestFile(fileName = "y.flac", sizeBytes = 20_000, durationMs = 200_400)
        val index = MediaStoreIdentityIndex(listOf(row(1, mediaId = 1, x), row(2, mediaId = 2, y)))

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 2, displacedTrackId = 1),
            index.plan(candidate(mediaId = 1, y)),
        )
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            index.plan(candidate(mediaId = 3, x)),
        )
    }

    @Test
    fun `a near duration does not take a row whose file is still listed under another _ID`() {
        // y is new and gets x's old _ID; x itself is renumbered later in the scan (#143 Q1).
        val x = TestFile(fileName = "x.flac", sizeBytes = 10_000, durationMs = 200_000)
        val y = TestFile(fileName = "y.flac", sizeBytes = 20_000, durationMs = 200_400)
        val index = MediaStoreIdentityIndex(
            rows = listOf(row(1, mediaId = 1, x)),
            listedFiles = listOf(listed(mediaId = 1, y), listed(mediaId = 3, x)),
        )

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = null, displacedTrackId = 1),
            index.plan(candidate(mediaId = 1, y)),
        )
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            index.plan(candidate(mediaId = 3, x)),
        )
    }

    @Test
    fun `after a rebuild a new song does not take a row whose file is not listed yet`() {
        // MediaStore lists only part of the files so far. A new song got x's old _ID; x itself is
        // listed later in the scan, or by the next one (#159).
        val x = TestFile(fileName = "x.flac", sizeBytes = 10_000, durationMs = 200_000)
        val nearDuration = TestFile(fileName = "y.flac", sizeBytes = 20_000, durationMs = 200_400)
        val sameSize = TestFile(fileName = "y.flac", sizeBytes = 10_000, durationMs = 300_000)
        for (y in listOf(nearDuration, sameSize)) {
            val index = MediaStoreIdentityIndex(
                rows = listOf(row(1, mediaId = 1, x)),
                listedFiles = listOf(listed(mediaId = 1, y, folderName = "Music/New")),
            )

            assertEquals(
                MediaStoreTrackPlan(targetTrackId = null, displacedTrackId = 1),
                index.plan(candidate(mediaId = 1, y, folderName = "Music/New")),
            )
            assertEquals(MediaStoreTrackPlan(targetTrackId = 1), index.plan(candidate(mediaId = 7, x)))
        }
    }

    @Test
    fun `an unchanged path and size on the same _ID keep the row even beside an identical copy`() {
        // Before Android 10 two album folders can share their last name, so a copied album lists
        // the same folder, name and size twice.
        val copy = row(2, mediaId = 2, songA, folderName = "CD1", volumeName = "external")
        val index = MediaStoreIdentityIndex(
            rows = listOf(row(1, mediaId = 1, songA, folderName = "CD1", volumeName = "external"), copy),
            listedFiles = listOf(
                listed(mediaId = 1, songA, folderName = "CD1", volumeName = "external"),
                listed(mediaId = 2, songA, folderName = "CD1", volumeName = "external"),
            ),
            pathIdentifiesFile = false,
        )

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            index.plan(candidate(mediaId = 1, songA, folderName = "CD1", volumeName = "external", fingerprint = "retagged")),
        )
    }

    @Test
    fun `before Android 10 a tag edit keeps its row beside an identical copy on other storage`() {
        // Music/Album/x.flac on the phone and Album/x.flac on an SD card share a key there; editing
        // the phone copy's tags changes its size, while the SD copy is still listed under its own row.
        val index = MediaStoreIdentityIndex(
            rows = listOf(
                row(1, mediaId = 1, songA, folderName = "Album", volumeName = "external"),
                row(2, mediaId = 2, songA, folderName = "Album", volumeName = "external"),
            ),
            listedFiles = listOf(
                listed(mediaId = 1, songA.copy(sizeBytes = songA.sizeBytes + 512), folderName = "Album", volumeName = "external"),
                listed(mediaId = 2, songA, folderName = "Album", volumeName = "external"),
            ),
            pathIdentifiesFile = false,
        )

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            index.plan(
                candidate(mediaId = 1, songA.copy(sizeBytes = songA.sizeBytes + 512), folderName = "Album", volumeName = "external"),
            ),
        )
    }

    @Test
    fun `before Android 10 a copy's leftover row does not hand this row to another song after a rebuild`() {
        // The SD copy's row (2) stays hidden after the card is removed. After a rebuild the phone's
        // file is listed under _ID 2, and another song within a second of it gets this row's _ID 1.
        val other = TestFile(fileName = "y.flac", sizeBytes = 55_555, durationMs = songA.durationMs + 400)
        val index = MediaStoreIdentityIndex(
            rows = listOf(
                row(1, mediaId = 1, songA, folderName = "Album", volumeName = "external"),
                row(2, mediaId = 2, songA, folderName = "Album", volumeName = "external"),
            ),
            listedFiles = listOf(
                listed(mediaId = 1, other, folderName = "Other", volumeName = "external"),
                listed(mediaId = 2, songA, folderName = "Album", volumeName = "external"),
            ),
            pathIdentifiesFile = false,
        )

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = null, displacedTrackId = 1),
            index.plan(candidate(mediaId = 1, other, folderName = "Other", volumeName = "external")),
        )
    }

    @Test
    fun `a rename keeps the row while the old name is no longer listed`() {
        val renamed = songA.copy(fileName = "renamed.flac")
        val index = MediaStoreIdentityIndex(
            rows = listOf(row(1, mediaId = 1, songA)),
            listedFiles = listOf(listed(mediaId = 1, renamed)),
        )

        assertEquals(MediaStoreTrackPlan(targetTrackId = 1), index.plan(candidate(mediaId = 1, renamed)))
    }

    @Test
    fun `before Android 10 the same folder and file name alone do not keep a row`() {
        // The folder is only the parent directory's name there, so Album A/CD1/01.flac and
        // Album B/CD1/01.flac look alike (#143 Q6).
        val firstOfA = TestFile(fileName = "01.flac", sizeBytes = 10_000, durationMs = 200_000)
        val firstOfB = TestFile(fileName = "01.flac", sizeBytes = 20_000, durationMs = 300_000)
        fun legacyIndex() = MediaStoreIdentityIndex(
            rows = listOf(row(1, mediaId = 1, firstOfA, folderName = "CD1", volumeName = "external")),
            pathIdentifiesFile = false,
        )

        assertEquals(
            MediaStoreTrackPlan(targetTrackId = null, displacedTrackId = 1),
            legacyIndex().plan(candidate(mediaId = 1, firstOfB, folderName = "CD1", volumeName = "external")),
        )
        // A tag edit still keeps its row there: the duration matches.
        assertEquals(
            MediaStoreTrackPlan(targetTrackId = 1),
            legacyIndex().plan(
                candidate(mediaId = 1, firstOfA.copy(sizeBytes = 12_345), folderName = "CD1", volumeName = "external"),
            ),
        )
    }

    private companion object {
        val PRIMARY = setOf("external_primary")
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

    private fun listed(
        mediaId: Long,
        file: TestFile,
        folderName: String = "Music/Album",
        volumeName: String = "external_primary",
    ) = ListedMediaStoreFile(
        remoteId = mediaStoreRemoteId(volumeName, mediaId),
        contentUri = "content://media/$volumeName/audio/media/$mediaId",
        folderName = folderName,
        fileName = file.fileName,
        sizeBytes = file.sizeBytes,
    )

    private fun fingerprint(volumeName: String, mediaId: Long, file: TestFile) =
        libraryFingerprint(volumeName, mediaId, file.fileName, file.sizeBytes, file.durationMs)
}
