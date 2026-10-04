package io.github.sumirenokai.vesqen.usage

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

internal class HttpsUsageTransport(
    private val endpoint: String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : UsageTransport {
    override suspend fun send(ping: UsagePing): String? {
        val url = URL(endpoint)
        if (url.protocol != "https" || url.userInfo != null || url.ref != null || url.query != null) throw IOException("Invalid usage endpoint")
        val bytes = JSONObject(ping.fields()).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 4096) throw IOException("Usage payload exceeds limit")
        val connection = open(url)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Vesqen-Usage")
            connection.outputStream.use { it.write(bytes) }
            if (connection.responseCode == 204) return null
            if (connection.responseCode != 200) throw IOException("Usage request rejected")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 131072) throw IOException("Usage response exceeds limit")
                    output.write(buffer, 0, count)
                }
            }
            return try {
                val response = JSONObject(output.toString(Charsets.UTF_8.name()))
                val channel = if (ping.facts.appVersion.contains('-')) "beta" else "stable"
                response.optJSONObject("updateManifests")?.optJSONObject(channel)?.toString()
            } catch (invalid: JSONException) { throw IOException("Invalid usage response", invalid) }
        } finally { connection.disconnect() }
    }
}
