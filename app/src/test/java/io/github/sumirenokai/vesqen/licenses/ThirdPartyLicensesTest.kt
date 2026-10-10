package io.github.sumirenokai.vesqen.licenses

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyLicensesTest {
    @Test
    fun `reader preserves upstream text and closes asset`() {
        var closed = false
        val result = ThirdPartyLicenses.load { path ->
            assertEquals(ThirdPartyLicenses.ASSET_PATH, path)
            object : ByteArrayInputStream(catalog().toByteArray()) {
                override fun close() { closed = true; super.close() }
            }
        } as ThirdPartyLicensesResult.Loaded
        assertTrue(closed)
        val entry = result.entries.single()
        assertEquals("Library & tools", entry.name)
        assertEquals("1.0", entry.version)
        assertEquals("Apache-2.0", entry.licenses.single().name)
        assertEquals(" First line\nSecond <line>\n", entry.licenses.single().text)
        assertEquals("Copyright authors\n", entry.notices.single().text)
        assertEquals("upstream/NOTICE", entry.notices.single().source)
    }

    @Test
    fun `invalid catalog never returns a partial list`() {
        listOf(
            catalog().replace("schemaVersion=\"1\"", "schemaVersion=\"2\"") to LicenseCatalogFailure.UNSUPPORTED_SCHEMA,
            "<broken>" to LicenseCatalogFailure.INVALID_CATALOG,
            catalog().replace("version=\"1.0\"", "version=\"\"") to LicenseCatalogFailure.INVALID_CATALOG,
            catalog().replace("</thirdPartyLicenses>", "<component/></thirdPartyLicenses>") to LicenseCatalogFailure.INVALID_CATALOG,
            catalog().replace("<license ", "<unknown ").replace("</license>", "</unknown>") to LicenseCatalogFailure.INVALID_CATALOG,
            catalog().replace("</thirdPartyLicenses>", component() + "</thirdPartyLicenses>") to LicenseCatalogFailure.INVALID_CATALOG,
            "<!DOCTYPE thirdPartyLicenses [<!ENTITY value SYSTEM 'file:///unreadable'>]>" + catalog() to LicenseCatalogFailure.INVALID_CATALOG,
        ).forEach { (xml, reason) ->
            assertEquals(ThirdPartyLicensesResult.Unavailable(reason), ThirdPartyLicenses.load { xml.byteInputStream() })
        }
        assertEquals(ThirdPartyLicensesResult.Unavailable(LicenseCatalogFailure.READ_FAILED), ThirdPartyLicenses.load { throw IOException() })
    }

    @Test
    fun `generated variant catalog loads with complete texts fonts and notices`() {
        val entries = loadGenerated(File(requireNotNull(System.getProperty("licenses.generatedFile"))))
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        assertTrue(entries.all { it.licenses.all { text -> text.text.isNotBlank() } })
        assertEquals(setOf("font:InstrumentSans", "font:InstrumentSerif", "font:NotoSerifSC"),
            entries.filter { it.id.startsWith("font:") }.map { it.id }.toSet())
        assertTrue(entries.filter { it.id.startsWith("font:") }.all {
            it.version.isNotBlank() && it.licenses.single().name == "OFL-1.1"
        })
        assertTrue(entries.single { it.id.startsWith("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:") }
            .notices.single().text.contains("kotlinx.coroutines library."))
        for (font in entries.filter { it.id.startsWith("font:") }) {
            val ofl = File("src/main/assets/licenses/fonts/${font.id.removePrefix("font:")}-OFL.txt")
            assertEquals(ofl.readText(), font.licenses.single().text)
        }
    }

    private fun loadGenerated(file: File) = (ThirdPartyLicenses.load { file.inputStream() } as ThirdPartyLicensesResult.Loaded).entries
    private fun catalog() = "<thirdPartyLicenses schemaVersion=\"1\">${component()}</thirdPartyLicenses>"
    private fun component() = """<component id="example:library:1.0" name="Library &amp; tools" version="1.0"><license name="Apache-2.0" source="upstream/LICENSE"> First line
Second &lt;line&gt;
</license><notice source="upstream/NOTICE">Copyright authors
</notice></component>"""
}
