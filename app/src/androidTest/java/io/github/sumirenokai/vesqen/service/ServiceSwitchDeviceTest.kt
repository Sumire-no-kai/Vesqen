package io.github.sumirenokai.vesqen.service

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** #96 on the device's own JSON and HTTP stacks. */
class ServiceSwitchDeviceTest {
    private val paused = ServiceSwitchFile.Paused

    @Test fun theFileIsReadStrictlyAndAnythingUnclearPauses() {
        assertEquals(ServiceStatus(ServiceState.ENABLED, ServiceState.ENABLED),
            ServiceSwitchFile.parse("""{ "schemaVersion": 1, "usagePings": "enabled", "reportUploads": "enabled" }"""))
        assertEquals(ServiceStatus(ServiceState.PAUSED, ServiceState.RETIRED),
            ServiceSwitchFile.parse("""{"schemaVersion":1,"usagePings":"paused","reportUploads":"retired"}"""))
        // Each entry stands alone: an unknown value pauses only its own service.
        assertEquals(ServiceStatus(ServiceState.PAUSED, ServiceState.ENABLED),
            ServiceSwitchFile.parse("""{"schemaVersion":1,"usagePings":"on","reportUploads":"enabled"}"""))
        listOf(
            """{"schemaVersion":1}""",
            """{"schemaVersion":2,"usagePings":"enabled","reportUploads":"enabled"}""",
            """{"schemaVersion":"1","usagePings":"enabled","reportUploads":"enabled"}""",
            """{"usagePings":"enabled","reportUploads":"enabled"}""",
            """{"schemaVersion":1,"usagePings":"ENABLED","reportUploads":true}""",
            "[]", "{", "",
        ).forEach { assertEquals(it, paused, ServiceSwitchFile.parse(it)) }
    }

    @Test fun aPlainGetWhereMissingFilesAndNetworkErrorsSendNothing() = runBlocking {
        val url = "https://example.test/service/status.json"
        val ok = Response(200, """{"schemaVersion":1,"usagePings":"enabled","reportUploads":"paused"}""")
        assertEquals(ServiceStatus(ServiceState.ENABLED, ServiceState.PAUSED), HttpsServiceSwitchSource(url) { ok.at(it) }.read())
        assertEquals("GET", ok.requestMethod)
        assertEquals(url, ok.url.toString())
        assertEquals("", ok.getRequestProperty("User-Agent"))
        assertFalse(ok.instanceFollowRedirects)
        assertEquals(5_000, ok.connectTimeout)
        assertEquals(5_000, ok.readTimeout)
        // Missing file, a redirect or a server error cannot be read: nothing is sent.
        listOf(404, 301, 503).forEach { status ->
            assertNull(status.toString(), HttpsServiceSwitchSource(url) { Response(status, "").at(it) }.read())
        }
        assertNull(HttpsServiceSwitchSource(url) { throw IOException("offline") }.read())
        assertNull(HttpsServiceSwitchSource(url) { throw SecurityException("firewall") }.read())
        assertNull(HttpsServiceSwitchSource("http://example.test/service/status.json") { error("must not connect") }.read())
        // A file that arrives but is too large is not understood, so it pauses.
        val large = "{\"schemaVersion\":1,\"usagePings\":\"enabled\",\"reportUploads\":\"enabled\",\"x\":\"" + "a".repeat(HttpsServiceSwitchSource.MaxBytes) + "\"}"
        assertEquals(paused, HttpsServiceSwitchSource(url) { Response(200, large).at(it) }.read())
    }

    private class Response(private val status: Int, private val body: String) : HttpURLConnection(URL("https://placeholder.test/")) {
        private var target: URL = super.getURL()
        fun at(url: URL) = apply { target = url }
        override fun getURL(): URL = target
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getResponseCode() = status
    }
}
