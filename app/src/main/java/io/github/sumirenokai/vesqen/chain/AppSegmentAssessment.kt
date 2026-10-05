package io.github.sumirenokai.vesqen.chain

import io.github.sumirenokai.vesqen.telemetry.TelemetryConfidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventKind
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import io.github.sumirenokai.vesqen.telemetry.isBluetoothRouteType

internal enum class AppSegmentStatus { UNCHANGED, MODIFIED, UNKNOWN }
internal enum class SourceCompression { LOSSLESS, LOSSY, UNKNOWN }
internal enum class AppSegmentCondition {
    SPEED, PITCH, PLAYER_VOLUME, SKIP_SILENCE, REPLAY_GAIN, EQUALIZER, LOUDNESS,
    CROSSFADE, APP_DSP, SAMPLE_RATE, CHANNELS, PCM_PRECISION, TRACK_TRANSITION,
}
internal enum class SegmentReason {
    VALUE_CHANGED, PRECISION_LOSS, MISSING, UNAVAILABLE, INSUFFICIENT_CONFIDENCE,
    EXPIRED, INVALID_TIME, UNSUPPORTED_VALUE, DEPENDENCY_CYCLE,
    /** The decoder may legitimately change rate or layout (AAC SBR/PS), so a mismatch proves nothing. */
    DECODER_MAY_CHANGE_FORMAT,
    /** A track change, seek or output re-initialisation happened within [TransitionSettleMs]. */
    TRANSITION_IN_PROGRESS,
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

internal enum class RouteKind { BLUETOOTH, OTHER, UNKNOWN }
internal enum class BluetoothSegmentStatus { LOSSY_OR_UNKNOWN_DEPENDING_ON_CODEC }
internal data class BluetoothSegmentAssessment(
    val selectedRouteEvidence: TelemetryEvidence,
    val status: BluetoothSegmentStatus = BluetoothSegmentStatus.LOSSY_OR_UNKNOWN_DEPENDING_ON_CODEC,
    /** Reserved for #73 route-associated codec evidence; never inferred from source or capabilities. */
    val reportedCodecEvidence: TelemetryEvidence? = null,
)

internal data class AppSegmentAssessment(
    val status: AppSegmentStatus,
    /** Chooses the wording only ("lossless" or "not altered further"); it never gates [status]. */
    val sourceCompression: SourceCompression,
    val checks: List<AppSegmentCheck>,
    val route: RouteKind,
    val bluetooth: BluetoothSegmentAssessment?,
    val routeEvidence: Map<TelemetryMetricId, TelemetryEvidence>,
    val routeIssues: List<SegmentEvidenceIssue>,
)

/** Every metric the assessment reads; Chain must request them explicitly while it shows the result. */
internal val AppSegmentMetricIds: List<TelemetryMetricId> = listOf(
    Metrics.PROCESSING_SPEED, Metrics.PROCESSING_PITCH, Metrics.PROCESSING_PLAYER_VOLUME,
    Metrics.PROCESSING_SKIP_SILENCE, Metrics.PROCESSING_REPLAY_GAIN_ACTIVE, Metrics.PROCESSING_EQUALIZER_ACTIVE,
    Metrics.PROCESSING_LOUDNESS_ACTIVE, Metrics.PROCESSING_CROSSFADE_ACTIVE, Metrics.PROCESSING_APP_DSP_ACTIVE,
    Metrics.DECODER_INPUT_MIME, Metrics.DECODER_INPUT_SAMPLE_RATE, Metrics.DECODER_INPUT_CHANNEL_COUNT,
    Metrics.DECODER_INPUT_PCM_ENCODING, Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE,
    Metrics.PLAYBACK_AUDIO_TRACK_CHANNEL_MASK, Metrics.PLAYBACK_AUDIO_TRACK_ENCODING,
    Metrics.ROUTE_SELECTED_SYSTEM_TYPE,
)

/** Events after which the output may still carry the previous item, a seek or a re-opened track. */
internal const val TransitionSettleMs = 2_000L
private val transitionEvents = setOf(
    TelemetryEventKind.MEDIA_ITEM_CHANGED, TelemetryEventKind.FORMAT_CHANGED,
    TelemetryEventKind.DECODER_INITIALIZED, TelemetryEventKind.OUTPUT_INITIALIZED,
    TelemetryEventKind.SEEK_COMPLETED,
)

/**
 * PRD F6.5.1: did Vesqen alter the PCM between reading the file and handing it to Android? This
 * is not an output declaration and never upgrades SYSTEM MIXED. Call again as time advances.
 *
 * - Freshness is the snapshot's: latched facts such as the AudioTrack format are stamped when
 *   they last changed, which says nothing about whether the snapshot itself is current.
 * - Decoder output is not exposed, so the measured decoder input (Media3's extracted format)
 *   stands in for it. Vesqen's decoders neither resample nor remix, so a rate or layout change
 *   before the AudioTrack is Vesqen's; AAC is the exception, as SBR/PS change both in the codec.
 * - Audio-focus ducking is not a condition: on Android 8+ Media3 does not pause music for
 *   ducking, so the system ducks it in its mixer, after this segment (owner decision 2026-10-04).
 * - Vesqen has no fade feature; crossfade is covered by its processing flag.
 */
internal fun assessAppSegment(
    snapshot: TelemetrySnapshot,
    nowElapsedRealtimeMs: Long,
    maxSnapshotAgeMs: Long,
): AppSegmentAssessment {
    require(nowElapsedRealtimeMs >= 0)
    require(maxSnapshotAgeMs >= 0)
    val snapshotIssue = when {
        nowElapsedRealtimeMs < snapshot.capturedAtElapsedRealtimeMs -> SegmentReason.INVALID_TIME
        nowElapsedRealtimeMs - snapshot.capturedAtElapsedRealtimeMs > maxSnapshotAgeMs -> SegmentReason.EXPIRED
        else -> null
    }
    val reader = SegmentEvidenceReader(snapshot, snapshotIssue)
    val checks = mutableListOf<AppSegmentCheck>()
    fun check(
        condition: AppSegmentCondition,
        ids: List<TelemetryMetricId>,
        changedReason: SegmentReason = SegmentReason.VALUE_CHANGED,
        predicate: (List<TelemetryReading>) -> Verdict,
    ) {
        val read = reader.read(ids)
        val verdict = if (read.issues.isEmpty()) {
            predicate(ids.map { read.evidence.getValue(it).reading!! })
        } else {
            Verdict.Unresolved(null)
        }
        checks += AppSegmentCheck(
            condition = condition,
            status = when (verdict) {
                Verdict.Kept -> AppSegmentStatus.UNCHANGED
                Verdict.Changed -> AppSegmentStatus.MODIFIED
                is Verdict.Unresolved -> AppSegmentStatus.UNKNOWN
            },
            reason = when (verdict) {
                Verdict.Kept -> null
                Verdict.Changed -> changedReason
                is Verdict.Unresolved -> verdict.reason ?: if (read.issues.isEmpty()) SegmentReason.UNSUPPORTED_VALUE else null
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
        // Catalog speed, pitch and volume are percentages, not the player's 1.0 multipliers.
        check(condition, listOf(id)) { Verdict.of((it.single() as TelemetryReading.Decimal).value == 100.0) }
    }
    listOf(
        AppSegmentCondition.SKIP_SILENCE to Metrics.PROCESSING_SKIP_SILENCE,
        AppSegmentCondition.REPLAY_GAIN to Metrics.PROCESSING_REPLAY_GAIN_ACTIVE,
        AppSegmentCondition.EQUALIZER to Metrics.PROCESSING_EQUALIZER_ACTIVE,
        AppSegmentCondition.LOUDNESS to Metrics.PROCESSING_LOUDNESS_ACTIVE,
        AppSegmentCondition.CROSSFADE to Metrics.PROCESSING_CROSSFADE_ACTIVE,
        AppSegmentCondition.APP_DSP to Metrics.PROCESSING_APP_DSP_ACTIVE,
    ).forEach { (condition, id) ->
        check(condition, listOf(id)) { Verdict.of(!(it.single() as TelemetryReading.Flag).value) }
    }
    val inputMime = reader.measuredText(Metrics.DECODER_INPUT_MIME)
    val codecMayChangeFormat = inputMime == "audio/mp4a-latm" || inputMime == "audio/aac"
    check(AppSegmentCondition.SAMPLE_RATE, listOf(Metrics.DECODER_INPUT_SAMPLE_RATE, Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE)) {
        val same = (it[0] as TelemetryReading.Integer).value == (it[1] as TelemetryReading.Integer).value
        if (!same && codecMayChangeFormat) Verdict.Unresolved(SegmentReason.DECODER_MAY_CHANGE_FORMAT) else Verdict.of(same)
    }
    check(AppSegmentCondition.CHANNELS, listOf(Metrics.DECODER_INPUT_CHANNEL_COUNT, Metrics.PLAYBACK_AUDIO_TRACK_CHANNEL_MASK)) {
        val output = channelCount((it[1] as TelemetryReading.Text).value)
            ?: return@check Verdict.Unresolved(null)
        val same = (it[0] as TelemetryReading.Integer).value == output.toLong()
        if (!same && codecMayChangeFormat) Verdict.Unresolved(SegmentReason.DECODER_MAY_CHANGE_FORMAT) else Verdict.of(same)
    }
    checks += precisionCheck(reader)
    checks += transitionCheck(snapshot, snapshotIssue)

    val route = reader.read(listOf(Metrics.ROUTE_SELECTED_SYSTEM_TYPE))
    val routeEvidence = route.evidence[Metrics.ROUTE_SELECTED_SYSTEM_TYPE]
    val routeType = (routeEvidence?.reading as? TelemetryReading.Text)?.value
    val routeKind = when {
        route.issues.isNotEmpty() || routeType == null -> RouteKind.UNKNOWN
        isBluetoothRouteType(routeType) -> RouteKind.BLUETOOTH
        else -> RouteKind.OTHER
    }
    return AppSegmentAssessment(
        status = when {
            checks.any { it.status == AppSegmentStatus.MODIFIED } -> AppSegmentStatus.MODIFIED
            checks.any { it.status == AppSegmentStatus.UNKNOWN } -> AppSegmentStatus.UNKNOWN
            else -> AppSegmentStatus.UNCHANGED
        },
        sourceCompression = inputMime?.let(::sourceCompression) ?: SourceCompression.UNKNOWN,
        checks = checks,
        route = routeKind,
        bluetooth = if (routeKind == RouteKind.BLUETOOTH) BluetoothSegmentAssessment(requireNotNull(routeEvidence)) else null,
        routeEvidence = route.evidence,
        routeIssues = route.issues,
    )
}

/**
 * Lossless input states its PCM width; compressed input has none (NOT_APPLICABLE) and decodes to
 * the codec's own output, which only a float AudioTrack is certain to hold exactly.
 */
private fun precisionCheck(reader: SegmentEvidenceReader): AppSegmentCheck {
    val ids = listOf(Metrics.DECODER_INPUT_PCM_ENCODING, Metrics.PLAYBACK_AUDIO_TRACK_ENCODING)
    val read = reader.read(ids)
    val inputEvidence = read.evidence[Metrics.DECODER_INPUT_PCM_ENCODING]
    val compressedInput = inputEvidence is TelemetryEvidence.Unavailable &&
        inputEvidence.reason == TelemetryUnavailableReason.NOT_APPLICABLE
    val blocking = read.issues.filterNot {
        compressedInput && it.metricId == Metrics.DECODER_INPUT_PCM_ENCODING && it.reason == SegmentReason.UNAVAILABLE
    }
    val output = (read.evidence[Metrics.PLAYBACK_AUDIO_TRACK_ENCODING]?.reading as? TelemetryReading.Text)?.value
    val verdict = when {
        blocking.isNotEmpty() || output == null -> Verdict.Unresolved(null)
        compressedInput -> if (output == "pcm-float") Verdict.Kept else Verdict.Unresolved(SegmentReason.UNSUPPORTED_VALUE)
        else -> when (preservesPcmPrecision((inputEvidence?.reading as TelemetryReading.Text).value, output)) {
            true -> Verdict.Kept
            false -> Verdict.Changed
            null -> Verdict.Unresolved(SegmentReason.UNSUPPORTED_VALUE)
        }
    }
    return AppSegmentCheck(
        condition = AppSegmentCondition.PCM_PRECISION,
        status = when (verdict) {
            Verdict.Kept -> AppSegmentStatus.UNCHANGED
            Verdict.Changed -> AppSegmentStatus.MODIFIED
            is Verdict.Unresolved -> AppSegmentStatus.UNKNOWN
        },
        reason = when (verdict) {
            Verdict.Kept -> null
            Verdict.Changed -> SegmentReason.PRECISION_LOSS
            is Verdict.Unresolved -> verdict.reason
        },
        issues = blocking,
        evidence = read.evidence,
    )
}

private fun transitionCheck(snapshot: TelemetrySnapshot, snapshotIssue: SegmentReason?): AppSegmentCheck {
    if (snapshotIssue != null) {
        return AppSegmentCheck(AppSegmentCondition.TRACK_TRANSITION, AppSegmentStatus.UNKNOWN, snapshotIssue)
    }
    val since = snapshot.capturedAtElapsedRealtimeMs - TransitionSettleMs
    val settling = snapshot.recentEvents.any {
        it.kind in transitionEvents && it.occurredAtElapsedRealtimeMs > since
    }
    return if (settling) {
        AppSegmentCheck(AppSegmentCondition.TRACK_TRANSITION, AppSegmentStatus.UNKNOWN, SegmentReason.TRANSITION_IN_PROGRESS)
    } else {
        AppSegmentCheck(AppSegmentCondition.TRACK_TRANSITION, AppSegmentStatus.UNCHANGED)
    }
}

private sealed interface Verdict {
    data object Kept : Verdict
    data object Changed : Verdict
    data class Unresolved(val reason: SegmentReason?) : Verdict

    companion object {
        fun of(kept: Boolean): Verdict = if (kept) Kept else Changed
    }
}

private data class SegmentEvidenceRead(
    val evidence: Map<TelemetryMetricId, TelemetryEvidence>,
    val issues: List<SegmentEvidenceIssue>,
)

/** Only MEASURED or DERIVED evidence in a current snapshot can support a verdict. */
private class SegmentEvidenceReader(
    private val snapshot: TelemetrySnapshot,
    private val snapshotIssue: SegmentReason?,
) {
    fun measuredText(id: TelemetryMetricId): String? {
        val read = read(listOf(id))
        return if (read.issues.isEmpty()) (read.evidence[id]?.reading as? TelemetryReading.Text)?.value else null
    }

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
            snapshotIssue?.let { issues += SegmentEvidenceIssue(id, it) }
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

/** AudioTrack channel masks are reported as "0x…"; the count is the number of speaker bits. */
private fun channelCount(mask: String): Int? = mask.takeIf { it.startsWith("0x") }
    ?.removePrefix("0x")?.toUIntOrNull(16)?.takeIf { it > 0u && it and 3u == 0u }?.countOneBits()

/** Float32 has 24 significant binary digits, not 32 integer bits of precision. */
internal fun preservesPcmPrecision(input: String, output: String): Boolean? {
    val integerBits = mapOf("pcm-8" to 8, "pcm-16" to 16, "pcm-24" to 24, "pcm-32" to 32)
    if (input !in integerBits && input != "pcm-float") return null
    if (output !in integerBits && output != "pcm-float") return null
    if (input == "pcm-float") return output == "pcm-float"
    val inputBits = integerBits.getValue(input)
    return if (output == "pcm-float") inputBits <= 24 else integerBits.getValue(output) >= inputBits
}

/**
 * Media3's decoder input MIME. "audio/raw" is PCM from WAV/AIFF; Media3 also decodes IMA ADPCM WAV
 * to raw PCM in its extractor, which this cannot tell apart (rare; recorded as a limitation).
 */
private fun sourceCompression(mime: String): SourceCompression = when (mime) {
    "audio/flac", "audio/alac", "audio/raw", "audio/true-hd" -> SourceCompression.LOSSLESS
    "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2", "audio/mp4a-latm", "audio/aac",
    "audio/opus", "audio/vorbis", "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/ac4" -> SourceCompression.LOSSY
    else -> SourceCompression.UNKNOWN
}
