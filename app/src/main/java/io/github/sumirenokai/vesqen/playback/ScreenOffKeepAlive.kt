package io.github.sumirenokai.vesqen.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * D13 (#333): the keep-alive switch, off by default, and the one-time guidance on vivo-family phones,
 * whose ROM freezes an app about 23 s after the screen goes off while Vesqen holds the DAC itself.
 */
internal class ScreenOffKeepAliveSettings(context: Context, val vivoFamily: Boolean = isVivoFamily()) {
    private val preferences = context.getSharedPreferences("direct_usb_keep_alive", Context.MODE_PRIVATE)
    private val enabledState = MutableStateFlow(preferences.getBoolean(ENABLED, false))
    private val guidanceState = MutableStateFlow(vivoFamily && !preferences.getBoolean(GUIDANCE_FINISHED, false))

    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    /** The first own-output playback on a vivo-family phone explains both remedies once. */
    val guidanceDue: StateFlow<Boolean> = guidanceState.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(ENABLED, enabled) }
        enabledState.value = enabled
    }

    fun finishGuidance() {
        preferences.edit { putBoolean(GUIDANCE_FINISHED, true) }
        guidanceState.value = false
    }

    private companion object {
        const val ENABLED = "enabled"
        const val GUIDANCE_FINISHED = "guidance_finished"
    }
}

/** iQOO phones report vivo as their manufacturer. */
internal fun isVivoFamily(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): Boolean =
    manufacturer.equals("vivo", ignoreCase = true) || brand.equals("vivo", ignoreCase = true) || brand.equals("iqoo", ignoreCase = true)

/**
 * Runs the keep-alive track only while the switch is on, the own output is delivering and the screen
 * is off (the owner's rule, 2026-11-06). Any of them ending stops it at once. Main thread only.
 */
internal class ScreenOffKeepAlive(private val startTrack: () -> AutoCloseable?) {
    private var enabled = false
    private var delivering = false
    private var screenOn = true
    private var track: AutoCloseable? = null

    /** A track that could not start is not retried until one of the conditions changes. */
    private var refused = false

    val running: Boolean get() = track != null

    fun update(enabled: Boolean = this.enabled, delivering: Boolean = this.delivering, screenOn: Boolean = this.screenOn) {
        if (enabled == this.enabled && delivering == this.delivering && screenOn == this.screenOn) return
        this.enabled = enabled
        this.delivering = delivering
        this.screenOn = screenOn
        refused = false
        if (enabled && delivering && !screenOn) start() else stop()
    }

    fun close() {
        enabled = false
        stop()
    }

    private fun start() {
        if (track != null || refused) return
        track = startTrack()
        refused = track == null
    }

    private fun stop() {
        val running = track ?: return
        track = null
        running.close()
    }
}

/** Feeds [ScreenOffKeepAlive] from the screen, the strict output's status and the switch. */
internal class ScreenOffKeepAliveController(
    context: Context,
    private val handler: Handler,
    private val keepAlive: ScreenOffKeepAlive,
) {
    private val context = context.applicationContext
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> keepAlive.update(screenOn = false)
                Intent.ACTION_SCREEN_ON -> keepAlive.update(screenOn = true)
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this.context, screen, filter, null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)
        val interactive = this.context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        keepAlive.update(screenOn = interactive)
    }

    val statusListener: (UsbOutputStatus) -> Unit = { status ->
        val delivering = status.phase == UsbOutputPhase.ACTIVE && status.decisionCode == DIRECT_USB_ACTIVE
        handler.post { keepAlive.update(delivering = delivering) }
    }

    fun setEnabled(enabled: Boolean) {
        handler.post { keepAlive.update(enabled = enabled) }
    }

    fun close() {
        context.unregisterReceiver(screen)
        keepAlive.close()
    }

    private companion object {
        const val DIRECT_USB_ACTIVE = "direct_usb.active"
    }
}

/**
 * The keep-alive signal as measured on the iQOO 9 on 2026-11-05: 22 kHz at -20 dBFS, attenuated by
 * 80 dB with the track's own volume, on the phone's speaker. The ROM judges the samples before that
 * volume, but treats a track at volume 0 as silent; written at -30 dBFS it still freezes the app.
 * The owner heard only the speaker's noise floor at full volume. It never touches the USB data.
 */
internal class SpeakerKeepAliveTrack private constructor(private val track: AudioTrack) : AutoCloseable {
    @Volatile
    private var writing = true

    private val writer = thread(name = "vesqen-keep-alive") {
        // 100 ms holds whole periods of 22 kHz at 48 kHz, so the loop joins without a step.
        val tone = ByteBuffer.allocateDirect(BUFFER_FRAMES * FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val samples = tone.asShortBuffer()
        for (frame in 0 until BUFFER_FRAMES) {
            val sample = (LEVEL * Short.MAX_VALUE * sin(2 * PI * TONE_HZ * frame / SAMPLE_RATE_HZ)).roundToInt().toShort()
            samples.put(frame * 2, sample)
            samples.put(frame * 2 + 1, sample)
        }
        while (writing) {
            tone.clear()
            while (writing && tone.hasRemaining()) {
                if (track.write(tone, tone.remaining(), AudioTrack.WRITE_BLOCKING) < 0) return@thread
            }
        }
    }

    /** For the device test: where the system sends it, and whether it plays. */
    internal fun routedDeviceType(): Int? = track.routedDevice?.type

    internal fun playing(): Boolean = track.playState == AudioTrack.PLAYSTATE_PLAYING

    override fun close() {
        writing = false
        // Pausing and flushing returns a blocked write, so the writer sees the flag.
        track.pause()
        track.flush()
        writer.join(JOIN_TIMEOUT_MS)
        track.release()
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 48_000
        private const val TONE_HZ = 22_000
        private const val LEVEL = 0.1 // -20 dBFS
        private const val GAIN = 1e-4f // -80 dB
        private const val FRAME_BYTES = 4
        private const val BUFFER_FRAMES = SAMPLE_RATE_HZ / 10
        private const val JOIN_TIMEOUT_MS = 500L

        /**
         * Null when the phone offers no speaker or the track cannot be set up as measured. The caller
         * then does not retry until a condition changes; Settings does not report it yet (#333).
         */
        fun start(context: Context): SpeakerKeepAliveTrack? {
            val audio = context.getSystemService(AudioManager::class.java) ?: return null
            // Without a preferred device the system would pick the USB route, whose card is gone.
            val speaker = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return null
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: UnsupportedOperationException) {
                return null // The format the measurement used is not offered: nothing to fall back to.
            }
            // A gain of 0, or a track the system would route elsewhere, would not keep the app alive.
            val ready = try {
                track.setPreferredDevice(speaker) && track.setVolume(GAIN) == AudioTrack.SUCCESS &&
                    run { track.play(); track.playState == AudioTrack.PLAYSTATE_PLAYING }
            } catch (_: IllegalStateException) {
                false // The track did not initialize.
            }
            if (!ready) {
                track.release()
                return null
            }
            return SpeakerKeepAliveTrack(track)
        }
    }
}
