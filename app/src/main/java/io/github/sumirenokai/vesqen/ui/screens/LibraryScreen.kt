package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.LibrarySourceKind
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.components.TrackDetailsSheet
import io.github.sumirenokai.vesqen.ui.components.TrackRow
import io.github.sumirenokai.vesqen.ui.components.VesqenEmptyState
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.WarningAmberBright
import io.github.sumirenokai.vesqen.ui.theme.WarningAmberDeep

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    playback: PlaybackSnapshot,
    onRequestMusicAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRescan: () -> Unit,
    onTrackSelected: (AudioTrack) -> Unit,
    modifier: Modifier = Modifier,
    onAddLibraryFolder: () -> Unit = {},
    onRemoveLibraryFolder: (String) -> Unit = {},
    onPauseLibraryScan: () -> Unit = {},
    onResumeLibraryScan: () -> Unit = {},
) {
    LibraryContent(
        state = state,
        playback = playback,
        onRequestMusicAccess = onRequestMusicAccess,
        onOpenAppSettings = onOpenAppSettings,
        onOpenNotificationSettings = onOpenNotificationSettings,
        onRescan = onRescan,
        onAddLibraryFolder = onAddLibraryFolder,
        onRemoveLibraryFolder = onRemoveLibraryFolder,
        onPauseLibraryScan = onPauseLibraryScan,
        onResumeLibraryScan = onResumeLibraryScan,
        onTrackSelected = onTrackSelected,
        modifier = modifier,
    )
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    playback: PlaybackSnapshot,
    onRequestMusicAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRescan: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onAddLibraryFolder: () -> Unit,
    onRemoveLibraryFolder: (String) -> Unit,
    onPauseLibraryScan: () -> Unit,
    onResumeLibraryScan: () -> Unit,
    onTrackSelected: (AudioTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var detailsTrack by remember { mutableStateOf<AudioTrack?>(null) }
    val visibleTracks = filterTracks(state.tracks, query)

    Column(modifier = modifier.fillMaxSize()) {
        LibraryHeader(
            onAddLibraryFolder = onAddLibraryFolder,
            onRescan = onRescan,
            sourceActionsEnabled = !state.isLoading,
        )
        if (state.musicAccess != MusicAccess.GRANTED) {
            DeviceMusicAccessNotice(
                denied = state.musicAccess == MusicAccess.DENIED,
                onRequestMusicAccess = onRequestMusicAccess,
                onOpenAppSettings = onOpenAppSettings,
                onAddLibraryFolder = onAddLibraryFolder,
            )
        }
        if (
            state.sources.any { it.kind == LibrarySourceKind.FOLDER } ||
            state.scanProgress != null ||
            state.sources.any { it.scanState == LibraryScanState.FAILED }
        ) {
            LibrarySourcesCard(
                state = state,
                onAddLibraryFolder = onAddLibraryFolder,
                onRemoveLibraryFolder = onRemoveLibraryFolder,
                onPauseLibraryScan = onPauseLibraryScan,
                onResumeLibraryScan = onResumeLibraryScan,
            )
        }
        if (!state.notificationsAllowed) {
            NotificationNotice(onOpenNotificationSettings = onOpenNotificationSettings)
        }
        LibrarySearchField(query = query, onQueryChange = { query = it })
        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading && state.tracks.isEmpty() -> LibraryLoading()
                state.loadingFailed && state.tracks.isEmpty() -> VesqenEmptyState(
                    title = stringResource(R.string.library_load_failed),
                    body = stringResource(
                        if (playback.hasActiveTrack) {
                            R.string.library_load_failed_playback_continues
                        } else {
                            R.string.library_load_failed_body
                        },
                    ),
                    actionLabel = stringResource(R.string.try_again),
                    onAction = onRescan,
                    modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
                )

                state.tracks.isEmpty() -> VesqenEmptyState(
                    title = stringResource(R.string.no_local_music),
                    body = stringResource(R.string.no_local_music_body),
                    actionLabel = stringResource(R.string.add_music_folder),
                    onAction = onAddLibraryFolder,
                    modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
                )

                visibleTracks.isEmpty() -> VesqenEmptyState(
                    title = stringResource(R.string.no_search_results),
                    body = stringResource(R.string.no_search_results_body),
                    actionLabel = stringResource(R.string.clear_search),
                    onAction = { query = "" },
                    modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = VesqenSpacing.md,
                        end = VesqenSpacing.md,
                        top = VesqenSpacing.xs,
                        bottom = VesqenSpacing.md,
                    ),
                    verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
                ) {
                    items(items = visibleTracks, key = AudioTrack::id) { track ->
                        TrackRow(
                            track = track,
                            isCurrent = track.id == playback.trackId,
                            isPlaying = playback.isPlaying,
                            onPlay = { onTrackSelected(track) },
                            onMore = { detailsTrack = track },
                        )
                    }
                }
            }
        }
    }

    detailsTrack?.let { track ->
        TrackDetailsSheet(
            track = track,
            onDismiss = { detailsTrack = null },
            onPlay = {
                onTrackSelected(track)
                detailsTrack = null
            },
        )
    }
}

