package io.github.sumirenokai.vesqen.usb.chain

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import io.github.sumirenokai.vesqen.usb.engine.UsbMediaClock
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.pcm.ExactPcmConverter
import io.github.sumirenokai.vesqen.usb.pcm.UsbPcmEncoding
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** One stream of URBs. A stream closed underneath its output stops accepting data without failing. */
internal interface UsbOutputStream {
    fun write(buffer: ByteBuffer, offset: Int, size: Int): Int
    fun setRunning(running: Boolean)
    fun markEndOfStream()
    fun readStats(): UsbStreamStats?
}

internal interface UsbOutputStreams {
    /**
     * Calls back off the playback thread, after every earlier stream has been reclaimed, so old
     * audio cannot follow new audio. Null means no stream can be opened.
     */
    fun open(onOpened: (UsbOutputStream?) -> Unit)
    fun close(stream: UsbOutputStream)
}

/**
 * M7-2: the output Media3 writes to on Vesqen's own USB path. Each output has its own stream, so a
 * seek or a format change starts from an empty ring with counters at zero. Everything except the
 * callback from [UsbOutputStreams.open] runs on the playback thread.
 */
@UnstableApi
internal class DirectUsbAudioOutput(
    private val plan: StreamingPlan,
    encoding: UsbPcmEncoding,
    private val streams: UsbOutputStreams,
    private val onFailure: (UsbStreamStats) -> Unit = {},
    private val onVolumeIgnored: (Float) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) : AudioOutput {
    private val converter = ExactPcmConverter(encoding, plan.channels, plan.bitResolution, plan.subslotBytes)
    private val clock = UsbMediaClock(plan.sampleRateHz)
    private val scratch = ByteBuffer.allocateDirect(SCRATCH_FRAMES * plan.frameBytes).also { it.limit(0) }
    private val prefillFrames = plan.sampleRateHz / PREFILL_DIVISOR
    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()
    private val lock = Any()

    private var stream: UsbOutputStream? = null
    private var openFailed = false
    private var released = false

    private var wantRunning = false
    private var running = false
    private var endRequested = false
    private var endMarked = false
    private var acceptedFrames = 0L
    private var failureReported = false

    init {
        streams.open { opened ->
            val unwanted = synchronized(lock) {
                stream = opened
                openFailed = opened == null
                released
            }
            if (unwanted && opened != null) streams.close(opened)
        }
    }

    override fun play() {
        wantRunning = true
        current()?.let(::settle)
    }

    override fun pause() {
        wantRunning = false
        current()?.let(::settle)
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val target = synchronized(lock) {
            if (released) throw AudioOutput.WriteException(ERROR_RELEASED, false)
            if (openFailed) throw AudioOutput.WriteException(ERROR_NO_STREAM, false)
            stream
        } ?: return false
        settle(target)
        target.readStats()?.let { stats ->
            if (stats.failure != UsbStreamStats.Failure.NONE) {
                reportFailure(stats)
                throw AudioOutput.WriteException(ERROR_STREAM_FAILED, false)
            }
        }
        while (true) {
            if (scratch.hasRemaining()) {
                val accepted = target.write(scratch, scratch.position(), scratch.remaining())
                if (accepted < 0) throw AudioOutput.WriteException(ERROR_STREAM_ENDED, false)
                scratch.position(scratch.position() + accepted)
                acceptedFrames += accepted / plan.frameBytes
                settle(target)
                if (scratch.hasRemaining()) return false
            }
            if (!buffer.hasRemaining()) return true
            scratch.clear()
            val exact = converter.convert(buffer, scratch)
            scratch.flip()
            // A leftover smaller than a frame could never leave the buffer.
            if (!exact || !scratch.hasRemaining()) throw AudioOutput.WriteException(ERROR_INEXACT_PCM, false)
        }
    }

    /** Media3 1.11 does not call this: a seek releases the output. */
    override fun flush() = Unit

    override fun stop() {
        endRequested = true
        current()?.let(::settle)
    }

    override fun release() {
        val closing = synchronized(lock) {
            if (released) return
            released = true
            stream
        }
        closing?.let(streams::close)
        listeners.forEach(AudioOutput.Listener::onReleased)
    }

    /** The volume stays at the DAC. Only the focus duck factor can arrive here, and it is ignored (D11). */
    override fun setVolume(volume: Float) {
        if (volume != 1f) onVolumeIgnored(volume)
    }

    override fun getPositionUs(): Long {
        val target = current() ?: return 0
        settle(target)
        val stats = target.readStats() ?: return 0
        if (stats.failure != UsbStreamStats.Failure.NONE) reportFailure(stats)
        val frames = clock.positionFrames(stats.mediaFramesProcessed, stats.mediaFramesSubmitted, running, nanoTime())
        return Util.sampleCountToDurationUs(frames, plan.sampleRateHz)
    }

    /** Stalls are the stream's to report: Media3 would rebuild a stalled output by itself. */
    override fun isStalled() = false

    override fun isOffloadedPlayback() = false

    override fun getAudioSessionId() = C.AUDIO_SESSION_ID_UNSET

    override fun getSampleRate() = plan.sampleRateHz

    override fun getBufferSizeInFrames() = plan.ringFrames.toLong()

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit

    override fun setOffloadEndOfStream() = Unit

    override fun attachAuxEffect(effectId: Int) = Unit

    override fun setAuxEffectSendLevel(level: Float) = Unit

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit

    private fun current(): UsbOutputStream? = synchronized(lock) { stream }

    /** Running waits for a little audio in the ring, so the start is not counted as an underrun. */
    private fun settle(target: UsbOutputStream) {
        if (endRequested && !endMarked) {
            target.markEndOfStream()
            endMarked = true
        }
        val ready = endMarked || acceptedFrames >= prefillFrames
        if (wantRunning != running && ready) {
            target.setRunning(wantRunning)
            running = wantRunning
        }
    }

    private fun reportFailure(stats: UsbStreamStats) {
        if (failureReported) return
        failureReported = true
        onFailure(stats)
    }

    companion object {
        const val ERROR_RELEASED = 1
        const val ERROR_NO_STREAM = 2
        const val ERROR_STREAM_FAILED = 3
        const val ERROR_STREAM_ENDED = 4
        const val ERROR_INEXACT_PCM = 5

        private const val SCRATCH_FRAMES = 2048
        private const val PREFILL_DIVISOR = 10
    }
}

