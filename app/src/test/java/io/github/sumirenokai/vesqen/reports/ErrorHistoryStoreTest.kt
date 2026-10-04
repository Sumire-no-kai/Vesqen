package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.*

class ErrorHistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `only newest hundred errors survive reopening and time retention removes expired data`() {
        val directory = temporary.newFolder()
        val store = ErrorHistoryStore(directory)
        repeat(120) { store.append(error(it.toLong()), 120) }
        val reopened = ErrorHistoryStore(directory)
        assertEquals((20L..119L).toList(), reopened.read(120).map { it.occurredAtEpochMs })
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= ErrorHistoryStore.MAX_FILE_BYTES)
        assertEquals(listOf(119L), reopened.read(ErrorHistoryStore.MAX_AGE_MS + 119).map { it.occurredAtEpochMs })
        assertTrue(reopened.read(ErrorHistoryStore.MAX_AGE_MS + 120).isEmpty())
        assertTrue(ErrorHistoryStore(directory).read(ErrorHistoryStore.MAX_AGE_MS + 120).isEmpty())
    }

    @Test fun `future records and expired interrupted writes cannot reappear`() {
        val directory = temporary.newFolder()
        val store = ErrorHistoryStore(directory)
        store.append(error(200), 200)
        File(directory, "errors.next").writeText("private interrupted bytes")
        assertTrue(store.read(100).isEmpty())
        assertFalse(File(directory, "errors.next").exists())
        assertTrue(store.read(300).isEmpty())
    }

    @Test fun `historical process exits deduplicate and keep numeric reasons without descriptions`() {
        val store = ErrorHistoryStore(temporary.newFolder())
        val event = ReportErrorEvent(ReportErrorKind.PROCESS_EXIT, 100, platformCode = 4)
        store.append(event, 200)
        store.append(event, 200)
        store.append(event.copy(platformCode = 6), 200)
        assertEquals(listOf(4, 6), store.read(200).map { it.platformCode })
        assertTrue(store.read(200).all { it.fileName == null && it.format == null })
    }

    @Test fun `strict output codes and source timestamps survive serialization`() {
        val store = ErrorHistoryStore(temporary.newFolder())
        val event = ReportErrorEvent(ReportErrorKind.STRICT_OUTPUT, 100, 70, strictFailure = UsbOutputFailure.entries.first(),
            format = FailedTrackFormat(sampleRateHz = 96000, bitDepth = 24, channelCount = 2, source = ErrorFormatSource.STRICT_OUTPUT_REQUEST))
        store.append(event, 200)
        assertEquals(event, store.read(200).single())
    }

    @Test fun `private journal stores only basename and reviewed format fields`() {
        val directory = temporary.newFolder()
        val store = ErrorHistoryStore(directory)
        store.append(error().copy(format = FailedTrackFormat("/private-folder/raw-error", "C:\\private-folder\\raw-error", -1, 24, 2)), 200)
        val event = store.read(200).single()
        assertEquals("private-track.flac", event.fileName)
        assertNull(event.format!!.container)
        assertNull(event.format.codecMime)
        assertNull(event.format.sampleRateHz)
        assertTrue(event.format.privacyFiltered)
        assertFalse(File(directory, "errors.bin").readBytes().toString(Charsets.ISO_8859_1).contains("private-folder"))
    }

    @Test fun `corrupt storage is explicit and never silently reset`() {
        val directory = temporary.newFolder()
        val file = File(directory, "errors.bin")
        file.writeText("not a journal")
        val store = ErrorHistoryStore(directory)
        assertThrows(IOException::class.java) { store.read(200) }
        assertThrows(IOException::class.java) { store.append(error(), 200) }
        assertEquals("not a journal", file.readText())
    }

    @Test fun `recorder flushes queued errors and exposes historical query availability`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val recorder = ReportErrorRecorder(scope, ErrorHistoryStore(temporary.newFolder()), { 200 }) {
                ExitHistoryAvailability.UNSUPPORTED_ANDROID_VERSION to emptyList()
            }
            recorder.record(error()) { "/private-folder/from-error.flac" }
            val snapshot = withTimeout(5000) { recorder.snapshot() }
            assertEquals("from-error.flac", snapshot.events.single().fileName)
            assertEquals(ExitHistoryAvailability.UNSUPPORTED_ANDROID_VERSION, snapshot.exitHistory)
            assertEquals(ErrorHistoryAvailability.AVAILABLE, snapshot.availability)
        } finally { scope.cancel() }
    }

    @Test fun `bounded queue explicitly reports dropped errors`() = runBlocking {
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val recorder = ReportErrorRecorder(scope, ErrorHistoryStore(temporary.newFolder()), { 200 }) {
                check(release.await(5, TimeUnit.SECONDS))
                ExitHistoryAvailability.AVAILABLE to emptyList()
            }
            repeat(40) { recorder.record(error(it.toLong())) }
            release.countDown()
            val snapshot = withTimeout(5000) { recorder.snapshot() }
            assertEquals(ErrorHistoryAvailability.QUEUE_OVERFLOW, snapshot.availability)
            assertEquals(32, snapshot.events.size)
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun `recorder reports storage failure without breaking playback caller`() = runBlocking {
        val directory = temporary.newFolder()
        File(directory, "errors.bin").writeText("corrupt")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val recorder = ReportErrorRecorder(scope, ErrorHistoryStore(directory), { 200 }) { ExitHistoryAvailability.AVAILABLE to emptyList() }
            recorder.record(error())
            val snapshot = withTimeout(5000) { recorder.snapshot() }
            assertEquals(ErrorHistoryAvailability.STORAGE_UNAVAILABLE, snapshot.availability)
            assertTrue(snapshot.events.isEmpty())
        } finally { scope.cancel() }
    }
}
