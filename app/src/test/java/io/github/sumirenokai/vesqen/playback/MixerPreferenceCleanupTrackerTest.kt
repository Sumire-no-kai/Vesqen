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
