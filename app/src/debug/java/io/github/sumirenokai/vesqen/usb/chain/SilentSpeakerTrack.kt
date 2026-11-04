package io.github.sumirenokai.vesqen.usb.chain

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The iQOO 9 freezes the app about 20 s after the screen goes off, foreground service and wake lock
 * notwithstanding, when the system can see no audio from it. This plays digital silence to the
 * phone's speaker so that it can. It never touches the USB data. Without the preferred device the
 * system would send it to the USB route, where the card no longer exists.
 */
internal class SilentSpeakerTrack(audioManager: AudioManager, level: Int = 0) {
    private val track = AudioTrack.Builder()
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

    @Volatile
    private var writing = true
    private val writer: Thread

    init {
        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        check(speaker != null && track.setPreferredDevice(speaker)) { "No speaker to prefer" }
        track.play()
        writer = thread(name = "silent-speaker") {
            val silence = ByteBuffer.allocateDirect(TONE_PERIOD_FRAMES * TONE_PERIODS * FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            // A level above zero shows whether the ROM looks at the samples as well as the track.
            val samples = silence.asShortBuffer()
            for (index in 0 until samples.capacity()) {
                samples.put(index, (level * sin(2 * PI * (index / 2) / TONE_PERIOD_FRAMES)).roundToInt().toShort())
            }
            while (writing) {
                silence.clear()
                while (writing && silence.hasRemaining()) {
                    if (track.write(silence, silence.remaining(), AudioTrack.WRITE_BLOCKING) < 0) return@thread
                }
            }
        }
    }

    fun describe(): String = "routed to ${track.routedDevice?.type}, state ${track.playState}, head ${track.playbackHeadPosition}"

    fun close() {
        writing = false
        track.pause()
        track.flush()
        writer.join()
        track.release()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val FRAME_BYTES = 4
        const val TONE_PERIOD_FRAMES = 48
        const val TONE_PERIODS = 100
    }
}
