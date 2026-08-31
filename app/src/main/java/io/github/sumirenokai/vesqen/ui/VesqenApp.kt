package io.github.sumirenokai.vesqen.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import io.github.sumirenokai.vesqen.ui.components.MiniPlayer
import io.github.sumirenokai.vesqen.ui.navigation.VesqenDestination
import io.github.sumirenokai.vesqen.ui.navigation.VesqenNavigation
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
    onRefreshConnectedOutputs: () -> Unit,
    modifier: Modifier = Modifier,
    motionPolicy: VesqenMotionPolicy? = null,
) {
    val appliedMotionPolicy = motionPolicy ?: rememberVesqenMotionPolicy()
    var destinationName by rememberSaveable { mutableStateOf(VesqenDestination.LIBRARY.name) }
    val destination = VesqenDestination.valueOf(destinationName)
    val useNavigationRail = LocalConfiguration.current.screenWidthDp >= 600

    LaunchedEffect(destination) {
        if (destination == VesqenDestination.CHAIN && state.playback.hasActiveTrack) {
            onRefreshConnectedOutputs()
        }
    }

    if (useNavigationRail) {
        Row(modifier = modifier.fillMaxSize()) {
            VesqenNavigation(
                selectedDestination = destination,
                onDestinationSelected = { destinationName = it.name },
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
                onDestinationSelected = { destinationName = it.name },
                onRequestMusicAccess = onRequestMusicAccess,
                onOpenAppSettings = onOpenAppSettings,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onRescan = onRescan,
                onTrackSelected = onTrackSelected,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onSeek = onSeek,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        VesqenDestinationFrame(
            state = state,
            destination = destination,
            showNavigation = true,
            motionPolicy = appliedMotionPolicy,
            onDestinationSelected = { destinationName = it.name },
            onRequestMusicAccess = onRequestMusicAccess,
            onOpenAppSettings = onOpenAppSettings,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onRescan = onRescan,
            onTrackSelected = onTrackSelected,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onSeek = onSeek,
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
    onRequestMusicAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRescan: () -> Unit,
    onTrackSelected: (AudioTrack) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showMiniPlayer = state.playback.hasActiveTrack && destination != VesqenDestination.NOW
    Scaffold(
        modifier = modifier,
        bottomBar = {
            Column {
                if (showMiniPlayer) {
                    MiniPlayer(
                        snapshot = state.playback,
                        onOpenNow = { onDestinationSelected(VesqenDestination.NOW) },
                        onPrevious = onPrevious,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (showNavigation) {
                    VesqenNavigation(
                        selectedDestination = destination,
                        onDestinationSelected = onDestinationSelected,
                        useNavigationRail = false,
                    )
                }
            }
        },
    ) { innerPadding ->
        val currentTrack = state.playback.trackId?.let { id ->
            state.library.tracks.firstOrNull { it.id == id }
        }
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
                    onBackToLibrary = { onDestinationSelected(VesqenDestination.LIBRARY) },
                    onOpenChain = { onDestinationSelected(VesqenDestination.CHAIN) },
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onSeek = onSeek,
                    onPlayTrack = onTrackSelected,
                )

                VesqenDestination.CHAIN -> ChainScreen(
                    library = state.library,
                    snapshot = state.playback,
                    onBackToLibrary = { onDestinationSelected(VesqenDestination.LIBRARY) },
                )
            }
        }
    }
}
