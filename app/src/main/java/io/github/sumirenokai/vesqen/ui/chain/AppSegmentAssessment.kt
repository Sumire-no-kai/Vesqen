package io.github.sumirenokai.vesqen.ui.chain

import io.github.sumirenokai.vesqen.telemetry.TelemetryConfidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.isBluetoothRouteType

internal enum class AppSegmentStatus { UNCHANGED, MODIFIED, UNKNOWN }
internal enum class SourceCompression { LOSSLESS, LOSSY, UNKNOWN }
internal enum class AppSegmentCondition {
    SPEED, PITCH, PLAYER_VOLUME, SKIP_SILENCE, REPLAY_GAIN, EQUALIZER, LOUDNESS,
    CROSSFADE, APP_DSP, SAMPLE_RATE, CHANNELS, PCM_PRECISION, SOURCE_COMPRESSION,
    FOCUS_GAIN, TRACK_TRANSITION,
}
internal enum class SegmentReason {
    VALUE_CHANGED, PRECISION_LOSS, MISSING, UNAVAILABLE, INSUFFICIENT_CONFIDENCE,
    EXPIRED, INVALID_TIME, UNSUPPORTED_VALUE, DEPENDENCY_CYCLE,
    FOCUS_GAIN_NOT_OBSERVABLE, TRACK_TRANSITION_NOT_OBSERVABLE,
}

internal data class SegmentEvidenceIssue(val metricId: TelemetryMetricId, val reason: SegmentReason)

internal data class AppSegmentCheck(
    val condition: AppSegmentCondition,
    val status: AppSegmentStatus,
    val reason: SegmentReason? = null,
    val issues: List<SegmentEvidenceIssue> = emptyList(),
    /** Original evidence includes provenance, both clocks, unavailable reasons and derivation inputs. */
    val evidence: Map<TelemetryMetricId, TelemetryEvidence> = emptyMap(),
)

internal enum class BluetoothSegmentStatus { LOSSY_OR_UNKNOWN_DEPENDING_ON_CODEC }
internal data class BluetoothSegmentAssessment(
    val selectedRouteEvidence: TelemetryEvidence,
    val status: BluetoothSegmentStatus = BluetoothSegmentStatus.LOSSY_OR_UNKNOWN_DEPENDING_ON_CODEC,
    /** Reserved for #73 route-associated codec evidence; never inferred from source or capabilities. */
    val reportedCodecEvidence: TelemetryEvidence? = null,
)

internal data class AppSegmentAssessment(
    val status: AppSegmentStatus,
    val sourceCompression: SourceCompression,
    val checks: List<AppSegmentCheck>,
    val bluetooth: BluetoothSegmentAssessment?,
    val routeEvidence: Map<TelemetryMetricId, TelemetryEvidence>,
    val routeIssues: List<SegmentEvidenceIssue>,
)

/**
 * F6.5.1 only: this result is not an output declaration. Call again as monotonic time advances.
 * The caller supplies its freshness budget (including its telemetry refresh/power policy).
 * A proven modification is decisive; unresolved checks are still retained alongside it.
 *
 * Today's catalog cannot attest effective focus gain or completion of a track transition/fade.
 * Consequently even otherwise neutral snapshots remain UNKNOWN. UNCHANGED is reserved until
 * those observation gaps can be closed; player volume and static app DSP flags cannot close them.
 */
