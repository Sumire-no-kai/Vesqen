package io.github.sumirenokai.vesqen.ui.privacy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPolicyDocumentTest {
    @Test
    fun `parses headings notes paragraphs bullets and tables`() {
        val blocks = PolicyMarkdown.parse(
            """
            # Title

            > **Draft.** First note.
            >
            > Second note.

            ## Section
            A paragraph.

            - One `CODE` item
            - Two **bold** item

            | Permission | Why |
            | --- | --- |
            | `READ` | To read. |
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                PolicyBlock.Heading(1, listOf(PolicySpan("Title"))),
                PolicyBlock.Note(listOf(PolicySpan("Draft.", bold = true), PolicySpan(" First note."))),
                PolicyBlock.Note(listOf(PolicySpan("Second note."))),
                PolicyBlock.Heading(2, listOf(PolicySpan("Section"))),
                PolicyBlock.Paragraph(listOf(PolicySpan("A paragraph."))),
                PolicyBlock.Bullets(
                    listOf(
                        listOf(PolicySpan("One "), PolicySpan("CODE", code = true), PolicySpan(" item")),
                        listOf(PolicySpan("Two "), PolicySpan("bold", bold = true), PolicySpan(" item")),
                    ),
                ),
                PolicyBlock.Table(
                    listOf(
                        listOf(listOf(PolicySpan("Permission")), listOf(PolicySpan("Why"))),
                        listOf(listOf(PolicySpan("READ", code = true)), listOf(PolicySpan("To read."))),
                    ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `links keep their text while bracketed placeholders stay literal`() {
        assertEquals(
            listOf(PolicySpan("[TBD: see "), PolicySpan("MONETIZATION.md"), PolicySpan(".]")),
            PolicyMarkdown.parseInline("[TBD: see [MONETIZATION.md](MONETIZATION.md).]"),
        )
    }

    @Test
    fun `stacked table rows keep each cell's column name`() {
        val table = PolicyMarkdown.parse(
            """
            | Feature | How long we keep it |
            | --- | --- |
            | Device report upload | 7 days |
            """.trimIndent(),
        ).single() as PolicyBlock.Table

        assertEquals(
            listOf(
                PolicyTableRow(
                    title = listOf(PolicySpan("Device report upload")),
                    cells = listOf(PolicyTableCell(listOf(PolicySpan("How long we keep it")), listOf(PolicySpan("7 days")))),
                ),
            ),
            table.bodyRows(),
        )
    }

    @Test
    fun `every cell of the packaged policies has a column name`() {
        for (name in POLICY_FILES) {
            val cells = PolicyMarkdown.parse(policyFile(name)).filterIsInstance<PolicyBlock.Table>()
                .flatMap { it.bodyRows() }
                .flatMap(PolicyTableRow::cells)
            assertTrue("$name has no table cells", cells.isNotEmpty())
            assertTrue("$name has a cell without a column name", cells.all { it.header != null })
        }
    }

    @Test
    fun `the document title is not repeated under the screen title`() {
        val blocks = PolicyMarkdown.parse("# Vesqen Privacy Policy\n\nBody").withoutDocumentTitle()

        assertEquals(listOf(PolicyBlock.Paragraph(listOf(PolicySpan("Body")))), blocks)
    }

    @Test
    fun `packaged policies use only the supported format and match across languages`() {
        val english = PolicyMarkdown.parse(policyFile("PRIVACY_POLICY.md"))
        for (name in POLICY_FILES) {
            val blocks = PolicyMarkdown.parse(policyFile(name))
            val title = blocks.first()
            assertTrue(title is PolicyBlock.Heading && title.level == 1)
            val leftovers = blocks.flatMap(::spansOf)
                .map(PolicySpan::text)
                .filter { text -> MARKDOWN_LEFTOVERS.any(text::contains) }
            assertTrue("Unsupported Markdown left in the policy: $leftovers", leftovers.isEmpty())
            assertEquals("$name sections", english.sectionCount(), blocks.sectionCount())
            assertEquals("$name bullets", english.bulletShape(), blocks.bulletShape())
            assertEquals("$name tables", english.tableShape(), blocks.tableShape())
            for (table in blocks.filterIsInstance<PolicyBlock.Table>()) {
                assertTrue("$name table needs a header and body", table.rows.size >= 2)
                assertTrue("$name table has inconsistent columns", table.rows.all { it.size == table.rows.first().size })
            }
        }
    }

    @Test
    fun `all policy translations preserve line structure and technical identifiers`() {
        val english = policyFile(POLICY_FILES.first())
        for (name in POLICY_FILES) {
            val text = policyFile(name)
            assertEquals("$name line structure", english.lines().map(::lineKind), text.lines().map(::lineKind))
            assertEquals("$name technical identifiers", codeIdentifiers(english), codeIdentifiers(text))
            assertTrue("$name contains a draft marker", listOf("TBD", "待定", "Draft", "草案").none(text::contains))
        }
    }

    @Test
    fun `each locale selects its own bundled policy and web page`() {
        for ((resourceDirectory, language, route) in listOf(
            Triple("values", "en", ""),
            Triple("values-zh-rCN", "zh-CN", "zh/"),
            Triple("values-b+zh+Hant", "zh-Hant", "zh-hant/"),
            Triple("values-ja", "ja", "ja/"),
        )) {
            val strings = File("src/main/res/$resourceDirectory/strings.xml").readText()
            val asset = Regex("""<string name="privacy_policy_asset">([^<]+)</string>""").find(strings)?.groupValues?.get(1)
            val url = Regex("""<string name="privacy_policy_url">([^<]+)</string>""").find(strings)?.groupValues?.get(1)
            assertEquals(resourceDirectory, "privacy/$language.md", asset)
            assertEquals(resourceDirectory, "https://vesqen.sumirenokai.com/${route}privacy/", url)
            val policy = if (language == "en") "PRIVACY_POLICY.md" else "PRIVACY_POLICY.$language.md"
            assertTrue("$resourceDirectory policy is missing", File("../docs/$policy").isFile)
        }
    }

    private fun lineKind(line: String): String = when {
        line.isBlank() -> "blank"
        line.startsWith("#") -> line.takeWhile { it == '#' }
        line.startsWith("|") -> "table:${line.count { it == '|' }}"
        line.startsWith("- ") -> "bullet"
        else -> "paragraph"
    }

    private fun codeIdentifiers(text: String): List<String> =
        Regex("`([^`]+)`").findAll(text).map { it.groupValues[1] }.sorted().toList()

    // Unit tests run from the app module, and the build packages these same files into the app.
    private fun policyFile(name: String): String = File("../docs/$name").readText()

    private fun spansOf(block: PolicyBlock): List<PolicySpan> = when (block) {
        is PolicyBlock.Heading -> block.content
        is PolicyBlock.Paragraph -> block.content
        is PolicyBlock.Note -> block.content
        is PolicyBlock.Bullets -> block.items.flatten()
        is PolicyBlock.Table -> block.rows.flatten().flatten()
    }

    private fun List<PolicyBlock>.sectionCount(): Int =
        count { it is PolicyBlock.Heading && it.level == 2 }

    private fun List<PolicyBlock>.bulletShape(): List<Int> =
        filterIsInstance<PolicyBlock.Bullets>().map { it.items.size }

    private fun List<PolicyBlock>.tableShape(): List<List<Int>> =
        filterIsInstance<PolicyBlock.Table>().map { table -> table.rows.map(List<List<PolicySpan>>::size) }

    private companion object {
        val POLICY_FILES = listOf("PRIVACY_POLICY.md", "PRIVACY_POLICY.zh-CN.md", "PRIVACY_POLICY.zh-Hant.md", "PRIVACY_POLICY.ja.md")
        val MARKDOWN_LEFTOVERS = listOf("**", "`", "](", "|", "#")
    }
}
