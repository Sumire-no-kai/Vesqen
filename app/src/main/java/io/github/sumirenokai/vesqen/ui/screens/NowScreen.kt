package io.github.sumirenokai.vesqen.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackOrderMode
import io.github.sumirenokai.vesqen.playback.PlaybackProblem
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.telemetry.PlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricSelection
import io.github.sumirenokai.vesqen.telemetry.TelemetryObservation
import io.github.sumirenokai.vesqen.ui.chain.formatTelemetryReading
import io.github.sumirenokai.vesqen.ui.chain.telemetryConfidenceLabel
import io.github.sumirenokai.vesqen.ui.components.AlbumArtwork
import io.github.sumirenokai.vesqen.ui.components.OutputStatusChip
import io.github.sumirenokai.vesqen.ui.components.PlaybackControls
import io.github.sumirenokai.vesqen.ui.components.QueueSheet
import io.github.sumirenokai.vesqen.ui.components.TrackDetailsSheet
import io.github.sumirenokai.vesqen.ui.components.outputDeclarationLabel
import io.github.sumirenokai.vesqen.ui.components.rememberAlbumBackground
import io.github.sumirenokai.vesqen.ui.formatDuration
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import io.github.sumirenokai.vesqen.ui.theme.VesqenDataStyle
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import java.util.Locale
import kotlinx.coroutines.delay

private val PaperEasing = CubicBezierEasing(.22f, 1f, .36f, 1f)
private const val COVER_TARGET_DP = 280

/** Liner-note rows reuse the Chain core metrics, observed only while the notes are open. */
private val NotesMetricIds = setOf(
    TelemetryMetricCatalog.SOURCE_CODEC_LABEL,
    TelemetryMetricCatalog.SOURCE_BIT_DEPTH,
    TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
    TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_ENCODING,
    TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE,
    TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME,
    TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT,
)

@Immutable
private data class NowTrackPresentation(
    val trackId: Long?,
    val title: String,
    val artist: String,
    val album: String,
    val artworkTrack: AudioTrack?,
    val animationIdentity: NowTrackAnimationIdentity,
)

/**
 * Track-change motion follows listening/artwork identity, not mutable catalog annotations.
 * Favorites and listening history live on [AudioTrack], but neither changes what the listener is
 * hearing or the bitmap being shown, so they must not restart the full-player transitions.
 */
@Immutable
internal data class NowTrackAnimationIdentity(
    val trackId: Long?,
    val artwork: NowArtworkIdentity?,
)

@Immutable
internal data class NowArtworkIdentity(
    val contentUri: String,
    val albumArtworkUri: String?,
    val dateModifiedSeconds: Long,
    val artworkRevision: Long,
)

internal fun nowTrackAnimationIdentity(
    trackId: Long?,
    artworkTrack: AudioTrack?,
): NowTrackAnimationIdentity = NowTrackAnimationIdentity(
    trackId = trackId,
    artwork = artworkTrack?.let { track ->
        NowArtworkIdentity(
            contentUri = track.contentUri,
            albumArtworkUri = track.albumArtworkUri,
            dateModifiedSeconds = track.dateModifiedSeconds,
            artworkRevision = track.artworkRevision,
        )
    },
)

/**
 * Portrait sizing for Now. The page never scrolls (PRD F3): the cover takes what is left after
 * identity, progress, transport and the liner-notes header, up to 280 dp, and shrinks to at most
 * 164 dp while the notes are open (B spec §4.2). A cover under 64 dp is dropped rather than
 * squeezed. Compact mode is for short windows and very large text.
 */
internal data class NowPortraitLayout(
    val artwork: Dp,
    val notesArtwork: Dp,
    val compact: Boolean,
)

internal fun nowPortraitLayout(height: Dp, fontScale: Float): NowPortraitLayout {
    val roomy = 236f + 78f * fontScale + maxOf(56f, 46f * fontScale)
    val compact = height.value < roomy + 64f
    val fixed = if (compact) 164f + 43f * fontScale + maxOf(48f, 40f * fontScale) else roomy
    val available = height.value - fixed
    // Budget the three evidence rows and the claim chip; the rest of the open notes scrolls.
    val notesContent = 3 * maxOf(42f, 38f * fontScale) + 60f * fontScale
    val artwork = if (available >= 64f) available.coerceAtMost(280f) else 0f
    val notesArtwork = (available - notesContent).let { room -> if (room >= 96f) room.coerceAtMost(164f) else 0f }
    return NowPortraitLayout(artwork.dp, notesArtwork.dp, compact)
}

