package io.github.sumirenokai.vesqen.ui.privacy

/**
 * The Markdown subset used by `docs/PRIVACY_POLICY*.md`, which the build packages into the app so
 * the in-app text is exactly the text published on the web. Lines outside the subset stay plain
 * paragraph text; the document tests keep both policy files inside it.
 */
internal sealed interface PolicyBlock {
    data class Heading(val level: Int, val content: List<PolicySpan>) : PolicyBlock
    data class Paragraph(val content: List<PolicySpan>) : PolicyBlock
    data class Bullets(val items: List<List<PolicySpan>>) : PolicyBlock
    data class Note(val content: List<PolicySpan>) : PolicyBlock
    data class Table(val rows: List<List<List<PolicySpan>>>) : PolicyBlock
}

internal data class PolicySpan(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false,
)

internal object PolicyMarkdown {
    fun parse(source: String): List<PolicyBlock> {
        val blocks = mutableListOf<PolicyBlock>()
        val paragraph = mutableListOf<String>()
        val bullets = mutableListOf<List<PolicySpan>>()
        val note = mutableListOf<String>()
        val table = mutableListOf<List<String>>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            blocks += PolicyBlock.Paragraph(parseInline(paragraph.joinToString(" ")))
            paragraph.clear()
        }
        fun flushBullets() {
            if (bullets.isEmpty()) return
            blocks += PolicyBlock.Bullets(bullets.toList())
            bullets.clear()
        }
        fun flushNote() {
            if (note.isEmpty()) return
            blocks += PolicyBlock.Note(parseInline(note.joinToString(" ")))
            note.clear()
        }
        fun flushTable() {
            if (table.isEmpty()) return
            blocks += PolicyBlock.Table(table.map { row -> row.map(::parseInline) })
            table.clear()
        }
        fun flushAll() {
            flushParagraph()
            flushBullets()
            flushNote()
            flushTable()
        }

        for (rawLine in source.lines()) {
            val line = rawLine.trimEnd()
            val heading = HEADING.matchEntire(line)
            when {
                line.isBlank() -> flushAll()
                heading != null -> {
                    flushAll()
                    val (marks, text) = heading.destructured
                    blocks += PolicyBlock.Heading(marks.length, parseInline(text))
                }
                line.startsWith(">") -> {
                    flushParagraph()
                    flushBullets()
                    flushTable()
                    // A bare ">" separates two notes inside one quoted region.
                    val text = line.removePrefix(">").trim()
                    if (text.isEmpty()) flushNote() else note += text
                }
                line.startsWith("- ") -> {
                    flushParagraph()
                    flushNote()
                    flushTable()
                    bullets += parseInline(line.removePrefix("- ").trim())
                }
                line.startsWith("|") -> {
                    flushParagraph()
                    flushBullets()
                    flushNote()
                    val cells = line.trim().removePrefix("|").removeSuffix("|").split("|").map(String::trim)
                    if (!cells.all(TABLE_RULE::matches)) table += cells
                }
                else -> {
                    flushBullets()
                    flushNote()
                    flushTable()
                    paragraph += line.trim()
                }
            }
        }
        flushAll()
        return blocks
    }

    fun parseInline(text: String): List<PolicySpan> {
        val spans = mutableListOf<PolicySpan>()
        var index = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > index) spans += PolicySpan(text.substring(index, match.range.first))
            val (bold, code, linkText) = match.destructured
            spans += when {
                bold.isNotEmpty() -> PolicySpan(bold, bold = true)
                code.isNotEmpty() -> PolicySpan(code, code = true)
                // Links show their text only; the screen offers the web version as its own action.
                else -> PolicySpan(linkText)
            }
            index = match.range.last + 1
        }
        if (index < text.length) spans += PolicySpan(text.substring(index))
        return spans
    }

    private val HEADING = Regex("^(#{1,3}) (.+)$")
    private val TABLE_RULE = Regex("^:?-{3,}:?$")
    private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\[\]]+)]\([^)\s]+\)""")
}

/** The screen title already names the document, so the file's own top-level title is not repeated. */
internal fun List<PolicyBlock>.withoutDocumentTitle(): List<PolicyBlock> {
    val first = firstOrNull()
    return if (first is PolicyBlock.Heading && first.level == 1) drop(1) else this
}
