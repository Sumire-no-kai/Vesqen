package io.github.sumirenokai.vesqen.library

import java.text.Collator
import java.util.Locale

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
        LibrarySortOrder.ALBUM -> {
            val albums = AlbumKeys()
            tracks.sortedWith(
                compareBy<AudioTrack, String>(names) { it.album }.thenBy(albums::of).then(albumTrackOrder(withinCollectionOrder())),
            )
        }
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

/** [artists] splits shared artist tags (#168); the Library keeps one per setting so each tag is split once. */
fun buildLibraryCollections(
    mode: LibraryBrowseMode,
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist>,
    names: Comparator<String> = LibraryNameOrder(),
    artists: ArtistNames = ArtistNames(ArtistSplitSettings()),
): List<LibraryCollection> {
    // Collections follow the Songs index (#167); the tracks inside one keep the plain collation
    // order, so "01 Intro.flac" stays before "Bonus.flac" in a folder.
    val within = withinCollectionOrder()
    val albums = AlbumKeys()
    return when (mode) {
        LibraryBrowseMode.SONGS -> emptyList()
        LibraryBrowseMode.ALBUMS -> tracks.groupedCollections(
            key = albums::of,
            title = AudioTrack::album,
            subtitle = { group -> group.firstNotBlank(AudioTrack::albumArtist, AudioTrack::artist) },
            trackOrder = albumTrackOrder(within),
            names = names,
        )
        // A song with several artists is in each of their collections, and counts there (#168).
        // Albums keep their own grouping, so an artist's album count uses the same albums.
        LibraryBrowseMode.ARTISTS -> {
            val trackOrder = compareBy<AudioTrack, String>(within) { it.album }.thenBy(albums::of).then(albumTrackOrder(within))
            tracks.flatMap { track -> artists.of(track).map { artist -> artist to track } }
                .groupBy { (artist, _) -> artist.key }
                .map { (artistKey, credits) ->
                    val group = credits.map { (_, track) -> track }
                    LibraryCollection(
                        key = collectionKey("artist", artistKey),
                        title = mostUsedSpelling(credits.map { (artist, _) -> artist.name }),
                        subtitle = group.filter { it.album.isNotBlank() }.map(albums::of).distinct().size.toString(),
                        tracks = group.sortedWith(trackOrder),
                    )
                }
                .sortedWith(compareBy(names) { it.title })
        }
        // The folder's own name leads; the path above it follows in the subtitle, so a narrow row or
        // large text never cuts off the part that tells folders apart (#35).
        LibraryBrowseMode.FOLDERS -> tracks.groupedCollections(
            key = { track -> collectionKey("folder", track.folderName) },
            title = { track -> folderPathParts(track.folderName).second },
            subtitle = { group -> folderPathParts(group.first().folderName).first },
            trackOrder = compareBy(within) { it.fileName },
            names = names,
        )
        LibraryBrowseMode.GENRES -> tracks.groupedCollections(
            key = { track -> collectionKey("genre", track.genre) },
            title = AudioTrack::genre,
            subtitle = { group -> group.size.toString() },
            trackOrder = compareBy<AudioTrack, String>(within) { it.artist }.thenBy(within) { it.title },
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

/** "Adam Gubman" on three songs and "adam gubman" on one show as "Adam Gubman"; a tie takes the first in code order. */
private fun mostUsedSpelling(spellings: List<String>): String {
    if (spellings.all { it == spellings.first() }) return spellings.first()
    return spellings.groupingBy { it }.eachCount().entries
        .minWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .key
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
 * the folder decides: an album's tracks share one, or disc folders inside or beside it (#170 P3).
 * Android 9 reports only a folder's own name, so there same-named folders still share an album.
 * Folders are far fewer than tracks, so each folder is worked out once per list.
 */
private class AlbumKeys {
    private val folders = HashMap<String, String>()

    fun of(track: AudioTrack): String {
        val albumArtist = track.albumArtist.trim()
        val album = track.album.trim()
        // Songs without an album tag stay one group, as before #170 P3.
        if (albumArtist.isNotEmpty() || album.isEmpty()) return collectionKey(AlbumNamespace, albumArtist, album)
        return collectionKey(AlbumNamespace, "", album, folders.getOrPut(track.folderName) { albumFolder(track.folderName) })
    }
}

/** "Music/A/Long Night/CD1" and "Music/A/Long Night (Disc 2)" both belong to "Music/A/Long Night". */
internal fun albumFolder(folderName: String): String {
    val (parent, name) = folderPathParts(folderName)
    if (DiscFolder.matches(name)) return parent
    val album = name.replace(DiscSuffix, "")
    // A folder named only "(Disc 1)" or "[CD1]" is a disc of its parent.
    if (album.isBlank()) return parent
    return if (parent.isEmpty()) album else "$parent/$album"
}

/** A folder named only for its disc: "CD1", "CD 02", "Disc 2", "disk_1", "Disc 2 - Bonus". */
private val DiscFolder = Regex("""(?i)(?:cd|dis[ck])[\s._-]*\d{1,2}(?:\D.*)?""")

/** A disc after the album's name: "Long Night (Disc 2)", "Long Night CD1", "Long Night [CD 2]", "Long Night - Disc 1". */
private val DiscSuffix = Regex("""(?i)(?:[\s._-]+[(\[]?|[(\[])(?:cd|dis[ck])[\s._-]*\d{1,2}[)\]]?$""")

/**
 * The order of tracks inside one collection: plain zh collation, as before #167, which puts digits
 * first like a file manager. Made per list, because a Collator is not safe to share between threads.
 */
private fun withinCollectionOrder(): Comparator<String> {
    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply { strength = Collator.SECONDARY }
    return Comparator { first, second -> collator.compare(first.trim(), second.trim()) }
}

private fun albumTrackOrder(names: Comparator<String>): Comparator<AudioTrack> =
    compareBy<AudioTrack> { it.discNumber ?: Int.MAX_VALUE }
        .thenBy { it.trackNumber ?: Int.MAX_VALUE }
        .thenBy(names) { it.title }

private const val AlbumNamespace = "album"
