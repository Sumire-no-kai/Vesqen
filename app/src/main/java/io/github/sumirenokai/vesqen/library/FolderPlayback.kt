package io.github.sumirenokai.vesqen.library

/** A snapshot of the visible folders, played by the ordinary service-owned queue. */
data class FolderPlaybackPlan(val tracks: List<AudioTrack>, val startIndex: Int)

fun planFolderPlayback(
    folders: List<LibraryCollection>,
    selectedFolderKey: String,
    selectedTrackId: Long,
): FolderPlaybackPlan? {
    val selected = folders.indexOfFirst { it.key == selectedFolderKey }
    if (selected < 0) return null
    val within = folders[selected].tracks.indexOfFirst { it.id == selectedTrackId }
    if (within < 0) return null
    return FolderPlaybackPlan(
        tracks = folders.flatMap(LibraryCollection::tracks),
        startIndex = folders.take(selected).sumOf { it.tracks.size } + within,
    )
}
