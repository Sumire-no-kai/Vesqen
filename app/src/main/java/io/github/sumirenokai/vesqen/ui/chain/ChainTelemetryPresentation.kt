package io.github.sumirenokai.vesqen.ui.chain

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.telemetry.TelemetryConfidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryPowerMode
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetryRefreshInterval
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

internal sealed interface ChainObservationState {
    data object Waiting : ChainObservationState

    @Immutable
    data class Content(val snapshot: TelemetrySnapshot) : ChainObservationState

    @Immutable
    data class Failed(val lastSnapshot: TelemetrySnapshot?) : ChainObservationState
}

@Immutable
internal data class ChainHistoryPoint(
    val elapsedRealtimeMs: Long,
    val value: Double,
    val confidence: TelemetryConfidence,
    val sourceId: String?,
    val windowDurationMs: Long?,
)

internal class ChainMetricHistoryBuffer(
    maximumDurationMs: Long = 60_000,
    private val maximumPoints: Int = 4_096,
) {
    private val histories = linkedMapOf<TelemetryMetricId, ArrayDeque<ChainHistoryPoint>>()
    private var sessionId: String? = null
    private var maximumDurationMs = maximumDurationMs
    private var latestElapsedRealtimeMs: Long? = null

    init {
        require(maximumDurationMs > 0) { "A chart history duration must be positive" }
        require(maximumPoints > 1) { "A chart history needs at least two points" }
    }

    fun add(snapshot: TelemetrySnapshot) {
        val clockMovedBackwards = latestElapsedRealtimeMs?.let { previous ->
            snapshot.capturedAtElapsedRealtimeMs < previous
        } == true
        if (snapshot.playbackSessionId != sessionId || clockMovedBackwards) {
            histories.clear()
            sessionId = snapshot.playbackSessionId
            latestElapsedRealtimeMs = null
        }
        latestElapsedRealtimeMs = maxOf(
            latestElapsedRealtimeMs ?: snapshot.capturedAtElapsedRealtimeMs,
            snapshot.capturedAtElapsedRealtimeMs,
        )
        snapshot.metrics.forEach { metric ->
            val descriptor = runCatching { TelemetryMetricCatalog.descriptor(metric.id) }.getOrNull()
            if (descriptor?.chartable != true) return@forEach
            val value = metric.evidence.reading.numericValue() ?: return@forEach
            val points = histories.getOrPut(metric.id, ::ArrayDeque)
            val point = ChainHistoryPoint(
                elapsedRealtimeMs = metric.evidence.observedAtElapsedRealtimeMs,
                value = value,
                confidence = metric.evidence.confidence,
                sourceId = metric.evidence.source?.id?.value,
                windowDurationMs = (metric.evidence as? TelemetryEvidence.Derived)?.window?.durationMs,
            )
            if (points.lastOrNull()?.elapsedRealtimeMs?.let { it > point.elapsedRealtimeMs } == true) {
                return@forEach
            }
            if (points.lastOrNull()?.elapsedRealtimeMs == point.elapsedRealtimeMs) {
                if (points.isNotEmpty()) points.removeLast()
            }
            points.addLast(point)
            while (points.size > maximumPoints) points.removeFirst()
        }
        prune(referenceElapsedRealtimeMs = requireNotNull(latestElapsedRealtimeMs))
    }

    fun points(metricId: TelemetryMetricId): List<ChainHistoryPoint> = histories[metricId]?.toList().orEmpty()

    fun clear() {
        histories.clear()
        sessionId = null
        latestElapsedRealtimeMs = null
    }

    fun updateRetentionDuration(maximumDurationMs: Long) {
        require(maximumDurationMs > 0) { "A chart history duration must be positive" }
        this.maximumDurationMs = maximumDurationMs
        latestElapsedRealtimeMs?.let(::prune)
    }

    private fun prune(referenceElapsedRealtimeMs: Long) {
        val firstRetainedElapsedRealtimeMs = referenceElapsedRealtimeMs - maximumDurationMs
        histories.values.forEach { points ->
            while (
                points.firstOrNull()?.elapsedRealtimeMs?.let { it < firstRetainedElapsedRealtimeMs } == true
            ) {
                points.removeFirst()
            }
        }
        histories.entries.removeAll { it.value.isEmpty() }
    }
}

internal fun TelemetryReading?.numericValue(): Double? = when (this) {
    is TelemetryReading.Decimal -> value
    is TelemetryReading.Integer -> value.toDouble()
    else -> null
}

internal fun telemetryMetricLabel(context: Context, metricId: TelemetryMetricId): String =
    telemetryMetricLabelResource(metricId.value)?.let(context::getString)
        ?: context.getString(R.string.chain_metric_unknown)

