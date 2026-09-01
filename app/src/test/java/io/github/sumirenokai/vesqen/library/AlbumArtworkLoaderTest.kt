package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlbumArtworkLoaderTest {
    @Test
    fun `legacy provider decode sampling caps the longest decoded edge`() {
        assertEquals(1, calculateLegacyArtworkSampleSize(512, 512, 256))
        assertEquals(2, calculateLegacyArtworkSampleSize(1024, 640, 256))
        assertEquals(8, calculateLegacyArtworkSampleSize(4000, 3000, 256))
        assertEquals(16, calculateLegacyArtworkSampleSize(8000, 1000, 256))
    }

    @Test
    fun `id3 helpers reject unsafe sizes and locate a bounded APIC image`() {
        assertEquals(1_448_542, decodeSynchsafeInt(byteArrayOf(0x00, 0x58, 0x34, 0x5e), 0))
        assertEquals(1_446_484, decodeBigEndianInt(byteArrayOf(0x00, 0x16, 0x12, 0x54), 0))
        assertEquals(null, decodeSynchsafeInt(byteArrayOf(0x00, 0x58, 0x34, 0x80.toByte()), 0))

        val apic = byteArrayOf(
            0x00,
            *"image/jpeg".toByteArray(),
            0x00,
            0x03,
            0x00,
            0xff.toByte(),
            0xd8.toByte(),
        )
        assertEquals(14, findId3ApicImageOffset(apic))
    }

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
