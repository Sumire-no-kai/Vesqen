package io.github.sumirenokai.vesqen.ui.chain

import io.github.sumirenokai.vesqen.telemetry.TelemetryPowerMode
import io.github.sumirenokai.vesqen.telemetry.TelemetryRefreshInterval
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainTelemetryPresentationTest {
    @Test
    fun `SI presentation applies decimal engineering prefixes without changing the reading`() {
        val sampleRate = requireNotNull(decimalEngineeringPresentation(96_000.0, TelemetryUnit.HERTZ))
        val duration = requireNotNull(decimalEngineeringPresentation(1_500.0, TelemetryUnit.MILLISECONDS))
        val decodeTime = requireNotNull(decimalEngineeringPresentation(2_500.0, TelemetryUnit.NANOSECONDS))
        val current = requireNotNull(decimalEngineeringPresentation(-1_500.0, TelemetryUnit.MILLIAMPERES))

        assertEquals(96.0, sampleRate.value, 1e-9)
        assertEquals("kHz", sampleRate.unitSymbol)
        assertEquals(1.5, duration.value, 1e-9)
        assertEquals("s", duration.unitSymbol)
        assertEquals(2.5, decodeTime.value, 1e-9)
        assertEquals("µs", decodeTime.unitSymbol)
        assertEquals(-1.5, current.value, 1e-9)
        assertEquals("A", current.unitSymbol)
        assertNull(decimalEngineeringPresentation(87.0, TelemetryUnit.PERCENT))
    }

    @Test
    fun `low power effective interval does not mark a valid snapshot stale`() {
        val snapshot = TelemetrySnapshot.empty(capturedAtElapsedRealtimeMs = 1_000)

        assertFalse(
            isTelemetrySnapshotStale(
                snapshot = snapshot,
                nowElapsedRealtimeMs = 4_999,
                refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
                powerMode = TelemetryPowerMode.LOW_POWER,
            ),
        )
        assertTrue(
            isTelemetrySnapshotStale(
                snapshot = snapshot,
                nowElapsedRealtimeMs = 5_001,
                refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
                powerMode = TelemetryPowerMode.LOW_POWER,
            ),
        )
    }

    @Test
    fun `evidence age uses the largest whole unit`() {
        fun age(seconds: Long, extraMs: Long = 0) = evidenceAge(seconds * 1_000 + extraMs)

        assertEquals(EvidenceAge(EvidenceAgeUnit.NOW, 0), evidenceAge(0))
        assertEquals(EvidenceAge(EvidenceAgeUnit.NOW, 0), evidenceAge(999))
        assertEquals(EvidenceAge(EvidenceAgeUnit.NOW, 0), evidenceAge(-5_000))
        assertEquals(EvidenceAge(EvidenceAgeUnit.SECONDS, 1), age(1))
        assertEquals(EvidenceAge(EvidenceAgeUnit.SECONDS, 59), age(59, extraMs = 999))
        assertEquals(EvidenceAge(EvidenceAgeUnit.MINUTES, 1), age(60))
        assertEquals(EvidenceAge(EvidenceAgeUnit.MINUTES, 59), age(3_599))
        assertEquals(EvidenceAge(EvidenceAgeUnit.HOURS, 1), age(3_600))
        assertEquals(EvidenceAge(EvidenceAgeUnit.HOURS, 23), age(86_399))
        assertEquals(EvidenceAge(EvidenceAgeUnit.DAYS, 1), age(86_400))
        // The device review showed "399368 seconds ago" for values kept from a session 4.6 days earlier.
        assertEquals(EvidenceAge(EvidenceAgeUnit.DAYS, 4), age(399_368))
    }

    @Test
    fun `a snapshot without a playback session describes the last playback`() {
        assertFalse(describesLastPlayback(null))
        assertTrue(describesLastPlayback(TelemetrySnapshot.empty(capturedAtElapsedRealtimeMs = 1_000)))
        assertFalse(
            describesLastPlayback(
                TelemetrySnapshot(capturedAtEpochMs = 1_000, playbackSessionId = "session-1"),
            ),
        )
    }

    @Test
    fun `every evidence age plural exists in English and Chinese`() {
        val plurals = listOf("seconds", "minutes", "hours", "days").map { "chain_updated_${it}_ago" }
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-zh-rCN/strings.xml")) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
            val nodes = document.getElementsByTagName("plurals")
            val defined = (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            for (name in plurals) {
                val text = defined[name]
                assertTrue("$path is missing plural $name", text != null)
                assertTrue("$path plural $name must show the count", text!!.contains("%1\$d"))
            }
        }
    }
}
