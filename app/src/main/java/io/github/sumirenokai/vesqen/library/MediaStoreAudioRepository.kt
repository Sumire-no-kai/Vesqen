package io.github.sumirenokai.vesqen.library

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import java.util.concurrent.atomic.AtomicLong

class MediaStoreAudioRepository(
    private val contentResolver: ContentResolver,
) {
    private val scanGeneration = AtomicLong()

    fun loadTracks(): List<AudioTrack> {
        val artworkRevision = scanGeneration.incrementAndGet()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        return contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateModifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val albumId = cursor.getLong(albumIdIndex).takeIf { it > 0 }
                    add(
                        AudioTrack(
                            id = id,
                            contentUri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                id,
                            ).toString(),
                            title = cursor.getString(titleIndex).orEmpty(),
                            artist = cursor.getString(artistIndex).orEmpty(),
                            album = cursor.getString(albumIndex).orEmpty(),
                            durationMs = cursor.getLong(durationIndex),
                            albumId = albumId,
                            albumArtworkUri = albumId?.let {
                                ContentUris.withAppendedId(
                                    MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                                    it,
                                ).toString()
                            },
                            dateModifiedSeconds = cursor.getLong(dateModifiedIndex),
                            artworkRevision = artworkRevision,
                        ),
                    )
                }
            }
        }.orEmpty()
    }
}