internal fun telemetrySectionLabel(context: Context, section: TelemetrySection): String = context.getString(
    when (section) {
        TelemetrySection.SOURCE -> R.string.chain_section_source
        TelemetrySection.DECODER -> R.string.chain_section_decoder
        TelemetrySection.PROCESSING -> R.string.chain_section_processing
        TelemetrySection.PLAYBACK -> R.string.chain_section_playback
        TelemetrySection.PROCESS -> R.string.chain_section_process
        TelemetrySection.POWER -> R.string.chain_section_power
        TelemetrySection.ROUTE -> R.string.chain_section_route
        TelemetrySection.USB -> R.string.chain_section_usb
    },
)

internal fun formatTelemetryReading(
    context: Context,
    reading: TelemetryReading?,
    unitDisplayMode: ChainUnitDisplayMode = ChainUnitDisplayMode.AUTO,
): String = when (reading) {
    null -> context.getString(R.string.unavailable)
    is TelemetryReading.Text -> localizedTelemetryText(context, reading.value)
    is TelemetryReading.Flag -> context.getString(
        if (reading.value) R.string.chain_value_yes else R.string.chain_value_no,
    )
    is TelemetryReading.Integer -> when (unitDisplayMode) {
        ChainUnitDisplayMode.AUTO -> formatTelemetryNumberAuto(context, reading.value.toDouble(), reading.unit)
        ChainUnitDisplayMode.SI -> formatTelemetryNumberSi(context, reading.value.toDouble(), reading.unit)
        ChainUnitDisplayMode.RAW -> formatTelemetryNumberRaw(context, formatRawNumber(context, reading.value), reading.unit)
    }
    is TelemetryReading.Decimal -> when (unitDisplayMode) {
        ChainUnitDisplayMode.AUTO -> formatTelemetryNumberAuto(context, reading.value, reading.unit)
        ChainUnitDisplayMode.SI -> formatTelemetryNumberSi(context, reading.value, reading.unit)
        ChainUnitDisplayMode.RAW -> formatTelemetryNumberRaw(context, formatRawNumber(context, reading.value), reading.unit)
    }
    is TelemetryReading.UsbInventory -> context.getString(
        R.string.chain_usb_inventory_summary,
        context.resources.getQuantityString(
            R.plurals.chain_usb_host_device_count,
            reading.value.hostDevices.size,
            reading.value.hostDevices.size,
        ),
        context.resources.getQuantityString(
            R.plurals.chain_usb_audio_output_count,
            reading.value.audioOutputEndpoints.size,
            reading.value.audioOutputEndpoints.size,
        ),
    )
}

internal fun telemetryConfidenceLabel(context: Context, confidence: TelemetryConfidence): String = context.getString(
    when (confidence) {
        TelemetryConfidence.MEASURED -> R.string.chain_confidence_measured
        TelemetryConfidence.DERIVED -> R.string.chain_confidence_derived
        TelemetryConfidence.ESTIMATED -> R.string.chain_confidence_estimated
        TelemetryConfidence.UNAVAILABLE -> R.string.unavailable
    },
)

internal fun telemetryEvidenceSource(context: Context, evidence: TelemetryEvidence): String =
    evidence.source?.id?.value?.let { sourceId ->
        val resource = telemetrySourceLabelResource(sourceId) ?: R.string.chain_source_platform
        context.getString(resource)
    } ?: context.getString(R.string.chain_source_not_available)

internal fun telemetryEvidenceAge(
    context: Context,
    evidence: TelemetryEvidence,
    nowElapsedRealtimeMs: Long,
): String {
    val elapsedSeconds = ((nowElapsedRealtimeMs - evidence.observedAtElapsedRealtimeMs).coerceAtLeast(0) / 1_000).toInt()
    return if (elapsedSeconds == 0) {
        context.getString(R.string.chain_updated_now)
    } else {
        context.resources.getQuantityString(
            R.plurals.chain_updated_seconds_ago,
            elapsedSeconds,
            elapsedSeconds,
        )
    }
}

