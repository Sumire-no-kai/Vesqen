package io.github.sumirenokai.vesqen.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.components.MiniPlayer
import io.github.sumirenokai.vesqen.ui.navigation.VesqenDestination
import io.github.sumirenokai.vesqen.ui.navigation.VesqenNavigation
import io.github.sumirenokai.vesqen.ui.navigation.VesqenNavigationState
import io.github.sumirenokai.vesqen.ui.screens.ChainScreen
import io.github.sumirenokai.vesqen.ui.screens.LibraryScreen
import io.github.sumirenokai.vesqen.ui.screens.NowScreen
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Android boundary for real permissions, MediaStore, and Media3. */
@Composable
fun VesqenApp(viewModel: VesqenViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val musicPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.updateNotificationPermission(granted)
    }
    val musicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val notificationsGranted = notificationPermission == null ||
            ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED
        viewModel.onMusicPermissionRequestResult(granted, notificationsGranted)
        if (granted && notificationPermission != null && !notificationsGranted) {
            notificationLauncher.launch(notificationPermission)
        }
    }

    val syncPermissions = {
        viewModel.initialisePermissions(
            musicGranted = ContextCompat.checkSelfPermission(context, musicPermission) == PackageManager.PERMISSION_GRANTED,
            notificationsGranted = notificationPermission == null ||
                ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    DisposableEffect(lifecycleOwner, musicPermission, notificationPermission, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) syncPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state = viewModel.uiState
    LaunchedEffect(state.playback.isPlaying) {
        while (isActive && state.playback.isPlaying) {
            delay(500)
            viewModel.refreshPlaybackPosition()
        }
    }

    VesqenAppContent(
        state = state,
        onRequestMusicAccess = { musicLauncher.launch(musicPermission) },
        onOpenAppSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        },
        onOpenNotificationSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        },
        onRescan = viewModel::refreshLibrary,
        onTrackSelected = viewModel::play,
        onPrevious = viewModel::skipToPrevious,
        onPlayPause = viewModel::togglePlayback,
        onNext = viewModel::skipToNext,
        onSeek = viewModel::seekTo,
        onToggleShuffle = viewModel::toggleShuffle,
        onCycleRepeatMode = viewModel::cycleRepeatMode,
        onRefreshConnectedOutputs = viewModel::refreshConnectedOutputs,
    )
}

/**
 * Pure, state-driven app surface. Tests can exercise all primary states without real permissions,
 * MediaStore data, or a foreground Media3 session.
 */
