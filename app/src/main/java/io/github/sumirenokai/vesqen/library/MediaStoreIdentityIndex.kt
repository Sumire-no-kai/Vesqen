package io.github.sumirenokai.vesqen.library

import kotlin.math.abs

/** The fields of one catalog row that a MediaStore scan needs to check what its `_ID` names. */
internal data class StoredTrackIdentity(
    val trackId: Long,
    val remoteId: String,
    val contentUri: String,
    val folderName: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val fingerprint: String,
)

/**
 * Where a changed or unknown MediaStore row is written. [targetTrackId] is the catalog row that
 * takes the candidate, or null for a new row. [displacedTrackId] held the candidate's `_ID` for
 * another file; it moves to an isolated identity so it can be reattached later in the scan, or is
 * pruned when the scan completes without its file.
 */
internal data class MediaStoreTrackPlan(
    val targetTrackId: Long?,
    val displacedTrackId: Long? = null,
)

/**
 * In-memory view of the device source for one scan. MediaStore renumbers `_ID` from 1 when its
 * database is rebuilt or its data is cleared, so an `_ID` alone does not prove that a row still
 * names the same file (#136 R9). Catalog IDs carry favorites, history, playlist positions and the
 * saved queue, so a row is kept only by the file it describes:
 *
 * - The same `_ID` stays on its row unless the path (folder and file name), size and duration (by
 *   a second or more) all changed. A rename or move, a tag edit, or both keep the duration. The
 *   folder counts because generic names such as `01.flac` repeat in every album folder.
 * - Otherwise, and for an `_ID` the catalog does not know, the candidate is attached to the one
 *   row not yet seen in this scan with the same volume, folder, file name and size. A file that
 *   was deleted and copied back therefore keeps its row too.
 * - Without exactly one such row the candidate becomes a new row. An ambiguous match never moves
 *   user data to another song.
 *
 * Built from the rows stored when the scan begins; each `_ID` is offered once per scan. The plan
 * returned by [plan] must be written before the next candidate, and a failed write abandons the
 * scan together with this index.
 */
internal class MediaStoreIdentityIndex(rows: Collection<StoredTrackIdentity>) {
    private val rowsByRemoteId = rows.associateByTo(HashMap(), StoredTrackIdentity::remoteId)
    private val unseenRowsByFile = rows.groupByTo(HashMap(), StoredTrackIdentity::fileKey)

    /** Returns the row of an unchanged file and marks it seen, or null when [plan] must decide. */
    fun unchangedTrackId(candidate: LibraryTrackCandidate): Long? {
        val row = rowsByRemoteId[candidate.remoteId]
            ?.takeIf { it.fingerprint == candidate.fingerprint }
            ?: return null
        markSeen(row)
        return row.trackId
    }

    fun plan(candidate: LibraryTrackCandidate): MediaStoreTrackPlan {
        val current = rowsByRemoteId[candidate.remoteId]
        if (current != null && current.namesSameFileAs(candidate)) {
            markSeen(current)
            return MediaStoreTrackPlan(targetTrackId = current.trackId)
        }
        // The displaced row stays unseen, so its own file can still claim it under a new `_ID`.
        current?.let { rowsByRemoteId.remove(it.remoteId) }
        val previous = unseenRowsByFile[candidate.fileKey()]?.singleOrNull()
        if (previous != null) {
            markSeen(previous)
            rowsByRemoteId.remove(previous.remoteId)
        }
        return MediaStoreTrackPlan(
            targetTrackId = previous?.trackId,
            displacedTrackId = current?.trackId,
        )
    }

    private fun markSeen(row: StoredTrackIdentity) {
        val key = row.fileKey()
        val unseen = unseenRowsByFile[key] ?: return
        unseen.remove(row)
        if (unseen.isEmpty()) unseenRowsByFile.remove(key)
    }
}

/** A difference below this is the same recording read by another parser or after a tag edit. */
internal const val SAME_FILE_DURATION_TOLERANCE_MS = 1_000L

private fun StoredTrackIdentity.namesSameFileAs(candidate: LibraryTrackCandidate): Boolean =
    (folderName == candidate.folderName && fileName == candidate.fileName) ||
        sizeBytes == candidate.sizeBytes ||
        abs(durationMs - candidate.durationMs) < SAME_FILE_DURATION_TOLERANCE_MS

private data class TrackFileKey(
    val volumeName: String?,
    val folderName: String,
    val fileName: String,
    val sizeBytes: Long,
)

private fun StoredTrackIdentity.fileKey() =
    TrackFileKey(mediaStoreVolumeName(contentUri), folderName, fileName, sizeBytes)

private fun LibraryTrackCandidate.fileKey() =
    TrackFileKey(mediaStoreVolumeName(contentUri), folderName, fileName, sizeBytes)
