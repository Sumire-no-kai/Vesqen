package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.sumirenokai.vesqen.library.ArtistNames
import io.github.sumirenokai.vesqen.library.ArtistSplitSettings
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryBrowseMode
import io.github.sumirenokai.vesqen.library.LibraryCollection
import io.github.sumirenokai.vesqen.library.LibraryNameOrder
import io.github.sumirenokai.vesqen.library.LibraryPlaylist
import io.github.sumirenokai.vesqen.library.LibrarySortOrder
import io.github.sumirenokai.vesqen.library.LibraryTitleIndex
import io.github.sumirenokai.vesqen.library.buildLibraryCollections
import io.github.sumirenokai.vesqen.library.libraryTitleKeys
import io.github.sumirenokai.vesqen.library.projectLibraryTitleOrder
import io.github.sumirenokai.vesqen.library.sortLibraryCollections
import io.github.sumirenokai.vesqen.library.sortLibraryTracks
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

private const val AlbumShelfSize = 12

/** One thread at a time: [ArtistNames] keeps a cache, and a list must not overtake a newer one. */
private val LibraryProjectionDispatcher = Dispatchers.Default.limitedParallelism(1)

/** False while the Library shows older lists than it asked for. UI tests wait on it (#223). */
internal val libraryProjectionSettled = AtomicBoolean(true)

/** The title order, with the keys that tell a play count change from a rename. */
internal class LibraryTitleProjection(
    val keys: List<Pair<Long, String>>,
    val index: LibraryTitleIndex,
)

/** What the Library's lists are worked out from. */
internal data class LibraryProjectionInput(
    val tracks: List<AudioTrack>,
    val playlists: List<LibraryPlaylist>,
    val query: String,
    val browseMode: LibraryBrowseMode,
    val rootSortOrder: LibrarySortOrder,
    val favoriteSortOrder: LibrarySortOrder,
    val favoriteCustomOrder: Boolean,
    val title: LibraryTitleProjection?,
    val artistNames: ArtistNames,
)

/** One consistent set of lists. The page reads its mode and query from [input], so they always match. */
internal class LibraryProjection(
    val input: LibraryProjectionInput,
    val searchedTracks: List<AudioTrack>,
    val favoriteTracks: List<AudioTrack>,
    val rootVisibleTracks: List<AudioTrack>,
    val sortedSongs: List<AudioTrack>?,
    val favoriteVisibleTracks: List<AudioTrack>,
    val collections: List<LibraryCollection>,
    val albumShelf: List<LibraryCollection>,
    val alphabetSections: Map<String, Int>,
)

/**
 * Kept outside the Library's composition, so a return to the page shows its lists at once and does
 * not work them out again (#223).
 */
@Stable
class LibraryProjections internal constructor(
    internal val dispatcher: CoroutineDispatcher,
) {
    constructor() : this(LibraryProjectionDispatcher)

    internal var title by mutableStateOf<LibraryTitleProjection?>(null)
    internal var shown by mutableStateOf<LibraryProjection?>(null)
    private var artistNames: Pair<ArtistSplitSettings, ArtistNames>? = null

    /** One per setting, so a search, a sort or a new play count regroups without splitting every tag again (#168). */
    internal fun artistNames(settings: ArtistSplitSettings): ArtistNames =
        artistNames?.takeIf { it.first == settings }?.second
            ?: ArtistNames(settings).also { artistNames = settings to it }
}

/**
 * Searching, sorting and grouping cost several frames from about 10,000 tracks, so they leave the
 * main thread (#223). Until the new lists are ready the page keeps the last ones whole: an empty list
 * in between would show "no results" and lose the scroll position. Only a page that has nothing to
 * show yet works them out here, as it would otherwise open blank.
 *
 * A computation always finishes and is shown, so a list that keeps changing during a scan still
 * moves on. Inputs that it overtook while it ran are skipped.
 */
@Composable
internal fun rememberLibraryProjection(
    projections: LibraryProjections,
    input: LibraryProjectionInput,
): LibraryProjection {
    val first = remember(projections) { projections.shown ?: projectLibrary(input, previous = null) }
    val latestInput by rememberUpdatedState(input)
    LaunchedEffect(projections) {
        if (projections.shown == null) projections.shown = first
        snapshotFlow { latestInput }.conflate().collect { requested ->
            val previous = projections.shown
            if (previous?.input != requested) {
                projections.shown = withContext(projections.dispatcher) { projectLibrary(requested, previous) }
            }
        }
    }
    return projections.shown ?: first
}

