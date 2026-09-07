package io.github.sumirenokai.vesqen.ui.chain

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryPowerMode
import io.github.sumirenokai.vesqen.telemetry.TelemetryRefreshInterval
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection

private const val LOW_POWER_MIN_INTERVAL_MS = 2_000L

enum class ChainMetricViewMode {
    COMPACT,
    DETAILED,
    CHART,
}

/** Controls presentation only; captured readings and diagnostic exports always retain base units. */
enum class ChainUnitDisplayMode {
    /** Existing human-friendly formatting, including Android's localized file-size formatter. */
    AUTO,

    /** Deterministic decimal engineering prefixes (k, M, G, m, µ, n) where they are meaningful. */
    SI,

    /** The unscaled value and unit carried by [io.github.sumirenokai.vesqen.telemetry.TelemetryReading]. */
    RAW,
}

enum class ChainDerivedWindow(val milliseconds: Long) {
    QUARTER_SECOND(250),
    HALF_SECOND(500),
    ONE_SECOND(1_000),
    TWO_SECONDS(2_000),
    FIVE_SECONDS(5_000),
    TEN_SECONDS(10_000),
    THIRTY_SECONDS(30_000),
    ONE_MINUTE(60_000),
}

enum class ChainHistoryLength(val milliseconds: Long) {
    FIFTEEN_SECONDS(15_000),
    THIRTY_SECONDS(30_000),
    ONE_MINUTE(60_000),
    TWO_MINUTES(120_000),
    FIVE_MINUTES(300_000),
}

data class ChainDashboardPreferences(
    val refreshInterval: TelemetryRefreshInterval = TelemetryRefreshInterval.ONE_SECOND,
    val powerMode: TelemetryPowerMode = TelemetryPowerMode.STANDARD,
    val derivedWindow: ChainDerivedWindow = ChainDerivedWindow.FIVE_SECONDS,
    val historyLength: ChainHistoryLength = ChainHistoryLength.ONE_MINUTE,
    val selectedMetricIds: List<TelemetryMetricId> = TelemetryMetricCatalog.defaultIds.toList(),
    val pinnedMetricIds: List<TelemetryMetricId> = emptyList(),
    val metricOrder: List<TelemetryMetricId> = TelemetryMetricCatalog.descriptors.map { it.id },
    val groupOrder: List<TelemetrySection> = TelemetrySection.entries,
    val viewMode: ChainMetricViewMode = ChainMetricViewMode.DETAILED,
    val unitDisplayMode: ChainUnitDisplayMode = ChainUnitDisplayMode.AUTO,
) {
    fun normalized(): ChainDashboardPreferences {
        val knownIds = TelemetryMetricCatalog.descriptors.map { it.id }
        val knownSet = knownIds.toSet()
        val normalizedOrder = (metricOrder.filter(knownSet::contains) + knownIds).distinct()
        val normalizedSelection = selectedMetricIds
            .filter(knownSet::contains)
            .distinct()
            .ifEmpty { TelemetryMetricCatalog.defaultIds.toList() }
        val normalizedPinned = pinnedMetricIds
            .filter(normalizedSelection.toSet()::contains)
            .distinct()
        val normalizedGroups = (groupOrder + TelemetrySection.entries).distinct()
        val effectiveRefreshMs = if (powerMode == TelemetryPowerMode.LOW_POWER) {
            maxOf(refreshInterval.milliseconds, LOW_POWER_MIN_INTERVAL_MS)
        } else {
            refreshInterval.milliseconds
        }
        val normalizedDerivedWindow = ChainDerivedWindow.entries.firstOrNull {
            it.milliseconds >= maxOf(derivedWindow.milliseconds, effectiveRefreshMs)
        } ?: ChainDerivedWindow.ONE_MINUTE
        return copy(
            derivedWindow = normalizedDerivedWindow,
            selectedMetricIds = normalizedSelection,
            pinnedMetricIds = normalizedPinned,
            metricOrder = normalizedOrder,
            groupOrder = normalizedGroups,
        )
    }

}

interface ChainDashboardPreferencesRepository {
    fun load(): ChainDashboardPreferences

    fun save(preferences: ChainDashboardPreferences)

    fun reset(): ChainDashboardPreferences
}

