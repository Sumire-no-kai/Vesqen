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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryBrowseMode
import io.github.sumirenokai.vesqen.library.LibraryCollection
import io.github.sumirenokai.vesqen.library.LibraryPlaylist
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySortOrder
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.LibrarySourceKind
import io.github.sumirenokai.vesqen.library.buildLibraryCollections
import io.github.sumirenokai.vesqen.library.sortLibraryTracks
import io.github.sumirenokai.vesqen.library.sortLibraryCollections
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
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var detailsTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var browseModeName by rememberSaveable { mutableStateOf(LibraryBrowseMode.SONGS.name) }
    var sortOrderName by rememberSaveable { mutableStateOf(LibrarySortOrder.TITLE.name) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var selectedCollectionKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreatePlaylist by rememberSaveable { mutableStateOf(false) }
    var playlistToEdit by remember { mutableStateOf<LibraryPlaylist?>(null) }
    val browseMode = LibraryBrowseMode.valueOf(browseModeName)
    val sortOrder = LibrarySortOrder.valueOf(sortOrderName)
    val searchedTracks = remember(state.tracks, query) { filterTracks(state.tracks, query) }
    val filteredTracks = remember(searchedTracks, favoritesOnly) {
        if (favoritesOnly) searchedTracks.filter(AudioTrack::isFavorite) else searchedTracks
    }
    val visibleTracks = remember(filteredTracks, sortOrder) {
        sortLibraryTracks(filteredTracks, sortOrder)
    }
    val collections = remember(browseMode, filteredTracks, state.playlists, sortOrder) {
        sortLibraryCollections(
            buildLibraryCollections(browseMode, filteredTracks, state.playlists),
            sortOrder,
        )
    }
    val selectedCollection = remember(collections, selectedCollectionKey) {
        collections.firstOrNull { it.key == selectedCollectionKey }
    }

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
        if (!state.notificationsAllowed && playback.hasActiveTrack) {
            NotificationNotice(onOpenNotificationSettings = onOpenNotificationSettings)
        }
        if (state.tracks.isNotEmpty()) {
            LibrarySearchField(query = query, onQueryChange = { query = it })
            LibraryBrowseBar(
                browseMode = browseMode,
                sortOrder = sortOrder,
                favoritesOnly = favoritesOnly,
                onBrowseModeChanged = { mode ->
                    browseModeName = mode.name
                    selectedCollectionKey = null
                },
                onSortOrderChanged = { sortOrderName = it.name },
                onToggleFavorites = { favoritesOnly = !favoritesOnly },
            )
        }
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

                browseMode == LibraryBrowseMode.PLAYLISTS && state.playlists.isEmpty() -> VesqenEmptyState(
                    title = stringResource(R.string.library_playlists),
                    body = stringResource(R.string.no_playlists_body),
                    actionLabel = stringResource(R.string.create_playlist),
                    onAction = { showCreatePlaylist = true },
                    modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
                )

                visibleTracks.isEmpty() && browseMode != LibraryBrowseMode.PLAYLISTS -> VesqenEmptyState(
                    title = stringResource(R.string.no_search_results),
                    body = stringResource(R.string.no_search_results_body),
                    actionLabel = stringResource(R.string.clear_search),
                    onAction = { query = "" },
                    modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
                )

                selectedCollection != null -> CollectionTrackList(
                    collection = selectedCollection,
                    playback = playback,
                    onBack = { selectedCollectionKey = null },
                    onPlayQueue = onPlayQueue,
                    onTrackSelected = { track ->
                        onPlayQueue(selectedCollection.tracks, selectedCollection.tracks.indexOf(track))
                    },
                    onTrackMore = { detailsTrack = it },
                    onEditPlaylist = selectedCollection.playlistId?.let { playlistId ->
                        { playlistToEdit = state.playlists.firstOrNull { it.id == playlistId } }
                    },
                )

                browseMode == LibraryBrowseMode.SONGS -> TrackList(
                    tracks = visibleTracks,
                    playback = playback,
                    onTrackSelected = { track ->
                        onPlayQueue(visibleTracks, visibleTracks.indexOf(track))
                    },
                    onTrackMore = { detailsTrack = it },
                )

                else -> CollectionList(
                    mode = browseMode,
                    collections = collections,
                    onCollectionSelected = { selectedCollectionKey = it.key },
                    onCreatePlaylist = if (browseMode == LibraryBrowseMode.PLAYLISTS) {
                        { showCreatePlaylist = true }
                    } else {
                        null
                    },
                )
            }
        }
    }

    detailsTrack?.let { track ->
        val playlistId = selectedCollection?.playlistId
        val playlistTrackIndex = selectedCollection?.tracks?.indexOfFirst { it.id == track.id } ?: -1
        TrackDetailsSheet(
            track = track,
            playlists = state.playlists,
            onDismiss = { detailsTrack = null },
            onPlay = {
                if (selectedCollection != null) {
                    onPlayQueue(selectedCollection.tracks, selectedCollection.tracks.indexOf(track))
                } else {
                    onTrackSelected(track)
                }
                detailsTrack = null
            },
            onToggleFavorite = {
                onToggleFavorite(track.id, !track.isFavorite)
                detailsTrack = null
            },
            onPlayNext = {
                onPlayNext(track)
                detailsTrack = null
            },
            onAddToQueue = {
                onAddToQueue(track)
                detailsTrack = null
            },
            onAddToPlaylist = { targetPlaylistId ->
                onAddTrackToPlaylist(targetPlaylistId, track.id)
                detailsTrack = null
            },
            onRemoveFromPlaylist = playlistId?.let {
                {
                    onRemoveTrackFromPlaylist(it, track.id)
                    detailsTrack = null
                }
            },
            onMoveUp = if (playlistId != null && playlistTrackIndex > 0) {
                {
                    onMovePlaylistTrack(playlistId, playlistTrackIndex, playlistTrackIndex - 1)
                    detailsTrack = null
                }
            } else null,
            onMoveDown = if (
                playlistId != null &&
                playlistTrackIndex >= 0 &&
                playlistTrackIndex < selectedCollection.tracks.lastIndex
            ) {
                {
                    onMovePlaylistTrack(playlistId, playlistTrackIndex, playlistTrackIndex + 1)
                    detailsTrack = null
                }
            } else null,
        )
    }

    if (showCreatePlaylist) {
        PlaylistNameDialog(
            title = stringResource(R.string.create_playlist),
            initialName = "",
            confirmLabel = stringResource(R.string.create),
            onDismiss = { showCreatePlaylist = false },
            onConfirm = { name ->
                onCreatePlaylist(name)
                showCreatePlaylist = false
            },
        )
    }
    playlistToEdit?.let { playlist ->
        PlaylistEditDialog(
            playlist = playlist,
            onDismiss = { playlistToEdit = null },
            onRename = { name ->
                onRenamePlaylist(playlist.id, name)
                playlistToEdit = null
            },
            onDelete = {
                onDeletePlaylist(playlist.id)
                selectedCollectionKey = null
                playlistToEdit = null
            },
        )
    }
}

