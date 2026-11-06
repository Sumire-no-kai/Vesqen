package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor
import kotlin.math.min

internal enum class PcmContainer { WAV, FLAC, AIFF }

/** Deterministic full-range stereo noise: every sample differs, so alignment is unambiguous. */
internal class PcmFixture(
    val container: PcmContainer,
    val sampleRateHz: Int,
    val bitDepth: Int,
    durationMs: Int,
    seed: Long,
) {
    val frameCount = sampleRateHz / 100 * durationMs / 10
    val samples = IntArray(frameCount * CHANNELS).also { samples ->
        val random = Random(seed)
        val range = 1 shl bitDepth
        for (index in samples.indices) samples[index] = random.nextInt(range) - range / 2
    }
    val name = "$container-$sampleRateHz-$bitDepth-$durationMs-$seed"

    fun write(directory: File): File {
        directory.mkdirs()
        return when (container) {
            PcmContainer.WAV -> File(directory, "$name.wav").also { it.writeBytes(wavBytes()) }
            PcmContainer.FLAC -> File(directory, "$name.flac").also { it.writeBytes(flacBytes()) }
            PcmContainer.AIFF -> File(directory, "$name.aiff").also { it.writeBytes(aiffBytes()) }
        }
    }

    private fun wavBytes() = PcmFiles.wavBytes(samples, sampleRateHz, bitDepth, CHANNELS)

    private fun flacBytes() = PcmFiles.flacBytes(samples, sampleRateHz, bitDepth, CHANNELS)

    /** The same samples as [wavBytes], big-endian as AIFF stores them. */
    private fun aiffBytes(): ByteArray {
        val bytesPerSample = bitDepth / 8
        val pcm = ByteArray(samples.size * bytesPerSample)
        samples.forEachIndexed { index, sample ->
            for (byte in 0 until bytesPerSample) {
                pcm[index * bytesPerSample + byte] = (sample shr (8 * (bytesPerSample - 1 - byte))).toByte()
            }
        }
        // An 80-bit extended float, whose mantissa's top bit stands for 2^exponent.
        val exponent = 31 - Integer.numberOfLeadingZeros(sampleRateHz)
        val mantissa = sampleRateHz.toLong() shl (63 - exponent)
        val body = 4 + 8 + 18 + 8 + 8 + pcm.size
        return ByteBuffer.allocate(8 + body).order(ByteOrder.BIG_ENDIAN).apply {
            put("FORM".toByteArray()); putInt(body); put("AIFF".toByteArray())
            put("COMM".toByteArray()); putInt(18); putShort(CHANNELS.toShort()); putInt(frameCount)
            putShort(bitDepth.toShort()); putShort((16383 + exponent).toShort()); putLong(mantissa)
            put("SSND".toByteArray()); putInt(8 + pcm.size); putInt(0); putInt(0); put(pcm)
        }.array()
    }

    companion object {
        const val CHANNELS = 2
    }
}

internal data class CaptureOptions(
    /** Answer format questions directly, as the strict path does once it owns the DAC. */
    val answersFormats: Boolean = false,
    /** Forces the partial writes a full output buffer would cause. */
    val maxFramesPerWrite: Int = Int.MAX_VALUE,
    /** The first output reports one stall after this many frames. */
    val stallAfterFrames: Long? = null,
    /** The clock stops here until [CapturingAudioOutput.releaseClock], so data stays unplayed. */
    val holdClockAtFrames: Long? = null,
)

/** Answers format questions like the real provider, but keeps what Media3 writes. */
@UnstableApi
internal class CapturingAudioOutputProvider(
    context: Context,
    private val options: CaptureOptions = CaptureOptions(),
) : ForwardingAudioOutputProvider(
    AudioTrackAudioOutputProvider.Builder(context.applicationContext).build(),
) {
    val outputs = CopyOnWriteArrayList<CapturingAudioOutput>()

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        if (!options.answersFormats) return super.getOutputConfig(formatConfig)
        val format = formatConfig.format
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(format.pcmEncoding)
            .setSampleRate(format.sampleRate)
            .setChannelMask(Util.getAudioTrackChannelConfig(format.channelCount))
            .setBufferSize(format.sampleRate / 2 * Util.getPcmFrameSize(format.pcmEncoding, format.channelCount))
            .setAudioAttributes(formatConfig.audioAttributes)
            .build()
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = CapturingAudioOutput(
        config,
        options.maxFramesPerWrite,
        options.stallAfterFrames.takeIf { outputs.isEmpty() },
        options.holdClockAtFrames,
    ).also(outputs::add)
}

