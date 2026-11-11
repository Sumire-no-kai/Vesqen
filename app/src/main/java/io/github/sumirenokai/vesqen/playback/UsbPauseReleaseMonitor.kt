package io.github.sumirenokai.vesqen.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Observes only a user-paused USB session. Every registration belongs to one pause interval. */
internal class UsbPauseReleaseMonitor(context: Context, private val handler: Handler, private val onRelease: () -> Unit) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var generation = 0L
    private var playback: AudioManager.AudioPlaybackCallback? = null
    private var screen: BroadcastReceiver? = null
    private var timeout: Runnable? = null

    fun setPaused(paused: Boolean) {
        if (!paused) { close(); return }
        if (timeout != null) return
        val epoch = ++generation
        fun release() {
            if (epoch != generation) return
            close()
            onRelease()
        }
        val deadline = Runnable { release() }
        timeout = deadline
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                if (configs.isNotEmpty()) release()
            }
        }
        playback = callback
        audio.registerAudioPlaybackCallback(callback, handler)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) release()
            }
        }
        screen = receiver
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed(deadline, 30_000)
        if (!power.isInteractive || audio.activePlaybackConfigurations.isNotEmpty()) release()
    }

    fun close() {
        generation++
        timeout?.let(handler::removeCallbacks)
        timeout = null
        playback?.let(audio::unregisterAudioPlaybackCallback)
        playback = null
        screen?.let(context::unregisterReceiver)
        screen = null
    }
}
