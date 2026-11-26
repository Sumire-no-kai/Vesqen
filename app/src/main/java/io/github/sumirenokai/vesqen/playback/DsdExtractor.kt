package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import io.github.sumirenokai.vesqen.library.DsdContainer
import io.github.sumirenokai.vesqen.library.DsdHeaderResult
import io.github.sumirenokai.vesqen.library.DsdHeaderSource
import io.github.sumirenokai.vesqen.library.DsdStreamInfo
import io.github.sumirenokai.vesqen.library.looksLikeDsd
import io.github.sumirenokai.vesqen.library.readDsdHeader
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

internal const val DSF_MIME_TYPE = "audio/x-dsf"
internal const val DFF_MIME_TYPE = "audio/x-dff"

/**
 * Plays DSF and uncompressed DFF by converting them to float PCM here (#249), so the rest of the
 * player handles them like any PCM. [conversion] is read once per stream.
 *
 * A seek starts a little before the requested time, so the filters have settled when Media3 starts
 * keeping samples; it drops every earlier buffer. A file this class reads but does not play (DST
 * compression, an unusual rate, channel count or speaker layout) ends in a [ParserException] for an
 * unsupported feature, which the player reports as an unsupported format.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class DsdExtractor(private val conversion: () -> DsdConversion = { DsdConversion() }) : Extractor {
    private lateinit var extractorOutput: ExtractorOutput
    private lateinit var trackOutput: TrackOutput

    private var stream: DsdStreamInfo? = null
    private var converter: DsdToPcmConverter? = null
    private var readBuffer = ByteArray(0)
    private var channelBytes = Array(0) { ByteArray(0) }
    private var pcm = FloatArray(0)
    private var sample = ParsableByteArray()

    /** Bytes per channel consumed from the start of the data, padding excluded. */
    private var consumedPerChannel = 0L

    /**
     * Where the next sample read starts. A load retried after an error resumes wherever the input
     * stopped, which can be inside a block, so a read elsewhere seeks back here first.
     */
    private var nextReadPosition = 0L
    private var startTimeUs = 0L

    /** Whether the frames still inside the filters at the end of the input have been written. */
    private var drained = false
    private var framesWritten = 0L

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(16)
        if (!input.peekFully(header, 0, header.size, true)) return false
        return looksLikeDsd(header)
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val info = stream ?: return readHeader(input, seekPosition)
        return readSamples(input, info, seekPosition)
    }

    /**
     * Media3 passes a seek point's [position] with the time that was asked for, which is later than
     * the point; the samples are timed from the position, so Media3 drops the ones before that time.
     */
    override fun seek(position: Long, timeUs: Long) {
        val info = stream
        if (position == 0L || info == null) {
            stream = null
            converter = null
            startTimeUs = 0
        } else {
            val offset = (position - info.dataStart).coerceAtLeast(0)
            consumedPerChannel = when (info.container) {
                DsdContainer.DSF -> offset / (info.blockBytesPerChannel.toLong() * info.channels) * info.blockBytesPerChannel
                DsdContainer.DFF -> offset / info.channels
            }
            nextReadPosition = info.dataStart + consumedPerChannel * info.channels
            startTimeUs = Util.scaleLargeTimestamp(consumedPerChannel * 8, C.MICROS_PER_SECOND, info.sampleRate.toLong())
            converter?.reset()
        }
        framesWritten = 0
        drained = false
    }

    override fun release() = Unit

    private fun readHeader(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // A load retried after an error resumes wherever the input stopped; the header is read
        // from the start again.
        if (input.position != 0L) {
            seekPosition.position = 0
            return Extractor.RESULT_SEEK
        }
        val length = input.length.takeIf { it != C.LENGTH_UNSET.toLong() }
        val result = try {
            readDsdHeader(ExtractorDsdHeaderSource(input), length)
        } catch (_: EOFException) {
            DsdHeaderResult.Unreadable
        }
        val info = when (result) {
            is DsdHeaderResult.Stream -> result.info
            is DsdHeaderResult.Unsupported -> throw ParserException.createForUnsupportedContainerFeature("DSD: ${result.reason}")
            DsdHeaderResult.Unreadable -> throw ParserException.createForMalformedContainer("DSD header is unreadable", null)
        }
        val settings = conversion()
        val outputRate = dsdOutputRate(info.sampleRate, settings.rateMode)
        val converter = DsdToPcmConverter(info.sampleRate, info.channels, outputRate, settings.gainDb)
        // One DSF block per read; DFF reads the same number of bytes per channel.
        val bytesPerChannel = if (info.container == DsdContainer.DSF) info.blockBytesPerChannel else DFF_BYTES_PER_READ
        readBuffer = ByteArray(bytesPerChannel * info.channels)
        channelBytes = if (info.container == DsdContainer.DFF) Array(info.channels) { ByteArray(bytesPerChannel) } else Array(0) { ByteArray(0) }
        // A read's frames, or the frames drained at the end, whichever is more.
        val maxFrames = maxOf(bytesPerChannel * 8 / converter.decimation, converter.latencyFrames) + 1
        pcm = FloatArray(maxFrames * info.channels)
        sample = ParsableByteArray(pcm.size * 4)
        val bitrate = outputRate * info.channels * 32
        trackOutput.format(
            Format.Builder()
                .setContainerMimeType(if (info.container == DsdContainer.DSF) DSF_MIME_TYPE else DFF_MIME_TYPE)
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setAverageBitrate(bitrate)
                .setPeakBitrate(bitrate)
                .setMaxInputSize(sample.data.size)
                .setChannelCount(info.channels)
                .setSampleRate(outputRate)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .build(),
        )
        extractorOutput.seekMap(DsdSeekMap(info, converter.decimation))
        this.converter = converter
        stream = info
        consumedPerChannel = 0
        nextReadPosition = info.dataStart
        drained = false
        // The header reader stops at the first sample byte.
        return Extractor.RESULT_CONTINUE
    }

    private fun readSamples(input: ExtractorInput, info: DsdStreamInfo, seekPosition: PositionHolder): Int {
        val converter = checkNotNull(converter)
        if (input.position != nextReadPosition) {
            seekPosition.position = nextReadPosition
            return Extractor.RESULT_SEEK
        }
        val totalPerChannel = (info.samplesPerChannel + 7) / 8
        val left = totalPerChannel - consumedPerChannel
        if (left <= 0) return finish(info, converter)
        val bytesPerChannel: Int
        val validPerChannel: Int
        when (info.container) {
            DsdContainer.DSF -> {
                // Whole blocks are stored; only the valid part of the last one is converted.
                bytesPerChannel = info.blockBytesPerChannel
                validPerChannel = min(left, bytesPerChannel.toLong()).toInt()
            }
            DsdContainer.DFF -> {
                bytesPerChannel = min(left, DFF_BYTES_PER_READ.toLong()).toInt()
                validPerChannel = bytesPerChannel
            }
        }
        val wanted = bytesPerChannel * info.channels
        var filled = 0
        while (filled < wanted) {
            val count = input.read(readBuffer, filled, wanted - filled)
            if (count == C.RESULT_END_OF_INPUT) break
            filled += count
        }
        // A file cut short converts what is there: whole bytes of every channel.
        val usable = when (info.container) {
            DsdContainer.DSF -> if (filled < wanted) 0 else validPerChannel
            DsdContainer.DFF -> filled / info.channels
        }
        var frames = 0
        if (usable > 0) {
            for (channel in 0 until info.channels) {
                frames = when (info.container) {
                    DsdContainer.DSF -> converter.process(
                        channel, readBuffer, channel * bytesPerChannel, usable, info.lsbFirst, pcm, 0,
                    )
                    DsdContainer.DFF -> {
                        val bytes = channelBytes[channel]
                        // Android's order may differ from the file's.
                        val source = info.channelOrder[channel]
                        for (index in 0 until usable) bytes[index] = readBuffer[index * info.channels + source]
                        converter.process(channel, bytes, 0, usable, info.lsbFirst, pcm, 0)
                    }
                }
            }
            consumedPerChannel += usable
        }
        nextReadPosition = input.position
        writeSample(frames, info.channels, converter)
        val ended = usable == 0 || filled < wanted || consumedPerChannel >= totalPerChannel
        return if (ended) finish(info, converter) else Extractor.RESULT_CONTINUE
    }

    /** Writes the frames still inside the filters once, so the stream ends on its last sample. */
    private fun finish(info: DsdStreamInfo, converter: DsdToPcmConverter): Int {
        if (!drained) {
            drained = true
            var frames = 0
            for (channel in 0 until info.channels) frames = converter.drain(channel, pcm, 0)
            writeSample(frames, info.channels, converter)
        }
        return Extractor.RESULT_END_OF_INPUT
    }

    private fun writeSample(frames: Int, channels: Int, converter: DsdToPcmConverter) {
        if (frames <= 0) return
        val size = frames * channels * 4
        ByteBuffer.wrap(sample.data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(pcm, 0, frames * channels)
        sample.setPosition(0)
        trackOutput.sampleData(sample, size)
        val timeUs = startTimeUs + Util.scaleLargeTimestamp(framesWritten, C.MICROS_PER_SECOND, converter.outputRate.toLong())
        trackOutput.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
        framesWritten += frames
    }

    private companion object {
        const val DFF_BYTES_PER_READ = 4096
    }
}

