package io.github.sumirenokai.vesqen.playback

import android.os.Bundle
import android.os.Process
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import io.github.sumirenokai.vesqen.VesqenApplication
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Holds startup callbacks apart so a fast app or media button cannot win the notification race. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = VesqenApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class PlatformQueueStartupTest {
    private val lifecycle = Robolectric.buildService(PlaybackService::class.java)
    private val service = lifecycle.get()
    private val callback = ReflectionHelpers.getField<MediaSession.Callback>(service, "sessionCallback")
    private val app = controller(service.packageName)
    private val notification = controller(service.packageName, hints = Bundle().apply {
        putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true)
    })
    // Robolectric connects the service's notification synchronously. A separate session with the
    // shipped callback lets these tests hold its connect and post-connect phases apart.
    private val player = ExoPlayer.Builder(service).build()
    private val session = MediaSession.Builder(service, player).setId("startup-regression")
        .setCallback(callback).build()
    private var destroyed = false

    @After fun close() {
        if (!destroyed) lifecycle.destroy()
        session.release()
        player.release()
    }

    @Test fun frameworkRegistrationCanInitializeWithoutWaitingForAClient() {
        service.onCreate()
        val actualSession = requireNotNull(service.onGetSession(app))
        assertFalse("Rejected media-button starts must retain the lazy registration policy", service.isSessionAdded(actualSession))
        // MediaSessionService does this before forwarding a controller or media-button request.
        service.addSession(actualSession)
        assertTrue(callback.onConnectAsync(actualSession, app).isDone)
    }

    @Test fun appAndLegacyConnectionsWaitUntilPlatformCommandsHaveBeenApplied() {
        val appConnection = callback.onConnectAsync(session, app)
        val legacyConnection = callback.onConnectAsync(session, controller("com.android.bluetooth",
            version = MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION))
        assertFalse("The app must not enqueue before the queue cap exists", appConnection.isDone)
        assertFalse("A cold media button must not restore a queue early", legacyConnection.isDone)

        assertTrue(callback.onConnectAsync(session, notification).isDone)
        assertFalse("Returning a result precedes Media3 applying it", appConnection.isDone)
        // Media3 applies the notification's platform commands before calling onPostConnect.
        callback.onPostConnect(session, notification)
        assertTrue(appConnection.isDone)
        assertTrue(legacyConnection.isDone)
        assertTrue(appConnection.get().availablePlayerCommands.contains(Player.COMMAND_GET_TIMELINE))
        assertTrue(legacyConnection.get().availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE))
    }

    @Test fun cancellingOneConnectionDoesNotCancelOtherStartupConnections() {
        val abandoned = callback.onConnectAsync(session, app)
        val waiting = callback.onConnectAsync(session, app)
        abandoned.cancel(false)
        assertFalse(waiting.isDone)
        callback.onPostConnect(session, notification)
        assertTrue(waiting.isDone)
        assertFalse(waiting.isCancelled)
    }

    @Test fun destroyingTheServiceCancelsConnectionsWaitingForInitialization() {
        val waiting = callback.onConnectAsync(session, app)
        assertFalse(waiting.isDone)
        lifecycle.destroy()
        destroyed = true
        assertTrue(waiting.isCancelled)
    }

    private fun controller(packageName: String, version: Int = MediaLibraryInfo.VERSION_INT, hints: Bundle = Bundle.EMPTY) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(packageName, Process.myPid(), Process.myUid(),
            version, MediaLibraryInfo.INTERFACE_VERSION, true, hints, true)
}