/** Answers from the plan: the system has no card to ask once the device is claimed. */
@UnstableApi
internal class DirectUsbAudioOutputProvider(
    private val plan: StreamingPlan,
    private val streams: UsbOutputStreams,
    private val onFailure: (UsbStreamStats) -> Unit = {},
    private val onVolumeIgnored: (Float) -> Unit = {},
) : AudioOutputProvider {
    private fun encodingOf(pcmEncoding: Int): UsbPcmEncoding? = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT -> UsbPcmEncoding.INT16
        C.ENCODING_PCM_24BIT -> UsbPcmEncoding.INT24
        C.ENCODING_PCM_32BIT -> UsbPcmEncoding.INT32
        C.ENCODING_PCM_FLOAT -> UsbPcmEncoding.FLOAT
        else -> null
    }

    private fun matchesPlan(formatConfig: AudioOutputProvider.FormatConfig): Boolean {
        val format = formatConfig.format
        return format.sampleRate == plan.sampleRateHz && format.channelCount == plan.channels &&
            encodingOf(format.pcmEncoding) != null
    }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(
                if (matchesPlan(formatConfig)) AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY else AudioOutputProvider.FORMAT_UNSUPPORTED,
            )
            .build()

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        val format = formatConfig.format
        if (!matchesPlan(formatConfig)) {
            throw AudioOutputProvider.ConfigurationException(
                "${format.sampleRate} Hz, ${format.channelCount} channels, encoding ${format.pcmEncoding} " +
                    "does not match the ${plan.sampleRateHz} Hz, ${plan.channels} channel plan",
            )
        }
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(format.pcmEncoding)
            .setSampleRate(format.sampleRate)
            .setChannelMask(Util.getAudioTrackChannelConfig(format.channelCount))
            .setBufferSize(plan.ringFrames * Util.getPcmFrameSize(format.pcmEncoding, format.channelCount))
            .setAudioAttributes(formatConfig.audioAttributes)
            .build()
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
        DirectUsbAudioOutput(plan, checkNotNull(encodingOf(config.encoding)), streams, onFailure, onVolumeIgnored)

    override fun addListener(listener: AudioOutputProvider.Listener) = Unit

    override fun removeListener(listener: AudioOutputProvider.Listener) = Unit

    override fun release() = Unit
}
