package io.github.sumirenokai.vesqen.ui.theme

import java.io.File
import java.nio.ByteBuffer
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The page-title font is a subset (tools/subset_title_font.py). A title character it lacks would
 * render in the system font, so a renamed or newly listed title must come with a regenerated font.
 */
class TitleFontCoverageTest {
    @Test
    fun titleFontCoversEveryPageTitleAndPrintableAscii() {
        val covered = cmapCodePoints(File("src/main/res/font/title_serif_semibold.otf").readBytes())
        val keys = File("../tools/title_font_strings.txt").readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(keys.isNotEmpty())
        for (locale in listOf("values", "values-zh-rCN")) {
            val strings = strings(File("src/main/res/$locale/strings.xml"))
            for (key in keys) {
                val title = requireNotNull(strings[key]) { "$locale has no $key" }
                val missing = title.codePoints().toArray().filterNot(covered::contains)
                    .joinToString("") { String(Character.toChars(it)) }
                assertEquals("$locale/$key \"$title\" lacks glyphs", "", missing)
            }
        }
        assertTrue((0x20..0x7E).all(covered::contains))
    }

    private fun strings(file: File): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.replace(Regex("""\\(.)"""), "$1") }
    }

    /** Code points mapped by the Windows Unicode BMP (format 4) cmap subtable. */
    private fun cmapCodePoints(font: ByteArray): Set<Int> {
        val data = ByteBuffer.wrap(font)
        fun u16(offset: Int) = data.getShort(offset).toInt() and 0xFFFF
        val cmap = (0 until u16(4)).map { 12 + it * 16 }
            .single { String(font, it, 4, Charsets.US_ASCII) == "cmap" }
            .let { data.getInt(it + 8) }
        val table = (0 until u16(cmap + 2)).map { cmap + 4 + it * 8 }
            .single { u16(it) == 3 && u16(it + 2) == 1 }
            .let { cmap + data.getInt(it + 4) }
        assertEquals(4, u16(table))
        val segments = u16(table + 6) / 2
        val ends = table + 14
        val starts = ends + segments * 2 + 2
        val deltas = starts + segments * 2
        val rangeOffsets = deltas + segments * 2
        return buildSet {
            for (i in 0 until segments) {
                val start = u16(starts + i * 2)
                val delta = u16(deltas + i * 2)
                val rangeOffsetAt = rangeOffsets + i * 2
                val rangeOffset = u16(rangeOffsetAt)
                for (codePoint in start..u16(ends + i * 2)) {
                    if (codePoint == 0xFFFF) continue
                    val glyph = if (rangeOffset == 0) {
                        (codePoint + delta) and 0xFFFF
                    } else {
                        u16(rangeOffsetAt + rangeOffset + (codePoint - start) * 2).let { if (it == 0) 0 else (it + delta) and 0xFFFF }
                    }
                    if (glyph != 0) add(codePoint)
                }
            }
        }
    }
}
