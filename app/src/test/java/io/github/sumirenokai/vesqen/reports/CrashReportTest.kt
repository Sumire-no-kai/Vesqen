package io.github.sumirenokai.vesqen.reports

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun stop() = scope.cancel()

    @Test fun `capture keeps types frames and causes and never message text`() {
        val cause = IOException("open failed: $PRIVATE_PATH")
        val crash = IllegalStateException("Could not play $PRIVATE_TITLE", cause)
        val record = CrashCapture.record(crash, 1_000, "1.0.0-beta.3", 12, "1000-a")

        assertEquals(listOf("java.lang.IllegalStateException", "java.io.IOException"), record.exceptions.map { it.type })
        val top = record.exceptions.first().frames.first()
        assertEquals(CrashReportTest::class.java.name, top.className)
        // A Kotlin test name has spaces, so it is not a plain JVM identifier and is dropped.
        assertNull(top.methodName)
        assertTrue(top.line!! > 0)
        assertFalse(record.causesOmitted)
        assertFalse(record.toString().contains(PRIVATE_TITLE))
        assertFalse(record.toString().contains("open failed"))
    }

    @Test fun `the handler records the crash, then hands the same crash and thread to the previous handler`() {
        val directory = temporary.newFolder()
        val handed = mutableListOf<Pair<Thread, Throwable>>()
        val handler = CrashRecordingHandler({ thread, crash -> handed += thread to crash }) { crash ->
            CrashReportStore(directory).write(CrashCapture.record(crash, 1_000, "1.0.0", 9, CrashReportStore.recordId(1_000)))
        }
        val crash = IllegalStateException("Could not play $PRIVATE_TITLE from $PRIVATE_PATH")
        val thread = Thread { throw crash }.apply {
            name = "$PRIVATE_TITLE player"
            uncaughtExceptionHandler = handler
        }
        thread.start()
        thread.join()

        assertEquals(1, handed.size)
        assertSame(thread, handed.single().first)
        assertSame(crash, handed.single().second)
        val stored = directory.listFiles()!!.single { it.name.endsWith(".crash") }.readBytes().toString(Charsets.ISO_8859_1)
        listOf(PRIVATE_TITLE, "player", "Could not play", "storage").forEach { assertFalse(it, stored.contains(it)) }

        val record = CrashReportStore(directory).read(1_000).single()
        assertEquals("java.lang.IllegalStateException", record.exceptions.single().type)
        val report = DeviceReportGenerator.generate(
            reportData().copy(crashes = CrashHistorySnapshot(listOf(record))), DeviceReportOptions(crashes = true),
        ).previewText
        assertTrue(report.contains("java.lang.IllegalStateException"))
        listOf(PRIVATE_TITLE, "player", "Could not play", "storage", record.id).forEach { assertFalse(it, report.contains(it)) }
    }

    @Test fun `a failed write goes along with the crash and the previous handler still runs`() {
        val handed = mutableListOf<Throwable>()
        val failure = IOException("disk full")
        val handler = CrashRecordingHandler({ _, crash -> handed += crash }) { throw failure }
        val crash = IllegalStateException()
        handler.uncaughtException(Thread.currentThread(), crash)

        assertSame(crash, handed.single())
        assertSame(failure, crash.suppressed.single())
    }

    @Test fun `one record per process even when a second thread fails`() {
        var records = 0
        val handed = mutableListOf<Throwable>()
        val handler = CrashRecordingHandler({ _, crash -> handed += crash }) { records++ }
        handler.uncaughtException(Thread.currentThread(), IllegalStateException())
        handler.uncaughtException(Thread.currentThread(), IllegalArgumentException())

        assertEquals(1, records)
        assertEquals(2, handed.size)
    }

    @Test fun `names that are not plain identifiers are dropped and unknown lines are null`() {
        val raw = CrashRecord("1-a", 1, "1.0.0", 1, listOf(CrashException("java.lang.Error: $PRIVATE_TITLE", listOf(
            CrashFrame(PRIVATE_PATH, "run", 1),
            CrashFrame("com.example.Player", "play $PRIVATE_TITLE", 2),
            CrashFrame("com.example.Pläyer", "<init>", -2),
            CrashFrame("a".repeat(CrashCapture.MAX_CLASS_NAME_LENGTH + 1), "b".repeat(CrashCapture.MAX_METHOD_NAME_LENGTH + 1), 3),
            CrashFrame("com.example.Player\$1", "<clinit>", -1),
            CrashFrame("com.example.Player", "foo<init>", 4),
            CrashFrame("-\$\$Lambda\$Player\$x1", "invoke-0kLqBqw", 5),
        ), 0)), causesOmitted = false)

        val filtered = CrashCapture.filtered(raw).exceptions.single()
        assertNull(filtered.type)
        assertEquals(
            listOf(null, "com.example.Player", null, null, "com.example.Player\$1", "com.example.Player", "-\$\$Lambda\$Player\$x1"),
            filtered.frames.map { it.className },
        )
        assertEquals(listOf("run", null, "<init>", null, "<clinit>", null, "invoke-0kLqBqw"), filtered.frames.map { it.methodName })
        assertEquals(listOf(1, 2, null, 3, null, 4, 5), filtered.frames.map { it.line })
    }

    @Test fun `long cause chains, deep stacks and cause cycles stay within the limits`() {
        var chain: Throwable = RuntimeException("first")
        repeat(CrashCapture.MAX_EXCEPTIONS + 2) { chain = RuntimeException("wrapped", chain) }
        val long = CrashCapture.record(chain, 1, "1.0.0", 1, "1-a")
        assertEquals(CrashCapture.MAX_EXCEPTIONS, long.exceptions.size)
        assertTrue(long.causesOmitted)

        val deep = IllegalStateException().apply {
            stackTrace = Array(CrashCapture.MAX_FRAMES + 16) { StackTraceElement("com.example.Deep", "level$it", "Deep.kt", it) }
        }
        val frames = CrashCapture.record(deep, 1, "1.0.0", 1, "1-b").exceptions.single()
        assertEquals((0 until CrashCapture.MAX_FRAMES).map { "level$it" }, frames.frames.map { it.methodName })
        assertEquals(16, frames.omittedFrames)

        val first = RuntimeException("a")
        val second = RuntimeException("b", first)
        first.initCause(second)
        val cycle = CrashCapture.record(first, 1, "1.0.0", 1, "1-c")
        assertEquals(2, cycle.exceptions.size)
        assertFalse(cycle.causesOmitted)
    }

    @Test fun `the store keeps the newest three crashes for seven days`() {
        val directory = temporary.newFolder()
        val store = CrashReportStore(directory)
        (1..5).forEach { store.write(record(it * 100L)) }
        // The crash path itself already keeps no more than three.
        assertEquals(CrashReportStore.MAX_CRASHES, directory.listFiles()!!.count { it.name.endsWith(".crash") })

        val reopened = CrashReportStore(directory)
        assertEquals(listOf(300L, 400L, 500L), reopened.read(500).map { it.occurredAtEpochMs })
        assertEquals(listOf(300L, 400L, 500L), reopened.read(CrashReportStore.MAX_AGE_MS + 300).map { it.occurredAtEpochMs })
        assertEquals(listOf(400L, 500L), reopened.read(CrashReportStore.MAX_AGE_MS + 301).map { it.occurredAtEpochMs })
        assertTrue(CrashReportStore(directory).read(CrashReportStore.MAX_AGE_MS + 600).isEmpty())
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".crash") })
    }

    @Test fun `records survive storage unchanged and future records are dropped`() {
        val directory = temporary.newFolder()
        val store = CrashReportStore(directory)
        val crash = IllegalStateException("not stored", IOException("not stored either"))
        val written = CrashCapture.record(crash, 1_000, "1.0.0-beta.3", 12, CrashReportStore.recordId(1_000))
        store.write(written)
        assertEquals(listOf(written), store.read(1_000))
        assertTrue(store.read(999).isEmpty())
    }

    @Test fun `unreadable records are discarded and reported`() {
        val directory = temporary.newFolder()
        File(directory, "100-a.crash").writeText("not a crash record")
        val store = CrashReportStore(directory)
        assertTrue(store.read(200).isEmpty())
        assertTrue(store.discardedUnreadableRecord)
        assertFalse(File(directory, "100-a.crash").exists())
    }

    @Test fun `interrupted writes are removed once they are no longer in progress`() {
        val directory = temporary.newFolder()
        val now = 10_000_000L
        val old = File(directory, "1-a.next").apply { writeText("partial"); setLastModified(now - CrashReportStore.PENDING_GRACE_MS - 1) }
        val recent = File(directory, "2-b.next").apply { writeText("partial"); setLastModified(now - 1_000) }
        CrashReportStore(directory).read(now)
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test fun `the time of the last question survives reopening and an unreadable one counts as never asked`() {
        val directory = temporary.newFolder()
        assertEquals(0, CrashReportStore(directory).promptedThrough())
        CrashReportStore(directory).setPromptedThrough(1_234)
        assertEquals(1_234, CrashReportStore(directory).promptedThrough())
        File(directory, "prompt-state.bin").writeText("x")
        assertEquals(0, CrashReportStore(directory).promptedThrough())
    }

    @Test fun `the question is due once for a new crash and kept records do not ask again`() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        CrashReportStore(directory).write(record(1_000))
        val runtime = CrashReportRuntime(scope, CrashReportStore(directory), { 2_000 }) { emptyList() }
        withTimeout(5_000) { runtime.due.first { it } }
        runtime.markShown()
        assertFalse(runtime.due.value)
        idle()

        val reopened = CrashReportRuntime(scope, CrashReportStore(directory), { 3_000 }) { emptyList() }
        idle()
        assertFalse(reopened.due.value)
        // Shown but not answered: the record stays for a report until it expires.
        assertEquals(listOf(1_000L), reopened.snapshot().records.map { it.occurredAtEpochMs })

        CrashReportStore(directory).write(record(2_500))
        val later = CrashReportRuntime(scope, CrashReportStore(directory), { 3_000 }) { emptyList() }
        withTimeout(5_000) { later.due.first { it } }
    }

    @Test fun `Android's records of a recent ANR or native crash ask the question and old ones do not`() = runBlocking<Unit> {
        val now = CrashReportStore.MAX_AGE_MS * 2
        val directory = temporary.newFolder()
        val expired = CrashReportRuntime(scope, CrashReportStore(directory), { now }) { listOf(now - CrashReportStore.MAX_AGE_MS - 1) }
        idle()
        assertFalse(expired.due.value)

        val recent = CrashReportRuntime(scope, CrashReportStore(directory), { now }) { listOf(now - CrashReportStore.MAX_AGE_MS - 1, now - 10) }
        withTimeout(5_000) { recent.due.first { it } }
        recent.markShown()
        idle()

        val again = CrashReportRuntime(scope, CrashReportStore(directory), { now + 5 }) { listOf(now - 10) }
        idle()
        assertFalse(again.due.value)
        val newer = CrashReportRuntime(scope, CrashReportStore(directory), { now + 5 }) { listOf(now - 10, now + 1) }
        withTimeout(5_000) { newer.due.first { it } }
    }

    @Test fun `No thanks deletes the crash records and a sent report deletes only its own`() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val store = CrashReportStore(directory)
        store.write(record(1_000))
        store.write(record(1_100))
        val runtime = CrashReportRuntime(scope, store, { 2_000 }) { emptyList() }
        withTimeout(5_000) { runtime.due.first { it } }

        val (first, second) = runtime.snapshot().records
        runtime.delivered(listOf(first))
        idle()
        assertEquals(listOf(second), store.read(2_000))

        runtime.decline()
        idle()
        assertFalse(runtime.due.value)
        assertTrue(store.read(2_000).isEmpty())
    }

    @Test fun `a storage failure is reported in the crash section and asks nothing`() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        // A record that exists but cannot be opened.
        File(directory, "100-a.crash").mkdirs()
        val runtime = CrashReportRuntime(scope, CrashReportStore(directory), { 2_000 }) { listOf(1_000) }
        idle()
        assertFalse(runtime.due.value)
        val snapshot = runtime.snapshot()
        assertEquals(CrashHistoryAvailability.STORAGE_UNAVAILABLE, snapshot.availability)
        assertTrue(snapshot.records.isEmpty())
    }

    /** Waits for the IO work the runtime started so far. */
    private suspend fun idle() = scope.coroutineContext.job.children.toList().joinAll()

    private fun record(time: Long) = CrashRecord(CrashReportStore.recordId(time), time, "1.0.0", 1,
        listOf(CrashException("java.lang.IllegalStateException", listOf(CrashFrame("com.example.Player", "play", 7)), 0)),
        causesOmitted = false)

    private companion object {
        const val PRIVATE_TITLE = "Secret Song"
        const val PRIVATE_PATH = "/storage/emulated/0/Music/Secret Song.flac"
    }
}
