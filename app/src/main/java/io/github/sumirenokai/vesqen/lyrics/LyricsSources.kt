package io.github.sumirenokai.vesqen.lyrics

/** Where a track's lyrics come from, in the order the sources are tried (#169). */
enum class LyricsSource {
    Imported,
    SameNameFile,
    Embedded,
}

/** Why lyrics that exist cannot be shown, so Now can say why (#191 C6). */
enum class UnreadableReason {
    /** No encoding fits the text without guessing. */
    Encoding,

    /** The file is larger than [MAX_LYRICS_TEXT_BYTES]. */
    TooLarge,

    /** The file could not be read just now; it is read again next time. */
    ReadFailed,
}

/** What one source holds for a track. */
internal sealed interface SourceLyrics {
    data object Absent : SourceLyrics

    /** The source exists, but its lyrics cannot be shown. */
    data class Unreadable(val reason: UnreadableReason) : SourceLyrics

    data class Readable(val lyrics: Lyrics) : SourceLyrics
}

/** The lyrics shown for a track. */
internal sealed interface PickedLyrics {
    data class Found(val source: LyricsSource, val lyrics: Lyrics) : PickedLyrics

    data object Absent : PickedLyrics

    /** At least one source exists and none of them can be read; [reason] is the first one's. */
    data class Unreadable(val reason: UnreadableReason) : PickedLyrics
}

/**
 * Reads the sources in [LyricsSource] order and stops at the first readable one. An unreadable
 * source falls through to the next (owner decision 8), so "unreadable" is shown only when nothing
 * can be read. Inline so a caller can read sources with suspending calls.
 */
internal inline fun pickLyrics(read: (LyricsSource) -> SourceLyrics): PickedLyrics {
    var unreadable: UnreadableReason? = null
    for (source in LyricsSource.entries) {
        when (val result = read(source)) {
            is SourceLyrics.Readable -> return PickedLyrics.Found(source, result.lyrics)
            is SourceLyrics.Unreadable -> if (unreadable == null) unreadable = result.reason
            SourceLyrics.Absent -> Unit
        }
    }
    return unreadable?.let(PickedLyrics::Unreadable) ?: PickedLyrics.Absent
}

/** Decodes and parses the bytes of a lyrics file; a file with nothing to show counts as absent. */
internal fun readLyricsFile(bytes: ByteArray): SourceLyrics {
    val text = decodeLyricsText(bytes) ?: return SourceLyrics.Unreadable(UnreadableReason.Encoding)
    return parseLyrics(text)?.let(SourceLyrics::Readable) ?: SourceLyrics.Absent
}
