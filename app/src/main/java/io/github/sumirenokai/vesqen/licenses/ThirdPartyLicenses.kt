package io.github.sumirenokai.vesqen.licenses

import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** Upstream names/text are attribution data, not application UI copy. */
data class ThirdPartyLicense(
    val id: String,
    val name: String,
    val version: String,
    val licenses: List<LicenseText>,
    val notices: List<LicenseNotice>,
)

data class LicenseText(val name: String, val text: String, val source: String)
data class LicenseNotice(val text: String, val source: String)

enum class LicenseCatalogFailure { READ_FAILED, INVALID_CATALOG, UNSUPPORTED_SCHEMA, PARSER_UNAVAILABLE }

sealed interface ThirdPartyLicensesResult {
    data class Loaded(val entries: List<ThirdPartyLicense>) : ThirdPartyLicensesResult
    data class Unavailable(val reason: LicenseCatalogFailure) : ThirdPartyLicensesResult
}

object ThirdPartyLicenses {
    const val ASSET_PATH = "licenses/third-party.xml"

    /** Call off the main thread with AssetManager::open. No network or persistent cache is used. */
    fun load(openAsset: (String) -> InputStream): ThirdPartyLicensesResult = try {
        val text = openAsset(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        // Generated assets never contain a DTD. Reject it before parsing, including internal entities.
        require(!text.contains("<!DOCTYPE"))
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External entities are not allowed") }
        builder.setErrorHandler(object : DefaultHandler() {
            override fun error(exception: org.xml.sax.SAXParseException) { throw exception }
            override fun fatalError(exception: org.xml.sax.SAXParseException) { throw exception }
        })
        val root = builder.parse(InputSource(StringReader(text))).documentElement
        require(root.tagName == "thirdPartyLicenses")
        if (root.getAttribute("schemaVersion") != "1") {
            ThirdPartyLicensesResult.Unavailable(LicenseCatalogFailure.UNSUPPORTED_SCHEMA)
        } else {
            val entries = root.elements().map { component ->
                require(component.tagName == "component")
                val documents = component.elements()
                require(documents.all { it.tagName == "license" || it.tagName == "notice" })
                val licenses = documents.filter { it.tagName == "license" }.map {
                    require(it.elements().isEmpty())
                    LicenseText(it.requiredAttribute("name"), it.requiredText(), it.requiredAttribute("source"))
                }
                require(licenses.isNotEmpty())
                val notices = documents.filter { it.tagName == "notice" }.map {
                    require(it.elements().isEmpty())
                    LicenseNotice(it.requiredText(), it.requiredAttribute("source"))
                }
                ThirdPartyLicense(
                    component.requiredAttribute("id"), component.requiredAttribute("name"),
                    component.requiredAttribute("version"), licenses, notices,
                )
            }
            require(entries.isNotEmpty() && entries.map { it.id }.distinct().size == entries.size)
            ThirdPartyLicensesResult.Loaded(entries)
        }
    } catch (_: IOException) {
        ThirdPartyLicensesResult.Unavailable(LicenseCatalogFailure.READ_FAILED)
    } catch (_: SAXException) {
        ThirdPartyLicensesResult.Unavailable(LicenseCatalogFailure.INVALID_CATALOG)
    } catch (_: IllegalArgumentException) {
        ThirdPartyLicensesResult.Unavailable(LicenseCatalogFailure.INVALID_CATALOG)
    } catch (_: ParserConfigurationException) {
        ThirdPartyLicensesResult.Unavailable(LicenseCatalogFailure.PARSER_UNAVAILABLE)
    }
}

private fun Element.requiredAttribute(name: String): String = getAttribute(name).also { require(it.isNotBlank()) }
private fun Element.requiredText(): String = textContent.also { require(it.isNotBlank()) }
private fun Element.elements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
