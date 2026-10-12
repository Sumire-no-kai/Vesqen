package io.github.sumirenokai.vesqen.service

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * #96: a plain GET of the owner's switch file: no query, cookies or app details, an empty
 * User-Agent as for update checks, no redirects, 5 s timeouts and at most [MaxBytes].
 * Network failures and missing files return null (send nothing); a file that arrives but is not
 * understood reads as paused.
 */
internal class HttpsServiceSwitchSource(
    private val url: String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : ServiceSwitchSource {
    override suspend fun read(): ServiceStatus? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        try {
            val connection = open(URL(url))
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val body = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1_024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (body.size() + count > MaxBytes) return@withContext ServiceSwitchFile.Paused
                        body.write(buffer, 0, count)
                    }
                }
                ServiceSwitchFile.parse(body.toString(Charsets.UTF_8.name()))
            } finally { connection.disconnect() }
        } catch (_: IOException) { null }
        // OEM per-app firewalls surface as SecurityException from DNS, not IOException.
        catch (_: SecurityException) { null }
    }

    companion object {
        const val MaxBytes = 4 * 1_024
    }
}

/** `{ "schemaVersion": 1, "usagePings": "enabled", "reportUploads": "enabled" }`, read strictly. */
internal object ServiceSwitchFile {
    val Paused = ServiceStatus(ServiceState.PAUSED, ServiceState.PAUSED)

    fun parse(text: String): ServiceStatus = try {
        val root = JSONObject(text)
        val version = root.opt("schemaVersion")
        if ((version !is Int && version !is Long) || (version as Number).toLong() != 1L) Paused
        else ServiceStatus(state(root.opt("usagePings")), state(root.opt("reportUploads")))
    } catch (_: JSONException) { Paused }

    private fun state(value: Any?): ServiceState = when (value) {
        "enabled" -> ServiceState.ENABLED
        "retired" -> ServiceState.RETIRED
        else -> ServiceState.PAUSED
    }
}