internal fun telemetryEvidenceMethod(context: Context, evidence: TelemetryEvidence): String? = when (evidence) {
    is TelemetryEvidence.Derived -> context.getString(
        telemetryDerivedMethodLabelResource(evidence.calculationId)
            ?: R.string.chain_method_documented_calculation,
    )
    is TelemetryEvidence.Estimated -> context.getString(
        telemetryEstimatedMethodLabelResource(evidence.methodId)
            ?: R.string.chain_method_documented_estimate,
    )
    is TelemetryEvidence.Unavailable -> context.getString(
        when (evidence.reason) {
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK -> R.string.chain_unavailable_no_playback
            TelemetryUnavailableReason.NOT_SAMPLED -> R.string.chain_unavailable_not_sampled
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM -> R.string.chain_unavailable_not_exposed
            TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION -> R.string.chain_unavailable_android_version
            TelemetryUnavailableReason.UNSUPPORTED_DEVICE -> R.string.chain_unavailable_device
            TelemetryUnavailableReason.PERMISSION_NOT_GRANTED -> R.string.chain_unavailable_permission
            TelemetryUnavailableReason.WARMING_UP -> R.string.chain_unavailable_warming_up
            TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT -> R.string.chain_unavailable_source
            TelemetryUnavailableReason.NOT_APPLICABLE -> R.string.chain_unavailable_not_applicable
            TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE -> R.string.chain_unavailable_temporary
            TelemetryUnavailableReason.UNKNOWN -> R.string.chain_unavailable_unknown
        },
    )
    is TelemetryEvidence.Measured -> null
}

internal fun telemetryEvidenceWindow(context: Context, evidence: TelemetryEvidence): String? =
    (evidence as? TelemetryEvidence.Derived)?.window?.durationMs?.let { durationMs ->
        context.getString(R.string.chain_window_value, formatSeconds(context, durationMs / 1_000.0))
    }

internal fun chartSummary(
    context: Context,
    label: String,
    points: List<ChainHistoryPoint>,
    unit: TelemetryUnit,
    expectedCadenceMs: Long,
    unitDisplayMode: ChainUnitDisplayMode = ChainUnitDisplayMode.AUTO,
): String? {
    if (points.isEmpty()) return null
    val values = points.map { it.value }
    val spanMs = (points.last().elapsedRealtimeMs - points.first().elapsedRealtimeMs).coerceAtLeast(0)
    val lineBreaks = segmentChartHistory(points, expectedCadenceMs).size.minus(1).coerceAtLeast(0)
    val breakSummary = context.resources.getQuantityString(
        R.plurals.chain_chart_line_breaks,
        lineBreaks,
        lineBreaks,
    )
    val sampleCount = context.resources.getQuantityString(
        R.plurals.chain_chart_sample_count,
        points.size,
        points.size,
    )
    return context.getString(
        R.string.chain_chart_summary,
        label,
        formatTelemetryNumber(context, values.last(), unit, unitDisplayMode),
        formatTelemetryNumber(context, values.min(), unit, unitDisplayMode),
        formatTelemetryNumber(context, values.max(), unit, unitDisplayMode),
        sampleCount,
        formatSeconds(context, spanMs / 1_000.0),
        breakSummary,
    )
}

internal fun telemetryHistorySpan(context: Context, points: List<ChainHistoryPoint>): String {
    val spanMs = if (points.size < 2) 0 else {
        (points.last().elapsedRealtimeMs - points.first().elapsedRealtimeMs).coerceAtLeast(0)
    }
    return formatSeconds(context, spanMs / 1_000.0)
}

internal fun segmentChartHistory(
    points: List<ChainHistoryPoint>,
    expectedCadenceMs: Long,
): List<List<ChainHistoryPoint>> {
    if (points.isEmpty()) return emptyList()
    val segments = mutableListOf(mutableListOf(points.first()))
    points.zipWithNext().forEach { (previous, current) ->
        val samplingGap = current.elapsedRealtimeMs - previous.elapsedRealtimeMs > expectedCadenceMs * 2
        val evidenceChanged = previous.confidence != current.confidence ||
            previous.sourceId != current.sourceId ||
            previous.windowDurationMs != current.windowDurationMs
        if (samplingGap || evidenceChanged) segments += mutableListOf(current) else segments.last() += current
    }
    return segments
}

internal fun elapsedChartFraction(
    elapsedRealtimeMs: Long,
    firstElapsedRealtimeMs: Long,
    lastElapsedRealtimeMs: Long,
): Float {
    val span = (lastElapsedRealtimeMs - firstElapsedRealtimeMs).coerceAtLeast(1)
    return ((elapsedRealtimeMs - firstElapsedRealtimeMs).toDouble() / span)
        .coerceIn(0.0, 1.0)
        .toFloat()
}

internal fun effectiveTelemetryIntervalMs(
    refreshInterval: TelemetryRefreshInterval,
    powerMode: TelemetryPowerMode,
): Long = if (powerMode == TelemetryPowerMode.LOW_POWER) {
    maxOf(refreshInterval.milliseconds, 2_000L)
} else {
    refreshInterval.milliseconds
}