@Composable
private fun LibraryBrowseBar(
    browseMode: LibraryBrowseMode,
    sortOrder: LibrarySortOrder,
    favoritesOnly: Boolean,
    onBrowseModeChanged: (LibraryBrowseMode) -> Unit,
    onSortOrderChanged: (LibrarySortOrder) -> Unit,
    onToggleFavorites: () -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    val modes = LibraryBrowseMode.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryScrollableTabRow(
            selectedTabIndex = modes.indexOf(browseMode),
            modifier = Modifier.weight(1f),
            edgePadding = 0.dp,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            divider = {},
        ) {
            modes.forEach { mode ->
                Tab(
                    selected = mode == browseMode,
                    onClick = { onBrowseModeChanged(mode) },
                    text = {
                        Text(
                            text = stringResource(mode.labelResource()),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.testTag("vesqen.library.mode.${mode.name.lowercase()}"),
                )
            }
        }
        IconButton(
            onClick = onToggleFavorites,
            modifier = Modifier.testTag("vesqen.library.favorites"),
        ) {
            Icon(
                imageVector = if (favoritesOnly) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(
                    if (favoritesOnly) R.string.show_all_music else R.string.show_favorites,
                ),
                tint = if (favoritesOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(
                onClick = { showSortMenu = true },
                modifier = Modifier.testTag("vesqen.library.sort"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = stringResource(R.string.sort_library),
                )
            }
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false },
            ) {
                LibrarySortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(stringResource(order.labelResource())) },
                        onClick = {
                            onSortOrderChanged(order)
                            showSortMenu = false
                        },
                        leadingIcon = if (order == sortOrder) {
                            { Icon(Icons.Filled.MusicNote, contentDescription = null) }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<AudioTrack>,
    playback: PlaybackSnapshot,
    onTrackSelected: (AudioTrack) -> Unit,
    onTrackMore: (AudioTrack) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = VesqenSpacing.md,
            end = VesqenSpacing.md,
            top = VesqenSpacing.xs,
            bottom = VesqenSpacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
    ) {
        items(
            items = tracks,
            key = AudioTrack::id,
            contentType = { "track" },
        ) { track ->
            TrackRow(
                track = track,
                isCurrent = track.id == playback.trackId,
                isPlaying = playback.isPlaying,
                onPlay = { onTrackSelected(track) },
                onMore = { onTrackMore(track) },
            )
        }
    }
}

@Composable
private fun CollectionList(
    mode: LibraryBrowseMode,
    collections: List<LibraryCollection>,
    onCollectionSelected: (LibraryCollection) -> Unit,
    onCreatePlaylist: (() -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = VesqenSpacing.md,
            end = VesqenSpacing.md,
            top = VesqenSpacing.xs,
            bottom = VesqenSpacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
    ) {
        onCreatePlaylist?.let { create ->
            item(key = "create-playlist") {
                Surface(
                    onClick = create,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("vesqen.library.playlist.create"),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = VesqenSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(VesqenSpacing.sm))
                        Text(stringResource(R.string.create_playlist), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
        items(
            items = collections,
            key = LibraryCollection::key,
            contentType = { "collection" },
        ) { collection ->
            CollectionRow(
                mode = mode,
                collection = collection,
                onClick = { onCollectionSelected(collection) },
            )
        }
    }
}

@Composable
private fun CollectionRow(
    mode: LibraryBrowseMode,
    collection: LibraryCollection,
    onClick: () -> Unit,
) {
    val title = collection.title.ifBlank {
        stringResource(
            when (mode) {
                LibraryBrowseMode.ALBUMS -> R.string.unknown_album
                LibraryBrowseMode.ARTISTS -> R.string.unknown_artist
                LibraryBrowseMode.FOLDERS -> R.string.unknown_folder
                LibraryBrowseMode.GENRES -> R.string.unknown_genre
                LibraryBrowseMode.PLAYLISTS -> R.string.unknown_playlist
                LibraryBrowseMode.SONGS -> R.string.unknown_title
            },
        )
    }
    val subtitle = when (mode) {
        LibraryBrowseMode.ALBUMS -> collection.subtitle.ifBlank {
            pluralStringResource(R.plurals.collection_track_count, collection.tracks.size, collection.tracks.size)
        }
        LibraryBrowseMode.ARTISTS -> pluralStringResource(
            R.plurals.collection_album_count,
            collection.subtitle.toIntOrNull() ?: 0,
            collection.subtitle.toIntOrNull() ?: 0,
        )
        else -> pluralStringResource(
            R.plurals.collection_track_count,
            collection.tracks.size,
            collection.tracks.size,
        )
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag("vesqen.library.collection.${collection.key}"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (mode) {
                    LibraryBrowseMode.ALBUMS -> Icons.Filled.Album
                    LibraryBrowseMode.ARTISTS -> Icons.Filled.Person
                    LibraryBrowseMode.FOLDERS -> Icons.Filled.FolderOpen
                    LibraryBrowseMode.GENRES -> Icons.Filled.MusicNote
                    LibraryBrowseMode.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
                    LibraryBrowseMode.SONGS -> Icons.Filled.MusicNote
                },
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(VesqenSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CollectionTrackList(
    collection: LibraryCollection,
    playback: PlaybackSnapshot,
    onBack: () -> Unit,
    onPlayQueue: (List<AudioTrack>, Int) -> Unit,
    onTrackSelected: (AudioTrack) -> Unit,
    onTrackMore: (AudioTrack) -> Unit,
    onEditPlaylist: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = VesqenSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                text = collection.title.ifBlank { stringResource(R.string.unknown_title) },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            onEditPlaylist?.let { edit ->
                IconButton(onClick = edit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_playlist))
                }
            }
            IconButton(
                onClick = { onPlayQueue(collection.tracks, 0) },
                enabled = collection.tracks.isNotEmpty(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play_all))
            }
        }
        if (collection.tracks.isEmpty()) {
            VesqenEmptyState(
                title = collection.title,
                body = stringResource(
                    if (collection.playlistId != null) {
                        R.string.empty_playlist_body
                    } else {
                        R.string.no_local_music_body
                    },
                ),
                actionLabel = stringResource(R.string.back),
                onAction = onBack,
                modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
            )
        } else {
            Box(modifier = Modifier.weight(1f)) {
                TrackList(collection.tracks, playback, onTrackSelected, onTrackMore)
            }
        }
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.playlist_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun PlaylistEditDialog(
    playlist: LibraryPlaylist,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_playlist)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.playlist_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(VesqenSpacing.xxs))
                    Text(stringResource(R.string.delete_playlist))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private fun LibraryBrowseMode.labelResource(): Int = when (this) {
    LibraryBrowseMode.SONGS -> R.string.library_songs
    LibraryBrowseMode.ALBUMS -> R.string.library_albums
    LibraryBrowseMode.ARTISTS -> R.string.library_artists
    LibraryBrowseMode.FOLDERS -> R.string.library_folders
    LibraryBrowseMode.GENRES -> R.string.library_genres
    LibraryBrowseMode.PLAYLISTS -> R.string.library_playlists
}

private fun LibrarySortOrder.labelResource(): Int = when (this) {
    LibrarySortOrder.TITLE -> R.string.sort_title
    LibrarySortOrder.ARTIST -> R.string.sort_artist
    LibrarySortOrder.ALBUM -> R.string.sort_album
    LibrarySortOrder.RECENTLY_ADDED -> R.string.sort_recently_added
    LibrarySortOrder.RECENTLY_PLAYED -> R.string.sort_recently_played
    LibrarySortOrder.MOST_PLAYED -> R.string.sort_most_played
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
            .padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.xs),
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
) {
    val title = stringResource(R.string.device_music_access_compact)
    val detail = stringResource(R.string.device_music_access_body)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xxs),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("vesqen.library.music-access-notice")
                .semantics { contentDescription = "$title. $detail" },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = VesqenSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(VesqenSpacing.xs))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = if (denied) onOpenAppSettings else onRequestMusicAccess,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("vesqen.permission.request"),
                    contentPadding = PaddingValues(horizontal = VesqenSpacing.sm),
                ) {
                    Text(
                        text = stringResource(
                            if (denied) R.string.destination_settings else R.string.allow_music_access_short,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
    val searchLabel = stringResource(R.string.search_local_music)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.md)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("vesqen.library.search"),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = searchLabel },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(start = VesqenSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(VesqenSpacing.xs))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (query.isBlank()) {
                                Text(
                                    text = searchLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                        if (query.isNotBlank()) {
                            IconButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.clear_search),
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun NotificationNotice(onOpenNotificationSettings: () -> Unit) {
    val warning = if (androidx.compose.foundation.isSystemInDarkTheme()) WarningAmberBright else WarningAmberDeep
    val title = stringResource(R.string.notifications_disabled_compact)
    val detail = stringResource(R.string.notifications_disabled)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xxs),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("vesqen.library.notifications-notice")
                .semantics { contentDescription = "$title. $detail" },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
            color = warning.copy(alpha = .12f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = VesqenSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = warning,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(VesqenSpacing.xs))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onOpenNotificationSettings,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("vesqen.library.notifications.settings"),
                    contentPadding = PaddingValues(horizontal = VesqenSpacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.destination_settings),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
            track.album.contains(normalizedQuery, ignoreCase = true) ||
            track.albumArtist.contains(normalizedQuery, ignoreCase = true) ||
            track.genre.contains(normalizedQuery, ignoreCase = true) ||
            track.folderName.contains(normalizedQuery, ignoreCase = true) ||
            track.fileName.contains(normalizedQuery, ignoreCase = true) ||
            track.codec.contains(normalizedQuery, ignoreCase = true)
    }
}
