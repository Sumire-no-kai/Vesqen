package io.github.sumirenokai.vesqen.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VesqenApp(viewModel: VesqenViewModel = viewModel()) {
    val context = LocalContext.current
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

    LaunchedEffect(Unit) {
        viewModel.initialisePermissions(
            musicGranted = ContextCompat.checkSelfPermission(context, musicPermission) == PackageManager.PERMISSION_GRANTED,
            notificationsGranted = notificationPermission == null ||
                ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val uiState = viewModel.uiState
    val playback = viewModel.playbackSnapshot
    LaunchedEffect(playback.isPlaying) {
        while (isActive && playback.isPlaying) {
            delay(500)
            viewModel.refreshPlaybackPosition()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        bottomBar = {
            if (playback.title.isNotBlank()) {
                NowPlayingBar(
                    snapshot = playback,
                    onPrevious = viewModel::skipToPrevious,
                    onPlayPause = viewModel::togglePlayback,
                    onNext = viewModel::skipToNext,
                    onSeek = viewModel::seekTo,
                )
            }
        },
    ) { innerPadding ->
        when (uiState.musicAccess) {
            MusicAccess.NEEDS_PERMISSION,
            MusicAccess.DENIED,
            -> PermissionScreen(
                modifier = Modifier.padding(innerPadding),
                denied = uiState.musicAccess == MusicAccess.DENIED,
                onRequest = {
                    musicLauncher.launch(musicPermission)
                },
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
            )

            MusicAccess.GRANTED -> LibraryScreen(
                modifier = Modifier.padding(innerPadding),
                state = uiState,
                onRescan = viewModel::refreshLibrary,
                onTrackSelected = viewModel::play,
            )
        }
    }
}

@Composable
private fun PermissionScreen(
    modifier: Modifier = Modifier,
    denied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = stringResource(R.string.library_permission_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(text = stringResource(R.string.library_permission_body))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) {
            Text(stringResource(if (denied) R.string.try_again else R.string.grant_music_access))
        }
        if (denied) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.open_app_settings))
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    modifier: Modifier = Modifier,
    state: LibraryUiState,
    onRescan: () -> Unit,
    onTrackSelected: (AudioTrack) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (!state.notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.notifications_disabled),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        ConnectedOutputs(state.connectedOutputs)
        when {
            state.isLoading -> LoadingLibrary()
            state.loadingFailed -> ErrorLibrary(onRescan)
            state.tracks.isEmpty() -> EmptyLibrary(onRescan)
            else -> TrackList(state.tracks, onTrackSelected)
        }
    }
}

@Composable
private fun ConnectedOutputs(outputs: Set<AudioOutputType>) {
    val labels = mutableListOf<String>()
    if (AudioOutputType.PHONE_SPEAKER in outputs) labels += stringResource(R.string.output_phone_speaker)
    if (AudioOutputType.WIRED_OR_USB in outputs) labels += stringResource(R.string.output_wired_or_usb)
    if (AudioOutputType.BLUETOOTH in outputs) labels += stringResource(R.string.output_bluetooth)
    if (AudioOutputType.OTHER in outputs) labels += stringResource(R.string.output_other)
    val description = labels.joinToString().ifBlank { stringResource(R.string.output_none_detected) }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.system_mixed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.system_mixed_explanation),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.connected_outputs, description),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LoadingLibrary() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.loading_library))
    }
}

@Composable
private fun EmptyLibrary(onRescan: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.no_local_music), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.no_local_music_body), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onRescan) { Text(stringResource(R.string.rescan_library)) }
    }
}

@Composable
private fun ErrorLibrary(onRescan: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.library_load_failed), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRescan) { Text(stringResource(R.string.try_again)) }
    }
}

@Composable
private fun TrackList(tracks: List<AudioTrack>, onTrackSelected: (AudioTrack) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = tracks, key = AudioTrack::id) { track ->
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTrackSelected(track) },
                headlineContent = {
                    Text(
                        text = track.title.ifBlank { stringResource(R.string.unknown_title) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        text = track.displaySubtitle().ifBlank { stringResource(R.string.unknown_artist) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = { Text(formatDuration(track.durationMs)) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun NowPlayingBar(
    snapshot: PlaybackSnapshot,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(snapshot.title, snapshot.durationMs, snapshot.positionMs) {
        if (!isSeeking) {
            seekPosition = snapshot.positionMs.coerceIn(0, snapshot.durationMs).toFloat()
        }
    }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(snapshot.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            if (snapshot.artist.isNotBlank()) {
                Text(snapshot.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { snapshot.progressFraction }, modifier = Modifier.fillMaxWidth())
            if (snapshot.durationMs > 0) {
                Slider(
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
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                OutlinedButton(onClick = onPrevious, enabled = snapshot.hasPrevious) {
                    Text(stringResource(R.string.previous))
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onPlayPause, enabled = snapshot.isControllerReady) {
                    Text(stringResource(if (snapshot.isPlaying) R.string.pause else R.string.play))
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onNext, enabled = snapshot.hasNext) {
                    Text(stringResource(R.string.next))
                }
            }
            if (snapshot.durationMs > 0) {
                Text(
                    text = stringResource(
                        R.string.playback_position,
                        formatDuration(snapshot.positionMs),
                        formatDuration(snapshot.durationMs),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