/** Reads the header through the extractor's input, which it leaves at the first sample byte. */
@androidx.annotation.OptIn(UnstableApi::class)
private class ExtractorDsdHeaderSource(private val input: ExtractorInput) : DsdHeaderSource {
    override fun read(count: Int): ByteArray? {
        val bytes = ByteArray(count)
        return if (input.readFully(bytes, 0, count, true)) bytes else null
    }

    override fun skip(count: Long): Boolean {
        if (count < 0) return false
        var remaining = count
        while (remaining > 0) {
            val step = min(remaining, Int.MAX_VALUE.toLong()).toInt()
            if (!input.skipFully(step, true)) return false
            remaining -= step
        }
        return true
    }
}

/**
 * Seek points start [WARM_UP_US] before the requested time, on a DSF block or a whole output frame
 * of DFF bytes, so the filters have settled by the time Media3 keeps samples.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class DsdSeekMap(private val info: DsdStreamInfo, decimation: Int) : SeekMap {
    /** Bytes per channel from one seek point to the next. */
    private val step: Long = when (info.container) {
        DsdContainer.DSF -> info.blockBytesPerChannel.toLong()
        DsdContainer.DFF -> max(1, decimation / 8).toLong()
    }
    private val lastStep = max(0, ((info.samplesPerChannel + 7) / 8 - 1) / step)

    override fun isSeekable() = true

    override fun getDurationUs(): Long = info.durationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val sample = Util.scaleLargeTimestamp(max(0, timeUs - WARM_UP_US), info.sampleRate.toLong(), C.MICROS_PER_SECOND)
        val index = (sample / 8 / step).coerceIn(0, lastStep)
        val bytesPerChannel = index * step
        val time = Util.scaleLargeTimestamp(bytesPerChannel * 8, C.MICROS_PER_SECOND, info.sampleRate.toLong())
        return SeekMap.SeekPoints(SeekPoint(time, info.dataStart + bytesPerChannel * info.channels))
    }

    private companion object {
        /** Far longer than the filters' delay, which stays under half a millisecond. */
        const val WARM_UP_US = 2_000L
    }
}
