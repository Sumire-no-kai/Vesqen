package io.github.sumirenokai.vesqen.playback

import android.media.AudioManager
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi

/** Audio modes cover telephony and VoIP without accessing phone numbers or requesting phone permissions. */
internal class UsbCommunicationMonitor(
    private val audio: AudioManager,
    private val handler: Handler,
    private val onChanged: (Boolean) -> Unit,
) {
    private var enabled = false
    private var active = false
    private var registration: ModeRegistration? = null

    // API 28–30 has getMode() but no mode-change callback. Check only while direct USB is in use
    // or awaiting communication to end, at the same 250 ms cadence as session fault monitoring.
    private val poll = object : Runnable {
        override fun run() {
            if (!enabled) return
            update(audio.mode)
            if (enabled) handler.postDelayed(this, 250)
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) {
            if (value) update(audio.mode)
            return
        }
        enabled = value
        if (value) {
            if (Build.VERSION.SDK_INT >= 31) {
                registration = ModeRegistration(audio, handler, ::update)
                update(audio.mode)
            } else {
                poll.run()
            }
        } else {
            handler.removeCallbacks(poll)
            if (Build.VERSION.SDK_INT >= 31) registration?.close()
            registration = null
        }
    }

    private fun update(mode: Int) {
        if (!enabled) return
        val next = mode != AudioManager.MODE_NORMAL
        if (next == active) return
        active = next
        onChanged(next)
    }

    fun close() = setEnabled(false)

    @RequiresApi(31)
    private class ModeRegistration(audio: AudioManager, handler: Handler, callback: (Int) -> Unit) {
        private val audio = audio
        private val listener = AudioManager.OnModeChangedListener(callback)
        init { audio.addOnModeChangedListener({ task -> handler.post(task) }, listener) }
        fun close() = audio.removeOnModeChangedListener(listener)
    }
}
