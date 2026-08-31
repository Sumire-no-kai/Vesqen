package io.github.sumirenokai.vesqen.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

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
        FullPlayerSystemBars()
        Surface(
            modifier = modifier
                .fillMaxSize()
                .testTag("vesqen.now.focus-surface"),
            color = MidnightViolet,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val configuration = LocalConfiguration.current
                val isShortScreen = maxHeight < 720.dp || configuration.fontScale > 1.15f
                val isUltraCompact = maxHeight < 640.dp ||
                    (maxHeight < 720.dp && configuration.fontScale > 1.15f) ||
                    configuration.fontScale > 1.5f
                // A short landscape window has the same vertical budget as extreme text: reserve
                // it for transport rather than letting artist and route metadata clip the dock.
                val isExtremeText = configuration.fontScale >= 2f || maxHeight < 480.dp
                val isTallScreen = maxHeight >= 760.dp && configuration.fontScale <= 1.15f
                val artworkSize = minOf(
                    when {
                        maxHeight < 480.dp ||
                            (maxHeight < 640.dp && configuration.fontScale >= 1.8f) ||
                            isExtremeText -> 64.dp
                        isUltraCompact -> 88.dp
                        configuration.fontScale > 1.3f -> 120.dp
                        maxHeight < 720.dp || configuration.fontScale > 1.15f -> 160.dp
                        maxHeight < 760.dp -> 200.dp
                        else -> 248.dp
                    },
                    (maxWidth - 72.dp).coerceAtLeast(96.dp),
                )

                FullPlayerBackdrop(artworkTrack = artworkTrack)
                Column(modifier = Modifier.fillMaxSize()) {
                    NowHeader(
                        onBack = onBackToLibrary,
                        modifier = Modifier.statusBarsPadding(),
                    )
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
                                artworkTrack = artworkTrack,
                                artworkSize = artworkSize,
                                isShortScreen = isShortScreen,
                                isUltraCompact = isUltraCompact,
                                isExtremeText = isExtremeText,
                                isTallScreen = isTallScreen,
                                onOpenChain = onOpenChain,
                                onToggleShuffle = onToggleShuffle,
                                onPrevious = onPrevious,
                                onPlayPause = onPlayPause,
                                onNext = onNext,
                                onCycleRepeatMode = onCycleRepeatMode,
                                onSeek = onSeek,
                                onOpenDetails = openDetails,
                                canOpenDetails = currentTrack != null,
                                selectedInfoPage = page,
                            )

                            else -> NowSessionPage(
                                snapshot = snapshot,
                                onOpenChain = onOpenChain,
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeatMode = onCycleRepeatMode,
                                onOpenDetails = openDetails,
                                canOpenDetails = currentTrack != null,
                                isUltraCompact = isUltraCompact,
                                selectedInfoPage = page,
                            )
                        }
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

/**
 * The focused player deliberately owns the window edge-to-edge while it is visible. The
 * surrounding app can still follow the system theme, but this protected listening surface needs
 * light system-bar icons over its Midnight Violet backdrop.
 */
@Composable
@Suppress("DEPRECATION") // API 35+ draws edge-to-edge from the focus surface; older APIs need this fallback.
private fun FullPlayerSystemBars() {
    val view = LocalView.current
    val navigationBarColor = MaterialTheme.colorScheme.surfaceContainer.toArgb()

    DisposableEffect(view, navigationBarColor) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { activityWindow ->
            WindowCompat.getInsetsController(activityWindow, view)
        }
        val previousStatusBarColor = window?.statusBarColor
        val previousNavigationBarColor = window?.navigationBarColor
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        val previousLightNavigationBars = controller?.isAppearanceLightNavigationBars
        val previousStatusBarContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window?.isStatusBarContrastEnforced
        } else {
            null
        }
        val previousNavigationBarContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window?.isNavigationBarContrastEnforced
        } else {
            null
        }

        // Do not leave system-bar surfaces to the outer light activity theme or an OEM contrast
        // scrim. The status bar belongs to the Midnight artwork field and the navigation bar to
        // the dock beneath it; light system glyphs remain accessible on both dark surfaces.
        window?.statusBarColor = MidnightViolet.toArgb()
        window?.navigationBarColor = navigationBarColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window?.isStatusBarContrastEnforced = false
            window?.isNavigationBarContrastEnforced = false
        }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false

        onDispose {
            window?.let { activityWindow ->
                previousStatusBarColor?.let { activityWindow.statusBarColor = it }
                previousNavigationBarColor?.let { activityWindow.navigationBarColor = it }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    previousStatusBarContrast?.let { activityWindow.isStatusBarContrastEnforced = it }
                    previousNavigationBarContrast?.let { activityWindow.isNavigationBarContrastEnforced = it }
                }
            }
            controller?.let { systemBars ->
                previousLightStatusBars?.let { systemBars.isAppearanceLightStatusBars = it }
                previousLightNavigationBars?.let { systemBars.isAppearanceLightNavigationBars = it }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun NowPlayerPage(
    snapshot: PlaybackSnapshot,
    artworkTrack: AudioTrack?,
    artworkSize: androidx.compose.ui.unit.Dp,
    isShortScreen: Boolean,
    isUltraCompact: Boolean,
    isExtremeText: Boolean,
    isTallScreen: Boolean,
    onOpenChain: () -> Unit,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenDetails: () -> Unit,
    canOpenDetails: Boolean,
    selectedInfoPage: Int,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag("vesqen.now.player-page")
    ) {
        PlayerArtworkStage(
            artworkTrack = artworkTrack,
            artworkSize = artworkSize,
            compact = isUltraCompact,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = when {
                    isUltraCompact -> VesqenSpacing.xxs
                    isTallScreen -> VesqenSpacing.lg
                    else -> VesqenSpacing.sm
                }),
        )
        NowTransportDock(
            snapshot = snapshot,
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
            onOpenDetails = onOpenDetails,
            canOpenDetails = canOpenDetails,
            selectedInfoPage = selectedInfoPage,
            modifier = Modifier
                .align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PlayerArtworkStage(
    artworkTrack: AudioTrack?,
    artworkSize: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val framePadding = if (compact) VesqenSpacing.xxs else VesqenSpacing.xs
    Surface(
        modifier = modifier
            .size(artworkSize + framePadding * 2)
            .testTag("vesqen.now.artwork-stage"),
        shape = RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        AlbumArtwork(
            track = artworkTrack,
            modifier = Modifier
                .fillMaxSize()
                .padding(framePadding)
                .testTag("vesqen.now.artwork"),
            emphasized = true,
        )
    }
}

@Composable
private fun NowTransportDock(
    snapshot: PlaybackSnapshot,
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
    canOpenDetails: Boolean,
    selectedInfoPage: Int,
    modifier: Modifier = Modifier,
) {
    val verticalPadding = when {
        isUltraCompact -> VesqenSpacing.xs
        isShortScreen -> VesqenSpacing.sm
        else -> VesqenSpacing.lg
    }
    val sectionSpacing = when {
        isUltraCompact -> VesqenSpacing.xxs
        isShortScreen -> VesqenSpacing.xs
        else -> VesqenSpacing.sm
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("vesqen.now.transport-dock"),
        shape = RoundedCornerShape(
            topStart = VesqenRadii.surface,
            topEnd = VesqenRadii.surface,
        ),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(
                    start = VesqenSpacing.lg,
                    top = verticalPadding,
                    end = VesqenSpacing.lg,
                    bottom = VesqenSpacing.xs,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            NowTrackIdentity(
                snapshot = snapshot,
                showArtist = !isExtremeText,
                showAlbum = !isUltraCompact && !isExtremeText,
                compact = isUltraCompact,
            )
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
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 320.dp),
            )
            NowInfoFooter(
                snapshot = snapshot,
                selectedPage = selectedInfoPage,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeatMode = onCycleRepeatMode,
                onOpenDetails = onOpenDetails,
                canOpenDetails = canOpenDetails,
                compact = isUltraCompact,
            )
        }
    }
}

