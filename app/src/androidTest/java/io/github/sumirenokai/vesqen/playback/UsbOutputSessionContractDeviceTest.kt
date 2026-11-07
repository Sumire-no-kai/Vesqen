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

    @Test
    fun ownUsbOutputDetailsSurviveSessionRoundTrip() {
        val original = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.FAILED,
            failure = UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED,
            deviceName = "JBL Flip 7",
            decisionCode = "direct_usb.rate_unsupported",
            directDetails = DirectUsbDetails(
                sourceCodec = "FLAC",
                sourceSampleRateHz = 96_000,
                sourceBitDepth = 24,
                sourceChannelCount = 1,
                deviceSampleRatesHz = listOf(44_100..44_100, 8_000..96_000),
                deviceMaxBitsAtSourceRate = 16,
                deviceChannelCount = 2,
                wireSampleRateHz = 48_000,
                wireBitDepth = 24,
            ),
            directUsbAvailable = true,
        )
        assertEquals(original, UsbOutputSessionContract.fromBundle(UsbOutputSessionContract.toBundle(original)))
    }

}
