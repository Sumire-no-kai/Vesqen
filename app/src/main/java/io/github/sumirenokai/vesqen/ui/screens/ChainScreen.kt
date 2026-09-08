package io.github.sumirenokai.vesqen.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecorder
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecordingState
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecordingTermination
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.telemetry.PlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.TelemetryConfidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventKind
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventSeverity
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricSelection
import io.github.sumirenokai.vesqen.telemetry.TelemetryObservation
import io.github.sumirenokai.vesqen.telemetry.TelemetryPowerMode
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetryRefreshInterval
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import io.github.sumirenokai.vesqen.telemetry.UsbInventoryReading
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardPreferences
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardPreferencesRepository
import io.github.sumirenokai.vesqen.ui.chain.ChainDerivedWindow
import io.github.sumirenokai.vesqen.ui.chain.ChainHistoryLength
import io.github.sumirenokai.vesqen.ui.chain.ChainHistoryPoint
import io.github.sumirenokai.vesqen.ui.chain.ChainMetricHistoryBuffer
import io.github.sumirenokai.vesqen.ui.chain.ChainMetricViewMode
import io.github.sumirenokai.vesqen.ui.chain.ChainObservationState
import io.github.sumirenokai.vesqen.ui.chain.ChainUnitDisplayMode
import io.github.sumirenokai.vesqen.ui.chain.DiagnosticExportFeedback
import io.github.sumirenokai.vesqen.ui.chain.chartSummary
import io.github.sumirenokai.vesqen.ui.chain.effectiveTelemetryIntervalMs
import io.github.sumirenokai.vesqen.ui.chain.elapsedChartFraction
import io.github.sumirenokai.vesqen.ui.chain.formatTelemetryReading
import io.github.sumirenokai.vesqen.ui.chain.formatSeconds
import io.github.sumirenokai.vesqen.ui.chain.isTelemetrySnapshotStale
import io.github.sumirenokai.vesqen.ui.chain.telemetryConfidenceLabel
import io.github.sumirenokai.vesqen.ui.chain.telemetryEvidenceAge
import io.github.sumirenokai.vesqen.ui.chain.telemetryEvidenceMethod
import io.github.sumirenokai.vesqen.ui.chain.telemetryEvidenceSource
import io.github.sumirenokai.vesqen.ui.chain.telemetryEvidenceWindow
import io.github.sumirenokai.vesqen.ui.chain.telemetryHistorySpan
import io.github.sumirenokai.vesqen.ui.chain.telemetryMetricLabel
import io.github.sumirenokai.vesqen.ui.chain.telemetrySectionLabel
import io.github.sumirenokai.vesqen.ui.chain.telemetrySourceLabelResource
import io.github.sumirenokai.vesqen.ui.chain.segmentChartHistory
import io.github.sumirenokai.vesqen.ui.components.OutputStatusChip
import io.github.sumirenokai.vesqen.ui.components.VesqenEmptyState
import io.github.sumirenokai.vesqen.ui.theme.VesqenDataStyle
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val SummarySections = listOf(
    TelemetrySection.SOURCE,
    TelemetrySection.DECODER,
    TelemetrySection.PROCESSING,
    TelemetrySection.ROUTE,
    TelemetrySection.USB,
    TelemetrySection.PLAYBACK,
)

private fun ChainDashboardPreferences.observedMetricIds(): Set<TelemetryMetricId> =
    selectedMetricIds.toSet() + TelemetryMetricCatalog.defaultIds + ChainCoreMetricIds

private val ChainCoreMetricIds = setOf(
    TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
    TelemetryMetricCatalog.SOURCE_BIT_DEPTH,
    TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE,
    TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_ENCODING,
    TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME,
    TelemetryMetricCatalog.PLAYBACK_CURRENT_MEDIA_READ_BITRATE,
    TelemetryMetricCatalog.PLAYBACK_ESTIMATED_TOTAL_BUFFERED_DURATION,
    TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT,
)

@Composable
fun ChainScreen(
    snapshot: PlaybackSnapshot,
    playbackTelemetry: PlaybackTelemetry?,
    preferencesRepository: ChainDashboardPreferencesRepository,
    diagnosticRecorder: DiagnosticRecorder?,
    diagnosticExportFeedback: DiagnosticExportFeedback,
    onRequestDiagnosticExport: () -> Unit,
    onClearDiagnosticExportFeedback: () -> Unit,
    onBack: () -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var preferences by remember(preferencesRepository) {
        mutableStateOf(preferencesRepository.load().normalized())
    }
    val history = remember {
        ChainMetricHistoryBuffer(maximumDurationMs = preferences.historyLength.milliseconds)
    }
    var observationState by remember { mutableStateOf<ChainObservationState>(ChainObservationState.Waiting) }
    var retryEpoch by remember { mutableLongStateOf(0) }
    var nowElapsedRealtimeMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showCustomizer by rememberSaveable { mutableStateOf(false) }
    val diagnosticActionScope = rememberCoroutineScope()
    val diagnosticState = if (diagnosticRecorder != null) {
        val observedState by diagnosticRecorder.state.collectAsStateWithLifecycle()
        observedState
    } else DiagnosticRecordingState.Idle

    val observation = remember(
        showAdvanced,
        preferences.refreshInterval,
        preferences.powerMode,
        preferences.derivedWindow,
        preferences.selectedMetricIds,
    ) {
        TelemetryObservation(
            refreshInterval = preferences.refreshInterval,
            derivedWindowMs = preferences.derivedWindow.milliseconds,
            powerMode = preferences.powerMode,
            selection = if (showAdvanced) {
                TelemetryMetricSelection.Explicit(preferences.observedMetricIds())
            } else {
                TelemetryMetricSelection.Explicit(TelemetryMetricCatalog.defaultIds + ChainCoreMetricIds)
            },
        )
    }

    fun persist(updated: ChainDashboardPreferences) {
        val normalized = updated.normalized()
        preferences = normalized
        preferencesRepository.save(normalized)
    }

    LaunchedEffect(snapshot.trackId) {
        history.clear()
        observationState = ChainObservationState.Waiting
    }

    LaunchedEffect(preferences.historyLength) {
        history.updateRetentionDuration(preferences.historyLength.milliseconds)
    }

    LaunchedEffect(snapshot.hasActiveTrack, playbackTelemetry, observation, lifecycleOwner, retryEpoch) {
        if (!snapshot.hasActiveTrack) {
            return@LaunchedEffect
        }
        val telemetry = playbackTelemetry ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            telemetry.observe(observation)
                .catch {
                    val previous = when (val current = observationState) {
                        is ChainObservationState.Content -> current.snapshot
                        is ChainObservationState.Failed -> current.lastSnapshot
                        ChainObservationState.Waiting -> null
                    }
                    observationState = ChainObservationState.Failed(previous)
                }
                .collect { telemetrySnapshot ->
                    history.add(telemetrySnapshot)
                    observationState = ChainObservationState.Content(telemetrySnapshot)
                    nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
                }
        }
    }
    LaunchedEffect(
        snapshot.hasActiveTrack,
        lifecycleOwner,
        preferences.refreshInterval,
        preferences.powerMode,
    ) {
        if (!snapshot.hasActiveTrack) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val tickMs = effectiveTelemetryIntervalMs(
                preferences.refreshInterval,
                preferences.powerMode,
            ).coerceIn(500L, 2_000L)
            while (isActive) {
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
                delay(tickMs)
            }
        }
    }

    BackHandler(enabled = showCustomizer || showAdvanced) {
        if (showCustomizer) showCustomizer = false else showAdvanced = false
    }

    val startDiagnosticRecording: () -> Unit = {
        if (diagnosticRecorder?.start() == true) onClearDiagnosticExportFeedback()
    }
    val stopDiagnosticRecording: () -> Unit = {
        onClearDiagnosticExportFeedback()
        diagnosticActionScope.launch { diagnosticRecorder?.stop() }
    }
    val clearDiagnosticRecording: () -> Unit = {
        if (diagnosticRecorder?.clear() == true) onClearDiagnosticExportFeedback()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("vesqen.chain"),
    ) {
        ChainHeader(
            showAdvanced = showAdvanced,
            onBack = if (showAdvanced) ({ showAdvanced = false }) else onBack,
            onShowSummary = { showAdvanced = false },
        )
        when {
            !snapshot.hasActiveTrack -> ChainEmptyScreen(
                onBrowseLibrary = onBrowseLibrary,
                diagnosticState = diagnosticState,
                diagnosticAvailable = diagnosticRecorder != null,
                diagnosticExportFeedback = diagnosticExportFeedback,
                onStartDiagnosticRecording = startDiagnosticRecording,
                onStopDiagnosticRecording = stopDiagnosticRecording,
                onClearDiagnosticRecording = clearDiagnosticRecording,
                onRequestDiagnosticExport = onRequestDiagnosticExport,
                modifier = Modifier.weight(1f),
            )
            showAdvanced -> ChainAdvancedLayout(
                playback = snapshot,
                observationState = observationState,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                history = history,
                preferences = preferences,
                onPreferencesChanged = ::persist,
                onCustomize = { showCustomizer = true },
                diagnosticState = diagnosticState,
                diagnosticAvailable = diagnosticRecorder != null,
                diagnosticExportFeedback = diagnosticExportFeedback,
                onStartDiagnosticRecording = startDiagnosticRecording,
                onStopDiagnosticRecording = stopDiagnosticRecording,
                onClearDiagnosticRecording = clearDiagnosticRecording,
                onRequestDiagnosticExport = onRequestDiagnosticExport,
                onRetry = { retryEpoch++ },
                modifier = Modifier.weight(1f),
            )
            else -> ChainSummaryScreen(
                playback = snapshot,
                observationState = observationState,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                refreshInterval = preferences.refreshInterval,
                powerMode = preferences.powerMode,
                unitDisplayMode = preferences.unitDisplayMode,
                requestedMetricIds = TelemetryMetricCatalog.defaultIds + ChainCoreMetricIds,
                onOpenAdvanced = { showAdvanced = true },
                onRetry = { retryEpoch++ },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showCustomizer) {
        ChainCustomizerSheet(
            preferences = preferences,
            onPreferencesChanged = ::persist,
            onReset = { persist(preferencesRepository.reset()) },
            onDismiss = { showCustomizer = false },
        )
    }
}

@Composable
private fun ChainHeader(
    showAdvanced: Boolean,
    onBack: () -> Unit,
    onShowSummary: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp).testTag("vesqen.chain.back"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Text(
            text = stringResource(if (showAdvanced) R.string.chain_advanced_title else R.string.destination_chain),
            style = if (showAdvanced) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .testTag("vesqen.chain.title")
                .semantics { heading() },
        )
        if (showAdvanced) {
            TextButton(
                onClick = onShowSummary,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.chain.show-summary"),
            ) {
                Text(stringResource(R.string.chain_summary_action))
            }
        }
    }
}