@Composable
private fun NowSessionPage(
    snapshot: PlaybackSnapshot,
    onOpenChain: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
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
            .navigationBarsPadding()
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
            contentColor = MaterialTheme.colorScheme.onSurface,
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
            snapshot = snapshot,
            selectedPage = selectedInfoPage,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeatMode = onCycleRepeatMode,
            onOpenDetails = onOpenDetails,
            canOpenDetails = canOpenDetails,
            compact = isUltraCompact,
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
    snapshot: PlaybackSnapshot,
    selectedPage: Int,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onOpenDetails: () -> Unit,
    canOpenDetails: Boolean,
    compact: Boolean,
) {
    val controlsEnabled = snapshot.isControllerReady
    val shuffleState = stringResource(
        if (snapshot.shuffleEnabled) R.string.shuffle_on else R.string.shuffle_off,
    )
    val repeatState = stringResource(
        when (snapshot.repeatMode) {
            io.github.sumirenokai.vesqen.playback.PlaybackRepeatMode.OFF -> R.string.repeat_off
            io.github.sumirenokai.vesqen.playback.PlaybackRepeatMode.ALL -> R.string.repeat_all
            io.github.sumirenokai.vesqen.playback.PlaybackRepeatMode.ONE -> R.string.repeat_one
        },
    )
    val inactiveModeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val disabledModeColor = inactiveModeColor.copy(alpha = .38f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        val showSessionLabel = !compact && maxWidth >= 300.dp
        IconButton(
            onClick = onToggleShuffle,
            enabled = controlsEnabled,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp)
                .testTag("vesqen.now.shuffle")
                .semantics { stateDescription = shuffleState },
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = if (snapshot.shuffleEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    inactiveModeColor
                },
                disabledContentColor = disabledModeColor,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = stringResource(R.string.shuffle),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showSessionLabel) {
                Text(
                    text = stringResource(R.string.playback_session),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onCycleRepeatMode,
                enabled = controlsEnabled,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("vesqen.now.repeat")
                    .semantics { stateDescription = repeatState },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (
                        snapshot.repeatMode == io.github.sumirenokai.vesqen.playback.PlaybackRepeatMode.OFF
                    ) {
                        inactiveModeColor
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    disabledContentColor = disabledModeColor,
                ),
            ) {
                Icon(
                    imageVector = if (
                        snapshot.repeatMode == io.github.sumirenokai.vesqen.playback.PlaybackRepeatMode.ONE
                    ) {
                        Icons.Filled.RepeatOne
                    } else {
                        Icons.Filled.Repeat
                    },
                    contentDescription = stringResource(R.string.repeat),
                )
            }
            IconButton(
                onClick = onOpenDetails,
                enabled = canOpenDetails,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("vesqen.now.info"),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.track_information),
                )
            }
        }
    }
}

@Composable
private fun NowHeader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
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
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.destination_now),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun NowTrackIdentity(
    snapshot: PlaybackSnapshot,
    showArtist: Boolean,
    showAlbum: Boolean,
    compact: Boolean,
) {
    val album = snapshot.album.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier.padding(horizontal = VesqenSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
    ) {
        Text(
            text = snapshot.title.ifBlank { stringResource(R.string.unknown_title) },
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
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
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showAlbum && album != null) {
            Text(
                text = album,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f),
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
