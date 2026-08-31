package io.github.sumirenokai.vesqen.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.sumirenokai.vesqen.playback.PlaybackOrderMode
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.components.AlbumArtwork
import io.github.sumirenokai.vesqen.ui.components.OutputStatusChip
import io.github.sumirenokai.vesqen.ui.components.PlaybackControls
import io.github.sumirenokai.vesqen.ui.components.TrackDetailsSheet
import io.github.sumirenokai.vesqen.ui.components.VesqenEmptyState
import io.github.sumirenokai.vesqen.ui.formatDuration
import io.github.sumirenokai.vesqen.ui.theme.FocusedPlayerMaterial
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

private val TrackTransitionEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val FocusedPlayerDockShape = RoundedCornerShape(
    topStart = VesqenRadii.surface,
    topEnd = VesqenRadii.surface,
)

private enum class TrackTransitionDirection {
    FORWARD,
    BACKWARD,
}

/** The Now shell stays put; only this upper focus stage changes its factual content. */
private enum class NowFocusContent {
    ARTWORK,
    SESSION,
}

@Immutable
private data class NowTrackPresentation(
    val trackId: Long?,
    val title: String,
    val artist: String,
    val album: String,
    val artworkTrack: AudioTrack?,
)

@Composable
fun NowScreen(
    snapshot: PlaybackSnapshot,
    currentTrack: AudioTrack?,
    artworkTrack: AudioTrack?,
    onBackToLibrary: () -> Unit,
    onOpenChain: () -> Unit,
    onCyclePlaybackOrder: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayTrack: (AudioTrack) -> Unit,
    motionPolicy: VesqenMotionPolicy,
    modifier: Modifier = Modifier,
) {
    if (!snapshot.hasActiveTrack) {
        NowEmptyScreen(onBackToLibrary = onBackToLibrary, modifier = modifier)
        return
    }

    var showDetails by remember { mutableStateOf(false) }
    var trackTransitionDirection by remember { mutableStateOf(TrackTransitionDirection.FORWARD) }
    var focusContent by remember { mutableStateOf(NowFocusContent.ARTWORK) }
    val trackPresentation = NowTrackPresentation(
        trackId = snapshot.trackId,
        title = snapshot.title,
        artist = snapshot.artist,
        album = snapshot.album,
        artworkTrack = artworkTrack,
    )

    LaunchedEffect(currentTrack) {
        if (currentTrack == null) showDetails = false
    }
    BackHandler(enabled = showDetails || focusContent == NowFocusContent.SESSION) {
        if (showDetails) {
            showDetails = false
        } else {
            focusContent = NowFocusContent.ARTWORK
        }
    }
    val openDetails = { if (currentTrack != null) showDetails = true }
    val requestPrevious = {
        trackTransitionDirection = TrackTransitionDirection.BACKWARD
        onPrevious()
    }
    val requestNext = {
        trackTransitionDirection = TrackTransitionDirection.FORWARD
        onNext()
    }

    VesqenTheme(darkTheme = true) {
        FullPlayerSystemBars()
        Surface(
            modifier = modifier
                .fillMaxSize()
                .testTag("vesqen.now.focus-surface"),
            color = FocusedPlayerMaterial.Canvas,
            contentColor = MaterialTheme.colorScheme.onSurface,
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

                FullPlayerBackdrop(
                    artworkTrack = artworkTrack,
                    motionPolicy = motionPolicy,
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    NowHeader(
                        onBack = onBackToLibrary,
                        modifier = Modifier.statusBarsPadding(),
                    )
                    NowPlayerPage(
                        snapshot = snapshot,
                        trackPresentation = trackPresentation,
                        trackTransitionDirection = trackTransitionDirection,
                        focusContent = focusContent,
                        motionPolicy = motionPolicy,
                        artworkSize = artworkSize,
                        isShortScreen = isShortScreen,
                        isUltraCompact = isUltraCompact,
                        isExtremeText = isExtremeText,
                        isTallScreen = isTallScreen,
                        onOpenChain = onOpenChain,
                        onCyclePlaybackOrder = onCyclePlaybackOrder,
                        onPrevious = requestPrevious,
                        onPlayPause = onPlayPause,
                        onNext = requestNext,
                        onSeek = onSeek,
                        onOpenDetails = openDetails,
                        onToggleFocusContent = {
                            focusContent = if (focusContent == NowFocusContent.ARTWORK) {
                                NowFocusContent.SESSION
                            } else {
                                NowFocusContent.ARTWORK
                            }
                        },
                        canOpenDetails = currentTrack != null,
                        modifier = Modifier.weight(1f),
                    )
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
private fun FullPlayerBackdrop(
    artworkTrack: AudioTrack?,
    motionPolicy: VesqenMotionPolicy,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusedPlayerMaterial.Canvas)
            .testTag("vesqen.now.backdrop"),
    ) {
        // Real artwork may cast one restrained reflection only where platform blur is real.
        // On API 26–30, render the opaque Canvas fallback rather than an unblurred cover at low
        // opacity: a vague photo is not the same as a controlled light source.
        AnimatedContent(
            targetState = artworkTrack,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { artworkReflectionTransition(motionPolicy) },
            label = "vesqen.now.artwork-reflection",
        ) { reflectionTrack ->
            if (
                isArtworkReflectionSupported(Build.VERSION.SDK_INT) &&
                !reflectionTrack?.contentUri.isNullOrBlank()
            ) {
                AlbumArtwork(
                    track = reflectionTrack,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(36.dp)
                        .alpha(FocusedPlayerMaterial.ArtworkReflectionAlpha),
                    emphasized = true,
                    fallbackContainerColor = FocusedPlayerMaterial.Canvas,
                    showFallback = false,
                    // The tag deliberately belongs to the loaded Image, not its neutral
                    // container. A URI that cannot produce a bitmap must not claim a reflection.
                    loadedArtworkModifier = Modifier.testTag("vesqen.now.artwork-reflection"),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FocusedPlayerMaterial.Canvas.copy(alpha = FocusedPlayerMaterial.CanvasScrimAlpha))
                .testTag("vesqen.now.backdrop.opaque-fallback"),
        )
    }
}

internal fun isArtworkReflectionSupported(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.S

private fun androidx.compose.animation.AnimatedContentTransitionScope<AudioTrack?>.artworkReflectionTransition(
    motionPolicy: VesqenMotionPolicy,
) = fadeIn(
    animationSpec = tween(motionPolicy.trackChangeMillis, easing = TrackTransitionEasing),
) togetherWith fadeOut(
    animationSpec = tween(
        durationMillis = if (motionPolicy.reduceMotion) {
            motionPolicy.trackChangeMillis
        } else {
            motionPolicy.trackChangeMillis * 3 / 4
        },
        easing = TrackTransitionEasing,
    ),
)

/**
 * The focused player deliberately owns the window edge-to-edge while it is visible. The
 * surrounding app can still follow the system theme, but this protected listening surface needs
 * light system-bar icons over its midnight-graphite material.
 */
@Composable
@Suppress("DEPRECATION") // API 35+ draws edge-to-edge from the focus surface; older APIs need this fallback.
private fun FullPlayerSystemBars() {
    val view = LocalView.current
    val navigationBarColor = FocusedPlayerMaterial.Dock.toArgb()

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
        // scrim. The status bar belongs to the midnight artwork field and the navigation bar to
        // the dock beneath it; light system glyphs remain accessible on both dark surfaces.
        window?.statusBarColor = FocusedPlayerMaterial.Canvas.toArgb()
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

private fun androidx.compose.animation.AnimatedContentTransitionScope<NowTrackPresentation>.trackPresentationTransition(
    direction: TrackTransitionDirection,
    motionPolicy: VesqenMotionPolicy,
) = if (motionPolicy.reduceMotion) {
    fadeIn(animationSpec = tween(motionPolicy.trackChangeMillis)) togetherWith
        fadeOut(animationSpec = tween(motionPolicy.trackChangeMillis))
} else {
    val enteringOffset: (Int) -> Int = if (direction == TrackTransitionDirection.FORWARD) {
        { width -> width / 10 }
    } else {
        { width -> -width / 10 }
    }
    val leavingOffset: (Int) -> Int = if (direction == TrackTransitionDirection.FORWARD) {
        { width -> -width / 12 }
    } else {
        { width -> width / 12 }
    }
    (fadeIn(
        animationSpec = tween(motionPolicy.trackChangeMillis, easing = TrackTransitionEasing),
    ) + slideInHorizontally(
        animationSpec = tween(motionPolicy.trackChangeMillis, easing = TrackTransitionEasing),
        initialOffsetX = enteringOffset,
    ) + scaleIn(
        initialScale = .985f,
        animationSpec = tween(motionPolicy.trackChangeMillis, easing = TrackTransitionEasing),
    )) togetherWith
        (fadeOut(
            animationSpec = tween(
                durationMillis = motionPolicy.trackChangeMillis * 3 / 4,
                easing = TrackTransitionEasing,
            ),
        ) + slideOutHorizontally(
            animationSpec = tween(motionPolicy.trackChangeMillis, easing = TrackTransitionEasing),
            targetOffsetX = leavingOffset,
        ) + scaleOut(
            targetScale = .985f,
            animationSpec = tween(motionPolicy.trackChangeMillis, easing = TrackTransitionEasing),
        ))
}

@Composable
private fun NowPlayerPage(
    snapshot: PlaybackSnapshot,
    trackPresentation: NowTrackPresentation,
    trackTransitionDirection: TrackTransitionDirection,
    focusContent: NowFocusContent,
    motionPolicy: VesqenMotionPolicy,
    artworkSize: androidx.compose.ui.unit.Dp,
    isShortScreen: Boolean,
    isUltraCompact: Boolean,
    isExtremeText: Boolean,
    isTallScreen: Boolean,
    onOpenChain: () -> Unit,
    onCyclePlaybackOrder: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenDetails: () -> Unit,
    onToggleFocusContent: () -> Unit,
    canOpenDetails: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusStageHeight = when {
        isExtremeText -> 112.dp
        isUltraCompact -> 152.dp
        isShortScreen -> 224.dp
        else -> 320.dp
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag("vesqen.now.player-page")
    ) {
        NowFocusStage(
            focusContent = focusContent,
            snapshot = snapshot,
            trackPresentation = trackPresentation,
            trackTransitionDirection = trackTransitionDirection,
            artworkSize = artworkSize,
            compact = isUltraCompact,
            isExtremeText = isExtremeText,
            motionPolicy = motionPolicy,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = when {
                    isUltraCompact -> VesqenSpacing.xxs
                    isTallScreen -> VesqenSpacing.lg
                    else -> VesqenSpacing.sm
                })
                .height(focusStageHeight),
        )
        NowTransportDock(
            snapshot = snapshot,
            trackPresentation = trackPresentation,
            trackTransitionDirection = trackTransitionDirection,
            motionPolicy = motionPolicy,
            isShortScreen = isShortScreen,
            isUltraCompact = isUltraCompact,
            isExtremeText = isExtremeText,
            onOpenChain = onOpenChain,
            onCyclePlaybackOrder = onCyclePlaybackOrder,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onSeek = onSeek,
            onOpenDetails = onOpenDetails,
            canOpenDetails = canOpenDetails,
            focusContent = focusContent,
            onToggleFocusContent = onToggleFocusContent,
            modifier = Modifier
                .align(Alignment.BottomCenter),
        )
    }
}

/** The only changing part of Now: the shell and transport stay spatially stable. */
@Composable
private fun NowFocusStage(
    focusContent: NowFocusContent,
    snapshot: PlaybackSnapshot,
    trackPresentation: NowTrackPresentation,
    trackTransitionDirection: TrackTransitionDirection,
    artworkSize: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    isExtremeText: Boolean,
    motionPolicy: VesqenMotionPolicy,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .testTag("vesqen.now.focus-content"),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = focusContent,
            transitionSpec = { focusContentTransition(motionPolicy) },
            contentAlignment = Alignment.Center,
            label = "vesqen.now.focus-content-transition",
        ) { content ->
            when (content) {
                NowFocusContent.ARTWORK -> AnimatedContent(
                    targetState = trackPresentation,
                    transitionSpec = {
                        trackPresentationTransition(trackTransitionDirection, motionPolicy)
                    },
                    contentAlignment = Alignment.Center,
                    label = "vesqen.now.artwork-transition",
                ) { presentation ->
                    PlayerArtworkStage(
                        artworkTrack = presentation.artworkTrack,
                        artworkSize = artworkSize,
                        compact = compact,
                    )
                }

                NowFocusContent.SESSION -> NowSessionStage(
                    snapshot = snapshot,
                    compact = compact,
                    showProgress = !isExtremeText,
                )
            }
        }
    }
}

private fun androidx.compose.animation.AnimatedContentTransitionScope<NowFocusContent>.focusContentTransition(
    motionPolicy: VesqenMotionPolicy,
) = if (motionPolicy.reduceMotion) {
    fadeIn(animationSpec = tween(motionPolicy.stateChangeMillis)) togetherWith
        fadeOut(animationSpec = tween(motionPolicy.stateChangeMillis))
} else {
    (fadeIn(
        animationSpec = tween(motionPolicy.stateChangeMillis, easing = TrackTransitionEasing),
    ) + scaleIn(
        initialScale = .985f,
        animationSpec = tween(motionPolicy.stateChangeMillis, easing = TrackTransitionEasing),
    )) togetherWith
        (fadeOut(
            animationSpec = tween(motionPolicy.stateChangeMillis * 3 / 4, easing = TrackTransitionEasing),
        ) + scaleOut(
            targetScale = .985f,
            animationSpec = tween(motionPolicy.stateChangeMillis, easing = TrackTransitionEasing),
        ))
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
        color = FocusedPlayerMaterial.ArtworkFrame,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        AlbumArtwork(
            track = artworkTrack,
            modifier = Modifier
                .fillMaxSize()
                .padding(framePadding)
                .testTag("vesqen.now.artwork"),
            emphasized = true,
            fallbackContainerColor = FocusedPlayerMaterial.Raised,
        )
    }
}

@Composable
private fun NowTransportDock(
    snapshot: PlaybackSnapshot,
    trackPresentation: NowTrackPresentation,
    trackTransitionDirection: TrackTransitionDirection,
    motionPolicy: VesqenMotionPolicy,
    isShortScreen: Boolean,
    isUltraCompact: Boolean,
    isExtremeText: Boolean,
    onOpenChain: () -> Unit,
    onCyclePlaybackOrder: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenDetails: () -> Unit,
    canOpenDetails: Boolean,
    focusContent: NowFocusContent,
    onToggleFocusContent: () -> Unit,
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
            .shadow(
                elevation = 20.dp,
                shape = FocusedPlayerDockShape,
                clip = false,
                ambientColor = FocusedPlayerMaterial.AmbientLiftShadow,
                spotColor = FocusedPlayerMaterial.SpotLiftShadow,
            )
            .testTag("vesqen.now.transport-dock"),
        shape = FocusedPlayerDockShape,
        color = FocusedPlayerMaterial.Dock,
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
            AnimatedContent(
                targetState = trackPresentation,
                transitionSpec = {
                    trackPresentationTransition(trackTransitionDirection, motionPolicy)
                },
                label = "vesqen.now.identity-transition",
            ) { presentation ->
                NowTrackIdentity(
                    presentation = presentation,
                    isControllerReady = snapshot.isControllerReady,
                    showArtist = !isExtremeText,
                    showAlbum = !isUltraCompact && !isExtremeText,
                    compact = isUltraCompact,
                )
            }
            if (!isExtremeText) {
                OutputStatusChip(
                    declaration = snapshot.declaration,
                    onClick = onOpenChain,
                    modifier = Modifier.testTag("vesqen.now.open-chain"),
                    containerColor = FocusedPlayerMaterial.Raised,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
                focusContent = focusContent,
                onToggleFocusContent = onToggleFocusContent,
                onOpenChain = onOpenChain,
                onCyclePlaybackOrder = onCyclePlaybackOrder,
                onOpenDetails = onOpenDetails,
                canOpenDetails = canOpenDetails,
                isExtremeText = isExtremeText,
                motionPolicy = motionPolicy,
            )
        }
    }
}

