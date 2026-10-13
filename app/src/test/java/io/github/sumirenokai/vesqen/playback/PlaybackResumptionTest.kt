package io.github.sumirenokai.vesqen.playback

import android.content.Intent
import android.view.KeyEvent
import io.github.sumirenokai.vesqen.library.AudioTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlaybackResumptionTest {
    @Test
    fun `nothing saved restores nothing and leaves the catalog unread`() = runBlocking {
        val resumption = PlaybackResumption(
            loadSavedState = { null },
            readPlayableTracks = { fail("The catalog must not be read without a saved queue"); emptyList() },
        )

        assertNull(resumption.restore())
    }

    @Test
    fun `saved queue comes back with its occurrence, position and play order`() = runBlocking {
        val saved = PersistedPlaybackState(
            queueTrackIds = listOf(1, 2, 3, 1),
            currentTrackId = 1,
            currentQueueIndex = 3,
            positionMs = 42_000,
            shuffleEnabled = true,
            repeatMode = PlaybackRepeatMode.ALL,
        )
        // Track 2 is no longer in the Library, for example its folder grant was removed.
        val restored = PlaybackResumption({ saved }, { listOf(track(3), track(1)) }).restore()

        requireNotNull(restored)
        assertEquals(listOf(1L, 3L, 1L), restored.tracks.map(AudioTrack::id))
        assertEquals(2, restored.startIndex)
        assertEquals(42_000, restored.positionMs)
        assertTrue(restored.shuffleEnabled)
        assertEquals(PlaybackRepeatMode.ALL, restored.repeatMode)
    }

    @Test
    fun `saved queue whose tracks the Library cannot read restores nothing`() = runBlocking {
        val saved = PersistedPlaybackState(
            queueTrackIds = listOf(7, 8),
            currentTrackId = 8,
            positionMs = 1_000,
            shuffleEnabled = false,
            repeatMode = PlaybackRepeatMode.OFF,
        )

        assertNull(PlaybackResumption({ saved }, { emptyList() }).restore())
        assertNull(PlaybackResumption({ saved }, { listOf(track(1)) }).restore())
    }

    @Test
    fun `only a Play press may start the service`() {
        val playKeys = listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
        )
        playKeys.forEach { key ->
            assertTrue(mediaButtonMayStartPlayback(key, hasCurrentItem = false, hasSavedQueue = { true }))
        }
        val otherKeys = listOf(
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            null,
        )
        otherKeys.forEach { key ->
            assertFalse(mediaButtonMayStartPlayback(key, hasCurrentItem = true, hasSavedQueue = { true }))
        }
    }

    @Test
    fun `a Play press needs a queue to start the service`() {
        assertFalse(
            mediaButtonMayStartPlayback(KeyEvent.KEYCODE_MEDIA_PLAY, hasCurrentItem = false, hasSavedQueue = { false }),
        )
        assertTrue(
            mediaButtonMayStartPlayback(
                KeyEvent.KEYCODE_MEDIA_PLAY,
                hasCurrentItem = true,
                hasSavedQueue = { fail("A current queue needs no saved one"); false },
            ),
        )
    }

    @Test
    fun `a connected controller cannot pass as a media button start`() {
        assertTrue(isMediaButtonStartPlaceholder(isPackageNameVerified = false, Intent.ACTION_MEDIA_BUTTON))
        // Any controller can send connection hints, but its package name is always verified.
        assertFalse(isMediaButtonStartPlaceholder(isPackageNameVerified = true, Intent.ACTION_MEDIA_BUTTON))
        // Legacy browsers also arrive as placeholders, without the media button hint.
        assertFalse(isMediaButtonStartPlaceholder(isPackageNameVerified = false, controllerInfoType = null))
    }

    @Test
    fun `strict failures before a Play request do not end a media button start`() {
        assertTrue(strictFailure(UsbOutputFailureOrigin.USER_PLAYBACK).stopsRequestedPlayback())
        assertTrue(strictFailure(UsbOutputFailureOrigin.ROUTE_CHANGE).stopsRequestedPlayback())
        assertFalse(strictFailure(UsbOutputFailureOrigin.SERVICE_START).stopsRequestedPlayback())
        assertFalse(strictFailure(UsbOutputFailureOrigin.QUEUE_RESTORE).stopsRequestedPlayback())
        assertFalse(
            UsbOutputStatus(
                mode = UsbOutputMode.STRICT_BIT_PERFECT,
                phase = UsbOutputPhase.APPLYING,
                decisionCode = "strict_usb.reconfiguration_requested",
            ).stopsRequestedPlayback(),
        )
    }

    @Test
    fun `a media button start that ends without playback is owed once`() {
        val obligation = MediaButtonStartObligation()
        obligation.open()

        assertTrue(obligation.endWithoutPlayback(inForeground = false))
        assertFalse(obligation.endWithoutPlayback(inForeground = false))
    }

    @Test
    fun `started playback or the foreground settles a media button start`() {
        val started = MediaButtonStartObligation().apply {
            open()
            playbackStarted()
        }
        assertFalse(started.endWithoutPlayback(inForeground = false))

        val inForeground = MediaButtonStartObligation().apply { open() }
        assertFalse(inForeground.endWithoutPlayback(inForeground = true))
        assertFalse(inForeground.endWithoutPlayback(inForeground = false))

        assertFalse(MediaButtonStartObligation().endWithoutPlayback(inForeground = false))
    }

    private fun strictFailure(origin: UsbOutputFailureOrigin) = UsbOutputStatus(
        mode = UsbOutputMode.STRICT_BIT_PERFECT,
        phase = UsbOutputPhase.FAILED,
        failure = UsbOutputFailure.NO_USB_AUDIO_DEVICE,
        decisionCode = "strict_usb.no_usb_audio_device",
        failureOrigin = origin,
    )

    private fun track(id: Long) = AudioTrack(
        id = id,
        contentUri = "content://track/$id",
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        durationMs = 60_000,
    )
}
