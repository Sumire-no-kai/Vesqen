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
    val stage: LibraryScanStage = LibraryScanStage.READING,
    /** Only known unsupported audio actually encountered in this source during this invocation. */
    val skippedUnsupportedAudio: Map<UnsupportedAudioFormat, Int> = emptyMap(),
    /** A control request, not confirmation that the provider cursor has stopped. */
    val pauseRequested: Boolean = false,
)

data class LibraryCatalogSnapshot(
    val tracks: List<AudioTrack>,
    val sources: List<LibrarySource>,
    val playlists: List<LibraryPlaylist> = emptyList(),
    val unmatchedRecords: List<UnmatchedLibraryRecord> = emptyList(),
)

/**
 * A hidden song's favorite, plays and playlist places that the Library could not give to one file
 * (#139). The user picks which of [candidates] it belongs to, or discards it.
 */
data class UnmatchedLibraryRecord(
    val record: AudioTrack,
    val candidates: List<AudioTrack>,
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
    /** The scan stopped at its next row for a folder removal or a record choice; it should run again (#191 D17). */
    val yieldedToEdit: Boolean = false,
    /** Per-source observations from this invocation; never a whole-device or unique-song count. */
    val sourceResults: List<LibrarySourceScanResult> = emptyList(),
)

/** A reported stage corresponds to work that the catalog is really performing. */
enum class LibraryScanStage {
    READING,
    RECONCILING,
}

/** Reasons safe for a future UI; provider messages, paths and exception text stay private. */
enum class LibraryScanFailureReason {
    PERMISSION_REQUIRED,
    PROVIDER_NOT_READY,
    SOURCE_CHANGED,
    READ_FAILED,
}

enum class LibrarySourceScanStatus {
    COMPLETED,
    /** The generation was unchanged; this invocation did not inspect the cached songs. */
    UNCHANGED,
    PAUSED,
    INTERRUPTED,
    FAILED,
    UNAVAILABLE,
    /** The catalog was replaced during this scan; its old session cannot commit. */
    REBUILT,
    /** The scan stopped for an edit waiting on the scan gate, rather than a user pause. */
    YIELDED,
}

/** Conservative types recognized as audio but not accepted by the current discovery policy. */
enum class UnsupportedAudioFormat {
    DSD,
    APE,
    WAVPACK,
}

data class LibrarySourceScanResult(
    val sourceId: String,
    val sourceName: String,
    val status: LibrarySourceScanStatus,
    val scannedTrackCount: Int = 0,
    val skippedUnsupportedAudio: Map<UnsupportedAudioFormat, Int> = emptyMap(),
    val failureReason: LibraryScanFailureReason? = null,
) {
    val skippedUnsupportedAudioCount: Int get() = skippedUnsupportedAudio.values.sum()
}

enum class FolderGrantRenewalResult {
    RENEWED,
    SOURCE_NOT_FOUND,
    WRONG_FOLDER,
    PERMISSION_DENIED,
    UNSUPPORTED,
}

/** Persisted values returned after one atomic listening-history increment. */
internal data class TrackPlaybackHistory(
    val playCount: Int,
    val lastPlayedAtMs: Long,
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

/** Bump when provider/container enrichment semantics change, invalidating scan cache keys. */
internal const val LIBRARY_METADATA_REVISION = 6

internal data class MediaStoreVolumeVersion(
    val volumeName: String,
    val databaseVersion: String,
    val generation: Long,
)

/** The merged external library changes when any volume is changed, rebuilt, mounted or removed. */
internal fun libraryScanGeneration(volumes: List<MediaStoreVolumeVersion>): String = libraryFingerprint(
    LIBRARY_METADATA_REVISION,
    *volumes.sortedBy(MediaStoreVolumeVersion::volumeName).map { volume ->
        libraryFingerprint(volume.volumeName, volume.databaseVersion, volume.generation)
    }.toTypedArray(),
)

/** Each volume's database version in a [libraryScanGeneration], or null when [generation] is not one. */
internal fun libraryScanDatabaseVersions(generation: String): Map<String, String>? {
    val fields = libraryFingerprintFields(generation) ?: return null
    // The first field is the metadata revision, which does not change MediaStore's `_ID`s.
    return fields.drop(1).associate { volume ->
        val (volumeName, databaseVersion) = libraryFingerprintFields(volume)
            ?.takeIf { it.size == 3 }
            ?: return null
        volumeName to databaseVersion
    }
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

/** Splits a [libraryFingerprint] into its fields, or returns null when [encoded] is not one. */
private fun libraryFingerprintFields(encoded: String): List<String>? {
    val fields = mutableListOf<String>()
    var start = 0
    while (start < encoded.length) {
        val colon = encoded.indexOf(':', start)
        if (colon < 0) return null
        val length = encoded.substring(start, colon).toIntOrNull() ?: return null
        if (length < 0 || length > encoded.length - colon - 1) return null
        fields += encoded.substring(colon + 1, colon + 1 + length)
        start = colon + 1 + length
    }
    return fields
}