/** "FLAC 24/96" style file summary from catalog metadata; null when the file reports nothing. */
internal fun nowFormatSummary(track: AudioTrack?): String? {
    val codec = track?.codec?.takeIf(String::isNotBlank) ?: return null
    val depth = track.bitDepth?.takeIf { it > 0 }
    val rate = track.sampleRateHz?.takeIf { it > 0 }?.let { hz ->
        if (hz % 1_000 == 0) "${hz / 1_000}" else String.format(Locale.ROOT, "%.1f", hz / 1_000.0)
    }
    return when {
        depth != null && rate != null -> "$codec $depth/$rate"
        rate != null -> "$codec $rate kHz"
        else -> codec
    }
}

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
    onPlayQueueIndex: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onRetryPlayback: () -> Unit,
    onToggleOrientation: () -> Unit,
    showOrientationToggle: Boolean,
    isLandscape: Boolean,
    motionPolicy: VesqenMotionPolicy,
    modifier: Modifier = Modifier,
    playbackTelemetry: PlaybackTelemetry? = null,
    onToggleFavorite: (Long, Boolean) -> Unit = { _, _ -> },
    onSetUsbOutputMode: (UsbOutputMode) -> Unit = {},
    onExplainStrictUsbUnavailable: () -> Unit = {},
) {
    if (!snapshot.hasActiveTrack) {
        NowEmbeddedEmptyState(modifier = modifier)
        return
    }

    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showOutputMode by rememberSaveable { mutableStateOf(false) }
    var notesOpen by rememberSaveable { mutableStateOf(false) }
    val trackPresentation = NowTrackPresentation(
        trackId = snapshot.trackId,
        title = snapshot.title,
        artist = snapshot.artist,
        album = snapshot.album,
        artworkTrack = artworkTrack,
        animationIdentity = nowTrackAnimationIdentity(snapshot.trackId, artworkTrack),
    )
    val playbackOrderMode = snapshot.playbackOrderMode
    val playbackOrderFeedbackText = stringResource(
        R.string.playback_order_changed,
        playbackOrderStateLabel(playbackOrderMode),
    )
    var requestedPlaybackOrderMode by remember { mutableStateOf<PlaybackOrderMode?>(null) }
    var playbackOrderFeedback by remember { mutableStateOf<String?>(null) }

    // Controller updates are asynchronous. Announce the applied state only after Media3 has
    // returned a different mode, rather than predicting that a request will succeed.
    LaunchedEffect(playbackOrderMode, requestedPlaybackOrderMode) {
        val requestedMode = requestedPlaybackOrderMode
        if (requestedMode != null && requestedMode != playbackOrderMode) {
            playbackOrderFeedback = playbackOrderFeedbackText
            requestedPlaybackOrderMode = null
        }
    }
    LaunchedEffect(playbackOrderFeedback) {
        val feedback = playbackOrderFeedback ?: return@LaunchedEffect
        delay(1_500)
        if (playbackOrderFeedback == feedback) playbackOrderFeedback = null
    }
    val requestPlaybackOrder = {
        requestedPlaybackOrderMode = playbackOrderMode
        onCyclePlaybackOrder()
    }
    LaunchedEffect(currentTrack) {
        if (currentTrack == null) showDetails = false
    }
    BackHandler(enabled = showDetails || notesOpen) {
        if (showDetails) showDetails = false else notesOpen = false
    }

    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val background = rememberAlbumBackground(artworkTrack, dark, motionPolicy)
    NowSystemBars(immersive = isLandscape, lightBars = !dark)

    val actions = NowActions(
        onBack = onBackToLibrary,
        onOpenQueue = { showQueue = true },
        onOpenDetails = { if (currentTrack != null) showDetails = true },
        canOpenDetails = currentTrack != null,
        onOpenOutputMode = { showOutputMode = true },
        onOpenChain = onOpenChain,
        onToggleOrientation = onToggleOrientation,
        showOrientationToggle = showOrientationToggle,
        onToggleNotes = { notesOpen = !notesOpen },
        onCyclePlaybackOrder = requestPlaybackOrder,
        onPrevious = onPrevious,
        onPlayPause = onPlayPause,
        onNext = onNext,
        onSeek = onSeek,
        onToggleFavorite = onToggleFavorite,
    )

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("vesqen.now.focus-surface"),
        color = background,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (isLandscape && maxWidth > maxHeight) {
                NowLandscapePage(
                    snapshot = snapshot,
                    currentTrack = currentTrack,
                    presentation = trackPresentation,
                    notesOpen = notesOpen,
                    telemetry = playbackTelemetry,
                    motionPolicy = motionPolicy,
                    actions = actions,
                    height = maxHeight,
                )
            } else {
                NowPortraitPage(
                    snapshot = snapshot,
                    currentTrack = currentTrack,
                    presentation = trackPresentation,
                    notesOpen = notesOpen,
                    telemetry = playbackTelemetry,
                    motionPolicy = motionPolicy,
                    actions = actions,
                )
            }
            PlaybackOrderFeedback(
                text = playbackOrderFeedback,
                motionPolicy = motionPolicy,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp),
            )
            snapshot.problem?.let { problem ->
                PlaybackProblemBanner(
                    problem = problem,
                    onRetry = onRetryPlayback,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 104.dp),
                )
            }
        }
    }

    if (showOutputMode) {
        OutputModeDialog(
            snapshot = snapshot,
            onSetUsbOutputMode = onSetUsbOutputMode,
            onExplainStrictUsbUnavailable = onExplainStrictUsbUnavailable,
            onDismiss = { showOutputMode = false },
        )
    }
    if (showDetails && currentTrack != null) {
        TrackDetailsSheet(
            track = currentTrack,
            onToggleFavorite = { onToggleFavorite(currentTrack.id, !currentTrack.isFavorite) },
            onDismiss = { showDetails = false },
            onPlay = {
                onPlayTrack(currentTrack)
                showDetails = false
            },
        )
    }
    if (showQueue) {
        QueueSheet(
            snapshot = snapshot,
            onDismiss = { showQueue = false },
            onPlayItem = onPlayQueueIndex,
            onRemoveItem = onRemoveQueueItem,
            onMoveItem = onMoveQueueItem,
            onClearQueue = onClearQueue,
        )
    }
}

