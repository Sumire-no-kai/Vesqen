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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sumirenokai.vesqen.BuildConfig
import io.github.sumirenokai.vesqen.VesqenApplication
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticExportFailure
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticExportResult
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecorder
import io.github.sumirenokai.vesqen.diagnostics.exportTo
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.telemetry.PlaybackTelemetry
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardPreferencesRepository
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardPreferencesStore
import io.github.sumirenokai.vesqen.ui.chain.InMemoryChainDashboardPreferencesRepository
import io.github.sumirenokai.vesqen.ui.chain.DiagnosticExportFeedback
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
import io.github.sumirenokai.vesqen.verification.OutputVerificationImportFailure
import io.github.sumirenokai.vesqen.verification.OutputVerificationImportResult
import io.github.sumirenokai.vesqen.verification.OutputVerificationRegistryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** The high-frequency progress cursor is useful only on the focused player surface. */
internal fun shouldRefreshPlaybackPosition(
    destination: VesqenDestination,
    playback: PlaybackSnapshot,
): Boolean = destination == VesqenDestination.NOW && playback.hasActiveTrack && playback.isPlaying

/** Android boundary for real permissions, MediaStore, and Media3. */
@Composable
fun VesqenApp(viewModel: VesqenViewModel = viewModel()) {
    val context = LocalContext.current
    val application = context.applicationContext as VesqenApplication
    val chainPreferencesRepository = remember(application) {
        ChainDashboardPreferencesStore(application)
    }
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
    val diagnosticRecorder = application.diagnosticRecorderOrNull
    val diagnosticExportScope = rememberCoroutineScope()
    val verificationRegistryState by application.outputVerificationRepository.state
        .collectAsStateWithLifecycle()
    val verificationImportScope = rememberCoroutineScope()
    var verificationImportResult by remember {
        mutableStateOf<OutputVerificationImportResult?>(null)
    }
    val verificationImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source != null) {
            verificationImportScope.launch {
                verificationImportResult = withContext(Dispatchers.IO) {
                    val input = runCatching { context.contentResolver.openInputStream(source) }.getOrNull()
                    if (input == null) {
                        OutputVerificationImportResult.Failure(OutputVerificationImportFailure.IO_ERROR)
                    } else {
                        application.outputVerificationRepository.import(input)
                    }
                }
            }
        }
    }
    var diagnosticExportFeedback by remember {
        mutableStateOf(DiagnosticExportFeedback.NONE)
    }
    val diagnosticExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { destination ->
        if (destination == null) {
            diagnosticExportFeedback = DiagnosticExportFeedback.CANCELLED
        } else {
            diagnosticExportScope.launch {
                diagnosticExportFeedback = DiagnosticExportFeedback.EXPORTING
                diagnosticExportFeedback = when (
                    val result = diagnosticRecorder?.exportTo(context.contentResolver, destination)
                        ?: DiagnosticExportResult.Failure(
                            DiagnosticExportFailure.NO_STOPPED_RECORDING,
                        )
                ) {
                    is DiagnosticExportResult.Success -> DiagnosticExportFeedback.SUCCESS
                    is DiagnosticExportResult.Failure -> when (result.reason) {
                        DiagnosticExportFailure.NO_STOPPED_RECORDING ->
                            DiagnosticExportFeedback.NO_STOPPED_RECORDING
                        DiagnosticExportFailure.DESTINATION_UNAVAILABLE ->
                            DiagnosticExportFeedback.DESTINATION_UNAVAILABLE
                        DiagnosticExportFailure.WRITE_FAILED -> DiagnosticExportFeedback.WRITE_FAILED
                    }
                }
            }
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

    VesqenAppContent(
        state = state,
        playbackTelemetry = application.playbackTelemetry,
        chainPreferencesRepository = chainPreferencesRepository,
        diagnosticRecorder = diagnosticRecorder,
        diagnosticExportFeedback = diagnosticExportFeedback,
        onRequestDiagnosticExport = {
            diagnosticExportFeedback = DiagnosticExportFeedback.NONE
            diagnosticExportLauncher.launch("vesqen-diagnostic-${System.currentTimeMillis()}.json")
        },
        onClearDiagnosticExportFeedback = {
            diagnosticExportFeedback = DiagnosticExportFeedback.NONE
        },
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
        onPlayQueue = viewModel::playQueue,
        onToggleFavorite = viewModel::setFavorite,
        onPlayNext = viewModel::playNext,
        onAddToQueue = viewModel::addToQueue,
        onCreatePlaylist = viewModel::createPlaylist,
        onRenamePlaylist = viewModel::renamePlaylist,
        onDeletePlaylist = viewModel::deletePlaylist,
        onAddTrackToPlaylist = viewModel::addTrackToPlaylist,
        onRemoveTrackFromPlaylist = viewModel::removeTrackFromPlaylist,
        onMovePlaylistTrack = viewModel::movePlaylistTrack,
        onSaveTrackOrder = viewModel::saveTrackOrder,
        onPlayQueueIndex = viewModel::playQueueIndex,
        onRemoveQueueItem = viewModel::removeQueueItem,
        onMoveQueueItem = viewModel::moveQueueItem,
        onClearQueue = viewModel::clearQueue,
        onRetryPlayback = viewModel::retryPlayback,
        onPrevious = viewModel::skipToPrevious,
        onPlayPause = viewModel::togglePlayback,
        onNext = viewModel::skipToNext,
        onSeek = viewModel::seekTo,
        onRefreshPlaybackPosition = viewModel::refreshPlaybackPosition,
        onCyclePlaybackOrder = viewModel::cyclePlaybackOrderMode,
        onSetUsbOutputMode = viewModel::setUsbOutputMode,
        verificationRegistryState = verificationRegistryState,
        verificationImportResult = verificationImportResult,
        onImportVerificationRegistry = {
            verificationImportResult = null
            verificationImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
        },
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
    modifier: Modifier = Modifier,
    onPlayQueue: (List<AudioTrack>, Int) -> Unit = { tracks, index ->
        tracks.getOrNull(index)?.let(onTrackSelected)
    },
    onToggleFavorite: (Long, Boolean) -> Unit = { _, _ -> },
    onPlayNext: (AudioTrack) -> Unit = {},
    onAddToQueue: (AudioTrack) -> Unit = {},
    onCreatePlaylist: (String) -> Unit = {},
    onRenamePlaylist: (Long, String) -> Unit = { _, _ -> },
    onDeletePlaylist: (Long) -> Unit = {},
    onAddTrackToPlaylist: (Long, Long) -> Unit = { _, _ -> },
    onRemoveTrackFromPlaylist: (Long, Long) -> Unit = { _, _ -> },
    onMovePlaylistTrack: (Long, Int, Int) -> Unit = { _, _, _ -> },
    onSaveTrackOrder: suspend (Long?, List<Long>) -> Boolean = { _, _ -> false },
    onPlayQueueIndex: (Int) -> Unit = {},
    onRemoveQueueItem: (Int) -> Unit = {},
    onMoveQueueItem: (Int, Int) -> Unit = { _, _ -> },
    onClearQueue: () -> Unit = {},
    onRetryPlayback: () -> Unit = {},
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onRefreshPlaybackPosition: () -> Unit = {},
    onCyclePlaybackOrder: () -> Unit,
    onSetUsbOutputMode: (UsbOutputMode) -> Unit = {},
    onAddLibraryFolder: () -> Unit = {},
    onRemoveLibraryFolder: (String) -> Unit = {},
    onPauseLibraryScan: () -> Unit = {},
    onResumeLibraryScan: () -> Unit = {},
    motionPolicy: VesqenMotionPolicy? = null,
    managePhoneOrientation: Boolean = false,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
    playbackTelemetry: PlaybackTelemetry? = null,
    chainPreferencesRepository: ChainDashboardPreferencesRepository? = null,
    diagnosticRecorder: DiagnosticRecorder? = null,
    diagnosticExportFeedback: DiagnosticExportFeedback = DiagnosticExportFeedback.NONE,
    onRequestDiagnosticExport: () -> Unit = {},
    onClearDiagnosticExportFeedback: () -> Unit = {},
    verificationRegistryState: OutputVerificationRegistryState = OutputVerificationRegistryState.Empty,
    verificationImportResult: OutputVerificationImportResult? = null,
    onImportVerificationRegistry: () -> Unit = {},
) {
    val appliedMotionPolicy = motionPolicy ?: rememberVesqenMotionPolicy()
    val appliedChainPreferencesRepository = chainPreferencesRepository ?: remember {
        InMemoryChainDashboardPreferencesRepository()
    }
    val destinationStateHolder = rememberSaveableStateHolder()
    var destinationName by rememberSaveable { mutableStateOf(VesqenDestination.LIBRARY.name) }
    var returnDestinationName by rememberSaveable { mutableStateOf(VesqenDestination.LIBRARY.name) }
    var playerReturnDestinationName by rememberSaveable { mutableStateOf(VesqenDestination.LIBRARY.name) }
    val navigationState = VesqenNavigationState(
        destination = VesqenDestination.valueOf(destinationName),
        returnDestination = VesqenDestination.valueOf(returnDestinationName),
        playerReturnDestination = VesqenDestination.valueOf(playerReturnDestinationName),
    )
    val destination = navigationState.destination
    val hasFocusedPlayer = destination == VesqenDestination.NOW && state.playback.hasActiveTrack
    val refreshFocusedPlayerPosition = shouldRefreshPlaybackPosition(destination, state.playback)
    val currentRefreshPlaybackPosition by rememberUpdatedState(onRefreshPlaybackPosition)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, refreshFocusedPlayerPosition, state.playback.trackId) {
        if (!refreshFocusedPlayerPosition) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                currentRefreshPlaybackPosition()
                delay(500)
            }
        }
    }
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
    val windowWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val useNavigationRail = windowWidth >= 600.dp &&
        !hasFocusedPlayer && !isSecondaryDetail

    fun applyNavigation(updated: VesqenNavigationState) {
        destinationName = updated.destination.name
        returnDestinationName = updated.returnDestination.name
        playerReturnDestinationName = updated.playerReturnDestination.name
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
                destinationStateHolder = destinationStateHolder,
                showNavigation = false,
                motionPolicy = appliedMotionPolicy,
                playbackTelemetry = playbackTelemetry,
                chainPreferencesRepository = appliedChainPreferencesRepository,
                diagnosticRecorder = diagnosticRecorder,
                diagnosticExportFeedback = diagnosticExportFeedback,
                onRequestDiagnosticExport = onRequestDiagnosticExport,
                onClearDiagnosticExportFeedback = onClearDiagnosticExportFeedback,
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
                onPlayQueue = onPlayQueue,
                onToggleFavorite = onToggleFavorite,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onCreatePlaylist = onCreatePlaylist,
                onRenamePlaylist = onRenamePlaylist,
                onDeletePlaylist = onDeletePlaylist,
                onAddTrackToPlaylist = onAddTrackToPlaylist,
                onRemoveTrackFromPlaylist = onRemoveTrackFromPlaylist,
                onMovePlaylistTrack = onMovePlaylistTrack,
                onSaveTrackOrder = onSaveTrackOrder,
                onPlayQueueIndex = onPlayQueueIndex,
                onRemoveQueueItem = onRemoveQueueItem,
                onMoveQueueItem = onMoveQueueItem,
                onClearQueue = onClearQueue,
                onRetryPlayback = onRetryPlayback,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onSeek = onSeek,
                onCyclePlaybackOrder = onCyclePlaybackOrder,
                onSetUsbOutputMode = onSetUsbOutputMode,
                verificationRegistryState = verificationRegistryState,
                verificationImportResult = verificationImportResult,
                onImportVerificationRegistry = onImportVerificationRegistry,
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
            destinationStateHolder = destinationStateHolder,
            showNavigation = true,
            motionPolicy = appliedMotionPolicy,
            playbackTelemetry = playbackTelemetry,
            chainPreferencesRepository = appliedChainPreferencesRepository,
            diagnosticRecorder = diagnosticRecorder,
            diagnosticExportFeedback = diagnosticExportFeedback,
            onRequestDiagnosticExport = onRequestDiagnosticExport,
            onClearDiagnosticExportFeedback = onClearDiagnosticExportFeedback,
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
            onPlayQueue = onPlayQueue,
            onToggleFavorite = onToggleFavorite,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onCreatePlaylist = onCreatePlaylist,
            onRenamePlaylist = onRenamePlaylist,
            onDeletePlaylist = onDeletePlaylist,
            onAddTrackToPlaylist = onAddTrackToPlaylist,
            onRemoveTrackFromPlaylist = onRemoveTrackFromPlaylist,
            onMovePlaylistTrack = onMovePlaylistTrack,
            onSaveTrackOrder = onSaveTrackOrder,
            onPlayQueueIndex = onPlayQueueIndex,
            onRemoveQueueItem = onRemoveQueueItem,
            onMoveQueueItem = onMoveQueueItem,
            onClearQueue = onClearQueue,
            onRetryPlayback = onRetryPlayback,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onSeek = onSeek,
            onCyclePlaybackOrder = onCyclePlaybackOrder,
            onSetUsbOutputMode = onSetUsbOutputMode,
            verificationRegistryState = verificationRegistryState,
            verificationImportResult = verificationImportResult,
            onImportVerificationRegistry = onImportVerificationRegistry,
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
    destinationStateHolder: SaveableStateHolder,
    showNavigation: Boolean,
    motionPolicy: VesqenMotionPolicy,
    playbackTelemetry: PlaybackTelemetry?,
    chainPreferencesRepository: ChainDashboardPreferencesRepository,
    diagnosticRecorder: DiagnosticRecorder?,
    diagnosticExportFeedback: DiagnosticExportFeedback,
    onRequestDiagnosticExport: () -> Unit,
    onClearDiagnosticExportFeedback: () -> Unit,
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
    onPlayQueue: (List<AudioTrack>, Int) -> Unit,
    onToggleFavorite: (Long, Boolean) -> Unit,
    onPlayNext: (AudioTrack) -> Unit,
    onAddToQueue: (AudioTrack) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onAddTrackToPlaylist: (Long, Long) -> Unit,
    onRemoveTrackFromPlaylist: (Long, Long) -> Unit,
    onMovePlaylistTrack: (Long, Int, Int) -> Unit,
    onSaveTrackOrder: suspend (Long?, List<Long>) -> Boolean,
    onPlayQueueIndex: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onRetryPlayback: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlaybackOrder: () -> Unit,
    onSetUsbOutputMode: (UsbOutputMode) -> Unit,
    verificationRegistryState: OutputVerificationRegistryState,
    verificationImportResult: OutputVerificationImportResult?,
    onImportVerificationRegistry: () -> Unit,
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
    val currentTrack = remember(state.playback.trackId, state.library.tracks) {
        state.playback.trackId?.let { id ->
            state.library.tracks.firstOrNull { it.id == id }
        }
    }
    // The controller can reconnect before a freshly-scanned library has been delivered. Retain
    // Media3's opaque metadata in that brief state so the mini and focus player do not regress to
    // a branded placeholder merely because the UI map is still empty.
    // A playing SAF item remains independently authorised by its persisted tree grant. Broad
    // MediaStore permission is therefore not a valid gate for the MediaSession fallback; the
    // loader itself safely handles a URI whose underlying grant has actually been revoked.
    val artworkTrack = currentTrack ?: state.playback.toArtworkTrackOrNull()
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
                        !initialState.isSecondaryDetail
                    val closesFocusedPlayer = initialState == VesqenDestination.NOW &&
                        !targetState.isSecondaryDetail
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
                                initialOffsetY = { height -> height / 4 },
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
                                    targetOffsetY = { height -> height / 4 },
                                ) + scaleOut(
                                    targetScale = .94f,
                                    animationSpec = tween(
                                        durationMillis = motionPolicy.playerCollapseMillis,
                                        easing = FocusedPlayerEasing,
                                    ),
                                ))
                        }

                        else -> {
                            val returning = initialState.isSecondaryDetail ||
                                targetState == VesqenDestination.LIBRARY
                            val direction = if (returning) -1 else 1
                            val duration = motionPolicy.playerExpandMillis
                            (fadeIn(animationSpec = tween(duration)) +
                                slideInHorizontally(animationSpec = tween(duration)) {
                                    it * direction / if (returning) 12 else 4
                                }) togetherWith
                                (fadeOut(animationSpec = tween(duration)) +
                                    slideOutHorizontally(animationSpec = tween(duration)) {
                                        -it * direction / if (returning) 4 else 12
                                    })
                        }
                    }.apply {
                        // Keep the retreating surface above its destination so the incoming opaque
                        // page cannot cover the player's collapse or a detail's return animation.
                        targetContentZIndex = when {
                            targetState.isSecondaryDetail -> 3f
                            targetState == VesqenDestination.NOW -> 2f
                            targetState == VesqenDestination.SETTINGS -> 1f
                            else -> 0f
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
                    VesqenDestination.LIBRARY -> destinationStateHolder.SaveableStateProvider(
                        key = VesqenDestination.LIBRARY.name,
                    ) {
                        LibraryScreen(
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
                            onPlayQueue = onPlayQueue,
                            onToggleFavorite = onToggleFavorite,
                            onPlayNext = onPlayNext,
                            onAddToQueue = onAddToQueue,
                            onCreatePlaylist = onCreatePlaylist,
                            onRenamePlaylist = onRenamePlaylist,
                            onDeletePlaylist = onDeletePlaylist,
                            onAddTrackToPlaylist = onAddTrackToPlaylist,
                            onRemoveTrackFromPlaylist = onRemoveTrackFromPlaylist,
                            onMovePlaylistTrack = onMovePlaylistTrack,
                            onSaveTrackOrder = onSaveTrackOrder,
                            motionPolicy = motionPolicy,
                            modifier = destinationModifier,
                        )
                    }

                    VesqenDestination.NOW -> NowScreen(
                        onToggleFavorite = onToggleFavorite,
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
                        onPlayQueueIndex = onPlayQueueIndex,
                        onRemoveQueueItem = onRemoveQueueItem,
                        onMoveQueueItem = onMoveQueueItem,
                        onClearQueue = onClearQueue,
                        onRetryPlayback = onRetryPlayback,
                        onToggleOrientation = onTogglePlayerOrientation,
                        showOrientationToggle = showOrientationToggle,
                        isLandscape = isLandscape,
                        motionPolicy = motionPolicy,
                        modifier = destinationModifier,
                    )

                    VesqenDestination.SETTINGS -> SettingsScreen(
                        outputStatus = state.playback.usbOutputStatus,
                        outputVerification = state.playback.outputVerification,
                        verificationRegistryState = verificationRegistryState,
                        verificationImportResult = verificationImportResult,
                        onSetUsbOutputMode = onSetUsbOutputMode,
                        onOpenPlaybackChain = onOpenChain,
                        onImportVerificationRegistry = onImportVerificationRegistry,
                        onOpenAbout = onOpenAbout,
                        versionName = versionName,
                        modifier = destinationModifier,
                    )

                    VesqenDestination.CHAIN -> ChainScreen(
                        snapshot = state.playback,
                        playbackTelemetry = playbackTelemetry,
                        preferencesRepository = chainPreferencesRepository,
                        diagnosticRecorder = diagnosticRecorder,
                        diagnosticExportFeedback = diagnosticExportFeedback,
                        onRequestDiagnosticExport = onRequestDiagnosticExport,
                        onClearDiagnosticExportFeedback = onClearDiagnosticExportFeedback,
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
