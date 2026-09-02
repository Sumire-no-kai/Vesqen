package io.github.sumirenokai.vesqen.library

/** A durable source that contributes tracks to the local catalog. */
enum class LibrarySourceKind {
    DEVICE,
    FOLDER,
}

/**
 * The persisted state of a source scan. A paused or interrupted scan always retains the last
 * committed catalog rows; only a successfully completed scan is allowed to remove unseen rows.
 */
enum class LibraryScanState {
    IDLE,
    SCANNING,
    PAUSED,
    INTERRUPTED,
    FAILED,
}

data class LibrarySource(
    val id: String,
    val kind: LibrarySourceKind,
    val displayName: String,
    val treeUri: String? = null,
    val scanState: LibraryScanState = LibraryScanState.IDLE,
    val trackCount: Int = 0,
    /** True when the grant required to read this source is still available to this install. */
    val isAvailable: Boolean = true,
)

/** UI-safe scan feedback. Total work is intentionally omitted because providers do not expose it. */
data class LibraryScanProgress(
    val sourceId: String,
    val sourceName: String,
    val scannedTrackCount: Int,
    val isPaused: Boolean = false,
)

data class LibraryCatalogSnapshot(
    val tracks: List<AudioTrack>,
    val sources: List<LibrarySource>,
    val playlists: List<LibraryPlaylist> = emptyList(),
)

data class LibraryPlaylist(
    val id: Long,
    val name: String,
    val trackIds: List<Long>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val trackCount: Int
        get() = trackIds.size
}

data class LibraryRefreshResult(
    val snapshot: LibraryCatalogSnapshot,
    val hadFailure: Boolean,
)

/** A scanner closes its provider cursor before reporting a pause, so resume can safely restart. */
internal data class ScanIterationResult(
    val completed: Boolean,
    val processedTrackCount: Int,
)

/**
 * Source-owned identity stays inside the catalog. The playback/UI layer receives the catalog's
 * durable numeric row ID in [AudioTrack.id], so adding SAF folders cannot collide with MediaStore
 * IDs or change Media3's existing queue identity contract.
 */
internal data class LibraryTrackCandidate(
    val remoteId: String,
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumId: Long? = null,
    val albumArtworkUri: String? = null,
    val dateModifiedSeconds: Long = 0,
    val sizeBytes: Long = 0,
    val mimeType: String = "",
    val albumArtist: String = "",
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String = "",
    val fileName: String = "",
    val folderName: String = "",
    val codec: String = "",
    val channelCount: Int? = null,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val bitrate: Int? = null,
    val fingerprint: String,
)

internal object LibrarySourceId {
    const val DEVICE = "media:external"

    fun forTree(treeUri: String): String = "tree:$treeUri"
}

/** Kept explicit rather than hashed: a collision must never make two audio documents one track. */
internal fun libraryFingerprint(vararg fields: Any?): String = buildString {
    fields.forEach { field ->
        val value = field?.toString().orEmpty()
        append(value.length)
        append(':')
        append(value)
    }
}
