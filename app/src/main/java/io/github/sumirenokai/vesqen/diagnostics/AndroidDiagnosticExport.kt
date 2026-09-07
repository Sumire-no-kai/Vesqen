package io.github.sumirenokai.vesqen.diagnostics

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Thin Storage Access Framework adapter. It owns and closes only the stream it opens; the recorder
 * continues to retain the immutable stopped recording on every open, write, flush, or close error.
 */
suspend fun DiagnosticRecorder.exportTo(
    contentResolver: ContentResolver,
    destination: Uri,
): DiagnosticExportResult = withContext(Dispatchers.IO) {
    val recording = acquireRecordingForExport()
        ?: return@withContext DiagnosticExportResult.Failure(
            DiagnosticExportFailure.NO_STOPPED_RECORDING,
        )

    try {
        try {
            val output = contentResolver.openOutputStream(destination, "wt")
                ?: return@withContext DiagnosticExportResult.Failure(
                    DiagnosticExportFailure.DESTINATION_UNAVAILABLE,
                )
            output.use { stream ->
                writeDiagnosticRecording(
                    recording,
                    stream.cancellationChecked(currentCoroutineContext()[Job]),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DiagnosticExportResult.Failure(DiagnosticExportFailure.WRITE_FAILED)
        }
    } finally {
        releaseRecordingAfterExport()
    }
}
