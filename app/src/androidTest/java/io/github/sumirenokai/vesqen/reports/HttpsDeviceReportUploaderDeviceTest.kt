package io.github.sumirenokai.vesqen.reports

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The receipt pattern must compile and match on Android's ICU regex engine, which is stricter than
 * the desktop JVM the unit tests run on. An invalid pattern crashed the app at startup (#93).
 */
class HttpsDeviceReportUploaderDeviceTest {
    @Test fun receiptParsesOnAndroidAndOtherShapesFail() = runBlocking {
        val id = "59790e58-0d99-480e-9504-e7f38a111290"
        listOf("{\"reportId\":\"$id\"}", " \n{ \"reportId\" : \"$id\" }\r\n").forEach { body ->
            assertEquals(body, DeviceReportUploadResult.Uploaded(id), upload(body))
        }
        listOf("{}", "{\"reportId\":\"$id\"} trailing", "{\"reportId\":\"$id\",\"reportId\":\"$id\"}").forEach { body ->
            assertEquals(body, DeviceReportUploadResult.Failed(DeviceReportFailure.UPLOAD_FAILED), upload(body))
        }
    }

    private suspend fun upload(body: String): DeviceReportUploadResult =
        HttpsDeviceReportUploader("https://example.test/v1/usage") { Receipt(it, body) }
            .upload(DeviceReportArtifact("{\"n\":1}".toByteArray()))

    private class Receipt(url: URL, private val body: String) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getResponseCode() = HTTP_CREATED
    }
}
