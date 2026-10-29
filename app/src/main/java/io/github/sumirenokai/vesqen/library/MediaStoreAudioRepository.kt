package io.github.sumirenokai.vesqen.library

import android.Manifest
import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The runtime permission that opens the device music library. The Library and the playback
 * service's queue restore (#52) check the same one.
 */
internal val musicLibraryPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

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
        val versions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volumeNames.map { volume ->
                MediaStoreVolumeVersion(
                    volumeName = volume,
                    databaseVersion = MediaStore.getVersion(context, volume),
                    generation = MediaStore.getGeneration(context, volume),
                )
            }
        } else {
            null
        }
        return MediaStoreScanScope(
            volumeNames = volumeNames,
            generation = versions?.let(::libraryScanGeneration),
            databaseVersions = versions?.associate { it.volumeName to it.databaseVersion },
        )
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

    /** Before Android 10 MediaStore exposes only the parent directory's name as the folder. */
    val pathIdentifiesFile: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Every audio file [scanTracks] will offer, read before it starts: the identity index needs to
     * know whether a row's own file is still listed under another `_ID` (#143 Q1).
     */
    suspend fun listFiles(scope: MediaStoreScanScope): List<ListedMediaStoreFile> {
        val scanContext = currentCoroutineContext()
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            @Suppress("DEPRECATION")
            add(if (pathIdentifiesFile) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA)
        }.toTypedArray()
        return buildList {
            for (volumeName in scope.volumeNames) {
                scanContext.ensureActive()
                val audioCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(volumeName)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val cursor = contentResolver.query(audioCollection, projection, TRACK_SELECTION, null, null)
                    ?: throw IllegalStateException("MediaStore returned no cursor for volume $volumeName")
                cursor.use {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val path = cursor.getString(3).orEmpty()
                        add(
                            ListedMediaStoreFile(
                                remoteId = mediaStoreRemoteId(volumeName, id),
                                contentUri = ContentUris.withAppendedId(audioCollection, id).toString(),
                                folderName = if (pathIdentifiesFile) path.trimEnd('/') else legacyMediaFolderName(path),
                                fileName = cursor.getString(2).orEmpty(),
                                sizeBytes = cursor.getLong(1),
                            ),
                        )
                    }
                }
            }
        }
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
        val selection = TRACK_SELECTION
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
    /** Each volume's MediaStore database version, which changes when MediaStore is rebuilt; null before Android 11. */
    val databaseVersions: Map<String, String>? = null,
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

/**
 * A completed scan may publish deletions while MediaStore kept the same volumes and databases: a
 * removed card or a rebuilt MediaStore lists nothing yet for files it still holds. Other media
 * changes, a photo or a screenshot, move the generation only and no longer interrupt the scan, which
 * could then never finish a large library (#136 R15); rows a changing cursor skipped are kept with
 * [LibraryCatalogStore.keepTracksStillListed] before anything is pruned.
 */
internal fun mediaStoreScanCanCommit(
    iteration: ScanIterationResult,
    initialScope: MediaStoreScanScope,
    finalScope: MediaStoreScanScope,
): Boolean = iteration.completed &&
    initialScope.volumeNames == finalScope.volumeNames &&
    initialScope.databaseVersions == finalScope.databaseVersions

/**
 * The volumes whose MediaStore database is the one the last completed scan read, recorded in its
 * [completedGeneration]. MediaStore renumbers `_ID`s only in a new database, which has a new version,
 * and a completed scan leaves no row on its volumes with an `_ID` from an older one: it took or
 * displaced each row it reached, kept others only while their `_ID` is still listed for them, and
 * pruned or hid the rest. On these volumes a stored `_ID` therefore still names its entry (#159).
 */
internal fun mediaStoreVolumesWithStableIds(
    completedGeneration: String?,
    scope: MediaStoreScanScope,
): Set<String> {
    val completedVersions = completedGeneration?.let(::libraryScanDatabaseVersions) ?: return emptySet()
    return scope.databaseVersions.orEmpty()
        .filter { (volumeName, version) -> completedVersions[volumeName] == version }
        .keys
}

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
private const val TRACK_SELECTION =
    "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