@Composable
private fun LibraryHeader(
    onAddLibraryFolder: () -> Unit,
    onRescan: () -> Unit,
    sourceActionsEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.destination_library),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            modifier = Modifier.size(48.dp).testTag("vesqen.library.add-folder"),
            onClick = onAddLibraryFolder,
            enabled = sourceActionsEnabled,
        ) {
            Icon(
                imageVector = Icons.Filled.CreateNewFolder,
                contentDescription = stringResource(R.string.add_music_folder),
            )
        }
        IconButton(
            modifier = Modifier.size(48.dp).testTag("vesqen.library.rescan"),
            onClick = onRescan,
            enabled = sourceActionsEnabled,
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.rescan_library),
            )
        }
    }
}

@Composable
private fun DeviceMusicAccessNotice(
    denied: Boolean,
    onRequestMusicAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onAddLibraryFolder: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xs),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(VesqenSpacing.md)) {
            Text(
                text = stringResource(R.string.device_music_access),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(VesqenSpacing.xxs))
            Text(
                text = stringResource(R.string.device_music_access_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(VesqenSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.xs)) {
                Button(
                    onClick = onRequestMusicAccess,
                    modifier = Modifier.testTag("vesqen.permission.request"),
                ) {
                    Text(stringResource(if (denied) R.string.try_again else R.string.grant_music_access))
                }
                TextButton(onClick = onAddLibraryFolder) {
                    Text(stringResource(R.string.add_music_folder))
                }
            }
            if (denied) {
                TextButton(onClick = onOpenAppSettings) {
                    Text(stringResource(R.string.open_app_settings))
                }
            }
        }
    }
}

