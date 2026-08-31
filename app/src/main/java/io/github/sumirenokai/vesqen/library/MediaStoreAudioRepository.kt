package io.github.sumirenokai.vesqen.library

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore

class MediaStoreAudioRepository(
    private val contentResolver: ContentResolver,
) {
    fun loadTracks(): List<AudioTrack> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
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
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
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
                        ),
                    )
                }
            }
        }.orEmpty()
    }
}
