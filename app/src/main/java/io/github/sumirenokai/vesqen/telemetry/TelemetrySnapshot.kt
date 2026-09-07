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
    POWER,
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
    COUNT_PER_SECOND,
    CELSIUS,
    VOLTS,
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

    data class UsbInventory(val value: UsbInventoryReading) : TelemetryReading
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
    /** Monotonic time used for all duration and rate calculations. */
    val startedAtElapsedRealtimeMs: Long = startedAtEpochMs,
    val endedAtElapsedRealtimeMs: Long = endedAtEpochMs,
) {
    init {
        require(startedAtEpochMs >= 0) { "Telemetry window start cannot be negative" }
        require(endedAtEpochMs >= 0) { "Telemetry window end cannot be negative" }
        require(startedAtElapsedRealtimeMs >= 0) { "Telemetry monotonic window start cannot be negative" }
        require(endedAtElapsedRealtimeMs > startedAtElapsedRealtimeMs) {
            "Telemetry windows must have positive monotonic duration"
        }
    }

    val durationMs: Long
        get() = endedAtElapsedRealtimeMs - startedAtElapsedRealtimeMs
}

enum class TelemetryUnavailableReason {
    NO_ACTIVE_PLAYBACK,
    NOT_SAMPLED,
    NOT_EXPOSED_BY_PLATFORM,
    UNSUPPORTED_ANDROID_VERSION,
    UNSUPPORTED_DEVICE,
    PERMISSION_NOT_GRANTED,
    WARMING_UP,
    SOURCE_DID_NOT_REPORT,
    NOT_APPLICABLE,
    TEMPORARILY_UNAVAILABLE,
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
    val observedAtElapsedRealtimeMs: Long
    val reading: TelemetryReading?

    data class Measured(
        override val reading: TelemetryReading,
        override val source: TelemetryDataSource,
        override val observedAtEpochMs: Long,
        override val observedAtElapsedRealtimeMs: Long = observedAtEpochMs,
    ) : TelemetryEvidence {
        override val confidence: TelemetryConfidence = TelemetryConfidence.MEASURED

        init {
            require(observedAtEpochMs >= 0) { "Telemetry timestamps cannot be negative" }
            require(observedAtElapsedRealtimeMs >= 0) { "Telemetry monotonic timestamps cannot be negative" }
        }
    }

    data class Derived(
        override val reading: TelemetryReading,
        override val source: TelemetryDataSource,
        override val observedAtEpochMs: Long,
        val window: TelemetryWindow,
        val calculationId: String,
        val inputMetricIds: Set<TelemetryMetricId>,
        val operands: Map<String, Double>,
        override val observedAtElapsedRealtimeMs: Long = observedAtEpochMs,
    ) : TelemetryEvidence {
        override val confidence: TelemetryConfidence = TelemetryConfidence.DERIVED

        init {
            require(observedAtEpochMs >= 0) { "Telemetry timestamps cannot be negative" }
            require(observedAtElapsedRealtimeMs >= 0) { "Telemetry monotonic timestamps cannot be negative" }
            require(calculationId.matches(IDENTIFIER_PATTERN)) {
                "Derived telemetry must use a stable calculation id"
            }
            require(inputMetricIds.isNotEmpty()) { "Derived telemetry must identify its input metrics" }
            require(operands.isNotEmpty()) { "Derived telemetry must retain its raw operands" }
            require(operands.all { (name, value) ->
                name.matches(IDENTIFIER_PATTERN) && value.isFinite()
            }) { "Derived telemetry operands must use stable ids and finite values" }
            require(observedAtElapsedRealtimeMs >= window.endedAtElapsedRealtimeMs) {
                "Derived telemetry cannot be observed before its monotonic source window ends"
            }
        }
    }