@Composable
private fun NowSessionStage(
    snapshot: PlaybackSnapshot,
    compact: Boolean,
    showProgress: Boolean,
) {
    val queueLabel = snapshot.queuePosition?.let { position ->
        stringResource(R.string.queue_position, position, snapshot.queueSize)
    } ?: stringResource(R.string.unavailable)
    val remaining = (snapshot.durationMs - snapshot.positionMs).coerceAtLeast(0)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) VesqenSpacing.md else VesqenSpacing.lg)
            .widthIn(max = 360.dp)
            .testTag("vesqen.now.info.session"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.surface),
        color = FocusedPlayerMaterial.Raised,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(if (compact) VesqenSpacing.md else VesqenSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(
                if (compact) VesqenSpacing.xxs else VesqenSpacing.sm,
            ),
        ) {
            if (!compact) {
                Text(
                    text = stringResource(R.string.playback_session),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            NowInfoLine(
                label = stringResource(R.string.playback_state),
                value = stringResource(if (snapshot.isPlaying) R.string.playing else R.string.paused),
                compact = compact,
            )
            if (showProgress) {
                NowInfoLine(
                    label = stringResource(R.string.playback_progress),
                    value = stringResource(
                        R.string.playback_position,
                        formatDuration(snapshot.positionMs),
                        formatDuration(snapshot.durationMs),
                    ),
                    compact = compact,
                )
            }
            if (!compact) {
                NowInfoLine(
                    label = stringResource(R.string.remaining_time),
                    value = formatDuration(remaining),
                    compact = false,
                )
            }
            NowInfoLine(
                label = stringResource(R.string.queue),
                value = queueLabel,
                compact = compact,
            )
        }
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
    focusContent: NowFocusContent,
    onToggleFocusContent: () -> Unit,
    onOpenChain: () -> Unit,
    onCyclePlaybackOrder: () -> Unit,
    onOpenDetails: () -> Unit,
    canOpenDetails: Boolean,
    isExtremeText: Boolean,
    motionPolicy: VesqenMotionPolicy,
) {
    val controlsEnabled = snapshot.isControllerReady
    val playbackOrderMode = snapshot.playbackOrderMode
    val playbackOrderState = stringResource(
        when (playbackOrderMode) {
            PlaybackOrderMode.SEQUENTIAL -> R.string.playback_order_sequential
            PlaybackOrderMode.SHUFFLE -> R.string.playback_order_shuffle
            PlaybackOrderMode.REPEAT_ALL -> R.string.playback_order_repeat_all
            PlaybackOrderMode.REPEAT_ONE -> R.string.playback_order_repeat_one
            PlaybackOrderMode.SHUFFLE_REPEAT_ALL -> R.string.playback_order_shuffle_repeat_all
            PlaybackOrderMode.SHUFFLE_REPEAT_ONE -> R.string.playback_order_shuffle_repeat_one
        },
    )
    val focusState = stringResource(
        if (focusContent == NowFocusContent.SESSION) {
            R.string.playback_session
        } else {
            R.string.album_artwork
        },
    )
    val focusAction = stringResource(
        if (focusContent == NowFocusContent.SESSION) {
            R.string.show_album_artwork
        } else {
            R.string.show_playback_session
        },
    )
    val inactiveModeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val disabledModeColor = inactiveModeColor.copy(alpha = .38f)
    val playbackOrderTint by animateColorAsState(
        targetValue = if (playbackOrderMode == PlaybackOrderMode.SEQUENTIAL) {
            inactiveModeColor
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(motionPolicy.modeChangeMillis, easing = TrackTransitionEasing),
        label = "vesqen.playback-order.tint",
    )
    val playbackOrderIconScale by animateFloatAsState(
        targetValue = if (playbackOrderMode == PlaybackOrderMode.SEQUENTIAL) .92f else 1f,
        animationSpec = tween(motionPolicy.modeChangeMillis, easing = TrackTransitionEasing),
        label = "vesqen.playback-order.scale",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        val useIconOnlyFooter = isExtremeText || maxWidth < 300.dp
        if (useIconOnlyFooter) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NowPlaybackOrderButton(
                    onClick = onCyclePlaybackOrder,
                    enabled = controlsEnabled,
                    mode = playbackOrderMode,
                    state = playbackOrderState,
                    tint = playbackOrderTint,
                    disabledTint = disabledModeColor,
                    iconScale = playbackOrderIconScale,
                    motionPolicy = motionPolicy,
                    modifier = Modifier.size(48.dp),
                )
                NowFocusToggleButton(
                    onClick = onToggleFocusContent,
                    action = focusAction,
                    state = focusState,
                    iconOnly = true,
                    motionPolicy = motionPolicy,
                    modifier = Modifier.size(48.dp),
                )
                if (isExtremeText) {
                    NowCompactChainButton(
                        onClick = onOpenChain,
                        modifier = Modifier.size(48.dp),
                    )
                }
                NowInfoButton(
                    onClick = onOpenDetails,
                    enabled = canOpenDetails,
                    modifier = Modifier.size(48.dp),
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                NowPlaybackOrderButton(
                    onClick = onCyclePlaybackOrder,
                    enabled = controlsEnabled,
                    mode = playbackOrderMode,
                    state = playbackOrderState,
                    tint = playbackOrderTint,
                    disabledTint = disabledModeColor,
                    iconScale = playbackOrderIconScale,
                    motionPolicy = motionPolicy,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(48.dp),
                )
                NowFocusToggleButton(
                    onClick = onToggleFocusContent,
                    action = focusAction,
                    state = focusState,
                    iconOnly = false,
                    motionPolicy = motionPolicy,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(96.dp)
                        .height(48.dp),
                )
                NowInfoButton(
                    onClick = onOpenDetails,
                    enabled = canOpenDetails,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(48.dp),
                )
            }
        }
    }
}

@Composable
private fun NowPlaybackOrderButton(
    onClick: () -> Unit,
    enabled: Boolean,
    mode: PlaybackOrderMode,
    state: String,
    tint: androidx.compose.ui.graphics.Color,
    disabledTint: androidx.compose.ui.graphics.Color,
    iconScale: Float,
    motionPolicy: VesqenMotionPolicy,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.playback_order)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .testTag("vesqen.now.playback-order")
            .semantics {
                contentDescription = description
                stateDescription = state
            },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            disabledContentColor = disabledTint,
        ),
    ) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                if (motionPolicy.reduceMotion) {
                    fadeIn(animationSpec = tween(motionPolicy.modeChangeMillis)) togetherWith
                        fadeOut(animationSpec = tween(motionPolicy.modeChangeMillis))
                } else {
                    (fadeIn(
                        animationSpec = tween(
                            motionPolicy.modeChangeMillis,
                            easing = TrackTransitionEasing,
                        ),
                    ) + scaleIn(
                        initialScale = .76f,
                        animationSpec = tween(
                            motionPolicy.modeChangeMillis,
                            easing = TrackTransitionEasing,
                        ),
                    )) togetherWith
                        (fadeOut(
                            animationSpec = tween(
                                motionPolicy.modeChangeMillis * 3 / 4,
                                easing = TrackTransitionEasing,
                            ),
                        ) + scaleOut(
                            targetScale = .76f,
                            animationSpec = tween(
                                motionPolicy.modeChangeMillis,
                                easing = TrackTransitionEasing,
                            ),
                        ))
                }
            },
            label = "vesqen.playback-order.mode",
        ) { currentMode ->
            NowPlaybackOrderIcon(mode = currentMode, iconScale = iconScale)
        }
    }
}

