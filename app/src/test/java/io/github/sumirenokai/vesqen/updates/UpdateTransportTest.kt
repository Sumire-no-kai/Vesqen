package io.github.sumirenokai.vesqen.updates

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateTransportTest {
    private class Response(url: URL, private val code: Int = 200, private val body: ByteArray = byteArrayOf(),
                           private val location: String? = null, private val length: Long = body.size.toLong()) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getHeaderFieldLong(name: String?, default: Long) = if (name == "Content-Length") length else default
    }

    @Test fun redirectsMustRemainHttpsAndAllConnectionsClose() = runBlocking {
        val response = Response(URL("https://example.org/"), 302, location = "http://example.org/app.apk")
        val transport = HttpsUpdateTransport { response }
        try { transport.manifest("https://example.org/"); fail("HTTP downgrade accepted") }
        catch (failure: UpdateOperationException) { assertEquals(UpdateFailure.DOWNLOAD_FAILED, failure.reason) }
        assertTrue(response.closed)
        assertEquals("", response.getRequestProperty("User-Agent"))
        assertFalse(response.instanceFollowRedirects)
    }

    @Test fun sizeLimitAndTruncatedDownloadAreRejected() = runBlocking {
        val large = Response(URL("https://example.org/"), body = ByteArray(UpdateManifest.MAX_BYTES + 1))
        try { HttpsUpdateTransport { large }.manifest("https://example.org/"); fail("Unbounded manifest") }
        catch (failure: UpdateOperationException) { assertEquals(UpdateFailure.INVALID_MANIFEST, failure.reason) }
        assertTrue(large.closed)
        val short = Response(URL("https://example.org/"), body = byteArrayOf(1), length = 2)
        val file = Files.createTempFile("update-transport", ".apk").toFile()
        try {
            try { HttpsUpdateTransport { short }.download("https://example.org/", file) { _, _ -> }; fail("Truncated APK") }
            catch (_: IOException) { assertTrue(short.closed) }
        } finally { file.delete() }
    }
}