class ChainDashboardPreferencesStore private constructor(
    private val preferences: SharedPreferences,
) : ChainDashboardPreferencesRepository {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    internal constructor(context: Context, preferencesName: String) : this(
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
    )

    override fun load(): ChainDashboardPreferences = ChainDashboardPreferences(
        refreshInterval = preferences.enumValue(KEY_REFRESH_INTERVAL, TelemetryRefreshInterval.ONE_SECOND),
        powerMode = preferences.enumValue(KEY_POWER_MODE, TelemetryPowerMode.STANDARD),
        derivedWindow = preferences.enumValue(KEY_DERIVED_WINDOW, ChainDerivedWindow.FIVE_SECONDS),
        historyLength = preferences.enumValue(KEY_HISTORY_LENGTH, ChainHistoryLength.ONE_MINUTE),
        selectedMetricIds = preferences.metricIds(KEY_SELECTED_METRICS),
        pinnedMetricIds = preferences.metricIds(KEY_PINNED_METRICS),
        metricOrder = preferences.metricIds(KEY_METRIC_ORDER),
        groupOrder = preferences.enumValues(KEY_GROUP_ORDER),
        viewMode = preferences.enumValue(KEY_VIEW_MODE, ChainMetricViewMode.DETAILED),
        unitDisplayMode = preferences.enumValue(KEY_UNIT_DISPLAY_MODE, ChainUnitDisplayMode.AUTO),
    ).normalized()

    override fun save(preferences: ChainDashboardPreferences) {
        val normalized = preferences.normalized()
        this.preferences.edit {
            putString(KEY_REFRESH_INTERVAL, normalized.refreshInterval.name)
            putString(KEY_POWER_MODE, normalized.powerMode.name)
            putString(KEY_DERIVED_WINDOW, normalized.derivedWindow.name)
            putString(KEY_HISTORY_LENGTH, normalized.historyLength.name)
            putString(KEY_SELECTED_METRICS, normalized.selectedMetricIds.joinToString(",") { it.value })
            putString(KEY_PINNED_METRICS, normalized.pinnedMetricIds.joinToString(",") { it.value })
            putString(KEY_METRIC_ORDER, normalized.metricOrder.joinToString(",") { it.value })
            putString(KEY_GROUP_ORDER, normalized.groupOrder.joinToString(",") { it.name })
            putString(KEY_VIEW_MODE, normalized.viewMode.name)
            putString(KEY_UNIT_DISPLAY_MODE, normalized.unitDisplayMode.name)
        }
    }

    override fun reset(): ChainDashboardPreferences = ChainDashboardPreferences().normalized().also { defaults ->
        preferences.edit { clear() }
        save(defaults)
    }

    private fun SharedPreferences.metricIds(key: String): List<TelemetryMetricId> =
        getString(key, null)
            ?.split(',')
            ?.mapNotNull { raw -> runCatching { TelemetryMetricId(raw) }.getOrNull() }
            .orEmpty()

    private inline fun <reified T : Enum<T>> SharedPreferences.enumValue(key: String, fallback: T): T =
        getString(key, null)?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } } ?: fallback

    private inline fun <reified T : Enum<T>> SharedPreferences.enumValues(key: String): List<T> =
        getString(key, null)
            ?.split(',')
            ?.mapNotNull { raw -> enumValues<T>().firstOrNull { it.name == raw } }
            .orEmpty()

    private companion object {
        const val PREFERENCES_NAME = "vesqen_chain_dashboard"
        const val KEY_REFRESH_INTERVAL = "refresh_interval"
        const val KEY_POWER_MODE = "power_mode"
        const val KEY_DERIVED_WINDOW = "derived_window"
        const val KEY_HISTORY_LENGTH = "history_length"
        const val KEY_SELECTED_METRICS = "selected_metrics"
        const val KEY_PINNED_METRICS = "pinned_metrics"
        const val KEY_METRIC_ORDER = "metric_order"
        const val KEY_GROUP_ORDER = "group_order"
        const val KEY_VIEW_MODE = "view_mode"
        const val KEY_UNIT_DISPLAY_MODE = "unit_display_mode"
    }
}

class InMemoryChainDashboardPreferencesRepository(
    initial: ChainDashboardPreferences = ChainDashboardPreferences(),
) : ChainDashboardPreferencesRepository {
    private var stored = initial.normalized()

    override fun load(): ChainDashboardPreferences = stored

    override fun save(preferences: ChainDashboardPreferences) {
        stored = preferences.normalized()
    }

    override fun reset(): ChainDashboardPreferences = ChainDashboardPreferences().normalized().also { stored = it }
}
