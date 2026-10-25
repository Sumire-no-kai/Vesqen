package io.github.sumirenokai.vesqen.playback

import io.github.sumirenokai.vesqen.library.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStateStoreTest {
    @Test
    fun `a queue that played to the end restarts from the beginning`() {
        val tracks = listOf(track(1), track(2), track(3))

        val ended = saved(2, 60_000, queueEnded = true).restoreAgainst(tracks)!!
        assertEquals(0, ended.startIndex)
        assertEquals(0, ended.positionMs)
        // Tracks added after the end: playback continues with the first of them.
        val extended = saved(0, 60_000, queueEnded = true).restoreAgainst(tracks)!!
        assertEquals(1, extended.startIndex)
        assertEquals(0, extended.positionMs)
        // Repeat one turned on after the end restarts that track.
        val repeatOne = saved(2, 60_000, PlaybackRepeatMode.ONE, queueEnded = true).restoreAgainst(tracks)!!
        assertEquals(2, repeatOne.startIndex)
        assertEquals(0, repeatOne.positionMs)
    }

    @Test
    fun `a pause near the end of a track restores the saved position in every repeat mode`() {
        val tracks = listOf(track(1), track(2), track(3))
        for (repeatMode in PlaybackRepeatMode.entries) {
            val restored = saved(0, 59_500, repeatMode, queueEnded = false).restoreAgainst(tracks)!!
            assertEquals(repeatMode.name, 0, restored.startIndex)
            assertEquals(repeatMode.name, 59_500, restored.positionMs)
            assertEquals(repeatMode, restored.repeatMode)
        }
    }

    @Test
    fun `a 1_0_0 save with repeat one keeps its track near the end`() {
        val restored = saved(0, 59_500, PlaybackRepeatMode.ONE).restoreAgainst(listOf(track(1), track(2)))!!
        assertEquals(0, restored.startIndex)
        assertEquals(59_500, restored.positionMs)
    }

    @Test
    fun `a 1_0_0 save at the start of a track shorter than a second keeps that track`() {
        val restored = saved(0, 0).restoreAgainst(listOf(track(1, durationMs = 500), track(2)))!!
        assertEquals(0, restored.startIndex)
        assertEquals(0, restored.positionMs)
    }

    @Test
    fun `a 1_0_0 save at the end of its last track restarts from the beginning`() {
        // An album ended with repeat off: the position was saved at the end of the last track.
        val tracks = listOf(track(1), track(2), track(3))

        val ended = saved(2, 59_800).restoreAgainst(tracks)!!
        assertEquals(0, ended.startIndex)
        assertEquals(0, ended.positionMs)
        // A finished track that is not the last continues with the next one.
        val between = saved(0, 60_000).restoreAgainst(tracks)!!
        assertEquals(1, between.startIndex)
        assertEquals(0, between.positionMs)
        // Anywhere earlier in a track is restored as saved.
        val middle = saved(2, 30_000).restoreAgainst(tracks)!!
        assertEquals(2, middle.startIndex)
        assertEquals(30_000, middle.positionMs)
    }

    @Test
    fun `restore preserves the current occurrence of a repeated track`() {
        val restored = repeatedTrackState().restoreAgainst(listOf(track(1), track(2)))!!

        assertEquals(listOf(1L, 2L, 1L), restored.tracks.map(AudioTrack::id))
        assertEquals(2, restored.startIndex)
        assertEquals(12_345, restored.positionMs)
    }

    @Test
    fun `restore remaps the current occurrence after missing earlier entries are removed`() {
        val restored = repeatedTrackState().restoreAgainst(listOf(track(1)))!!

        assertEquals(listOf(1L, 1L), restored.tracks.map(AudioTrack::id))
        assertEquals(1, restored.startIndex)
        assertEquals(12_345, restored.positionMs)
    }

    @Test
    fun `legacy or invalid occurrence falls back to the saved track identity`() {
        for (index in listOf(null, -1, 99, 1)) {
            val restored = repeatedTrackState().copy(currentQueueIndex = index)
                .restoreAgainst(listOf(track(1), track(2)))!!
            assertEquals(0, restored.startIndex)
            assertEquals(12_345, restored.positionMs)
        }
    }

    @Test
    fun `missing current track never transfers its position to another track`() {
        val restored = repeatedTrackState().restoreAgainst(listOf(track(2)))!!
        assertEquals(0, restored.startIndex)
        assertEquals(0, restored.positionMs)
    }

    private fun repeatedTrackState() = PersistedPlaybackState(
        queueTrackIds = listOf(1, 2, 1),
        currentTrackId = 1,
        currentQueueIndex = 2,
        positionMs = 12_345,
        shuffleEnabled = false,
        repeatMode = PlaybackRepeatMode.OFF,
    )

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

    /** A save of a three-track queue; [queueEnded] null is a save from 1.0.0. */
    private fun saved(
        index: Int,
        positionMs: Long,
        repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
        queueEnded: Boolean? = null,
    ) = PersistedPlaybackState(
        listOf(1L, 2L, 3L), currentTrackId = index + 1L, positionMs = positionMs,
        shuffleEnabled = false, repeatMode = repeatMode, currentQueueIndex = index, queueEnded = queueEnded,
    )

    private fun track(id: Long, durationMs: Long = 60_000) = AudioTrack(
        id = id,
        contentUri = "content://track/$id",
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        durationMs = durationMs,
    )
}
