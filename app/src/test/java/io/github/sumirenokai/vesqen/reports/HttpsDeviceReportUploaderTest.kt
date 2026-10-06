package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.service.ServiceState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HttpsDeviceReportUploaderTest {
    @Test fun `only configured https usage endpoint can open a connection`() = runBlocking {
        listOf("http://example.test/v1/usage", "file:///v1/usage", "https://user:secret@example.test/v1/usage",
            "https://example.test/v1/usage?token=secret", "https://example.test/v1/usage#fragment",
            "https://example.test/other", "https:///v1/usage", "https://example.test:65536/v1/usage", "not a URL").forEach { address ->
            val result = HttpsDeviceReportUploader(address, enabled) { error("must not connect: $address") }.upload(artifact())
            assertEquals(address, failed, result)
        }
        assertEquals("https://example.test:8443/v1/reports", HttpsDeviceReportUploader.reportEndpoint("https://example.test:8443/v1/usage").toString())
    }

    @Test fun `missing configuration keeps its existing failure and never connects`() = runBlocking {
        assertEquals(DeviceReportUploadResult.Failed(DeviceReportFailure.UPLOAD_NOT_CONFIGURED),
            HttpsDeviceReportUploader("", enabled) { error("must not connect") }.upload(artifact()))
    }

    @Test fun `posts the exact preview bytes with bounded timeouts and redirects disabled`() = runBlocking {
        val report = artifact()
        val connection = Connection()
        var calls = 0
        val uploader = HttpsDeviceReportUploader(endpoint, enabled) { url ->
            calls++
            assertEquals("https://example.test/v1/reports", url.toString())
            connection
        }
        assertEquals(DeviceReportUploadResult.Uploaded(id), uploader.upload(report))
        assertEquals(1, calls)
        assertArrayEquals(report.copyBytes(), connection.sent.toByteArray())
        assertArrayEquals(report.previewText.toByteArray(Charsets.UTF_8), connection.sent.toByteArray())
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(5_000, connection.readTimeout)
        assertEquals(report.copyBytes().size, connection.length)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.doOutput)
        assertTrue(connection.disconnected)
    }

    @Test fun `request size is checked in bytes before connecting and includes the exact limit`() = runBlocking {
        val max = HttpsDeviceReportUploader.MAX_REPORT_BYTES
        val atLimit = DeviceReportArtifact(("{\"x\":\"" + "a".repeat(max - 8) + "\"}").toByteArray())
        assertEquals(max, atLimit.copyBytes().size)
        val connection = Connection()
        assertEquals(DeviceReportUploadResult.Uploaded(id), HttpsDeviceReportUploader(endpoint, enabled) { connection }.upload(atLimit))
        val aboveLimit = DeviceReportArtifact(("{\"x\":\"" + "界".repeat(max / 3) + "\"}").toByteArray())
        assertEquals(failed, HttpsDeviceReportUploader(endpoint, enabled) { error("oversized body must not connect") }.upload(aboveLimit))
    }

    @Test fun `redirect rejection server errors and invalid receipts are failures without retry`() = runBlocking {
        listOf(200, 204, 301, 302, 307, 308, 400, 413, 429, 500).forEach { status ->
            val connection = Connection(status)
            var calls = 0
            assertEquals(failed, HttpsDeviceReportUploader(endpoint, enabled) { calls++; connection }.upload(artifact()))
            assertEquals(1, calls)
            assertTrue(connection.disconnected)
        }
        listOf("", "{}", "{\"reportId\":null}", "{\"reportId\":123}", "{\"reportId\":\"/private/file\"}",
            "{\"reportId\":\"$id\",\"reportId\":\"$id\"}", "$receipt trailing", " ".repeat(4097) + receipt).forEach { response ->
            val connection = Connection(body = response)
            assertEquals(response.take(80), failed, HttpsDeviceReportUploader(endpoint, enabled) { connection }.upload(artifact()))
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `IO timeout and permission failures at connection write or read map to upload failed`() = runBlocking {
        listOf(IOException("private"), SocketTimeoutException("private"), SecurityException("private")).forEach { exception ->
            assertEquals(failed, HttpsDeviceReportUploader(endpoint, enabled) { throw exception }.upload(artifact()))
            listOf(true, false).forEach { writing ->
                val connection = Connection().apply { if (writing) writeFailure = exception else readFailure = exception }
                assertEquals(failed, HttpsDeviceReportUploader(endpoint, enabled) { connection }.upload(artifact()))
                assertTrue(connection.disconnected)
            }
        }
    }

    @Test fun `only explicit send uploads once and receipt returns with the original preview`() = runBlocking {
        val connection = Connection()
        var captures = 0
        var requests = 0
        val reporter = DefaultDeviceReporter(CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            capture = { captures++; reportData() }, sharer = DeviceReportSharer { _, _ -> null },
            uploader = HttpsDeviceReportUploader(endpoint, enabled) { requests++; connection })
        reporter.send(DeviceReportDelivery.UPLOAD)
        reporter.generate()
        val preview = (reporter.snapshot.value.state as DeviceReportState.Preview).report
        assertEquals(0, requests)
        reporter.send(DeviceReportDelivery.UPLOAD)
        val sent = withTimeout(5_000) { reporter.snapshot.first { it.state is DeviceReportState.Sent } }.state as DeviceReportState.Sent
        assertEquals(id, sent.reportId)
        assertSame(preview, sent.report)
        assertArrayEquals(preview.previewText.toByteArray(Charsets.UTF_8), connection.sent.toByteArray())
        assertEquals(1, requests)
        assertEquals(1, captures)
        reporter.send(DeviceReportDelivery.SHARE)
        assertNull((reporter.snapshot.value.state as DeviceReportState.Sent).reportId)
        reporter.discard()
        reporter.send(DeviceReportDelivery.UPLOAD)
        assertEquals(1, requests)
    }

    @Test fun `reporter also handles thrown uploader permission errors and preserves preview for retry`() = runBlocking {
        val reporter = DefaultDeviceReporter(CoroutineScope(coroutineContext + Dispatchers.Unconfined), { reportData() },
            DeviceReportSharer { _, _ -> null }, object : DeviceReportUploader {
                override suspend fun upload(report: DeviceReportArtifact): DeviceReportUploadResult = throw SecurityException("private")
            })
        reporter.generate()
        val preview = (reporter.snapshot.value.state as DeviceReportState.Preview).report
        reporter.send(DeviceReportDelivery.UPLOAD)
        assertEquals(DeviceReportState.Failed(DeviceReportFailure.UPLOAD_FAILED, preview), reporter.snapshot.value.state)
    }

    @Test fun `the service switch can only stop an upload, and is asked only when one would go out`() = runBlocking {
        var asked = 0
        fun switch(state: ServiceState?): suspend () -> ServiceState? = { asked++; state }
        val paused = DeviceReportUploadResult.Failed(DeviceReportFailure.UPLOAD_PAUSED)
        listOf(ServiceState.PAUSED, ServiceState.RETIRED).forEach { state ->
            assertEquals(state.name, paused, HttpsDeviceReportUploader(endpoint, switch(state)) { error("$state must not connect") }.upload(artifact()))
        }
        // An unreadable switch file sends nothing either, with its own message.
        assertEquals(DeviceReportUploadResult.Failed(DeviceReportFailure.UPLOAD_UNAVAILABLE),
            HttpsDeviceReportUploader(endpoint, switch(null)) { error("must not connect") }.upload(artifact()))
        assertEquals(3, asked)
        // No server, an invalid address or an oversized report never reads the switch.
        HttpsDeviceReportUploader("", switch(ServiceState.ENABLED)) { error("must not connect") }.upload(artifact())
        HttpsDeviceReportUploader("http://example.test/v1/usage", switch(ServiceState.ENABLED)) { error("must not connect") }.upload(artifact())
        val oversized = DeviceReportArtifact(ByteArray(HttpsDeviceReportUploader.MAX_REPORT_BYTES + 1) { 'a'.code.toByte() })
        HttpsDeviceReportUploader(endpoint, switch(ServiceState.ENABLED)) { error("must not connect") }.upload(oversized)
        assertEquals(3, asked)
        assertEquals(DeviceReportUploadResult.Uploaded(id), HttpsDeviceReportUploader(endpoint, switch(ServiceState.ENABLED)) { Connection() }.upload(artifact()))
    }

    private class Connection(private val status: Int = 201, private val body: String = receipt) : HttpURLConnection(URL(endpoint)) {
        val sent = ByteArrayOutputStream()
        val length get() = fixedContentLength
        var disconnected = false
        var writeFailure: Exception? = null
        var readFailure: Exception? = null
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream { writeFailure?.let { throw it }; return sent }
        override fun getInputStream(): InputStream { readFailure?.let { throw it }; return ByteArrayInputStream(body.toByteArray()) }
        override fun getResponseCode() = status
    }

    companion object {
        private const val endpoint = "https://example.test/v1/usage"
        private const val id = "59790e58-0d99-480e-9504-e7f38a111290"
        private const val receipt = "{\"reportId\":\"$id\"}"
        private val failed = DeviceReportUploadResult.Failed(DeviceReportFailure.UPLOAD_FAILED)
        private val enabled: suspend () -> ServiceState? = { ServiceState.ENABLED }
        private fun artifact() = DeviceReportArtifact("{ \"sample\": \"中文\", \"n\": 1.0 }\n".toByteArray(Charsets.UTF_8))
    }
}
