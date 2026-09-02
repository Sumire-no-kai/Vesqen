package io.github.sumirenokai.vesqen.playback

import io.github.sumirenokai.vesqen.library.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStateStoreTest {
    @Test
    fun `restore drops missing tracks and preserves the current position`() {
        val tracks = listOf(track(1), track(3))
        val restored = PersistedPlaybackState(
            queueTrackIds = listOf(1, 2, 3),
            currentTrackId = 3,
            positionMs = 12_345,
            shuffleEnabled = true,
            repeatMode = PlaybackRepeatMode.ALL,
        ).restoreAgainst(tracks)

        requireNotNull(restored)
        assertEquals(listOf(1L, 3L), restored.tracks.map(AudioTrack::id))
        assertEquals(1, restored.startIndex)
        assertEquals(12_345, restored.positionMs)
        assertEquals(true, restored.shuffleEnabled)
        assertEquals(PlaybackRepeatMode.ALL, restored.repeatMode)
    }

    @Test
    fun `restore refuses a queue whose sources no longer exist`() {
        assertNull(
            PersistedPlaybackState(
                queueTrackIds = listOf(9),
                currentTrackId = 9,
                positionMs = 1,
                shuffleEnabled = false,
                repeatMode = PlaybackRepeatMode.OFF,
            ).restoreAgainst(listOf(track(1))),
        )
    }

    private fun track(id: Long) = AudioTrack(
        id = id,
        contentUri = "content://track/$id",
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        durationMs = 60_000,
    )
}
