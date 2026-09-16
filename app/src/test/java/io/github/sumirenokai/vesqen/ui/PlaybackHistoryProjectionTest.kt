package io.github.sumirenokai.vesqen.ui

import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackHistoryUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackHistoryProjectionTest {
    @Test
    fun `history update patches one track without changing playback identity`() {
        val first = track(1, playCount = 2, lastPlayedAtMs = 100)
        val second = track(2, playCount = 4, lastPlayedAtMs = 200)

        val updated = applyPlaybackHistoryUpdates(
            tracks = listOf(first, second),
            updates = listOf(PlaybackHistoryUpdate(2, playCount = 5, lastPlayedAtMs = 300)),
        )

        assertSame(first, updated[0])
        assertEquals(second.copy(playCount = 5, lastPlayedAtMs = 300), updated[1])
        assertEquals(second.contentUri, updated[1].contentUri)
        assertEquals(second.artworkRevision, updated[1].artworkRevision)
    }

    @Test
    fun `stale history event cannot roll back a newer snapshot`() {
        val current = track(1, playCount = 7, lastPlayedAtMs = 700)

        val unchanged = applyPlaybackHistoryUpdates(
            tracks = listOf(current),
            updates = listOf(PlaybackHistoryUpdate(1, playCount = 6, lastPlayedAtMs = 800)),
        )

        assertSame(current, unchanged.single())
    }

    private fun track(id: Long, playCount: Int, lastPlayedAtMs: Long) = AudioTrack(
        id = id,
        contentUri = "content://track/$id",
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        durationMs = 60_000,
        artworkRevision = 9,
        playCount = playCount,
        lastPlayedAtMs = lastPlayedAtMs,
    )
}
