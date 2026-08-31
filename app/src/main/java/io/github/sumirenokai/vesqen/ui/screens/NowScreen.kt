package io.github.sumirenokai.vesqen.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
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
import io.github.sumirenokai.vesqen.ui.theme.MidnightViolet
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme

@Composable
fun NowScreen(
    snapshot: PlaybackSnapshot,
    currentTrack: AudioTrack?,
    artworkTrack: AudioTrack?,
    onBackToLibrary: () -> Unit,
    onOpenChain: () -> Unit,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayTrack: (AudioTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!snapshot.hasActiveTrack) {
        NowEmptyScreen(onBackToLibrary = onBackToLibrary, modifier = modifier)
        return
    }

    var showDetails by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState { 2 }

    LaunchedEffect(currentTrack) {
        if (currentTrack == null) showDetails = false
    }
    BackHandler(enabled = showDetails && currentTrack != null) { showDetails = false }
    val openDetails = { if (currentTrack != null) showDetails = true }

    VesqenTheme(darkTheme = true) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val configuration = LocalConfiguration.current
            val isShortScreen = maxHeight < 640.dp || configuration.fontScale > 1.15f
            val isUltraCompact = maxHeight < 560.dp ||
                (maxHeight < 640.dp && configuration.fontScale > 1.15f) ||
                configuration.fontScale > 1.5f
            val isExtremeText = configuration.fontScale >= 2f
            val artworkSize = minOf(
                when {
                    maxHeight < 480.dp ||
                        (maxHeight < 640.dp && configuration.fontScale >= 1.8f) ||
                        isExtremeText -> 64.dp
                    isUltraCompact -> 96.dp
                    configuration.fontScale > 1.3f -> 128.dp
                    maxHeight < 640.dp || configuration.fontScale > 1.15f -> 176.dp
                    maxHeight < 760.dp -> 224.dp
                    else -> 320.dp
                },
                (maxWidth - 64.dp).coerceAtLeast(128.dp),
            )

            FullPlayerBackdrop(artworkTrack = artworkTrack)
            Column(modifier = Modifier.fillMaxSize()) {
                NowHeader(onBack = onBackToLibrary)
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("vesqen.now.info-pager"),
                    beyondViewportPageCount = 0,
                ) { page ->
                    when (page) {
                        0 -> NowPlayerPage(
                            snapshot = snapshot,
                            currentTrack = currentTrack,
                            artworkTrack = artworkTrack,
                            artworkSize = artworkSize,
                            isShortScreen = isShortScreen,
                            isUltraCompact = isUltraCompact,
                            isExtremeText = isExtremeText,
                            onOpenChain = onOpenChain,
                            onToggleShuffle = onToggleShuffle,
                            onPrevious = onPrevious,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            onCycleRepeatMode = onCycleRepeatMode,
                            onSeek = onSeek,
                            onOpenDetails = openDetails,
                            selectedInfoPage = page,
                        )

                        else -> NowSessionPage(
                            snapshot = snapshot,
                            onOpenChain = onOpenChain,
                            onOpenDetails = openDetails,
                            canOpenDetails = currentTrack != null,
                            isUltraCompact = isUltraCompact,
                            selectedInfoPage = page,
                        )
                    }
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
}

@Composable
private fun FullPlayerBackdrop(artworkTrack: AudioTrack?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightViolet),
    ) {
        // The visible player remains fully legible. Only real album artwork may add a low-key
        // ambient layer; the opaque scrim is the cross-version contrast fallback.
        if (!artworkTrack?.albumArtworkUri.isNullOrBlank()) {
            AlbumArtwork(
                track = artworkTrack,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(28.dp)
                    .alpha(.10f),
                emphasized = true,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MidnightViolet.copy(alpha = .88f)),
        )
    }
}

@Composable
private fun NowPlayerPage(
    snapshot: PlaybackSnapshot,
    currentTrack: AudioTrack?,
    artworkTrack: AudioTrack?,
    artworkSize: androidx.compose.ui.unit.Dp,
    isShortScreen: Boolean,
    isUltraCompact: Boolean,
    isExtremeText: Boolean,
    onOpenChain: () -> Unit,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenDetails: () -> Unit,
    selectedInfoPage: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag("vesqen.now.player-page")
            .padding(horizontal = VesqenSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(
            when {
                isUltraCompact -> 0.dp
                isShortScreen -> VesqenSpacing.xs
                else -> VesqenSpacing.sm
            },
        ),
    ) {
        if (!isUltraCompact) Spacer(Modifier.height(VesqenSpacing.xs))
        AlbumArtwork(
            track = artworkTrack,
            modifier = Modifier
                .size(artworkSize)
                .testTag("vesqen.now.artwork"),
            emphasized = true,
        )
        NowTrackIdentity(snapshot = snapshot, showArtist = !isExtremeText)
        if (!isExtremeText) {
            OutputStatusChip(
                declaration = snapshot.declaration,
                onClick = onOpenChain,
                modifier = Modifier.testTag("vesqen.now.open-chain"),
            )
        }
        PlaybackProgress(snapshot = snapshot, onSeek = onSeek)
        PlaybackControls(
            snapshot = snapshot,
            onToggleShuffle = onToggleShuffle,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onCycleRepeatMode = onCycleRepeatMode,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp),
        )
        NowInfoFooter(
            selectedPage = selectedInfoPage,
            onOpenDetails = onOpenDetails,
            canOpenDetails = currentTrack != null,
        )
    }
}