internal fun assessAppSegment(
    snapshot: TelemetrySnapshot,
    nowElapsedRealtimeMs: Long,
    maxEvidenceAgeMs: Long,
): AppSegmentAssessment {
    require(nowElapsedRealtimeMs >= 0)
    require(maxEvidenceAgeMs >= 0)
    val reader = SegmentEvidenceReader(snapshot, nowElapsedRealtimeMs, maxEvidenceAgeMs)
    val checks = mutableListOf<AppSegmentCheck>()
    fun check(
        condition: AppSegmentCondition,
        ids: List<TelemetryMetricId>,
        changedReason: SegmentReason = SegmentReason.VALUE_CHANGED,
        predicate: (List<TelemetryReading>) -> Boolean?,
    ) {
        val read = reader.read(ids)
        val passed = if (read.issues.isEmpty()) predicate(ids.map { read.evidence.getValue(it).reading!! }) else null
        checks += AppSegmentCheck(
            condition = condition,
            status = when (passed) {
                true -> AppSegmentStatus.UNCHANGED
                false -> AppSegmentStatus.MODIFIED
                null -> AppSegmentStatus.UNKNOWN
            },
            reason = when (passed) {
                true -> null
                false -> changedReason
                null -> if (read.issues.isEmpty()) SegmentReason.UNSUPPORTED_VALUE else null
            },
            issues = read.issues,
            evidence = read.evidence,
        )
    }
    listOf(
        AppSegmentCondition.SPEED to Metrics.PROCESSING_SPEED,
        AppSegmentCondition.PITCH to Metrics.PROCESSING_PITCH,
        AppSegmentCondition.PLAYER_VOLUME to Metrics.PROCESSING_PLAYER_VOLUME,
    ).forEach { (condition, id) ->
        // Catalog speed and pitch are percentages, not the player's 1.0 multipliers.
        check(condition, listOf(id)) { (it.single() as TelemetryReading.Decimal).value == 100.0 }
    }
    listOf(
        AppSegmentCondition.SKIP_SILENCE to Metrics.PROCESSING_SKIP_SILENCE,
        AppSegmentCondition.REPLAY_GAIN to Metrics.PROCESSING_REPLAY_GAIN_ACTIVE,
        AppSegmentCondition.EQUALIZER to Metrics.PROCESSING_EQUALIZER_ACTIVE,
        AppSegmentCondition.LOUDNESS to Metrics.PROCESSING_LOUDNESS_ACTIVE,
        AppSegmentCondition.CROSSFADE to Metrics.PROCESSING_CROSSFADE_ACTIVE,
        AppSegmentCondition.APP_DSP to Metrics.PROCESSING_APP_DSP_ACTIVE,
    ).forEach { (condition, id) ->
        check(condition, listOf(id)) { !(it.single() as TelemetryReading.Flag).value }
    }
    // processing.sample_rate_conversion is currently estimated from decoder INPUT, not output.
    check(AppSegmentCondition.SAMPLE_RATE, listOf(Metrics.DECODER_OUTPUT_SAMPLE_RATE, Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE)) {
        (it[0] as TelemetryReading.Integer).value == (it[1] as TelemetryReading.Integer).value
    }
    check(AppSegmentCondition.CHANNELS, listOf(Metrics.DECODER_OUTPUT_CHANNEL_CONFIG, Metrics.PLAYBACK_AUDIO_TRACK_CHANNEL_MASK)) {
        val input = channelMask((it[0] as TelemetryReading.Text).value)
        val output = channelMask((it[1] as TelemetryReading.Text).value)
        // Require the same channel layout too; arbitrary matching strings are not PCM evidence.
        if (input == null || output == null) null else input == output
    }
    check(
        AppSegmentCondition.PCM_PRECISION,
        listOf(Metrics.DECODER_OUTPUT_ENCODING, Metrics.PLAYBACK_AUDIO_TRACK_ENCODING),
        changedReason = SegmentReason.PRECISION_LOSS,
    ) { preservesPcmPrecision((it[0] as TelemetryReading.Text).value, (it[1] as TelemetryReading.Text).value) }
    var compression = SourceCompression.UNKNOWN
    check(AppSegmentCondition.SOURCE_COMPRESSION, listOf(Metrics.SOURCE_CODEC_MIME)) {
        compression = sourceCompression((it.single() as TelemetryReading.Text).value)
        if (compression == SourceCompression.UNKNOWN) null else true
    }
    checks += AppSegmentCheck(
        AppSegmentCondition.FOCUS_GAIN, AppSegmentStatus.UNKNOWN, SegmentReason.FOCUS_GAIN_NOT_OBSERVABLE,
    )
    checks += AppSegmentCheck(
        AppSegmentCondition.TRACK_TRANSITION, AppSegmentStatus.UNKNOWN, SegmentReason.TRACK_TRANSITION_NOT_OBSERVABLE,
    )
    val route = reader.read(listOf(Metrics.ROUTE_SELECTED_SYSTEM_TYPE))
    val routeEvidence = route.evidence[Metrics.ROUTE_SELECTED_SYSTEM_TYPE]
    val routeType = (routeEvidence?.reading as? TelemetryReading.Text)?.value
    return AppSegmentAssessment(
        status = when {
            checks.any { it.status == AppSegmentStatus.MODIFIED } -> AppSegmentStatus.MODIFIED
            checks.any { it.status == AppSegmentStatus.UNKNOWN } -> AppSegmentStatus.UNKNOWN
            else -> AppSegmentStatus.UNCHANGED
        },
        sourceCompression = compression,
        checks = checks,
        bluetooth = if (route.issues.isEmpty() && routeType != null && isBluetoothRouteType(routeType)) {
            BluetoothSegmentAssessment(requireNotNull(routeEvidence))
        } else null,
        routeEvidence = route.evidence,
        routeIssues = route.issues,
    )
}

