package io.github.sumirenokai.vesqen.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** #249: a failed DSD track is reported with its own facts, which are no more private than PCM's. */
class DsdDeviceReportTest {
    @Test
    fun `a failed DSD track keeps its format and DSD rate`() {
        for (rate in listOf(2_822_400, 24_576_000)) {
            val raw = FailedTrackFormat(codecMime = "audio/x-dsf", sampleRateHz = rate, bitDepth = 1, channelCount = 2, codecLabel = "DSD")
            val reported = DeviceReportPrivacy.format(raw)
            assertEquals(raw, reported)
            assertFalse(reported.privacyFiltered)
        }
        assertEquals(null, DeviceReportPrivacy.format(FailedTrackFormat(sampleRateHz = 24_576_001)).sampleRateHz)
        for (container in listOf("dsf", "dsdiff")) {
            assertEquals(container, DeviceReportPrivacy.format(FailedTrackFormat(container = container)).container)
        }
    }
}
