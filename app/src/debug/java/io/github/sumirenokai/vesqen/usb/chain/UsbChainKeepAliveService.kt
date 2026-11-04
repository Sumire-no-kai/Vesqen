package io.github.sumirenokai.vesqen.usb.chain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper

/** Gives a long chain run the same standing with the system as the app's playback service. */
@androidx.annotation.OptIn(UnstableApi::class)
class UsbChainKeepAliveService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel(CHANNEL, "USB chain test", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("USB chain test")
            .apply { mediaStyleSession?.let { setStyle(MediaStyleNotificationHelper.MediaStyle(it)) } }
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    companion object {
        /** Set before the service starts, to show its notification in the media style. */
        @Volatile
        internal var mediaStyleSession: MediaSession? = null

        private const val CHANNEL = "usb-chain-test"
        private const val NOTIFICATION_ID = 7102
    }
}
