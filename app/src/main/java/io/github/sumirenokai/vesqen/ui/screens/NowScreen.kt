package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.components.AlbumArtwork
import io.github.sumirenokai.vesqen.ui.components.OutputStatusChip
import io.github.sumirenokai.vesqen.ui.components.PlaybackControls
import io.github.sumirenokai.vesqen.ui.components.TrackDetailsSheet
import io.github.sumirenokai.vesqen.ui.components.VesqenEmptyState
import io.github.sumirenokai.vesqen.ui.formatDuration
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

@Composable
fun NowScreen(
    snapshot: PlaybackSnapshot,
    currentTrack: AudioTrack?,
    onBackToLibrary: () -> Unit,
    onOpenChain: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayTrack: (AudioTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!snapshot.hasActiveTrack) {
        NowEmptyScreen(onBackToLibrary = onBackToLibrary, modifier = modifier)
        return
    }

    var showDetails by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        NowHeader(
            onBackToLibrary = onBackToLibrary,
            canOpenDetails = currentTrack != null,
            onOpenDetails = { showDetails = true },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
        ) {
            item {
                Spacer(Modifier.height(VesqenSpacing.md))
                AlbumArtwork(
                    modifier = Modifier
                        .fillMaxWidth(.78f)
                        .widthIn(max = 360.dp)
                        .aspectRatio(1f),
                    emphasized = true,
                )
            }
            item {
                NowTrackIdentity(snapshot = snapshot)
            }
            item {
                OutputStatusChip(
                    declaration = snapshot.declaration,
                    onClick = onOpenChain,
                    modifier = Modifier.testTag("vesqen.now.open-chain"),
                )
            }
            item {
                PlaybackProgress(snapshot = snapshot, onSeek = onSeek)
            }
            item {
                PlaybackControls(
                    snapshot = snapshot,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                )
                Spacer(Modifier.height(VesqenSpacing.xl))
            }
        }
    }

    if (showDetails && currentTrack != null) {
        TrackDetailsSheet(
            track = currentTrack,
            onDismiss = { showDetails = false },
            onPlay = {
                onPlayTrack(currentTrack)
                showDetails = false
            },
        )
    }
}

@Composable
private fun NowHeader(
    onBackToLibrary: () -> Unit,
    canOpenDetails: Boolean,
    onOpenDetails: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBackToLibrary, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back_to_library),
            )
        }
        Text(
            text = stringResource(R.string.destination_now),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        IconButton(
            onClick = onOpenDetails,
            enabled = canOpenDetails,
            modifier = Modifier.size(48.dp).testTag("vesqen.now.track-details"),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.track_details),
            )
        }
    }
}

@Composable
private fun NowTrackIdentity(snapshot: PlaybackSnapshot) {
    Column(
        modifier = Modifier.padding(horizontal = VesqenSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
    ) {
        Text(
            text = snapshot.title.ifBlank { stringResource(R.string.unknown_title) },
            style = MaterialTheme.typography.displayLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = snapshot.artist.ifBlank { stringResource(R.string.unknown_artist) },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!snapshot.isControllerReady) {
            Text(
                text = stringResource(R.string.playback_controls_connecting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaybackProgress(
    snapshot: PlaybackSnapshot,
    onSeek: (Long) -> Unit,
) {
    if (snapshot.durationMs <= 0) return

    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(snapshot.trackId, snapshot.durationMs, snapshot.positionMs) {
        if (!isSeeking) {
            seekPosition = snapshot.positionMs.coerceIn(0, snapshot.durationMs).toFloat()
        }
    }
    val positionLabel = stringResource(
        R.string.playback_position,
        formatDuration(seekPosition.toLong()),
        formatDuration(snapshot.durationMs),
    )
    val progressContentDescription = stringResource(R.string.playback_progress)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.xl),
    ) {
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("vesqen.now.progress")
                .semantics {
                    contentDescription = progressContentDescription
                    stateDescription = positionLabel
                },
            value = seekPosition.coerceIn(0f, snapshot.durationMs.toFloat()),
            onValueChange = {
                isSeeking = true
                seekPosition = it
            },
            onValueChangeFinished = {
                onSeek(seekPosition.toLong())
                isSeeking = false
            },
            valueRange = 0f..snapshot.durationMs.toFloat(),
            enabled = snapshot.isControllerReady,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration(seekPosition.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatDuration(snapshot.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NowEmptyScreen(onBackToLibrary: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.destination_now),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.md),
        )
        VesqenEmptyState(
            title = stringResource(R.string.now_empty_title),
            body = stringResource(R.string.now_empty_body),
            actionLabel = stringResource(R.string.browse_library),
            onAction = onBackToLibrary,
            modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
        )
    }
}
