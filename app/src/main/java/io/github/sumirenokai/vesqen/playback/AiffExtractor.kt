package io.github.sumirenokai.vesqen.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import io.github.sumirenokai.vesqen.library.AIFF_COMM_MIN_SIZE
import io.github.sumirenokai.vesqen.library.AIFF_COMM_READ_SIZE
import io.github.sumirenokai.vesqen.library.AiffPcmFormat
import io.github.sumirenokai.vesqen.library.parseAiffComm
import java.io.EOFException
import kotlin.math.max
import kotlin.math.min

/**
 * Media3's extractors with AIFF added (#268). AIFF goes first: its check is exact, while sniffing
 * the others over PCM data could mistake audio for a compressed stream.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VesqenExtractorsFactory : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> =
        arrayOf<Extractor>(AiffExtractor()) + defaults.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        arrayOf<Extractor>(AiffExtractor()) + defaults.createExtractors(uri, responseHeaders)
}

/**
 * Reads AIFF and uncompressed AIFF-C, which Media3 has no extractor for. Samples leave here
 * little-endian with their values unchanged, so everything after this class handles AIFF as WAV.
 *
 * Chunk sizes and offsets come from the file and are not trusted: a bad file ends in a
 * [ParserException], which the player reports as an unsupported format.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AiffExtractor : Extractor {
    private lateinit var trackOutput: TrackOutput
    private lateinit var extractorOutput: ExtractorOutput
    private val scratch = ByteArray(max(CHUNK_HEADER_SIZE, AIFF_COMM_READ_SIZE))

    private var fileHeaderRead = false
    private var aifc = false
    private var chunksLeft = MAX_CHUNKS
    private var format: AiffPcmFormat? = null
    private var dataStart = C.INDEX_UNSET.toLong()
    private var dataEnd = C.INDEX_UNSET.toLong()
    private var ready = false

    private var buffer = ParsableByteArray()
    private var bufferedBytes = 0
    private var startTimeUs = 0L
    private var framesWritten = 0L

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(FILE_HEADER_SIZE)
        if (!input.peekFully(header, 0, FILE_HEADER_SIZE, true)) return false
        return header.hasId("FORM", 0) && (header.hasId("AIFF", 8) || header.hasId("AIFC", 8))
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        if (ready) readSamples(input) else readHeader(input, seekPosition)
    } catch (end: EOFException) {
        // Only the header reads with readFully; samples treat the end of the file as the end.
        throw ParserException.createForMalformedContainer("AIFF header is cut off", end)
    }

    override fun seek(position: Long, timeUs: Long) {
        if (position == 0L) {
            fileHeaderRead = false
            chunksLeft = MAX_CHUNKS
            format = null
            dataStart = C.INDEX_UNSET.toLong()
            ready = false
        }
        bufferedBytes = 0
        startTimeUs = timeUs
        framesWritten = 0
    }

    override fun release() = Unit

    private fun readHeader(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!fileHeaderRead) {
            input.readFully(scratch, 0, CHUNK_HEADER_SIZE)
            input.readFully(scratch, 0, 4)
            aifc = scratch.hasId("AIFC", 0)
            fileHeaderRead = true
        }
        if (--chunksLeft < 0) throw malformed("too many chunks")
        if (!input.readFully(scratch, 0, CHUNK_HEADER_SIZE, true)) throw malformed("COMM or SSND is missing")
        val size = scratch.u32(4)
        val bodyStart = input.position
        when {
            scratch.hasId("COMM", 0) -> {
                if (size < AIFF_COMM_MIN_SIZE) throw malformed("COMM is too short")
                val read = min(size, AIFF_COMM_READ_SIZE.toLong()).toInt()
                input.readFully(scratch, 0, read)
                format = parseAiffComm(scratch.copyOf(read), aifc)
                    ?: throw ParserException.createForUnsupportedContainerFeature("AIFF sample format")
            }
            scratch.hasId("SSND", 0) -> {
                if (size < SSND_HEADER_SIZE) throw malformed("SSND is too short")
                input.readFully(scratch, 0, 4)
                val offset = scratch.u32(0)
                if (offset > size - SSND_HEADER_SIZE) throw malformed("SSND offset is past its end")
                dataStart = bodyStart + SSND_HEADER_SIZE + offset
                dataEnd = bodyStart + size
            }
        }
        val pcm = format
        if (pcm != null && dataStart != C.INDEX_UNSET.toLong()) {
            startSamples(pcm, input)
            return moveTo(dataStart, input, seekPosition)
        }
        // Chunks are padded to an even length, and the padding is not part of the size.
        return moveTo(bodyStart + size + (size and 1), input, seekPosition)
    }

    /** A long jump seeks rather than reading through, since SSND can come before COMM. */
    private fun moveTo(position: Long, input: ExtractorInput, seekPosition: PositionHolder): Int {
        val distance = position - input.position
        if (distance in 0 until SKIP_LIMIT) {
            input.skipFully(distance.toInt(), true)
            return Extractor.RESULT_CONTINUE
        }
        if (input.length != C.LENGTH_UNSET.toLong() && position > input.length) throw malformed("chunk is past the end")
        seekPosition.position = position
        return Extractor.RESULT_SEEK
    }

    private fun startSamples(pcm: AiffPcmFormat, input: ExtractorInput) {
        val frameSize = pcm.bytesPerFrame.toLong()
        val available = if (input.length == C.LENGTH_UNSET.toLong()) dataEnd else min(dataEnd, input.length)
        var frames = max(available - dataStart, 0) / frameSize
        // Some writers leave the count at 0 until the end, which they may never reach.
        if (pcm.frames > 0) frames = min(frames, pcm.frames)
        if (frames == 0L) throw malformed("no audio")
        dataEnd = dataStart + frames * frameSize

        val encoding = when (pcm.bytesPerSample) {
            2 -> C.ENCODING_PCM_16BIT
            3 -> C.ENCODING_PCM_24BIT
            else -> C.ENCODING_PCM_32BIT
        }
        // A tenth of a second, as Media3's WAV extractor writes.
        val sampleSize = max(1, min(MAX_SAMPLE_BYTES, pcm.sampleRate * pcm.bytesPerFrame / 10) / pcm.bytesPerFrame) *
            pcm.bytesPerFrame
        if (buffer.data.size != sampleSize) buffer = ParsableByteArray(sampleSize)
        val bitrate = pcm.sampleRate * pcm.bytesPerFrame * 8
        trackOutput.format(
            Format.Builder()
                .setContainerMimeType(AIFF_MIME_TYPE)
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setAverageBitrate(bitrate)
                .setPeakBitrate(bitrate)
                .setMaxInputSize(sampleSize)
                .setChannelCount(pcm.channels)
                .setSampleRate(pcm.sampleRate)
                .setPcmEncoding(encoding)
                .build(),
        )
        extractorOutput.seekMap(AiffSeekMap(dataStart, frames, pcm.bytesPerFrame, pcm.sampleRate))
        ready = true
    }

    private fun readSamples(input: ExtractorInput): Int {
        val pcm = checkNotNull(format)
        val data = buffer.data
        var ended = false
        while (bufferedBytes < data.size) {
            val left = dataEnd - input.position
            if (left <= 0) {
                ended = true
                break
            }
            val count = input.read(data, bufferedBytes, min(left, (data.size - bufferedBytes).toLong()).toInt())
            if (count == C.RESULT_END_OF_INPUT) {
                ended = true
                break
            }
            bufferedBytes += count
        }
        val frames = bufferedBytes / pcm.bytesPerFrame
        if (frames > 0) {
            val size = frames * pcm.bytesPerFrame
            if (!pcm.littleEndian) reverseSamples(data, size, pcm.bytesPerSample)
            buffer.setPosition(0)
            trackOutput.sampleData(buffer, size)
            val timeUs = startTimeUs + Util.scaleLargeTimestamp(framesWritten, C.MICROS_PER_SECOND, pcm.sampleRate.toLong())
            trackOutput.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
            framesWritten += frames
            // A full buffer always holds whole frames; a partial frame is left only at the end.
            bufferedBytes -= size
        }
        return if (ended) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
    }

    private fun malformed(detail: String) = ParserException.createForMalformedContainer("AIFF: $detail", null)
}