@Composable
private fun NowPlaybackOrderIcon(mode: PlaybackOrderMode, iconScale: Float) {
    val iconModifier = Modifier.graphicsLayer {
        scaleX = iconScale
        scaleY = iconScale
    }
    when (mode) {
        PlaybackOrderMode.SHUFFLE_REPEAT_ALL,
        PlaybackOrderMode.SHUFFLE_REPEAT_ONE -> Box(
            modifier = iconModifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            Icon(
                imageVector = if (mode == PlaybackOrderMode.SHUFFLE_REPEAT_ONE) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Filled.Repeat
                },
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp),
            )
        }

        else -> Icon(
            imageVector = when (mode) {
                PlaybackOrderMode.SEQUENTIAL -> Icons.Filled.FormatListNumbered
                PlaybackOrderMode.SHUFFLE -> Icons.Filled.Shuffle
                PlaybackOrderMode.REPEAT_ALL -> Icons.Filled.Repeat
                PlaybackOrderMode.REPEAT_ONE -> Icons.Filled.RepeatOne
                PlaybackOrderMode.SHUFFLE_REPEAT_ALL,
                PlaybackOrderMode.SHUFFLE_REPEAT_ONE -> error("Compound modes are rendered above")
            },
            contentDescription = null,
            modifier = iconModifier,
        )
    }
}