@Composable
private fun NowSessionPage(
    snapshot: PlaybackSnapshot,
    onOpenChain: () -> Unit,
    onOpenDetails: () -> Unit,
    canOpenDetails: Boolean,
    isUltraCompact: Boolean,
    selectedInfoPage: Int,
) {
    val queueLabel = snapshot.queuePosition?.let { position ->
        stringResource(R.string.queue_position, position, snapshot.queueSize)
    } ?: stringResource(R.string.unavailable)
    val remaining = (snapshot.durationMs - snapshot.positionMs).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .padding(horizontal = if (isUltraCompact) VesqenSpacing.md else VesqenSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .testTag("vesqen.now.info.session"),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.surface),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f),
        ) {
            Column(
                modifier = Modifier.padding(if (isUltraCompact) VesqenSpacing.md else VesqenSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(
                    if (isUltraCompact) VesqenSpacing.xxs else VesqenSpacing.sm,
                ),
            ) {
                if (!isUltraCompact) {
                    Text(
                        text = stringResource(R.string.playback_session),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                NowInfoLine(
                    label = stringResource(R.string.playback_state),
                    value = stringResource(if (snapshot.isPlaying) R.string.playing else R.string.paused),
                    compact = isUltraCompact,
                )
                NowInfoLine(
                    label = stringResource(R.string.playback_progress),
                    value = stringResource(
                        R.string.playback_position,
                        formatDuration(snapshot.positionMs),
                        formatDuration(snapshot.durationMs),
                    ),
                    compact = isUltraCompact,
                )
                NowInfoLine(
                    label = stringResource(R.string.remaining_time),
                    value = formatDuration(remaining),
                    compact = isUltraCompact,
                )
                NowInfoLine(
                    label = stringResource(R.string.queue),
                    value = queueLabel,
                    compact = isUltraCompact,
                )
                OutputStatusChip(
                    declaration = snapshot.declaration,
                    onClick = onOpenChain,
                    modifier = Modifier.testTag("vesqen.now.info.chain"),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        NowInfoFooter(
            selectedPage = selectedInfoPage,
            onOpenDetails = onOpenDetails,
            canOpenDetails = canOpenDetails,
        )
    }
}

@Composable
private fun NowInfoLine(label: String, value: String, compact: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NowInfoFooter(
    selectedPage: Int,
    onOpenDetails: () -> Unit,
    canOpenDetails: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(2) { index ->
                Surface(
                    modifier = Modifier.size(if (index == selectedPage) 8.dp else 6.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (index == selectedPage) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
                    },
                ) {}
            }
        }
        IconButton(
            onClick = onOpenDetails,
            enabled = canOpenDetails,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(48.dp)
                .testTag("vesqen.now.info"),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.track_information),
            )
        }
    }
}

@Composable
private fun NowHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp).testTag("vesqen.now.back"),
        ) {
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
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun NowTrackIdentity(snapshot: PlaybackSnapshot, showArtist: Boolean) {
    Column(
        modifier = Modifier.padding(horizontal = VesqenSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
    ) {
        Text(
            text = snapshot.title.ifBlank { stringResource(R.string.unknown_title) },
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee()
                .testTag("vesqen.now.title"),
        )
        if (showArtist) {
            Text(
                text = snapshot.artist.ifBlank { stringResource(R.string.unknown_artist) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!snapshot.isControllerReady) {
            Text(
                text = stringResource(R.string.playback_controls_connecting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackProgress(
    snapshot: PlaybackSnapshot,
    onSeek: (Long) -> Unit,
) {
    if (snapshot.durationMs <= 0) return

    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    val interactionSource = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors()
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

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
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
            colors = colors,
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = colors,
                    enabled = snapshot.isControllerReady,
                    thumbSize = DpSize(12.dp, 12.dp),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    enabled = snapshot.isControllerReady,
                    colors = colors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 2.dp,
                )
            },
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
