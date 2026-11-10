package io.github.sumirenokai.vesqen.playback

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import io.github.sumirenokai.vesqen.usb.engine.UsbMediaClock
import io.github.sumirenokai.vesqen.usb.pcm.ExactPcmConverter
import io.github.sumirenokai.vesqen.usb.pcm.UsbPcmEncoding
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import io.github.sumirenokai.vesqen.usb.session.UsbOutputHandle
import io.github.sumirenokai.vesqen.usb.session.asFailure
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M7-3: the output Media3 writes to on Vesqen's own USB path. It writes under its own epoch of the
 * session's stream, so after [release] (a seek or a format change) nothing it still holds reaches the
 * DAC, and the next output's audio waits until this one's URBs are back. When the session gives the
 * device back on purpose, writes simply stop being taken until the player is rebuilt; only a failure
 * fails them. Every call runs on the playback thread and none waits for USB. No release build uses it
 * before M7-4.
 */
@UnstableApi
class DirectUsbAudioOutput(
    private val plan: StreamingPlan,
    private val encoding: UsbPcmEncoding,
    /** The source file's bit depth, as [DirectUsbFormatGate] accepted it: the lattice every sample must lie on. */
    sourceBits: Int,
    /** Null when the session could not give an output; the first write then fails. */
    private val handle: UsbOutputHandle?,
    private val onFailure: (DirectUsbFailure) -> Unit = {},
    private val onVolumeIgnored: (Float) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    /**
     * The source depth the gate accepted for the format Media3 configured last, or 0 when the last
     * configuration was refused. Read when the sink keeps this output for that format (#282).
     */
    private val configuredSourceBits: () -> Int = { sourceBits },
    private val listenerFactory: (() -> PlaybackListener)? = null,
) : AudioOutput {
    interface PlaybackListener {
        fun onActiveChanged(active: Boolean)
        fun onFailed(failure: DirectUsbFailure)
    }

    private var playbackListener = listenerFactory?.invoke()
    private var reportedActive = false
    // A reused output must deliver beyond the previous source's accepted frames before it can
    // support the next source's declaration. Lost frames cannot count as delivery evidence.
    private var sourceStartFrames = 0L
    private var latticeBits = sourceBits
    private var converter = ExactPcmConverter(encoding, plan.channels, sourceBits, plan.bitResolution, plan.subslotBytes)
    private val clock = UsbMediaClock(plan.sampleRateHz)
    private val scratch = ByteBuffer.allocateDirect(SCRATCH_FRAMES * plan.frameBytes).also { it.limit(0) }
    private val prefillFrames = plan.sampleRateHz / PREFILL_DIVISOR
    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()

    private var released = false
    private var wantRunning = false
    private var running = false
    private var endRequested = false
    private var endMarked = false
    private var acceptedFrames = 0L
    private var failureReported = false

    override fun play() {
        wantRunning = true
        handle?.let(::settle)
    }

    override fun pause() {
        wantRunning = false
        reportActive(false)
        handle?.let(::settle)
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (released) throw AudioOutput.WriteException(ERROR_RELEASED, false)
        val target = handle ?: throw AudioOutput.WriteException(ERROR_NO_STREAM, false)
        checkFailure(target)
        settle(target)
        while (true) {
            if (scratch.hasRemaining()) {
                val accepted = target.write(scratch, scratch.position(), scratch.remaining())
                if (accepted < 0) {
                    checkFailure(target)
                    throw AudioOutput.WriteException(ERROR_STREAM_ENDED, false)
                }
                scratch.position(scratch.position() + accepted)
                acceptedFrames += accepted / plan.frameBytes
                settle(target)
                if (scratch.hasRemaining()) return false
            }
            if (!buffer.hasRemaining()) return true
            scratch.clear()
            val exact = converter.convert(buffer, scratch)
            scratch.flip()
            if (!exact) {
                report(DirectUsbFailure(DirectUsbFailure.Reason.PCM_NOT_EXACT, "a sample is not on the source's lattice"))
                throw AudioOutput.WriteException(ERROR_INEXACT_PCM, false)
            }
            // A leftover smaller than a frame could never leave the buffer.
            if (!scratch.hasRemaining()) throw AudioOutput.WriteException(ERROR_INEXACT_PCM, false)
        }
    }

    /** Media3 1.11 does not call this: a seek releases the output. */
    override fun flush() = Unit

    override fun stop() {
        endRequested = true
        handle?.let(::settle)
    }

    override fun release() {
        if (released) return
        released = true
        reportActive(false)
        handle?.release()
        listeners.forEach(AudioOutput.Listener::onReleased)
    }

    /** The volume stays at the DAC. Only the focus duck factor can arrive here, and it is ignored (D11). */
    override fun setVolume(volume: Float) {
        if (volume != 1f) onVolumeIgnored(volume)
    }

    override fun getPositionUs(): Long {
        val target = handle ?: return 0
        settle(target)
        target.failure?.let(::report)
        // Null until the stream reached this output's epoch: none of its audio has gone out yet.
        val stats = target.readStats() ?: return 0
        stats.asFailure()?.let(::report)
        reportActive(!released && running && !failureReported && stats.lastDeliveredMediaFrame > sourceStartFrames)
        val frames = clock.positionFrames(stats.mediaFramesProcessed, stats.mediaFramesSubmitted, running, nanoTime())
        return Util.sampleCountToDurationUs(frames, plan.sampleRateHz)
    }

    /**
     * A sink asks this after the last buffer of one format and before the first of the next, so the
     * next track's lattice applies from its first frame and the previous one's tail kept its own.
     * Two tracks that decode to the same PCM keep the output and its USB session; only the check
     * changes, never the wire format (#282).
     */
    override fun canReuseAudioOutput(
        currentConfig: AudioOutputProvider.OutputConfig,
        newFormat: AudioOutputProvider.FormatConfig,
        newConfig: AudioOutputProvider.OutputConfig,
    ): Boolean {
        if (newConfig != currentConfig) return false
        val bits = configuredSourceBits()
        if (bits <= 0) return false
        reportActive(false)
        sourceStartFrames = acceptedFrames
        playbackListener = listenerFactory?.invoke()
        if (bits != latticeBits) {
            converter = ExactPcmConverter(encoding, plan.channels, bits, plan.bitResolution, plan.subslotBytes)
            latticeBits = bits
        }
        return true
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

    private fun checkFailure(target: UsbOutputHandle) {
        val failure = target.failure ?: target.readStats()?.asFailure() ?: return
        report(failure)
        throw AudioOutput.WriteException(ERROR_STREAM_FAILED, false)
    }

    /** Running waits for a little audio in the ring, so the start is not counted as an underrun. */
    private fun settle(target: UsbOutputHandle) {
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

    private fun reportActive(active: Boolean) {
        if (reportedActive == active) return
        reportedActive = active
        playbackListener?.onActiveChanged(active)
    }

    private fun report(failure: DirectUsbFailure) {
        if (failureReported) return
        failureReported = true
        reportActive(false)
        val listener = playbackListener
        if (listener != null) listener.onFailed(failure) else onFailure(failure)
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

/**
 * Answers from the plan, because the system has no card to ask once the device is claimed, and only
 * for formats [DirectUsbFormatGate] accepts.
 */
@UnstableApi
internal class DirectUsbAudioOutputProvider(
    private val plan: StreamingPlan,
    /** The session's [io.github.sumirenokai.vesqen.usb.session.DirectUsbSession.openOutput]. */
    private val openOutput: () -> UsbOutputHandle?,
    /** The library's facts about the file being configured, from its MediaItem. */
    private val source: () -> TelemetrySourceFacts?,
    private val onFailure: (DirectUsbFailure) -> Unit = {},
    private val onVolumeIgnored: (Float) -> Unit = {},
    private val listenerFactory: (() -> DirectUsbAudioOutput.PlaybackListener)? = null,
) : AudioOutputProvider {
    /**
     * Set by [getOutputConfig] for the format being configured, and 0 while that format is refused.
     * A new output starts with it, and an output the sink keeps for that format switches to it.
     */
    private var acceptedSourceBits = 0

    private fun encodingOf(pcmEncoding: Int): UsbPcmEncoding? = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT -> UsbPcmEncoding.INT16
        C.ENCODING_PCM_24BIT -> UsbPcmEncoding.INT24
        C.ENCODING_PCM_32BIT -> UsbPcmEncoding.INT32
        C.ENCODING_PCM_FLOAT -> UsbPcmEncoding.FLOAT
        else -> null
    }

    private fun decide(formatConfig: AudioOutputProvider.FormatConfig): DirectUsbFormatGate.Decision {
        val format = formatConfig.format
        if (encodingOf(format.pcmEncoding) == null) {
            return DirectUsbFormatGate.Decision.Rejected(
                DirectUsbFailure(DirectUsbFailure.Reason.DECODER_PRECISION_LOST, "decoded to PCM encoding ${format.pcmEncoding}"),
            )
        }
        return DirectUsbFormatGate.check(source(), format, plan)
    }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(
                if (decide(formatConfig) is DirectUsbFormatGate.Decision.Accepted) {
                    AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
                } else {
                    AudioOutputProvider.FORMAT_UNSUPPORTED
                },
            )
            .build()

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        val format = formatConfig.format
        acceptedSourceBits = 0
        when (val decision = decide(formatConfig)) {
            is DirectUsbFormatGate.Decision.Rejected -> {
                onFailure(decision.failure)
                throw AudioOutputProvider.ConfigurationException("${decision.failure.reason}: ${decision.failure.detail}")
            }
            is DirectUsbFormatGate.Decision.Accepted -> acceptedSourceBits = decision.sourceBits
        }
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(format.pcmEncoding)
            .setSampleRate(format.sampleRate)
            .setChannelMask(Util.getAudioTrackChannelConfig(format.channelCount))
            .setBufferSize(plan.ringFrames * Util.getPcmFrameSize(format.pcmEncoding, format.channelCount))
            .setAudioAttributes(formatConfig.audioAttributes)
            .build()
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        check(acceptedSourceBits > 0) { "No format was accepted before the output" }
        return DirectUsbAudioOutput(
            plan,
            checkNotNull(encodingOf(config.encoding)),
            acceptedSourceBits,
            openOutput(),
            onFailure,
            onVolumeIgnored,
            configuredSourceBits = { acceptedSourceBits },
            listenerFactory = listenerFactory,
        )
    }

    override fun addListener(listener: AudioOutputProvider.Listener) = Unit

    override fun removeListener(listener: AudioOutputProvider.Listener) = Unit

    override fun release() = Unit
}
