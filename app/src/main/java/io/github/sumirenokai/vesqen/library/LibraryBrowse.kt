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

/** [names] is the Songs index order unless a JVM test, which has no ICU, supplies its own. */
fun sortLibraryTracks(
    tracks: List<AudioTrack>,
    sortOrder: LibrarySortOrder,
    names: Comparator<String> = LibraryNameOrder(),
): List<AudioTrack> {
    return when (sortOrder) {
        LibrarySortOrder.TITLE -> tracks.sortedWith(compareBy(names) { it.title })
        LibrarySortOrder.ARTIST -> tracks.sortedWith(
            compareBy<AudioTrack, String>(names) { it.artist }
                .thenBy(names) { it.album }
                .thenBy(names) { it.title },
        )
        LibrarySortOrder.ALBUM -> tracks.sortedWith(
            compareBy<AudioTrack, String>(names) { it.album }.then(albumTrackOrder(names)),
        )
        LibrarySortOrder.RECENTLY_ADDED -> tracks.sortedWith(
            compareByDescending<AudioTrack> { it.dateModifiedSeconds }
                .thenBy(names) { it.title },
        )
        LibrarySortOrder.RECENTLY_PLAYED -> tracks.sortedWith(
            compareByDescending<AudioTrack> { it.lastPlayedAtMs }
                .thenBy(names) { it.title },
        )
        LibrarySortOrder.MOST_PLAYED -> tracks.sortedWith(
            compareByDescending<AudioTrack> { it.playCount }
                .thenByDescending { it.lastPlayedAtMs }
                .thenBy(names) { it.title },
        )
    }
}

fun buildLibraryCollections(
    mode: LibraryBrowseMode,
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist>,
    names: Comparator<String> = LibraryNameOrder(),
): List<LibraryCollection> {
    return when (mode) {
        LibraryBrowseMode.SONGS -> emptyList()
        LibraryBrowseMode.ALBUMS -> tracks.groupedCollections(
            key = { track -> albumKey(track) },
            title = AudioTrack::album,
            subtitle = { group -> group.firstNotBlank(AudioTrack::albumArtist, AudioTrack::artist) },
            trackOrder = albumTrackOrder(names),
            names = names,
        )
        LibraryBrowseMode.ARTISTS -> tracks.groupedCollections(
            key = { track -> collectionKey("artist", track.artist.ifBlank { track.albumArtist }) },
            title = { track -> track.artist.ifBlank { track.albumArtist } },
            subtitle = { group -> group.map(AudioTrack::album).filter(String::isNotBlank).distinct().size.toString() },
            trackOrder = compareBy<AudioTrack, String>(names) { it.album }.then(albumTrackOrder(names)),
            names = names,
        )
        // The folder's own name leads; the path above it follows in the subtitle, so a narrow row or
        // large text never cuts off the part that tells folders apart (#35).
        LibraryBrowseMode.FOLDERS -> tracks.groupedCollections(
            key = { track -> collectionKey("folder", track.folderName) },
            title = { track -> folderPathParts(track.folderName).second },
            subtitle = { group -> folderPathParts(group.first().folderName).first },
            trackOrder = compareBy(names) { it.fileName },
            names = names,
        )
        LibraryBrowseMode.GENRES -> tracks.groupedCollections(
            key = { track -> collectionKey("genre", track.genre) },
            title = AudioTrack::genre,
            subtitle = { group -> group.size.toString() },
            trackOrder = compareBy<AudioTrack, String>(names) { it.artist }.thenBy(names) { it.title },
            names = names,
        )
        LibraryBrowseMode.PLAYLISTS -> {
            val tracksById = tracks.associateBy(AudioTrack::id)
            playlists.map { playlist ->
                LibraryCollection(
                    key = collectionKey("playlist", playlist.id.toString()),
                    title = playlist.name,
                    subtitle = playlist.trackCount.toString(),
                    tracks = playlist.trackIds.mapNotNull(tracksById::get),
                    playlistId = playlist.id,
                )
            }
        }
    }
}

