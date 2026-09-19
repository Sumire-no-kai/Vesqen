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
}
