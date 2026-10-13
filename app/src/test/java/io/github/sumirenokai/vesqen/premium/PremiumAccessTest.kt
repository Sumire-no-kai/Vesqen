package io.github.sumirenokai.vesqen.premium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumAccessTest {
    private val day = 24 * 60 * 60_000L
    private var now = 1_000_000L
    private val store = MemoryStore()

    private fun access(enforced: Boolean = true, unlocked: () -> Boolean = { false }) =
        PremiumAccess(store, enforced, unlocked) { now }

    @Test fun `a build that does not charge counts nothing and locks nothing`() {
        val access = access(enforced = false)
        access.onStrictOutputActive()
        now += 30 * day
        assertEquals(PremiumState.FreeInBeta, access.refresh())
        assertTrue(access.strictOutputAllowed())
        assertNull(store.trial.startedAtEpochMs)
    }

    @Test fun `the trial waits until strict output actually plays`() {
        val access = access()
        now += 30 * day
        assertEquals(PremiumState.TrialNotStarted, access.refresh())
        assertTrue(access.strictOutputAllowed())
        assertNull(store.trial.startedAtEpochMs)
    }

    @Test fun `the first active strict output starts seven days, counted in whole days left`() {
        val access = access()
        access.onStrictOutputActive()
        assertEquals(PremiumState.Trial(now + 7 * day, 7), access.state.value)
        now += day + 1
        assertEquals(6, (access.refresh() as PremiumState.Trial).daysLeft)
        now += 5 * day - 1
        assertEquals(1, (access.refresh() as PremiumState.Trial).daysLeft)
        now += day
        assertEquals(PremiumState.TrialEnded, access.refresh())
        assertFalse(access.strictOutputAllowed())
    }

    @Test fun `later active outputs do not restart the trial`() {
        val access = access()
        access.onStrictOutputActive()
        val startedAt = now
        now += 3 * day
        access.onStrictOutputActive()
        assertEquals(startedAt, store.trial.startedAtEpochMs)
    }

    @Test fun `moving the clock back does not extend the trial`() {
        val access = access()
        access.onStrictOutputActive()
        now += 6 * day
        access.refresh()
        now -= 5 * day
        assertEquals(1, (access.refresh() as PremiumState.Trial).daysLeft)
        now += 6 * day
        assertEquals(PremiumState.TrialEnded, access.refresh())
    }

    @Test fun `the trial and the clock floor survive a restart`() {
        access().onStrictOutputActive()
        now += 7 * day
        access().refresh()
        now -= 7 * day
        assertEquals(PremiumState.TrialEnded, access().refresh())
    }

    @Test fun `an unlock allows everything and never starts the trial`() {
        var unlocked = true
        val access = access(unlocked = { unlocked })
        access.onStrictOutputActive()
        assertNull(store.trial.startedAtEpochMs)
        assertEquals(PremiumState.Unlocked, access.refresh())
        unlocked = false
        assertEquals(PremiumState.TrialNotStarted, access.refresh())
    }

    @Test fun `an unlock after the trial ended allows strict output again`() {
        var unlocked = false
        val access = access(unlocked = { unlocked })
        access.onStrictOutputActive()
        now += 8 * day
        assertFalse(access.strictOutputAllowed())
        unlocked = true
        assertTrue(access.strictOutputAllowed())
        assertEquals(PremiumState.Unlocked, access.state.value)
    }

    @Test fun `the strict output explanation is remembered once shown`() {
        val access = access()
        assertFalse(access.strictIntroductionShown.value)
        access.markStrictIntroductionShown()
        assertTrue(access.strictIntroductionShown.value)
        assertTrue(access().strictIntroductionShown.value)
    }

    private class MemoryStore : PremiumStore {
        var trial = TrialRecord()
        private var introduction = false
        override fun readTrial() = trial
        override fun writeTrial(record: TrialRecord) { trial = record }
        override fun strictIntroductionShown() = introduction
        override fun markStrictIntroductionShown() { introduction = true }
    }
}
