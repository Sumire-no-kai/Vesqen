package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlbumArtworkLoaderTest {
    @Test
    fun `album thumbnail cache is shared within a scan and refreshed by revision`() {
        val track = AudioTrack(
            id = 1,
            contentUri = "content://media/external/audio/media/1",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 1_000,
            albumArtworkUri = "content://media/external/audio/albums/9",
            dateModifiedSeconds = 100,
        )

        val albumUri = requireNotNull(track.albumArtworkUri)
        val base = AlbumArtworkCacheKey.albumThumbnail(albumUri, targetPx = 96, revision = 1)
        assertEquals(base, AlbumArtworkCacheKey.albumThumbnail(albumUri, targetPx = 96, revision = 1))
        assertNotEquals(base, AlbumArtworkCacheKey.albumThumbnail(albumUri, targetPx = 96, revision = 2))
        assertNotEquals(base, AlbumArtworkCacheKey.albumThumbnail(albumUri, targetPx = 192, revision = 1))
    }

    @Test
    fun `media thumbnail fallback is isolated per source and refreshes after an update`() {
        val track = AudioTrack(
            id = 1,
            contentUri = "content://media/external/audio/media/1",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 1_000,
            dateModifiedSeconds = 100,
            artworkRevision = 1,
        )

        val base = AlbumArtworkCacheKey.mediaThumbnail(track, 96)
        assertNotEquals(base, AlbumArtworkCacheKey.mediaThumbnail(track.copy(contentUri = "content://media/external/audio/media/2"), 96))
        assertNotEquals(base, AlbumArtworkCacheKey.mediaThumbnail(track.copy(dateModifiedSeconds = 101), 96))
        assertNotEquals(base, AlbumArtworkCacheKey.mediaThumbnail(track.copy(artworkRevision = 2), 96))
        assertNotEquals(base, AlbumArtworkCacheKey.mediaThumbnail(track, 192))
    }
}
