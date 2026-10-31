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
        if (loadSavedState() == null) return null
        val tracks = readPlayableTracks()
        // Opening the catalog may replay a merge committed just before the previous process died.
        // The pre-read queue can still name a row that no longer exists (#191 A5).
        return loadSavedState()?.restoreAgainst(tracks)
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
 * startForegroundService(), even when playback starts later, or it stops the app. Media3 enters the
 * foreground only once playback is under way. When a queue has to be restored first, which can
 * take longer than Android allows, the service enters the foreground itself as the press arrives,
 * with a hidden notification Media3 replaces when playback starts. A loaded queue starts playing
 * well within the limit. Either way, a resume that finds no playable queue, a strict USB failure
 * (PRD 9.2) or a playback error leaves the foreground to the service.
 */
internal class MediaButtonStartObligation {
    enum class Ending {
        /** Nothing is owed, or Media3 runs the foreground. */
        NONE,
        /** The service entered the foreground for a restore and must leave it, removing its notification. */
        LEAVE_FOREGROUND,
        /** The service never entered the foreground and must enter and leave it once to meet the start. */
        ENTER_AND_LEAVE,
    }

    private var open = false
    private var enteredForeground = false

    /** A media button started the service; [enteredForeground] when it already entered for a restore. */
    fun open(enteredForeground: Boolean) {
        open = true
        this.enteredForeground = enteredForeground
    }

    /** Playback started; Media3 keeps the service in the foreground from here. */
    fun playbackStarted() {
        open = false
    }

    /** The attempt ended without playback. [mediaInForeground]: Media3 already runs the foreground. */
    fun endWithoutPlayback(mediaInForeground: Boolean): Ending {
        val ending = when {
            !open || mediaInForeground -> Ending.NONE
            enteredForeground -> Ending.LEAVE_FOREGROUND
            else -> Ending.ENTER_AND_LEAVE
        }
        open = false
        return ending
    }
}