private class NowActions(
    val onBack: () -> Unit,
    val onOpenQueue: () -> Unit,
    val onOpenDetails: () -> Unit,
    val canOpenDetails: Boolean,
    val onOpenOutputMode: () -> Unit,
    val onOpenChain: () -> Unit,
    val onToggleOrientation: () -> Unit,
    val showOrientationToggle: Boolean,
    val onToggleNotes: () -> Unit,
    val onCyclePlaybackOrder: () -> Unit,
    val onPrevious: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onToggleFavorite: (Long, Boolean) -> Unit,
)

@Composable
private fun NowPortraitPage(
    snapshot: PlaybackSnapshot,
    currentTrack: AudioTrack?,
    presentation: NowTrackPresentation,
    notesOpen: Boolean,
    telemetry: PlaybackTelemetry?,
    motionPolicy: VesqenMotionPolicy,
    actions: NowActions,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("vesqen.now.player-page"),
    ) {
        NowTopBar(snapshot = snapshot, actions = actions, isLandscape = false)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .padding(horizontal = VesqenSpacing.lg),
        ) {
            val layout = nowPortraitLayout(maxHeight, LocalDensity.current.fontScale)
            val artworkSize by animateDpAsState(
                targetValue = if (notesOpen) layout.notesArtwork else layout.artwork,
                animationSpec = tween(motionPolicy.notesExpandMillis, easing = PaperEasing),
                label = "vesqen.now.cover-size",
            )
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(VesqenSpacing.xs))
                if (artworkSize > 0.dp) {
                    NowCover(
                        presentation = presentation,
                        size = artworkSize,
                        isPlaying = snapshot.isPlaying,
                        motionPolicy = motionPolicy,
                    )
                }
                NowTrackIdentity(
                    presentation = presentation,
                    isControllerReady = snapshot.isControllerReady,
                    compact = layout.compact,
                    motionPolicy = motionPolicy,
                    modifier = Modifier.padding(top = if (layout.compact) VesqenSpacing.sm else 22.dp),
                )
                PlaybackProgress(
                    snapshot = snapshot,
                    onSeek = actions.onSeek,
                    modifier = Modifier.padding(top = VesqenSpacing.sm),
                )
                NowTransport(
                    snapshot = snapshot,
                    currentTrack = currentTrack,
                    actions = actions,
                    motionPolicy = motionPolicy,
                    modifier = Modifier.padding(top = VesqenSpacing.xs),
                )
                NowLinerNotes(
                    snapshot = snapshot,
                    currentTrack = currentTrack,
                    open = notesOpen,
                    telemetry = telemetry,
                    motionPolicy = motionPolicy,
                    actions = actions,
                    modifier = Modifier.padding(top = if (layout.compact) VesqenSpacing.xs else VesqenSpacing.md),
                )
            }
        }
    }
}

