package io.github.sumirenokai.vesqen.ui.screens

import io.github.sumirenokai.vesqen.library.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NowTrackAnimationIdentityTest {
    @Test
    fun `favorite and listening history do not change track animation identity`() {
        val track = track()
        val catalogOnlyUpdate = track.copy(
            isFavorite = true,
            lastPlayedAtMs = 42_000,
            playCount = 7,
            favoritePosition = 3,
        )

        assertEquals(
            nowTrackAnimationIdentity(track.id, track),
            nowTrackAnimationIdentity(track.id, catalogOnlyUpdate),
        )
    }

    @Test
    fun `track or artwork changes update track animation identity`() {
        val track = track()
        val identity = nowTrackAnimationIdentity(track.id, track)

        assertNotEquals(identity, nowTrackAnimationIdentity(track.id + 1, track))
        assertNotEquals(identity, nowTrackAnimationIdentity(track.id, track.copy(artworkRevision = 2)))
        assertNotEquals(
            identity,
            nowTrackAnimationIdentity(track.id, track.copy(albumArtworkUri = "content://artwork/2")),
        )
    }

    private fun track() = AudioTrack(
        id = 1,
        contentUri = "content://audio/1",
        title = "Track",
        artist = "Artist",
        album = "Album",
        durationMs = 180_000,
        albumArtworkUri = "content://artwork/1",
        dateModifiedSeconds = 10,
        artworkRevision = 1,
    )
}
