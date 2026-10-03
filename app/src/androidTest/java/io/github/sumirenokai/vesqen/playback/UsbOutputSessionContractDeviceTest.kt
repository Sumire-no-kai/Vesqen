package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbOutputSessionContractDeviceTest {
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
            decisionCode = "strict_usb.device_disconnected",
            mixerCleanup = MixerCleanupStatus(1, setOf("java.lang.SecurityException")),
        )
        assertEquals(original, UsbOutputSessionContract.fromBundle(UsbOutputSessionContract.toBundle(original)))
    }

}
