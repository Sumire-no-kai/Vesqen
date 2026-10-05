package io.github.sumirenokai.vesqen.reports

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Configured from the usage service address, but never gated on the usage-statistics switch. */
internal class HttpsDeviceReportUploader(
    private val usageEndpoint: String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : DeviceReportUploader {
    override suspend fun upload(report: DeviceReportArtifact): DeviceReportUploadResult = withContext(Dispatchers.IO) {
        if (usageEndpoint.isEmpty()) return@withContext DeviceReportUploadResult.Failed(DeviceReportFailure.UPLOAD_NOT_CONFIGURED)
        try {
            val url = reportEndpoint(usageEndpoint) ?: return@withContext failed()
            val bytes = report.copyBytes()
            if (bytes.size > MAX_REPORT_BYTES) return@withContext failed()
            ensureActive()
            val connection = open(url)
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Vesqen-Device-Report")
                ensureActive()
                connection.outputStream.use { it.write(bytes) }
                if (connection.responseCode != HttpURLConnection.HTTP_CREATED) return@withContext failed()
                val response = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1_024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (response.size() + count > MAX_RECEIPT_BYTES) return@withContext failed()
                        response.write(buffer, 0, count)
                    }
                }
                val receipt = RECEIPT.matchEntire(response.toString(Charsets.UTF_8.name()))
                    ?.groupValues?.get(1) ?: return@withContext failed()
                DeviceReportUploadResult.Uploaded(receipt)
            } finally { connection.disconnect() }
        } catch (_: IOException) { failed() }
        catch (_: SecurityException) { failed() }
    }

    private fun failed() = DeviceReportUploadResult.Failed(DeviceReportFailure.UPLOAD_FAILED)

    companion object {
        internal const val MAX_REPORT_BYTES = 256 * 1_024
        private const val MAX_RECEIPT_BYTES = 4 * 1_024
        // The Worker returns exactly {reportId: crypto.randomUUID()}. Accept only that JSON shape,
        // avoiding arbitrary server text in the UI, duplicate fields and an extra JSON dependency.
        private val RECEIPT = Regex("""[ \t\r\n]*\{[ \t\r\n]*"reportId"[ \t\r\n]*:[ \t\r\n]*"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"[ \t\r\n]*}[ \t\r\n]*""")

        internal fun reportEndpoint(configured: String): URL? = try {
            val uri = URI(configured)
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.rawPath != "/v1/usage" ||
                uri.port !in -1..65_535 || uri.port == 0) null
            else URI("https", null, uri.host, uri.port, "/v1/reports", null, null).toURL()
        } catch (_: URISyntaxException) { null }
        catch (_: IOException) { null }
    }
}
