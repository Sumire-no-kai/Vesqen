package io.github.sumirenokai.vesqen.playback

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.min

/** WAV and FLAC files written from known samples, for tests that compare or play them. */
internal object PcmFiles {
    private const val FLAC_BLOCK_FRAMES = 4096
    private const val FLAC_VERBATIM_SUBFRAME = 0x02

    private fun littleEndianPcm(samples: IntArray, bitDepth: Int): ByteArray {
        val bytesPerSample = bitDepth / 8
        val pcm = ByteArray(samples.size * bytesPerSample)
        samples.forEachIndexed { index, sample ->
            for (byte in 0 until bytesPerSample) {
                pcm[index * bytesPerSample + byte] = (sample shr (8 * byte)).toByte()
            }
        }
        return pcm
    }

    fun wavBytes(samples: IntArray, sampleRateHz: Int, bitDepth: Int, channels: Int): ByteArray {
        val pcm = littleEndianPcm(samples, bitDepth)
        val blockAlign = channels * bitDepth / 8
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(channels.toShort()); putInt(sampleRateHz)
            putInt(sampleRateHz * blockAlign); putShort(blockAlign.toShort()); putShort(bitDepth.toShort())
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }

    /** Verbatim subframes only: a valid FLAC file with no encoder dependency. */
    fun flacBytes(samples: IntArray, sampleRateHz: Int, bitDepth: Int, channels: Int): ByteArray {
        val frameCount = samples.size / channels
        val output = ByteArrayOutputStream()
        output.write("fLaC".toByteArray())
        output.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
        val info = ByteBuffer.allocate(34)
        info.putShort(FLAC_BLOCK_FRAMES.toShort()).putShort(FLAC_BLOCK_FRAMES.toShort())
        info.put(ByteArray(6))
        info.putLong(
            (sampleRateHz.toLong() shl 44) or ((channels - 1L) shl 41) or
                ((bitDepth - 1L) shl 36) or frameCount.toLong(),
        )
        info.put(MessageDigest.getInstance("MD5").digest(littleEndianPcm(samples, bitDepth)))
        output.write(info.array())
        var frameNumber = 0
        var frame = 0
        while (frame < frameCount) {
            val blockFrames = min(FLAC_BLOCK_FRAMES, frameCount - frame)
            output.write(flacFrame(samples, sampleRateHz, bitDepth, channels, frameNumber++, frame, blockFrames))
            frame += blockFrames
        }
        return output.toByteArray()
    }

    private fun flacFrame(
        samples: IntArray,
        sampleRateHz: Int,
        bitDepth: Int,
        channels: Int,
        frameNumber: Int,
        firstFrame: Int,
        blockFrames: Int,
    ): ByteArray {
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
        require(frameNumber < 0x800) { "Too long for this frame number coding" }
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
        for (channel in 0 until channels) {
            frame.write(FLAC_VERBATIM_SUBFRAME)
            for (index in firstFrame until firstFrame + blockFrames) {
                val sample = samples[index * channels + channel]
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
}
