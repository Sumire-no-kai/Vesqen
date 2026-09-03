package io.github.sumirenokai.vesqen.playback

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
import io.github.sumirenokai.vesqen.VesqenApplication

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

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
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            (application as VesqenApplication).telemetryRuntime.detachPlayer(player as ExoPlayer)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
