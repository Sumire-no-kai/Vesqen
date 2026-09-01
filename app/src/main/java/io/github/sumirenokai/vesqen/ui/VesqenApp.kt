package io.github.sumirenokai.vesqen.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sumirenokai.vesqen.BuildConfig
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.components.MiniPlayer
import io.github.sumirenokai.vesqen.ui.components.MiniPlayerHeight
import io.github.sumirenokai.vesqen.ui.navigation.CompactNavigationBarContentHeight
import io.github.sumirenokai.vesqen.ui.navigation.VesqenDestination
import io.github.sumirenokai.vesqen.ui.navigation.VesqenNavigation
import io.github.sumirenokai.vesqen.ui.navigation.VesqenNavigationState
import io.github.sumirenokai.vesqen.ui.navigation.isSecondaryDetail
import io.github.sumirenokai.vesqen.ui.screens.AboutScreen
import io.github.sumirenokai.vesqen.ui.screens.ChainScreen
import io.github.sumirenokai.vesqen.ui.screens.LibraryScreen
import io.github.sumirenokai.vesqen.ui.screens.NowScreen
import io.github.sumirenokai.vesqen.ui.screens.SettingsScreen
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val FocusedPlayerEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

internal enum class PlayerOrientationOverride {
    FOLLOW_SYSTEM,
    FORCE_PORTRAIT,
    FORCE_LANDSCAPE,
}

