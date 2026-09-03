package io.github.sumirenokai.vesqen.library

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore

/** MediaStore adapter for the catalog; its source identity never escapes this boundary. */
internal class MediaStoreAudioRepository(
    private val context: Context,
    private val contentResolver: ContentResolver,
) {
    /** API 30+ lets an unchanged volume avoid reopening every audio row. */
    fun currentGeneration(): Long? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
    } else {
        null
    }

    fun scanTracks(
        shouldPause: () -> Boolean,
        onTrack: (LibraryTrackCandidate) -> Unit,
    ): ScanIterationResult {
        val projection = buildList {
            addAll(arrayOf(
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
            ))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder,
        ) ?: throw IllegalStateException("MediaStore returned no cursor")
        return try {
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

            var processedTrackCount = 0
            while (cursor.moveToNext()) {
                if (shouldPause()) {
                    return ScanIterationResult(completed = false, processedTrackCount = processedTrackCount)
                }
                val id = cursor.getLong(idIndex)
                val albumId = cursor.getLong(albumIdIndex).takeIf { it > 0 }
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id,
                ).toString()
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
                        remoteId = id.toString(),
                        contentUri = contentUri,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        albumId = albumId,
                        albumArtworkUri = albumId?.let {
                            ContentUris.withAppendedId(
                                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                                it,
                            ).toString()
                        },
                        dateModifiedSeconds = dateModifiedSeconds,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        fileName = fileName,
                        folderName = folderName,
                        fingerprint = libraryFingerprint(
                            "media",
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
            ScanIterationResult(completed = true, processedTrackCount = processedTrackCount)
        } finally {
            cursor.close()
        }
    }
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
