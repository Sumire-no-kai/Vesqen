package io.github.sumirenokai.vesqen.library

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** MediaStore adapter for the catalog; its source identity never escapes this boundary. */
internal class MediaStoreAudioRepository(
    private val context: Context,
    private val contentResolver: ContentResolver,
) {
    /** Mounted volumes are also used to hide retained rows whose removable media is offline. */
    fun currentVolumeNames(): List<String> = mediaStoreScanVolumeNames(
        sdkInt = Build.VERSION.SDK_INT,
        externalVolumeNames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context)
        } else {
            emptySet()
        },
    )

    /**
     * Captures the exact mounted-volume set that a scan must query. Generations are comparable
     * only within a database version and a concrete mounted volume.
     */
    fun currentScanScope(): MediaStoreScanScope {
        val volumeNames = currentVolumeNames()
        if (volumeNames.isEmpty()) {
            throw IllegalStateException("MediaStore returned no external volumes")
        }
        val generation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            libraryScanGeneration(volumeNames.map { volume ->
                MediaStoreVolumeVersion(
                    volumeName = volume,
                    databaseVersion = MediaStore.getVersion(context, volume),
                    generation = MediaStore.getGeneration(context, volume),
                )
            })
        } else {
            null
        }
        return MediaStoreScanScope(volumeNames = volumeNames, generation = generation)
    }

    /**
     * Resolves rows written by releases that queried the synthetic `external` volume. Only an
     * unambiguous `_ID -> concrete volume` mapping is returned; ambiguous identities stay durable
     * and hidden instead of being attached to the wrong track.
     */
    fun legacyIdentityMappings(scope: MediaStoreScanScope): List<MediaStoreLegacyIdentity> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.MediaColumns.VOLUME_NAME),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0",
            null,
            null,
        ) ?: throw IllegalStateException("MediaStore returned no legacy identity cursor")
        val identitiesByLegacyId = linkedMapOf<String, MediaStoreLegacyIdentity?>()
        cursor.use {
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val volumeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idIndex)
                val legacyRemoteId = mediaId.toString()
                val volumeName = cursor.getString(volumeIndex)
                    ?.takeIf { it in scope.volumeNames }
                    ?: continue
                val identity = MediaStoreLegacyIdentity(
                    legacyRemoteId = legacyRemoteId,
                    targetRemoteId = mediaStoreRemoteId(volumeName, mediaId),
                    targetContentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.getContentUri(volumeName),
                        mediaId,
                    ).toString(),
                )
                val previous = identitiesByLegacyId[legacyRemoteId]
                identitiesByLegacyId[legacyRemoteId] = when {
                    !identitiesByLegacyId.containsKey(legacyRemoteId) -> identity
                    previous == identity -> identity
                    else -> null
                }
            }
        }
        return identitiesByLegacyId.values.filterNotNull()
    }

    suspend fun scanTracks(
        scope: MediaStoreScanScope,
        shouldPause: () -> Boolean,
        onTrack: (LibraryTrackCandidate) -> Unit,
    ): ScanIterationResult {
        val scanContext = currentCoroutineContext()
        scanContext.ensureActive()
        val projection = buildList {
            addAll(
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        var processedTrackCount = 0
        for (volumeName in scope.volumeNames) {
            scanContext.ensureActive()
            if (shouldPause()) {
                return ScanIterationResult(
                    completed = false,
                    processedTrackCount = processedTrackCount,
                )
            }
            val audioCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(volumeName)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val albumCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Albums.getContentUri(volumeName)
            } else {
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI
            }
            val cursor = contentResolver.query(
                audioCollection,
                projection,
                selection,
                null,
                sortOrder,
            ) ?: throw IllegalStateException("MediaStore returned no cursor for volume $volumeName")
            cursor.use {
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateModifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val mimeTypeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val displayNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val relativePathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                @Suppress("DEPRECATION")
                val legacyDataIndex = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    scanContext.ensureActive()
                    if (shouldPause()) {
                        return ScanIterationResult(
                            completed = false,
                            processedTrackCount = processedTrackCount,
                        )
                    }
                    val id = cursor.getLong(idIndex)
                    val albumId = cursor.getLong(albumIdIndex).takeIf { it > 0 }
                    val contentUri = ContentUris.withAppendedId(audioCollection, id).toString()
                    val title = cursor.getString(titleIndex).orEmpty()
                    val artist = cursor.getString(artistIndex).orEmpty()
                    val album = cursor.getString(albumIndex).orEmpty()
                    val durationMs = cursor.getLong(durationIndex)
                    val dateModifiedSeconds = cursor.getLong(dateModifiedIndex)
                    val sizeBytes = cursor.getLong(sizeIndex)
                    val mimeType = cursor.getString(mimeTypeIndex).orEmpty()
                    val fileName = cursor.getString(displayNameIndex).orEmpty()
                    val folderName = when {
                        relativePathIndex >= 0 ->
                            cursor.getString(relativePathIndex).orEmpty().trimEnd('/')
                        legacyDataIndex >= 0 ->
                            legacyMediaFolderName(cursor.getString(legacyDataIndex).orEmpty())
                        else -> ""
                    }
                    onTrack(
                        LibraryTrackCandidate(
                            // Scope every concrete volume to prevent provider-ID collisions. The
                            // upgrade migration preserves an unambiguous legacy row's catalog ID.
                            remoteId = mediaStoreRemoteId(volumeName, id),
                            contentUri = contentUri,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = durationMs,
                            albumId = albumId,
                            albumArtworkUri = albumId?.let {
                                ContentUris.withAppendedId(albumCollection, it).toString()
                            },
                            dateModifiedSeconds = dateModifiedSeconds,
                            sizeBytes = sizeBytes,
                            mimeType = mimeType,
                            fileName = fileName,
                            folderName = folderName,
                            fingerprint = libraryFingerprint(
                                "media",
                                LIBRARY_METADATA_REVISION,
                                id,
                                contentUri,
                                title,
                                artist,
                                album,
                                durationMs,
                                albumId,
                                dateModifiedSeconds,
                                sizeBytes,
                                mimeType,
                                fileName,
                                folderName,
                            ),
                        ),
                    )
                    processedTrackCount++
                }
            }
        }
        return ScanIterationResult(completed = true, processedTrackCount = processedTrackCount)
    }
}

