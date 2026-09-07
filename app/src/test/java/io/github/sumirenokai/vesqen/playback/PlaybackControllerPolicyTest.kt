package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.Player
import io.github.sumirenokai.vesqen.library.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackControllerPolicyTest {
    @Test
    fun `requested playback can be paused while buffering or suppressed`() {
        assertEquals(PlaybackToggleAction.PAUSE, playbackToggleAction(true, Player.STATE_BUFFERING))
        assertEquals(PlaybackToggleAction.PAUSE, playbackToggleAction(true, Player.STATE_READY))
        assertEquals(PlaybackToggleAction.PLAY, playbackToggleAction(false, Player.STATE_BUFFERING))
        assertEquals(PlaybackToggleAction.PLAY, playbackToggleAction(false, Player.STATE_READY))
    }

    @Test
    fun `idle and ended players need recovery even when playWhenReady remains true`() {
        for (requested in listOf(false, true)) {
            assertEquals(PlaybackToggleAction.PREPARE, playbackToggleAction(requested, Player.STATE_IDLE))
            assertEquals(PlaybackToggleAction.REPLAY, playbackToggleAction(requested, Player.STATE_ENDED))
        }
    }

    @Test
    fun `position-only projection preserves structural state`() {
        val queue = listOf(PlaybackQueueItem(1, "Track", "Artist", isCurrent = true))
        val original = PlaybackSnapshot(
            isControllerReady = true,
            trackId = 1,
            title = "Track",
            durationMs = 60_000,
            positionMs = 1_000,
            queueSize = 1,
            queue = queue,
        )

        val updated = original.withPlayerPosition(
            isPlaying = true,
            durationMs = 60_000,
            positionMs = 1_500,
        )

        assertSame(queue, updated.queue)
        assertEquals("Track", updated.title)
        assertEquals(1_500, updated.positionMs)
        assertEquals(true, updated.isPlaying)
    }

    @Test
    fun `position-only projection normalizes Media3 unset values`() {
        val updated = PlaybackSnapshot(trackId = 1).withPlayerPosition(
            isPlaying = false,
            durationMs = Long.MIN_VALUE,
            positionMs = -1,
        )

        assertEquals(0, updated.durationMs)
        assertEquals(0, updated.positionMs)
    }

    @Test
    fun `reconnect delay backs off and remains bounded`() {
        assertEquals(500, reconnectDelayMs(0))
        assertEquals(1_000, reconnectDelayMs(1))
        assertEquals(8_000, reconnectDelayMs(4))
        assertEquals(10_000, reconnectDelayMs(5))
        assertEquals(10_000, reconnectDelayMs(100))
        assertEquals(500, reconnectDelayMs(-1))
    }

    @Test
    fun `latest library snapshot survives a later controller connection`() {
        val source = mutableListOf(track())
        val snapshot = PlaybackLibrarySnapshot()

        val stored = snapshot.update(source)
        source += track().copy(id = 2)

        assertSame(stored, snapshot.current())
        assertEquals(listOf(1L), snapshot.current().map(AudioTrack::id))
    }

    @Test
    fun `pending queue is applied before reconnect library reconciliation`() {
        val steps = mutableListOf<String>()

        applyPlaybackConnectionState(
            pendingQueue = "user queue",
            applyPendingQueue = { steps += it },
            reconcileLibrary = { steps += "library" },
        )

        assertEquals(listOf("user queue", "library"), steps)

        steps.clear()
        applyPlaybackConnectionState<String>(
            pendingQueue = null,
            applyPendingQueue = { steps += it },
            reconcileLibrary = { steps += "library" },
        )
        assertEquals(listOf("library"), steps)
    }

    @Test
    fun `queue metadata changes are emitted as one replacement batch`() {
        val batch = buildSinglePlaybackReplacementBatch(
            currentItems = listOf("old-a", "same", "old-b"),
        ) { index, _ ->
            when (index) {
                0 -> "new-a"
                2 -> "new-b"
                else -> null
            }
        }

        assertEquals(listOf("new-a", "same", "new-b"), batch)
        assertNull(
            buildSinglePlaybackReplacementBatch(listOf("already-current")) { _, _ -> null },
        )
    }

    @Test
    fun `queue media fingerprint ignores catalog-only state`() {
        val original = track().playbackMediaFingerprint()
        val catalogOnlyUpdate = track().copy(
            isFavorite = true,
            lastPlayedAtMs = 123,
            playCount = 9,
        ).playbackMediaFingerprint()

        assertEquals(original, catalogOnlyUpdate)
    }

    @Test
    fun `queue media fingerprint detects source and artwork changes`() {
        val original = track().playbackMediaFingerprint()

        assertNotEquals(original, track().copy(codec = "audio/flac").playbackMediaFingerprint())
        assertNotEquals(original, track().copy(sampleRateHz = 96_000).playbackMediaFingerprint())
        assertNotEquals(original, track().copy(artworkRevision = 8).playbackMediaFingerprint())
        assertNotEquals(original, track().copy(albumArtworkUri = "content://art/2").playbackMediaFingerprint())
        assertNotEquals(original, track().copy(title = "Retitled").playbackMediaFingerprint())
    }

    @Test
    fun `playback start gate counts once until a new listening session`() {
        val gate = PlaybackStartGate()

        assertNull(gate.trackStarted(isPlaying = false, trackId = 1))
        assertEquals(1L, gate.trackStarted(isPlaying = true, trackId = 1))
        assertNull(gate.trackStarted(isPlaying = true, trackId = 1))

        gate.reset()
        assertEquals(1L, gate.trackStarted(isPlaying = true, trackId = 1))
    }

    @Test
    fun `metadata replacement is not a new listen but repeat transition is`() {
        val gate = PlaybackStartGate()
        gate.onMediaItemTransition(1, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "occurrence-a")
        assertEquals(1L, gate.trackStarted(isPlaying = true, trackId = 1))

        gate.onMediaItemTransition(1, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "occurrence-a")
        assertNull(gate.trackStarted(isPlaying = true, trackId = 1))

        gate.onMediaItemTransition(1, androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        assertEquals(1L, gate.trackStarted(isPlaying = true, trackId = 1))
    }

    @Test
    fun `explicit same song queue replacement counts only after playback starts`() {
        val gate = PlaybackStartGate()
        gate.onMediaItemTransition(1, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "a")
        assertEquals(1L, gate.trackStarted(true, 1))
        gate.onMediaItemTransition(1, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "b")
        assertNull(gate.trackStarted(false, 1))
        assertEquals(1L, gate.trackStarted(true, 1))
        assertNull(gate.trackStarted(true, 1))
    }

    @Test
    fun `unknown occurrence identity cannot suppress a new same song listen`() {
        val gate = PlaybackStartGate()
        assertEquals(1L, gate.trackStarted(true, 1))
        gate.onMediaItemTransition(1, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertEquals(1L, gate.trackStarted(true, 1))
    }

    private fun track() = AudioTrack(
        id = 1,
        contentUri = "content://track/1",
        title = "Track",
        artist = "Artist",
        album = "Album",
        durationMs = 60_000,
        albumArtworkUri = "content://art/1",
        artworkRevision = 7,
        fileName = "track.flac",
        fileSizeBytes = 1_024,
        mimeType = "audio/flac",
        codec = "flac",
        channelCount = 2,
        bitDepth = 24,
        sampleRateHz = 48_000,
        bitrate = 1_200_000,
    )
}