private data class SegmentEvidenceRead(
    val evidence: Map<TelemetryMetricId, TelemetryEvidence>,
    val issues: List<SegmentEvidenceIssue>,
)

private class SegmentEvidenceReader(
    private val snapshot: TelemetrySnapshot,
    private val now: Long,
    private val maxAge: Long,
) {
    fun read(ids: List<TelemetryMetricId>): SegmentEvidenceRead {
        val evidence = linkedMapOf<TelemetryMetricId, TelemetryEvidence>()
        val issues = linkedSetOf<SegmentEvidenceIssue>()
        val visiting = mutableSetOf<TelemetryMetricId>()
        fun visit(id: TelemetryMetricId) {
            if (id in visiting) {
                issues += SegmentEvidenceIssue(id, SegmentReason.DEPENDENCY_CYCLE)
                return
            }
            if (id in evidence) return
            val value = snapshot.metric(id)?.evidence
            if (value == null) {
                issues += SegmentEvidenceIssue(id, SegmentReason.MISSING)
                return
            }
            evidence[id] = value
            when (value.confidence) {
                TelemetryConfidence.UNAVAILABLE -> issues += SegmentEvidenceIssue(id, SegmentReason.UNAVAILABLE)
                TelemetryConfidence.ESTIMATED -> issues += SegmentEvidenceIssue(id, SegmentReason.INSUFFICIENT_CONFIDENCE)
                else -> Unit
            }
            if (now < snapshot.capturedAtElapsedRealtimeMs) {
                issues += SegmentEvidenceIssue(id, SegmentReason.INVALID_TIME)
            } else if (now - value.observedAtElapsedRealtimeMs > maxAge) {
                issues += SegmentEvidenceIssue(id, SegmentReason.EXPIRED)
            }
            val inputs = when (value) {
                is TelemetryEvidence.Derived -> value.inputMetricIds
                is TelemetryEvidence.Estimated -> value.inputMetricIds
                else -> emptySet()
            }
            visiting += id
            inputs.forEach(::visit)
            visiting -= id
        }
        ids.forEach(::visit)
        return SegmentEvidenceRead(evidence, issues.toList())
    }
}

private fun channelMask(value: String): UInt? = value.takeIf { it.startsWith("0x") }
    ?.removePrefix("0x")?.toUIntOrNull(16)?.takeIf { it > 0u && it and 3u == 0u }

/** Float32 has 24 significant binary digits, not 32 integer bits of precision. */
internal fun preservesPcmPrecision(input: String, output: String): Boolean? {
    val integerBits = mapOf("pcm-8" to 8, "pcm-16" to 16, "pcm-24" to 24, "pcm-32" to 32)
    if (input !in integerBits && input != "pcm-float") return null
    if (output !in integerBits && output != "pcm-float") return null
    if (input == "pcm-float") return output == "pcm-float"
    val inputBits = integerBits.getValue(input)
    return if (output == "pcm-float") inputBits <= 24 else integerBits.getValue(output) >= inputBits
}

private fun sourceCompression(mime: String): SourceCompression = when (mime) {
    "audio/flac", "audio/alac", "audio/raw", "audio/true-hd" -> SourceCompression.LOSSLESS
    "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2", "audio/mp4a-latm", "audio/aac",
    "audio/opus", "audio/vorbis", "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/ac4" -> SourceCompression.LOSSY
    // WAV/AIFF are containers: their name alone cannot attest the contained codec.
    else -> SourceCompression.UNKNOWN
}
