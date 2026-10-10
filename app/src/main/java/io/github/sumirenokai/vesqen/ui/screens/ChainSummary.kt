package io.github.sumirenokai.vesqen.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.chain.AppSegmentAssessment
import io.github.sumirenokai.vesqen.chain.AppSegmentCheck
import io.github.sumirenokai.vesqen.chain.AppSegmentCondition
import io.github.sumirenokai.vesqen.chain.AppSegmentMetricIds
import io.github.sumirenokai.vesqen.chain.AppSegmentStatus
import io.github.sumirenokai.vesqen.chain.RouteKind
import io.github.sumirenokai.vesqen.chain.SegmentReason
import io.github.sumirenokai.vesqen.chain.SourceCompression
import io.github.sumirenokai.vesqen.chain.assessAppSegment
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryPowerMode
import io.github.sumirenokai.vesqen.telemetry.TelemetryRefreshInterval
import io.github.sumirenokai.vesqen.ui.chain.ChainObservationState
import io.github.sumirenokai.vesqen.ui.chain.ChainUnitDisplayMode
import io.github.sumirenokai.vesqen.ui.chain.describesLastPlayback
import io.github.sumirenokai.vesqen.ui.chain.effectiveTelemetryRefreshInterval
import io.github.sumirenokai.vesqen.ui.chain.isTelemetrySnapshotStale
import io.github.sumirenokai.vesqen.ui.chain.telemetryConfidenceLabel
import io.github.sumirenokai.vesqen.ui.chain.telemetryEvidenceMethod
import io.github.sumirenokai.vesqen.ui.chain.telemetryEvidenceSource
import io.github.sumirenokai.vesqen.ui.chain.telemetryEvidenceWindow
import io.github.sumirenokai.vesqen.ui.chain.telemetryMetricLabel
import io.github.sumirenokai.vesqen.ui.chain.telemetrySnapshotMaxAgeMs
import io.github.sumirenokai.vesqen.ui.components.OutputStatusChip
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import io.github.sumirenokai.vesqen.ui.theme.VesqenDataStyle
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.serif

/** The five stations of B's "current playback path" (spec §5), in signal order. */
internal enum class ChainStation(@StringRes val label: Int, val tag: String) {
    SOURCE(R.string.chain_station_source, "source"),
    DECODER(R.string.chain_station_decoder, "decoder"),
    PROCESSING(R.string.chain_station_processing, "processing"),
    AUDIO_TRACK(R.string.chain_station_audio_track, "audio-track"),
    ROUTE(R.string.chain_station_route, "route"),
}

/** Facts each station shows; processing summarises its flags instead (see [ChainProcessingFact]). */
internal val ChainStationFacts: Map<ChainStation, List<TelemetryMetricId>> = mapOf(
    ChainStation.SOURCE to listOf(Metrics.SOURCE_CODEC_LABEL, Metrics.SOURCE_SAMPLE_RATE, Metrics.SOURCE_BIT_DEPTH),
    ChainStation.DECODER to listOf(Metrics.DECODER_NAME, Metrics.DECODER_PATH),
    ChainStation.PROCESSING to emptyList(),
    ChainStation.AUDIO_TRACK to listOf(Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE, Metrics.PLAYBACK_AUDIO_TRACK_ENCODING),
    ChainStation.ROUTE to listOf(Metrics.ROUTE_SELECTED_SYSTEM_NAME),
)

/** B §5 pinned metrics: buffer, underruns, process CPU and the whole-device power estimate. */
internal val ChainPinnedMetricIds = listOf(
    Metrics.PLAYBACK_ESTIMATED_TOTAL_BUFFERED_DURATION,
    Metrics.PLAYBACK_UNDERRUN_COUNT,
    Metrics.PROCESS_CPU_PERCENT,
    Metrics.POWER_DEVICE_ESTIMATE,
)

/** Everything the summary reads. Chain observes exactly these, and only while it is visible. */
internal val ChainSummaryMetricIds: Set<TelemetryMetricId> =
    Metrics.defaultIds + ChainStationFacts.values.flatten() + ChainPinnedMetricIds + AppSegmentMetricIds

