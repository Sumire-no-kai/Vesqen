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
        require(derivedWindowMs > 0) { "A telemetry derivation window must be positive" }
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