internal fun isTelemetrySnapshotStale(
    snapshot: TelemetrySnapshot,
    nowElapsedRealtimeMs: Long,
    refreshInterval: TelemetryRefreshInterval,
    powerMode: TelemetryPowerMode,
): Boolean {
    val effectiveIntervalMs = effectiveTelemetryIntervalMs(refreshInterval, powerMode)
    val allowedSilence = maxOf(effectiveIntervalMs * 2, 1_500L)
    return nowElapsedRealtimeMs - snapshot.capturedAtElapsedRealtimeMs > allowedSilence
}

private fun formatTelemetryNumber(
    context: Context,
    value: Double,
    unit: TelemetryUnit,
    unitDisplayMode: ChainUnitDisplayMode,
): String = when (unitDisplayMode) {
    ChainUnitDisplayMode.AUTO -> formatTelemetryNumberAuto(context, value, unit)
    ChainUnitDisplayMode.SI -> formatTelemetryNumberSi(context, value, unit)
    ChainUnitDisplayMode.RAW -> formatTelemetryNumberRaw(context, formatRawNumber(context, value), unit)
}

private fun formatTelemetryNumberAuto(context: Context, value: Double, unit: TelemetryUnit): String {
    val absolute = abs(value)
    return when (unit) {
        TelemetryUnit.BYTES -> Formatter.formatShortFileSize(context, value.toLong())
        TelemetryUnit.BYTES_PER_SECOND -> context.getString(
            R.string.chain_unit_per_second,
            Formatter.formatShortFileSize(context, value.toLong()),
        )
        TelemetryUnit.BITS_PER_SECOND -> when {
            absolute >= 1_000_000 -> context.getString(
                R.string.chain_unit_mbps,
                formatNumber(context, value / 1_000_000),
            )
            absolute >= 1_000 -> context.getString(R.string.chain_unit_kbps, formatNumber(context, value / 1_000))
            else -> context.getString(R.string.chain_unit_bps, formatNumber(context, value))
        }
        TelemetryUnit.HERTZ -> if (absolute >= 1_000) {
            context.getString(R.string.chain_unit_khz, formatNumber(context, value / 1_000))
        } else {
            context.getString(R.string.chain_unit_hz, formatNumber(context, value))
        }
        TelemetryUnit.BITS -> context.getString(R.string.chain_unit_bits, formatNumber(context, value))
        TelemetryUnit.MILLISECONDS -> context.getString(R.string.chain_unit_ms, formatNumber(context, value))
        TelemetryUnit.NANOSECONDS -> context.getString(R.string.chain_unit_ns, formatNumber(context, value))
        TelemetryUnit.PERCENT -> context.getString(R.string.chain_unit_percent, formatNumber(context, value))
        TelemetryUnit.COUNT -> formatNumber(context, value)
        TelemetryUnit.COUNT_PER_SECOND -> context.getString(
            R.string.chain_unit_count_per_second,
            formatNumber(context, value),
        )
        TelemetryUnit.CELSIUS -> context.getString(R.string.chain_unit_celsius, formatNumber(context, value))
        TelemetryUnit.VOLTS -> context.getString(R.string.chain_unit_volts, formatNumber(context, value))
        TelemetryUnit.MILLIAMPERES -> context.getString(R.string.chain_unit_milliamperes, formatNumber(context, value))
        TelemetryUnit.MILLIWATTS -> context.getString(R.string.chain_unit_milliwatts, formatNumber(context, value))
    }
}

@Immutable
internal data class ChainEngineeringNumber(
    val value: Double,
    val unitSymbol: String,
)

/**
 * Converts a base-unit reading to deterministic decimal engineering notation.
 * This is deliberately a presentation transform: telemetry storage remains untouched.
 */
