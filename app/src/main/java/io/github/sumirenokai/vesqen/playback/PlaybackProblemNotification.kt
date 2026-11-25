package io.github.sumirenokai.vesqen.playback

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.sumirenokai.vesqen.MainActivity
import io.github.sumirenokai.vesqen.R

/**
 * #344: tells a listener who is not looking at the app why playback stopped (owner, 2026-11-24).
 * Opening it shows the app's own explanation, which replaces it. Quiet: no sound or vibration.
 */
internal class PlaybackProblemNotification(private val context: Context) {
    fun show(interruption: PlaybackInterruption) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.playback_problem_channel))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .build(),
        )
        val title = interruption.trackTitle.ifBlank { context.getString(R.string.unknown_title) }
        val text = context.getString(interruption.kind.notificationText())
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_vesqen)
                .setContentTitle(context.getString(R.string.playback_problem_notification_title, title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setSilent(true)
                .build(),
        )
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "playback_problems"

        /** Media3 uses 1001 and 20938; the service's rejected start uses 20939. */
        const val NOTIFICATION_ID = 20940
    }
}

internal fun PlaybackProblem.notificationText(): Int = when (this) {
    PlaybackProblem.UNPLAYABLE_TRACK -> R.string.playback_problem_notification_unplayable
    else -> message()
}

/** The explanation each problem shows in the app's dialog. */
internal fun PlaybackProblem.message(): Int = when (this) {
    PlaybackProblem.SOURCE_UNAVAILABLE -> R.string.playback_problem_source
    PlaybackProblem.UNSUPPORTED_FORMAT -> R.string.playback_problem_format
    PlaybackProblem.DECODER_FAILURE, PlaybackProblem.UNPLAYABLE_TRACK -> R.string.playback_problem_decoder
    PlaybackProblem.UNKNOWN -> R.string.playback_problem_unknown
}
