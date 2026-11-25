package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private val fixture = Fixture()

    @Test fun countdownUsesElapsedDeadlineAndPausesOnceWithoutClearingTheQueue() = with(fixture) {
        assertTrue(timer.startCountdown(30_000, false))
        assertEquals(30_100L, timer.state.deadlineElapsedRealtimeMs)
        assertEquals(30_000L, timer.state.remainingMs(now))
        advance(29_999)
        assertEquals(0, pauses)
        advance(1)
        assertEquals(1, pauses)
        assertEquals("first", current.queueEntryId)
        assertEquals(SleepTimerMode.OFF, timer.state.mode)
        jobs.single().callback()
        assertEquals(1, pauses)
    }

    @Test fun finishCurrentArmsThePlayersEndGateOnlyAfterTheDeadline() = with(fixture) {
        timer.startCountdown(1_000, true)
        assertFalse(endGate)
        advance(1_000)
        assertTrue(endGate)
        assertTrue(timer.state.waitingForTrackEnd)
        assertEquals(0, pauses)
        timer.onTrackEnded()
        assertEquals(1, pauses)
        assertFalse(endGate)
        assertEquals(SleepTimerMode.OFF, timer.state.mode)
    }

    @Test fun repeatOneBoundaryCompletesWithoutRequiringANewEntryId() = with(fixture) {
        assertTrue(timer.pauseAfterCurrentTrack("first"))
        timer.onTrackEnded()
        assertEquals("first", current.queueEntryId)
        assertEquals(1, pauses)
        assertEquals(SleepTimerMode.OFF, timer.state.mode)
    }

    @Test fun aRepeatBoundaryDeliversAnOverdueFinishCurrentCountdown() = with(fixture) {
        timer.startCountdown(1_000, true)
        now += 1_001 // The player reports repeat-one before the scheduled deadline callback runs.
        timer.onTrackEnded()
        timer.onPlaybackChanged()
        assertEquals(SleepTimerMode.OFF, timer.state.mode)
        assertEquals(1, pauses)
        jobs.single().callback()
        assertEquals(1, pauses)
    }

    @Test fun staleEndRequestCannotArmAnotherOccurrenceOfTheSameSong() = with(fixture) {
        current = current.copy(queueEntryId = "second-occurrence")
        assertFalse(timer.pauseAfterCurrentTrack("first"))
        assertFalse(endGate)
        assertEquals(SleepTimerMode.OFF, timer.state.mode)
    }

    @Test fun manualSkipCancelsAnEndOfCurrentTrackRequest() = with(fixture) {
        timer.pauseAfterCurrentTrack("first")
        current = current.copy(queueEntryId = "second")
        timer.onManualTrackChange()
        assertEquals(0, pauses)
        assertEquals(SleepTimerMode.OFF, timer.state.mode)
        assertFalse(endGate)
    }

    @Test fun manualSkipAfterExpiryStopsInsteadOfWaitingForAnotherTrack() = with(fixture) {
        timer.startCountdown(1_000, true)
        advance(1_000)
        current = current.copy(queueEntryId = "second")
        timer.onManualTrackChange()
        assertEquals(1, pauses)
        assertEquals(SleepTimerMode.OFF, timer.state.mode)
    }

    @Test fun countdownBeforeExpiryFollowsQueueEditsWithoutRestartingItsDeadline() = with(fixture) {
        timer.startCountdown(1_000, true)
        val deadline = timer.state.deadlineElapsedRealtimeMs
        current = current.copy(queueEntryId = "second")
        timer.onManualTrackChange()
        assertEquals(deadline, timer.state.deadlineElapsedRealtimeMs)
        advance(1_000)
        assertTrue(endGate)
        assertEquals(0, pauses)
    }

    @Test fun withinTrackSeeksAndMetadataUpdatesKeepTheEndGate() = with(fixture) {
        timer.pauseAfterCurrentTrack("first")
        repeat(3) { timer.onPlaybackChanged() }
        assertTrue(endGate)
        assertEquals(SleepTimerMode.END_OF_TRACK, timer.state.mode)
    }

    @Test fun clearingOrFailingThePlayerCancelsWithoutPausingAFutureSong() = with(fixture) {
        timer.startCountdown(1_000, false)
        current = SleepTimerPlayback(null, true, terminal = true)
        timer.onPlaybackChanged()
        current = SleepTimerPlayback("new", true, terminal = false)
        advance(1_000)
        assertEquals(0, pauses)
        assertEquals(SleepTimerMode.OFF, timer.state.mode)
    }

    @Test fun alreadyPausedAtExpiryCompletesWithoutStartingOrStoppingPlayback() = with(fixture) {
        current = current.copy(playWhenReady = false)
        timer.startCountdown(1_000, true)
        advance(1_000)
        assertEquals(0, pauses)
        assertFalse(endGate)
        assertEquals(SleepTimerMode.OFF, timer.state.mode)
    }

    @Test fun requestedButSuppressedPlaybackCanWaitForItsCurrentTrack() = with(fixture) {
        // Buffering and transient suppression retain playWhenReady and are not terminal.
        timer.startCountdown(1_000, true)
        advance(1_000)
        assertTrue(endGate)
        assertTrue(timer.state.waitingForTrackEnd)
    }

    @Test fun resumingAfterASleepDeferredDeadlineDoesNotPauseTheNewPlay() = with(fixture) {
        current = current.copy(playWhenReady = false)
        timer.startCountdown(1_000, false)
        now += 2_000 // No callback delivery while the paused device slept.
        current = current.copy(playWhenReady = true)
        timer.onPlaybackChanged()
        jobs.single().callback()
        assertEquals(0, pauses)
        assertEquals(SleepTimerMode.OFF, timer.state.mode)
    }

    @Test fun anEditedTimerRejectsCallbacksFromThePreviousDeadline() = with(fixture) {
        timer.startCountdown(1_000, false)
        val first = jobs.single()
        timer.startCountdown(5_000, false)
        now += 1_000
        first.callback()
        assertEquals(0, pauses)
        assertEquals(5_100L, timer.state.deadlineElapsedRealtimeMs)
        advance(4_000)
        assertEquals(1, pauses)
    }

    @Test fun cancelRejectsBothOldDeadlineAndTrackEndCallbacks() = with(fixture) {
        timer.startCountdown(1_000, false)
        val old = jobs.single()
        timer.cancel()
        now += 1_000
        old.callback()
        timer.onTrackEnded()
        assertEquals(0, pauses)
        assertEquals(SleepTimerMode.OFF, timer.state.mode)
    }

    @Test fun switchingFromAnEndRequestToCountdownReleasesTheEndGate() = with(fixture) {
        timer.pauseAfterCurrentTrack("first")
        assertTrue(endGate)
        timer.startCountdown(1_000, false)
        assertFalse(endGate)
        timer.onTrackEnded()
        assertEquals(SleepTimerMode.COUNTDOWN, timer.state.mode)
    }

    @Test fun anInvalidRequestDoesNotReplaceAValidTimer() = with(fixture) {
        timer.startCountdown(1_000, false)
        val original = timer.state
        for (duration in listOf(0L, -1L, Long.MAX_VALUE)) assertFalse(timer.startCountdown(duration, true))
        assertFalse(timer.pauseAfterCurrentTrack("wrong"))
        assertSame(original, timer.state)
        assertEquals(1, jobs.size)
    }

    @Test fun schedulingFailurePreservesTheExistingTimerAndGate() = with(fixture) {
        timer.pauseAfterCurrentTrack("first")
        val original = timer.state
        failSchedule = true
        assertThrows(SecurityException::class.java) { timer.startCountdown(1_000, false) }
        assertSame(original, timer.state)
        assertTrue(endGate)
    }

    @Test fun closeCancelsTheAlarmAndDoesNotRestoreItIntoANewService() = with(fixture) {
        timer.startCountdown(1_000, false)
        val old = jobs.single()
        timer.close()
        now += 1_000
        old.callback()
        assertEquals(0, pauses)
        assertTrue(old.cancelled)
        assertFalse(timer.startCountdown(1_000, false))
        assertFalse(timer.pauseAfterCurrentTrack("first"))
        assertEquals(SleepTimerMode.OFF, Fixture().timer.state.mode)
    }

    @Test fun stateRevisionsIncreaseAcrossCountdownWaitAndCompletion() = with(fixture) {
        timer.startCountdown(1_000, true)
        advance(1_000)
        timer.onTrackEnded()
        assertEquals(listOf(1L, 2L, 3L), published.map(SleepTimerState::revision))
    }

    private class Job(val deadline: Long, val callback: () -> Unit, var cancelled: Boolean = false)
    private class Fixture {
        var now = 100L
        var current = SleepTimerPlayback("first", true, terminal = false)
        var pauses = 0
        var endGate = false
        var failSchedule = false
        val jobs = mutableListOf<Job>()
        val published = mutableListOf<SleepTimerState>()
        val timer = SleepTimer(
            elapsedRealtimeMs = { now },
            scheduler = { deadline, callback ->
                if (failSchedule) throw SecurityException("alarm scheduling refused")
                val job = Job(deadline, callback).also(jobs::add)
                val cancel: () -> Unit = { job.cancelled = true }
                cancel
            },
            playback = { current },
            setPauseAtTrackEnd = { endGate = it },
            pause = { pauses++ },
            onChanged = published::add,
        )

        fun advance(delta: Long) {
            now += delta
            jobs.toList().filter { !it.cancelled && it.deadline <= now }.forEach { it.callback() }
        }
    }
}
