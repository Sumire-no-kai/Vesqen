package io.github.sumirenokai.vesqen.ui.screens

import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry.CapturedAtMs
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry.measured
import io.github.sumirenokai.vesqen.ui.chain.missingMetricCount
import org.junit.Assert.assertEquals
import org.junit.Test

/** The summary's notice counts what the summary shows, so a complete session reads as live. */
class ChainSummaryNoticeTest {
    @Test
    fun `a complete speaker session misses nothing the summary shows`() {
        // The Bluetooth facts, the transport bit rate and the verification record do not apply.
        assertEquals(0, missing(session()))
    }

    @Test
    fun `values Android 11 and 12 never expose do not count`() {
        val snapshot = session(
            Metrics.ROUTE_SELECTED_SYSTEM_TYPE to unavailable(TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM),
            Metrics.ROUTE_ANTICIPATED_TYPE to unavailable(TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION),
        )

        assertEquals(0, missing(snapshot))
    }

    @Test
    fun `a shown value this sample could not read counts`() {
        val snapshot = session(Metrics.DECODER_NAME to unavailable(TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE))

        assertEquals(1, missing(snapshot))
    }

    @Test
    fun `bluetooth codec facts count only on a bluetooth route`() {
        val codec = Metrics.ROUTE_BLUETOOTH_CODEC to unavailable(TelemetryUnavailableReason.PERMISSION_NOT_GRANTED)

        assertEquals(0, missing(session(codec)))
        assertEquals(
            1,
            missing(session(codec, Metrics.ROUTE_SELECTED_SYSTEM_TYPE to measured(TelemetryReading.Text("bluetooth")))),
        )
    }

    @Test
    fun `a fallback counts only when the summary falls back to it`() {
        val noBitrate = Metrics.SOURCE_AVERAGE_BITRATE to unavailable(TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT)
        val noAnticipated = Metrics.ROUTE_ANTICIPATED_TYPE to unavailable(TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE)

        assertEquals(0, missing(session(noBitrate, noAnticipated)))
        // Without a bit depth the source card shows the bit rate, so both are shown missing.
        assertEquals(
            2,
            missing(session(noBitrate, Metrics.SOURCE_BIT_DEPTH to unavailable(TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT))),
        )
    }

    private fun missing(snapshot: TelemetrySnapshot): Int =
        missingMetricCount(snapshot, chainSummaryShownMetricIds(snapshot.metrics.associateBy(TelemetryMetric::id)))

    private fun session(vararg overrides: Pair<TelemetryMetricId, TelemetryEvidence>): TelemetrySnapshot =
        FakeAppSegmentTelemetry.speakerSession(overrides.toMap())

    private fun unavailable(reason: TelemetryUnavailableReason) = TelemetryEvidence.Unavailable(reason, CapturedAtMs)
}