/** Landscape keeps the cover beside the controls; open notes take the cover's place. */
@Composable
private fun NowLandscapePage(
    snapshot: PlaybackSnapshot,
    currentTrack: AudioTrack?,
    presentation: NowTrackPresentation,
    notesOpen: Boolean,
    telemetry: PlaybackTelemetry?,
    motionPolicy: VesqenMotionPolicy,
    actions: NowActions,
    height: Dp,
) {
    val compressed = LocalDensity.current.fontScale >= 1.5f || height < 320.dp
    Row(
        modifier = Modifier
            .fillMaxSize()
            .testTag("vesqen.now.landscape-player"),
    ) {
      Row(Modifier.fillMaxSize().testTag("vesqen.now.player-page")) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(.43f)
                .fillMaxHeight()
                .padding(VesqenSpacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            if (notesOpen) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    NowLinerNotesBody(snapshot, currentTrack, telemetry, actions)
                }
            } else {
                NowCover(
                    presentation = presentation,
                    size = minOf(maxWidth, maxHeight, COVER_TARGET_DP.dp),
                    isPlaying = snapshot.isPlaying,
                    motionPolicy = motionPolicy,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(.57f)
                .fillMaxHeight()
                .padding(end = VesqenSpacing.lg, bottom = VesqenSpacing.xs)
                .testTag("vesqen.now.landscape-controls"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NowTopBar(snapshot = snapshot, actions = actions, isLandscape = true)
            NowTrackIdentity(
                presentation = presentation,
                isControllerReady = snapshot.isControllerReady,
                compact = true,
                motionPolicy = motionPolicy,
                showArtist = !compressed,
            )
            Spacer(Modifier.weight(1f))
            PlaybackProgress(snapshot = snapshot, onSeek = actions.onSeek)
            NowTransport(
                snapshot = snapshot,
                currentTrack = currentTrack,
                actions = actions,
                motionPolicy = motionPolicy,
                modifier = Modifier.widthIn(max = 400.dp),
            )
            NowLinerNotesHeader(
                currentTrack = currentTrack,
                snapshot = snapshot,
                open = notesOpen,
                compact = true,
                motionPolicy = motionPolicy,
                onToggle = actions.onToggleNotes,
            )
        }
      }
    }
}

@Composable
private fun NowTopBar(
    snapshot: PlaybackSnapshot,
    actions: NowActions,
    isLandscape: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = if (isLandscape) 0.dp else VesqenSpacing.xs),
    ) {
        IconButton(
            onClick = actions.onBack,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp)
                .testTag("vesqen.now.back"),
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.collapse_player))
        }
        // Landscape gives the title row's height to the controls; the page header stays portrait-only.
        if (!isLandscape) Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.destination_now),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            snapshot.queuePosition?.let { position ->
                Text(
                    text = stringResource(R.string.queue_position, position, snapshot.queueSize),
                    style = VesqenDataStyle.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Row(modifier = Modifier.align(Alignment.CenterEnd)) {
            if (actions.showOrientationToggle) {
                IconButton(
                    onClick = actions.onToggleOrientation,
                    modifier = Modifier.size(48.dp).testTag("vesqen.now.orientation-toggle"),
                ) {
                    Icon(
                        Icons.Filled.ScreenRotation,
                        stringResource(if (isLandscape) R.string.switch_to_portrait else R.string.switch_to_landscape),
                    )
                }
            }
            IconButton(
                onClick = actions.onOpenQueue,
                modifier = Modifier.size(48.dp).testTag("vesqen.now.queue"),
            ) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue))
            }
        }
    }
}