@Composable
fun VesqenAppContent(
    state: VesqenUiState,
    onRequestMusicAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRescan: () -> Unit,
    onTrackSelected: (AudioTrack) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onRefreshConnectedOutputs: () -> Unit,
    modifier: Modifier = Modifier,
    motionPolicy: VesqenMotionPolicy? = null,
) {
    val appliedMotionPolicy = motionPolicy ?: rememberVesqenMotionPolicy()
    var destinationName by rememberSaveable { mutableStateOf(VesqenDestination.LIBRARY.name) }
    var returnDestinationName by rememberSaveable { mutableStateOf(VesqenDestination.LIBRARY.name) }
    val navigationState = VesqenNavigationState(
        destination = VesqenDestination.valueOf(destinationName),
        returnDestination = VesqenDestination.valueOf(returnDestinationName),
    )
    val destination = navigationState.destination
    val useNavigationRail = LocalConfiguration.current.screenWidthDp >= 600

    fun applyNavigation(updated: VesqenNavigationState) {
        destinationName = updated.destination.name
        returnDestinationName = updated.returnDestination.name
    }

    fun selectTopLevel(destination: VesqenDestination) {
        applyNavigation(navigationState.selectTopLevel(destination))
    }

    fun openChainFromNow() {
        applyNavigation(navigationState.openChainFromNow())
    }

    fun navigateBack() {
        applyNavigation(navigationState.back())
    }

    BackHandler(enabled = destination != VesqenDestination.LIBRARY) {
        navigateBack()
    }

    LaunchedEffect(destination) {
        if (destination == VesqenDestination.CHAIN && state.playback.hasActiveTrack) {
            onRefreshConnectedOutputs()
        }
    }

    if (useNavigationRail) {
        Row(modifier = modifier.fillMaxSize()) {
            VesqenNavigation(
                selectedDestination = destination,
                onDestinationSelected = ::selectTopLevel,
                useNavigationRail = true,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(96.dp),
            )
            VesqenDestinationFrame(
                state = state,
                destination = destination,
                showNavigation = false,
                motionPolicy = appliedMotionPolicy,
                onDestinationSelected = ::selectTopLevel,
                onOpenChainFromNow = ::openChainFromNow,
                onNavigateBack = ::navigateBack,
                onRequestMusicAccess = onRequestMusicAccess,
                onOpenAppSettings = onOpenAppSettings,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onRescan = onRescan,
                onTrackSelected = onTrackSelected,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onSeek = onSeek,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeatMode = onCycleRepeatMode,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        VesqenDestinationFrame(
            state = state,
            destination = destination,
            showNavigation = true,
            motionPolicy = appliedMotionPolicy,
            onDestinationSelected = ::selectTopLevel,
            onOpenChainFromNow = ::openChainFromNow,
            onNavigateBack = ::navigateBack,
            onRequestMusicAccess = onRequestMusicAccess,
            onOpenAppSettings = onOpenAppSettings,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onRescan = onRescan,
            onTrackSelected = onTrackSelected,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onSeek = onSeek,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeatMode = onCycleRepeatMode,
            modifier = modifier,
        )
    }
}

@Composable
private fun VesqenDestinationFrame(
    state: VesqenUiState,
    destination: VesqenDestination,
    showNavigation: Boolean,
    motionPolicy: VesqenMotionPolicy,
    onDestinationSelected: (VesqenDestination) -> Unit,
    onOpenChainFromNow: () -> Unit,
    onNavigateBack: () -> Unit,
    onRequestMusicAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRescan: () -> Unit,
    onTrackSelected: (AudioTrack) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showMiniPlayer = state.playback.hasActiveTrack && destination != VesqenDestination.NOW
    val showCompactNavigation = showNavigation && destination != VesqenDestination.NOW
    val currentTrack = state.playback.trackId?.takeIf {
        state.library.musicAccess == MusicAccess.GRANTED
    }?.let { id ->
        state.library.tracks.firstOrNull { it.id == id }
    }
    // The controller can reconnect before a freshly-scanned library has been delivered. Retain
    // Media3's opaque metadata in that brief state so the mini and focus player do not regress to
    // a branded placeholder merely because the UI map is still empty.
    val artworkTrack = if (state.library.musicAccess == MusicAccess.GRANTED) {
        currentTrack ?: state.playback.toArtworkTrackOrNull()
    } else {
        null
    }
    Scaffold(
        modifier = modifier,
        bottomBar = {
            Column {
                if (showMiniPlayer) {
                    MiniPlayer(
                        snapshot = state.playback,
                        currentTrack = artworkTrack,
                        onOpenNow = { onDestinationSelected(VesqenDestination.NOW) },
                        onPrevious = onPrevious,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (showCompactNavigation) {
                    VesqenNavigation(
                        selectedDestination = destination,
                        onDestinationSelected = onDestinationSelected,
                        useNavigationRail = false,
                    )
                }
            }
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = destination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            transitionSpec = {
                val duration = if (
                    targetState == VesqenDestination.NOW && initialState != VesqenDestination.NOW
                ) {
                    motionPolicy.playerExpandMillis
                } else {
                    motionPolicy.stateChangeMillis
                }
                if (motionPolicy.reduceMotion) {
                    fadeIn(animationSpec = tween(duration)) togetherWith
                        fadeOut(animationSpec = tween(duration))
                } else {
                    (fadeIn(animationSpec = tween(duration)) +
                        scaleIn(initialScale = .98f, animationSpec = tween(duration))) togetherWith
                        (fadeOut(animationSpec = tween(duration / 2)) +
                            scaleOut(targetScale = .98f, animationSpec = tween(duration / 2)))
                }
            },
            label = "vesqen-destination",
        ) { activeDestination ->
            when (activeDestination) {
                VesqenDestination.LIBRARY -> LibraryScreen(
                    state = state.library,
                    playback = state.playback,
                    onRequestMusicAccess = onRequestMusicAccess,
                    onOpenAppSettings = onOpenAppSettings,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onRescan = onRescan,
                    onTrackSelected = onTrackSelected,
                )

                VesqenDestination.NOW -> NowScreen(
                    snapshot = state.playback,
                    currentTrack = currentTrack,
                    artworkTrack = artworkTrack,
                    onBackToLibrary = onNavigateBack,
                    onOpenChain = onOpenChainFromNow,
                    onToggleShuffle = onToggleShuffle,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onCycleRepeatMode = onCycleRepeatMode,
                    onSeek = onSeek,
                    onPlayTrack = onTrackSelected,
                )

                VesqenDestination.CHAIN -> ChainScreen(
                    library = state.library,
                    snapshot = state.playback,
                    onBackToLibrary = onNavigateBack,
                )
            }
        }
    }
}

private fun PlaybackSnapshot.toArtworkTrackOrNull(): AudioTrack? {
    val sourceUri = mediaUri.takeIf(String::isNotBlank) ?: return null
    return AudioTrack(
        id = trackId ?: return null,
        contentUri = sourceUri,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        albumArtworkUri = albumArtworkUri,
        artworkRevision = artworkRevision,
    )
}