/**
 * Each list is reused from [previous] while what it depends on is unchanged, so a new mode or sort
 * costs only its own step. [names] is the Songs index order unless a JVM test, which has no ICU,
 * supplies its own.
 */
internal fun projectLibrary(
    input: LibraryProjectionInput,
    previous: LibraryProjection?,
    names: () -> Comparator<String> = ::LibraryNameOrder,
): LibraryProjection {
    // Measures each name once for all the lists of this run; Title order alone needs none.
    val nameOrder by lazy(LazyThreadSafetyMode.NONE, names)
    val titleOrder = input.title?.index?.tracks

    val searchedTracks = previous
        ?.takeIf { it.input.tracks === input.tracks && it.input.query == input.query }
        ?.searchedTracks
        ?: filterTracks(input.tracks, input.query)
    val sameSearch = previous?.takeIf { it.searchedTracks === searchedTracks }
    val sameSearchAndTitle = sameSearch?.takeIf { it.input.title === input.title }

    val favoriteTracks = sameSearch?.favoriteTracks ?: searchedTracks.filter(AudioTrack::isFavorite)
    // Only Songs shows this order, and a grouped mode reads only whether anything was found. The sorted
    // list stays through a look at a grouped mode, so going back to Songs costs nothing.
    val sortedSongs = sameSearchAndTitle
        ?.takeIf { it.input.rootSortOrder == input.rootSortOrder }
        ?.sortedSongs
        ?: when {
            input.browseMode != LibraryBrowseMode.SONGS -> null
            input.rootSortOrder == LibrarySortOrder.TITLE && titleOrder != null ->
                projectLibraryTitleOrder(titleOrder, searchedTracks)
            input.rootSortOrder == LibrarySortOrder.TITLE -> searchedTracks
            else -> sortLibraryTracks(searchedTracks, input.rootSortOrder, nameOrder)
        }
    val rootVisibleTracks = sortedSongs?.takeIf { input.browseMode == LibraryBrowseMode.SONGS } ?: searchedTracks
    val favoriteVisibleTracks = sameSearchAndTitle
        ?.takeIf {
            it.input.favoriteSortOrder == input.favoriteSortOrder &&
                it.input.favoriteCustomOrder == input.favoriteCustomOrder
        }
        ?.favoriteVisibleTracks
        ?: when {
            input.favoriteCustomOrder -> favoriteTracks.sortedWith(
                compareBy<AudioTrack> { it.favoritePosition ?: Long.MAX_VALUE }.thenBy { it.id },
            )
            input.favoriteSortOrder == LibrarySortOrder.TITLE && titleOrder != null ->
                projectLibraryTitleOrder(titleOrder, favoriteTracks)
            input.favoriteSortOrder == LibrarySortOrder.TITLE -> favoriteTracks
            else -> sortLibraryTracks(favoriteTracks, input.favoriteSortOrder, nameOrder)
        }
    val sameCollections = sameSearch?.takeIf {
        it.input.browseMode == input.browseMode && it.input.playlists == input.playlists &&
            it.input.rootSortOrder == input.rootSortOrder && it.input.artistNames === input.artistNames
    }
    val collections = sameCollections?.collections ?: when (input.browseMode) {
        LibraryBrowseMode.SONGS -> emptyList()
        else -> sortLibraryCollections(
            buildLibraryCollections(input.browseMode, searchedTracks, input.playlists, nameOrder, input.artistNames),
            input.rootSortOrder,
            nameOrder,
        )
    }
    val albumShelf = sameCollections
        ?.takeIf { it.input.query.isBlank() == input.query.isBlank() }
        ?.albumShelf
        ?: if (input.browseMode == LibraryBrowseMode.ALBUMS && input.query.isBlank() && collections.size > 1) {
            sortLibraryCollections(collections, LibrarySortOrder.RECENTLY_ADDED, nameOrder).take(AlbumShelfSize)
        } else {
            emptyList()
        }
    // An index built for other tracks has other positions, so its letters would jump to the wrong rows.
    val alphabetSections = input.title
        ?.takeIf { input.query.isBlank() && input.rootSortOrder == LibrarySortOrder.TITLE }
        ?.takeIf { it.keys == libraryTitleKeys(input.tracks) }
        ?.index?.sections
        .orEmpty()
    return LibraryProjection(
        input = input,
        searchedTracks = searchedTracks,
        favoriteTracks = favoriteTracks,
        rootVisibleTracks = rootVisibleTracks,
        sortedSongs = sortedSongs,
        favoriteVisibleTracks = favoriteVisibleTracks,
        collections = collections,
        albumShelf = albumShelf,
        alphabetSections = alphabetSections,
    )
}
