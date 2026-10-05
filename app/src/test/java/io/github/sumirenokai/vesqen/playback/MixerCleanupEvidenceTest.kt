package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MixerCleanupEvidenceTest {
    @Test
    fun `each exception type has a stable reason including system server wrappers`() {
        val cases = mapOf(
            "java.lang.SecurityException" to MixerCleanupExceptionReason.PERMISSION_DENIED,
            "android.os.DeadObjectException" to MixerCleanupExceptionReason.SERVICE_UNAVAILABLE,
            "android.os.DeadSystemException" to MixerCleanupExceptionReason.SERVICE_UNAVAILABLE,
            "android.os.DeadSystemRuntimeException" to MixerCleanupExceptionReason.SERVICE_UNAVAILABLE,
            "android.os.RemoteException" to MixerCleanupExceptionReason.REMOTE_CALL_FAILED,
            "java.lang.IllegalArgumentException" to MixerCleanupExceptionReason.INVALID_ARGUMENT,
            "java.lang.IllegalStateException" to MixerCleanupExceptionReason.INVALID_STATE,
            "java.lang.UnsupportedOperationException" to MixerCleanupExceptionReason.UNSUPPORTED_OPERATION,
            "java.lang.RuntimeException" to MixerCleanupExceptionReason.OTHER_EXCEPTION,
            "vendor.UnknownException" to MixerCleanupExceptionReason.OTHER_EXCEPTION,
        )
        cases.forEach { (type, reason) ->
            assertEquals(type, setOf(reason), MixerCleanupStatus(1, setOf(type)).exceptionReasons)
        }
        // An old bundle or a rejected Boolean carries no evidence of an exception.
        assertEquals(emptySet<MixerCleanupExceptionReason>(), MixerCleanupStatus(1).exceptionReasons)
    }

    @Test
    fun `thrown and returned wrapped failures retain causes but never private messages`() {
        listOf(false, true).forEach { thrown ->
            val failure = RuntimeException("/storage/private/song.flac", SecurityException("device secret"))
            val tracker = MixerPreferenceCleanupTracker<String> {
                if (thrown) throw failure else Result.failure(failure)
            }
            tracker.track("device")
            assertFalse(tracker.clearAll())
            assertEquals(setOf("java.lang.RuntimeException", "java.lang.SecurityException"), tracker.snapshot().exceptionTypes)
            assertEquals(setOf(MixerCleanupExceptionReason.OTHER_EXCEPTION, MixerCleanupExceptionReason.PERMISSION_DENIED), tracker.snapshot().exceptionReasons)
            assertFalse(tracker.snapshot().toString().contains("secret"))
            assertFalse(tracker.snapshot().toString().contains("song.flac"))
        }
    }

    @Test
    fun `exception traversal terminates for cycles and deep chains`() {
        val first = IllegalStateException("private")
        val second = SecurityException("private", first)
        first.initCause(second)
        assertEquals(setOf("java.lang.IllegalStateException", "java.lang.SecurityException"), cleanupExceptionTypes(first))
        var deep: Throwable = SecurityException("private")
        repeat(8) { deep = RuntimeException(deep) }
        assertEquals(setOf("java.lang.RuntimeException"), cleanupExceptionTypes(deep))
    }

    @Test
    fun `cleanup rejection or exception preserves every original failure through publication`() {
        listOf(Result.success(false), Result.failure<Boolean>(SecurityException("private"))).forEach { cleanup ->
            UsbOutputFailure.entries.forEachIndexed { index, failure ->
                val tracker = MixerPreferenceCleanupTracker<String> { cleanup }
                tracker.track("preference")
                assertFalse(tracker.clearAll())
                val status = UsbOutputStatus(
                    mode = UsbOutputMode.STRICT_BIT_PERFECT,
                    phase = UsbOutputPhase.FAILED,
                    failure = failure,
                    failureOrigin = UsbOutputFailureOrigin.ROUTE_CHANGE,
                    decisionCode = "strict_usb.${failure.name.lowercase(java.util.Locale.ROOT)}",
                    observedAtEpochMs = 100,
                    observedAtElapsedRealtimeMs = 80,
                    generation = index.toLong() + 1,
                    mixerCleanup = tracker.snapshot(),
                )
                val repository = UsbOutputStateRepository()
                var observed = UsbOutputStatus()
                repository.addListener { observed = it }
                repository.publish(status)
                assertEquals(status, observed)
                assertEquals(failure, observed.failure)
                assertEquals(status.decisionCode, observed.decisionCode)
                assertEquals(1, observed.mixerCleanup.pendingCount)
                assertEquals(OutputDeclaration.BIT_PERFECT_FAILED, observed.declaration)
            }
        }
    }
}