@Composable
private fun NowCover(
    presentation: NowTrackPresentation,
    size: Dp,
    isPlaying: Boolean,
    motionPolicy: VesqenMotionPolicy,
) {
    val shape = RoundedCornerShape(VesqenRadii.album)
    val shadowColor = LocalVesqenColors.current.shadow
    // The cover settles when playback pauses and returns to full presence on play: a one-shot
    // state change, not a decorative loop.
    val playScale by animateFloatAsState(
        targetValue = if (motionPolicy.reduceMotion || isPlaying) 1f else .95f,
        animationSpec = tween(if (motionPolicy.reduceMotion) 0 else 220, easing = PaperEasing),
        label = "vesqen.now.artwork-play-state",
    )
    AnimatedContent(
        targetState = presentation,
        transitionSpec = {
            if (motionPolicy.reduceMotion) {
                fadeIn(tween(motionPolicy.coverChangeMillis)) togetherWith fadeOut(tween(motionPolicy.coverChangeMillis))
            } else {
                (fadeIn(tween(motionPolicy.coverChangeMillis, easing = PaperEasing)) +
                    slideInVertically(tween(motionPolicy.coverChangeMillis, easing = PaperEasing)) { it / 35 }) togetherWith
                    fadeOut(tween(motionPolicy.coverChangeMillis / 2))
            }
        },
        contentKey = NowTrackPresentation::animationIdentity,
        contentAlignment = Alignment.Center,
        label = "vesqen.now.artwork-transition",
    ) { cover ->
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                }
                .shadow(24.dp, shape, ambientColor = shadowColor.copy(alpha = .24f), spotColor = shadowColor.copy(alpha = .24f))
                .testTag("vesqen.now.artwork-stage"),
        ) {
            AlbumArtwork(
                track = cover.artworkTrack,
                targetSize = COVER_TARGET_DP.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("vesqen.now.artwork"),
                emphasized = true,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun NowTrackIdentity(
    presentation: NowTrackPresentation,
    isControllerReady: Boolean,
    compact: Boolean,
    motionPolicy: VesqenMotionPolicy,
    modifier: Modifier = Modifier,
    showArtist: Boolean = true,
) {
    AnimatedContent(
        targetState = presentation,
        transitionSpec = {
            fadeIn(tween(motionPolicy.coverChangeMillis, easing = PaperEasing)) togetherWith
                fadeOut(tween(motionPolicy.coverChangeMillis / 2))
        },
        contentKey = NowTrackPresentation::animationIdentity,
        modifier = modifier.fillMaxWidth(),
        label = "vesqen.now.identity-transition",
    ) { identity ->
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
        ) {
            Text(
                text = identity.title.ifBlank { stringResource(R.string.unknown_title) },
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displayLarge,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee()
                    .testTag("vesqen.now.title"),
            )
            val byline = listOf(identity.artist.ifBlank { stringResource(R.string.unknown_artist) }, identity.album)
                .filter(String::isNotBlank)
                .joinToString(" — ")
            if (showArtist && !(compact && LocalDensity.current.fontScale >= 2f)) {
                Text(
                    text = byline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
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
}

@Composable
private fun NowTransport(
    snapshot: PlaybackSnapshot,
    currentTrack: AudioTrack?,
    actions: NowActions,
    motionPolicy: VesqenMotionPolicy,
    modifier: Modifier = Modifier,
) {
    PlaybackControls(
        snapshot = snapshot,
        onPrevious = actions.onPrevious,
        onPlayPause = actions.onPlayPause,
        onNext = actions.onNext,
        modifier = modifier
            .fillMaxWidth()
            .testTag("vesqen.now.transport-dock"),
        leading = {
            NowPlaybackOrderButton(
                mode = snapshot.playbackOrderMode,
                enabled = snapshot.isControllerReady,
                onClick = actions.onCyclePlaybackOrder,
                motionPolicy = motionPolicy,
            )
        },
        trailing = { NowFavoriteButton(currentTrack, actions.onToggleFavorite) },
    )
}

/** Collapsed: one summary line. Open: three evidence rows, the claim and the output actions. */
@Composable
private fun NowLinerNotes(
    snapshot: PlaybackSnapshot,
    currentTrack: AudioTrack?,
    open: Boolean,
    telemetry: PlaybackTelemetry?,
    motionPolicy: VesqenMotionPolicy,
    actions: NowActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("vesqen.now.notes")) {
        NowLinerNotesHeader(
            currentTrack = currentTrack,
            snapshot = snapshot,
            open = open,
            compact = false,
            motionPolicy = motionPolicy,
            onToggle = actions.onToggleNotes,
        )
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(motionPolicy.notesExpandMillis, easing = PaperEasing)) +
                fadeIn(tween(motionPolicy.notesContentMillis, easing = PaperEasing)) +
                slideInVertically(tween(motionPolicy.notesContentMillis, easing = PaperEasing)) { -it / 40 },
            exit = shrinkVertically(tween(motionPolicy.notesExpandMillis, easing = PaperEasing)) +
                fadeOut(tween(motionPolicy.notesContentMillis / 2)),
            label = "vesqen.now.notes-body",
        ) {
            // The body scrolls inside its own bounds so transport never leaves the screen.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                NowLinerNotesBody(snapshot, currentTrack, telemetry, actions)
            }
        }
    }
}

@Composable
private fun NowLinerNotesHeader(
    currentTrack: AudioTrack?,
    snapshot: PlaybackSnapshot,
    open: Boolean,
    compact: Boolean,
    motionPolicy: VesqenMotionPolicy,
    onToggle: () -> Unit,
) {
    val hairline = LocalVesqenColors.current.hairline
    val summary = listOfNotNull(nowFormatSummary(currentTrack), outputDeclarationLabel(snapshot.declaration))
        .joinToString(" · ")
    val state = stringResource(if (open) R.string.now_notes_expanded else R.string.now_notes_collapsed)
    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(motionPolicy.notesExpandMillis, easing = PaperEasing),
        label = "vesqen.now.notes-chevron",
    )
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) 48.dp else 56.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) { stateDescription = state }
                .testTag("vesqen.now.notes-toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.now_liner_notes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.rotate(chevron),
            )
        }
    }
}

