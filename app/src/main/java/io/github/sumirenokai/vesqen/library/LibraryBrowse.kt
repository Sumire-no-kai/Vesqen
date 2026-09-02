package io.github.sumirenokai.vesqen.library

enum class LibraryBrowseMode {
    SONGS,
    ALBUMS,
    ARTISTS,
    FOLDERS,
    GENRES,
    PLAYLISTS,
}

enum class LibrarySortOrder {
    TITLE,
    ARTIST,
    ALBUM,
    RECENTLY_ADDED,
    RECENTLY_PLAYED,
    MOST_PLAYED,
}

data class LibraryCollection(
    val key: String,
    val title: String,
    val subtitle: String,
    val tracks: List<AudioTrack>,
    val playlistId: Long? = null,
)

fun sortLibraryTracks(
    tracks: List<AudioTrack>,
    sortOrder: LibrarySortOrder,
): List<AudioTrack> = when (sortOrder) {
    LibrarySortOrder.TITLE -> tracks.sortedWith(compareBy<AudioTrack> { it.title.lowercase() })
    LibrarySortOrder.ARTIST -> tracks.sortedWith(
        compareBy<AudioTrack> { it.artist.lowercase() }
            .thenBy { it.album.lowercase() }
            .thenBy { it.title.lowercase() },
    )
    LibrarySortOrder.ALBUM -> tracks.sortedWith(
        compareBy<AudioTrack> { it.album.lowercase() }
            .thenBy { it.discNumber ?: Int.MAX_VALUE }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            .thenBy { it.title.lowercase() },
    )
    LibrarySortOrder.RECENTLY_ADDED -> tracks.sortedWith(
        compareByDescending<AudioTrack> { it.dateModifiedSeconds }
            .thenBy { it.title.lowercase() },
    )
    LibrarySortOrder.RECENTLY_PLAYED -> tracks.sortedWith(
        compareByDescending<AudioTrack> { it.lastPlayedAtMs }
            .thenBy { it.title.lowercase() },
    )
    LibrarySortOrder.MOST_PLAYED -> tracks.sortedWith(
        compareByDescending<AudioTrack> { it.playCount }
            .thenByDescending { it.lastPlayedAtMs }
            .thenBy { it.title.lowercase() },
    )
}

fun buildLibraryCollections(
    mode: LibraryBrowseMode,
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist>,
): List<LibraryCollection> = when (mode) {
    LibraryBrowseMode.SONGS -> emptyList()
    LibraryBrowseMode.ALBUMS -> tracks.groupedCollections(
        key = { track -> "${track.albumArtist.trim()}::${track.album.trim()}" },
        title = AudioTrack::album,
        subtitle = { group -> group.firstNotBlank(AudioTrack::albumArtist, AudioTrack::artist) },
        trackOrder = albumTrackComparator,
    )
    LibraryBrowseMode.ARTISTS -> tracks.groupedCollections(
        key = { track -> track.artist.ifBlank { track.albumArtist } },
        title = { track -> track.artist.ifBlank { track.albumArtist } },
        subtitle = { group -> group.map(AudioTrack::album).filter(String::isNotBlank).distinct().size.toString() },
        trackOrder = compareBy<AudioTrack> { it.album.lowercase() }
            .then(albumTrackComparator),
    )
    LibraryBrowseMode.FOLDERS -> tracks.groupedCollections(
        key = AudioTrack::folderName,
        title = AudioTrack::folderName,
        subtitle = { group -> group.size.toString() },
        trackOrder = compareBy { it.fileName.lowercase() },
    )
    LibraryBrowseMode.GENRES -> tracks.groupedCollections(
        key = AudioTrack::genre,
        title = AudioTrack::genre,
        subtitle = { group -> group.size.toString() },
        trackOrder = compareBy<AudioTrack> { it.artist.lowercase() }
            .thenBy { it.title.lowercase() },
    )
    LibraryBrowseMode.PLAYLISTS -> {
        val tracksById = tracks.associateBy(AudioTrack::id)
        playlists.map { playlist ->
            LibraryCollection(
                key = playlist.id.toString(),
                title = playlist.name,
                subtitle = playlist.trackCount.toString(),
                tracks = playlist.trackIds.mapNotNull(tracksById::get),
                playlistId = playlist.id,
            )
        }
    }
}

fun sortLibraryCollections(
    collections: List<LibraryCollection>,
    sortOrder: LibrarySortOrder,
): List<LibraryCollection> = when (sortOrder) {
    LibrarySortOrder.TITLE,
    LibrarySortOrder.ALBUM,
    -> collections.sortedBy { it.title.lowercase() }
    LibrarySortOrder.ARTIST -> collections.sortedWith(
        compareBy<LibraryCollection> { it.subtitle.lowercase() }
            .thenBy { it.title.lowercase() },
    )
    LibrarySortOrder.RECENTLY_ADDED -> collections.sortedWith(
        compareByDescending<LibraryCollection> { collection ->
            collection.tracks.maxOfOrNull(AudioTrack::dateModifiedSeconds) ?: 0
        }.thenBy { it.title.lowercase() },
    )
    LibrarySortOrder.RECENTLY_PLAYED -> collections.sortedWith(
        compareByDescending<LibraryCollection> { collection ->
            collection.tracks.maxOfOrNull(AudioTrack::lastPlayedAtMs) ?: 0
        }.thenBy { it.title.lowercase() },
    )
    LibrarySortOrder.MOST_PLAYED -> collections.sortedWith(
        compareByDescending<LibraryCollection> { collection -> collection.tracks.sumOf(AudioTrack::playCount) }
            .thenBy { it.title.lowercase() },
    )
}

private inline fun List<AudioTrack>.groupedCollections(
    crossinline key: (AudioTrack) -> String,
    crossinline title: (AudioTrack) -> String,
    crossinline subtitle: (List<AudioTrack>) -> String,
    trackOrder: Comparator<AudioTrack>,
): List<LibraryCollection> = groupBy(key)
    .map { (groupKey, groupTracks) ->
        LibraryCollection(
            key = groupKey,
            title = title(groupTracks.first()),
            subtitle = subtitle(groupTracks),
            tracks = groupTracks.sortedWith(trackOrder),
        )
    }
    .sortedWith(compareBy { it.title.lowercase() })

private fun List<AudioTrack>.firstNotBlank(vararg selectors: (AudioTrack) -> String): String =
    asSequence().flatMap { track -> selectors.asSequence().map { it(track) } }.firstOrNull(String::isNotBlank).orEmpty()

private val albumTrackComparator = compareBy<AudioTrack> { it.discNumber ?: Int.MAX_VALUE }
    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
    .thenBy { it.title.lowercase() }