/** The processing conditions of the segment verdict and the flag each one reads, in station order. */
private val ProcessingFlags = listOf(
    AppSegmentCondition.SPEED to Metrics.PROCESSING_SPEED,
    AppSegmentCondition.PITCH to Metrics.PROCESSING_PITCH,
    AppSegmentCondition.PLAYER_VOLUME to Metrics.PROCESSING_PLAYER_VOLUME,
    AppSegmentCondition.SKIP_SILENCE to Metrics.PROCESSING_SKIP_SILENCE,
    AppSegmentCondition.REPLAY_GAIN to Metrics.PROCESSING_REPLAY_GAIN_ACTIVE,
    AppSegmentCondition.EQUALIZER to Metrics.PROCESSING_EQUALIZER_ACTIVE,
    AppSegmentCondition.LOUDNESS to Metrics.PROCESSING_LOUDNESS_ACTIVE,
    AppSegmentCondition.CROSSFADE to Metrics.PROCESSING_CROSSFADE_ACTIVE,
    AppSegmentCondition.APP_DSP to Metrics.PROCESSING_APP_DSP_ACTIVE,
)

/** B · Paper & Sound Chain summary: claim, two-segment path with evidence, pinned metrics. */
@Composable
internal fun ChainSummaryScreen(
    playback: PlaybackSnapshot,
    observationState: ChainObservationState,
    nowElapsedRealtimeMs: Long,
    refreshInterval: TelemetryRefreshInterval,
    powerMode: TelemetryPowerMode,
    unitDisplayMode: ChainUnitDisplayMode,
    onOpenAdvanced: () -> Unit,
    onUseSystemOutput: (() -> Unit)?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val telemetry = observationState.lastSnapshot()
    val lastPlayback = describesLastPlayback(telemetry, playback)
    val metrics = remember(telemetry) { telemetry?.metrics.orEmpty().associateBy(TelemetryMetric::id) }
    val maxAgeMs = telemetrySnapshotMaxAgeMs(refreshInterval, powerMode)
    // PRD F6.5.1: a verdict about Vesqen's own segment, re-evaluated as time advances. It is never
    // an output declaration, and an old snapshot yields EXPIRED rather than a stale verdict.
    val assessment = remember(telemetry, nowElapsedRealtimeMs, maxAgeMs, lastPlayback) {
        telemetry?.takeUnless { lastPlayback }?.let { assessAppSegment(it, nowElapsedRealtimeMs.coerceAtLeast(0), maxAgeMs) }
    }
    // B §5: the dot flows only while fresh samples describe current playback, not while output switches.
    val live = observationState is ChainObservationState.Content && !lastPlayback && telemetry != null &&
        !isTelemetrySnapshotStale(telemetry, nowElapsedRealtimeMs, refreshInterval, powerMode) &&
        playback.usbOutputStatus.phase != UsbOutputPhase.APPLYING
    var expanded by rememberSaveable { mutableStateOf<ChainStation?>(null) }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.chain.summary-list"),
            contentPadding = PaddingValues(start = VesqenSpacing.lg, end = VesqenSpacing.lg, bottom = VesqenSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "observation") {
                ChainObservationNotice(
                    state = observationState,
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                    refreshInterval = refreshInterval,
                    powerMode = powerMode,
                    requestedMetricIds = ChainSummaryMetricIds,
                    lastPlayback = lastPlayback,
                    onRetry = onRetry,
                    compact = true,
                )
            }
            item(key = "current-source") { ChainNowPlaying(playback) }
            item(key = "output-status") { ChainDeclarationCard(playback, onUseSystemOutput) }
            item(key = "path") {
                ChainPath(
                    playback = playback,
                    metrics = metrics,
                    assessment = assessment,
                    waiting = telemetry == null,
                    lastPlayback = lastPlayback,
                    live = live,
                    unitDisplayMode = unitDisplayMode,
                    expanded = expanded,
                    onToggle = { station -> expanded = if (expanded == station) null else station },
                )
            }
            item(key = "boundary") {
                Text(
                    text = stringResource(R.string.chain_core_boundary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "pinned") {
                ChainPinnedMetrics(
                    metrics = metrics,
                    unitDisplayMode = unitDisplayMode,
                    idle = lastPlayback,
                    // Lower power stretches the cadence; the label says what the readings actually do.
                    refreshInterval = effectiveTelemetryRefreshInterval(refreshInterval, powerMode),
                )
            }
            item(key = "advanced") { ChainAdvancedEntry(onOpenAdvanced) }
        }
    }
}

