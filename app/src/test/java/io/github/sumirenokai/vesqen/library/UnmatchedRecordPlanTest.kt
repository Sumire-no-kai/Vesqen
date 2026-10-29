package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** #139: which hidden rows wait for the user to choose their song, and among which listed rows. */
class UnmatchedRecordPlanTest {
    /** The device source's last scan completed as scan [SCAN]. */
    private val completed = mapOf(LibrarySourceId.DEVICE to SCAN, FOLDER to SCAN)

    /** A device row on Android 9's single volume, where the folder is only the last directory's name. */
    private fun device(id: Long, name: String = "01.flac", size: Long = 1_000, seen: Long = SCAN, data: Boolean = false) =
        RecordTrackIdentity(
            trackId = id,
            sourceId = LibrarySourceId.DEVICE,
            contentUri = "content://media/external/audio/media/$id",
            folderName = "CD1",
            fileName = name,
            sizeBytes = size,
            modifiedSeconds = 100,
            seenEpoch = seen,
            holdsUserState = data,
        )

    /** A row hidden after scan [SCAN] - 2 missed it, with user data unless [data] is false. */
    private fun hidden(id: Long, name: String = "01.flac", size: Long = 1_000, seen: Long = SCAN - 2, data: Boolean = true) =
        device(id, name, size, seen, data)

    @Test
    fun `two hidden rows and two listed rows with one file key wait for a choice among both`() {
        val plan = planUnmatchedRecords(
            hidden = listOf(hidden(1), hidden(2)),
            listed = listOf(device(11), device(12), device(13, name = "02.flac")),
            completedScanEpochs = completed,
        )

        assertEquals(mapOf(1L to listOf(11L, 12L), 2L to listOf(11L, 12L)), plan)
    }

    @Test
    fun `a key shared by one hidden and several listed rows, or several hidden and one listed, is ambiguous`() {
        assertEquals(
            mapOf(1L to listOf(11L, 12L)),
            planUnmatchedRecords(listOf(hidden(1)), listOf(device(11), device(12)), completed),
        )
        assertEquals(
            mapOf(1L to listOf(11L), 2L to listOf(11L)),
            planUnmatchedRecords(listOf(hidden(1), hidden(2)), listOf(device(11)), completed),
        )
    }

    @Test
    fun `a one to one key and a row with no listed match stay with the scans`() {
        assertEquals(
            emptyMap<Long, List<Long>>(),
            planUnmatchedRecords(
                hidden = listOf(hidden(1), hidden(2, name = "lost.flac")),
                listed = listOf(device(11), device(12, size = 2_000)),
                completedScanEpochs = completed,
            ),
        )
    }

    @Test
    fun `a row waits only after two scans missed it, the last one completed, and with user data`() {
        val listed = listOf(device(11), device(12))

        assertEquals(emptyMap<Long, List<Long>>(), planUnmatchedRecords(listOf(hidden(1, seen = SCAN - 1)), listed, completed))
        assertEquals(emptyMap<Long, List<Long>>(), planUnmatchedRecords(listOf(hidden(1, data = false)), listed, completed))
        // The device's last scan was paused, interrupted or failed, or is still running.
        assertEquals(emptyMap<Long, List<Long>>(), planUnmatchedRecords(listOf(hidden(1)), listed, emptyMap()))
    }

    @Test
    fun `rows on another volume or in another source do not share a key`() {
        val otherVolume = device(11).copy(contentUri = "content://media/1234-5678/audio/media/11")
        val folderRow = device(12).copy(sourceId = FOLDER, contentUri = "content://tree/document/12")

        assertEquals(
            emptyMap<Long, List<Long>>(),
            planUnmatchedRecords(listOf(hidden(1)), listOf(otherVolume, folderRow), completed),
        )
    }

    @Test
    fun `an added folder matches by file name, size and modified time`() {
        fun folder(id: Long, modified: Long, seen: Long = SCAN, data: Boolean = false) = device(id, seen = seen, data = data)
            .copy(sourceId = FOLDER, contentUri = "content://tree/document/$id", folderName = "Album $id", modifiedSeconds = modified)

        assertEquals(
            mapOf(1L to listOf(11L, 12L)),
            planUnmatchedRecords(
                hidden = listOf(folder(1, modified = 100, seen = SCAN - 2, data = true)),
                listed = listOf(folder(11, modified = 100), folder(12, modified = 100), folder(13, modified = 200)),
                completedScanEpochs = completed,
            ),
        )
    }

    private companion object {
        const val SCAN = 5L
        const val FOLDER = "tree:content://tree"
    }
}