@Composable
private fun ChainEmptyScreen(
    onBrowseLibrary: () -> Unit,
    diagnosticState: DiagnosticRecordingState,
    diagnosticAvailable: Boolean,
    diagnosticExportFeedback: DiagnosticExportFeedback,
    onStartDiagnosticRecording: () -> Unit,
    onStopDiagnosticRecording: () -> Unit,
    onClearDiagnosticRecording: () -> Unit,
    onRequestDiagnosticExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (diagnosticState == DiagnosticRecordingState.Idle) {
        VesqenEmptyState(
            title = stringResource(R.string.chain_empty_title),
            body = stringResource(R.string.chain_empty_body),
            actionLabel = stringResource(R.string.browse_library),
            onAction = onBrowseLibrary,
            modifier = modifier.padding(horizontal = VesqenSpacing.lg),
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("vesqen.chain.empty-retained-diagnostic"),
        contentPadding = PaddingValues(
            start = VesqenSpacing.lg,
            top = VesqenSpacing.sm,
            end = VesqenSpacing.lg,
            bottom = VesqenSpacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
    ) {
        item(key = "empty-playback") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = VesqenSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.chain_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.chain_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onBrowseLibrary,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.browse_library))
                }
            }
        }
        item(key = "retained-diagnostic") {
            ChainDiagnosticPanel(
                state = diagnosticState,
                available = diagnosticAvailable,
                exportFeedback = diagnosticExportFeedback,
                onStart = onStartDiagnosticRecording,
                onStop = onStopDiagnosticRecording,
                onClear = onClearDiagnosticRecording,
                onExport = onRequestDiagnosticExport,
            )
        }
    }
}

@Composable
private fun ChainSummaryScreen(
    playback: PlaybackSnapshot,
    observationState: ChainObservationState,
    nowElapsedRealtimeMs: Long,
    refreshInterval: TelemetryRefreshInterval,
    powerMode: TelemetryPowerMode,
    unitDisplayMode: ChainUnitDisplayMode,
    requestedMetricIds: Set<TelemetryMetricId>,
    onOpenAdvanced: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.chain.summary-list"),
            contentPadding = PaddingValues(
                start = VesqenSpacing.lg,
                top = VesqenSpacing.xs,
                end = VesqenSpacing.lg,
                bottom = VesqenSpacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
        ) {
            item(key = "current-source") { ChainCurrentSource(playback) }
            item {
                ChainObservationNotice(
                    state = observationState,
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                    refreshInterval = refreshInterval,
                    powerMode = powerMode,
                    requestedMetricIds = requestedMetricIds,
                    onRetry = onRetry,
                    compact = true,
                )
            }
            item {
                ChainCorePanel(observationState.lastSnapshot(), nowElapsedRealtimeMs, unitDisplayMode)
            }
            item {
                Button(
                    onClick = onOpenAdvanced,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("vesqen.chain.open-advanced"),
                ) {
                    Icon(Icons.Filled.Speed, contentDescription = null)
                    Spacer(Modifier.width(VesqenSpacing.xs))
                    Text(stringResource(R.string.chain_open_advanced))
                }
            }
            item {
                ChainSummaryPanel(playback = playback)
            }
            item {
                ChainPathSummary(
                    telemetrySnapshot = observationState.lastSnapshot(),
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                    unitDisplayMode = unitDisplayMode,
                )
            }
        }
    }
}

@Composable
private fun ChainCurrentSource(playback: PlaybackSnapshot) {
    Text(
        text = stringResource(R.string.chain_current_source, playback.title.ifBlank {
            stringResource(R.string.unknown_title)
        }),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().testTag("vesqen.chain.current-source"),
    )
}

