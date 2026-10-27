package io.github.sumirenokai.vesqen.playback

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes [durationMs] of 8 kHz mono 16-bit silence; device-test fixtures, never in MediaStore. */
internal fun writeSilentWav(file: File, durationMs: Int) {
    val rate = 8_000
    val dataSize = rate * 2 * durationMs / 1_000
    file.parentFile?.mkdirs()
    file.outputStream().buffered().use { output ->
        output.write(ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(rate)
            putInt(rate * 2); putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
        }.array())
        output.write(ByteArray(dataSize))
    }
}
