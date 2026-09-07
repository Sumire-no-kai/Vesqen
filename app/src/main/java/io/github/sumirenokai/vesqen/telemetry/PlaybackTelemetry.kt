package io.github.sumirenokai.vesqen.telemetry

import kotlinx.coroutines.flow.Flow

enum class TelemetryRefreshInterval(val milliseconds: Long) {
    QUARTER_SECOND(250),
    HALF_SECOND(500),
    ONE_SECOND(1_000),
    TWO_SECONDS(2_000),
    FIVE_SECONDS(5_000),
}

enum class TelemetryPowerMode {
    STANDARD,
    LOW_POWER,
}

sealed interface TelemetryMetricSelection {
    /** The Module chooses the stable listener-facing summary set. */
    data object Default : TelemetryMetricSelection

    data class Explicit(val metricIds: Set<TelemetryMetricId>) : TelemetryMetricSelection {
        init {
            require(metricIds.isNotEmpty()) { "An explicit telemetry selection cannot be empty" }
            metricIds.forEach(TelemetryMetricCatalog::requireKnown)
        }
    }
}

data class TelemetryObservation(
    val refreshInterval: TelemetryRefreshInterval = TelemetryRefreshInterval.ONE_SECOND,
    val derivedWindowMs: Long = 5_000,
    val powerMode: TelemetryPowerMode = TelemetryPowerMode.STANDARD,
    val selection: TelemetryMetricSelection = TelemetryMetricSelection.Default,
) {
    init {
        val effectiveIntervalMs = when (powerMode) {
            TelemetryPowerMode.STANDARD -> refreshInterval.milliseconds
            TelemetryPowerMode.LOW_POWER -> maxOf(refreshInterval.milliseconds, LOW_POWER_MIN_INTERVAL_MS)
        }
        require(derivedWindowMs in MIN_DERIVATION_WINDOW_MS..MAX_DERIVATION_WINDOW_MS) {
            "A telemetry derivation window must be between $MIN_DERIVATION_WINDOW_MS and $MAX_DERIVATION_WINDOW_MS ms"
        }
        require(derivedWindowMs >= effectiveIntervalMs) {
            "A telemetry derivation window cannot be shorter than its effective sampling interval"
        }
    }

    private companion object {
        const val MIN_DERIVATION_WINDOW_MS = 250L
        const val LOW_POWER_MIN_INTERVAL_MS = 2_000L
        const val MAX_DERIVATION_WINDOW_MS = 60_000L
    }
}

/**
 * The single external seam for Audio Proof observations.
 *
 * Collection activates only the sampling needed by [observation]. Cancelling collection releases
 * that demand; implementations may share internal samplers across concurrent collectors. The Flow
 * must emit a current snapshot first and never perform system or player polling on the UI thread.
 */
interface PlaybackTelemetry {
    fun observe(observation: TelemetryObservation = TelemetryObservation()): Flow<TelemetrySnapshot>
}