    data class Estimated(
        override val reading: TelemetryReading,
        override val source: TelemetryDataSource,
        override val observedAtEpochMs: Long,
        val methodId: String,
        val inputMetricIds: Set<TelemetryMetricId> = emptySet(),
        override val observedAtElapsedRealtimeMs: Long = observedAtEpochMs,
    ) : TelemetryEvidence {
        override val confidence: TelemetryConfidence = TelemetryConfidence.ESTIMATED

        init {
            require(observedAtEpochMs >= 0) { "Telemetry timestamps cannot be negative" }
            require(observedAtElapsedRealtimeMs >= 0) { "Telemetry monotonic timestamps cannot be negative" }
            require(methodId.matches(IDENTIFIER_PATTERN)) {
                "Estimated telemetry must use a stable method id"
            }
        }
    }

    data class Unavailable(
        val reason: TelemetryUnavailableReason,
        override val observedAtEpochMs: Long,
        override val source: TelemetryDataSource? = null,
        val detail: String? = null,
        override val observedAtElapsedRealtimeMs: Long = observedAtEpochMs,
    ) : TelemetryEvidence {
        override val confidence: TelemetryConfidence = TelemetryConfidence.UNAVAILABLE
        override val reading: TelemetryReading? = null

        init {
            require(observedAtEpochMs >= 0) { "Telemetry timestamps cannot be negative" }
            require(observedAtElapsedRealtimeMs >= 0) { "Telemetry monotonic timestamps cannot be negative" }
            require(detail == null || detail.isNotBlank()) { "Unavailable detail cannot be blank" }
        }
    }
}

data class TelemetryMetric(
    val id: TelemetryMetricId,
    val section: TelemetrySection,
    val evidence: TelemetryEvidence,
) {
    init {
        TelemetryMetricCatalog.requireValid(this)
    }

}

/** Structured USB facts keep each permission/interface tuple together and intentionally have no
 * serial number or usbfs-path field. Android audio endpoints are separate because public APIs do
 * not reliably map them back to a particular UsbDevice. */
data class UsbInventoryReading(
    val hostDevices: List<UsbHostDeviceReading>,
    val audioOutputEndpoints: List<UsbAudioOutputEndpointReading>,
)

data class UsbHostDeviceReading(
    val snapshotKey: String,
    val manufacturerName: String?,
    val productName: String?,
    val vendorId: Int,
    val productId: Int,
    val permissionGranted: Boolean,
    val audioInterfaces: List<UsbAudioInterfaceReading>,
) {
    init {
        require(snapshotKey.isNotBlank()) { "USB snapshot keys cannot be blank" }
        require(vendorId in 0..0xffff && productId in 0..0xffff) { "USB ids must be unsigned 16-bit values" }
    }
}

data class UsbAudioInterfaceReading(
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
)

data class UsbAudioOutputEndpointReading(
    val snapshotKey: String,
    val productName: String?,
    val type: String,
    /** Empty platform arrays mean arbitrary values; the explicit flags preserve that meaning. */
    val sampleRatesHz: List<Int>,
    val arbitrarySampleRate: Boolean,
    val channelCounts: List<Int>,
    val arbitraryChannelCount: Boolean,
    val encodings: List<String>,
    val arbitraryEncoding: Boolean,
) {
    init {
        require(snapshotKey.isNotBlank()) { "USB audio endpoint keys cannot be blank" }
        require(type.isNotBlank()) { "USB audio endpoint types cannot be blank" }
        require(sampleRatesHz.all { it > 0 }) { "USB sample rates must be positive" }
        require(channelCounts.all { it > 0 }) { "USB channel counts must be positive" }
        require(encodings.all(String::isNotBlank)) { "USB encodings cannot be blank" }
    }
}

enum class TelemetryEventKind {
    MEDIA_ITEM_CHANGED,
    PLAYBACK_STATE_CHANGED,
    FORMAT_CHANGED,
    ROUTE_CHANGED,
    UNDERRUN,
    SEEK_COMPLETED,
    DECODER_INITIALIZED,
    OUTPUT_INITIALIZED,
    USB_ATTACHED,
    USB_DETACHED,
    DIAGNOSTIC_RECORDING_STARTED,
    DIAGNOSTIC_RECORDING_STOPPED,
    ERROR,
}