/** Current track, shared by the summary and the advanced dashboard. */
@Composable
internal fun ChainNowPlaying(playback: PlaybackSnapshot) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.chain_current_playing),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = playback.title.ifBlank { stringResource(R.string.unknown_title) },
            style = MaterialTheme.typography.headlineSmall.serif(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("vesqen.chain.current-source"),
        )
        if (playback.artist.isNotBlank()) {
            Text(
                text = playback.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** B §5 output declaration card; strict failures offer the explicit fallback. */
@Composable
internal fun ChainDeclarationCard(playback: PlaybackSnapshot, onUseSystemOutput: (() -> Unit)?) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("vesqen.chain.summary"),
        shape = RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalVesqenColors.current.hairline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutputStatusChip(declaration = playback.declaration)
            Text(text = outputClaimTitle(playback), style = MaterialTheme.typography.headlineMedium)
            Text(
                text = outputClaimBody(playback),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (playback.usbOutputStatus.phase == UsbOutputPhase.FAILED && onUseSystemOutput != null) {
                OutlinedButton(
                    onClick = onUseSystemOutput,
                    enabled = playback.canSetUsbOutputMode,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("vesqen.chain.use-system"),
                ) { Text(stringResource(R.string.player_use_system_output)) }
            }
        }
    }
}

@Composable
private fun ChainPath(
    playback: PlaybackSnapshot,
    metrics: Map<TelemetryMetricId, TelemetryMetric>,
    assessment: AppSegmentAssessment?,
    waiting: Boolean,
    lastPlayback: Boolean,
    live: Boolean,
    unitDisplayMode: ChainUnitDisplayMode,
    expanded: ChainStation?,
    onToggle: (ChainStation) -> Unit,
) {
    val hairline = LocalVesqenColors.current.hairline
    val ink = MaterialTheme.colorScheme.onSurface
    val reduceMotion = rememberVesqenMotionPolicy().reduceMotion
    // The flowing dot (B §5, §7) shows live observation; it stops when playback or sampling does.
    val flow = if (live && !reduceMotion) {
        rememberInfiniteTransition(label = "vesqen.chain.path-flow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2_800, easing = LinearEasing), RepeatMode.Restart),
            label = "vesqen.chain.path-flow.progress",
        ).value
    } else null
    var lastRowHeight by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().testTag("vesqen.chain.path")) {
        Text(
            text = stringResource(if (lastPlayback) R.string.chain_last_path else R.string.chain_current_path),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(bottom = VesqenSpacing.sm)
                .semantics { heading() },
        )
        Column(
            Modifier.drawWithContent {
                drawContent()
                if (flow != null) {
                    val top = NodeCenter.toPx()
                    val bottom = (size.height - lastRowHeight + NodeCenter.toPx()).coerceAtLeast(top)
                    val alpha = when {
                        flow < .12f -> flow / .12f
                        flow > .88f -> (1f - flow) / .12f
                        else -> 1f
                    }
                    drawCircle(ink.copy(alpha = alpha), radius = 2.5.dp.toPx(), center = Offset(RailCenter.toPx(), top + (bottom - top) * flow))
                }
            },
        ) {
            ChainSegmentLabel(
                title = stringResource(R.string.chain_segment_app),
                body = appSegmentVerdict(assessment, waiting || lastPlayback),
                tag = "vesqen.chain.segment.app",
                hairline = hairline,
            )
            ChainStation.entries.forEach { station ->
                if (station == ChainStation.ROUTE) {
                    ChainSegmentLabel(
                        title = stringResource(R.string.chain_segment_system),
                        body = systemSegmentText(assessment),
                        tag = "vesqen.chain.segment.system",
                        hairline = hairline,
                    )
                }
                ChainStationRow(
                    station = station,
                    metrics = metrics,
                    assessment = assessment,
                    unitDisplayMode = unitDisplayMode,
                    expanded = expanded == station,
                    onToggle = { onToggle(station) },
                    hairline = hairline,
                    last = station == ChainStation.ROUTE,
                    modifier = if (station == ChainStation.ROUTE) Modifier.onSizeChanged { lastRowHeight = it.height } else Modifier,
                )
            }
        }
    }
}

private val RailWidth = 24.dp
private val RailCenter = 4.5.dp
private val NodeCenter = 10.dp

