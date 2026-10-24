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
 * another file; it moves to an isolated identity so it can be reattached later in the scan. When
 * the scan completes without its file it is pruned, or stays hidden if it holds user data.
 */
internal data class MediaStoreTrackPlan(
    val targetTrackId: Long?,
    val displacedTrackId: Long? = null,
)

/** One audio file MediaStore lists when a scan begins: only what [MediaStoreIdentityIndex] matches on. */
internal data class ListedMediaStoreFile(
    val remoteId: String,
    val contentUri: String,
    val folderName: String,
    val fileName: String,
    val sizeBytes: Long,
)

/**
 * In-memory view of the device source for one scan. MediaStore renumbers `_ID` from 1 when its
 * database is rebuilt or its data is cleared, so an `_ID` alone does not prove that a row still
 * names the same file (#136 R9). Catalog IDs carry favorites, history, playlist positions and the
 * saved queue, so a row is kept only by the file it describes:
 *
 * - The candidate first takes the one row not yet seen in this scan with the same volume, folder,
 *   file name and size, even when another row holds its `_ID`. A file that was deleted and copied
 *   back therefore keeps its row too.
 * - Without such a row, the same `_ID` stays on its row unless the path (folder and file name),
 *   size and duration (by a second or more) all changed: a rename or move, a tag edit, or both
 *   keep the duration. It does not stay when the row's own file is still listed under another
 *   `_ID` (#143 Q1): two songs within a second of each other are common in a large library.
 * - Before Android 10 the folder is only the parent directory's name, so there a same path alone
 *   does not keep a row; the size or duration has to match too ([pathIdentifiesFile], #143 Q6).
 * - Otherwise the candidate becomes a new row. An ambiguous match never moves user data to another
 *   song.
 *
 * Built from the rows stored and the files MediaStore lists when the scan begins; each `_ID` is
 * offered once per scan. The plan returned by [plan] must be written before the next candidate,
 * and a failed write abandons the scan together with this index.
 */
internal class MediaStoreIdentityIndex(
    rows: Collection<StoredTrackIdentity>,
    listedFiles: Collection<ListedMediaStoreFile> = emptyList(),
    private val pathIdentifiesFile: Boolean = true,
) {
    private val rowsByRemoteId = rows.associateByTo(HashMap(), StoredTrackIdentity::remoteId)
    private val unseenRowsByFile = rows.groupByTo(HashMap(), StoredTrackIdentity::fileKey)
    private val listedRemoteIdsByFile = listedFiles.groupBy(ListedMediaStoreFile::fileKey) { it.remoteId }
    private val storedFileByRemoteId = rows.associate { it.remoteId to it.fileKey() }

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
        val sameFile = unseenRowsByFile[candidate.fileKey()]?.singleOrNull()
        val keepsCurrent = current != null && (
            sameFile === current ||
                current.fileKey() == candidate.fileKey() ||
                sameFile == null &&
                current.namesSameFileAs(candidate, pathIdentifiesFile) &&
                !current.isListedUnderAnotherId(candidate)
            )
        if (keepsCurrent) {
            markSeen(current!!)
            return MediaStoreTrackPlan(targetTrackId = current.trackId)
        }
        // The displaced row stays unseen, so its own file can still claim it under a new `_ID`.
        current?.let { rowsByRemoteId.remove(it.remoteId) }
        if (sameFile != null) {
            markSeen(sameFile)
            rowsByRemoteId.remove(sameFile.remoteId)
        }
        return MediaStoreTrackPlan(
            targetTrackId = sameFile?.trackId,
            displacedTrackId = current?.trackId,
        )
    }

    /**
     * True when this row's file is listed under another `_ID`. Before Android 10 copies on two
     * storages share a key, so when the candidate keeps this row's path (a tag edit), an identical
     * copy listed under the `_ID` of its own row with the same key is that copy, not this file
     * renumbered. A candidate on another path gets no such exemption: the copy's row may only be
     * left over from a removed card while this row's file was renumbered after a MediaStore rebuild.
     */
    private fun StoredTrackIdentity.isListedUnderAnotherId(candidate: LibraryTrackCandidate): Boolean {
        val key = fileKey()
        val samePath = folderName == candidate.folderName && fileName == candidate.fileName
        return listedRemoteIdsByFile[key].orEmpty().any {
            it != candidate.remoteId && !(samePath && storedFileByRemoteId[it] == key)
        }
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

private fun StoredTrackIdentity.namesSameFileAs(
    candidate: LibraryTrackCandidate,
    pathIdentifiesFile: Boolean,
): Boolean =
    (pathIdentifiesFile && folderName == candidate.folderName && fileName == candidate.fileName) ||
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

private fun ListedMediaStoreFile.fileKey() =
    TrackFileKey(mediaStoreVolumeName(contentUri), folderName, fileName, sizeBytes)
