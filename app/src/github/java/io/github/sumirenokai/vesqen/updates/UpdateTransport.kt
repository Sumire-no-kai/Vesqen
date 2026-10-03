package io.github.sumirenokai.vesqen.updates

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface UpdateTransport {
    suspend fun manifest(url: String): String
    suspend fun download(url: String, target: File, progress: (Long, Long?) -> Unit)
}

/** HTTPS only; no identifiers, cookies or app/device headers. Redirects cannot downgrade to HTTP. */
internal class HttpsUpdateTransport(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : UpdateTransport {
    private fun connection(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            if (!isHttpsDownloadUrl(current)) throw UpdateOperationException(UpdateFailure.DOWNLOAD_FAILED)
            val connection = open(URL(current)).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                useCaches = false
                setRequestProperty("Accept", "application/octet-stream, application/json")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "")
            }
            try {
                val response = connection.responseCode
                if (response in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                    current = URL(URL(current), location).toString()
                    connection.disconnect()
                } else if (response == HttpURLConnection.HTTP_OK) {
                    return connection
                } else {
                    throw IOException("HTTP request failed")
                }
            } catch (failure: IOException) {
                connection.disconnect()
                throw failure
            }
        }
        throw IOException("Too many redirects")
    }

    override suspend fun manifest(url: String): String {
        val connection = connection(url)
        try {
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > UpdateManifest.MAX_BYTES) throw UpdateOperationException(UpdateFailure.INVALID_MANIFEST)
                    bytes.write(buffer, 0, count)
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        } finally { connection.disconnect() }
    }

    override suspend fun download(url: String, target: File, progress: (Long, Long?) -> Unit) {
        val connection = connection(url)
        try {
            val total = connection.getHeaderFieldLong("Content-Length", -1).takeIf { it > 0 }
            if (total != null && total > MAX_APK_BYTES) throw UpdateOperationException(UpdateFailure.DOWNLOAD_FAILED)
            if (total != null && target.parentFile!!.usableSpace < total) throw UpdateOperationException(UpdateFailure.INSUFFICIENT_STORAGE)
            val output = try { target.outputStream().buffered() }
                catch (failure: IOException) { throw UpdateOperationException(UpdateFailure.STORAGE_UNAVAILABLE, failure) }
            output.use { sink ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MAX_APK_BYTES || (total != null && received > total))
                            throw UpdateOperationException(UpdateFailure.DOWNLOAD_FAILED)
                        try { sink.write(buffer, 0, count) }
                        catch (failure: IOException) { throw storageFailure(target, failure) }
                        progress(received, total)
                    }
                    if (received == 0L || (total != null && received != total)) throw IOException("Incomplete download")
                }
                try { sink.flush() } catch (failure: IOException) { throw storageFailure(target, failure) }
            }
        } finally { connection.disconnect() }
    }

    private fun storageFailure(target: File, failure: IOException) = UpdateOperationException(
        if (target.parentFile!!.usableSpace < 64 * 1024) UpdateFailure.INSUFFICIENT_STORAGE else UpdateFailure.STORAGE_UNAVAILABLE,
        failure,
    )

    private companion object { const val MAX_APK_BYTES = 512 * 1024 * 1024L }
}
