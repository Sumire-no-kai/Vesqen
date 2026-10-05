package io.github.sumirenokai.vesqen.reports

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceReportFileProvider : FileProvider()

internal class AndroidDeviceReportSharing(private val context: Context) : DeviceReportSharer {
    private val files = ReportShareFiles(File(context.cacheDir, "device-reports"))

    override suspend fun share(report: DeviceReportArtifact, email: Boolean): DeviceReportFailure? {
        val uri = try {
            val file = withContext(Dispatchers.IO) { files.prepare(report, System.currentTimeMillis()) }
            FileProvider.getUriForFile(context, "${context.packageName}.device-reports", file)
        } catch (_: IllegalArgumentException) { return DeviceReportFailure.SHARE_FAILED }
        catch (_: SecurityException) { return DeviceReportFailure.SHARE_FAILED }
        val intent = deviceReportShareIntent(uri, email)
        // The chooser itself always resolves, so check that something can take the report.
        if (intent.resolveActivity(context.packageManager) == null) return DeviceReportFailure.NO_SHARE_APPLICATION
        return withContext(Dispatchers.Main) {
            try {
                context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            } catch (_: ActivityNotFoundException) { DeviceReportFailure.NO_SHARE_APPLICATION }
            catch (_: SecurityException) { DeviceReportFailure.SHARE_FAILED }
        }
    }

}

/** The chooser receives only a scoped content URI and the already previewed artifact. */
internal fun deviceReportShareIntent(uri: Uri, email: Boolean): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "application/json"
    putExtra(Intent.EXTRA_STREAM, uri)
    clipData = ClipData.newRawUri("device-report", uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (email) {
        putExtra(Intent.EXTRA_EMAIL, arrayOf("vesqen@sumirenokai.com"))
        selector = Intent(Intent.ACTION_SENDTO, "mailto:vesqen@sumirenokai.com".toUri())
    }
}