internal fun decimalEngineeringPresentation(value: Double, unit: TelemetryUnit): ChainEngineeringNumber? {
    val basis = when (unit) {
        TelemetryUnit.BYTES -> EngineeringBasis(multiplierToBase = 1.0, baseSymbol = "B", minExponent = 0)
        TelemetryUnit.BYTES_PER_SECOND ->
            EngineeringBasis(multiplierToBase = 1.0, baseSymbol = "B/s", minExponent = 0)
        TelemetryUnit.BITS_PER_SECOND ->
            EngineeringBasis(multiplierToBase = 1.0, baseSymbol = "bit/s", minExponent = 0)
        TelemetryUnit.HERTZ -> EngineeringBasis(multiplierToBase = 1.0, baseSymbol = "Hz", minExponent = 0)
        TelemetryUnit.BITS -> EngineeringBasis(multiplierToBase = 1.0, baseSymbol = "bit", minExponent = 0)
        TelemetryUnit.MILLISECONDS ->
            EngineeringBasis(multiplierToBase = 1e-3, baseSymbol = "s", minExponent = -3)
        TelemetryUnit.NANOSECONDS ->
            EngineeringBasis(multiplierToBase = 1e-9, baseSymbol = "s", minExponent = -9)
        TelemetryUnit.VOLTS -> EngineeringBasis(multiplierToBase = 1.0, baseSymbol = "V", minExponent = -9)
        TelemetryUnit.MILLIAMPERES ->
            EngineeringBasis(multiplierToBase = 1e-3, baseSymbol = "A", minExponent = -3)
        TelemetryUnit.MILLIWATTS ->
            EngineeringBasis(multiplierToBase = 1e-3, baseSymbol = "W", minExponent = -3)
        TelemetryUnit.PERCENT,
        TelemetryUnit.COUNT,
        TelemetryUnit.COUNT_PER_SECOND,
        TelemetryUnit.CELSIUS,
        -> return null
    }
    val baseValue = value * basis.multiplierToBase
    val exponent = engineeringExponent(abs(baseValue)).coerceIn(basis.minExponent, 9)
    val divisor = when (exponent) {
        -9 -> 1e-9
        -6 -> 1e-6
        -3 -> 1e-3
        0 -> 1.0
        3 -> 1e3
        6 -> 1e6
        9 -> 1e9
        else -> error("Unsupported engineering exponent: $exponent")
    }
    val prefix = when (exponent) {
        -9 -> "n"
        -6 -> "µ"
        -3 -> "m"
        0 -> ""
        3 -> "k"
        6 -> "M"
        9 -> "G"
        else -> error("Unsupported engineering exponent: $exponent")
    }
    return ChainEngineeringNumber(
        value = baseValue / divisor,
        unitSymbol = prefix + basis.baseSymbol,
    )
}

private data class EngineeringBasis(
    val multiplierToBase: Double,
    val baseSymbol: String,
    val minExponent: Int,
)

private fun engineeringExponent(absoluteValue: Double): Int = when {
    absoluteValue == 0.0 -> 0
    absoluteValue >= 1e9 -> 9
    absoluteValue >= 1e6 -> 6
    absoluteValue >= 1e3 -> 3
    absoluteValue >= 1.0 -> 0
    absoluteValue >= 1e-3 -> -3
    absoluteValue >= 1e-6 -> -6
    else -> -9
}

private fun formatTelemetryNumberSi(context: Context, value: Double, unit: TelemetryUnit): String {
    val engineering = decimalEngineeringPresentation(value, unit)
        ?: return formatTelemetryNumberRaw(context, formatNumber(context, value), unit)
    return context.getString(
        R.string.chain_unit_symbol,
        formatNumber(context, engineering.value),
        engineering.unitSymbol,
    )
}

private fun formatTelemetryNumberRaw(context: Context, formattedValue: String, unit: TelemetryUnit): String =
    when (unit) {
        TelemetryUnit.BYTES -> context.getString(R.string.chain_unit_symbol, formattedValue, "B")
        TelemetryUnit.BYTES_PER_SECOND -> context.getString(R.string.chain_unit_symbol, formattedValue, "B/s")
        TelemetryUnit.BITS_PER_SECOND -> context.getString(R.string.chain_unit_symbol, formattedValue, "bit/s")
        TelemetryUnit.HERTZ -> context.getString(R.string.chain_unit_hz, formattedValue)
        TelemetryUnit.BITS -> context.getString(R.string.chain_unit_symbol, formattedValue, "bit")
        TelemetryUnit.MILLISECONDS -> context.getString(R.string.chain_unit_ms, formattedValue)
        TelemetryUnit.NANOSECONDS -> context.getString(R.string.chain_unit_ns, formattedValue)
        TelemetryUnit.PERCENT -> context.getString(R.string.chain_unit_percent, formattedValue)
        TelemetryUnit.COUNT -> formattedValue
        TelemetryUnit.COUNT_PER_SECOND -> context.getString(R.string.chain_unit_count_per_second, formattedValue)
        TelemetryUnit.CELSIUS -> context.getString(R.string.chain_unit_celsius, formattedValue)
        TelemetryUnit.VOLTS -> context.getString(R.string.chain_unit_volts, formattedValue)
        TelemetryUnit.MILLIAMPERES -> context.getString(R.string.chain_unit_milliamperes, formattedValue)
        TelemetryUnit.MILLIWATTS -> context.getString(R.string.chain_unit_milliwatts, formattedValue)
    }

internal fun formatSeconds(context: Context, seconds: Double): String = context.getString(
    R.string.chain_unit_seconds,
    formatNumber(context, seconds),
)