fun sortLibraryCollections(
    collections: List<LibraryCollection>,
    sortOrder: LibrarySortOrder,
    names: Comparator<String> = LibraryNameOrder(),
): List<LibraryCollection> {
    return when (sortOrder) {
        LibrarySortOrder.TITLE,
        LibrarySortOrder.ALBUM,
        -> collections.sortedWith(compareBy(names) { it.title })
        // Only an album's subtitle is an artist; artists, genres, folders and playlists show a count
        // or a path there, so they sort by their own name ("10" no longer before "2").
        LibrarySortOrder.ARTIST -> collections.sortedWith(
            compareBy<LibraryCollection, String>(names) { if (it.isAlbum) it.subtitle else it.title }
                .thenBy(names) { it.title },
        )
        LibrarySortOrder.RECENTLY_ADDED -> collections.sortedWith(
            compareByDescending<LibraryCollection> { collection ->
                collection.tracks.maxOfOrNull(AudioTrack::dateModifiedSeconds) ?: 0
            }.thenBy(names) { it.title },
        )
        LibrarySortOrder.RECENTLY_PLAYED -> collections.sortedWith(
            compareByDescending<LibraryCollection> { collection ->
                collection.tracks.maxOfOrNull(AudioTrack::lastPlayedAtMs) ?: 0
            }.thenBy(names) { it.title },
        )
        LibrarySortOrder.MOST_PLAYED -> collections.sortedWith(
            compareByDescending<LibraryCollection> { collection -> collection.tracks.sumOf(AudioTrack::playCount) }
                .thenBy(names) { it.title },
        )
    }
}

private val LibraryCollection.isAlbum: Boolean get() = key.startsWith("$AlbumNamespace|")

private inline fun List<AudioTrack>.groupedCollections(
    crossinline key: (AudioTrack) -> String,
    crossinline title: (AudioTrack) -> String,
    crossinline subtitle: (List<AudioTrack>) -> String,
    trackOrder: Comparator<AudioTrack>,
    names: Comparator<String>,
): List<LibraryCollection> = groupBy(key)
    .map { (groupKey, groupTracks) ->
        LibraryCollection(
            key = groupKey,
            title = title(groupTracks.first()),
            subtitle = subtitle(groupTracks),
            tracks = groupTracks.sortedWith(trackOrder),
        )
    }
    .sortedWith(compareBy(names) { it.title })

/** "Music/Demo/Copper Hours/" → ("Music/Demo", "Copper Hours"); a bare name has nothing above it. */
internal fun folderPathParts(path: String): Pair<String, String> {
    val trimmed = path.trimEnd('/')
    val slash = trimmed.lastIndexOf('/')
    return if (slash < 0) "" to trimmed else trimmed.substring(0, slash) to trimmed.substring(slash + 1)
}

private fun List<AudioTrack>.firstNotBlank(vararg selectors: (AudioTrack) -> String): String =
    asSequence().flatMap { track -> selectors.asSequence().map { it(track) } }.firstOrNull(String::isNotBlank).orEmpty()

/** Length-prefix each component so user metadata cannot collide with separators or browse modes. */
private fun collectionKey(namespace: String, vararg components: String): String = buildString {
    append(namespace)
    components.forEach { component ->
        append('|')
        append(component.length)
        append(':')
        append(component)
    }
}

/**
 * An album is its name and album artist. Without an album artist the name alone would merge
 * same-named albums by different artists, and each track's artist would split a compilation, so
 * the folder decides: an album's tracks share one, or disc folders such as CD1 inside one (#170 P3).
 * Android 9 reports only a folder's own name, so there same-named folders still share an album.
 */
private fun albumKey(track: AudioTrack): String {
    val albumArtist = track.albumArtist.trim()
    val album = track.album.trim()
    if (albumArtist.isNotEmpty()) return collectionKey(AlbumNamespace, albumArtist, album)
    val (parent, name) = folderPathParts(track.folderName)
    val folder = if (DiscFolder.matches(name)) parent else track.folderName.trimEnd('/')
    return collectionKey(AlbumNamespace, "", album, folder)
}

/** "CD1", "CD 02", "Disc 2", "disk_1", "Disc 2 - Bonus". */
private val DiscFolder = Regex("""(?i)(?:cd|dis[ck])[\s._-]*\d{1,2}(?:\D.*)?""")

private fun albumTrackOrder(names: Comparator<String>): Comparator<AudioTrack> =
    compareBy<AudioTrack> { it.discNumber ?: Int.MAX_VALUE }
        .thenBy { it.trackNumber ?: Int.MAX_VALUE }
        .thenBy(names) { it.title }

private const val AlbumNamespace = "album"