@Composable
private fun LibrarySourcesCard(
    state: LibraryUiState,
    onAddLibraryFolder: () -> Unit,
    onRemoveLibraryFolder: (String) -> Unit,
    onPauseLibraryScan: () -> Unit,
    onResumeLibraryScan: () -> Unit,
) {
    var showSourceManager by rememberSaveable { mutableStateOf(false) }
    val folders = state.sources.filter { it.kind == LibrarySourceKind.FOLDER }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xs)
            .testTag("vesqen.library.sources"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(VesqenSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(VesqenSpacing.xs))
                Text(
                    text = stringResource(R.string.music_sources),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = if (folders.isEmpty()) {
                        onAddLibraryFolder
                    } else {
                        { showSourceManager = true }
                    },
                    enabled = !state.isLoading,
                    modifier = Modifier.testTag(
                        if (folders.isEmpty()) {
                            "vesqen.library.sources.add"
                        } else {
                            "vesqen.library.sources.manage"
                        },
                    ),
                ) {
                    Text(
                        stringResource(
                            if (folders.isEmpty()) R.string.add_music_folder else R.string.manage_music_sources,
                        ),
                    )
                }
            }
            Text(
                text = pluralStringResource(
                    R.plurals.imported_folders_count,
                    folders.size,
                    folders.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val progress = state.scanProgress
            if (progress != null) {
                Spacer(Modifier.height(VesqenSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (state.isScanPaused) {
                            pluralStringResource(
                                R.plurals.library_scan_paused,
                                progress.scannedTrackCount,
                                progress.sourceName,
                                progress.scannedTrackCount,
                            )
                        } else {
                            stringResource(
                                R.string.library_scanning_source,
                                progress.sourceName,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = if (state.isScanPaused) onResumeLibraryScan else onPauseLibraryScan,
                        modifier = Modifier.testTag(
                            if (state.isScanPaused) {
                                "vesqen.library.resume-scan"
                            } else {
                                "vesqen.library.pause-scan"
                            },
                        ),
                    ) {
                        Text(
                            stringResource(
                                if (state.isScanPaused) R.string.resume_scan else R.string.pause_scan,
                            ),
                        )
                    }
                }
            }
            if (state.sources.any { it.scanState == LibraryScanState.FAILED }) {
                Text(
                    text = stringResource(R.string.library_source_scan_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (folders.any { !it.isAvailable }) {
                Text(
                    text = stringResource(R.string.library_folder_access_needs_renewal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    if (showSourceManager) {
        LibrarySourcesSheet(
            folders = folders,
            scanInProgress = state.isLoading,
            onDismiss = { showSourceManager = false },
            onAddLibraryFolder = {
                showSourceManager = false
                onAddLibraryFolder()
            },
            onRemove = { sourceId ->
                onRemoveLibraryFolder(sourceId)
                if (folders.size == 1) showSourceManager = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySourcesSheet(
    folders: List<LibrarySource>,
    scanInProgress: Boolean,
    onDismiss: () -> Unit,
    onAddLibraryFolder: () -> Unit,
    onRemove: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("vesqen.library.source-manager"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.manage_music_sources),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onAddLibraryFolder,
                    enabled = !scanInProgress,
                ) {
                    Text(stringResource(R.string.add_music_folder))
                }
            }
            Spacer(Modifier.height(VesqenSpacing.sm))
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(items = folders, key = LibrarySource::id) { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = VesqenSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(source.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = sourceStatusText(source),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            enabled = !scanInProgress,
                            onClick = { onRemove(source.id) },
                            modifier = Modifier.testTag("vesqen.library.source.${source.id}.remove"),
                        ) {
                            Text(stringResource(R.string.remove_music_folder))
                        }
                    }
                }
            }
            Spacer(Modifier.height(VesqenSpacing.sm))
        }
    }
}

@Composable
private fun sourceStatusText(source: LibrarySource): String = when {
    !source.isAvailable -> stringResource(R.string.library_folder_access_needs_renewal)
    source.scanState == LibraryScanState.PAUSED -> stringResource(R.string.library_source_paused)
    source.scanState == LibraryScanState.INTERRUPTED -> stringResource(R.string.library_source_interrupted)
    source.scanState == LibraryScanState.FAILED -> stringResource(R.string.library_source_scan_failed)
    else -> pluralStringResource(
        R.plurals.library_source_track_count,
        source.trackCount,
        source.trackCount,
    )
}

@Composable
private fun LibrarySearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.md)
            .testTag("vesqen.library.search"),
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        label = { Text(stringResource(R.string.search_local_music)) },
        leadingIcon = {
            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
        },
        trailingIcon = if (query.isBlank()) {
            null
        } else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.clear_search),
                    )
                }
            }
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

@Composable
private fun NotificationNotice(onOpenNotificationSettings: () -> Unit) {
    val warning = if (androidx.compose.foundation.isSystemInDarkTheme()) WarningAmberBright else WarningAmberDeep
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.sm),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
        color = warning.copy(alpha = .16f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(VesqenSpacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = warning,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(VesqenSpacing.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.notifications_disabled),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onOpenNotificationSettings) {
                    Text(stringResource(R.string.open_app_settings))
                }
            }
        }
    }
}

@Composable
private fun LibraryLoading() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
    ) {
        repeat(6) {
            Row(
                modifier = Modifier.height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.album),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {}
                Spacer(Modifier.width(VesqenSpacing.sm))
                Column(verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs)) {
                    Surface(
                        modifier = Modifier.width(176.dp).height(14.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {}
                    Surface(
                        modifier = Modifier.width(112.dp).height(12.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {}
                }
            }
        }
    }
}

internal fun filterTracks(tracks: List<AudioTrack>, query: String): List<AudioTrack> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return tracks
    return tracks.filter { track ->
        track.title.contains(normalizedQuery, ignoreCase = true) ||
            track.artist.contains(normalizedQuery, ignoreCase = true) ||
            track.album.contains(normalizedQuery, ignoreCase = true)
    }
}
