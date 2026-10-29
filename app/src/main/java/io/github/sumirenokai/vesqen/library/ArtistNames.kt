package io.github.sumirenokai.vesqen.library

import java.text.Normalizer
import java.util.Locale

/**
 * How the Artists page reads a tag that names several artists (#168, PRD "2026-10-24 多位艺术家拆分").
 * Display only: tags, the catalog and its IDs stay as stored, and songs, Now and track details show
 * the tag as written.
 */
data class ArtistSplitSettings(
    val enabled: Boolean = true,
    val separators: List<String> = DefaultArtistSeparators,
    val keptNames: List<String> = DefaultKeptArtistNames,
)

/**
 * `&`, `,` and `x` are not here: "Simon & Garfunkel" and "Earth, Wind & Fire" are one act each, and
 * a wrong split invents artists, while a tag left whole only stays as it was before #168.
 */
val DefaultArtistSeparators = listOf("/", ";", "；", "、", "feat.", "ft.")

/** "AC/DC" is the PRD's example of a name a default separator would cut. */
val DefaultKeptArtistNames = listOf("AC/DC")

/** Separators as typed in Settings: apart by spaces (a full-width one too), each once. */
fun parseArtistSeparators(text: String): List<String> = text.words().distinctBy { it.lowercase(Locale.ROOT) }

/** Names as typed in Settings: one per line, each once. */
fun parseKeptArtistNames(text: String): List<String> =
    text.lines().map(String::trim).filter(String::isNotEmpty).distinctBy(::artistNameKey)

/** One artist a track is filed under; [key] decides which names are one artist. */
class ArtistName(val key: String, val name: String)

/**
 * The artists of each track. A tag is split once and kept, since a library repeats its tags; search
 * and sort build the Artists list again without splitting again. Not safe to share between threads.
 */
class ArtistNames(settings: ArtistSplitSettings) {
    private val enabled = settings.enabled
    // Longest first, so a longer separator is not cut by a shorter one inside it.
    private val separators = settings.separators.filter(String::isNotEmpty).sortedByDescending(String::length)
        .map { Separator(it, word = it.any(Char::isLetterOrDigit)) }
    private val kept = settings.keptNames.mapTo(HashSet(), ::artistNameKey)
    private val names = HashMap<String, List<ArtistName>>()

    fun of(track: AudioTrack): List<ArtistName> {
        val tag = track.artist.ifBlank { track.albumArtist }
        return names.getOrPut(tag) {
            // Switched off, the whole tag is the artist, exactly as before #168.
            if (enabled) split(tag) else listOf(ArtistName(tag, tag))
        }
    }

    private fun split(tag: String): List<ArtistName> {
        val parts = parts(tag)
        val found = LinkedHashMap<String, ArtistName>()
        var first = 0
        while (first < parts.size) {
            val last = keptNameEnd(tag, parts, first)
            val name = tag.substring(parts[first].start, parts[last].end).trim()
            if (name.isNotEmpty()) artistNameKey(name).let { key -> found.getOrPut(key) { ArtistName(key, name) } }
            first = last + 1
        }
        // A tag of separators only ("/") still names someone.
        if (found.isEmpty()) return listOf(tag.trim().let { ArtistName(artistNameKey(it), it) })
        return found.values.toList()
    }

    /** The text between separators. */
    private fun parts(tag: String): List<Part> {
        val parts = ArrayList<Part>()
        var start = 0
        var index = 0
        while (index < tag.length) {
            val separator = separators.firstOrNull { it.matches(tag, index) }
            if (separator == null) {
                index++
            } else {
                parts += Part(start, index)
                index += separator.text.length
                start = index
            }
        }
        parts += Part(start, tag.length)
        return parts
    }

    /** The last part that, joined to [first] with the separators between them, is a kept name. */
    private fun keptNameEnd(tag: String, parts: List<Part>, first: Int): Int {
        if (kept.isEmpty()) return first
        for (last in parts.lastIndex downTo first + 1) {
            if (artistNameKey(tag.substring(parts[first].start, parts[last].end)) in kept) return last
        }
        return first
    }

    private class Part(val start: Int, val end: Int)

    /** A separator with letters, such as "feat.", splits only between spaces: "A feat. B", not "Afeat.B". */
    private class Separator(val text: String, val word: Boolean) {
        fun matches(tag: String, index: Int): Boolean {
            if (!tag.regionMatches(index, text, 0, text.length, ignoreCase = true)) return false
            if (!word) return true
            val after = index + text.length
            return index > 0 && tag[index - 1].isWhitespace() && after < tag.length && tag[after].isWhitespace()
        }
    }
}

/**
 * Names that differ only in case, width ("ＡＢＣ") or spacing are one artist; the list shows the
 * spelling most of their songs use.
 */
internal fun artistNameKey(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFKC).words().joinToString(" ").lowercase(Locale.ROOT)

/** Kotlin's [Char.isWhitespace] knows the full-width space; a `\s` regex on the JVM does not. */
private fun String.words(): List<String> {
    val words = ArrayList<String>()
    var start = -1
    forEachIndexed { index, char ->
        if (!char.isWhitespace()) {
            if (start < 0) start = index
        } else if (start >= 0) {
            words += substring(start, index)
            start = -1
        }
    }
    if (start >= 0) words += substring(start)
    return words
}
