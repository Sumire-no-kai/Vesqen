package io.github.sumirenokai.vesqen.reports

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Compile-only until the owner schedules device acceptance. Never launches a share target. */
class DeviceReportSharingDeviceTest {
    @Test fun providerReadsPreviewBytesAndRejectsPrivateJournalDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = DeviceReportData(DeviceReportBasic("test", 1, "deviceTest", "Example", "Model", "16", 36, "Build"), 100)
        val artifact = DeviceReportGenerator.generate(data, DeviceReportOptions())
        assertEquals(1, JSONObject(artifact.previewText).getInt("schemaVersion"))
        val file = ReportShareFiles(File(context.cacheDir, "device-reports")).prepare(artifact, System.currentTimeMillis())
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.device-reports", file)
            assertEquals("content", uri.scheme)
            assertFalse(uri.toString().contains(context.cacheDir.absolutePath))
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(artifact.copyBytes(), it.readBytes()) }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.device-reports", File(context.noBackupFilesDir, "device-report-errors/errors.bin"))
            }
        } finally { file.delete() }
    }

    @Test fun shareAndEmailGrantReadOnlyAccessToSameUriAndPrefillSupportAddress() {
        val uri = Uri.parse("content://example.device-reports/device_reports/report.json")
        for (email in listOf(false, true)) {
            val intent = deviceReportShareIntent(uri, email)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("application/json", intent.type)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            @Suppress("DEPRECATION")
            assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (email) {
                assertArrayEquals(arrayOf("vesqen@sumirenokai.com"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
                assertEquals(Intent.ACTION_SENDTO, intent.selector!!.action)
                assertEquals("mailto:vesqen@sumirenokai.com", intent.selector!!.data.toString())
            } else {
                assertNull(intent.selector)
                assertNull(intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
            }
        }
    }
}
