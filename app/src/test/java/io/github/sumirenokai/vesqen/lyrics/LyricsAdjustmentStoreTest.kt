package io.github.sumirenokai.vesqen.lyrics

import io.github.sumirenokai.vesqen.library.CatalogTrackIds
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsAdjustmentStoreTest {
    private val directory = Files.createTempDirectory("lyrics-adjustments").toFile()
    private val store = LyricsStore(directory)
    private val content = revision("[00:01.00]one")
    private val replacement = revision("[00:01.00]other")

    @After
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun actionsAccumulateAndResetWithoutChangingTheImportedFile() {
        val bytes = "[offset:500]\n[00:01.00]one".toByteArray()
        store.import(7, bytes, 0)
        assertEquals(LyricsAdjustmentResult.APPLIED, adjust(7, LyricsAdjustmentAction.ADVANCE))
        assertEquals(LyricsAdjustmentResult.APPLIED, adjust(7, LyricsAdjustmentAction.ADVANCE))
        assertEquals(1_000L, store.userAdvance(7, content))
        assertEquals(LyricsAdjustmentResult.APPLIED, adjust(7, LyricsAdjustmentAction.DELAY))
        assertEquals(500L, store.userAdvance(7, content))
        assertEquals(bytes.toList(), store.read(7)!!.toList())

        assertEquals(LyricsAdjustmentResult.APPLIED, adjust(7, LyricsAdjustmentAction.RESET))
        assertEquals(0L, store.userAdvance(7, content))
        assertEquals(listOf("7.lrc"), directory.list()!!.toList())
    }

    @Test
    fun calibrationPersistsOnlyForTheSameCatalogIdSourceAndContent() {
        adjust(7, LyricsAdjustmentAction.ADVANCE)
        val reopened = LyricsStore(directory)

        assertEquals(500L, reopened.userAdvance(7, content))
        assertEquals(0L, reopened.userAdvance(8, content))
        assertEquals(0L, reopened.userAdvance(7, replacement))
        val otherSource = (parseLyrics("[00:01.00]one") as Lyrics.Synced).contentRevision(LyricsSource.Embedded)
        assertEquals(0L, reopened.userAdvance(7, otherSource))
        reopened.load()
        assertTrue(reopened.importedLyrics.value.trackIds.isEmpty())
    }

    @Test
    fun successivePreparedActionsAccumulateWithoutTreatingACalibrationAsNewContent() {
        val epoch = store.contentEpoch(7)
        repeat(2) {
            assertEquals(LyricsAdjustmentResult.APPLIED, store.adjust(
                7, content, LyricsAdjustmentAction.ADVANCE, store.generation(), epoch,
            ))
        }
        // Importing another track does not invalidate this track's pending operation either.
        store.import(8, "[00:01.00]other".toByteArray(), store.generation())
        assertEquals(LyricsAdjustmentResult.APPLIED, store.adjust(
            7, content, LyricsAdjustmentAction.DELAY, store.generation(), epoch,
        ))
        assertEquals(500L, store.userAdvance(7, content))
    }

    @Test
    fun oldGenerationAndChangesBetweenResolutionAndCommitRefuseTheWrite() {
        val generation = store.generation()
        val revision = store.contentEpoch(7)
        store.clearAll()
        assertEquals(
            LyricsAdjustmentResult.TRACK_CHANGED,
            store.adjust(7, content, LyricsAdjustmentAction.ADVANCE, generation, revision),
        )
        val currentRevision = store.contentEpoch(7)
        store.import(7, "[00:01.00]replacement".toByteArray(), store.generation())
        assertEquals(
            LyricsAdjustmentResult.LYRICS_CHANGED,
            store.adjust(7, content, LyricsAdjustmentAction.ADVANCE, store.generation(), currentRevision),
        )
        assertEquals(0L, store.userAdvance(7, content))
    }

    @Test
    fun writeFailureKeepsThePreviousValueAndPublishedRevision() {
        adjust(7, LyricsAdjustmentAction.ADVANCE)
        val revision = store.importedLyrics.value.revision
        val obstruction = File(directory, "7.$content.adjustment.tmp")
        assertTrue(obstruction.mkdir())
        File(obstruction, "occupied").writeText("keep")

        assertEquals(LyricsAdjustmentResult.FAILED, adjust(7, LyricsAdjustmentAction.ADVANCE))
        assertEquals(500L, store.userAdvance(7, content))
        assertEquals(revision, store.importedLyrics.value.revision)
    }

    @Test
    fun numericOverflowIsReportedWithoutChangingTheValue() {
        File(directory, "7.$content.adjustment").writeText(Long.MAX_VALUE.toString())

        assertEquals(LyricsAdjustmentResult.LIMIT_REACHED, adjust(7, LyricsAdjustmentAction.ADVANCE))
        assertEquals(Long.MAX_VALUE, store.userAdvance(7, content))
    }

    @Test
    fun corruptionDoesNotBecomeAnInventedZeroOrSuccessfulWrite() {
        File(directory, "7.$content.adjustment").writeText("not a number")

        assertEquals(LyricsAdjustmentResult.FAILED, adjust(7, LyricsAdjustmentAction.DELAY))
        var failed = false
        try {
            store.userAdvance(7, content)
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun mergesKeepEachContentCorrectionAndTheSurvivorsSameContentValue() {
        adjust(10, LyricsAdjustmentAction.ADVANCE)
        adjust(20, LyricsAdjustmentAction.DELAY)
        assertEquals(LyricsAdjustmentResult.APPLIED, store.adjust(
            10, replacement, LyricsAdjustmentAction.ADVANCE, store.generation(), store.contentEpoch(10),
        ))

        store.prepareMerge(10, 20)
        store.mergeCommitted(10, 20)

        assertEquals(-500L, store.userAdvance(20, content))
        assertEquals(500L, store.userAdvance(20, replacement))
        assertEquals(0L, store.userAdvance(10, content))
        assertEquals(0L, store.userAdvance(10, replacement))
    }

    @Test
    fun aRolledBackMergeRemovesOnlyTheNewCorrectionCopies() {
        adjust(10, LyricsAdjustmentAction.ADVANCE)
        store.prepareMerge(10, 20)
        store.mergeRolledBack(10, 20)

        assertEquals(500L, store.userAdvance(10, content))
        assertEquals(0L, store.userAdvance(20, content))
    }

    @Test
    fun fullDiskMergeMovesItsOnlyCopyAndRollbackMovesItBack() {
        val full = LyricsStore(directory, duplicate = { _, _ -> throw IOException("full") })
        full.adjust(10, content, LyricsAdjustmentAction.ADVANCE, 0, 0)
        full.prepareMerge(10, 20)
        assertEquals(0L, full.userAdvance(10, content))
        assertEquals(500L, full.userAdvance(20, content))
        full.mergeRolledBack(10, 20)
        assertEquals(500L, full.userAdvance(10, content))
        assertEquals(0L, full.userAdvance(20, content))
    }

    @Test
    fun reopeningAnUncommittedCopyRestoresTheOriginalAndKeepsTheSurvivorsOwnKeys() {
        adjust(10, LyricsAdjustmentAction.ADVANCE)
        store.adjust(20, replacement, LyricsAdjustmentAction.DELAY, store.generation(), store.contentEpoch(20))
        store.prepareMerge(10, 20)

        val reopened = LyricsStore(directory)
        reopened.recoverMerges { it == 10L || it == 20L }

        assertEquals(500L, reopened.userAdvance(10, content))
        assertEquals(0L, reopened.userAdvance(20, content))
        assertEquals(-500L, reopened.userAdvance(20, replacement))
        assertFalse(directory.list()!!.any { it.endsWith(".merge") })
    }

    @Test
    fun reopeningAnUncommittedMoveRestoresTheOnlyOriginalCopy() {
        val full = LyricsStore(directory, duplicate = { _, _ -> throw IOException("full") })
        full.adjust(10, content, LyricsAdjustmentAction.ADVANCE, 0, 0)
        full.prepareMerge(10, 20)

        val reopened = LyricsStore(directory)
        reopened.recoverMerges { it == 10L || it == 20L }

        assertEquals(500L, reopened.userAdvance(10, content))
        assertEquals(0L, reopened.userAdvance(20, content))
    }

    @Test
    fun committedMovesSurviveRestartBeforeTheCleanupCallback() {
        val full = LyricsStore(directory, duplicate = { _, _ -> throw IOException("full") })
        full.adjust(10, content, LyricsAdjustmentAction.ADVANCE, 0, 0)
        full.prepareMerge(10, 20)

        val reopened = LyricsStore(directory)
        reopened.recoverMerges { it == 20L }

        assertEquals(500L, reopened.userAdvance(20, content))
        assertEquals(0L, reopened.userAdvance(10, content))
        assertFalse(directory.list()!!.any { it.endsWith(".merge") })
        reopened.recoverMerges { it == 20L }
        assertEquals(500L, reopened.userAdvance(20, content))
    }

    @Test
    fun chainedMovesRecoverTheFinalSurvivorOrUndoInReverseOrder() {
        val full = LyricsStore(directory, duplicate = { _, _ -> throw IOException("full") })
        full.adjust(10, content, LyricsAdjustmentAction.ADVANCE, 0, 0)
        full.prepareMerge(10, 20)
        full.prepareMerge(20, 30)

        var reopened = LyricsStore(directory)
        reopened.recoverMerges { it == 30L }
        assertEquals(500L, reopened.userAdvance(30, content))
        assertEquals(0L, reopened.userAdvance(10, content))
        assertEquals(0L, reopened.userAdvance(20, content))

        val againFull = LyricsStore(directory, duplicate = { _, _ -> throw IOException("full") })
        againFull.prepareMerge(30, 40)
        againFull.prepareMerge(40, 50)
        reopened = LyricsStore(directory)
        reopened.recoverMerges { it in setOf(30L, 40L, 50L) }
        assertEquals(500L, reopened.userAdvance(30, content))
        assertEquals(0L, reopened.userAdvance(40, content))
        assertEquals(0L, reopened.userAdvance(50, content))
    }

    @Test
    fun aDeathAfterTheJournalButBeforeCopyingDoesNotLeaveASurvivorCorrection() {
        adjust(10, LyricsAdjustmentAction.ADVANCE)
        val interrupted = LyricsStore(directory, duplicate = { _, _ -> error("process stopped") })
        try {
            interrupted.prepareMerge(10, 20)
        } catch (_: IllegalStateException) {
            // No callback can run after the simulated process stop.
        }

        val reopened = LyricsStore(directory)
        reopened.recoverMerges { it == 10L || it == 20L }

        assertEquals(500L, reopened.userAdvance(10, content))
        assertEquals(0L, reopened.userAdvance(20, content))
        assertFalse(directory.list()!!.any { it.endsWith(".merge") })
    }

    @Test
    fun anUnavailableCorrectionAbortsPreparationAndCanBeRecovered() {
        adjust(10, LyricsAdjustmentAction.ADVANCE)
        val original = File(directory, "10.$content.adjustment")
        val unavailable = File(directory, "offline")
        val interrupted = LyricsStore(directory, duplicate = { _, _ ->
            check(original.renameTo(unavailable))
            throw IOException("source temporarily unavailable")
        })
        org.junit.Assert.assertThrows(IOException::class.java) { interrupted.prepareMerge(10, 20) }
        check(unavailable.renameTo(original))
        interrupted.mergeRolledBack(10, 20)
        assertEquals(500L, interrupted.userAdvance(10, content))
        assertEquals(0L, interrupted.userAdvance(20, content))
        assertFalse(directory.list()!!.any { it.endsWith(".merge") })
    }

    @Test
    fun journalWriteFailureLeavesImportedLyricsAndCorrectionsUntouched() {
        store.import(10, "[00:01.00]one".toByteArray(), store.generation())
        adjust(10, LyricsAdjustmentAction.ADVANCE)
        val obstruction = File(directory, ".adjustment-merge-00000000000000000001-10-20.merge.tmp")
        assertTrue(obstruction.mkdir())
        File(obstruction, "occupied").writeText("keep")
        var failed = false
        try {
            store.prepareMerge(10, 20)
        } catch (_: IOException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals("[00:01.00]one", store.read(10)!!.toString(Charsets.UTF_8))
        assertEquals(null, store.read(20))
        assertEquals(500L, store.userAdvance(10, content))
        assertEquals(0L, store.userAdvance(20, content))
    }

    @Test
    fun anInFlightMergeProtectsItsOnlyCopyFromAPostScanSweep() {
        adjust(10, LyricsAdjustmentAction.ADVANCE)
        store.prepareMerge(10, 20)
        // A scan can finish reading the newly committed catalog before file cleanup gets its turn.
        store.sweep(CatalogTrackIds(setOf(20), 20), store.generation())

        val reopened = LyricsStore(directory)
        reopened.recoverMerges { it == 10L || it == 20L }
        assertEquals(500L, reopened.userAdvance(10, content))
        assertEquals(0L, reopened.userAdvance(20, content))
    }

    @Test
    fun sweepRebuildAndExplicitRemovalCleanCorrectionsWithoutPruningNewRows() {
        listOf(1L, 2L, 9L).forEach { adjust(it, LyricsAdjustmentAction.ADVANCE) }
        store.sweep(CatalogTrackIds(setOf(2), 5), store.generation())
        assertEquals(0L, store.userAdvance(1, content))
        assertEquals(500L, store.userAdvance(2, content))
        assertEquals(500L, store.userAdvance(9, content))
        store.remove(2)
        assertEquals(0L, store.userAdvance(2, content))
        val oldGeneration = store.generation()
        store.clearAll()
        adjust(1, LyricsAdjustmentAction.DELAY)
        store.sweep(CatalogTrackIds(emptySet(), 100), oldGeneration)
        assertEquals(-500L, store.userAdvance(1, content))
        assertEquals(0L, store.userAdvance(9, content))
        assertFalse(directory.list()!!.any { it.endsWith(".tmp") })
    }

    private fun adjust(trackId: Long, action: LyricsAdjustmentAction) = store.adjust(
        trackId, content, action, store.generation(), store.contentEpoch(trackId),
    )

    private fun revision(text: String) = (parseLyrics(text) as Lyrics.Synced).contentRevision(LyricsSource.Imported)
}
