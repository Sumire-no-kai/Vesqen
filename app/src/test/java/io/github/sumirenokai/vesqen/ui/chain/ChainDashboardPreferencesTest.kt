package io.github.sumirenokai.vesqen.ui.chain

import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryPowerMode
import io.github.sumirenokai.vesqen.telemetry.TelemetryRefreshInterval
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainDashboardPreferencesTest {
    @Test
    fun `normalization removes stale ids and repairs all persistent orders`() {
        val first = TelemetryMetricCatalog.descriptors.first().id
        val second = TelemetryMetricCatalog.descriptors[1].id
        val stale = TelemetryMetricId("removed.metric")

        val normalized = ChainDashboardPreferences(
            selectedMetricIds = listOf(first, stale, first),
            pinnedMetricIds = listOf(second, first, stale),
            metricOrder = listOf(second, stale, second),
            groupOrder = listOf(TelemetrySection.USB, TelemetrySection.USB),
        ).normalized()

        assertEquals(listOf(first), normalized.selectedMetricIds)
        assertEquals(listOf(first), normalized.pinnedMetricIds)
        assertEquals(second, normalized.metricOrder.first())
        assertEquals(TelemetryMetricCatalog.allIds, normalized.metricOrder.toSet())
        assertEquals(TelemetrySection.USB, normalized.groupOrder.first())
        assertEquals(TelemetrySection.entries.toSet(), normalized.groupOrder.toSet())
        assertFalse(stale in normalized.metricOrder)
    }

    @Test
    fun `empty persisted selection fails safe to catalog defaults`() {
        val normalized = ChainDashboardPreferences(selectedMetricIds = emptyList()).normalized()

        assertTrue(normalized.selectedMetricIds.isNotEmpty())
        assertEquals(TelemetryMetricCatalog.defaultIds, normalized.selectedMetricIds.toSet())
    }

    @Test
    fun `calculation window is never shorter than effective sampling cadence`() {
        val standard = ChainDashboardPreferences(
            refreshInterval = TelemetryRefreshInterval.FIVE_SECONDS,
            derivedWindow = ChainDerivedWindow.QUARTER_SECOND,
        ).normalized()
        val lowPower = ChainDashboardPreferences(
            refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
            powerMode = TelemetryPowerMode.LOW_POWER,
            derivedWindow = ChainDerivedWindow.HALF_SECOND,
        ).normalized()

        assertEquals(ChainDerivedWindow.FIVE_SECONDS, standard.derivedWindow)
        assertEquals(ChainDerivedWindow.TWO_SECONDS, lowPower.derivedWindow)
    }

    @Test
    fun `in memory repository retains calculation and history choices`() {
        val repository = InMemoryChainDashboardPreferencesRepository()

        repository.save(
            repository.load().copy(
                derivedWindow = ChainDerivedWindow.THIRTY_SECONDS,
                historyLength = ChainHistoryLength.FIVE_MINUTES,
                unitDisplayMode = ChainUnitDisplayMode.RAW,
            ),
        )

        assertEquals(ChainDerivedWindow.THIRTY_SECONDS, repository.load().derivedWindow)
        assertEquals(ChainHistoryLength.FIVE_MINUTES, repository.load().historyLength)
        assertEquals(ChainUnitDisplayMode.RAW, repository.load().unitDisplayMode)
    }

    @Test
    fun `in memory repository applies the same normalization and reset contract`() {
        val repository = InMemoryChainDashboardPreferencesRepository()
        val onlyMetric = TelemetryMetricCatalog.descriptors.last().id

        repository.save(
            repository.load().copy(
                selectedMetricIds = listOf(onlyMetric),
                pinnedMetricIds = listOf(onlyMetric),
                groupOrder = TelemetrySection.entries.reversed(),
            ),
        )

        assertEquals(listOf(onlyMetric), repository.load().selectedMetricIds)
        assertEquals(listOf(onlyMetric), repository.load().pinnedMetricIds)
        assertEquals(TelemetrySection.entries.reversed(), repository.load().groupOrder)
        assertEquals(ChainDashboardPreferences().normalized(), repository.reset())
    }
}
