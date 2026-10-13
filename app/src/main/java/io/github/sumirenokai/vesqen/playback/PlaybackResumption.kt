package io.github.sumirenokai.vesqen.playback

import android.content.Intent
import android.view.KeyEvent
import io.github.sumirenokai.vesqen.library.AudioTrack

/**
 * #52: rebuilds the saved queue for Media3 playback resumption after Android ended the process.
 *
 * Media3 asks for it only when a Play request (a headset or Bluetooth button, the media
 * notification) reaches a player without a current item. Restoring never prepares or plays the
 * player: Media3 sets the returned queue and starts playback for that one request.
 */
internal class PlaybackResumption(
    private val loadSavedState: () -> PersistedPlaybackState?,
    private val readPlayableTracks: suspend () -> List<AudioTrack>,
) {
    /**
     * Applies the same rules as reopening the app: only tracks the Library can read now are kept,
     * with the saved occurrence, position, shuffle and repeat mode. Null when nothing saved can
     * be played. The catalog is read only when a queue was saved.
     */
    suspend fun restore(): RestoredPlaybackQueue? {
        val saved = loadSavedState() ?: return null
        return saved.restoreAgainst(readPlayableTracks())
    }
}

/**
 * Whether a media button may start this service. Only a Play press counts as a request to play:
 * the same keys Media3's MediaButtonReceiver forwards on Android 8+. Next, previous, pause or stop
 * after the process ended do nothing, and a Play press needs a queue to play.
 */
internal fun mediaButtonMayStartPlayback(
    keyCode: Int?,
    hasCurrentItem: Boolean,
    hasSavedQueue: () -> Boolean,
): Boolean = keyCode in PLAY_KEY_CODES && (hasCurrentItem || hasSavedQueue())

private val PLAY_KEY_CODES = setOf(
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_HEADSETHOOK,
)

/**
 * Media3 describes a media button that starts the service with a placeholder controller. A real
 * controller always has a verified package name, so it cannot pass as one by copying the hint.
 */
internal fun isMediaButtonStartPlaceholder(
    isPackageNameVerified: Boolean,
    controllerInfoType: String?,
): Boolean = !isPackageNameVerified && controllerInfoType == Intent.ACTION_MEDIA_BUTTON

/**
 * A strict output failure that stopped a requested playback. Failures at service start or while a
 * queue is restored paused happen before any Play request and do not end a media button start.
 */
internal fun UsbOutputStatus.stopsRequestedPlayback(): Boolean =
    failure != null &&
        failureOrigin != UsbOutputFailureOrigin.SERVICE_START &&
        failureOrigin != UsbOutputFailureOrigin.QUEUE_RESTORE

/**
 * Android requires startForeground() within seconds once a media button starts this service with
 * startForegroundService(), even when nothing ends up playing, or it stops the app. Media3 enters
 * the foreground only for ongoing playback. A resume that finds no playable queue, a strict USB
 * failure (PRD 9.2) or a playback error therefore leaves the requirement to the service.
 */
internal class MediaButtonStartObligation {
    private var open = false

    /** A media button started the service while it was not in the foreground. */
    fun open() {
        open = true
    }

    /** Playback started; Media3 keeps the service in the foreground from here. */
    fun playbackStarted() {
        open = false
    }

    /**
     * The attempt ended without playback. True when the service must enter and leave the
     * foreground itself to meet Android's requirement.
     */
    fun endWithoutPlayback(inForeground: Boolean): Boolean {
        val owed = open && !inForeground
        open = false
        return owed
    }
}