/** Consumes audio on a simulated clock, faster than real time, so playback ends on its own. */
@UnstableApi
internal class CapturingAudioOutput(
    private val config: AudioOutputProvider.OutputConfig,
    private val maxFramesPerWrite: Int = Int.MAX_VALUE,
    private var stallAfterFrames: Long? = null,
    private var holdClockAtFrames: Long? = null,
) : AudioOutput {
    private val channelCount = Integer.bitCount(config.channelMask)
    private val frameSize = Util.getPcmFrameSize(config.encoding, channelCount)
    private val bufferFrames = (config.bufferSize / frameSize).toLong()
    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()
    private val lock = Any()
    private val captured = ByteArrayOutputStream()
    private var writtenFrames = 0L
    private var consumedAtPlay = 0L
    private var playStartedNanos = NOT_PLAYING
    private var parameters = PlaybackParameters.DEFAULT

    val encoding: Int get() = config.encoding
    val capturedFrames: Long get() = synchronized(lock) { writtenFrames }
    val isPlaying: Boolean get() = synchronized(lock) { playStartedNanos != NOT_PLAYING }

    @Volatile
    var lastVolume = 1f
        private set

    fun releaseClock() = synchronized(lock) { holdClockAtFrames = null }

    fun capturedPcm() = CapturedPcm(config.encoding, channelCount, synchronized(lock) { captured.toByteArray() })

    private fun consumedFrames(): Long {
        val running = if (playStartedNanos == NOT_PLAYING) {
            0L
        } else {
            (SystemClock.elapsedRealtimeNanos() - playStartedNanos) * config.sampleRate * CLOCK_SPEED / 1_000_000_000L
        }
        return min(min(consumedAtPlay + running, writtenFrames), holdClockAtFrames ?: Long.MAX_VALUE)
    }

    override fun play() = synchronized(lock) {
        if (playStartedNanos == NOT_PLAYING) playStartedNanos = SystemClock.elapsedRealtimeNanos()
    }

    override fun pause() = synchronized(lock) {
        consumedAtPlay = consumedFrames()
        playStartedNanos = NOT_PLAYING
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean =
        synchronized(lock) {
            val freeFrames = (bufferFrames - (writtenFrames - consumedFrames())).coerceAtLeast(0)
            val writableFrames = min(freeFrames, maxFramesPerWrite.toLong())
            val byteCount = min(buffer.remaining().toLong(), writableFrames * frameSize).toInt() / frameSize * frameSize
            val bytes = ByteArray(byteCount)
            buffer.get(bytes)
            captured.write(bytes)
            writtenFrames += byteCount / frameSize
            !buffer.hasRemaining()
        }

    override fun flush() = Unit

    override fun stop() = Unit

    override fun release() {
        listeners.forEach(AudioOutput.Listener::onReleased)
    }

    override fun setVolume(volume: Float) {
        lastVolume = volume
    }

    override fun isOffloadedPlayback() = false

    override fun getAudioSessionId() = C.AUDIO_SESSION_ID_UNSET

    override fun getSampleRate() = config.sampleRate

    override fun getBufferSizeInFrames() = bufferFrames

    override fun getPositionUs(): Long = synchronized(lock) {
        Util.sampleCountToDurationUs(consumedFrames(), config.sampleRate)
    }

    override fun getPlaybackParameters(): PlaybackParameters = parameters

    override fun isStalled(): Boolean = synchronized(lock) {
        val threshold = stallAfterFrames ?: return false
        if (writtenFrames < threshold) return false
        stallAfterFrames = null
        true
    }

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {
        parameters = playbackParams
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit

    override fun setOffloadEndOfStream() = Unit

    override fun attachAuxEffect(effectId: Int) = Unit

    override fun setAuxEffectSendLevel(level: Float) = Unit

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit

    private companion object {
        const val NOT_PLAYING = -1L
        const val CLOCK_SPEED = 4L
    }
}

internal class CapturedPcm(val encoding: Int, val channelCount: Int, private val bytes: ByteArray) {
    val frameCount = bytes.size / Util.getPcmFrameSize(encoding, channelCount)

    /** The decoder delivered fewer bits than the file holds, so no comparison can succeed. */
    fun lostPrecision(bitDepth: Int) = encoding == C.ENCODING_PCM_16BIT && bitDepth > 16

    /** Samples as integers at [bitDepth]; a value that is not on that lattice becomes [NOT_EXACT]. */
    fun samplesAt(bitDepth: Int): IntArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val scale = (1L shl (bitDepth - 1)).toDouble()
        return IntArray(frameCount * channelCount) {
            when (encoding) {
                C.ENCODING_PCM_16BIT -> input.short.toInt() shl (bitDepth - 16)
                C.ENCODING_PCM_24BIT -> {
                    val low = input.short.toInt() and 0xFFFF
                    ((input.get().toInt() shl 16) or low) shr (24 - bitDepth)
                }
                C.ENCODING_PCM_32BIT -> input.int shr (32 - bitDepth)
                C.ENCODING_PCM_FLOAT -> {
                    val value = input.float.toDouble() * scale
                    if (value == floor(value) && value >= -scale && value < scale) value.toInt() else NOT_EXACT
                }
                else -> error("Unexpected encoding $encoding")
            }
        }
    }

    companion object {
        const val NOT_EXACT = Int.MIN_VALUE
    }
}

internal data class PcmComparison(
    val capturedFrames: Int,
    val notExactSamples: Int,
    /** Captured frames before the source starts; nothing may precede it. */
    val leadingFrames: Int,
    val referenceStartFrame: Int,
    val mismatchedFrames: Int,
    /** Relative to the first captured frame that maps onto the reference; -1 when none. */
    val firstMismatchFrame: Int,
    val lastMismatchFrame: Int,
    val missingTailFrames: Int,
    val trailingFrames: Int,
)

/**
 * Finds where the capture sits in [reference] from a window past any start-up processing, then
 * compares every captured frame, including the frames before that window.
 */
internal fun compareCapturedPcm(
    captured: CapturedPcm,
    reference: IntArray,
    bitDepth: Int,
    probeFrame: Int,
    /** Skips the search for captures too short to align by content. */
    knownReferenceStart: Int? = null,
): PcmComparison {
    val channels = captured.channelCount
    val samples = captured.samplesAt(bitDepth)
    val referenceFrames = reference.size / channels
    val referenceStart = knownReferenceStart ?: run {
        check(captured.frameCount >= probeFrame + PROBE_FRAMES) {
            "Captured only ${captured.frameCount} frames, need ${probeFrame + PROBE_FRAMES} to align"
        }
        val alignedAt = (0..referenceFrames - PROBE_FRAMES).firstOrNull { candidate ->
            (0 until PROBE_FRAMES * channels).all { offset ->
                samples[probeFrame * channels + offset] == reference[candidate * channels + offset]
            }
        } ?: error("Capture does not align with the reference at frame $probeFrame")
        alignedAt - probeFrame
    }
    var mismatched = 0
    var first = -1
    var last = -1
    var trailing = 0
    val leading = (-referenceStart).coerceAtLeast(0)
    for (frame in 0 until captured.frameCount) {
        val referenceFrame = referenceStart + frame
        val frameSamples = (0 until channels).map { samples[frame * channels + it] }
        when {
            referenceFrame < 0 -> Unit
            referenceFrame >= referenceFrames -> trailing++
            frameSamples.indices.any { frameSamples[it] != reference[referenceFrame * channels + it] } -> {
                mismatched++
                val relative = frame - leading
                if (first < 0) first = relative
                last = relative
            }
        }
    }
    return PcmComparison(
        capturedFrames = captured.frameCount,
        notExactSamples = samples.count { it == CapturedPcm.NOT_EXACT },
        leadingFrames = leading,
        referenceStartFrame = referenceStart.coerceAtLeast(0),
        mismatchedFrames = mismatched,
        firstMismatchFrame = first,
        lastMismatchFrame = last,
        missingTailFrames = (referenceFrames - (referenceStart + captured.frameCount)).coerceAtLeast(0),
        trailingFrames = trailing,
    )
}

private const val PROBE_FRAMES = 64
