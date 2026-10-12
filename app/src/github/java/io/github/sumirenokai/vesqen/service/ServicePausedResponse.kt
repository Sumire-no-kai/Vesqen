package io.github.sumirenokai.vesqen.service

import java.net.HttpURLConnection

private const val MaxPausedBytes = 256
private val ServicePaused = Regex("""[ \t\r\n]*\{[ \t\r\n]*"error"[ \t\r\n]*:[ \t\r\n]*"SERVICE_PAUSED"[ \t\r\n]*\}[ \t\r\n]*""")

/**
 * #96: an endpoint the owner closed on the Worker answers 503 with exactly
 * `{"error":"SERVICE_PAUSED"}`. Only that answer counts; any other 503, such as a Cloudflare error
 * page, stays a failure.
 */
internal fun HttpURLConnection.servicePaused(): Boolean {
    if (responseCode != HttpURLConnection.HTTP_UNAVAILABLE) return false
    val body = errorStream?.use { input ->
        val buffer = ByteArray(MaxPausedBytes + 1)
        var total = 0
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        if (total > MaxPausedBytes) return false
        String(buffer, 0, total, Charsets.UTF_8)
    } ?: return false
    return ServicePaused.matches(body)
}
