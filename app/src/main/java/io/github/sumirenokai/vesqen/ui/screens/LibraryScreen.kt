package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import io.github.sumirenokai.vesqen.ui.components.AlbumArtwork
import io.github.sumirenokai.vesqen.ui.components.rememberAlbumBackground
import io.github.sumirenokai.vesqen.ui.formatDuration
import io.github.sumirenokai.vesqen.ui.theme.VesqenDataStyle
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.sumirenokai.vesqen.library.LibraryTitleIndex
import io.github.sumirenokai.vesqen.library.libraryTitleKeys
import io.github.sumirenokai.vesqen.library.projectLibraryTitleOrder
import io.github.sumirenokai.vesqen.ui.components.LibraryAlphabetIndex
import io.github.sumirenokai.vesqen.ui.components.LibraryTrackList
import io.github.sumirenokai.vesqen.ui.components.hairlineBelow
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy

private val LibraryHierarchyEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

// B · Paper & Sound shelf: 124 dp covers; a dozen recent albums is enough to browse sideways.
private val AlbumShelfCover = 124.dp
private const val AlbumShelfSize = 12

private sealed interface LibraryContentView {
    val depth: Int

    data object Root : LibraryContentView {
        override val depth = 0
    }

    data object Favorites : LibraryContentView {
        override val depth = 1
    }

    data class Collection(val key: String) : LibraryContentView {
        override val depth = 1
    }
}