internal fun requestedPhoneOrientation(
    hasFocusedPlayer: Boolean,
    playerOverride: PlayerOrientationOverride,
): Int = if (!hasFocusedPlayer) {
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
} else {
    when (playerOverride) {
        PlayerOrientationOverride.FOLLOW_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_USER
        PlayerOrientationOverride.FORCE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        PlayerOrientationOverride.FORCE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}

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
    val musicFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        treeUri?.let(viewModel::addLibraryFolder)
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
        onAddLibraryFolder = { musicFolderLauncher.launch(null) },
        onRemoveLibraryFolder = viewModel::removeLibraryFolder,
        onPauseLibraryScan = viewModel::pauseLibraryScan,
        onResumeLibraryScan = viewModel::resumeLibraryScan,
        onTrackSelected = viewModel::play,
        onPrevious = viewModel::skipToPrevious,
        onPlayPause = viewModel::togglePlayback,
        onNext = viewModel::skipToNext,
        onSeek = viewModel::seekTo,
        onCyclePlaybackOrder = viewModel::cyclePlaybackOrderMode,
        onRefreshConnectedOutputs = viewModel::refreshConnectedOutputs,
        managePhoneOrientation = true,
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
    onCyclePlaybackOrder: () -> Unit,
    onRefreshConnectedOutputs: () -> Unit,
    onAddLibraryFolder: () -> Unit = {},
    onRemoveLibraryFolder: (String) -> Unit = {},
    onPauseLibraryScan: () -> Unit = {},
    onResumeLibraryScan: () -> Unit = {},
    modifier: Modifier = Modifier,
    motionPolicy: VesqenMotionPolicy? = null,
    managePhoneOrientation: Boolean = false,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
) {
    val appliedMotionPolicy = motionPolicy ?: rememberVesqenMotionPolicy()
    var destinationName by rememberSaveable { mutableStateOf(VesqenDestination.LIBRARY.name) }
    var returnDestinationName by rememberSaveable { mutableStateOf(VesqenDestination.LIBRARY.name) }
    val navigationState = VesqenNavigationState(
        destination = VesqenDestination.valueOf(destinationName),
        returnDestination = VesqenDestination.valueOf(returnDestinationName),
    )
    val destination = navigationState.destination
    val hasFocusedPlayer = destination == VesqenDestination.NOW && state.playback.hasActiveTrack
    val configuration = LocalConfiguration.current
    val isPhone = configuration.smallestScreenWidthDp < 600
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var playerOrientationOverrideName by rememberSaveable {
        mutableStateOf(PlayerOrientationOverride.FOLLOW_SYSTEM.name)
    }
    val playerOrientationOverride = PlayerOrientationOverride.valueOf(playerOrientationOverrideName)
    LaunchedEffect(hasFocusedPlayer) {
        if (!hasFocusedPlayer) {
            playerOrientationOverrideName = PlayerOrientationOverride.FOLLOW_SYSTEM.name
        }
    }
    PhoneOrientationPolicy(
        hasFocusedPlayer = hasFocusedPlayer,
        playerOverride = playerOrientationOverride,
        enabled = managePhoneOrientation,
    )
    // A protected Now surface owns the whole window. Keeping a light navigation rail beside it
    // would split the transparent status bar between incompatible backgrounds and make one set of
    // system icons unreadable. Back remains the deliberate route to the stable top-level shell.
    val isSecondaryDetail = destination.isSecondaryDetail
    val useNavigationRail = LocalConfiguration.current.screenWidthDp >= 600 &&
        !hasFocusedPlayer && !isSecondaryDetail

    fun applyNavigation(updated: VesqenNavigationState) {
        destinationName = updated.destination.name
        returnDestinationName = updated.returnDestination.name
    }

    fun selectTopLevel(destination: VesqenDestination) {
        applyNavigation(navigationState.selectTopLevel(destination))
    }

    fun openChain() {
        applyNavigation(navigationState.openChain())
    }

    fun openAbout() {
        applyNavigation(navigationState.openAbout())
    }

    fun togglePlayerOrientation() {
        playerOrientationOverrideName = if (isLandscape) {
            PlayerOrientationOverride.FORCE_PORTRAIT.name
        } else {
            PlayerOrientationOverride.FORCE_LANDSCAPE.name
        }
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
                onOpenChain = ::openChain,
                onOpenAbout = ::openAbout,
                onNavigateBack = ::navigateBack,
                onRequestMusicAccess = onRequestMusicAccess,
                onOpenAppSettings = onOpenAppSettings,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onRescan = onRescan,
                onAddLibraryFolder = onAddLibraryFolder,
                onRemoveLibraryFolder = onRemoveLibraryFolder,
                onPauseLibraryScan = onPauseLibraryScan,
                onResumeLibraryScan = onResumeLibraryScan,
                onTrackSelected = onTrackSelected,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onSeek = onSeek,
                onCyclePlaybackOrder = onCyclePlaybackOrder,
                onTogglePlayerOrientation = ::togglePlayerOrientation,
                showOrientationToggle = isPhone,
                isLandscape = isLandscape,
                versionName = versionName,
                versionCode = versionCode,
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
            onOpenChain = ::openChain,
            onOpenAbout = ::openAbout,
            onNavigateBack = ::navigateBack,
            onRequestMusicAccess = onRequestMusicAccess,
            onOpenAppSettings = onOpenAppSettings,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onRescan = onRescan,
            onAddLibraryFolder = onAddLibraryFolder,
            onRemoveLibraryFolder = onRemoveLibraryFolder,
            onPauseLibraryScan = onPauseLibraryScan,
            onResumeLibraryScan = onResumeLibraryScan,
            onTrackSelected = onTrackSelected,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onSeek = onSeek,
            onCyclePlaybackOrder = onCyclePlaybackOrder,
            onTogglePlayerOrientation = ::togglePlayerOrientation,
            showOrientationToggle = isPhone,
            isLandscape = isLandscape,
            versionName = versionName,
            versionCode = versionCode,
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
    onOpenChain: () -> Unit,
    onOpenAbout: () -> Unit,
    onNavigateBack: () -> Unit,
    onRequestMusicAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRescan: () -> Unit,
    onAddLibraryFolder: () -> Unit,
    onRemoveLibraryFolder: (String) -> Unit,
    onPauseLibraryScan: () -> Unit,
    onResumeLibraryScan: () -> Unit,
    onTrackSelected: (AudioTrack) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlaybackOrder: () -> Unit,
    onTogglePlayerOrientation: () -> Unit,
    showOrientationToggle: Boolean,
    isLandscape: Boolean,
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    val usesFocusedPlayerInsets = destination == VesqenDestination.NOW && state.playback.hasActiveTrack
    val showMiniPlayer = state.playback.hasActiveTrack &&
        destination != VesqenDestination.NOW && !destination.isSecondaryDetail
    val showCompactNavigation = showNavigation &&
        !usesFocusedPlayerInsets && !destination.isSecondaryDetail
    val miniPlayerContentClearance = if (showMiniPlayer) {
        MiniPlayerHeight + VesqenSpacing.xxs
    } else {
        0.dp
    }
    val miniPlayerBottomPadding = if (showCompactNavigation) {
        CompactNavigationBarContentHeight + VesqenSpacing.xxs
    } else {
        VesqenSpacing.md
    }
    val playerExpandContentMillis =
        (motionPolicy.playerExpandMillis - motionPolicy.playerHandoffDelayMillis).coerceAtLeast(1)
    val playerReturnContentMillis =
        (motionPolicy.playerCollapseMillis - motionPolicy.playerReturnRevealDelayMillis).coerceAtLeast(1)
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
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = if (usesFocusedPlayerInsets) {
                WindowInsets(0, 0, 0, 0)
            } else {
                ScaffoldDefaults.contentWindowInsets
            },
            bottomBar = {
                if (showNavigation && !destination.isSecondaryDetail) {
                    // Keep the compact navigation in composition until its exit completes. The
                    // focused player can then take over the window without the shell snapping
                    // away one frame before the player starts moving.
                    AnimatedVisibility(
                        visible = showCompactNavigation,
                        enter = if (motionPolicy.reduceMotion) {
                            fadeIn(animationSpec = tween(motionPolicy.stateChangeMillis))
                        } else {
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = playerReturnContentMillis,
                                    delayMillis = motionPolicy.playerReturnRevealDelayMillis,
                                    easing = FocusedPlayerEasing,
                                ),
                            ) + slideInVertically(
                                animationSpec = tween(
                                    durationMillis = playerReturnContentMillis,
                                    delayMillis = motionPolicy.playerReturnRevealDelayMillis,
                                    easing = FocusedPlayerEasing,
                                ),
                                initialOffsetY = { height -> height / 2 },
                            )
                        },
                        exit = if (motionPolicy.reduceMotion) {
                            fadeOut(animationSpec = tween(motionPolicy.stateChangeMillis))
                        } else {
                            fadeOut(
                                animationSpec = tween(
                                    durationMillis = motionPolicy.stateChangeMillis,
                                    easing = FocusedPlayerEasing,
                                ),
                            ) + slideOutVertically(
                                animationSpec = tween(
                                    durationMillis = motionPolicy.stateChangeMillis,
                                    easing = FocusedPlayerEasing,
                                ),
                                targetOffsetY = { height -> height / 2 },
                            )
                        },
                        label = "vesqen.compact-navigation-visibility",
                    ) {
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
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val opensFocusedPlayer = targetState == VesqenDestination.NOW &&
                        initialState != VesqenDestination.NOW
                    val closesFocusedPlayer = initialState == VesqenDestination.NOW &&
                        targetState != VesqenDestination.NOW
                    when {
                        motionPolicy.reduceMotion -> {
                            fadeIn(animationSpec = tween(motionPolicy.stateChangeMillis)) togetherWith
                                fadeOut(animationSpec = tween(motionPolicy.stateChangeMillis))
                        }

                        opensFocusedPlayer -> {
                            (fadeIn(
                                animationSpec = tween(
                                    durationMillis = playerExpandContentMillis,
                                    delayMillis = motionPolicy.playerHandoffDelayMillis,
                                    easing = FocusedPlayerEasing,
                                ),
                            ) + slideInVertically(
                                animationSpec = tween(
                                    durationMillis = playerExpandContentMillis,
                                    delayMillis = motionPolicy.playerHandoffDelayMillis,
                                    easing = FocusedPlayerEasing,
                                ),
                                initialOffsetY = { height -> height / 6 },
                            ) + scaleIn(
                                initialScale = .94f,
                                animationSpec = tween(
                                    durationMillis = playerExpandContentMillis,
                                    delayMillis = motionPolicy.playerHandoffDelayMillis,
                                    easing = FocusedPlayerEasing,
                                ),
                            )) togetherWith
                                (fadeOut(
                                    animationSpec = tween(
                                        durationMillis = motionPolicy.stateChangeMillis,
                                        easing = FocusedPlayerEasing,
                                    ),
                                ) + slideOutVertically(
                                    animationSpec = tween(
                                        durationMillis = motionPolicy.stateChangeMillis,
                                        easing = FocusedPlayerEasing,
                                    ),
                                    targetOffsetY = { height -> -height / 24 },
                                ) + scaleOut(
                                    targetScale = .99f,
                                    animationSpec = tween(
                                        durationMillis = motionPolicy.stateChangeMillis,
                                        easing = FocusedPlayerEasing,
                                    ),
                                ))
                        }

                        closesFocusedPlayer -> {
                            (fadeIn(
                                animationSpec = tween(
                                    durationMillis = playerReturnContentMillis,
                                    delayMillis = motionPolicy.playerReturnRevealDelayMillis,
                                    easing = FocusedPlayerEasing,
                                ),
                            ) + slideInVertically(
                                animationSpec = tween(
                                    durationMillis = playerReturnContentMillis,
                                    delayMillis = motionPolicy.playerReturnRevealDelayMillis,
                                    easing = FocusedPlayerEasing,
                                ),
                                initialOffsetY = { height -> -height / 28 },
                            ) + scaleIn(
                                initialScale = .99f,
                                animationSpec = tween(
                                    durationMillis = playerReturnContentMillis,
                                    delayMillis = motionPolicy.playerReturnRevealDelayMillis,
                                    easing = FocusedPlayerEasing,
                                ),
                            )) togetherWith
                                (fadeOut(
                                    animationSpec = tween(
                                        durationMillis = motionPolicy.playerCollapseMillis -
                                            motionPolicy.playerHandoffDelayMillis,
                                        easing = FocusedPlayerEasing,
                                    ),
                                ) + slideOutVertically(
                                    animationSpec = tween(
                                        durationMillis = motionPolicy.playerCollapseMillis,
                                        easing = FocusedPlayerEasing,
                                    ),
                                    targetOffsetY = { height -> height / 6 },
                                ) + scaleOut(
                                    targetScale = .94f,
                                    animationSpec = tween(
                                        durationMillis = motionPolicy.playerCollapseMillis,
                                        easing = FocusedPlayerEasing,
                                    ),
                                ))
                        }

                        else -> {
                            val duration = motionPolicy.stateChangeMillis
                            (fadeIn(animationSpec = tween(duration)) +
                                scaleIn(initialScale = .98f, animationSpec = tween(duration))) togetherWith
                                (fadeOut(animationSpec = tween(duration / 2)) +
                                    scaleOut(targetScale = .98f, animationSpec = tween(duration / 2)))
                        }
                    }
                },
                label = "vesqen-destination",
            ) { activeDestination ->
                // During destination transitions keep the outgoing focused player edge-to-edge until
                // it fades out. Applying the incoming Library padding here would flash a white inset.
                val destinationModifier = if (
                    activeDestination == VesqenDestination.NOW && state.playback.hasActiveTrack
                ) {
                    Modifier
                } else {
                    Modifier
                        .padding(innerPadding)
                        .padding(bottom = miniPlayerContentClearance)
                }
                when (activeDestination) {
                    VesqenDestination.LIBRARY -> LibraryScreen(
                        state = state.library,
                        playback = state.playback,
                        onRequestMusicAccess = onRequestMusicAccess,
                        onOpenAppSettings = onOpenAppSettings,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onRescan = onRescan,
                        onAddLibraryFolder = onAddLibraryFolder,
                        onRemoveLibraryFolder = onRemoveLibraryFolder,
                        onPauseLibraryScan = onPauseLibraryScan,
                        onResumeLibraryScan = onResumeLibraryScan,
                        onTrackSelected = onTrackSelected,
                        modifier = destinationModifier,
                    )

                    VesqenDestination.NOW -> NowScreen(
                        snapshot = state.playback,
                        currentTrack = currentTrack,
                        artworkTrack = artworkTrack,
                        onBackToLibrary = onNavigateBack,
                        onOpenChain = onOpenChain,
                        onCyclePlaybackOrder = onCyclePlaybackOrder,
                        onPrevious = onPrevious,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onSeek = onSeek,
                        onPlayTrack = onTrackSelected,
                        onToggleOrientation = onTogglePlayerOrientation,
                        showOrientationToggle = showOrientationToggle,
                        isLandscape = isLandscape,
                        motionPolicy = motionPolicy,
                        modifier = destinationModifier,
                    )

                    VesqenDestination.SETTINGS -> SettingsScreen(
                        onOpenPlaybackChain = onOpenChain,
                        onOpenAbout = onOpenAbout,
                        versionName = versionName,
                        modifier = destinationModifier,
                    )

                    VesqenDestination.CHAIN -> ChainScreen(
                        library = state.library,
                        snapshot = state.playback,
                        onBack = onNavigateBack,
                        onBrowseLibrary = { onDestinationSelected(VesqenDestination.LIBRARY) },
                        modifier = destinationModifier,
                    )

                    VesqenDestination.ABOUT -> AboutScreen(
                        versionName = versionName,
                        versionCode = versionCode,
                        onBack = onNavigateBack,
                        modifier = destinationModifier,
                    )
                }
            }
        }
        if (state.playback.hasActiveTrack && !destination.isSecondaryDetail) {
            // Mirror the full-player handoff: on close the mini-player waits until the outgoing
            // surface has meaningfully receded, avoiding the previous double-player ghost frame.
            AnimatedVisibility(
                visible = showMiniPlayer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        start = VesqenSpacing.md,
                        end = VesqenSpacing.md,
                        bottom = miniPlayerBottomPadding,
                    ),
                enter = if (motionPolicy.reduceMotion) {
                    fadeIn(animationSpec = tween(motionPolicy.stateChangeMillis))
                } else {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = playerReturnContentMillis,
                            delayMillis = motionPolicy.playerReturnRevealDelayMillis,
                            easing = FocusedPlayerEasing,
                        ),
                    ) + slideInVertically(
                        animationSpec = tween(
                            durationMillis = playerReturnContentMillis,
                            delayMillis = motionPolicy.playerReturnRevealDelayMillis,
                            easing = FocusedPlayerEasing,
                        ),
                        initialOffsetY = { height -> height / 2 },
                    ) + scaleIn(
                        initialScale = .96f,
                        animationSpec = tween(
                            durationMillis = playerReturnContentMillis,
                            delayMillis = motionPolicy.playerReturnRevealDelayMillis,
                            easing = FocusedPlayerEasing,
                        ),
                    )
                },
                exit = if (motionPolicy.reduceMotion) {
                    fadeOut(animationSpec = tween(motionPolicy.stateChangeMillis))
                } else {
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = motionPolicy.stateChangeMillis,
                            easing = FocusedPlayerEasing,
                        ),
                    ) + slideOutVertically(
                        animationSpec = tween(
                            durationMillis = motionPolicy.stateChangeMillis,
                            easing = FocusedPlayerEasing,
                        ),
                        targetOffsetY = { height -> height / 2 },
                    ) + scaleOut(
                        targetScale = .96f,
                        animationSpec = tween(
                            durationMillis = motionPolicy.stateChangeMillis,
                            easing = FocusedPlayerEasing,
                        ),
                    )
                },
                label = "vesqen.mini-player-visibility",
            ) {
                MiniPlayer(
                    snapshot = state.playback,
                    currentTrack = artworkTrack,
                    onOpenNow = { onDestinationSelected(VesqenDestination.NOW) },
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
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

@Composable
private fun PhoneOrientationPolicy(
    hasFocusedPlayer: Boolean,
    playerOverride: PlayerOrientationOverride,
    enabled: Boolean,
) {
    val configuration = LocalConfiguration.current
    val activity = LocalContext.current.findActivity()
    val isPhone = configuration.smallestScreenWidthDp < 600
    val originalOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    LaunchedEffect(activity, enabled, isPhone, hasFocusedPlayer, playerOverride) {
        if (activity != null && enabled && isPhone) {
            activity.requestedOrientation = requestedPhoneOrientation(
                hasFocusedPlayer = hasFocusedPlayer,
                playerOverride = playerOverride,
            )
        }
    }
    DisposableEffect(activity, enabled, isPhone) {
        onDispose {
            if (activity != null && enabled && isPhone) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
