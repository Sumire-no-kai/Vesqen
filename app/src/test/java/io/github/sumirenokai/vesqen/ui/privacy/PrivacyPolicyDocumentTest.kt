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
    fun `the document title is not repeated under the screen title`() {
        val blocks = PolicyMarkdown.parse("# Vesqen Privacy Policy\n\nBody").withoutDocumentTitle()

        assertEquals(listOf(PolicyBlock.Paragraph(listOf(PolicySpan("Body")))), blocks)
    }

    @Test
    fun `packaged policies use only the supported format and match across languages`() {
        val english = PolicyMarkdown.parse(policyFile("PRIVACY_POLICY.md"))
        val chinese = PolicyMarkdown.parse(policyFile("PRIVACY_POLICY.zh-CN.md"))

        for (blocks in listOf(english, chinese)) {
            val title = blocks.first()
            assertTrue(title is PolicyBlock.Heading && title.level == 1)
            val leftovers = blocks.flatMap(::spansOf)
                .map(PolicySpan::text)
                .filter { text -> MARKDOWN_LEFTOVERS.any(text::contains) }
            assertTrue("Unsupported Markdown left in the policy: $leftovers", leftovers.isEmpty())
        }
        assertEquals(english.sectionCount(), chinese.sectionCount())
        assertEquals(english.bulletShape(), chinese.bulletShape())
        assertEquals(english.tableShape(), chinese.tableShape())
    }

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
        val MARKDOWN_LEFTOVERS = listOf("**", "`", "](", "|", "#")
    }
}
