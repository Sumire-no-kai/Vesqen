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
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor
import kotlin.math.min

internal enum class PcmContainer { WAV, FLAC }

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
        val extension = if (container == PcmContainer.WAV) "wav" else "flac"
        return File(directory, "$name.$extension").also { file ->
            file.writeBytes(if (container == PcmContainer.WAV) wavBytes() else flacBytes())
        }
    }

    private fun littleEndianPcm(): ByteArray {
        val bytesPerSample = bitDepth / 8
        val pcm = ByteArray(samples.size * bytesPerSample)
        samples.forEachIndexed { index, sample ->
            for (byte in 0 until bytesPerSample) {
                pcm[index * bytesPerSample + byte] = (sample shr (8 * byte)).toByte()
            }
        }
        return pcm
    }

    private fun wavBytes(): ByteArray {
        val pcm = littleEndianPcm()
        val blockAlign = CHANNELS * bitDepth / 8
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(CHANNELS.toShort()); putInt(sampleRateHz)
            putInt(sampleRateHz * blockAlign); putShort(blockAlign.toShort()); putShort(bitDepth.toShort())
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }

    /** Verbatim subframes only: a valid FLAC file with no encoder dependency. */
    private fun flacBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("fLaC".toByteArray())
        output.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
        val info = ByteBuffer.allocate(34)
        info.putShort(FLAC_BLOCK_FRAMES.toShort()).putShort(FLAC_BLOCK_FRAMES.toShort())
        info.put(ByteArray(6))
        info.putLong(
            (sampleRateHz.toLong() shl 44) or ((CHANNELS - 1L) shl 41) or
                ((bitDepth - 1L) shl 36) or frameCount.toLong(),
        )
        info.put(MessageDigest.getInstance("MD5").digest(littleEndianPcm()))
        output.write(info.array())
        var frameNumber = 0
        var frame = 0
        while (frame < frameCount) {
            val blockFrames = min(FLAC_BLOCK_FRAMES, frameCount - frame)
            output.write(flacFrame(frameNumber++, frame, blockFrames))
            frame += blockFrames
        }
        return output.toByteArray()
    }

    private fun flacFrame(frameNumber: Int, firstFrame: Int, blockFrames: Int): ByteArray {
        val rateCode = when (sampleRateHz) {
            44_100 -> 0b1001
            48_000 -> 0b1010
            96_000 -> 0b1011
            else -> error("No FLAC rate code for $sampleRateHz")
        }
        val sizeCode = when (bitDepth) {
            16 -> 0b100
            24 -> 0b110
            else -> error("No FLAC size code for $bitDepth")
        }
        val frame = ByteArrayOutputStream()
        frame.write(0xFF)
        frame.write(0xF8)
        frame.write((0b0111 shl 4) or rateCode)
        frame.write((0b0001 shl 4) or (sizeCode shl 1))
        require(frameNumber < 0x800) { "Fixture too long for this frame number coding" }
        if (frameNumber < 0x80) {
            frame.write(frameNumber)
        } else {
            frame.write(0xC0 or (frameNumber shr 6))
            frame.write(0x80 or (frameNumber and 0x3F))
        }
        frame.write((blockFrames - 1) shr 8)
        frame.write((blockFrames - 1) and 0xFF)
        frame.write(crc8(frame.toByteArray()))
        val bytesPerSample = bitDepth / 8
        for (channel in 0 until CHANNELS) {
            frame.write(FLAC_VERBATIM_SUBFRAME)
            for (index in firstFrame until firstFrame + blockFrames) {
                val sample = samples[index * CHANNELS + channel]
                for (byte in bytesPerSample - 1 downTo 0) frame.write((sample shr (8 * byte)) and 0xFF)
            }
        }
        val crc = crc16(frame.toByteArray())
        frame.write(crc shr 8)
        frame.write(crc and 0xFF)
        return frame.toByteArray()
    }

    private fun crc8(bytes: ByteArray): Int {
        var crc = 0
        for (value in bytes) {
            crc = crc xor (value.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else crc shl 1 }
            crc = crc and 0xFF
        }
        return crc
    }

    private fun crc16(bytes: ByteArray): Int {
        var crc = 0
        for (value in bytes) {
            crc = crc xor ((value.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x8005 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    companion object {
        const val CHANNELS = 2
        private const val FLAC_BLOCK_FRAMES = 4096
        private const val FLAC_VERBATIM_SUBFRAME = 0x02
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