@Composable
private fun NowLinerNotesBody(
    snapshot: PlaybackSnapshot,
    currentTrack: AudioTrack?,
    telemetry: PlaybackTelemetry?,
    actions: NowActions,
) {
    val context = LocalContext.current
    // Collected only while the notes are composed, so sampling follows this observer.
    val observed by remember(telemetry) {
        telemetry?.observe(
            TelemetryObservation(selection = TelemetryMetricSelection.Explicit(NotesMetricIds)),
        ) ?: kotlinx.coroutines.flow.emptyFlow<io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot>()
    }.collectAsState(initial = null)
    val metrics = observed?.metrics.orEmpty().associateBy(TelemetryMetric::id)
    fun value(id: TelemetryMetricId) = metrics[id]?.evidence
    val collecting = stringResource(R.string.chain_sampling_starting_short)

    fun row(ids: List<TelemetryMetricId>): Pair<String, String> {
        val evidence = ids.mapNotNull { value(it) }
        if (evidence.isEmpty()) return collecting to ""
        val text = evidence.filterNot { it is TelemetryEvidence.Unavailable }
            .joinToString(" · ") { formatTelemetryReading(context, it.reading) }
            .ifBlank { formatTelemetryReading(context, null) }
        // The weakest piece of evidence decides the row's confidence label.
        val weakest = evidence.maxBy { it.confidence.ordinal }
        return text to telemetryConfidenceLabel(context, weakest.confidence)
    }

    val source = row(
        listOf(
            TelemetryMetricCatalog.SOURCE_CODEC_LABEL,
            TelemetryMetricCatalog.SOURCE_BIT_DEPTH,
            TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
        ),
    )
    val audioTrack = row(
        listOf(TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_ENCODING, TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE),
    )
    val routeEvidence = value(TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME)
    val underruns = value(TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT)
    val route = if (routeEvidence != null && underruns != null && underruns !is TelemetryEvidence.Unavailable) {
        stringResource(
            R.string.now_notes_route_underruns,
            formatTelemetryReading(context, routeEvidence.reading),
            formatTelemetryReading(context, underruns.reading),
        ) to row(listOf(TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME, TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT)).second
    } else {
        row(listOf(TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME))
    }

    NotesRow(stringResource(R.string.chain_core_source), source, "source")
    NotesRow(stringResource(R.string.chain_core_output), audioTrack, "audio-track")
    NotesRow(stringResource(R.string.chain_core_route), route, "route")
    HorizontalDivider(color = LocalVesqenColors.current.hairline)
    Column(
        modifier = Modifier.padding(vertical = VesqenSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
    ) {
        OutputStatusChip(
            declaration = snapshot.declaration,
            onClick = actions.onOpenChain,
            modifier = Modifier.testTag("vesqen.now.open-chain"),
        )
        Text(
            text = outputClaimBody(snapshot),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.xs)) {
            val outputModeDescription = stringResource(
                if (snapshot.usbOutputStatus.mode == UsbOutputMode.STRICT_BIT_PERFECT) {
                    R.string.settings_strict_usb_output
                } else R.string.settings_system_output,
            )
            TextButton(
                onClick = actions.onOpenOutputMode,
                modifier = Modifier
                    .testTag("vesqen.now.output-mode")
                    .semantics { stateDescription = outputModeDescription },
            ) { Text(stringResource(R.string.player_output_mode)) }
            TextButton(
                onClick = actions.onOpenDetails,
                enabled = actions.canOpenDetails && currentTrack != null,
                modifier = Modifier.testTag("vesqen.now.info"),
            ) { Text(stringResource(R.string.track_information)) }
        }
    }
}