internal data class MediaStoreScanScope(
    val volumeNames: List<String>,
    val generation: String?,
)

internal data class MediaStoreLegacyIdentity(
    val legacyRemoteId: String,
    val targetRemoteId: String,
    val targetContentUri: String,
)

/** API 29+ exposes each mounted external volume explicitly; older releases have one URI. */
internal fun mediaStoreScanVolumeNames(
    sdkInt: Int,
    externalVolumeNames: Set<String>,
): List<String> = if (sdkInt >= Build.VERSION_CODES.Q) {
    externalVolumeNames.sorted()
} else {
    listOf(LEGACY_EXTERNAL_VOLUME_NAME)
}

internal fun mediaStoreRemoteId(volumeName: String, mediaId: Long): String =
    if (volumeName == LEGACY_EXTERNAL_VOLUME_NAME) {
        mediaId.toString()
    } else {
        libraryFingerprint("media-volume", volumeName, mediaId)
    }

/** The synthetic legacy volume is the only MediaStore identity available before API 29. */
internal fun mediaStoreScanUsesConcreteVolumes(scope: MediaStoreScanScope): Boolean =
    scope.volumeNames.any { it != LEGACY_EXTERNAL_VOLUME_NAME }

/** Only concrete volumes scanned in this pass may authorize deletion. */
internal fun mediaStorePrunableVolumeNames(scannedVolumeNames: Collection<String>): Set<String> =
    scannedVolumeNames.toSet()

/** Returns the concrete MediaStore volume carried by an audio content URI. */
internal fun mediaStoreVolumeName(contentUri: String): String? {
    if (!contentUri.startsWith(MEDIA_STORE_CONTENT_PREFIX)) return null
    return contentUri.removePrefix(MEDIA_STORE_CONTENT_PREFIX)
        .substringBefore('/')
        .takeIf(String::isNotBlank)
}

/** Retained removable-volume rows stay durable but are not exposed while their volume is absent. */
internal fun mediaStoreTrackIsMounted(
    contentUri: String,
    mountedVolumeNames: Collection<String>,
): Boolean {
    val volumeName = mediaStoreVolumeName(contentUri) ?: return true
    return volumeName in mountedVolumeNames
}

/** Only an unchanged scope can authorize publishing deletions from a completed scan. */
internal fun mediaStoreScanCanCommit(
    iteration: ScanIterationResult,
    initialScope: MediaStoreScanScope,
    finalScope: MediaStoreScanScope,
): Boolean = iteration.completed && initialScope == finalScope

/**
 * Android 8/9 expose only the deprecated absolute DATA column. Keep the raw path inside the
 * MediaStore adapter and persist only its immediate parent label.
 */
internal fun legacyMediaFolderName(dataPath: String): String {
    val normalized = dataPath.replace('\\', '/').trimEnd('/')
    val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "")
    return parent.substringAfterLast('/', missingDelimiterValue = "")
}

private const val LEGACY_EXTERNAL_VOLUME_NAME = "external"
private const val MEDIA_STORE_CONTENT_PREFIX = "content://media/"
