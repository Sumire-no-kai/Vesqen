package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.AudioOutputType
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.components.OutputStatusChip
import io.github.sumirenokai.vesqen.ui.components.VesqenEmptyState
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

@Composable
fun ChainScreen(
    library: LibraryUiState,
    snapshot: PlaybackSnapshot,
    onBack: () -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!snapshot.hasActiveTrack) {
        ChainEmptyScreen(
            onBack = onBack,
            onBrowseLibrary = onBrowseLibrary,
            modifier = modifier,
        )
        return
    }

    val phoneSpeaker = stringResource(R.string.output_phone_speaker)
    val wiredOrUsb = stringResource(R.string.output_wired_or_usb)
    val bluetooth = stringResource(R.string.output_bluetooth)
    val other = stringResource(R.string.output_other)
    val outputLabels = library.connectedOutputs.map { output ->
        when (output) {
            AudioOutputType.PHONE_SPEAKER -> phoneSpeaker
            AudioOutputType.WIRED_OR_USB -> wiredOrUsb
            AudioOutputType.BLUETOOTH -> bluetooth
            AudioOutputType.OTHER -> other
        }
    }
    val currentTrack = snapshot.trackId?.let { trackId ->
        library.tracks.firstOrNull { it.id == trackId }
    }
    val sourceFacts = currentTrack?.let { track ->
        listOf(
            track.codec,
            track.sampleRateHz?.takeIf { it > 0 }?.let { "${it / 1_000f} kHz" }.orEmpty(),
            track.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" }.orEmpty(),
            track.channelCount?.takeIf { it > 0 }?.let { "$it ch" }.orEmpty(),
            track.bitrate?.takeIf { it > 0 }?.let { "${it / 1_000} kbps" }.orEmpty(),
        ).filter(String::isNotBlank).joinToString(" · ")
    }.orEmpty()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("vesqen.chain"),
        contentPadding = PaddingValues(
            horizontal = VesqenSpacing.lg,
            vertical = VesqenSpacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
    ) {
        item {
            ChainHeader(onBack = onBack)
        }
        item {
            ChainSummary(snapshot = snapshot)
        }
        item {
            EvidencePanel(
                icon = { Icon(imageVector = Icons.Filled.Route, contentDescription = null) },
                title = stringResource(R.string.chain_active_system_route),
                body = library.activeRoute?.let { route ->
                    stringResource(R.string.chain_active_system_route_value, route.name)
                } ?: stringResource(R.string.chain_active_system_route_unknown),
            )
        }
        item {
            EvidencePanel(
                icon = { Icon(imageVector = Icons.Filled.Route, contentDescription = null) },
                title = stringResource(R.string.chain_detected_outputs),
                body = if (outputLabels.isEmpty()) {
                    stringResource(R.string.output_none_detected)
                } else {
                    stringResource(R.string.detected_output_types_live, outputLabels.joinToString())
                },
            )
        }
        item {
            EvidencePanel(
                icon = { Icon(imageVector = Icons.Filled.FolderOpen, contentDescription = null) },
                title = stringResource(R.string.chain_source_details),
                body = if (sourceFacts.isBlank()) {
                    stringResource(R.string.chain_source_details_body)
                } else {
                    stringResource(R.string.chain_source_details_available, sourceFacts)
                },
            )
        }
        item {
            EvidencePanel(
                icon = { Icon(imageVector = Icons.Filled.Info, contentDescription = null) },
                title = stringResource(R.string.chain_direct_evidence),
                body = stringResource(R.string.chain_direct_evidence_body),
                status = stringResource(R.string.unavailable),
            )
        }
    }
}

@Composable
private fun ChainSummary(snapshot: PlaybackSnapshot) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(VesqenSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
        ) {
            OutputStatusChip(declaration = snapshot.declaration)
            Text(
                text = stringResource(R.string.chain_system_mixed_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.chain_system_mixed_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EvidencePanel(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    status: String? = null,
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(VesqenSpacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.width(VesqenSpacing.sm))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChainEmptyScreen(
    onBack: () -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ChainHeader(
            onBack = onBack,
            modifier = Modifier.padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.md),
        )
        VesqenEmptyState(
            title = stringResource(R.string.chain_empty_title),
            body = stringResource(R.string.chain_empty_body),
            actionLabel = stringResource(R.string.browse_library),
            onAction = onBrowseLibrary,
            modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
        )
    }
}

@Composable
private fun ChainHeader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("vesqen.chain.back"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Spacer(Modifier.width(VesqenSpacing.xs))
        Text(
            text = stringResource(R.string.destination_chain),
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}
