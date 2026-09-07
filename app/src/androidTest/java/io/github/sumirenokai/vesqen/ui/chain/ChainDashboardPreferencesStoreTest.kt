package io.github.sumirenokai.vesqen.ui.chain

import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryPowerMode
import io.github.sumirenokai.vesqen.telemetry.TelemetryRefreshInterval
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChainDashboardPreferencesStoreTest {
    /** Run with m2PersistencePhase=write, then =verify in a new instrumentation process. */
    @Test
    fun completeLayoutAndResetPersist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("m2PersistencePhase") ?: "roundtrip"
        require(phase in setOf("write", "verify", "roundtrip"))
        val name = "chain-dashboard-process-test"
        val owner = context.getSharedPreferences("$name-owner", 0)
        val store = ChainDashboardPreferencesStore(context, name)
        val selected = listOf(TelemetryMetricCatalog.PROCESS_CPU_PERCENT, TelemetryMetricCatalog.SOURCE_SAMPLE_RATE)
        val expected = ChainDashboardPreferences(
            refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
            powerMode = TelemetryPowerMode.LOW_POWER,
            derivedWindow = ChainDerivedWindow.THIRTY_SECONDS,
            historyLength = ChainHistoryLength.FIVE_MINUTES,
            selectedMetricIds = selected,
            pinnedMetricIds = selected.take(1),
            metricOrder = TelemetryMetricCatalog.allIds.toList().reversed(),
            groupOrder = TelemetrySection.entries.reversed(),
            viewMode = ChainMetricViewMode.CHART,
            unitDisplayMode = ChainUnitDisplayMode.RAW,
        ).normalized()
        if (phase != "verify") {
            store.save(expected)
            assertEquals(expected, store.load())
            check(owner.edit().putInt("writerPid", android.os.Process.myPid()).commit())
        }
        if (phase == "write") {
            // Exercise Android's normal Activity-stop boundary, which drains pending apply()
            // writes. Killing an activity-less instrumentation process skips that boundary.
            androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use { }
            return
        }
        try {
            if (phase == "verify") assertNotEquals(owner.getInt("writerPid", -1), android.os.Process.myPid())
            assertEquals(expected, store.load())
            assertEquals(ChainDashboardPreferences().normalized(), store.reset())
            assertEquals(ChainDashboardPreferences().normalized(), ChainDashboardPreferencesStore(context, name).load())
        } finally {
            context.deleteSharedPreferences(name)
            context.deleteSharedPreferences("$name-owner")
        }
    }

    @Test
    fun calculationWindowAndHistoryLengthSurviveRepositoryReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "chain-dashboard-test-${System.nanoTime()}"
        try {
            ChainDashboardPreferencesStore(context, preferencesName).save(
                ChainDashboardPreferences(
                    derivedWindow = ChainDerivedWindow.THIRTY_SECONDS,
                    historyLength = ChainHistoryLength.FIVE_MINUTES,
                    unitDisplayMode = ChainUnitDisplayMode.SI,
                ),
            )

            val restored = ChainDashboardPreferencesStore(context, preferencesName).load()

            assertEquals(ChainDerivedWindow.THIRTY_SECONDS, restored.derivedWindow)
            assertEquals(ChainHistoryLength.FIVE_MINUTES, restored.historyLength)
            assertEquals(ChainUnitDisplayMode.SI, restored.unitDisplayMode)
        } finally {
            context.getSharedPreferences(preferencesName, 0).edit().clear().commit()
        }
    }
}