private fun formatNumber(context: Context, value: Double): String = NumberFormat.getNumberInstance(
    context.resources.configuration.locales[0],
).run {
    maximumFractionDigits = 2
    minimumFractionDigits = 0
    isGroupingUsed = true
    format(value)
}

private fun formatRawNumber(context: Context, value: Number): String = NumberFormat.getNumberInstance(
    context.resources.configuration.locales[0],
).run {
    maximumFractionDigits = 12
    minimumFractionDigits = 0
    isGroupingUsed = true
    format(value)
}

private fun localizedTelemetryText(context: Context, raw: String): String = when (raw.lowercase(Locale.ROOT)) {
    "idle" -> context.getString(R.string.chain_text_idle)
    "buffering" -> context.getString(R.string.chain_text_buffering)
    "ready" -> context.getString(R.string.chain_text_ready)
    "ended" -> context.getString(R.string.chain_text_ended)
    "speaker" -> context.getString(R.string.output_phone_speaker)
    "bluetooth" -> context.getString(R.string.output_bluetooth)
    "wired", "usb", "wired-or-usb" -> context.getString(R.string.output_wired_or_usb)
    "none" -> context.getString(R.string.chain_text_none)
    "light", "moderate", "severe", "critical", "emergency", "shutdown" -> context.getString(
        when (raw.lowercase(Locale.ROOT)) {
            "light" -> R.string.chain_thermal_light
            "moderate" -> R.string.chain_thermal_moderate
            "severe" -> R.string.chain_thermal_severe
            "critical" -> R.string.chain_thermal_critical
            "emergency" -> R.string.chain_thermal_emergency
            else -> R.string.chain_thermal_shutdown
        },
    )
    else -> raw
}

@StringRes
internal fun telemetryMetricLabelResource(metricId: String): Int? = METRIC_LABEL_RESOURCES[metricId]

@StringRes
internal fun telemetryDerivedMethodLabelResource(calculationId: String): Int? =
    DERIVED_METHOD_LABEL_RESOURCES[calculationId]

@StringRes
internal fun telemetryEstimatedMethodLabelResource(methodId: String): Int? =
    ESTIMATED_METHOD_LABEL_RESOURCES[methodId]

@StringRes
internal fun telemetrySourceLabelResource(sourceId: String): Int? = SOURCE_LABEL_RESOURCES[sourceId]

private val DERIVED_METHOD_LABEL_RESOURCES = mapOf(
    "rate.data_source_bytes_per_window" to R.string.chain_method_bytes_per_window,
    "rate.current_media_bytes_per_window" to R.string.chain_method_current_media_bytes_per_window,
    "rate.decoder_input_buffers_per_window" to R.string.chain_method_decoder_input_buffers,
    "rate.decoder_output_buffers_per_window" to R.string.chain_method_decoder_output_buffers,
    "rate.process_cpu_one_core" to R.string.chain_method_process_cpu,
)

private val ESTIMATED_METHOD_LABEL_RESOURCES = mapOf(
    "decoder.path_from_public_flags" to R.string.chain_method_decoder_path,
    "decoder.path_from_public_runtime_facts" to R.string.chain_method_decoder_path,
    "power.whole_device_current_times_voltage" to R.string.chain_method_current_times_voltage,
    "media3.position_from_last_event" to R.string.chain_method_position_from_last_event,
    "audio_track.request_format_pcm_data_rate" to R.string.chain_method_pcm_data_rate,
    "processing.compare_decoder_input_and_audio_track_rates" to R.string.chain_method_rate_conversion,
)

private val SOURCE_LABEL_RESOURCES = mapOf(
    "library.metadata" to R.string.chain_source_library_metadata,
    "media3.analytics" to R.string.chain_source_media3_analytics,
    "media3.decoder_counters" to R.string.chain_source_media3_decoder_counters,
    "media3.player" to R.string.chain_source_media3_player,
    "media3.data_source" to R.string.chain_source_media3_data_source,
    "media3.current_media_data_source" to R.string.chain_source_media3_current_media_data_source,
    "media3.audio_track" to R.string.chain_source_media3_audio_track,
    "android.media_codec" to R.string.chain_source_android_media_codec,
    "android.process" to R.string.chain_source_android_process,
    "android.power" to R.string.chain_source_android_power,
    "android.build" to R.string.chain_source_android_build,
    "android.battery" to R.string.chain_source_android_battery,
    "android.audio_route" to R.string.chain_source_android_audio_route,
    "android.runtime" to R.string.chain_source_android_runtime,
    "android.system_media_route" to R.string.chain_source_android_system_media_route,
    "android.direct_playback_support" to R.string.chain_source_android_direct_capability,
    "android.mixer_attributes" to R.string.chain_source_android_mixer_attributes,
    "android.usb_public_api" to R.string.chain_source_android_usb,
    "vesqen.configuration" to R.string.chain_source_app_configuration,
)

