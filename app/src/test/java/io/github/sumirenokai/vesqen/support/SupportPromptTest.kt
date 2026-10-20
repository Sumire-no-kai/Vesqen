package io.github.sumirenokai.vesqen.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPromptTest {
    private val day = 24 * 60 * 60_000L
    private val installedAt = 1_000_000L
    private var now = installedAt + 7 * day
    private var plays = 30L
    private var playReads = 0
    private val store = MemoryStore()
    // Unconfined runs check() to completion before it returns.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun stop() = scope.cancel()

    private fun runtime() = SupportPromptRuntime(
        scope,
        store,
        installedAtEpochMs = { installedAt },
        totalPlays = { playReads++; plays },
        clock = { now },
        dispatcher = Dispatchers.Unconfined,
    )

    private fun due(): Boolean = runtime().run { check(); due.value }

    @Test fun `seven days after installing and thirty plays, the first open asks`() {
        assertTrue(due())
    }

    @Test fun `a newer install or fewer plays does not ask`() {
        now = installedAt + 7 * day - 1
        assertFalse(due())
        now = installedAt + 7 * day
        plays = 29
        assertFalse(due())
    }

    @Test fun `the library is read only when nothing else already says no`() {
        now = installedAt + day
        assertFalse(due())
        store.record = SupportPromptRecord(finished = true)
        now = installedAt + 30 * day
        assertFalse(due())
        assertEquals(0, playReads)
    }

    @Test fun `once on screen it is not due again for sixty days`() {
        val prompt = runtime()
        prompt.check()
        assertTrue(prompt.due.value)
        prompt.markShown()
        assertFalse(prompt.due.value)
        assertEquals(SupportPromptRecord(asks = 1, lastAskedAtEpochMs = now), store.record)

        now += 60 * day - 1
        assertFalse(due())
        now += 1
        assertTrue(due())
    }

    @Test fun `later is asked at most three times`() {
        repeat(3) {
            val prompt = runtime()
            prompt.check()
            assertTrue(prompt.due.value)
            prompt.markShown()
            now += 60 * day
        }
        assertEquals(3, store.record.asks)
        now += 365 * day
        assertFalse(due())
    }

    @Test fun `support or never ends the question for good`() {
        val prompt = runtime()
        prompt.check()
        prompt.markShown()
        prompt.finish()
        assertEquals(SupportPromptRecord(asks = 1, lastAskedAtEpochMs = now, finished = true), store.record)
        now += 365 * day
        assertFalse(due())
    }

    @Test fun `a clock set back before the last question waits instead of asking`() {
        store.record = SupportPromptRecord(asks = 1, lastAskedAtEpochMs = now + 30 * day)
        assertFalse(due())
    }

    private class MemoryStore : SupportPromptStore {
        var record = SupportPromptRecord()
        override fun read() = record
        override fun write(record: SupportPromptRecord) { this.record = record }
    }
}
