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
    fun `dashboard groups put pinned metrics first and leave out sections with nothing shown`() {
        val preferences = ChainDashboardPreferences(
            selectedMetricIds = listOf(
                TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
                TelemetryMetricCatalog.SOURCE_BIT_DEPTH,
                TelemetryMetricCatalog.USB_AUDIO_DEVICE_COUNT,
                TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT,
            ),
            pinnedMetricIds = listOf(TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT, TelemetryMetricCatalog.SOURCE_BIT_DEPTH),
            groupOrder = listOf(TelemetrySection.USB) + TelemetrySection.entries,
        ).normalized()

        assertEquals(
            listOf(
                ChainDashboardGroup(
                    section = null,
                    // Pinned metrics follow the saved metric order, not the order they were pinned in.
                    metricIds = listOf(TelemetryMetricCatalog.SOURCE_BIT_DEPTH, TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT),
                ),
                ChainDashboardGroup(TelemetrySection.USB, listOf(TelemetryMetricCatalog.USB_AUDIO_DEVICE_COUNT)),
                ChainDashboardGroup(TelemetrySection.SOURCE, listOf(TelemetryMetricCatalog.SOURCE_SAMPLE_RATE)),
            ),
            preferences.dashboardGroups(),
        )
    }

    @Test
    fun `hiding a metric unpins it and the last shown metric cannot be hidden`() {
        val rate = TelemetryMetricCatalog.SOURCE_SAMPLE_RATE
        val depth = TelemetryMetricCatalog.SOURCE_BIT_DEPTH
        val preferences = ChainDashboardPreferences(selectedMetricIds = listOf(rate, depth), pinnedMetricIds = listOf(depth))

        val hidden = preferences.withMetricShown(depth, shown = false)
        assertEquals(listOf(rate), hidden.selectedMetricIds)
        assertEquals(emptyList<TelemetryMetricId>(), hidden.pinnedMetricIds)

        assertEquals(hidden, hidden.withMetricShown(rate, shown = false))
        assertEquals(listOf(rate, depth), hidden.withMetricShown(depth, shown = true).selectedMetricIds)
        assertEquals(preferences, preferences.withMetricShown(rate, shown = true))
    }

    @Test
    fun `only a shown metric can be pinned`() {
        val rate = TelemetryMetricCatalog.SOURCE_SAMPLE_RATE
        val hiddenMetric = TelemetryMetricCatalog.SOURCE_FILE_SIZE
        val preferences = ChainDashboardPreferences(selectedMetricIds = listOf(rate))

        assertEquals(listOf(rate), preferences.withMetricPinned(rate, pinned = true).pinnedMetricIds)
        assertEquals(preferences, preferences.withMetricPinned(hiddenMetric, pinned = true))
        assertEquals(
            emptyList<TelemetryMetricId>(),
            preferences.withMetricPinned(rate, pinned = true).withMetricPinned(rate, pinned = false).pinnedMetricIds,
        )
    }

    @Test
    fun `a metric moves only within its own group and hidden metrics keep their places`() {
        val container = TelemetryMetricCatalog.SOURCE_CONTAINER
        val mime = TelemetryMetricCatalog.SOURCE_CODEC_MIME
        val label = TelemetryMetricCatalog.SOURCE_CODEC_LABEL
        val rate = TelemetryMetricCatalog.SOURCE_SAMPLE_RATE
        val decoder = TelemetryMetricCatalog.DECODER_NAME
        val preferences = ChainDashboardPreferences(
            selectedMetricIds = listOf(container, label, rate, decoder),
        ).normalized()

        val moved = preferences.withMetricMoved(container, targetIndex = 2)

        assertEquals(listOf(label, rate, container), moved.dashboardGroups().first().metricIds)
        // The hidden MIME type stays between the two metrics it sat between.
        assertEquals(listOf(mime, label, rate, container), moved.metricOrder.take(4))
        assertEquals(preferences, preferences.withMetricMoved(container, targetIndex = 3))
        assertEquals(preferences, preferences.withMetricMoved(decoder, targetIndex = 1))
    }

    @Test
    fun `pinned metrics reorder among themselves`() {
        val rate = TelemetryMetricCatalog.SOURCE_SAMPLE_RATE
        val underruns = TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT
        val preferences = ChainDashboardPreferences(
            selectedMetricIds = listOf(rate, underruns),
            pinnedMetricIds = listOf(rate, underruns),
        ).normalized()

        val moved = preferences.withMetricMoved(underruns, targetIndex = 0)

        assertEquals(listOf(ChainDashboardGroup(null, listOf(underruns, rate))), moved.dashboardGroups())
    }

    @Test
    fun `a section moves among the shown sections and hidden sections keep their places`() {
        val preferences = ChainDashboardPreferences(
            selectedMetricIds = listOf(
                TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
                TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT,
                TelemetryMetricCatalog.USB_AUDIO_DEVICE_COUNT,
            ),
        ).normalized()

        val moved = preferences.withSectionMoved(TelemetrySection.USB, targetIndex = 0)

        assertEquals(
            listOf(TelemetrySection.USB, TelemetrySection.SOURCE, TelemetrySection.PLAYBACK),
            moved.dashboardGroups().map(ChainDashboardGroup::section),
        )
        assertEquals(
            listOf(
                TelemetrySection.USB,
                TelemetrySection.SOURCE,
                TelemetrySection.DECODER,
                TelemetrySection.PROCESSING,
                TelemetrySection.PLAYBACK,
                TelemetrySection.PROCESS,
                TelemetrySection.POWER,
                TelemetrySection.ROUTE,
            ),
            moved.groupOrder,
        )
        assertEquals(preferences, preferences.withSectionMoved(TelemetrySection.DECODER, targetIndex = 0))
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
