package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbOutputSessionContractDeviceTest {
    @Test
    fun remoteServiceExceptionsRetainTheirReasonsAcrossSessionTransport() {
        listOf(
            android.os.DeadObjectException() to MixerCleanupExceptionReason.SERVICE_UNAVAILABLE,
            android.os.DeadSystemException() to MixerCleanupExceptionReason.SERVICE_UNAVAILABLE,
            android.os.RemoteException() to MixerCleanupExceptionReason.REMOTE_CALL_FAILED,
        ).forEach { (failure, reason) ->
            val tracker = MixerPreferenceCleanupTracker<String> {
                Result.failure(RuntimeException("private", failure))
            }
            tracker.track("preference")
            tracker.clearAll()
            val restored = requireNotNull(UsbOutputSessionContract.fromBundle(
                UsbOutputSessionContract.toBundle(UsbOutputStatus(mixerCleanup = tracker.snapshot())),
            ))
            assertEquals(setOf(MixerCleanupExceptionReason.OTHER_EXCEPTION, reason), restored.mixerCleanup.exceptionReasons)
        }
    }

    @Test
    fun officialBitPerfectRuntimeSupportSurvivesSessionBundleRoundTrip() {
        val support = OfficialMixerApiSupport(
            androidRelease = "9",
            apiLevel = 28,
            mixerApiAvailable = false,
        )

        val restored = requireNotNull(
            UsbOutputSessionContract.fromBundle(
                UsbOutputSessionContract.toBundle(
                    UsbOutputStatus(officialMixerApiSupport = support),
                ),
            ),
        )

        assertEquals(support, restored.officialMixerApiSupport)
    }
    @Test
    fun originalFailureAndCleanupEvidenceSurviveSessionRoundTrip() {
        val original = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.FAILED,
            failure = UsbOutputFailure.DEVICE_DISCONNECTED,
            failureOrigin = UsbOutputFailureOrigin.ROUTE_CHANGE,
            decisionCode = "strict_usb.device_disconnected",
            mixerCleanup = MixerCleanupStatus(1, setOf("java.lang.SecurityException")),
        )
        assertEquals(original, UsbOutputSessionContract.fromBundle(UsbOutputSessionContract.toBundle(original)))
    }

}
