package io.github.sumirenokai.vesqen.library

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.testing.FakeDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** #217: a pause takes effect between the files of one folder, not only while it is listed. */
class TreeAudioScannerPauseDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri(
        FakeDocumentsProvider.authority(app.packageName),
        FakeDocumentsProvider.FOLDER,
    )

    @After
    fun resetProvider() = FakeDocumentsProvider.reset()

    @Test
    fun aPauseWhileAFileIsReadStopsBeforeTheNextFile() = runBlocking {
        repeat(3) { FakeDocumentsProvider.files["music/song-$it.flac"] = ByteArray(0) }
        val scanner = TreeAudioScanner(app.contentResolver)
        var paused = false
        val read = mutableListOf<String>()

        // The folder is listed in full; the user pauses while the first file's tags are read.
        val stopped = scanner.scan(tree, shouldPause = { paused }) { document ->
            read += document.displayName
            paused = true
        }
        assertEquals(ScanIterationResult(completed = false, processedTrackCount = 1), stopped)
        assertEquals(1, read.size)

        // Resumed, the scan reads the folder to its end.
        paused = false
        read.clear()
        val resumed = scanner.scan(tree, shouldPause = { paused }) { document -> read += document.displayName }
        assertEquals(ScanIterationResult(completed = true, processedTrackCount = 3), resumed)
        assertEquals(setOf("song-0.flac", "song-1.flac", "song-2.flac"), read.toSet())
    }
}
