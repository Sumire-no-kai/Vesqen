package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryMetricCatalogTest {
    @Test
    fun `probe demand sets contain only known metrics`() {
        assertTrue(TelemetryMetricCatalog.allIds.containsAll(TelemetryMetricCatalog.systemProbeIds))
        assertTrue(TelemetryMetricCatalog.allIds.containsAll(TelemetryMetricCatalog.outputProbeIds))
    }

    @Test
    fun `process data source counters do not activate Android system polling`() {
        assertFalse(
            TelemetryMetricCatalog.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED in
                TelemetryMetricCatalog.systemProbeIds,
        )
        assertFalse(
            TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT in
                TelemetryMetricCatalog.systemProbeIds,
        )
        assertFalse(
            TelemetryMetricCatalog.PLAYBACK_CURRENT_MEDIA_READ_BITRATE in
                TelemetryMetricCatalog.systemProbeIds,
        )
    }

    @Test
    fun `static output declaration does not activate route or USB polling`() {
        assertFalse(TelemetryMetricCatalog.ROUTE_OUTPUT_DECLARATION in TelemetryMetricCatalog.outputProbeIds)
        assertFalse(TelemetryMetricCatalog.ROUTE_LAST_STRATEGY_DECISION in TelemetryMetricCatalog.outputProbeIds)
        assertTrue(TelemetryMetricCatalog.USB_DEVICE_INVENTORY in TelemetryMetricCatalog.outputProbeIds)
    }

    @Test
    fun `new text descriptors fail closed while explicit safe text stays exportable`() {
        val newText = TelemetryMetricDescriptor(
            id = TelemetryMetricId("test.future_text"),
            section = TelemetrySection.PROCESS,
            valueKind = TelemetryValueKind.TEXT,
        )

        assertEquals(TelemetryExportPolicy.REDACT_TEXT, newText.exportPolicy)
        assertEquals(
            TelemetryExportPolicy.REDACT_TEXT,
            TelemetryMetricCatalog.descriptor(TelemetryMetricCatalog.PROCESS_SOC_MODEL).exportPolicy,
        )
        assertEquals(
            TelemetryExportPolicy.REDACT_TEXT,
            TelemetryMetricCatalog.descriptor(TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME).exportPolicy,
        )
        assertEquals(
            TelemetryExportPolicy.INCLUDE,
            TelemetryMetricCatalog.descriptor(TelemetryMetricCatalog.SOURCE_CODEC_MIME).exportPolicy,
        )
        assertEquals(
            TelemetryExportPolicy.INCLUDE,
            TelemetryMetricCatalog.descriptor(
                TelemetryMetricCatalog.ROUTE_EXTERNAL_VERIFICATION_RECORD,
            ).exportPolicy,
        )
        assertEquals(
            TelemetryExportPolicy.REDACT_TEXT,
            TelemetryMetricCatalog.descriptor(
                TelemetryMetricCatalog.ROUTE_EXTERNAL_VERIFICATION_EVIDENCE,
            ).exportPolicy,
        )
    }
}
