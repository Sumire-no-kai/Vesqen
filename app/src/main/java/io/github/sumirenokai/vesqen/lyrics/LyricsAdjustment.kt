package io.github.sumirenokai.vesqen.lyrics

import java.security.MessageDigest

/** Positive advances display the lyrics earlier; none of these actions changes the audio. */
enum class LyricsAdjustmentAction { ADVANCE, DELAY, RESET }

enum class LyricsAdjustmentResult {
    APPLIED,
    UNAVAILABLE,
    LYRICS_CHANGED,
    TRACK_CHANGED,
    FAILED,
    LIMIT_REACHED,
}

/** The same corrected times serve highlighting, following, the seek marker and line seeking. */
internal fun Lyrics.Synced.withUserAdvance(advanceMs: Long): Lyrics.Synced {
    return copy(lines = lines.mapIndexed { index, line ->
        line.copy(timeMs = subtractSaturated(unadjustedTimesMs[index], advanceMs).coerceAtLeast(0L))
    })
}

/** Content and source, rather than a filename or import counter, identify the calibrated lyrics. */
internal fun Lyrics.Synced.contentRevision(source: LyricsSource): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val number = ByteArray(java.lang.Long.BYTES)
    fun writeLong(value: Long) {
        for (index in number.indices) number[index] = (value ushr (index * 8)).toByte()
        digest.update(number)
    }
    fun writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeLong(bytes.size.toLong())
        digest.update(bytes)
    }
    writeText("lyrics-adjustment-v1")
    writeText(source.name)
    writeLong(lines.size.toLong())
    lines.forEachIndexed { index, line ->
        writeLong(unadjustedTimesMs[index])
        writeText(line.text)
        writeLong(line.translations.size.toLong())
        line.translations.forEach(::writeText)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun subtractSaturated(timeMs: Long, advanceMs: Long): Long = when {
    advanceMs > 0 && timeMs < Long.MIN_VALUE + advanceMs -> Long.MIN_VALUE
    advanceMs < 0 && timeMs > Long.MAX_VALUE + advanceMs -> Long.MAX_VALUE
    else -> timeMs - advanceMs
}