@androidx.annotation.OptIn(UnstableApi::class)
private class AiffSeekMap(
    private val dataStart: Long,
    private val frames: Long,
    private val bytesPerFrame: Int,
    private val sampleRate: Int,
) : SeekMap {
    override fun isSeekable() = true

    override fun getDurationUs(): Long = timeUs(frames)

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val frame = Util.scaleLargeTimestamp(timeUs, sampleRate.toLong(), C.MICROS_PER_SECOND).coerceIn(0, frames - 1)
        val point = point(frame)
        return if (point.timeUs >= timeUs || frame == frames - 1) {
            SeekMap.SeekPoints(point)
        } else {
            SeekMap.SeekPoints(point, point(frame + 1))
        }
    }

    private fun point(frame: Long) = SeekPoint(timeUs(frame), dataStart + frame * bytesPerFrame)

    private fun timeUs(frame: Long) = Util.scaleLargeTimestamp(frame, C.MICROS_PER_SECOND, sampleRate.toLong())
}

private fun reverseSamples(data: ByteArray, size: Int, bytesPerSample: Int) {
    var start = 0
    while (start < size) {
        var low = start
        var high = start + bytesPerSample - 1
        while (low < high) {
            val byte = data[low]
            data[low++] = data[high]
            data[high--] = byte
        }
        start += bytesPerSample
    }
}

private fun ByteArray.hasId(id: String, at: Int) = id.indices.all { this[at + it] == id[it].code.toByte() }

private fun ByteArray.u32(at: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (this[at + i].toLong() and 255) }

private const val AIFF_MIME_TYPE = "audio/aiff"
private const val FILE_HEADER_SIZE = 12
private const val CHUNK_HEADER_SIZE = 8
private const val SSND_HEADER_SIZE = 8L
private const val MAX_CHUNKS = 1024
private const val MAX_SAMPLE_BYTES = 1 shl 20
private const val SKIP_LIMIT = 256 * 1024L
