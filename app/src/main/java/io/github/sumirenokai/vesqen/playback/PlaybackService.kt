package io.github.sumirenokai.vesqen.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.MainActivity
import io.github.sumirenokai.vesqen.VesqenApplication

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var playbackStateKeeper: PlaybackStateKeeper? = null
    private var sessionArtworkLoader: SessionArtworkLoader? = null
    private var usbOutputCoordinator: UsbOutputCoordinator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbOutputCommand = SessionCommand(UsbOutputSessionContract.SET_MODE_ACTION, android.os.Bundle.EMPTY)
    private val outputStateListener: (UsbOutputStatus) -> Unit = { status ->
        mainHandler.post {
            mediaSession?.setSessionExtras(UsbOutputSessionContract.toBundle(status))
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            // Media3 1.11's deprecated onConnect default is a sentinel with empty commands.
            // Resolve the controller's trust-aware defaults before adding our private command.
            val base = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
            if (controller.packageName != packageName) return Futures.immediateFuture(base)
            return Futures.immediateFuture(MediaSession.ConnectionResult.accept(
                base.availableSessionCommands.buildUpon().add(usbOutputCommand).build(),
                base.availablePlayerCommands,
            ))
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            if (
                controller.packageName != packageName ||
                customCommand.customAction != UsbOutputSessionContract.SET_MODE_ACTION
            ) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            val mode = UsbOutputSessionContract.readMode(args)
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            return applyUsbOutputMode(mode)
        }
    }

    private fun applyUsbOutputMode(mode: UsbOutputMode): ListenableFuture<SessionResult> {
        fun apply(): SessionResult {
            usbOutputCoordinator?.requestMode(mode)
            val extras = UsbOutputSessionContract.toBundle(
                usbOutputCoordinator?.currentStatus() ?: UsbOutputStatus(),
            )
            return SessionResult(SessionResult.RESULT_SUCCESS, extras)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Futures.immediateFuture(apply())
        }
        val result = SettableFuture.create<SessionResult>()
        if (!mainHandler.post { result.set(apply()) }) {
            result.set(SessionResult(SessionError.ERROR_SESSION_DISCONNECTED))
        }
        return result
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .build()
            .apply { setSmallIcon(R.drawable.ic_notification_vesqen) }
        setMediaNotificationProvider(notificationProvider)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val telemetry = (application as VesqenApplication).telemetryRuntime
        val dataSourceFactory = DefaultDataSource.Factory(this)
            .setTransferListener(telemetry.transferListener)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val outputStateRepository = (application as VesqenApplication).usbOutputStateRepository
        val outputCoordinator = UsbOutputCoordinator(this, outputStateRepository).also {
            usbOutputCoordinator = it
        }
        val player = ExoPlayer.Builder(this, VesqenAudioRenderersFactory(this, outputCoordinator))
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setAudioAttributes(audioAttributes, true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                pauseAtEndOfMediaItems = false
            }
        telemetry.attachPlayer(player)
        outputCoordinator.attachPlayer(player)
        playbackStateKeeper = PlaybackStateKeeper(
            player = player,
            stateStore = PlaybackStateStore(this),
            handler = Handler(Looper.getMainLooper()),
            onPlaybackStarted = (application as VesqenApplication)
                .playbackHistoryRecorder::recordPlayback,
        ).also(PlaybackStateKeeper::start)
        val artworkLoader = SessionArtworkLoader(this).also { sessionArtworkLoader = it }
        val sessionActivity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSession.Builder(this, player)
            .setBitmapLoader(artworkLoader)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .setSessionExtras(UsbOutputSessionContract.toBundle(outputStateRepository.snapshot()))
            .build()
        outputStateRepository.addListener(outputStateListener)
    }

    @UnstableApi
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession.takeIf {
            // The service remains exported so Android system controls, media buttons, trusted
            // assistants and this app can reach it. An arbitrary local app does not need read
            // access to the user's current title or queue.
            controllerInfo.isTrusted
        }

    override fun onDestroy() {
        (application as VesqenApplication).usbOutputStateRepository.removeListener(outputStateListener)
        usbOutputCoordinator?.close()
        usbOutputCoordinator = null
        playbackStateKeeper?.stop()
        playbackStateKeeper = null
        mediaSession?.run {
            (application as VesqenApplication).telemetryRuntime.detachPlayer(player as ExoPlayer)
            player.release()
            release()
        }
        mediaSession = null
        sessionArtworkLoader?.close()
        sessionArtworkLoader = null
        super.onDestroy()
    }
}
