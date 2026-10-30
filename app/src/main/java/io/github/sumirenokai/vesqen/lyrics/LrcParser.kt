package io.github.sumirenokai.vesqen.lyrics

/**
 * Lyrics ready for Now (#169). Synced lines are ordered by time; plain text has no times, so it is
 * neither highlighted nor followed.
 */
internal sealed interface Lyrics {
    data class Synced(val lines: List<LyricLine>) : Lyrics

    data class Plain(val lines: List<String>) : Lyrics
}

/**
 * One timed line. The first text at a time is the original; every other distinct text at the same
 * time follows in file order as a translation. An empty [text] marks a pause between lines.
 */
internal data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translations: List<String> = emptyList(),
)

/**
 * Parses LRC or plain text and returns null when nothing is left to show.
 *
 * Only the leading run of `[mm:ss]`, `[mm:ss.f]` or `[mm:ss:f]` tags times a line; the fraction is
 * decimal, so `.5`, `.50` and `.500` are the same time. If any line carries a valid time, lines
 * without one are dropped; otherwise the whole text is plain. The standard LRC ID tags such as
 * `[ar:]` and `[ti:]` are hidden; other bracketed lines, such as "[Hook: Rihanna]" in lyrics without
 * times, are text. The last valid `[offset:]` moves every line earlier by its milliseconds. Word
 * times of enhanced LRC are removed, keeping line times only.
 */
internal fun parseLyrics(text: String): Lyrics? {
    val timed = ArrayList<TimedText>()
    val untimed = ArrayList<String>()
    var hasTimes = false
    var offsetMs = 0L
    text.replace(BYTE_ORDER_MARK, "")
        .trimEnd('\u0000')
        .lineSequence()
        .forEach { rawLine ->
            val line = rawLine.trim()
            when (val tags = readLeadingTimeTags(line)) {
                LeadingTimeTags.Invalid -> Unit
                null -> {
                    val information = INFORMATION_TAG.matchEntire(line)
                    when {
                        information == null -> if (untimed.size < MAX_LYRIC_LINES) untimed += line.capped()
                        information.groupValues[1].equals(OFFSET_KEY, ignoreCase = true) ->
                            parseOffset(information.groupValues[2])?.let { offsetMs = it }
                    }
                }
                is LeadingTimeTags.Times -> {
                    hasTimes = true
                    val body = stripWordTimes(line.substring(tags.textStart)).capped()
                    tags.timesMs.forEach { time ->
                        if (timed.size < MAX_LYRIC_LINES) timed += TimedText(time, body)
                    }
                }
            }
        }
    return if (hasTimes) syncedLyrics(timed, offsetMs) else plainLyrics(untimed)
}

/** Index of the line playing at [positionMs], or -1 before the first line. */
internal fun Lyrics.Synced.lineIndexAt(positionMs: Long): Int {
    // Times only repeat where an offset pulls several lines to 0; the last of them is current.
    var low = 0
    var high = lines.lastIndex
    var found = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (lines[middle].timeMs <= positionMs) {
            found = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return found
}

private class TimedText(val timeMs: Long, val text: String)

private sealed interface LeadingTimeTags {
    class Times(val timesMs: List<Long>, val textStart: Int) : LeadingTimeTags

    /** A time-shaped tag with an impossible value, such as 75 seconds: the whole line is dropped. */
    data object Invalid : LeadingTimeTags
}

private fun readLeadingTimeTags(line: String): LeadingTimeTags? {
    var cursor = 0
    val times = ArrayList<Long>(1)
    while (true) {
        val match = TIME_TAG.matchAt(line, cursor) ?: break
        times += match.toMillis() ?: return LeadingTimeTags.Invalid
        cursor = match.range.last + 1
        while (cursor < line.length && line[cursor].isWhitespace()) cursor++
    }
    return if (times.isEmpty()) null else LeadingTimeTags.Times(times, cursor)
}

private fun MatchResult.toMillis(): Long? {
    val minutes = groupValues[1].toLong()
    val seconds = groupValues[2].toLong()
    if (seconds >= 60) return null
    val fractionMs = groupValues[3].takeIf(String::isNotEmpty)?.padEnd(3, '0')?.toLong() ?: 0L
    return minutes * 60_000L + seconds * 1_000L + fractionMs
}

private fun parseOffset(value: String): Long? =
    value.trim().takeIf(OFFSET_VALUE::matches)?.toLong()

private fun stripWordTimes(body: String): String {
    val stripped = WORD_TIME_TAG.replace(body, "")
    if (stripped.length == body.length) return body.trim()
    return REPEATED_SPACES.replace(stripped, " ").trim()
}

/** Cuts an overlong line without splitting a surrogate pair. */
private fun String.capped(): String {
    if (length <= MAX_LYRIC_LINE_CHARS) return this
    val end = if (this[MAX_LYRIC_LINE_CHARS - 1].isHighSurrogate()) MAX_LYRIC_LINE_CHARS - 1 else MAX_LYRIC_LINE_CHARS
    return substring(0, end).trimEnd()
}

private fun syncedLyrics(timed: List<TimedText>, offsetMs: Long): Lyrics? {
    // A stable sort keeps file order within one time: that order decides original and translations.
    val sorted = timed.sortedBy(TimedText::timeMs)
    val lines = ArrayList<LyricLine>()
    var start = 0
    while (start < sorted.size) {
        val time = sorted[start].timeMs
        var end = start
        while (end < sorted.size && sorted[end].timeMs == time) end++
        val texts = sorted.subList(start, end).map(TimedText::text).filter(String::isNotEmpty).distinct()
        start = end
        val line = LyricLine(
            timeMs = (time - offsetMs).coerceAtLeast(0L),
            text = texts.firstOrNull().orEmpty(),
            translations = texts.drop(1),
        )
        // A pause before the first line or right after another pause adds only an empty row.
        if (line.text.isEmpty() && lines.lastOrNull()?.text?.isNotEmpty() != true) continue
        lines += line
    }
    return lines.takeIf { it.isNotEmpty() }?.let(Lyrics::Synced)
}

private fun plainLyrics(untimed: List<String>): Lyrics? {
    val lines = ArrayList<String>()
    untimed.forEach { line ->
        if (line.isNotEmpty() || lines.lastOrNull()?.isNotEmpty() == true) lines += line
    }
    while (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)
    return lines.takeIf { it.isNotEmpty() }?.let(Lyrics::Plain)
}

private const val BYTE_ORDER_MARK = "\uFEFF"
private const val OFFSET_KEY = "offset"
internal const val MAX_LYRIC_LINES = 5_000
internal const val MAX_LYRIC_LINE_CHARS = 500

// [0-9], not \d: Android's ICU regex treats \d as any Unicode digit, so full-width times would
// parse on a phone but not in JVM tests.
private val TIME_TAG = Regex("""\[([0-9]{1,3}):([0-9]{1,2})(?:[.:]([0-9]{1,3}))?]""")
private val WORD_TIME_TAG = Regex("""<[0-9]{1,3}:[0-9]{1,2}(?:[.:][0-9]{1,3})?>|\[[0-9]{1,3}:[0-9]{1,2}(?:[.:][0-9]{1,3})?]""")
private val INFORMATION_TAG = Regex(
    """\[(ar|ti|al|au|by|length|offset|re|ve|tool|la|id|#):(.*)]""",
    RegexOption.IGNORE_CASE,
)
private val OFFSET_VALUE = Regex("""[+-]?[0-9]{1,9}""")
private val REPEATED_SPACES = Regex(""" {2,}""")
