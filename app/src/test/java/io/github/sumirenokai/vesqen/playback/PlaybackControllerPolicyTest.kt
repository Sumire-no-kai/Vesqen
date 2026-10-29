package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.C
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
        val queue = listOf(PlaybackQueueItem(1, "Track", "Artist", isCurrent = true, listIndex = 0))
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
    fun `unprepared player uses catalog duration and position refresh retains it`() {
        for (unknown in listOf(C.TIME_UNSET, 0L)) {
            val duration = resolvePlaybackDurationMs(unknown, 60_000)
            assertEquals(60_000L, duration)
            val restored = PlaybackSnapshot(trackId = 1, durationMs = duration, positionMs = 12_000)
            val refreshed = restored.withPlayerPosition(
                isPlaying = false, durationMs = unknown, positionMs = 15_000,
                libraryDurationMs = 60_000,
            )
            assertEquals(60_000L, refreshed.durationMs)
            assertEquals(0.25f, refreshed.progressFraction)
        }
    }

    @Test
    fun `measured player duration takes precedence and missing catalog duration stays unknown`() {
        assertEquals(59_500L, resolvePlaybackDurationMs(59_500, 60_000))
        assertEquals(0L, resolvePlaybackDurationMs(C.TIME_UNSET, null))
        assertEquals(0L, resolvePlaybackDurationMs(C.TIME_UNSET, -10))
        val changed = PlaybackSnapshot(trackId = 1, durationMs = 60_000).withPlayerPosition(
            isPlaying = false, durationMs = C.TIME_UNSET, positionMs = 0,
            libraryDurationMs = 90_000,
        )
        assertEquals(90_000L, changed.durationMs)
    }

    @Test
    fun `controller disconnect revokes active strict output declaration`() {
        val active = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.ACTIVE,
            decisionCode = "strict_usb.active",
            generation = 7,
        )

        val disconnected = PlaybackSnapshot(
            isControllerReady = true,
            isPlaying = true,
            showsPauseAction = true,
            usbOutputStatus = active,
        ).withDisconnectedController(
            observedAtEpochMs = 123,
            observedAtElapsedRealtimeMs = 456,
        )

        assertEquals(false, disconnected.isControllerReady)
        assertEquals(false, disconnected.canSetUsbOutputMode)
        assertEquals(false, disconnected.isPlaying)
        assertEquals(false, disconnected.showsPauseAction)
        assertEquals(UsbOutputPhase.APPLYING, disconnected.usbOutputStatus.phase)
        assertEquals("strict_usb.session_disconnected", disconnected.usbOutputStatus.decisionCode)
        assertEquals(OutputDeclaration.BIT_PERFECT_REQUESTED, disconnected.declaration)
        assertNull(disconnected.outputVerification)
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
    fun `only changed queue items are replaced, and a moved file alone`() {
        // "a:1" is item a at source 1: a new tag keeps the source, a renamed file changes it.
        val queue = listOf("a:1", "b:1", "c:1", "d:1", "e:1", "f:1")
        val updates = mapOf(0 to "a:1+tag", 1 to "b:1+tag", 2 to "c:2", 3 to "d:1+tag", 5 to "f:1+tag")
        val ranges = buildPlaybackReplacementRanges(
            currentItems = queue,
            keepsSource = { current, replacement -> current.substringBefore('+') == replacement.substringBefore('+') },
        ) { index, _ -> updates[index] }

        assertEquals(
            listOf(0 to listOf("a:1+tag", "b:1+tag"), 2 to listOf("c:2"), 3 to listOf("d:1+tag"), 5 to listOf("f:1+tag")),
            ranges,
        )
        assertEquals(
            emptyList<Pair<Int, List<String>>>(),
            buildPlaybackReplacementRanges(listOf("already-current"), { _, _ -> true }) { _, _ -> null },
        )
    }

    @Test
    fun `an edit shown before the session's queue keeps the play order`() {
        // #170 F10: the play order by queue entry, with Play next, Add to queue and shuffle turned on.
        val order = listOf("c", "a", "d", "b")
        assertEquals(listOf("c", "next", "a", "d", "b"), order.withEntryAfter("next", anchor = "c"))
        assertEquals(listOf("c", "a", "d", "b", "last"), order.withEntryAfter("last", anchor = order.last()))
        assertEquals(listOf("d", "c", "a", "b"), order.withEntryAfter("d", anchor = null))
        assertEquals(listOf("only"), emptyList<String>().withEntryAfter("only", anchor = null))
        // Back to list indices for the list Media3 shows.
        assertEquals(listOf(2, 0, 3, 1), expectedPlayOrderIndices(order, listEntryIds = listOf("a", "b", "c", "d")))
    }

    @Test
    fun `an expected order that is not exactly the queue is not used`() {
        val list = listOf("a", "b", "c")
        assertNull(expectedPlayOrderIndices(listOf("c", "a"), list))
        assertNull(expectedPlayOrderIndices(listOf("c", "a", "x"), list))
        assertNull(expectedPlayOrderIndices(listOf("c", "a", "a"), list))
        // Entries without their own ID (the same track twice) cannot be told apart.
        assertNull(expectedPlayOrderIndices(listOf("a", "a", "b"), listOf("a", "b", "a")))
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
    fun `a playing song merged into a record is not counted again`() {
        // #139: the entry keeps its occurrence while its track ID becomes the record's.
        val gate = PlaybackStartGate()
        gate.onMediaItemTransition(2, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, "a")
        assertEquals(2L, gate.trackStarted(isPlaying = true, trackId = 2))

        gate.onMediaItemTransition(7, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "a")
        assertNull(gate.trackStarted(isPlaying = true, trackId = 7))
        assertEquals("a", gate.countedOccurrence(currentTrackId = 7))

        // Another entry, even of the record, is a new listen.
        gate.onMediaItemTransition(7, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, "b")
        assertEquals(7L, gate.trackStarted(isPlaying = true, trackId = 7))
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
    fun `a restore that resumes a counted listen does not count it again`() {
        // Paused in occurrence a; Android then ended the process (#165). The next service's gate
        // starts from the saved listen, and the restore sets occurrence a again.
        val gate = PlaybackStartGate()
        gate.resumeCounted(CountedPlayback(trackId = 1, occurrenceId = "a"))
        gate.onMediaItemTransition(1, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "a")
        assertEquals("a", gate.countedOccurrence(currentTrackId = 1))
        assertNull(gate.trackStarted(isPlaying = true, trackId = 1))

        // Moving on is a new listen, and so is coming back to that entry.
        gate.onMediaItemTransition(2, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, "b")
        assertNull(gate.countedOccurrence(currentTrackId = 2))
        assertEquals(2L, gate.trackStarted(isPlaying = true, trackId = 2))
        assertEquals("b", gate.countedOccurrence(currentTrackId = 2))
        gate.onMediaItemTransition(1, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, "a")
        assertEquals(1L, gate.trackStarted(isPlaying = true, trackId = 1))
    }

    @Test
    fun `a saved listen does not hold back another queue or another entry`() {
        // A new queue, even of the same song, has new occurrences.
        val newQueue = PlaybackStartGate()
        newQueue.resumeCounted(CountedPlayback(trackId = 1, occurrenceId = "a"))
        newQueue.onMediaItemTransition(1, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "c")
        assertNull(newQueue.countedOccurrence(currentTrackId = 1))
        assertEquals(1L, newQueue.trackStarted(isPlaying = true, trackId = 1))

        // The saved queue had ended, so the restore starts its next entry.
        val nextEntry = PlaybackStartGate()
        nextEntry.resumeCounted(CountedPlayback(trackId = 1, occurrenceId = "a"))
        nextEntry.onMediaItemTransition(2, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "d")
        assertEquals(2L, nextEntry.trackStarted(isPlaying = true, trackId = 2))
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
