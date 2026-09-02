package io.github.sumirenokai.vesqen.library

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogStoreTest {
    @Test
    fun rich_metadata_history_and_playlists_survive_reopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-test-${System.nanoTime()}.db"
        try {
            val trackId = LibraryCatalogStore(context, databaseName).use { store ->
                val source = store.ensureDeviceSource()
                val session = store.beginSourceScan(source.id)
                store.upsertTrack(
                    session,
                    LibraryTrackCandidate(
                        remoteId = "fixture-1",
                        contentUri = "content://fixture/1",
                        title = "Signal",
                        artist = "Mori",
                        album = "Quiet",
                        durationMs = 60_000,
                        sizeBytes = 1_024,
                        mimeType = "audio/flac",
                        albumArtist = "Mori",
                        trackNumber = 1,
                        discNumber = 1,
                        year = 2026,
                        genre = "Ambient",
                        fileName = "signal.flac",
                        folderName = "Music/Quiet",
                        codec = "FLAC",
                        channelCount = 2,
                        bitDepth = 24,
                        sampleRateHz = 96_000,
                        bitrate = 2_304_000,
                        fingerprint = "fixture",
                    ),
                )
                store.finishSourceScan(source.id)
                val inserted = store.readTracks(listOf(source.id)).single()
                store.setFavorite(inserted.id, true)
                store.recordPlayback(inserted.id, 123_456)
                val playlistId = requireNotNull(store.createPlaylist("Night"))
                store.addTrackToPlaylist(playlistId, inserted.id)
                inserted.id
            }

            LibraryCatalogStore(context, databaseName).use { reopened ->
                val sourceId = reopened.ensureDeviceSource().id
                val track = reopened.readTracks(listOf(sourceId)).single()
                assertEquals(trackId, track.id)
                assertEquals("FLAC", track.codec)
                assertEquals(24, track.bitDepth)
                assertEquals(96_000, track.sampleRateHz)
                assertTrue(track.isFavorite)
                assertEquals(1, track.playCount)
                assertEquals(listOf(trackId), reopened.readPlaylists().single().trackIds)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