private val METRIC_LABEL_RESOURCES: Map<String, Int> = mapOf(
    "source.container" to R.string.chain_metric_source_container,
    "source.codec_mime" to R.string.chain_metric_source_codec_mime,
    "source.codec_label" to R.string.chain_metric_source_codec_label,
    "source.sample_rate" to R.string.chain_metric_source_sample_rate,
    "source.bit_depth" to R.string.chain_metric_source_bit_depth,
    "source.channel_count" to R.string.chain_metric_source_channel_count,
    "source.average_bitrate" to R.string.chain_metric_source_average_bitrate,
    "source.file_size" to R.string.chain_metric_source_file_size,
    "decoder.name" to R.string.chain_metric_decoder_name,
    "decoder.input_mime" to R.string.chain_metric_decoder_input_mime,
    "decoder.input_sample_rate" to R.string.chain_metric_decoder_input_sample_rate,
    "decoder.input_channel_count" to R.string.chain_metric_decoder_input_channel_count,
    "decoder.initialization_duration" to R.string.chain_metric_decoder_initialization_duration,
    "decoder.software_only" to R.string.chain_metric_decoder_software_only,
    "decoder.hardware_accelerated" to R.string.chain_metric_decoder_hardware_accelerated,
    "decoder.is_vendor" to R.string.chain_metric_decoder_vendor,
    "decoder.path" to R.string.chain_metric_decoder_path,
    "decoder.output_sample_rate" to R.string.chain_metric_decoder_output_sample_rate,
    "decoder.output_encoding" to R.string.chain_metric_decoder_output_encoding,
    "decoder.output_channel_config" to R.string.chain_metric_decoder_output_channel_config,
    "decoder.compressed_frame_size" to R.string.chain_metric_decoder_compressed_frame_size,
    "decoder.input_buffer_rate" to R.string.chain_metric_decoder_input_buffer_rate,
    "decoder.output_buffer_rate" to R.string.chain_metric_decoder_output_buffer_rate,
    "decoder.frame_decode_time" to R.string.chain_metric_decoder_frame_decode_time,
    "decoder.format_change_count" to R.string.chain_metric_decoder_format_change_count,
    "decoder.renderer_queued_input_buffers_total" to R.string.chain_metric_decoder_queued_input_buffers_total,
    "decoder.renderer_rendered_output_buffers_total" to R.string.chain_metric_decoder_rendered_output_buffers_total,
    "decoder.renderer_skipped_output_buffers_total" to R.string.chain_metric_decoder_skipped_output_buffers_total,
    "processing.speed" to R.string.chain_metric_processing_speed,
    "processing.pitch" to R.string.chain_metric_processing_pitch,
    "processing.player_volume" to R.string.chain_metric_processing_player_volume,
    "processing.skip_silence" to R.string.chain_metric_processing_skip_silence,
    "processing.app_dsp_active" to R.string.chain_metric_processing_app_dsp_active,
    "processing.replay_gain_active" to R.string.chain_metric_processing_replay_gain_active,
    "processing.equalizer_active" to R.string.chain_metric_processing_equalizer_active,
    "processing.crossfade_active" to R.string.chain_metric_processing_crossfade_active,
    "processing.loudness_active" to R.string.chain_metric_processing_loudness_active,
    "processing.sample_rate_conversion_detected" to R.string.chain_metric_processing_rate_conversion,
    "playback.state" to R.string.chain_metric_playback_state,
    "playback.is_playing" to R.string.chain_metric_playback_is_playing,
    "playback.last_event_position" to R.string.chain_metric_playback_last_event_position,
    "playback.position" to R.string.chain_metric_playback_position,
    "playback.duration" to R.string.chain_metric_playback_duration,
    "playback.estimated_total_buffered_duration" to R.string.chain_metric_playback_buffered_duration,
    "playback.current_media_bytes_read" to R.string.chain_metric_playback_current_media_bytes_read,
    "playback.current_media_read_bitrate" to R.string.chain_metric_playback_current_media_read_bitrate,
    "playback.pcm_data_rate" to R.string.chain_metric_playback_pcm_data_rate,
    "playback.target_buffer_duration" to R.string.chain_metric_playback_target_buffer_duration,
    "playback.prebuffer_target_duration" to R.string.chain_metric_playback_prebuffer_target_duration,
    "playback.last_seek_latency" to R.string.chain_metric_playback_last_seek_latency,
    "playback.last_gapless_transition_gap" to R.string.chain_metric_playback_last_gapless_transition_gap,
    "playback.audio_track_timestamp" to R.string.chain_metric_playback_audio_track_timestamp,
    "playback.clock_deviation" to R.string.chain_metric_playback_clock_deviation,
    "playback.last_error_code" to R.string.chain_metric_playback_last_error_code,
    "process.data_source_bytes_transferred_since_start" to R.string.chain_metric_process_bytes_transferred,
    "process.data_source_read_throughput" to R.string.chain_metric_process_read_throughput,
    "playback.underrun_count" to R.string.chain_metric_playback_underrun_count,
    "playback.last_underrun_feed_gap" to R.string.chain_metric_playback_underrun_feed_gap,
    "playback.audio_track_sample_rate" to R.string.chain_metric_playback_output_sample_rate,
    "playback.audio_track_encoding" to R.string.chain_metric_playback_output_encoding,
    "playback.audio_track_channel_mask" to R.string.chain_metric_playback_output_channel_mask,
    "playback.audio_track_buffer_size" to R.string.chain_metric_playback_output_buffer_size,
    "playback.offload" to R.string.chain_metric_playback_offload,
    "playback.tunneling" to R.string.chain_metric_playback_tunneling,
    "process.cpu_time" to R.string.chain_metric_process_cpu_time,
    "process.cpu_percent" to R.string.chain_metric_process_cpu_percent,
    "process.java_heap" to R.string.chain_metric_process_java_heap,
    "process.native_heap" to R.string.chain_metric_process_native_heap,
    "process.pss" to R.string.chain_metric_process_pss,
    "process.processor_count" to R.string.chain_metric_process_processor_count,
    "process.thermal_status" to R.string.chain_metric_process_thermal_status,
    "process.soc_model" to R.string.chain_metric_process_soc_model,
    "process.playback_thread_cpu_time" to R.string.chain_metric_process_playback_thread_cpu_time,
    "process.gc_count" to R.string.chain_metric_process_gc_count,
    "process.gc_time" to R.string.chain_metric_process_gc_time,
    "process.cpu_core_state" to R.string.chain_metric_process_cpu_core_state,
    "power.battery_current" to R.string.chain_metric_power_battery_current,
    "power.battery_voltage" to R.string.chain_metric_power_battery_voltage,
    "power.battery_temperature" to R.string.chain_metric_power_battery_temperature,
    "power.device_estimate" to R.string.chain_metric_power_device_estimate,
    "route.selected_system_name" to R.string.chain_metric_route_selected_system_name,
    "route.selected_system_type" to R.string.chain_metric_route_selected_system_type,
    "route.anticipated_name" to R.string.chain_metric_route_anticipated_name,
    "route.anticipated_type" to R.string.chain_metric_route_anticipated_type,
    "route.connected_types" to R.string.chain_metric_route_connected_types,
    "route.request_format_direct_supported" to R.string.chain_metric_route_direct_supported,
    "route.request_format_direct_modes" to R.string.chain_metric_route_direct_modes,
    "route.anticipated_mixer_profile_count" to R.string.chain_metric_route_mixer_profile_count,
    "route.anticipated_preferred_mixer_profile" to R.string.chain_metric_route_preferred_mixer_profile,
    "route.output_declaration" to R.string.chain_metric_route_output_declaration,
    "route.audio_track_request_format" to R.string.chain_metric_route_audio_track_request_format,
    "route.observed_output_format" to R.string.chain_metric_route_observed_output_format,
    "route.bluetooth_codec" to R.string.chain_metric_route_bluetooth_codec,
    "route.bluetooth_connected_names" to R.string.chain_metric_route_bluetooth_connected_names,
    "route.bluetooth_connected_types" to R.string.chain_metric_route_bluetooth_connected_types,
    "route.bluetooth_capabilities" to R.string.chain_metric_route_bluetooth_capabilities,
    "route.bluetooth_configuration" to R.string.chain_metric_route_bluetooth_configuration,
    "route.bluetooth_configured_bitrate" to R.string.chain_metric_route_bluetooth_configured_bitrate,
    "route.bluetooth_transport_bitrate" to R.string.chain_metric_route_bluetooth_transport_bitrate,
    "route.system_music_volume" to R.string.chain_metric_route_system_music_volume,
    "route.system_music_muted" to R.string.chain_metric_route_system_music_muted,
    "route.system_dsp_state" to R.string.chain_metric_route_system_dsp_state,
    "route.last_strategy_decision" to R.string.chain_metric_route_last_strategy_decision,
    "usb.host_supported" to R.string.chain_metric_usb_host_supported,
    "usb.audio_device_count" to R.string.chain_metric_usb_audio_device_count,
    "usb.device_inventory" to R.string.chain_metric_usb_device_inventory,
)
