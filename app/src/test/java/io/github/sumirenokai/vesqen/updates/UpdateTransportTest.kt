package io.github.sumirenokai.vesqen.updates

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.UnknownHostException
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
        val transport = HttpsUpdateTransport(allocatableBytes = { Long.MAX_VALUE }) { response }
        try { transport.manifest("https://example.org/"); fail("HTTP downgrade accepted") }
        catch (failure: UpdateOperationException) { assertEquals(UpdateFailure.INVALID_MANIFEST, failure.reason) }
        assertTrue(response.closed)
        assertEquals("", response.getRequestProperty("User-Agent"))
        assertFalse(response.instanceFollowRedirects)
    }

    @Test fun sizeLimitAndTruncatedDownloadAreRejected() = runBlocking {
        val large = Response(URL("https://example.org/"), body = ByteArray(UpdateManifest.MAX_BYTES + 1))
        try { HttpsUpdateTransport(allocatableBytes = { Long.MAX_VALUE }) { large }.manifest("https://example.org/"); fail("Unbounded manifest") }
        catch (failure: UpdateOperationException) { assertEquals(UpdateFailure.INVALID_MANIFEST, failure.reason) }
        assertTrue(large.closed)
        val short = Response(URL("https://example.org/"), body = byteArrayOf(1), length = 2)
        val file = Files.createTempFile("update-transport", ".apk").toFile()
        try {
            try { HttpsUpdateTransport(allocatableBytes = { Long.MAX_VALUE }) { short }.download("https://example.org/", file) { _, _ -> }; fail("Truncated APK") }
            catch (_: IOException) { assertTrue(short.closed) }
        } finally { file.delete() }
    }
    @Test fun httpErrorsAreInvalidManifestsOrFailedDownloadsRatherThanConnectionFailures() = runBlocking {
        val file = Files.createTempFile("update-http", ".apk").toFile()
        try {
            for (code in listOf(204, 403, 404, 500, 503)) {
                val manifest = Response(URL("https://example.org/"), code)
                val transport = HttpsUpdateTransport(allocatableBytes = { Long.MAX_VALUE }) { manifest }
                try { transport.manifest("https://example.org/"); fail("HTTP $code accepted") }
                catch (failure: UpdateOperationException) { assertEquals(UpdateFailure.INVALID_MANIFEST, failure.reason) }
                assertTrue(manifest.closed)
                val download = Response(URL("https://example.org/"), code)
                try {
                    HttpsUpdateTransport(allocatableBytes = { Long.MAX_VALUE }) { download }
                        .download("https://example.org/", file) { _, _ -> }
                    fail("HTTP $code accepted as APK")
                } catch (failure: UpdateOperationException) { assertEquals(UpdateFailure.DOWNLOAD_FAILED, failure.reason) }
                assertTrue(download.closed)
            }
        } finally { file.delete() }
    }

    @Test fun connectionFailureRemainsAnIoFailure() = runBlocking {
        val cause = UnknownHostException("unreachable")
        val transport = HttpsUpdateTransport(allocatableBytes = { Long.MAX_VALUE }) { throw cause }
        try { transport.manifest("https://example.org/"); fail("Connection failure accepted") }
        catch (failure: IOException) { assertSame(cause, failure) }
    }

    @Test fun missingMalformedAndExhaustedRedirectsAreInvalidManifests() = runBlocking {
        for (location in listOf(null, "https://[invalid", "/loop")) {
            val responses = mutableListOf<Response>()
            val transport = HttpsUpdateTransport(allocatableBytes = { Long.MAX_VALUE }) {
                Response(it, 302, location = location).also(responses::add)
            }
            try { transport.manifest("https://example.org/"); fail("Invalid redirect accepted") }
            catch (failure: UpdateOperationException) { assertEquals(UpdateFailure.INVALID_MANIFEST, failure.reason) }
            assertTrue(responses.all { it.closed })
            if (location == "/loop") assertEquals(6, responses.size)
        }
    }

    @Test fun insufficientAllocatableSpaceStopsBeforeWritingAndQueryErrorsRemainStorageFailures() = runBlocking {
        val directory = Files.createTempDirectory("update-space").toFile()
        val file = java.io.File(directory, "update.apk")
        try {
            val response = Response(URL("https://example.org/"), body = byteArrayOf(1, 2))
            val transport = HttpsUpdateTransport(allocatableBytes = { path ->
                assertEquals(directory, path)
                1L
            }) { response }
            try { transport.download("https://example.org/", file) { _, _ -> }; fail("Insufficient space accepted") }
            catch (failure: UpdateOperationException) { assertEquals(UpdateFailure.INSUFFICIENT_STORAGE, failure.reason) }
            assertFalse(file.exists())
            assertTrue(response.closed)
            val queryFailure = IOException("unavailable volume")
            val unavailable = HttpsUpdateTransport(allocatableBytes = { throw queryFailure }) { response }
            try { unavailable.download("https://example.org/", file) { _, _ -> }; fail("Unavailable storage accepted") }
            catch (failure: UpdateOperationException) {
                assertEquals(UpdateFailure.STORAGE_UNAVAILABLE, failure.reason)
                assertSame(queryFailure, failure.cause)
            }
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun writeFailureRetainsOriginalCauseWhenSpaceQueryAlsoFails() = runBlocking {
        val directory = Files.createTempDirectory("update-write-failure").toFile()
        val queryFailure = IOException("space query failed")
        val response = Response(URL("https://example.org/"), length = -1)
        try {
            val transport = HttpsUpdateTransport(allocatableBytes = { throw queryFailure }) { response }
            // A directory cannot be opened as the APK output file.
            try { transport.download("https://example.org/", directory) { _, _ -> }; fail("Write failure accepted") }
            catch (failure: UpdateOperationException) {
                assertEquals(UpdateFailure.STORAGE_UNAVAILABLE, failure.reason)
                assertTrue(failure.cause is java.io.FileNotFoundException)
                assertSame(queryFailure, failure.cause!!.suppressed.single())
            }
            assertTrue(response.closed)
        } finally { directory.deleteRecursively() }
    }

}
