package io.github.sumirenokai.vesqen.reports

import android.content.Intent
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class DeviceReportShareVisibilityTest {
    /** Android 11+ hides apps from resolveActivity() unless the manifest declares the intent. */
    @Test fun `manifest declares both intents the share check resolves`() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement
        val declared = manifest.children("queries").flatMap { it.children("intent") }.map { intent ->
            val data = intent.children("data").single()
            listOf(intent.children("action").single().android("name"), data.android("mimeType"), data.android("scheme"))
        }
        // Sharing resolves the SEND intent itself; email resolves its SENDTO mailto selector.
        assertTrue(declared.toString(), listOf(Intent.ACTION_SEND, "application/json", "") in declared)
        assertTrue(declared.toString(), listOf(Intent.ACTION_SENDTO, "", "mailto") in declared)
    }

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().filter { it.tagName == name }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