@Composable
private fun ChainSummaryPanel(playback: PlaybackSnapshot) {
    val status = playback.usbOutputStatus
    val title = when (status.phase) {
        io.github.sumirenokai.vesqen.playback.UsbOutputPhase.SYSTEM ->
            stringResource(R.string.chain_system_mixed_title)
        io.github.sumirenokai.vesqen.playback.UsbOutputPhase.AVAILABLE ->
            stringResource(R.string.chain_strict_available_title)
        io.github.sumirenokai.vesqen.playback.UsbOutputPhase.APPLYING ->
            stringResource(R.string.chain_strict_applying_title)
        io.github.sumirenokai.vesqen.playback.UsbOutputPhase.ACTIVE ->
            stringResource(R.string.chain_strict_active_title)
        io.github.sumirenokai.vesqen.playback.UsbOutputPhase.FAILED ->
            stringResource(R.string.chain_strict_failed_title)
    }
    val body = when (status.phase) {
        io.github.sumirenokai.vesqen.playback.UsbOutputPhase.SYSTEM ->
            stringResource(R.string.chain_system_mixed_body)
        io.github.sumirenokai.vesqen.playback.UsbOutputPhase.AVAILABLE ->
            stringResource(
                R.string.chain_strict_available_body,
                status.deviceName ?: stringResource(R.string.settings_usb_device_unknown),
            )
        io.github.sumirenokai.vesqen.playback.UsbOutputPhase.APPLYING ->
            stringResource(R.string.chain_strict_applying_body)
        io.github.sumirenokai.vesqen.playback.UsbOutputPhase.ACTIVE ->
            stringResource(
                R.string.chain_strict_active_body,
                status.deviceName ?: stringResource(R.string.settings_usb_device_unknown),
                status.sinkFormat?.displayName ?: stringResource(R.string.settings_format_unknown),
            )
        io.github.sumirenokai.vesqen.playback.UsbOutputPhase.FAILED ->
            stringResource(R.string.chain_strict_failed_body, status.decisionCode)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("vesqen.chain.summary"),
        shape = RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(VesqenSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
        ) {
            OutputStatusChip(declaration = playback.declaration)
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChainCorePanel(
    snapshot: TelemetrySnapshot?,
    nowElapsedRealtimeMs: Long,
    unitDisplayMode: ChainUnitDisplayMode,
) {
    val metrics = snapshot?.metrics.orEmpty().associateBy { it.id }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("vesqen.chain.core"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AccountTree, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.chain_core_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
            }
            BoxWithConstraints {
                val source: @Composable () -> Unit = {
                    ChainCoreFact(TelemetryMetricCatalog.SOURCE_SAMPLE_RATE, metrics, nowElapsedRealtimeMs, unitDisplayMode, prominent = true)
                    ChainCoreFact(TelemetryMetricCatalog.SOURCE_BIT_DEPTH, metrics, nowElapsedRealtimeMs, unitDisplayMode)
                }
                val pcm: @Composable () -> Unit = {
                    ChainCoreFact(TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE, metrics, nowElapsedRealtimeMs, unitDisplayMode, prominent = true)
                    ChainCoreFact(TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_ENCODING, metrics, nowElapsedRealtimeMs, unitDisplayMode)
                }
                if (maxWidth >= 280.dp && LocalDensity.current.fontScale <= 1.3f) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { source() }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { pcm() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { source(); pcm() }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ChainCoreFact(TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME, metrics, nowElapsedRealtimeMs, unitDisplayMode)
            listOf(
                TelemetryMetricCatalog.PLAYBACK_CURRENT_MEDIA_READ_BITRATE,
                TelemetryMetricCatalog.PLAYBACK_ESTIMATED_TOTAL_BUFFERED_DURATION,
                TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT,
            ).forEach { id -> ChainCoreFact(id, metrics, nowElapsedRealtimeMs, unitDisplayMode, inline = true) }
            Text(stringResource(R.string.chain_core_boundary), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun rememberedTelemetryReading(reading: TelemetryReading?, unitDisplayMode: ChainUnitDisplayMode): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Evidence timestamps still update each sample; an unchanged reading needs no new formatter.
    return remember(context, configuration, reading, unitDisplayMode) {
        formatTelemetryReading(context, reading, unitDisplayMode)
    }
}

@Composable
private fun ChainCoreFact(
    id: TelemetryMetricId,
    metrics: Map<TelemetryMetricId, TelemetryMetric>,
    nowElapsedRealtimeMs: Long,
    unitDisplayMode: ChainUnitDisplayMode,
    prominent: Boolean = false,
    inline: Boolean = false,
) {
    val context = LocalContext.current
    val evidence = metrics[id]?.evidence
    val label = telemetryMetricLabel(context, id)
    val value = rememberedTelemetryReading(evidence?.reading, unitDisplayMode)
    var expanded by remember(id) { mutableStateOf(false) }
    val action = stringResource(R.string.chain_evidence_details)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("vesqen.chain.core.${id.value}")
            .clickable(onClickLabel = action) { expanded = !expanded }
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (inline && LocalDensity.current.fontScale <= 1.3f) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, Modifier.weight(.8f), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace))
            }
        } else {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = (if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
                .copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium))
        }
        Text(
            evidence?.let { telemetryConfidenceLabel(context, it.confidence) + " · " + telemetryEvidenceAge(context, it, nowElapsedRealtimeMs) }
                ?: stringResource(R.string.chain_sampling_starting_short),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (evidence != null && (expanded || evidence is TelemetryEvidence.Unavailable)) {
            telemetryEvidenceMethod(context, evidence)?.let { method ->
                Text(method, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded && evidence != null) {
            Text(listOfNotNull(telemetryEvidenceSource(context, evidence), telemetryEvidenceWindow(context, evidence)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChainObservationNotice(
    state: ChainObservationState,
    nowElapsedRealtimeMs: Long,
    refreshInterval: TelemetryRefreshInterval,
    powerMode: TelemetryPowerMode,
    requestedMetricIds: Set<TelemetryMetricId>,
    onRetry: () -> Unit,
    compact: Boolean = false,
) {
    val snapshot = state.lastSnapshot()
    val isStale = snapshot?.let {
        isTelemetrySnapshotStale(it, nowElapsedRealtimeMs, refreshInterval, powerMode)
    } == true
    val unavailableCount = snapshot?.let { current ->
        val metrics = current.metrics.associateBy(TelemetryMetric::id)
        requestedMetricIds.count { id ->
            metrics[id]?.evidence.let { evidence ->
                evidence == null || evidence is TelemetryEvidence.Unavailable
            }
        }
    } ?: 0
    val content = when {
        state is ChainObservationState.Failed -> ChainNoticeContent(
            icon = Icons.Filled.ErrorOutline,
            title = stringResource(R.string.chain_sampling_failed),
            body = stringResource(
                if (snapshot == null) R.string.chain_sampling_failed_body else R.string.chain_sampling_failed_preserved,
            ),
            tag = "vesqen.chain.failure",
            showRetry = true,
        )
        state is ChainObservationState.Waiting -> ChainNoticeContent(
            icon = Icons.Filled.Schedule,
            title = stringResource(R.string.chain_sampling_starting),
            body = stringResource(R.string.chain_sampling_starting_body),
            tag = "vesqen.chain.loading",
            showProgress = true,
        )
        isStale -> ChainNoticeContent(
            icon = Icons.Filled.Schedule,
            title = stringResource(R.string.chain_snapshot_stale),
            body = stringResource(R.string.chain_snapshot_stale_body),
            tag = "vesqen.chain.stale",
            showRetry = true,
        )
        unavailableCount > 0 -> ChainNoticeContent(
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            title = stringResource(R.string.chain_partial_title),
            body = pluralStringResource(
                R.plurals.chain_partial_body,
                unavailableCount,
                unavailableCount,
            ),
            tag = "vesqen.chain.partial",
        )
        else -> ChainNoticeContent(
            icon = Icons.Filled.Sensors,
            title = stringResource(R.string.chain_live_title),
            body = stringResource(R.string.chain_live_body),
            tag = "vesqen.chain.live",
        )
    }
    if (compact && state is ChainObservationState.Content && !isStale) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).testTag(content.tag)
            .semantics { contentDescription = content.body }, verticalAlignment = Alignment.CenterVertically) {
            Icon(content.icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(content.title + if (unavailableCount > 0) " ($unavailableCount)" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(content.tag),
        shape = RoundedCornerShape(VesqenRadii.control),
        color = if (state is ChainObservationState.Failed) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (state is ChainObservationState.Failed) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Column(
            modifier = Modifier.padding(VesqenSpacing.md),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(content.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(VesqenSpacing.xs))
                Column(modifier = Modifier.weight(1f)) {
                    Text(content.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        content.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state is ChainObservationState.Failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (content.showRetry) {
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier.size(48.dp).testTag("vesqen.chain.retry"),
                    ) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.retry))
                    }
                }
            }
            if (content.showProgress) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private data class ChainNoticeContent(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val body: String,
    val tag: String,
    val showRetry: Boolean = false,
    val showProgress: Boolean = false,
)

@Composable
private fun ChainPathSummary(
    telemetrySnapshot: TelemetrySnapshot?,
    nowElapsedRealtimeMs: Long,
    unitDisplayMode: ChainUnitDisplayMode,
) {
    val context = LocalContext.current
    val metricsBySection = telemetrySnapshot?.metrics.orEmpty().groupBy { it.section }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("vesqen.chain.path"),
        shape = RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(VesqenSpacing.md)) {
            Text(
                text = stringResource(R.string.chain_current_path),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(VesqenSpacing.sm))
            SummarySections.forEach { section ->
                val metrics = metricsBySection[section]
                    .orEmpty()
                    .filter { metric ->
                        runCatching { TelemetryMetricCatalog.descriptor(metric.id).defaultVisible }.getOrDefault(false)
                    }
                    .take(2)
                val value = when {
                    metrics.isNotEmpty() -> metrics.joinToString(" · ") { metric ->
                        "${telemetryMetricLabel(context, metric.id)} ${
                            formatTelemetryReading(context, metric.evidence.reading, unitDisplayMode)
                        }"
                    }
                    else -> stringResource(R.string.unavailable)
                }
                val updated = metrics.maxOfOrNull { it.evidence.observedAtElapsedRealtimeMs }?.let { observedAt ->
                    val evidence = metrics.first { it.evidence.observedAtElapsedRealtimeMs == observedAt }.evidence
                    telemetryEvidenceAge(context, evidence, nowElapsedRealtimeMs)
                }
                ChainPathRow(
                    section = section,
                    value = value,
                    updated = updated,
                )
            }
        }
    }
}

@Composable
private fun ChainPathRow(section: TelemetrySection, value: String, updated: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = VesqenSpacing.sm)
            .testTag("vesqen.chain.path.${section.name.lowercase()}"),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(VesqenRadii.control),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AccountTree, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(VesqenSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(telemetrySectionLabel(LocalContext.current, section), style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            updated?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChainAdvancedLayout(
    playback: PlaybackSnapshot,
    observationState: ChainObservationState,
    nowElapsedRealtimeMs: Long,
    history: ChainMetricHistoryBuffer,
    preferences: ChainDashboardPreferences,
    onPreferencesChanged: (ChainDashboardPreferences) -> Unit,
    onCustomize: () -> Unit,
    diagnosticState: DiagnosticRecordingState,
    diagnosticAvailable: Boolean,
    diagnosticExportFeedback: DiagnosticExportFeedback,
    onStartDiagnosticRecording: () -> Unit,
    onStopDiagnosticRecording: () -> Unit,
    onClearDiagnosticRecording: () -> Unit,
    onRequestDiagnosticExport: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("vesqen.chain.advanced")) {
        if (maxWidth >= 840.dp) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = VesqenSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.lg),
            ) {
                LazyColumn(
                    modifier = Modifier.width(320.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = VesqenSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
                ) {
                    item { ChainSummaryPanel(playback) }
                    item {
                        ChainObservationNotice(
                            state = observationState,
                            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                            refreshInterval = preferences.refreshInterval,
                            powerMode = preferences.powerMode,
                            requestedMetricIds = preferences.observedMetricIds(),
                            onRetry = onRetry,
                        )
                    }
                    item {
                        ChainPathSummary(
                            telemetrySnapshot = observationState.lastSnapshot(),
                            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                            unitDisplayMode = preferences.unitDisplayMode,
                        )
                    }
                }
                ChainMetricsGrid(
                    playback = playback,
                    observationState = observationState,
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                    history = history,
                    preferences = preferences,
                    columns = GridCells.Adaptive(240.dp),
                    showObservationNotice = false,
                    onPreferencesChanged = onPreferencesChanged,
                    onCustomize = onCustomize,
                    diagnosticState = diagnosticState,
                    diagnosticAvailable = diagnosticAvailable,
                    diagnosticExportFeedback = diagnosticExportFeedback,
                    onStartDiagnosticRecording = onStartDiagnosticRecording,
                    onStopDiagnosticRecording = onStopDiagnosticRecording,
                    onClearDiagnosticRecording = onClearDiagnosticRecording,
                    onRequestDiagnosticExport = onRequestDiagnosticExport,
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            val columns = if (maxWidth >= 600.dp) GridCells.Fixed(2) else GridCells.Fixed(1)
            ChainMetricsGrid(
                playback = playback,
                observationState = observationState,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                history = history,
                preferences = preferences,
                columns = columns,
                showObservationNotice = true,
                onPreferencesChanged = onPreferencesChanged,
                onCustomize = onCustomize,
                diagnosticState = diagnosticState,
                diagnosticAvailable = diagnosticAvailable,
                diagnosticExportFeedback = diagnosticExportFeedback,
                onStartDiagnosticRecording = onStartDiagnosticRecording,
                onStopDiagnosticRecording = onStopDiagnosticRecording,
                onClearDiagnosticRecording = onClearDiagnosticRecording,
                onRequestDiagnosticExport = onRequestDiagnosticExport,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ChainMetricsGrid(
    playback: PlaybackSnapshot,
    observationState: ChainObservationState,
    nowElapsedRealtimeMs: Long,
    history: ChainMetricHistoryBuffer,
    preferences: ChainDashboardPreferences,
    columns: GridCells,
    showObservationNotice: Boolean,
    onPreferencesChanged: (ChainDashboardPreferences) -> Unit,
    onCustomize: () -> Unit,
    diagnosticState: DiagnosticRecordingState,
    diagnosticAvailable: Boolean,
    diagnosticExportFeedback: DiagnosticExportFeedback,
    onStartDiagnosticRecording: () -> Unit,
    onStopDiagnosticRecording: () -> Unit,
    onClearDiagnosticRecording: () -> Unit,
    onRequestDiagnosticExport: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = observationState.lastSnapshot()
    val metricMap = remember(snapshot?.metrics) { snapshot?.metrics.orEmpty().associateBy { it.id } }
    val expectedCadenceMs = effectiveTelemetryIntervalMs(
        preferences.refreshInterval,
        preferences.powerMode,
    )
    val (pinned, grouped) = remember(
        preferences.selectedMetricIds, preferences.metricOrder, preferences.pinnedMetricIds, preferences.groupOrder,
    ) {
        val selectedSet = preferences.selectedMetricIds.toSet()
        val orderedIds = preferences.metricOrder.filter(selectedSet::contains)
        val pinnedSet = preferences.pinnedMetricIds.toSet()
        val grouped = preferences.groupOrder.mapNotNull { section ->
            val ids = orderedIds.filter { id ->
                id !in pinnedSet && runCatching { TelemetryMetricCatalog.descriptor(id).section == section }.getOrDefault(false)
            }
            if (ids.isEmpty()) null else section to ids
        }
        orderedIds.filter(pinnedSet::contains) to grouped
    }
    LazyVerticalGrid(
        columns = columns,
        modifier = modifier.testTag("vesqen.chain.metrics"),
        contentPadding = PaddingValues(
            start = VesqenSpacing.md,
            top = VesqenSpacing.xs,
            end = VesqenSpacing.md,
            bottom = VesqenSpacing.xl,
        ),
        horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "current-source", span = { GridItemSpan(maxLineSpan) }) { ChainCurrentSource(playback) }
        if (showObservationNotice) {
            item(key = "observation-notice", span = { GridItemSpan(maxLineSpan) }) {
                ChainObservationNotice(
                    state = observationState,
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                    refreshInterval = preferences.refreshInterval,
                    powerMode = preferences.powerMode,
                    requestedMetricIds = preferences.observedMetricIds(),
                    onRetry = onRetry,
                    compact = true,
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ChainCorePanel(snapshot, nowElapsedRealtimeMs, preferences.unitDisplayMode)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ChainDashboardControls(
                preferences = preferences,
                onPreferencesChanged = onPreferencesChanged,
                onCustomize = onCustomize,
            )
        }
        if (pinned.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ChainSectionHeading(stringResource(R.string.chain_pinned_metrics))
            }
            gridItems(pinned, key = { "pinned:${it.value}" }) { id ->
                ChainMetricCard(
                    metricId = id,
                    metric = metricMap[id],
                    pinned = true,
                    viewMode = preferences.viewMode,
                    unitDisplayMode = preferences.unitDisplayMode,
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                    history = if (preferences.viewMode == ChainMetricViewMode.CHART) history.points(id) else emptyList(),
                    expectedCadenceMs = expectedCadenceMs,
                )
            }
        }
        grouped.forEach { (section, ids) ->
            item(key = "section:${section.name}", span = { GridItemSpan(maxLineSpan) }) {
                ChainSectionHeading(telemetrySectionLabel(LocalContext.current, section))
            }
            gridItems(ids, key = { it.value }) { id ->
                ChainMetricCard(
                    metricId = id,
                    metric = metricMap[id],
                    pinned = false,
                    viewMode = preferences.viewMode,
                    unitDisplayMode = preferences.unitDisplayMode,
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                    history = if (preferences.viewMode == ChainMetricViewMode.CHART) history.points(id) else emptyList(),
                    expectedCadenceMs = expectedCadenceMs,
                )
            }
        }
        item(key = "diagnostic-recorder", span = { GridItemSpan(maxLineSpan) }) {
            ChainDiagnosticPanel(
                state = diagnosticState,
                available = diagnosticAvailable,
                exportFeedback = diagnosticExportFeedback,
                onStart = onStartDiagnosticRecording,
                onStop = onStopDiagnosticRecording,
                onClear = onClearDiagnosticRecording,
                onExport = onRequestDiagnosticExport,
            )
        }
        item(key = "recent-events", span = { GridItemSpan(maxLineSpan) }) {
            ChainRecentEventsPanel(
                events = snapshot?.recentEvents.orEmpty(),
                currentPlaybackSessionId = snapshot?.playbackSessionId,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            )
        }
    }
}

@Composable
private fun ChainRecentEventsPanel(
    events: List<TelemetryEvent>,
    currentPlaybackSessionId: String?,
    nowElapsedRealtimeMs: Long,
) {
    val context = LocalContext.current
    val visibleEvents = events.asReversed().take(8)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vesqen.chain.recent-events"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.chain_recent_events_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.chain_recent_events_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (visibleEvents.isEmpty()) {
                Text(
                    text = stringResource(R.string.chain_recent_events_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("vesqen.chain.recent-events.empty"),
                )
            } else {
                visibleEvents.forEach { event ->
                    val containerColor = when (event.severity) {
                        TelemetryEventSeverity.INFO -> MaterialTheme.colorScheme.surface
                        TelemetryEventSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
                        TelemetryEventSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer
                    }
                    val contentColor = when (event.severity) {
                        TelemetryEventSeverity.INFO -> MaterialTheme.colorScheme.onSurface
                        TelemetryEventSeverity.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
                        TelemetryEventSeverity.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vesqen.chain.event.${event.sequence}"),
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        color = containerColor,
                        contentColor = contentColor,
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = telemetryEventKindLabel(event.kind),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = telemetryEventSeverityLabel(event.severity),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Text(
                                text = event.code,
                                style = VesqenDataStyle,
                            )
                            Text(
                                text = stringResource(
                                    R.string.chain_event_metadata,
                                    telemetryEventAge(context, event, nowElapsedRealtimeMs),
                                    telemetryEventScopeLabel(event, currentPlaybackSessionId),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun telemetryEventKindLabel(kind: TelemetryEventKind): String = stringResource(
    when (kind) {
        TelemetryEventKind.MEDIA_ITEM_CHANGED -> R.string.chain_event_kind_media_item_changed
        TelemetryEventKind.PLAYBACK_STATE_CHANGED -> R.string.chain_event_kind_playback_state_changed
        TelemetryEventKind.FORMAT_CHANGED -> R.string.chain_event_kind_format_changed
        TelemetryEventKind.ROUTE_CHANGED -> R.string.chain_event_kind_route_changed
        TelemetryEventKind.UNDERRUN -> R.string.chain_event_kind_underrun
        TelemetryEventKind.SEEK_COMPLETED -> R.string.chain_event_kind_seek_completed
        TelemetryEventKind.DECODER_INITIALIZED -> R.string.chain_event_kind_decoder_initialized
        TelemetryEventKind.OUTPUT_INITIALIZED -> R.string.chain_event_kind_output_initialized
        TelemetryEventKind.USB_ATTACHED -> R.string.chain_event_kind_usb_attached
        TelemetryEventKind.USB_DETACHED -> R.string.chain_event_kind_usb_detached
        TelemetryEventKind.DIAGNOSTIC_RECORDING_STARTED -> R.string.chain_event_kind_diagnostic_started
        TelemetryEventKind.DIAGNOSTIC_RECORDING_STOPPED -> R.string.chain_event_kind_diagnostic_stopped
        TelemetryEventKind.ERROR -> R.string.chain_event_kind_error
    },
)

@Composable
private fun telemetryEventSeverityLabel(severity: TelemetryEventSeverity): String = stringResource(
    when (severity) {
        TelemetryEventSeverity.INFO -> R.string.chain_event_severity_info
        TelemetryEventSeverity.WARNING -> R.string.chain_event_severity_warning
        TelemetryEventSeverity.ERROR -> R.string.chain_event_severity_error
    },
)

@Composable
private fun telemetryEventScopeLabel(
    event: TelemetryEvent,
    currentPlaybackSessionId: String?,
): String = stringResource(
    when {
        event.playbackSessionId == null -> R.string.chain_event_scope_system
        event.playbackSessionId == currentPlaybackSessionId -> R.string.chain_event_scope_current
        else -> R.string.chain_event_scope_earlier
    },
)

private fun telemetryEventAge(
    context: android.content.Context,
    event: TelemetryEvent,
    nowElapsedRealtimeMs: Long,
): String {
    val elapsedSeconds = (
        (nowElapsedRealtimeMs - event.occurredAtElapsedRealtimeMs).coerceAtLeast(0) / 1_000
    ).toInt()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainDiagnosticPanel(
    state: DiagnosticRecordingState,
    available: Boolean,
    exportFeedback: DiagnosticExportFeedback,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    val progress = when (state) {
        DiagnosticRecordingState.Idle -> null
        is DiagnosticRecordingState.Active -> state.progress
        is DiagnosticRecordingState.Stopping -> state.progress
        is DiagnosticRecordingState.Stopped -> null
    }
    val recording = (state as? DiagnosticRecordingState.Stopped)?.recording
    val snapshotCount = progress?.snapshotCount ?: recording?.snapshots?.size
    val eventCount = progress?.eventCount ?: recording?.events?.size
    val droppedSnapshotCount = progress?.droppedSnapshotCount ?: recording?.droppedSnapshotCount ?: 0
    val droppedEventCount = progress?.droppedEventCount ?: recording?.droppedEventCount ?: 0
    val sequenceGapCount = progress?.observedEventSequenceGapCount
        ?: recording?.observedEventSequenceGapCount
        ?: 0
    val isExporting = exportFeedback == DiagnosticExportFeedback.EXPORTING
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state is DiagnosticRecordingState.Stopped) {
        if (state !is DiagnosticRecordingState.Stopped) showClearConfirmation = false
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vesqen.chain.diagnostics"),
        shape = RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(VesqenSpacing.md),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    tint = if (state is DiagnosticRecordingState.Active) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(VesqenSpacing.xs))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chain_diagnostic_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(
                            when (state) {
                                DiagnosticRecordingState.Idle -> if (available) {
                                    R.string.chain_diagnostic_idle
                                } else {
                                    R.string.chain_diagnostic_unavailable
                                }
                                is DiagnosticRecordingState.Active -> R.string.chain_diagnostic_active
                                is DiagnosticRecordingState.Stopping -> R.string.chain_diagnostic_stopping
                                is DiagnosticRecordingState.Stopped -> R.string.chain_diagnostic_stopped
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("vesqen.chain.diagnostics.status"),
                    )
                }
            }
            Text(
                text = stringResource(R.string.chain_diagnostic_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshotCount != null && eventCount != null) {
                Text(
                    text = stringResource(
                        R.string.chain_diagnostic_counts,
                        pluralStringResource(
                            R.plurals.chain_diagnostic_snapshot_count,
                            snapshotCount,
                            snapshotCount,
                        ),
                        pluralStringResource(
                            R.plurals.chain_diagnostic_event_count,
                            eventCount,
                            eventCount,
                        ),
                    ),
                    style = VesqenDataStyle,
                    modifier = Modifier.testTag("vesqen.chain.diagnostics.counts"),
                )
            }
            if (droppedSnapshotCount > 0 || droppedEventCount > 0 || sequenceGapCount > 0) {
                Surface(
                    shape = RoundedCornerShape(VesqenRadii.control),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().testTag("vesqen.chain.diagnostics.warning"),
                ) {
                    Row(
                        modifier = Modifier.padding(VesqenSpacing.sm),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Filled.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(VesqenSpacing.xs))
                        Column(modifier = Modifier.weight(1f)) {
                            if (droppedSnapshotCount > 0 || droppedEventCount > 0) {
                                Text(
                                    stringResource(
                                        R.string.chain_diagnostic_dropped_warning,
                                        pluralStringResource(
                                            R.plurals.chain_diagnostic_dropped_snapshot_count,
                                            droppedSnapshotCount.pluralQuantity(),
                                            droppedSnapshotCount,
                                        ),
                                        pluralStringResource(
                                            R.plurals.chain_diagnostic_dropped_event_count,
                                            droppedEventCount.pluralQuantity(),
                                            droppedEventCount,
                                        ),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (sequenceGapCount > 0) {
                                Text(
                                    pluralStringResource(
                                        R.plurals.chain_diagnostic_sequence_warning,
                                        sequenceGapCount.pluralQuantity(),
                                        sequenceGapCount,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            recording?.termination?.let { termination ->
                if (termination != DiagnosticRecordingTermination.USER_STOPPED) {
                    Text(
                        text = diagnosticTerminationLabel(termination),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (termination == DiagnosticRecordingTermination.SOURCE_FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.testTag("vesqen.chain.diagnostics.termination"),
                    )
                }
            }
            diagnosticExportFeedback(exportFeedback)?.let { feedback ->
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (
                        exportFeedback == DiagnosticExportFeedback.WRITE_FAILED ||
                        exportFeedback == DiagnosticExportFeedback.DESTINATION_UNAVAILABLE ||
                        exportFeedback == DiagnosticExportFeedback.NO_STOPPED_RECORDING
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("vesqen.chain.diagnostics.export-status"),
                )
            }
            if (state is DiagnosticRecordingState.Stopping || isExporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
            ) {
                when (state) {
                    DiagnosticRecordingState.Idle -> Button(
                        onClick = onStart,
                        enabled = available,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.chain.diagnostics.start"),
                    ) {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                        Spacer(Modifier.width(VesqenSpacing.xs))
                        Text(stringResource(R.string.chain_diagnostic_start))
                    }
                    is DiagnosticRecordingState.Active -> Button(
                        onClick = onStop,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.chain.diagnostics.stop"),
                    ) {
                        Icon(Icons.Filled.StopCircle, contentDescription = null)
                        Spacer(Modifier.width(VesqenSpacing.xs))
                        Text(stringResource(R.string.chain_diagnostic_stop))
                    }
                    is DiagnosticRecordingState.Stopping -> Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.chain.diagnostics.stop"),
                    ) {
                        Icon(Icons.Filled.StopCircle, contentDescription = null)
                        Spacer(Modifier.width(VesqenSpacing.xs))
                        Text(stringResource(R.string.chain_diagnostic_stopping_action))
                    }
                    is DiagnosticRecordingState.Stopped -> {
                        Button(
                            onClick = onExport,
                            enabled = !isExporting,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.chain.diagnostics.export"),
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(VesqenSpacing.xs))
                            Text(stringResource(R.string.chain_diagnostic_export))
                        }
                        OutlinedButton(
                            onClick = { showClearConfirmation = true },
                            enabled = !isExporting,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.chain.diagnostics.clear"),
                        ) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(VesqenSpacing.xs))
                            Text(stringResource(R.string.chain_diagnostic_clear))
                        }
                    }
                }
            }
        }
    }
    if (showClearConfirmation && state is DiagnosticRecordingState.Stopped) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.chain_diagnostic_clear_confirm_title)) },
            text = { Text(stringResource(R.string.chain_diagnostic_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClear()
                    },
                    modifier = Modifier.testTag("vesqen.chain.diagnostics.clear-confirm"),
                ) {
                    Text(
                        text = stringResource(R.string.chain_diagnostic_clear_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmation = false },
                    modifier = Modifier.testTag("vesqen.chain.diagnostics.clear-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun Long.pluralQuantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

@Composable
private fun diagnosticTerminationLabel(termination: DiagnosticRecordingTermination): String = stringResource(
    when (termination) {
        DiagnosticRecordingTermination.USER_STOPPED -> R.string.chain_diagnostic_termination_user
        DiagnosticRecordingTermination.PLAYBACK_STOPPED -> R.string.chain_diagnostic_termination_playback_stopped
        DiagnosticRecordingTermination.SOURCE_COMPLETED -> R.string.chain_diagnostic_termination_completed
        DiagnosticRecordingTermination.SOURCE_FAILED -> R.string.chain_diagnostic_termination_failed
        DiagnosticRecordingTermination.OWNER_CANCELLED -> R.string.chain_diagnostic_termination_cancelled
    },
)

@Composable
private fun diagnosticExportFeedback(feedback: DiagnosticExportFeedback): String? = when (feedback) {
    DiagnosticExportFeedback.NONE -> null
    DiagnosticExportFeedback.EXPORTING -> stringResource(R.string.chain_diagnostic_exporting)
    DiagnosticExportFeedback.SUCCESS -> stringResource(R.string.chain_diagnostic_export_success)
    DiagnosticExportFeedback.CANCELLED -> stringResource(R.string.chain_diagnostic_export_cancelled)
    DiagnosticExportFeedback.NO_STOPPED_RECORDING ->
        stringResource(R.string.chain_diagnostic_export_no_recording)
    DiagnosticExportFeedback.DESTINATION_UNAVAILABLE ->
        stringResource(R.string.chain_diagnostic_export_destination_failed)
    DiagnosticExportFeedback.WRITE_FAILED -> stringResource(R.string.chain_diagnostic_export_write_failed)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainDashboardControls(
    preferences: ChainDashboardPreferences,
    onPreferencesChanged: (ChainDashboardPreferences) -> Unit,
    onCustomize: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("vesqen.chain.dashboard-controls"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stackSelectors = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f
            val selectorWidth = if (maxWidth < 480.dp) (maxWidth - 8.dp) / 2 else null
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val selectorModifier = when {
                    stackSelectors -> Modifier.fillMaxWidth()
                    selectorWidth != null -> Modifier.width(selectorWidth)
                    else -> Modifier.weight(1f)
                }
                ChainOptionMenuButton(
                    stringResource(R.string.chain_view_mode), viewModeLabel(preferences.viewMode),
                    ChainMetricViewMode.entries.map { it to viewModeLabel(it) },
                    { onPreferencesChanged(preferences.copy(viewMode = it)) }, "vesqen.chain.control.view", selectorModifier,
                )
                ChainOptionMenuButton(
                    stringResource(R.string.chain_refresh_interval), refreshIntervalLabel(preferences.refreshInterval),
                    TelemetryRefreshInterval.entries.map { it to refreshIntervalLabel(it) },
                    { onPreferencesChanged(preferences.copy(refreshInterval = it)) }, "vesqen.chain.control.refresh", selectorModifier,
                )
                IconButton(onClick = { showSettings = !showSettings }, modifier = Modifier.size(48.dp).testTag("vesqen.chain.control.settings")) {
                    Icon(Icons.Filled.Tune, stringResource(R.string.chain_dashboard_controls),
                        tint = if (showSettings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onCustomize, modifier = Modifier.size(48.dp).testTag("vesqen.chain.customize")) {
                    Icon(Icons.Filled.DashboardCustomize, stringResource(R.string.chain_customize))
                }
            }
        }
        if (showSettings) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val controlModifier = if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) Modifier.fillMaxWidth()
                    else Modifier.width((maxWidth - 8.dp) / 2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChainOptionMenuButton(
                        stringResource(R.string.chain_unit_display), unitDisplayModeLabel(preferences.unitDisplayMode),
                        ChainUnitDisplayMode.entries.map { it to unitDisplayModeLabel(it) },
                        { onPreferencesChanged(preferences.copy(unitDisplayMode = it)) }, "vesqen.chain.control.units", controlModifier,
                    )
                    ChainOptionMenuButton(
                        stringResource(R.string.chain_power_mode), powerModeLabel(preferences.powerMode),
                        TelemetryPowerMode.entries.map { it to powerModeLabel(it) },
                        { onPreferencesChanged(preferences.copy(powerMode = it)) }, "vesqen.chain.control.power", controlModifier,
                    )
                    val minimumWindowMs = effectiveTelemetryIntervalMs(preferences.refreshInterval, preferences.powerMode)
                    ChainOptionMenuButton(
                        stringResource(R.string.chain_derived_window), derivedWindowLabel(preferences.derivedWindow),
                        ChainDerivedWindow.entries.filter { it.milliseconds >= minimumWindowMs }.map { it to derivedWindowLabel(it) },
                        { onPreferencesChanged(preferences.copy(derivedWindow = it)) }, "vesqen.chain.control.window", controlModifier,
                    )
                    ChainOptionMenuButton(
                        stringResource(R.string.chain_history_length), historyLengthLabel(preferences.historyLength),
                        ChainHistoryLength.entries.map { it to historyLengthLabel(it) },
                        { onPreferencesChanged(preferences.copy(historyLength = it)) }, "vesqen.chain.control.history", controlModifier,
                    )
                }
            }
        }
    }
}
@Composable
private fun <T> ChainOptionMenuButton(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(testTag)
                .semantics { stateDescription = value },
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(
                horizontal = VesqenSpacing.sm,
                vertical = VesqenSpacing.xs,
            ),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            Spacer(Modifier.width(VesqenSpacing.xs))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (option, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun viewModeLabel(mode: ChainMetricViewMode): String = stringResource(
    when (mode) {
        ChainMetricViewMode.COMPACT -> R.string.chain_view_compact
        ChainMetricViewMode.DETAILED -> R.string.chain_view_detailed
        ChainMetricViewMode.CHART -> R.string.chain_view_chart
    },
)

@Composable
private fun unitDisplayModeLabel(mode: ChainUnitDisplayMode): String = stringResource(
    when (mode) {
        ChainUnitDisplayMode.AUTO -> R.string.chain_unit_display_auto
        ChainUnitDisplayMode.SI -> R.string.chain_unit_display_si
        ChainUnitDisplayMode.RAW -> R.string.chain_unit_display_raw
    },
)

@Composable
private fun refreshIntervalLabel(interval: TelemetryRefreshInterval): String = stringResource(
    when (interval) {
        TelemetryRefreshInterval.QUARTER_SECOND -> R.string.chain_refresh_250ms
        TelemetryRefreshInterval.HALF_SECOND -> R.string.chain_refresh_500ms
        TelemetryRefreshInterval.ONE_SECOND -> R.string.chain_refresh_1s
        TelemetryRefreshInterval.TWO_SECONDS -> R.string.chain_refresh_2s
        TelemetryRefreshInterval.FIVE_SECONDS -> R.string.chain_refresh_5s
    },
)

@Composable
private fun powerModeLabel(mode: TelemetryPowerMode): String = stringResource(
    when (mode) {
        TelemetryPowerMode.STANDARD -> R.string.chain_power_standard
        TelemetryPowerMode.LOW_POWER -> R.string.chain_power_low
    },
)

@Composable
private fun derivedWindowLabel(window: ChainDerivedWindow): String = stringResource(
    when (window) {
        ChainDerivedWindow.QUARTER_SECOND -> R.string.chain_duration_250ms
        ChainDerivedWindow.HALF_SECOND -> R.string.chain_duration_500ms
        ChainDerivedWindow.ONE_SECOND -> R.string.chain_duration_1s
        ChainDerivedWindow.TWO_SECONDS -> R.string.chain_duration_2s
        ChainDerivedWindow.FIVE_SECONDS -> R.string.chain_duration_5s
        ChainDerivedWindow.TEN_SECONDS -> R.string.chain_duration_10s
        ChainDerivedWindow.THIRTY_SECONDS -> R.string.chain_duration_30s
        ChainDerivedWindow.ONE_MINUTE -> R.string.chain_duration_1m
    },
)

@Composable
private fun historyLengthLabel(length: ChainHistoryLength): String = stringResource(
    when (length) {
        ChainHistoryLength.FIFTEEN_SECONDS -> R.string.chain_duration_15s
        ChainHistoryLength.THIRTY_SECONDS -> R.string.chain_duration_30s
        ChainHistoryLength.ONE_MINUTE -> R.string.chain_duration_1m
        ChainHistoryLength.TWO_MINUTES -> R.string.chain_duration_2m
        ChainHistoryLength.FIVE_MINUTES -> R.string.chain_duration_5m
    },
)

@Composable
private fun ChainSectionHeading(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.semantics { heading() })
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
@Composable
private fun ChainMetricCard(
    metricId: TelemetryMetricId,
    metric: TelemetryMetric?,
    pinned: Boolean,
    viewMode: ChainMetricViewMode,
    unitDisplayMode: ChainUnitDisplayMode,
    nowElapsedRealtimeMs: Long,
    history: List<ChainHistoryPoint>,
    expectedCadenceMs: Long,
) {
    val context = LocalContext.current
    val label = telemetryMetricLabel(context, metricId)
    var evidenceExpanded by remember(metricId) { mutableStateOf(false) }
    val evidenceAction = stringResource(R.string.chain_evidence_details)
    val evidence = metric?.evidence
    val value = rememberedTelemetryReading(evidence?.reading, unitDisplayMode)
    val confidence = evidence?.let { telemetryConfidenceLabel(context, it.confidence) }
        ?: stringResource(R.string.chain_sampling_starting_short)
    val source = evidence?.let { telemetryEvidenceSource(context, it) }
    val updated = evidence?.let { telemetryEvidenceAge(context, it, nowElapsedRealtimeMs) }
    val window = evidence?.let { telemetryEvidenceWindow(context, it) }
    val method = evidence?.let { telemetryEvidenceMethod(context, it) }
    val usbInventory = (evidence?.reading as? TelemetryReading.UsbInventory)?.value
    val chartableUnit = (evidence?.reading as? TelemetryReading.Integer)?.unit
        ?: (evidence?.reading as? TelemetryReading.Decimal)?.unit
    val chartDescription = chartableUnit?.takeIf { viewMode == ChainMetricViewMode.CHART }?.let {
        chartSummary(context, label, history, it, expectedCadenceMs, unitDisplayMode)
    }
    val chartEvidenceSummary = if (chartDescription != null) {
        chainChartEvidenceSummary(context, history, expectedCadenceMs)
    } else {
        null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vesqen.chain.metric.${metricId.value}")
            .clickable(onClickLabel = evidenceAction) { evidenceExpanded = !evidenceExpanded }
            .semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(12.dp),
        color = if (pinned) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Identifiers and route descriptions need the full card width even when they contain
            // few characters. Numeric values retain the aligned readout column at normal text size.
            val stackedValue = evidence?.reading is TelemetryReading.Text ||
                evidence?.reading is TelemetryReading.UsbInventory || LocalDensity.current.fontScale > 1.3f
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (pinned) Icon(Icons.Filled.PushPin, stringResource(R.string.chain_pinned), Modifier.size(14.dp))
                if (!stackedValue) Text(
                    value, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.End, modifier = Modifier.weight(.85f).testTag("vesqen.chain.metric-value.${metricId.value}"),
                )
                Icon(if (evidenceExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (stackedValue) Text(value, style = VesqenDataStyle,
                modifier = Modifier.fillMaxWidth().testTag("vesqen.chain.metric-value.${metricId.value}"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { ChainConfidenceChip(evidence?.confidence, confidence) }
                if (viewMode != ChainMetricViewMode.COMPACT) updated?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (viewMode != ChainMetricViewMode.COMPACT) {
                usbInventory?.let { ChainUsbInventoryDetails(it) }
                if (source != null || window != null) Text(
                    listOfNotNull(source, window).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (evidenceExpanded || evidence is TelemetryEvidence.Unavailable) {
                    method?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (viewMode == ChainMetricViewMode.CHART && chartDescription != null) {
                ChainMetricChart(
                    points = history,
                    description = listOfNotNull(
                        chartDescription,
                        chartEvidenceSummary?.accessibilityDescription,
                    ).joinToString(" "),
                    color = MaterialTheme.colorScheme.primary,
                    expectedCadenceMs = expectedCadenceMs,
                    modifier = Modifier.testTag("vesqen.chain.chart.${metricId.value}"),
                )
                Text(
                    text = stringResource(
                        R.string.chain_chart_span,
                        telemetryHistorySpan(context, history),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                chartEvidenceSummary?.let { summary ->
                    ChainChartEvidenceSummary(
                        summary = summary,
                        modifier = Modifier.testTag("vesqen.chain.chart-evidence.${metricId.value}"),
                    )
                }
            } else if (viewMode == ChainMetricViewMode.CHART && chartableUnit != null) {
                Text(
                    text = stringResource(R.string.chain_chart_collecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class ChainChartEvidenceProfile(
    val confidence: TelemetryConfidence,
    val sourceId: String?,
    val windowDurationMs: Long?,
)

private data class ChainChartEvidenceSummaryContent(
    val title: String,
    val profiles: List<String>,
) {
    val accessibilityDescription: String = (listOf(title) + profiles).joinToString(". ")
}

private fun chainChartEvidenceSummary(
    context: android.content.Context,
    points: List<ChainHistoryPoint>,
    expectedCadenceMs: Long,
): ChainChartEvidenceSummaryContent? {
    val segments = segmentChartHistory(points, expectedCadenceMs)
    if (segments.isEmpty()) return null
    val profiles = segments.groupBy { segment ->
        segment.first().let { point ->
            ChainChartEvidenceProfile(
                confidence = point.confidence,
                sourceId = point.sourceId,
                windowDurationMs = point.windowDurationMs,
            )
        }
    }
    val segmentCount = context.resources.getQuantityString(
        R.plurals.chain_chart_evidence_segment_count,
        segments.size,
        segments.size,
    )
    return ChainChartEvidenceSummaryContent(
        title = context.getString(R.string.chain_chart_evidence_title, segmentCount),
        profiles = profiles.map { (profile, matchingSegments) ->
            val confidence = telemetryConfidenceLabel(context, profile.confidence)
            val source = profile.sourceId?.let { sourceId ->
                telemetrySourceLabelResource(sourceId)?.let(context::getString) ?: sourceId
            } ?: context.getString(R.string.chain_source_not_available)
            val window = profile.windowDurationMs?.let { durationMs ->
                context.getString(
                    R.string.chain_window_value,
                    formatSeconds(context, durationMs / 1_000.0),
                )
            } ?: context.getString(R.string.chain_chart_evidence_instant)
            val sampleCount = matchingSegments.sumOf { it.size }
            val sampleCountLabel = context.resources.getQuantityString(
                R.plurals.chain_chart_sample_count,
                sampleCount,
                sampleCount,
            )
            val matchingSegmentCountLabel = context.resources.getQuantityString(
                R.plurals.chain_chart_evidence_segment_count,
                matchingSegments.size,
                matchingSegments.size,
            )
            context.getString(
                R.string.chain_chart_evidence_profile,
                confidence,
                source,
                window,
                sampleCountLabel,
                matchingSegmentCountLabel,
            )
        },
    )
}

@Composable
private fun ChainChartEvidenceSummary(
    summary: ChainChartEvidenceSummaryContent,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
    ) {
        Text(
            text = summary.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        summary.profiles.forEach { profile ->
            Text(
                text = profile,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChainUsbInventoryDetails(inventory: UsbInventoryReading) {
    val unnamedDevice = stringResource(R.string.chain_usb_unnamed_device)
    val unnamedEndpoint = stringResource(R.string.chain_usb_unnamed_endpoint)
    val permissionGranted = stringResource(R.string.chain_usb_permission_granted)
    val permissionNotGranted = stringResource(R.string.chain_usb_permission_not_granted)
    val anyValue = stringResource(R.string.chain_usb_any_value)
    val unavailable = stringResource(R.string.unavailable)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.chain_usb_independent_observations),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.chain_usb_host_devices),
            style = MaterialTheme.typography.labelLarge,
        )
        if (inventory.hostDevices.isEmpty()) {
            Text(
                text = stringResource(R.string.chain_usb_none_reported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            inventory.hostDevices.forEachIndexed { deviceIndex, device ->
                val name = listOfNotNull(device.manufacturerName, device.productName)
                    .filter(String::isNotBlank)
                    .joinToString(" ")
                    .ifBlank { unnamedDevice }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vesqen.chain.usb.host.$deviceIndex"),
                    verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.chain_usb_host_identity,
                            formatUsbDescriptor(device.vendorId, 4),
                            formatUsbDescriptor(device.productId, 4),
                            if (device.permissionGranted) permissionGranted else permissionNotGranted,
                        ),
                        style = VesqenDataStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("vesqen.chain.usb.host.$deviceIndex.identity"),
                    )
                    if (device.audioInterfaces.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chain_usb_no_audio_interfaces),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        device.audioInterfaces.forEachIndexed { interfaceIndex, audioInterface ->
                            Text(
                                text = stringResource(
                                    R.string.chain_usb_audio_interface,
                                    interfaceIndex + 1,
                                    formatUsbDescriptor(audioInterface.interfaceClass, 2),
                                    formatUsbDescriptor(audioInterface.interfaceSubclass, 2),
                                    formatUsbDescriptor(audioInterface.interfaceProtocol, 2),
                                ),
                                style = VesqenDataStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(
                                    "vesqen.chain.usb.host.$deviceIndex.interface.$interfaceIndex",
                                ),
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.chain_usb_audio_endpoints),
            style = MaterialTheme.typography.labelLarge,
        )
        if (inventory.audioOutputEndpoints.isEmpty()) {
            Text(
                text = stringResource(R.string.chain_usb_none_reported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            inventory.audioOutputEndpoints.forEach { endpoint ->
                val endpointName = endpoint.productName
                    ?.takeIf(String::isNotBlank)
                    ?: unnamedEndpoint
                val sampleRates = if (endpoint.arbitrarySampleRate) {
                    anyValue
                } else {
                    endpoint.sampleRatesHz.joinToString().ifBlank { unavailable }
                }
                val channelCounts = if (endpoint.arbitraryChannelCount) {
                    anyValue
                } else {
                    endpoint.channelCounts.joinToString().ifBlank { unavailable }
                }
                val encodings = if (endpoint.arbitraryEncoding) {
                    anyValue
                } else {
                    endpoint.encodings.joinToString().ifBlank { unavailable }
                }
                Text(
                    text = stringResource(R.string.chain_usb_endpoint_value, endpointName, endpoint.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.chain_usb_endpoint_capabilities,
                        sampleRates,
                        channelCounts,
                        encodings,
                    ),
                    style = VesqenDataStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatUsbDescriptor(value: Int, minimumDigits: Int): String =
    String.format(Locale.ROOT, "0x%0${minimumDigits}X", value)

@Composable
private fun ChainConfidenceChip(confidence: TelemetryConfidence?, label: String) {
    val icon = when (confidence) {
        TelemetryConfidence.MEASURED -> Icons.Filled.Sensors
        TelemetryConfidence.DERIVED -> Icons.Filled.Functions
        TelemetryConfidence.ESTIMATED -> Icons.Filled.Speed
        else -> Icons.AutoMirrored.Filled.HelpOutline
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun ChainMetricMeta(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Text(
            text = value,
            style = VesqenDataStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ChainMetricChart(
    points: List<ChainHistoryPoint>,
    description: String,
    color: Color,
    expectedCadenceMs: Long,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .semantics { contentDescription = description },
    ) {
        if (points.isEmpty()) return@Canvas
        val minimum = points.minOf { it.value }
        val maximum = points.maxOf { it.value }
        val span = (maximum - minimum).takeIf { it > 0.0 } ?: 1.0
        val firstElapsedRealtimeMs = points.first().elapsedRealtimeMs
        val lastElapsedRealtimeMs = points.last().elapsedRealtimeMs
        fun x(point: ChainHistoryPoint): Float = size.width * elapsedChartFraction(
            elapsedRealtimeMs = point.elapsedRealtimeMs,
            firstElapsedRealtimeMs = firstElapsedRealtimeMs,
            lastElapsedRealtimeMs = lastElapsedRealtimeMs,
        )
        fun y(point: ChainHistoryPoint): Float =
            size.height - ((point.value - minimum) / span * size.height).toFloat()
        points.forEach { point ->
            drawCircle(color = color, radius = 2.dp.toPx(), center = Offset(x(point), y(point)))
        }
        segmentChartHistory(points, expectedCadenceMs).forEach { segment ->
            segment.zipWithNext().forEach { (first, second) ->
                drawLine(
                    color = color,
                    start = Offset(x(first), y(first)),
                    end = Offset(x(second), y(second)),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChainCustomizerSheet(
    preferences: ChainDashboardPreferences,
    onPreferencesChanged: (ChainDashboardPreferences) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().testTag("vesqen.chain.customizer")) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = VesqenSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chain_customize_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.close))
                }
            }
            Text(
                text = stringResource(R.string.chain_customize_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(VesqenSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
            ) {
                preferences.groupOrder.forEach { section ->
                    item(key = "custom-section:${section.name}") {
                        ChainCustomizerSectionRow(
                            section = section,
                            canMoveUp = preferences.groupOrder.indexOf(section) > 0,
                            canMoveDown = preferences.groupOrder.indexOf(section) < preferences.groupOrder.lastIndex,
                            onMoveUp = {
                                onPreferencesChanged(
                                    preferences.copy(groupOrder = preferences.groupOrder.moved(section, -1)),
                                )
                            },
                            onMoveDown = {
                                onPreferencesChanged(
                                    preferences.copy(groupOrder = preferences.groupOrder.moved(section, 1)),
                                )
                            },
                        )
                    }
                    val sectionIds = preferences.metricOrder.filter { id ->
                        runCatching { TelemetryMetricCatalog.descriptor(id).section == section }.getOrDefault(false)
                    }
                    items(sectionIds, key = { "custom:${it.value}" }) { id ->
                        val selected = id in preferences.selectedMetricIds
                        val pinned = id in preferences.pinnedMetricIds
                        val selectedInSection = sectionIds.filter(preferences.selectedMetricIds.toSet()::contains)
                        val position = selectedInSection.indexOf(id)
                        val canMoveUp = selected && position > 0
                        val canMoveDown = selected && position >= 0 && position < selectedInSection.lastIndex
                        ChainCustomizerMetricRow(
                            metricId = id,
                            selected = selected,
                            selectionEnabled = !selected || preferences.selectedMetricIds.size > 1,
                            pinned = pinned,
                            canMoveUp = canMoveUp,
                            canMoveDown = canMoveDown,
                            onSelectedChanged = { shouldSelect ->
                                val selection = if (shouldSelect) {
                                    preferences.selectedMetricIds + id
                                } else {
                                    preferences.selectedMetricIds - id
                                }
                                onPreferencesChanged(
                                    preferences.copy(
                                        selectedMetricIds = selection,
                                        pinnedMetricIds = if (shouldSelect) {
                                            preferences.pinnedMetricIds
                                        } else {
                                            preferences.pinnedMetricIds - id
                                        },
                                    ),
                                )
                            },
                            onPinnedChanged = { shouldPin ->
                                onPreferencesChanged(
                                    preferences.copy(
                                        pinnedMetricIds = if (shouldPin) {
                                            preferences.pinnedMetricIds + id
                                        } else {
                                            preferences.pinnedMetricIds - id
                                        },
                                    ),
                                )
                            },
                            onMoveUp = {
                                onPreferencesChanged(
                                    preferences.copy(metricOrder = preferences.metricOrder.movedWithin(id, selectedInSection, -1)),
                                )
                            },
                            onMoveDown = {
                                onPreferencesChanged(
                                    preferences.copy(metricOrder = preferences.metricOrder.movedWithin(id, selectedInSection, 1)),
                                )
                            },
                        )
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { confirmReset = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vesqen.chain.reset"),
                    ) {
                        Text(stringResource(R.string.chain_reset_dashboard))
                    }
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.chain_reset_title)) },
            text = { Text(stringResource(R.string.chain_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onReset()
                    },
                    modifier = Modifier.testTag("vesqen.chain.reset-confirm"),
                ) {
                    Text(stringResource(R.string.reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ChainCustomizerSectionRow(
    section: TelemetrySection,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val label = telemetrySectionLabel(LocalContext.current, section)
    ChainReorderRow(
        label = label,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        modifier = Modifier.testTag("vesqen.chain.customizer.section.${section.name.lowercase()}"),
        leading = {
            Icon(Icons.Filled.DashboardCustomize, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        labelStyle = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun ChainCustomizerMetricRow(
    metricId: TelemetryMetricId,
    selected: Boolean,
    selectionEnabled: Boolean,
    pinned: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelectedChanged: (Boolean) -> Unit,
    onPinnedChanged: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val label = telemetryMetricLabel(LocalContext.current, metricId)
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vesqen.chain.customizer.metric.${metricId.value}")
            .semantics {
                contentDescription = label
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction(moveUpLabel) { onMoveUp(); true })
                    if (canMoveDown) add(CustomAccessibilityAction(moveDownLabel) { onMoveDown(); true })
                }
            },
        shape = RoundedCornerShape(VesqenRadii.control),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = VesqenSpacing.xs, vertical = VesqenSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectedChanged,
                enabled = selectionEnabled,
                modifier = Modifier.testTag("vesqen.chain.customizer.select.${metricId.value}"),
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { onPinnedChanged(!pinned) },
                enabled = selected,
                modifier = Modifier.size(48.dp).testTag("vesqen.chain.customizer.pin.${metricId.value}"),
            ) {
                Icon(
                    if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = stringResource(if (pinned) R.string.chain_unpin else R.string.chain_pin),
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = selected && (canMoveUp || canMoveDown),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.reorder))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(moveUpLabel) },
                        enabled = canMoveUp,
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                        onClick = { menuExpanded = false; onMoveUp() },
                    )
                    DropdownMenuItem(
                        text = { Text(moveDownLabel) },
                        enabled = canMoveDown,
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                        onClick = { menuExpanded = false; onMoveDown() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChainReorderRow(
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    labelStyle: androidx.compose.ui.text.TextStyle,
) {
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = label
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction(moveUpLabel) { onMoveUp(); true })
                    if (canMoveDown) add(CustomAccessibilityAction(moveDownLabel) { onMoveDown(); true })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(VesqenSpacing.xs))
        Text(label, style = labelStyle, modifier = Modifier.weight(1f))
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.KeyboardArrowUp, moveUpLabel)
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.KeyboardArrowDown, moveDownLabel)
        }
    }
}

private fun <T> List<T>.moved(item: T, delta: Int): List<T> {
    val from = indexOf(item)
    if (from < 0) return this
    val to = (from + delta).coerceIn(indices)
    if (from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun List<TelemetryMetricId>.movedWithin(
    item: TelemetryMetricId,
    sectionOrder: List<TelemetryMetricId>,
    delta: Int,
): List<TelemetryMetricId> {
    val position = sectionOrder.indexOf(item)
    if (position < 0) return this
    val target = (position + delta).coerceIn(sectionOrder.indices)
    if (position == target) return this
    val targetItem = sectionOrder[target]
    val fromGlobal = indexOf(item)
    val targetGlobal = indexOf(targetItem)
    if (fromGlobal < 0 || targetGlobal < 0) return this
    return toMutableList().apply { add(targetGlobal, removeAt(fromGlobal)) }
}

private fun ChainObservationState.lastSnapshot(): TelemetrySnapshot? = when (this) {
    is ChainObservationState.Content -> snapshot
    is ChainObservationState.Failed -> lastSnapshot
    ChainObservationState.Waiting -> null
}