private fun libraryHierarchyTransition(
    forward: Boolean,
    motionPolicy: VesqenMotionPolicy,
): ContentTransform {
    val durationMillis = if (motionPolicy.reduceMotion) motionPolicy.stateChangeMillis else 180
    if (motionPolicy.reduceMotion) {
        return fadeIn(tween(durationMillis)) togetherWith fadeOut(tween(durationMillis))
    }
    val direction = if (forward) 1 else -1
    return (
        fadeIn(tween(durationMillis, easing = LibraryHierarchyEasing)) +
            slideInHorizontally(tween(durationMillis, easing = LibraryHierarchyEasing)) {
                it * direction / 8
            }
        ) togetherWith (
        fadeOut(tween(durationMillis, easing = LibraryHierarchyEasing)) +
            slideOutHorizontally(tween(durationMillis, easing = LibraryHierarchyEasing)) {
                -it * direction / 12
            }
        )
}

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
    onSaveTrackOrder: suspend (Long?, List<Long>) -> Boolean = { _, _ -> false },
    onAddLibraryFolder: () -> Unit = {},
    onRemoveLibraryFolder: (String) -> Unit = {},
    onPauseLibraryScan: () -> Unit = {},
    onResumeLibraryScan: () -> Unit = {},
    motionPolicy: VesqenMotionPolicy? = null,
    onPageBackgroundChange: (Color) -> Unit = {},
) {
    val appliedMotionPolicy = motionPolicy ?: rememberVesqenMotionPolicy()
    val listStateHolder = rememberSaveableStateHolder()
    var query by rememberSaveable { mutableStateOf("") }
    // The search field opens from the title bar; a typed query keeps it open.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val showSearch = searchOpen || query.isNotEmpty()
    var detailsTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var browseModeName by rememberSaveable { mutableStateOf(LibraryBrowseMode.SONGS.name) }
    var sortOrderName by rememberSaveable { mutableStateOf(LibrarySortOrder.TITLE.name) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var favoriteCustomOrder by rememberSaveable { mutableStateOf(true) }
    var favoriteSortName by rememberSaveable { mutableStateOf(LibrarySortOrder.TITLE.name) }
    var selectedCollectionKey by rememberSaveable { mutableStateOf<String?>(null) }
    var orderTarget by remember { mutableStateOf<Pair<Long?, List<AudioTrack>>?>(null) }
    var orderSaving by remember { mutableStateOf(false) }
    var orderSaveFailed by remember { mutableStateOf(false) }
    val orderScope = rememberCoroutineScope()
    BackHandler(enabled = orderTarget != null || selectedCollectionKey != null || showSearch || favoritesOnly) {
        if (!orderSaving) {
            when {
                orderTarget != null -> orderTarget = null
                selectedCollectionKey != null -> selectedCollectionKey = null
                showSearch -> {
                    query = ""
                    searchOpen = false
                }
                else -> {
                    favoritesOnly = false
                    query = ""
                }
            }
        }
    }
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("library-browse", 0) }
    var alphabetEnabled by remember { mutableStateOf(preferences.getBoolean("alphabet-index", true)) }
    val sourceTracks = state.tracks
    val titleKeys = remember(sourceTracks) { libraryTitleKeys(sourceTracks) }
    val titleProjection by produceState<Pair<List<Pair<Long, String>>, LibraryTitleIndex>?>(null, titleKeys) {
        value = withContext(Dispatchers.Default) { titleKeys to LibraryTitleIndex.build(sourceTracks) }
    }
    val titleIndex = titleProjection?.second
    val titleIndexIsCurrent = titleProjection?.first == titleKeys
    var showCreatePlaylist by rememberSaveable { mutableStateOf(false) }
    var playlistToEdit by remember { mutableStateOf<LibraryPlaylist?>(null) }
    val browseMode = LibraryBrowseMode.valueOf(browseModeName)
    val rootSortOrder = LibrarySortOrder.valueOf(sortOrderName)
    val favoriteSortOrder = LibrarySortOrder.valueOf(favoriteSortName)
    val sortOrder = if (favoritesOnly) favoriteSortOrder else rootSortOrder
    val searchedTracks = remember(state.tracks, query) { filterTracks(state.tracks, query) }
    val favoriteTracks = remember(searchedTracks) {
        searchedTracks.filter(AudioTrack::isFavorite)
    }
    val rootVisibleTracks = remember(searchedTracks, rootSortOrder, titleIndex) {
        when {
            rootSortOrder == LibrarySortOrder.TITLE && titleIndex != null -> {
                projectLibraryTitleOrder(titleIndex.tracks, searchedTracks)
            }
            rootSortOrder == LibrarySortOrder.TITLE -> searchedTracks
            else -> sortLibraryTracks(searchedTracks, rootSortOrder)
        }
    }
    val favoriteVisibleTracks = remember(favoriteTracks, favoriteSortOrder, favoriteCustomOrder, titleIndex) {
        when {
            favoriteCustomOrder -> favoriteTracks.sortedWith(
                compareBy<AudioTrack> { it.favoritePosition ?: Long.MAX_VALUE }.thenBy { it.id },
            )
            favoriteSortOrder == LibrarySortOrder.TITLE && titleIndex != null -> {
                projectLibraryTitleOrder(titleIndex.tracks, favoriteTracks)
            }
            favoriteSortOrder == LibrarySortOrder.TITLE -> favoriteTracks
            else -> sortLibraryTracks(favoriteTracks, favoriteSortOrder)
        }
    }
    val visibleTracks = if (favoritesOnly) favoriteVisibleTracks else rootVisibleTracks
    val filteredTracks = if (favoritesOnly) favoriteTracks else searchedTracks
    val collections = remember(browseMode, searchedTracks, state.playlists, rootSortOrder) {
        sortLibraryCollections(
            buildLibraryCollections(browseMode, searchedTracks, state.playlists),
            rootSortOrder,
        )
    }
    val selectedCollection = remember(collections, selectedCollectionKey) {
        collections.firstOrNull { it.key == selectedCollectionKey }
    }
    val albumShelf = remember(collections, browseMode, query) {
        if (browseMode == LibraryBrowseMode.ALBUMS && query.isBlank() && collections.size > 1) {
            sortLibraryCollections(collections, LibrarySortOrder.RECENTLY_ADDED).take(AlbumShelfSize)
        } else {
            emptyList()
        }
    }
    // An open album tints the whole page from its cover, as Now does; every other view stays paper.
    val openAlbum = selectedCollection?.takeIf { browseMode == LibraryBrowseMode.ALBUMS && !favoritesOnly }
    val pageBackground = rememberAlbumBackground(
        track = openAlbum?.tracks?.firstOrNull(),
        dark = MaterialTheme.colorScheme.background.luminance() < .5f,
        motionPolicy = appliedMotionPolicy,
    )
    SideEffect { onPageBackgroundChange(pageBackground) }
    val isFavoriteList = favoritesOnly && browseMode == LibraryBrowseMode.SONGS && selectedCollection == null
    val isPlaylist = selectedCollection?.playlistId != null
    val contentView = selectedCollection?.let { LibraryContentView.Collection(it.key) }
        ?: if (isFavoriteList) LibraryContentView.Favorites else LibraryContentView.Root

    Column(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = favoritesOnly,
            transitionSpec = { libraryHierarchyTransition(targetState, appliedMotionPolicy) },
            label = "vesqen.library-header-hierarchy",
        ) { showingFavorites ->
            val browsing = state.tracks.isNotEmpty() && orderTarget == null
            LibraryHeader(
                favoritesOnly = showingFavorites,
                navigationEnabled = !orderSaving,
                searchOpen = showSearch,
                onToggleSearch = if (browsing) {
                    {
                        if (showSearch) query = ""
                        searchOpen = !showSearch
                    }
                } else {
                    null
                },
                sortButton = if (browsing) {
                    {
                        LibrarySortButton(
                            sortOrder = sortOrder,
                            onSortOrderChanged = {
                                if (favoritesOnly) { favoriteSortName = it.name; favoriteCustomOrder = false }
                                else sortOrderName = it.name
                            },
                            customOrderAvailable = isFavoriteList || isPlaylist,
                            customOrderActive = (isFavoriteList && favoriteCustomOrder) || isPlaylist,
                            manualOnly = isPlaylist,
                            onCustomOrder = { favoriteCustomOrder = true },
                            showAlphabetOption = !favoritesOnly && browseMode == LibraryBrowseMode.SONGS && selectedCollection == null,
                            alphabetEnabled = alphabetEnabled,
                            onToggleAlphabet = {
                                alphabetEnabled = !alphabetEnabled
                                preferences.edit().putBoolean("alphabet-index", alphabetEnabled).apply()
                            },
                        )
                    }
                } else {
                    null
                },
                onOpenFavorites = {
                    favoritesOnly = true
                    browseModeName = LibraryBrowseMode.SONGS.name
                    selectedCollectionKey = null
                    query = ""
                    searchOpen = false
                },
                onBack = {
                    if (orderTarget != null) {
                        orderTarget = null
                    } else {
                        favoritesOnly = false
                        query = ""
                        searchOpen = false
                    }
                },
                onAddLibraryFolder = onAddLibraryFolder,
                onRescan = onRescan,
                sourceActionsEnabled = !state.isLoading && orderTarget == null,
            )
        }
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
        if (state.catalogMutationFailed) {
            LibraryMutationFailureNotice()
        }
        if (state.tracks.isNotEmpty() && orderTarget == null) {
            if (showSearch) {
                LibrarySearchField(query = query, onQueryChange = { query = it }, requestFocus = query.isEmpty())
            }
            LibraryBrowseTabs(
                browseMode = browseMode,
                favoritesOnly = favoritesOnly,
                trackCount = filteredTracks.size,
                onBrowseModeChanged = { mode ->
                    favoritesOnly = false
                    browseModeName = mode.name
                    selectedCollectionKey = null
                },
            )
        }
        if (isFavoriteList || isPlaylist) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (orderTarget == null) {
                    TextButton(
                        onClick = {
                            orderSaveFailed = false
                            orderTarget = selectedCollection?.let { it.playlistId to it.tracks } ?: (null to visibleTracks)
                        },
                        enabled = query.isBlank() && (!favoritesOnly || favoriteCustomOrder) &&
                            (selectedCollection?.tracks ?: visibleTracks).size > 1,
                        modifier = Modifier.testTag("vesqen.library.edit-order"),
                    ) { Text(stringResource(R.string.edit_track_order)) }
                } else {
                    Text(stringResource(R.string.drag_track_order), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { orderTarget = null }, enabled = !orderSaving) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        onClick = {
                            val target = orderTarget ?: return@TextButton
                            orderSaving = true
                            orderSaveFailed = false
                            orderScope.launch {
                                try {
                                    if (onSaveTrackOrder(target.first, target.second.map(AudioTrack::id))) orderTarget = null
                                    else orderSaveFailed = true
                                } finally { orderSaving = false }
                            }
                        },
                        enabled = !orderSaving,
                        modifier = Modifier.testTag("vesqen.library.save-order"),
                    ) { Text(stringResource(R.string.save)) }
                }
            }
            if (orderTarget != null && orderSaveFailed) Text(
                stringResource(R.string.track_order_save_failed), Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = contentView,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    libraryHierarchyTransition(
                        forward = targetState.depth > initialState.depth,
                        motionPolicy = appliedMotionPolicy,
                    )
                },
                label = "vesqen.library-content-hierarchy",
            ) { activeView ->
                val activeFavorites = activeView == LibraryContentView.Favorites
                val activeVisibleTracks = if (activeFavorites) favoriteVisibleTracks else rootVisibleTracks
                val activeCollection = (activeView as? LibraryContentView.Collection)?.let { view ->
                    val current = collections.firstOrNull { it.key == view.key }
                    val transitionSnapshot = remember(view.key) { current }
                    // Keep the outgoing collection alive until AnimatedContent disposes its
                    // provider. Otherwise a disappearing collection can fall through to the
                    // incoming root list and use the same saveable-state key twice.
                    current ?: transitionSnapshot
                }
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

                    activeFavorites && query.isBlank() && activeVisibleTracks.isEmpty() -> VesqenEmptyState(
                        title = stringResource(R.string.library_favorites),
                        body = stringResource(R.string.library_favorites_empty),
                        actionLabel = stringResource(R.string.show_all_music),
                        onAction = { favoritesOnly = false },
                        modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
                    )

                    activeVisibleTracks.isEmpty() && browseMode != LibraryBrowseMode.PLAYLISTS -> VesqenEmptyState(
                        title = stringResource(R.string.no_search_results),
                        body = stringResource(R.string.no_search_results_body),
                        actionLabel = stringResource(R.string.clear_search),
                        onAction = { query = "" },
                        modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
                    )

                    activeCollection != null && browseMode == LibraryBrowseMode.ALBUMS -> {
                        listStateHolder.SaveableStateProvider(key = "collection:${activeCollection.key}") {
                            AlbumPage(
                                album = activeCollection,
                                playback = playback,
                                onBack = { selectedCollectionKey = null },
                                onPlayQueue = onPlayQueue,
                                onTrackMore = { detailsTrack = it },
                            )
                        }
                    }

                    activeCollection != null -> listStateHolder.SaveableStateProvider(
                        key = "collection:${activeCollection.key}",
                    ) {
                        CollectionTrackList(
                            collection = activeCollection.copy(
                                tracks = orderTarget?.second ?: activeCollection.tracks,
                            ),
                            playback = playback,
                            onBack = {
                                if (orderTarget != null) {
                                    if (!orderSaving) orderTarget = null
                                } else {
                                    selectedCollectionKey = null
                                }
                            },
                            onPlayQueue = onPlayQueue,
                            onTrackSelected = { track ->
                                onPlayQueue(activeCollection.tracks, activeCollection.tracks.indexOf(track))
                            },
                            onTrackMore = { detailsTrack = it },
                            editing = orderTarget != null,
                            saving = orderSaving,
                            onReorder = { tracks -> orderTarget = orderTarget?.copy(second = tracks) },
                            onEditPlaylist = activeCollection.playlistId
                                ?.takeIf { orderTarget == null }
                                ?.let { playlistId ->
                                    { playlistToEdit = state.playlists.firstOrNull { it.id == playlistId } }
                                },
                        )
                    }

                    browseMode == LibraryBrowseMode.SONGS -> listStateHolder.SaveableStateProvider(
                        key = if (activeFavorites) "tracks:favorites" else "tracks:root",
                    ) {
                        LibraryTrackList(
                            tracks = orderTarget?.second ?: activeVisibleTracks,
                            editing = orderTarget != null,
                            saving = orderSaving,
                            onReorder = { tracks -> orderTarget = orderTarget?.copy(second = tracks) },
                            currentTrackId = playback.trackId,
                            isPlaying = playback.isPlaying,
                            alphabetSections = if (
                                titleIndexIsCurrent && alphabetEnabled && !activeFavorites &&
                                query.isBlank() && rootSortOrder == LibrarySortOrder.TITLE
                            ) titleIndex?.sections.orEmpty() else emptyMap(),
                            onTrackSelected = { track ->
                                onPlayQueue(activeVisibleTracks, activeVisibleTracks.indexOf(track))
                            },
                            onTrackMore = { detailsTrack = it },
                        )
                    }

                    else -> listStateHolder.SaveableStateProvider(key = "collections:${browseMode.name}") {
                        CollectionList(
                            mode = browseMode,
                            collections = collections,
                            shelf = albumShelf,
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
        }
    }

    detailsTrack?.let { track ->
        val playlistId = selectedCollection?.playlistId
        val playlistTrackIndex = selectedCollection?.tracks?.indexOfFirst { it.id == track.id } ?: -1
        val storedPlaylistOrder = state.playlists.firstOrNull { it.id == playlistId }?.trackIds.orEmpty()
        TrackDetailsSheet(
            track = track,
            playlists = state.playlists,
            onDismiss = { detailsTrack = null },
            onPlay = {
                if (selectedCollection != null) {
                    onPlayQueue(selectedCollection.tracks, selectedCollection.tracks.indexOf(track))
                } else {
                    val index = visibleTracks.indexOfFirst { it.id == track.id }
                    if (index >= 0) onPlayQueue(visibleTracks, index)
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
            queueActionsEnabled = playback.isControllerReady,
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
            onMoveUp = if (playlistId != null && query.isBlank() && !favoritesOnly && playlistTrackIndex > 0) {
                {
                    onMovePlaylistTrack(playlistId, storedPlaylistOrder.indexOf(track.id), storedPlaylistOrder.indexOf(selectedCollection.tracks[playlistTrackIndex - 1].id))
                    detailsTrack = null
                }
            } else null,
            onMoveDown = if (
                playlistId != null && query.isBlank() && !favoritesOnly &&
                playlistTrackIndex >= 0 &&
                playlistTrackIndex < selectedCollection.tracks.lastIndex
            ) {
                {
                    onMovePlaylistTrack(playlistId, storedPlaylistOrder.indexOf(track.id), storedPlaylistOrder.indexOf(selectedCollection.tracks[playlistTrackIndex + 1].id))
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

// B · Paper & Sound §5: text tabs 20 dp apart, the selected one in ink over a 2 dp ink rule, the
// rest muted; no pills or filled blocks. The row scrolls sideways and keeps the selection in view.
@Composable
private fun LibraryBrowseTabs(
    browseMode: LibraryBrowseMode,
    favoritesOnly: Boolean,
    trackCount: Int,
    onBrowseModeChanged: (LibraryBrowseMode) -> Unit,
) {
    if (favoritesOnly) {
        Text(
            pluralStringResource(R.plurals.library_song_total, trackCount, trackCount),
            modifier = Modifier.padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.xs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val hairline = LocalVesqenColors.current.hairline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val y = size.height - .5.dp.toPx()
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = VesqenSpacing.lg)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        LibraryBrowseMode.entries.forEach { mode ->
            LibraryBrowseTab(
                label = stringResource(mode.labelResource()),
                selected = mode == browseMode,
                onClick = { onBrowseModeChanged(mode) },
                modifier = Modifier.testTag("vesqen.library.mode.${mode.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun LibraryBrowseTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) requester.bringIntoView()
    }
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .bringIntoViewRequester(requester)
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .drawBehind {
                if (selected) {
                    val rule = 2.dp.toPx()
                    drawRect(ink, topLeft = Offset(0f, size.height - rule), size = Size(size.width, rule))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (selected) ink else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun LibrarySortButton(
    sortOrder: LibrarySortOrder,
    onSortOrderChanged: (LibrarySortOrder) -> Unit,
    customOrderAvailable: Boolean,
    customOrderActive: Boolean,
    manualOnly: Boolean,
    onCustomOrder: () -> Unit,
    showAlphabetOption: Boolean,
    alphabetEnabled: Boolean,
    onToggleAlphabet: () -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { showSortMenu = true },
            modifier = Modifier.size(48.dp).testTag("vesqen.library.sort"),
        ) {
            Icon(
                imageVector = Icons.Outlined.SwapVert,
                contentDescription = stringResource(R.string.sort_library),
            )
        }
        DropdownMenu(
            expanded = showSortMenu,
            onDismissRequest = { showSortMenu = false },
        ) {
            if (customOrderAvailable) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.custom_track_order)) },
                    onClick = { onCustomOrder(); showSortMenu = false },
                    leadingIcon = if (customOrderActive) { { Icon(Icons.Filled.MusicNote, null) } } else null,
                )
            }
            if (showAlphabetOption) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (alphabetEnabled) R.string.hide_alphabet_index else R.string.show_alphabet_index)) },
                    onClick = { onToggleAlphabet(); showSortMenu = false },
                )
            }
            LibrarySortOrder.entries.filter { !manualOnly }.forEach { order ->
                DropdownMenuItem(
                    text = { Text(stringResource(order.labelResource())) },
                    onClick = {
                        onSortOrderChanged(order)
                        showSortMenu = false
                    },
                    leadingIcon = if (order == sortOrder && !customOrderActive) {
                        { Icon(Icons.Filled.MusicNote, contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}

// B · Paper & Sound: list views share one row, a serif title over a muted meta line with a hairline
// below and a chevron at the end, inside the 24 dp page margin. Albums add the recently-added shelf
// above the full list; the shelf scrolls sideways edge to edge, so rows carry the margin themselves.
@Composable
private fun CollectionList(
    mode: LibraryBrowseMode,
    collections: List<LibraryCollection>,
    shelf: List<LibraryCollection>,
    onCollectionSelected: (LibraryCollection) -> Unit,
    onCreatePlaylist: (() -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = VesqenSpacing.xs, bottom = VesqenSpacing.md),
    ) {
        if (shelf.isNotEmpty()) {
            item(key = "shelf-label", contentType = "label") {
                LibrarySectionLabel(stringResource(R.string.library_recently_added))
            }
            item(key = "shelf", contentType = "shelf") {
                LazyRow(
                    modifier = Modifier.testTag("vesqen.library.shelf"),
                    contentPadding = PaddingValues(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(shelf, key = { "shelf:${it.key}" }) { album ->
                        AlbumShelfItem(album = album, onClick = { onCollectionSelected(album) })
                    }
                }
            }
            item(key = "all-label", contentType = "label") {
                LibrarySectionLabel(stringResource(R.string.library_all_albums))
            }
        }
        onCreatePlaylist?.let { create ->
            item(key = "create-playlist") {
                Row(
                    modifier = Modifier
                        .padding(horizontal = VesqenSpacing.lg)
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .hairlineBelow()
                        .clickable(onClick = create)
                        .testTag("vesqen.library.playlist.create"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(VesqenSpacing.sm))
                    Text(
                        stringResource(R.string.create_playlist),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
private fun LibrarySectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = VesqenSpacing.lg, end = VesqenSpacing.lg, top = VesqenSpacing.md, bottom = VesqenSpacing.xxs),
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AlbumShelfItem(album: LibraryCollection, onClick: () -> Unit) {
    val shadow = LocalVesqenColors.current.shadow
    Column(
        modifier = Modifier
            .width(AlbumShelfCover)
            .clickable(onClick = onClick)
            .testTag("vesqen.library.shelf.${album.key}"),
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
    ) {
        AlbumArtwork(
            track = album.tracks.first(),
            targetSize = AlbumShelfCover,
            modifier = Modifier
                .size(AlbumShelfCover)
                .shadow(2.dp, RoundedCornerShape(VesqenRadii.album), ambientColor = shadow, spotColor = shadow),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = album.title.ifBlank { stringResource(R.string.unknown_album) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.subtitle.ifBlank { stringResource(R.string.unknown_artist) },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
    Row(
        modifier = Modifier
            .padding(horizontal = VesqenSpacing.lg)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .hairlineBelow()
            .clickable(onClick = onClick)
            .padding(vertical = VesqenSpacing.xs)
            .testTag("vesqen.library.collection.${collection.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mode == LibraryBrowseMode.ALBUMS) {
            collection.tracks.firstOrNull()?.let { track ->
                AlbumArtwork(track = track, targetSize = 48.dp, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(VesqenSpacing.sm))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
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
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// B · Paper & Sound album page: the cover above a paper-raised card (18 dp corners) with the serif
// title, "artist · format" and the Moss play button, then numbered 48 dp rows with tabular numbers.
// The page itself takes the cover tint (see onPageBackgroundChange).
@Composable
private fun AlbumPage(
    album: LibraryCollection,
    playback: PlaybackSnapshot,
    onBack: () -> Unit,
    onPlayQueue: (List<AudioTrack>, Int) -> Unit,
    onTrackMore: (AudioTrack) -> Unit,
) {
    val card = MaterialTheme.colorScheme.surface
    val shadow = LocalVesqenColors.current.shadow
    val title = album.title.ifBlank { stringResource(R.string.unknown_album) }
    val artist = album.subtitle.ifBlank { stringResource(R.string.unknown_artist) }
    val meta = remember(artist, album.tracks) {
        listOfNotNull(artist, nowFormatSummary(album.tracks.firstOrNull())).joinToString(" · ")
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("vesqen.library.album"),
        contentPadding = PaddingValues(bottom = VesqenSpacing.md),
    ) {
        item(key = "back") {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = VesqenSpacing.xxs)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        }
        album.tracks.firstOrNull()?.let { cover ->
            item(key = "cover") {
                AlbumArtwork(
                    track = cover,
                    targetSize = AlbumShelfCover,
                    modifier = Modifier
                        .padding(start = VesqenSpacing.lg, top = VesqenSpacing.xxs, bottom = VesqenSpacing.md)
                        .size(AlbumShelfCover)
                        .shadow(12.dp, RoundedCornerShape(VesqenRadii.album), ambientColor = shadow, spotColor = shadow),
                )
            }
        }
        item(key = "card-head") {
            Row(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .background(card, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .padding(start = VesqenSpacing.md, end = VesqenSpacing.md, top = 18.dp, bottom = VesqenSpacing.sm),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs)) {
                    Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("vesqen.library.album.title"))
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledIconButton(
                    onClick = { onPlayQueue(album.tracks, 0) },
                    enabled = album.tracks.isNotEmpty(),
                    modifier = Modifier.size(48.dp).testTag("vesqen.library.album.play"),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play_album, title))
                }
            }
        }
        itemsIndexed(album.tracks, key = { _, track -> track.id }, contentType = { _, _ -> "album-track" }) { index, track ->
            AlbumTrackRow(
                number = track.trackNumber ?: (index + 1),
                track = track,
                isCurrent = track.id == playback.trackId,
                onPlay = { onPlayQueue(album.tracks, index) },
                onMore = { onTrackMore(track) },
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .background(card)
                    .padding(horizontal = VesqenSpacing.md),
            )
        }
        item(key = "card-foot") {
            Spacer(
                Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .height(VesqenSpacing.sm)
                    .background(card, RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)),
            )
        }
    }
}

@Composable
private fun AlbumTrackRow(
    number: Int,
    track: AudioTrack,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = track.title.ifBlank { stringResource(R.string.unknown_title) }
    val subtitle = remember(track.artist, track.album) { track.displaySubtitle() }
        .ifBlank { stringResource(R.string.unknown_artist) }
    val accessibilityLabel = stringResource(R.string.track_row_description, title, subtitle)
    val hairline = LocalVesqenColors.current.hairline
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .drawBehind { drawLine(hairline, Offset(0f, .5.dp.toPx()), Offset(size.width, .5.dp.toPx()), 1.dp.toPx()) }
            .testTag("vesqen.library.track.${track.id}")
            .semantics { contentDescription = accessibilityLabel }
            .clickable(onClick = onPlay)
            .padding(start = VesqenSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = String.format(Locale.ROOT, "%02d", number),
            modifier = Modifier.width(20.dp),
            style = VesqenDataStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatDuration(track.durationMs),
            style = VesqenDataStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = onMore,
            modifier = Modifier.size(40.dp).testTag("vesqen.library.track.${track.id}.more"),
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_track_actions, title))
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
    editing: Boolean = false,
    saving: Boolean = false,
    onReorder: (List<AudioTrack>) -> Unit = {},
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
                style = MaterialTheme.typography.headlineMedium,
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
                enabled = collection.tracks.isNotEmpty() && !editing,
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
                LibraryTrackList(collection.tracks, playback.trackId, playback.isPlaying, onTrackSelected, onTrackMore,
                    editing = editing, saving = saving, onReorder = onReorder)
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

// B · Paper & Sound §5: a serif page title with line icons for search, sort, favorites and the
// source menu. Narrow windows and large text step the title down so every action keeps its 48 dp
// target beside it.
@Composable
private fun LibraryHeader(
    favoritesOnly: Boolean,
    navigationEnabled: Boolean,
    searchOpen: Boolean,
    onToggleSearch: (() -> Unit)?,
    sortButton: (@Composable () -> Unit)?,
    onOpenFavorites: () -> Unit,
    onBack: () -> Unit,
    onAddLibraryFolder: () -> Unit,
    onRescan: () -> Unit,
    sourceActionsEnabled: Boolean,
) {
    var showLibraryMenu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compactTitle = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(
                    start = if (favoritesOnly) VesqenSpacing.xxs else VesqenSpacing.lg,
                    end = VesqenSpacing.xxs,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (favoritesOnly) IconButton(
                onClick = onBack,
                enabled = navigationEnabled,
                modifier = Modifier.size(48.dp).testTag("vesqen.library.back"),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.show_all_music)) }
            Text(
                stringResource(if (favoritesOnly) R.string.library_favorites else R.string.destination_library),
                modifier = Modifier
                    .weight(1f)
                    .testTag(if (favoritesOnly) "vesqen.library.title.favorites" else "vesqen.library.title.root"),
                style = if (compactTitle) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displayMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            onToggleSearch?.let { toggle ->
                IconButton(
                    onClick = toggle,
                    enabled = navigationEnabled,
                    modifier = Modifier.size(48.dp).testTag("vesqen.library.search-toggle"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = stringResource(if (searchOpen) R.string.clear_search else R.string.search_local_music),
                    )
                }
            }
            sortButton?.invoke()
            if (!favoritesOnly) {
                IconButton(
                    onClick = onOpenFavorites,
                    enabled = navigationEnabled,
                    modifier = Modifier.size(48.dp).testTag("vesqen.library.favorites"),
                ) { Icon(Icons.Outlined.FavoriteBorder, stringResource(R.string.library_favorites)) }
                Box {
                    IconButton(
                        onClick = { showLibraryMenu = true },
                        enabled = sourceActionsEnabled,
                        modifier = Modifier.size(48.dp).testTag("vesqen.library.menu"),
                    ) { Icon(Icons.Filled.MoreVert, stringResource(R.string.library_actions)) }
                    DropdownMenu(expanded = showLibraryMenu, onDismissRequest = { showLibraryMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_music_folder)) },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                            onClick = { showLibraryMenu = false; onAddLibraryFolder() },
                            modifier = Modifier.testTag("vesqen.library.add-folder"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rescan_library)) },
                            leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            onClick = { showLibraryMenu = false; onRescan() },
                            modifier = Modifier.testTag("vesqen.library.rescan"),
                        )
                    }
                }
            }
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
private fun LibraryMutationFailureNotice() {
    val message = stringResource(R.string.library_change_save_failed)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xxs)
            .heightIn(min = 48.dp)
            .testTag("vesqen.library.mutation-failed")
            .semantics { contentDescription = message },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = VesqenSpacing.sm, vertical = VesqenSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(VesqenSpacing.xs))
            Text(message, style = MaterialTheme.typography.bodyMedium)
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
private fun LibrarySearchField(query: String, onQueryChange: (String) -> Unit, requestFocus: Boolean) {
    val searchLabel = stringResource(R.string.search_local_music)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (requestFocus) focusRequester.requestFocus()
    }
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
                    .focusRequester(focusRequester)
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
    val warning = io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors.current.warning
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