/** A segment heading on the rail: no node, just the line passing beside it. */
@Composable
private fun ChainSegmentLabel(title: String, body: String?, tag: String, hairline: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(hairline, Offset(RailCenter.toPx(), 0f), Offset(RailCenter.toPx(), size.height), 1.dp.toPx())
            }
            .semantics(mergeDescendants = true) {}
            .testTag(tag),
    ) {
        Spacer(Modifier.width(RailWidth))
        Column(Modifier.weight(1f).padding(bottom = VesqenSpacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            body?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ChainStationRow(
    station: ChainStation,
    metrics: Map<TelemetryMetricId, TelemetryMetric>,
    assessment: AppSegmentAssessment?,
    unitDisplayMode: ChainUnitDisplayMode,
    expanded: Boolean,
    onToggle: () -> Unit,
    hairline: Color,
    last: Boolean,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val index = ChainStation.entries.indexOf(station) + 1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val x = RailCenter.toPx()
                // The line runs through every station and ends at the route's node.
                drawLine(hairline, Offset(x, 0f), Offset(x, if (last) NodeCenter.toPx() else size.height), 1.dp.toPx())
            }
            .testTag("vesqen.chain.path.${station.tag}"),
    ) {
        Box(Modifier.width(RailWidth).padding(top = NodeCenter - 4.5.dp)) {
            Box(
                Modifier
                    .size(9.dp)
                    .then(if (expanded) Modifier.background(ink, CircleShape) else Modifier.background(MaterialTheme.colorScheme.background, CircleShape))
                    .border(1.5.dp, ink.copy(alpha = if (expanded) 1f else .55f), CircleShape),
            )
        }
        Column(Modifier.weight(1f).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "${index.toString().padStart(2, '0')} · ${stringResource(station.label)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            if (station == ChainStation.PROCESSING) {
                ChainProcessingFact(assessment, metrics, unitDisplayMode, expanded, onToggle)
            } else {
                ChainStationFacts.getValue(station).forEach { id ->
                    ChainFact(id, metrics, unitDisplayMode, expanded, onToggle, prominent = id == ChainStationFacts.getValue(station).first())
                }
            }
        }
    }
}

