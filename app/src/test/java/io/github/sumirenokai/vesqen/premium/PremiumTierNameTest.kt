package io.github.sumirenokai.vesqen.premium

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * In Chinese the paid tier is 高级功能 / 進階功能. Free screens must not use 高级 / 進階 on their own, or
 * "2.0 起高级功能需要买断" reads as if the Chain details page and the settings group were paid too (#161).
 */
class PremiumTierNameTest {
    // String directory -> (word of the paid tier, the tier's full name).
    private val tiers = mapOf(
        "values-zh-rCN" to ("高级" to "高级功能"),
        "values-b+zh+Hant" to ("進階" to "進階功能"),
    )

    @Test
    fun theTierWordAppearsOnlyInTheTierName() {
        for ((locale, tier) in tiers) {
            val (word, name) = tier
            val strings = strings(File("src/main/res/$locale/strings.xml"))
            assertEquals("$locale settings_premium", name, strings.getValue("settings_premium"))
            val misuses = strings.filterValues { it.replace(name, "").contains(word) }
            assertEquals("$locale uses $word outside $name", emptyMap<String, String>(), misuses)
        }
    }

    /** Strings by name, and plural forms as "name/quantity". */
    private fun strings(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        fun elements(tag: String) = document.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
        return elements("string").associate { it.getAttribute("name") to it.textContent } +
            elements("item").associate { "${(it.parentNode as Element).getAttribute("name")}/${it.getAttribute("quantity")}" to it.textContent }
    }
}
