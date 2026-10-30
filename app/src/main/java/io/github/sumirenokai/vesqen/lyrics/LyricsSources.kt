package io.github.sumirenokai.vesqen.lyrics

/** Where a track's lyrics come from, in the order the sources are tried (#169). */
enum class LyricsSource {
    Imported,
    SameNameFile,
    Embedded,
}

/** What one source holds for a track. */
internal sealed interface SourceLyrics {
    data object Absent : SourceLyrics

    /** The source exists, but its text cannot be decoded without guessing. */
    data object Unreadable : SourceLyrics

    data class Readable(val lyrics: Lyrics) : SourceLyrics
}

/** The lyrics shown for a track. */
internal sealed interface PickedLyrics {
    data class Found(val source: LyricsSource, val lyrics: Lyrics) : PickedLyrics

    data object Absent : PickedLyrics

    /** At least one source exists and none of them can be read. */
    data object Unreadable : PickedLyrics
}

/**
 * Reads the sources in [LyricsSource] order and stops at the first readable one. An unreadable
 * source falls through to the next (owner decision 8), so "unreadable" is shown only when nothing
 * can be read. Inline so a caller can read sources with suspending calls.
 */
internal inline fun pickLyrics(read: (LyricsSource) -> SourceLyrics): PickedLyrics {
    var anyUnreadable = false
    for (source in LyricsSource.entries) {
        when (val result = read(source)) {
            is SourceLyrics.Readable -> return PickedLyrics.Found(source, result.lyrics)
            SourceLyrics.Unreadable -> anyUnreadable = true
            SourceLyrics.Absent -> Unit
        }
    }
    return if (anyUnreadable) PickedLyrics.Unreadable else PickedLyrics.Absent
}

/** Decodes and parses the bytes of a lyrics file; a file with nothing to show counts as absent. */
internal fun readLyricsFile(bytes: ByteArray): SourceLyrics {
    val text = decodeLyricsText(bytes) ?: return SourceLyrics.Unreadable
    return parseLyrics(text)?.let(SourceLyrics::Readable) ?: SourceLyrics.Absent
}