/** One measured fact: label, value and confidence; tapping shows its source and method. */
@Composable
private fun ChainFact(
    id: TelemetryMetricId,
    metrics: Map<TelemetryMetricId, TelemetryMetric>,
    unitDisplayMode: ChainUnitDisplayMode,
    expanded: Boolean,
    onToggle: () -> Unit,
    prominent: Boolean,
) {
    val context = LocalContext.current
    val evidence = metrics[id]?.evidence
    val value = rememberedTelemetryReading(evidence?.reading, unitDisplayMode)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.chain_evidence_details), role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {}
            .testTag("vesqen.chain.core.${id.value}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(telemetryMetricLabel(context, id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = (if (prominent) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
                .copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.fillMaxWidth().testTag("vesqen.chain.core-value.${id.value}"),
        )
        Text(
            text = evidence?.let { telemetryConfidenceLabel(context, it.confidence) }
                ?: stringResource(R.string.chain_sampling_starting_short),
            style = evidenceStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().testTag("vesqen.chain.core-evidence.${id.value}"),
        )
        if (evidence != null && (expanded || evidence is TelemetryEvidence.Unavailable)) {
            telemetryEvidenceMethod(context, evidence)?.let { method ->
                Text(method, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded && evidence != null) {
            Text(
                text = listOfNotNull(telemetryEvidenceSource(context, evidence), telemetryEvidenceWindow(context, evidence)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** App processing: the processing part of the segment verdict, never a separate claim. */
@Composable
private fun ChainProcessingFact(
    assessment: AppSegmentAssessment?,
    metrics: Map<TelemetryMetricId, TelemetryMetric>,
    unitDisplayMode: ChainUnitDisplayMode,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val conditions = ProcessingFlags.map { it.first }
    val checks = assessment?.checks.orEmpty().filter { it.condition in conditions }
    val changed = checks.filter { it.status == AppSegmentStatus.MODIFIED }.map { it.condition }
    val summary = when {
        assessment == null -> stringResource(R.string.chain_segment_waiting)
        changed.isNotEmpty() -> stringResource(R.string.chain_processing_changed, conditionList(changed))
        checks.all { it.status == AppSegmentStatus.UNCHANGED } -> stringResource(R.string.chain_processing_none)
        else -> stringResource(R.string.chain_segment_unknown, segmentReasonText(checks))
    }
    // The flags come from one player state, so one confidence and age stand for all of them.
    val evidence = ProcessingFlags.firstNotNullOfOrNull { metrics[it.second]?.evidence }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.chain_evidence_details), role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {}
            .testTag("vesqen.chain.processing"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(summary, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = evidence?.let { telemetryConfidenceLabel(context, it.confidence) }
                ?: stringResource(R.string.chain_sampling_starting_short),
            style = evidenceStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
            ProcessingFlags.forEach { (_, id) ->
                Text(
                    text = telemetryMetricLabel(context, id) + " · " + rememberedTelemetryReading(metrics[id]?.evidence?.reading, unitDisplayMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("vesqen.chain.processing.${id.value}"),
                )
            }
            evidence?.let {
                Text(telemetryEvidenceSource(context, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun appSegmentVerdict(assessment: AppSegmentAssessment?, waiting: Boolean): String = when {
    waiting || assessment == null -> stringResource(R.string.chain_segment_waiting)
    assessment.status == AppSegmentStatus.UNCHANGED -> stringResource(
        when (assessment.sourceCompression) {
            SourceCompression.LOSSLESS -> R.string.chain_segment_unchanged_lossless
            SourceCompression.LOSSY -> R.string.chain_segment_unchanged_lossy
            SourceCompression.UNKNOWN -> R.string.chain_segment_unchanged
        },
    )
    assessment.status == AppSegmentStatus.MODIFIED -> stringResource(
        R.string.chain_segment_modified,
        conditionList(assessment.checks.filter { it.status == AppSegmentStatus.MODIFIED }.map { it.condition }),
    )
    else -> stringResource(R.string.chain_segment_unknown, segmentReasonText(assessment.checks))
}

/**
 * PRD F6.5.1: only Bluetooth adds a statement about the system's part. Elsewhere the label just
 * marks where Vesqen's part ends; the declaration card above says what the system does.
 */
@Composable
private fun systemSegmentText(assessment: AppSegmentAssessment?): String? =
    if (assessment?.route == RouteKind.BLUETOOTH) stringResource(R.string.chain_segment_bluetooth) else null

@Composable
private fun conditionList(conditions: List<AppSegmentCondition>): String =
    conditions.map { stringResource(conditionLabel(it)) }.joinToString(stringResource(R.string.chain_segment_list_separator))

/** The most useful reason among unresolved checks: a settling transition explains the others. */
@Composable
private fun segmentReasonText(checks: List<AppSegmentCheck>): String {
    val reasons = checks.filter { it.status == AppSegmentStatus.UNKNOWN }
        .flatMap { listOfNotNull(it.reason) + it.issues.map { issue -> issue.reason } }
        .toSet()
    return stringResource(
        when {
            SegmentReason.TRANSITION_IN_PROGRESS in reasons -> R.string.chain_segment_reason_transition
            SegmentReason.EXPIRED in reasons || SegmentReason.INVALID_TIME in reasons -> R.string.chain_segment_reason_stale
            SegmentReason.DECODER_MAY_CHANGE_FORMAT in reasons -> R.string.chain_segment_reason_decoder
            SegmentReason.UNSUPPORTED_VALUE in reasons -> R.string.chain_segment_reason_unsupported
            else -> R.string.chain_segment_reason_missing
        },
    )
}

@StringRes
internal fun conditionLabel(condition: AppSegmentCondition): Int = when (condition) {
    AppSegmentCondition.SPEED -> R.string.chain_condition_speed
    AppSegmentCondition.PITCH -> R.string.chain_condition_pitch
    AppSegmentCondition.PLAYER_VOLUME -> R.string.chain_condition_player_volume
    AppSegmentCondition.SKIP_SILENCE -> R.string.chain_condition_skip_silence
    AppSegmentCondition.REPLAY_GAIN -> R.string.chain_condition_replay_gain
    AppSegmentCondition.EQUALIZER -> R.string.chain_condition_equalizer
    AppSegmentCondition.LOUDNESS -> R.string.chain_condition_loudness
    AppSegmentCondition.CROSSFADE -> R.string.chain_condition_crossfade
    AppSegmentCondition.APP_DSP -> R.string.chain_condition_app_dsp
    AppSegmentCondition.SAMPLE_RATE -> R.string.chain_condition_sample_rate
    AppSegmentCondition.CHANNELS -> R.string.chain_condition_channels
    AppSegmentCondition.PCM_PRECISION -> R.string.chain_condition_pcm_precision
    AppSegmentCondition.TRACK_TRANSITION -> R.string.chain_condition_track_transition
}

/** B §5 pinned metrics, refreshed only while Chain is visible (the observation follows it). */
@Composable
private fun ChainPinnedMetrics(
    metrics: Map<TelemetryMetricId, TelemetryMetric>,
    unitDisplayMode: ChainUnitDisplayMode,
    idle: Boolean,
    refreshInterval: TelemetryRefreshInterval,
) {
    Column(Modifier.fillMaxWidth().testTag("vesqen.chain.pinned"), verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chain_pinned_metrics),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Text(
                text = stringResource(R.string.chain_pinned_refresh, refreshIntervalLabel(refreshInterval)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 300.dp && LocalDensity.current.fontScale < ChainLargeTextFontScale) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm)) {
                ChainPinnedMetricIds.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.sm)) {
                        row.forEach { id ->
                            ChainPinnedCard(id, metrics[id]?.evidence, unitDisplayMode, idle, Modifier.weight(1f))
                        }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChainPinnedCard(
    id: TelemetryMetricId,
    evidence: TelemetryEvidence?,
    unitDisplayMode: ChainUnitDisplayMode,
    idle: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reading = rememberedTelemetryReading(evidence?.reading, unitDisplayMode)
    val onSurface = MaterialTheme.colorScheme.onSurface
    // Autosize changes only the font size, so the line height must follow it (em), and the box
    // is sized for the largest value at this font scale. Smaller values then shrink to fit the
    // width instead of reflowing the card at digit and unit boundaries.
    val valueHeight = with(LocalDensity.current) { PinnedValueSize.toDp() * PinnedValueLineHeight }
    Surface(
        modifier = modifier.testTag("vesqen.chain.core.${id.value}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalVesqenColors.current.hairline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(
                text = if (idle || evidence == null) "—" else reading,
                // A measured value is data, so it is sans like every other number (B spec §3).
                style = MaterialTheme.typography.displayLarge.copy(
                    color = onSurface,
                    fontFamily = VesqenDataStyle.fontFamily,
                    fontWeight = VesqenDataStyle.fontWeight,
                    fontFeatureSettings = "tnum",
                    lineHeight = PinnedValueLineHeight.em,
                ),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = PinnedValueSize, stepSize = 1.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(valueHeight)
                    .testTag("vesqen.chain.core-value.${id.value}"),
            )
            Text(
                text = telemetryMetricLabel(context, id),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    idle -> stringResource(R.string.chain_idle_title)
                    evidence == null -> stringResource(R.string.chain_sampling_starting_short)
                    else -> telemetryConfidenceLabel(context, evidence.confidence)
                },
                style = evidenceStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("vesqen.chain.core-evidence.${id.value}"),
            )
        }
    }
}

/**
 * B §5: confidence and age at 12 sp. The theme's labelSmall is the eyebrow style, whose wide
 * letter spacing breaks up Chinese text, so the evidence line uses plain small body text.
 */
@Composable
private fun evidenceStyle() = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp)

/** B §5: pinned values are 34 px serif digits. */
private val PinnedValueSize = 34.sp
private const val PinnedValueLineHeight = 1.15f

/** B §5: a full-width row between two hairlines. */
@Composable
private fun ChainAdvancedEntry(onOpenAdvanced: () -> Unit) {
    val hairline = LocalVesqenColors.current.hairline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .drawBehind {
                drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                drawLine(hairline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .clickable(role = Role.Button, onClick = onOpenAdvanced)
            .testTag("vesqen.chain.open-advanced")
            .padding(horizontal = VesqenSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.chain_open_advanced), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The breathing ink dot of B's live-observation hint (§5, §7). */
@Composable
internal fun ChainLiveDot(live: Boolean) {
    val reduceMotion = rememberVesqenMotionPolicy().reduceMotion
    val alpha = if (live && !reduceMotion) {
        rememberInfiniteTransition(label = "vesqen.chain.live-dot").animateFloat(
            initialValue = 1f,
            targetValue = .3f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "vesqen.chain.live-dot.alpha",
        ).value
    } else 1f
    Box(
        Modifier
            .size(6.dp)
            .alpha(if (live) alpha else .45f)
            .background(MaterialTheme.colorScheme.onSurface, CircleShape),
    )
}
