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
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.MainActivity
import io.github.sumirenokai.vesqen.VesqenApplication

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var playbackStateKeeper: PlaybackStateKeeper? = null
    private var sessionArtworkLoader: SessionArtworkLoader? = null

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
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            pauseAtEndOfMediaItems = false
        }
        telemetry.attachPlayer(player)
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
            .build()
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