@Composable
private fun NotesRow(step: String, valueAndConfidence: Pair<String, String>, tag: String) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = LocalVesqenColors.current.hairline.copy(alpha = .1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .padding(vertical = VesqenSpacing.xxs)
                .semantics(mergeDescendants = true) {}
                .testTag("vesqen.now.notes.$tag"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = .08.em),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = valueAndConfidence.first, style = VesqenDataStyle.copy(fontSize = 14.sp))
            }
            if (valueAndConfidence.second.isNotEmpty()) {
                Text(
                    text = valueAndConfidence.second,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackProgress(
    snapshot: PlaybackSnapshot,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot.durationMs <= 0) return

    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(snapshot.trackId, snapshot.durationMs, snapshot.positionMs) {
        if (!isSeeking) seekPosition = snapshot.positionMs.coerceIn(0, snapshot.durationMs).toFloat()
    }
    val positionLabel = stringResource(
        R.string.playback_position,
        formatDuration(seekPosition.toLong()),
        formatDuration(snapshot.durationMs),
    )
    val progressContentDescription = stringResource(R.string.playback_progress)
    val enabled = snapshot.isControllerReady
    val played = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val rail = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("vesqen.now.progress")
                .semantics(mergeDescendants = true) {
                    contentDescription = progressContentDescription
                    stateDescription = positionLabel
                },
        ) {
            Slider(
                modifier = Modifier.fillMaxSize(),
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
                enabled = enabled,
                interactionSource = interactionSource,
                thumb = {
                    // Match the touch container so the 10 dp knob stays centred on the 2 dp line.
                    Box(Modifier.size(width = 10.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(10.dp).background(played, CircleShape))
                    }
                },
                track = { sliderState ->
                    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                        val fraction = ((sliderState.value - sliderState.valueRange.start) /
                            (sliderState.valueRange.endInclusive - sliderState.valueRange.start)).coerceIn(0f, 1f)
                        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                        val start = androidx.compose.ui.geometry.Offset(if (rtl) size.width else 0f, size.height / 2f)
                        val end = androidx.compose.ui.geometry.Offset(if (rtl) 0f else size.width, size.height / 2f)
                        drawLine(rail, start, end, size.height)
                        if (fraction > 0f) drawLine(played, start, start + (end - start) * fraction, size.height)
                    }
                },
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            val timeStyle = VesqenDataStyle.copy(fontSize = 12.sp)
            Text(formatDuration(seekPosition.toLong()), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(snapshot.durationMs), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun playbackOrderStateLabel(mode: PlaybackOrderMode): String = stringResource(
    when (mode) {
        PlaybackOrderMode.SEQUENTIAL -> R.string.playback_order_sequential
        PlaybackOrderMode.SHUFFLE -> R.string.playback_order_shuffle
        PlaybackOrderMode.REPEAT_ALL -> R.string.playback_order_repeat_all
        PlaybackOrderMode.REPEAT_ONE -> R.string.playback_order_repeat_one
        PlaybackOrderMode.SHUFFLE_REPEAT_ALL -> R.string.playback_order_shuffle_repeat_all
        PlaybackOrderMode.SHUFFLE_REPEAT_ONE -> R.string.playback_order_shuffle_repeat_one
    },
)

/** One playback-order control cycles every mode (PRD F3); its state is spoken, not only drawn. */
@Composable
private fun NowPlaybackOrderButton(
    mode: PlaybackOrderMode,
    enabled: Boolean,
    onClick: () -> Unit,
    motionPolicy: VesqenMotionPolicy,
) {
    val description = stringResource(R.string.playback_order)
    val state = playbackOrderStateLabel(mode)
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    val tint by animateColorAsState(
        targetValue = if (mode == PlaybackOrderMode.SEQUENTIAL) idle else MaterialTheme.colorScheme.primary,
        animationSpec = tween(motionPolicy.modeChangeMillis, easing = PaperEasing),
        label = "vesqen.playback-order.tint",
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .testTag("vesqen.now.playback-order")
            .semantics {
                contentDescription = description
                stateDescription = state
            },
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint, disabledContentColor = idle.copy(alpha = .38f)),
    ) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                if (motionPolicy.reduceMotion) {
                    fadeIn(tween(motionPolicy.modeChangeMillis)) togetherWith fadeOut(tween(motionPolicy.modeChangeMillis))
                } else {
                    (fadeIn(tween(motionPolicy.modeChangeMillis, easing = PaperEasing)) +
                        scaleIn(tween(motionPolicy.modeChangeMillis, easing = PaperEasing), initialScale = .76f)) togetherWith
                        (fadeOut(tween(motionPolicy.modeChangeMillis * 3 / 4, easing = PaperEasing)) +
                            scaleOut(tween(motionPolicy.modeChangeMillis, easing = PaperEasing), targetScale = .76f))
                }
            },
            label = "vesqen.playback-order.mode",
        ) { current -> NowPlaybackOrderIcon(current) }
    }
}

@Composable
private fun NowPlaybackOrderIcon(mode: PlaybackOrderMode) {
    when (mode) {
        PlaybackOrderMode.SHUFFLE_REPEAT_ALL,
        PlaybackOrderMode.SHUFFLE_REPEAT_ONE -> Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Shuffle, null, Modifier.fillMaxSize())
            Icon(
                if (mode == PlaybackOrderMode.SHUFFLE_REPEAT_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                null,
                Modifier.align(Alignment.BottomEnd).size(13.dp),
            )
        }
        PlaybackOrderMode.SEQUENTIAL -> Icon(Icons.Filled.FormatListNumbered, null)
        PlaybackOrderMode.SHUFFLE -> Icon(Icons.Filled.Shuffle, null)
        PlaybackOrderMode.REPEAT_ALL -> Icon(Icons.Filled.Repeat, null)
        PlaybackOrderMode.REPEAT_ONE -> Icon(Icons.Filled.RepeatOne, null)
    }
}