enum class TelemetryEventSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class TelemetryEvent(
    /** Monotonically increasing inside one telemetry runtime and allowed to span playback sessions. */
    val sequence: Long,
    val kind: TelemetryEventKind,
    val severity: TelemetryEventSeverity,
    val occurredAtEpochMs: Long,
    val occurredAtElapsedRealtimeMs: Long = occurredAtEpochMs,
    /** Stable, localizable code; raw exceptions and file paths do not belong here. */
    val code: String,
    val playbackSessionId: String? = null,
    val relatedMetricIds: Set<TelemetryMetricId> = emptySet(),
) {
    init {
        require(sequence >= 0) { "Telemetry event sequence cannot be negative" }
        require(occurredAtEpochMs >= 0) { "Telemetry event timestamps cannot be negative" }
        require(occurredAtElapsedRealtimeMs >= 0) { "Telemetry monotonic event timestamps cannot be negative" }
        require(code.matches(IDENTIFIER_PATTERN)) { "Telemetry event codes must be stable identifiers" }
        require(playbackSessionId == null || playbackSessionId.isNotBlank()) {
            "Telemetry event playback session id cannot be blank"
        }
        relatedMetricIds.forEach(TelemetryMetricCatalog::requireKnown)
    }
}

data class TelemetrySnapshot(
    val capturedAtEpochMs: Long,
    val capturedAtElapsedRealtimeMs: Long = capturedAtEpochMs,
    val playbackSessionId: String? = null,
    val metrics: List<TelemetryMetric> = emptyList(),
    val recentEvents: List<TelemetryEvent> = emptyList(),
) {
    init {
        require(capturedAtEpochMs >= 0) { "Telemetry snapshot timestamps cannot be negative" }
        require(capturedAtElapsedRealtimeMs >= 0) { "Telemetry snapshot monotonic timestamps cannot be negative" }
        require(playbackSessionId == null || playbackSessionId.isNotBlank()) {
            "Playback session id cannot be blank"
        }
        require(metrics.map(TelemetryMetric::id).distinct().size == metrics.size) {
            "A telemetry snapshot cannot contain duplicate metric ids"
        }
        require(recentEvents.zipWithNext().all { (first, second) -> first.sequence < second.sequence }) {
            "Telemetry events must have a strictly increasing sequence"
        }
        require(recentEvents.all { event ->
            event.occurredAtElapsedRealtimeMs <= capturedAtElapsedRealtimeMs
        }) {
            "Telemetry events cannot occur after their containing snapshot"
        }
        require(metrics.all { metric ->
            metric.evidence.observedAtElapsedRealtimeMs <= capturedAtElapsedRealtimeMs
        }) {
            "Telemetry evidence cannot be observed after its containing snapshot"
        }
        require(metrics.all { metric ->
            val derived = metric.evidence as? TelemetryEvidence.Derived
            derived == null || derived.window.endedAtElapsedRealtimeMs <= capturedAtElapsedRealtimeMs
        }) {
            "Telemetry derivation windows cannot end after their containing snapshot"
        }
        val availableMetricIds = metrics.mapTo(mutableSetOf(), TelemetryMetric::id)
        metrics.forEach { metric ->
            val inputs = when (val evidence = metric.evidence) {
                is TelemetryEvidence.Derived -> evidence.inputMetricIds
                is TelemetryEvidence.Estimated -> evidence.inputMetricIds
                else -> emptySet()
            }
            require(inputs.all(availableMetricIds::contains)) {
                "Derived and estimated telemetry inputs must be retained in the same snapshot"
            }
        }
    }

    fun metric(id: TelemetryMetricId): TelemetryMetric? = metrics.firstOrNull { it.id == id }

    fun metricsIn(section: TelemetrySection): List<TelemetryMetric> = metrics.filter { it.section == section }

    companion object {
        fun empty(
            capturedAtEpochMs: Long = 0,
            capturedAtElapsedRealtimeMs: Long = capturedAtEpochMs,
        ): TelemetrySnapshot = TelemetrySnapshot(
            capturedAtEpochMs = capturedAtEpochMs,
            capturedAtElapsedRealtimeMs = capturedAtElapsedRealtimeMs,
        )
    }
}

private val IDENTIFIER_PATTERN = Regex("^[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)+$")
