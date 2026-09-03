package io.github.sumirenokai.vesqen.telemetry

@JvmInline
value class TelemetryMetricId(val value: String) {
    init {
        require(value.matches(IDENTIFIER_PATTERN)) {
            "Telemetry metric ids must be stable dot-separated identifiers"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class TelemetrySourceId(val value: String) {
    init {
        require(value.matches(IDENTIFIER_PATTERN)) {
            "Telemetry source ids must be stable dot-separated identifiers"
        }
    }

    override fun toString(): String = value
}

data class TelemetryDataSource(
    val id: TelemetrySourceId,
    /** Optional implementation detail retained for diagnostics, never used as a UI label. */
    val detail: String? = null,
) {
    init {
        require(detail == null || detail.isNotBlank()) { "Telemetry source detail cannot be blank" }
    }
}

enum class TelemetrySection {
    SOURCE,
    DECODER,
    PROCESSING,
    PLAYBACK,
    PROCESS,
    ROUTE,
    USB,
}

enum class TelemetryUnit {
    BITS,
    BYTES,
    BYTES_PER_SECOND,
    BITS_PER_SECOND,
    HERTZ,
    MILLISECONDS,
    NANOSECONDS,
    PERCENT,
    COUNT,
    CELSIUS,
    MILLIAMPERES,
    MILLIWATTS,
}

sealed interface TelemetryReading {
    data class Text(val value: String) : TelemetryReading {
        init {
            require(value.isNotBlank()) { "Telemetry text cannot be blank" }
        }
    }

    data class Flag(val value: Boolean) : TelemetryReading

    data class Integer(
        val value: Long,
        val unit: TelemetryUnit,
    ) : TelemetryReading

    data class Decimal(
        val value: Double,
        val unit: TelemetryUnit,
    ) : TelemetryReading {
        init {
            require(value.isFinite()) { "Telemetry decimals must be finite" }
        }
    }
}

enum class TelemetryConfidence {
    MEASURED,
    DERIVED,
    ESTIMATED,
    UNAVAILABLE,
}

data class TelemetryWindow(
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
) {
    init {
        require(startedAtEpochMs >= 0) { "Telemetry window start cannot be negative" }
        require(endedAtEpochMs > startedAtEpochMs) { "Telemetry windows must have positive duration" }
    }

    val durationMs: Long
        get() = endedAtEpochMs - startedAtEpochMs
}

enum class TelemetryUnavailableReason {
    NO_ACTIVE_PLAYBACK,
    NOT_SAMPLED,
    NOT_EXPOSED_BY_PLATFORM,
    UNSUPPORTED_ANDROID_VERSION,
    UNSUPPORTED_DEVICE,
    PERMISSION_NOT_GRANTED,
    UNKNOWN,
}

/**
 * Evidence and reading are one sealed value so an unavailable metric cannot accidentally retain a
 * stale number, and a derived value cannot omit its window or calculation.
 */
sealed interface TelemetryEvidence {
    val confidence: TelemetryConfidence
    val source: TelemetryDataSource?
    val observedAtEpochMs: Long
    val reading: TelemetryReading?

    data class Measured(
        override val reading: TelemetryReading,
        override val source: TelemetryDataSource,
        override val observedAtEpochMs: Long,
    ) : TelemetryEvidence {
        override val confidence: TelemetryConfidence = TelemetryConfidence.MEASURED

        init {
            require(observedAtEpochMs >= 0) { "Telemetry timestamps cannot be negative" }
        }
    }

    data class Derived(
        override val reading: TelemetryReading,
        override val source: TelemetryDataSource,
        override val observedAtEpochMs: Long,
        val window: TelemetryWindow,
        val calculation: String,
    ) : TelemetryEvidence {
        override val confidence: TelemetryConfidence = TelemetryConfidence.DERIVED

        init {
            require(observedAtEpochMs >= 0) { "Telemetry timestamps cannot be negative" }
            require(calculation.isNotBlank()) { "Derived telemetry must explain its calculation" }
            require(observedAtEpochMs >= window.endedAtEpochMs) {
                "Derived telemetry cannot be observed before its source window ends"
            }
        }
    }

    data class Estimated(
        override val reading: TelemetryReading,
        override val source: TelemetryDataSource,
        override val observedAtEpochMs: Long,
        val method: String,
    ) : TelemetryEvidence {
        override val confidence: TelemetryConfidence = TelemetryConfidence.ESTIMATED

        init {
            require(observedAtEpochMs >= 0) { "Telemetry timestamps cannot be negative" }
            require(method.isNotBlank()) { "Estimated telemetry must explain its method" }
        }
    }

    data class Unavailable(
        val reason: TelemetryUnavailableReason,
        override val observedAtEpochMs: Long,
        override val source: TelemetryDataSource? = null,
        val detail: String? = null,
    ) : TelemetryEvidence {
        override val confidence: TelemetryConfidence = TelemetryConfidence.UNAVAILABLE
        override val reading: TelemetryReading? = null

        init {
            require(observedAtEpochMs >= 0) { "Telemetry timestamps cannot be negative" }
            require(detail == null || detail.isNotBlank()) { "Unavailable detail cannot be blank" }
        }
    }
}

data class TelemetryMetric(
    val id: TelemetryMetricId,
    val section: TelemetrySection,
    val evidence: TelemetryEvidence,
)

enum class TelemetryEventKind {
    PLAYBACK_STATE_CHANGED,
    FORMAT_CHANGED,
    ROUTE_CHANGED,
    UNDERRUN,
    SEEK_COMPLETED,
    ERROR,
}

enum class TelemetryEventSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class TelemetryEvent(
    /** Monotonically increasing inside one playback session or diagnostic recording. */
    val sequence: Long,
    val kind: TelemetryEventKind,
    val severity: TelemetryEventSeverity,
    val occurredAtEpochMs: Long,
    /** Stable, localizable code; raw exceptions and file paths do not belong here. */
    val code: String,
    val relatedMetricIds: Set<TelemetryMetricId> = emptySet(),
) {
    init {
        require(sequence >= 0) { "Telemetry event sequence cannot be negative" }
        require(occurredAtEpochMs >= 0) { "Telemetry event timestamps cannot be negative" }
        require(code.matches(IDENTIFIER_PATTERN)) { "Telemetry event codes must be stable identifiers" }
    }
}

data class TelemetrySnapshot(
    val capturedAtEpochMs: Long,
    val playbackSessionId: String? = null,
    val metrics: List<TelemetryMetric> = emptyList(),
    val recentEvents: List<TelemetryEvent> = emptyList(),
) {
    init {
        require(capturedAtEpochMs >= 0) { "Telemetry snapshot timestamps cannot be negative" }
        require(playbackSessionId == null || playbackSessionId.isNotBlank()) {
            "Playback session id cannot be blank"
        }
        require(metrics.map(TelemetryMetric::id).distinct().size == metrics.size) {
            "A telemetry snapshot cannot contain duplicate metric ids"
        }
        require(recentEvents.zipWithNext().all { (first, second) -> first.sequence < second.sequence }) {
            "Telemetry events must have a strictly increasing sequence"
        }
        require(recentEvents.all { event -> event.occurredAtEpochMs <= capturedAtEpochMs }) {
            "Telemetry events cannot occur after their containing snapshot"
        }
    }

    fun metric(id: TelemetryMetricId): TelemetryMetric? = metrics.firstOrNull { it.id == id }

    fun metricsIn(section: TelemetrySection): List<TelemetryMetric> = metrics.filter { it.section == section }

    companion object {
        fun empty(capturedAtEpochMs: Long = 0): TelemetrySnapshot = TelemetrySnapshot(
            capturedAtEpochMs = capturedAtEpochMs,
        )
    }
}

private val IDENTIFIER_PATTERN = Regex("^[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)+$")
