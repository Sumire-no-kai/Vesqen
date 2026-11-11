package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScanDiagnosticsTest {
    @Test fun `only rejected known audio under generic MIME contributes to skip diagnostics`() {
        assertEquals(UnsupportedAudioFormat.DSD, knownUnsupportedAudioFormat("application/octet-stream", "album.DSF"))
        assertEquals(UnsupportedAudioFormat.DSD, knownUnsupportedAudioFormat(null, "album.dff"))
        assertEquals(UnsupportedAudioFormat.APE, knownUnsupportedAudioFormat("", "album.ape"))
        assertEquals(UnsupportedAudioFormat.WAVPACK, knownUnsupportedAudioFormat("binary/octet-stream", "album.wv"))
        listOf("cover.jpg", "song.lrc", "album.cue", "notes.txt", "unknown.bin").forEach { name ->
            assertNull(name, knownUnsupportedAudioFormat("application/octet-stream", name))
        }
        assertNull(knownUnsupportedAudioFormat("image/jpeg", "album.dsf"))
        assertNull(knownUnsupportedAudioFormat("text/plain", "album.ape"))
        assertNull(knownUnsupportedAudioFormat("application/x-unidentified", "album.wv"))
    }

    @Test fun `provider audio acceptance stays intact and is not claimed to be a skipped format`() {
        assertTrue(isSupportedAudioDocument("audio/dsd", "album.dsf"))
        assertNull(knownUnsupportedAudioFormat("audio/dsd", "album.dsf"))
        assertTrue(isSupportedAudioDocument("application/octet-stream", "short.flac"))
        assertNull(knownUnsupportedAudioFormat("application/octet-stream", "short.flac"))
    }

    @Test fun `batch progress retains per-source actual counts without inventing totals`() {
        val report = SourceScanReport(source)
        assertFalse(report.shouldPublish())
        repeat(31) { report.checkedTrack() }
        assertFalse(report.shouldPublish())
        report.skippedAudio(UnsupportedAudioFormat.DSD)
        assertTrue(report.shouldPublish())
        val reading = report.progress()
        assertEquals(31, reading.scannedTrackCount)
        assertEquals(mapOf(UnsupportedAudioFormat.DSD to 1), reading.skippedUnsupportedAudio)
        assertEquals(LibraryScanStage.READING, reading.stage)
        assertFalse(reading.isPaused)
        report.checkedTrack()
        assertFalse(report.shouldPublish())
        assertEquals(32, report.progress(isPaused = true).scannedTrackCount)
        assertTrue(report.progress(isPaused = true).isPaused)
        assertEquals(LibraryScanStage.RECONCILING, report.progress(LibraryScanStage.RECONCILING).stage)
    }

    @Test fun `result and progress copies never change as the next observation arrives`() {
        val report = SourceScanReport(source)
        report.skippedAudio(UnsupportedAudioFormat.APE)
        val before = report.result(LibrarySourceScanStatus.PAUSED)
        val progress = report.progress()
        report.skippedAudio(UnsupportedAudioFormat.APE)
        report.checkedTrack()
        assertEquals(1, before.skippedUnsupportedAudioCount)
        assertEquals(0, before.scannedTrackCount)
        assertEquals(mapOf(UnsupportedAudioFormat.APE to 1), progress.skippedUnsupportedAudio)
        assertEquals(2, report.result(LibrarySourceScanStatus.COMPLETED).skippedUnsupportedAudioCount)
        assertEquals(source.id, before.sourceId)
    }

    @Test fun `unchanged unavailable and failed results do not claim cached tracks were checked`() {
        val report = SourceScanReport(source)
        assertEquals(0, report.result(LibrarySourceScanStatus.UNCHANGED).scannedTrackCount)
        val unavailable = report.result(LibrarySourceScanStatus.UNAVAILABLE, LibraryScanFailureReason.PERMISSION_REQUIRED)
        assertEquals(LibraryScanFailureReason.PERMISSION_REQUIRED, unavailable.failureReason)
        assertEquals(0, unavailable.scannedTrackCount)
        assertEquals(LibraryScanFailureReason.READ_FAILED, report.result(LibrarySourceScanStatus.FAILED).failureReason)
    }

    @Test fun `failure classification exposes categories without private provider messages`() {
        assertEquals(LibraryScanFailureReason.PERMISSION_REQUIRED, scanFailureReason(SecurityException("private/path")))
        assertEquals(LibraryScanFailureReason.PROVIDER_NOT_READY, scanFailureReason(IncompleteLibraryProviderException()))
        assertEquals(LibraryScanFailureReason.READ_FAILED, scanFailureReason(java.io.FileNotFoundException("private/path")))
        assertEquals(LibraryScanFailureReason.READ_FAILED, scanFailureReason(IllegalStateException("provider-secret")))
    }

    private val source = StoredLibrarySource(
        id = "fixture-source", kind = LibrarySourceKind.FOLDER, displayName = "Music", treeUri = null,
        scanState = LibraryScanState.IDLE, generation = null, scanEpoch = 0, trackCount = 1_000,
    )
}
