package io.github.sumirenokai.vesqen.storage

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/** #39: cloud backup and device transfer must exclude every Android backup data domain. */
class BackupRulesTest {
    @Test fun `Android 12 and later exclude all domains from both transfer modes`() {
        val rules = xml("res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", rules.tagName)
        assertEquals(listOf("cloud-backup", "device-transfer"), rules.children().map { it.tagName })
        rules.children().forEach(::assertAllDomainsExcluded)
    }

    @Test fun `Android 11 and earlier exclude all domains from legacy backup`() {
        val rules = xml("res/xml/backup_rules.xml")
        assertEquals("full-backup-content", rules.tagName)
        assertAllDomainsExcluded(rules)
    }

    @Test fun `manifest disables backup and retains both version-specific rule references`() {
        val app = xml("AndroidManifest.xml").children().single { it.tagName == "application" }
        assertEquals("false", app.android("allowBackup"))
        assertEquals("@xml/backup_rules", app.android("fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.android("dataExtractionRules"))
    }

    private fun assertAllDomainsExcluded(section: Element) {
        assertEquals("No include anywhere in ${section.tagName}", 0, section.getElementsByTagName("include").length)
        val rules = section.children()
        assertEquals(domains.size, rules.size)
        assertEquals(domains, rules.map { it.getAttribute("domain") }.toSet())
        rules.forEach { rule ->
            assertEquals("exclude", rule.tagName)
            assertEquals("Entire domain must be excluded", ".", rule.getAttribute("path"))
            assertEquals(2, rule.attributes.length)
            assertEquals(0, rule.children().size)
        }
    }

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File("src/main/$path")).documentElement

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>()

    private fun Element.android(name: String): String = getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private val domains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )
}