@Composable
private fun NowFavoriteButton(track: AudioTrack?, onToggleFavorite: (Long, Boolean) -> Unit) {
    val favorite = track?.isFavorite == true
    IconToggleButton(
        checked = favorite,
        onCheckedChange = { checked -> track?.let { onToggleFavorite(it.id, checked) } },
        enabled = track != null,
        modifier = Modifier.size(44.dp).testTag("vesqen.now.favorite"),
    ) {
        Icon(
            if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            stringResource(if (favorite) R.string.remove_favorite else R.string.favorite),
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaybackOrderFeedback(
    text: String?,
    motionPolicy: VesqenMotionPolicy,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = if (motionPolicy.reduceMotion) {
            fadeIn(tween(motionPolicy.modeChangeMillis))
        } else {
            fadeIn(tween(motionPolicy.modeChangeMillis, easing = PaperEasing)) +
                scaleIn(tween(motionPolicy.modeChangeMillis, easing = PaperEasing), initialScale = .96f)
        },
        exit = if (motionPolicy.reduceMotion) {
            fadeOut(tween(motionPolicy.modeChangeMillis))
        } else {
            fadeOut(tween(motionPolicy.modeChangeMillis * 3 / 4, easing = PaperEasing)) +
                scaleOut(tween(motionPolicy.modeChangeMillis, easing = PaperEasing), targetScale = .96f)
        },
        label = "vesqen.playback-order.feedback",
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .testTag("vesqen.now.playback-order-feedback")
                .semantics { liveRegion = LiveRegionMode.Polite },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, LocalVesqenColors.current.hairline),
            shadowElevation = 4.dp,
        ) {
            Text(
                text = text.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xs),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaybackProblemBanner(
    problem: PlaybackProblem,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = stringResource(
        when (problem) {
            PlaybackProblem.SOURCE_UNAVAILABLE -> R.string.playback_problem_source
            PlaybackProblem.UNSUPPORTED_FORMAT -> R.string.playback_problem_format
            PlaybackProblem.DECODER_FAILURE -> R.string.playback_problem_decoder
            PlaybackProblem.UNKNOWN -> R.string.playback_problem_unknown
        },
    )
    Snackbar(
        modifier = modifier
            .padding(horizontal = VesqenSpacing.md)
            .widthIn(max = 420.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("vesqen.now.playback-problem"),
        action = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.retry_playback))
            }
        },
    ) {
        Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * #62: without the official mixer API strict output can never start, so the switch cannot turn it
 * on. A strict mode saved earlier (for example restored onto Android 13) must still turn off here.
 */
internal fun strictUsbSwitchEnabled(canSetMode: Boolean, platformUnavailable: Boolean, strictOn: Boolean): Boolean =
    canSetMode && (strictOn || !platformUnavailable)

@Composable
private fun OutputModeDialog(
    snapshot: PlaybackSnapshot,
    onSetUsbOutputMode: (UsbOutputMode) -> Unit,
    onExplainStrictUsbUnavailable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strictUsbPlatformUnavailable = snapshot.usbOutputStatus.officialMixerApiSupport?.mixerApiAvailable == false
    val strictOn = snapshot.usbOutputStatus.mode == UsbOutputMode.STRICT_BIT_PERFECT
    val switchEnabled = strictUsbSwitchEnabled(snapshot.canSetUsbOutputMode, strictUsbPlatformUnavailable, strictOn)
    val strictOutputLabel = stringResource(R.string.settings_strict_usb_output)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_output_mode)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
            ) {
                Row(
                    modifier = Modifier
                        .testTag("vesqen.now.strict-usb-row")
                        .fillMaxWidth()
                        .then(
                            if (snapshot.canSetUsbOutputMode && strictUsbPlatformUnavailable && !strictOn) {
                                Modifier.clickable(onClick = onExplainStrictUsbUnavailable, role = Role.Button)
                            } else {
                                Modifier
                            },
                        )
                        .alpha(if (switchEnabled) 1f else .56f)
                        .padding(vertical = VesqenSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(strictOutputLabel, modifier = Modifier.weight(1f).padding(end = VesqenSpacing.sm))
                    Switch(
                        checked = strictOn,
                        onCheckedChange = { enabled ->
                            onSetUsbOutputMode(if (enabled) UsbOutputMode.STRICT_BIT_PERFECT else UsbOutputMode.SYSTEM)
                            onDismiss()
                        },
                        enabled = switchEnabled,
                        modifier = Modifier
                            .testTag("vesqen.now.strict-usb-switch")
                            .semantics { contentDescription = strictOutputLabel },
                    )
                }
                if (!snapshot.canSetUsbOutputMode) {
                    Text(
                        text = stringResource(R.string.playback_controls_connecting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.player_output_mode_body))
                Text(strictUsbOutputBody(snapshot.usbOutputStatus))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_output_done)) }
        },
    )
}

/**
 * Now follows the system theme (B spec §2.4), so its bar icons follow the background's
 * lightness. Landscape hides the bars for an immersive listening surface.
 */
@Composable
private fun NowSystemBars(immersive: Boolean, lightBars: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, immersive, lightBars) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        val previousLightNavigationBars = controller?.isAppearanceLightNavigationBars
        val previousSystemBarsBehavior = controller?.systemBarsBehavior
        controller?.isAppearanceLightStatusBars = lightBars
        controller?.isAppearanceLightNavigationBars = lightBars
        if (immersive) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.let { systemBars ->
                previousLightStatusBars?.let { systemBars.isAppearanceLightStatusBars = it }
                previousLightNavigationBars?.let { systemBars.isAppearanceLightNavigationBars = it }
                previousSystemBarsBehavior?.let { systemBars.systemBarsBehavior = it }
                // Library and secondary pages use visible system bars. Insets observed during
                // a rotation animation can still report the preceding immersive player's state.
                systemBars.show(WindowInsetsCompat.Type.systemBars())
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
private fun NowEmbeddedEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("vesqen.now.empty"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = VesqenSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.now_empty_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.now_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