@Composable
private fun NowFocusToggleButton(
    onClick: () -> Unit,
    action: String,
    state: String,
    iconOnly: Boolean,
    motionPolicy: VesqenMotionPolicy,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .testTag("vesqen.now.session-toggle")
            .semantics {
                contentDescription = action
                stateDescription = state
            },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        if (iconOnly) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
            )
        } else {
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn(animationSpec = tween(motionPolicy.modeChangeMillis)) togetherWith
                        fadeOut(animationSpec = tween(motionPolicy.modeChangeMillis * 3 / 4))
                },
                label = "vesqen.now.session-toggle-label",
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NowCompactChainButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.testTag("vesqen.now.compact-chain"),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = Icons.Filled.AccountTree,
            contentDescription = stringResource(R.string.open_playback_chain),
        )
    }
}

@Composable
private fun NowInfoButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.testTag("vesqen.now.info"),
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
    presentation: NowTrackPresentation,
    isControllerReady: Boolean,
    showArtist: Boolean,
    showAlbum: Boolean,
    compact: Boolean,
) {
    val album = presentation.album.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier.padding(horizontal = VesqenSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
    ) {
        Text(
            text = presentation.title.ifBlank { stringResource(R.string.unknown_title) },
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
                text = presentation.artist.ifBlank { stringResource(R.string.unknown_artist) },
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
        if (!isControllerReady) {
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
