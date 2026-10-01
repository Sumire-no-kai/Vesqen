package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerPreferenceCleanupTrackerTest {
    @Test
    fun confirmedClearRemovesTrackedPreference() {
        val tracker = MixerPreferenceCleanupTracker<String> { Result.success(true) }
        tracker.track("usb-preference")

        assertTrue(tracker.clear("usb-preference"))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun rejectedClearRemainsTrackedUntilRetrySucceeds() {
        var clears = 0
        val tracker = MixerPreferenceCleanupTracker<String> {
            clears += 1
            Result.success(clears > 1)
        }
        tracker.track("usb-preference")

        assertFalse(tracker.clearAll())
        assertEquals(1, tracker.pendingCount())
        assertTrue(tracker.clearAll())
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun rejectedRequestIsNotTrackedAndNeverCleared() {
        var clears = 0
        val tracker = MixerPreferenceCleanupTracker<String> {
            clears += 1
            // What Android answers when another app holds the device's preference.
            Result.success(false)
        }

        val result = tracker.request("usb-preference") { Result.success(false) }

        assertEquals(false, result.getOrNull())
        assertEquals(0, tracker.pendingCount())
        assertTrue(tracker.clearAll())
        assertEquals(0, clears)
    }

    @Test
    fun appliedRequestIsTrackedUntilCleared() {
        val tracker = MixerPreferenceCleanupTracker<String> { Result.success(true) }

        val result = tracker.request("usb-preference") { Result.success(true) }

        assertEquals(true, result.getOrNull())
        assertEquals(1, tracker.pendingCount())
        assertTrue(tracker.clearAll())
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun requestThatThrewStaysTrackedBecauseItMayHaveApplied() {
        val tracker = MixerPreferenceCleanupTracker<String> { Result.success(true) }

        val result = tracker.request("usb-preference") { Result.failure(IllegalStateException("binder died")) }

        assertTrue(result.isFailure)
        assertEquals(1, tracker.pendingCount())
    }

    @Test
    fun clearExceptionRemainsTrackedAndDoesNotSkipOtherPreferences() {
        val tracker = MixerPreferenceCleanupTracker<String> { preference ->
            if (preference == "failing") Result.failure(IllegalStateException("platform failure"))
            else Result.success(true)
        }
        tracker.track("failing")
        tracker.track("clearable")

        assertFalse(tracker.clearAll())
        assertEquals(1, tracker.pendingCount())
    }
}
