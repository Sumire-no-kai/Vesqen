package io.github.sumirenokai.vesqen.usage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises Android JSON with an in-memory connection; never sends network traffic. */
class UsageTransportDeviceTest {
    @Test fun payloadIsBoundedAnonymousAndResponseUsesExistingChannelManifest() = runBlocking {
        val connection = Connection()
        val transport = HttpsUsageTransport("https://example.invalid/v1/usage") { connection }
        val ping = UsagePing(UsageFacts("1.0.0-beta.2", "github", "16", "Example", "Model", "Build", null), false, true, true, true)
        assertEquals("beta", JSONObject(transport.send(ping)!!).getString("channel"))
        val payload = JSONObject(connection.written.toString("UTF-8"))
        assertEquals(ping.fields().keys, payload.keys().asSequence().toSet())
        assertTrue(payload.isNull("bitPerfectMixer"))
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("Vesqen-Usage", connection.getRequestProperty("User-Agent"))
        assertEquals(5000, connection.readTimeout)
        assertTrue(connection.disconnected)
    }
    @Test fun aWorkerClosedByTheOwnerHandsTheUpdateCheckBackAndOtherRefusalsFail() = runBlocking {
        val ping = UsagePing(UsageFacts("1.0.0-beta.2", "github", "16", "Example", "Model", "Build", null), false, true, true, true)
        // Null is what the engine treats as "accepted without update data": the updater checks itself (#96).
        assertNull(HttpsUsageTransport("https://example.invalid/v1/usage") {
            Connection(503, error = """{"error":"SERVICE_PAUSED"}""")
        }.send(ping))
        listOf(503 to "<html>Error 1102</html>", 503 to """{"error":"SERVICE_UNAVAILABLE"}""",
            429 to """{"error":"RATE_LIMITED"}""").forEach { (status, body) ->
            try {
                HttpsUsageTransport("https://example.invalid/v1/usage") { Connection(status, error = body) }.send(ping)
                fail("$status $body must fail")
            } catch (_: IOException) { }
        }
    }

    private class Connection(private val status: Int = 200, private val error: String? = null) :
        HttpURLConnection(URL("https://example.invalid/v1/usage")) {
        val written = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream() = written
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream("""{"updateManifests":{"beta":{"channel":"beta"}}}""".toByteArray())
        override fun getErrorStream(): InputStream? = error?.let { ByteArrayInputStream(it.toByteArray()) }
    }
}
