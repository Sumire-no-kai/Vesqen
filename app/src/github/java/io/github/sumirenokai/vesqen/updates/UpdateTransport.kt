package io.github.sumirenokai.vesqen.updates

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.MalformedURLException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal interface UpdateTransport {
    suspend fun manifest(url: String): String
    suspend fun download(url: String, target: File, progress: (Long, Long?) -> Unit)
}

/**
 * HTTPS only; no identifiers, cookies or app/device headers. Redirects cannot downgrade to HTTP.
 * The version file, with its redirects, and a download's response headers each get [responseDeadlineMs];
 * a download's body then gets time for its size at [minimumBytesPerSecond] (#194 S4).
 */
internal class HttpsUpdateTransport(
    private val allocatableBytes: (File) -> Long,
    private val responseDeadlineMs: Long = 90_000,
    private val minimumBytesPerSecond: Long = 8 * 1024,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : UpdateTransport {
    private fun connection(url: String, invalidResponse: UpdateFailure, deadline: RequestDeadline): HttpURLConnection {
        var current = url
        repeat(6) {
            if (!isHttpsDownloadUrl(current)) throw UpdateOperationException(invalidResponse)
            val connection = open(URL(current)).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                useCaches = false
                setRequestProperty("Accept", "application/octet-stream, application/json")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "")
            }
            deadline.connection = connection
            var handedOff = false
            try {
                deadline.check()
                val response = connection.responseCode
                if (response in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw UpdateOperationException(invalidResponse)
                    current = try { URL(URL(current), location).toString() }
                        catch (failure: MalformedURLException) { throw UpdateOperationException(invalidResponse, failure) }
                } else if (response == HttpURLConnection.HTTP_OK) {
                    handedOff = true
                    return connection
                } else {
                    throw UpdateOperationException(invalidResponse)
                }
            } finally {
                if (!handedOff) connection.disconnect()
            }
        }
        throw UpdateOperationException(invalidResponse)
    }

    override suspend fun manifest(url: String): String = withDeadline(responseDeadlineMs) { deadline ->
        val connection = connection(url, UpdateFailure.INVALID_MANIFEST, deadline)
        try {
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    deadline.check()
                    if (count < 0) break
                    if (bytes.size() + count > UpdateManifest.MAX_BYTES) throw UpdateOperationException(UpdateFailure.INVALID_MANIFEST)
                    bytes.write(buffer, 0, count)
                }
            }
            bytes.toString(Charsets.UTF_8.name())
        } finally { connection.disconnect() }
    }

    override suspend fun download(url: String, target: File, progress: (Long, Long?) -> Unit): Unit = withDeadline(responseDeadlineMs) { deadline ->
        val connection = connection(url, UpdateFailure.DOWNLOAD_FAILED, deadline)
        try {
            val total = connection.getHeaderFieldLong("Content-Length", -1).takeIf { it > 0 }
            if (total != null && total > MAX_APK_BYTES) throw UpdateOperationException(UpdateFailure.DOWNLOAD_FAILED)
            if (total != null && availableBytes(target.parentFile!!) < total) throw UpdateOperationException(UpdateFailure.INSUFFICIENT_STORAGE)
            deadline.restart(responseDeadlineMs + (total ?: UNKNOWN_LENGTH_BYTES) * 1000 / minimumBytesPerSecond)
            val output = try { target.outputStream().buffered() }
                catch (failure: IOException) { throw storageFailure(target, failure) }
            output.use { sink ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        deadline.check()
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

    private fun availableBytes(directory: File): Long = try { allocatableBytes(directory) }
        catch (failure: IOException) { throw UpdateOperationException(UpdateFailure.STORAGE_UNAVAILABLE, failure) }

    private fun storageFailure(target: File, failure: IOException): UpdateOperationException {
        val reason = try {
            if (allocatableBytes(target.parentFile!!) < 64 * 1024) UpdateFailure.INSUFFICIENT_STORAGE
            else UpdateFailure.STORAGE_UNAVAILABLE
        } catch (queryFailure: IOException) {
            // A failed space query must not replace the original write failure.
            failure.addSuppressed(queryFailure)
            UpdateFailure.STORAGE_UNAVAILABLE
        }
        return UpdateOperationException(reason, failure)
    }

    /** Inline, so the request's own exception reaches the caller as it was thrown. */
    private inline fun <T> withDeadline(timeoutMs: Long, block: (RequestDeadline) -> T): T {
        val deadline = RequestDeadline().apply { restart(timeoutMs) }
        try { return block(deadline) } finally { deadline.cancel() }
    }

    private companion object {
        const val MAX_APK_BYTES = 512 * 1024 * 1024L
        /** A download without a Content-Length gets the time of one this large. */
        const val UNKNOWN_LENGTH_BYTES = 64 * 1024 * 1024L
    }
}

/**
 * Ends a request at its deadline by disconnecting its connection, which ends a blocked read with an
 * IOException. The read timeout restarts with every byte, so a server that sends a few now and then
 * would otherwise hold the updater, which runs one operation at a time, for the life of the process,
 * and a download would never move on to the next URL (#194 S4).
 */
private class RequestDeadline {
    /** Its own scope: the request blocks its thread, so the watchdog runs elsewhere. */
    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile
    var connection: HttpURLConnection? = null

    @Volatile
    private var expired = false
    private var watchdog: Job? = null

    /** From the request only. */
    fun restart(timeoutMs: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(timeoutMs)
            expired = true
            connection?.disconnect()
        }
    }

    /** A read can still return after the disconnect, or the deadline passed before the connection was set. */
    fun check() {
        if (expired) throw IOException("Update request passed its deadline")
    }

    fun cancel() {
        scope.cancel()
    }
}
