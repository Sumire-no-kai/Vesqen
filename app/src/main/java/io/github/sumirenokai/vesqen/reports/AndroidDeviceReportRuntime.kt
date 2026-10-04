package io.github.sumirenokai.vesqen.reports

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.database.SQLException
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.sumirenokai.vesqen.BuildConfig
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStateRepository
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.telemetry.PlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.TelemetryMediaItemExtras
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricSelection
import io.github.sumirenokai.vesqen.telemetry.TelemetryObservation
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@androidx.annotation.OptIn(UnstableApi::class)
internal class AndroidDeviceReportRuntime(
    private val context: Context,
    scope: CoroutineScope,
    outputState: UsbOutputStateRepository,
    telemetry: () -> PlaybackTelemetry,
) {
    private val errors = ReportErrorRecorder(
        scope, ErrorHistoryStore(File(context.noBackupFilesDir, "device-report-errors")),
        exits = { if (Build.VERSION.SDK_INT >= 30) historicalExits(context) else
            ExitHistoryAvailability.UNSUPPORTED_ANDROID_VERSION to emptyList() },
    )
    private val strictListener: (UsbOutputStatus) -> Unit = { status ->
        if (status.phase == UsbOutputPhase.FAILED) {
            // This callback may run on the audio thread. Use the failure's own immutable source
            // format, never read a possibly different current MediaItem later on the main thread.
            val format = status.sourceFormat?.let {
                FailedTrackFormat(
                    sampleRateHz = it.sampleRateHz, channelCount = it.channelCount,
                    bitDepth = Regex("^(8|16|24|32)-bit source$").matchEntire(it.encoding)?.groupValues?.get(1)?.toInt(),
                    source = ErrorFormatSource.STRICT_OUTPUT_REQUEST,
                )
            }
            errors.record(ReportErrorEvent(
                kind = ReportErrorKind.STRICT_OUTPUT,
                occurredAtEpochMs = status.observedAtEpochMs,
                occurredAtElapsedRealtimeMs = status.observedAtElapsedRealtimeMs,
                strictFailure = status.failure, strictOrigin = status.failureOrigin, format = format,
            ))
        }
    }

    init { outputState.addListener(strictListener) }

    val reporter: DeviceReporter = DefaultDeviceReporter(
        scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        capture = { options ->
            val evidence = if (options.audioCapabilities || options.chainEvidence) {
                withTimeoutOrNull(5_000) {
                    telemetry().observe(TelemetryObservation(selection = TelemetryMetricSelection.Explicit(
                        if (options.chainEvidence) TelemetryMetricCatalog.allIds else
                            TelemetryMetricCatalog.allIds.filterTo(linkedSetOf()) { it.value.startsWith("route.") || it.value.startsWith("usb.") },
                    ))).first()
                }
            } else null
            DeviceReportData(
                basic = DeviceReportBasic(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE,
                    Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.DISPLAY),
                generatedAtEpochMs = System.currentTimeMillis(), telemetry = evidence,
                history = if (options.recentErrors || options.failedTrackFormats) errors.snapshot() else ErrorHistorySnapshot(),
                telemetryUnavailableReason = TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
            )
        },
        sharer = AndroidDeviceReportSharing(context),
    )

    fun playbackListener(player: Player): Player.Listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            recordPlaybackError(error.errorCode, player.currentMediaItem)
        }
    }

    private fun recordPlaybackError(code: Int, item: MediaItem?) {
        val facts = TelemetryMediaItemExtras.read(item?.mediaMetadata?.extras)
        val event = ReportErrorEvent(
            ReportErrorKind.PLAYBACK, System.currentTimeMillis(), SystemClock.elapsedRealtime(), platformCode = code,
            format = if (item == null) null else FailedTrackFormat(facts.container, facts.codecMime, facts.sampleRateHz, facts.bitDepth, facts.channelCount, codecLabel = facts.codecLabel),
        )
        // The URI is used only for this error's DISPLAY_NAME lookup on IO; never persisted/exported.
        val uri = item?.localConfiguration?.uri
        errors.record(event, uri?.let { { readDisplayName(context, it) } })
    }
}

private fun readDisplayName(context: Context, uri: Uri): String? = try {
    when (uri.scheme) {
        "file" -> uri.lastPathSegment
        "content" -> context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString) else null
        }
        else -> null
    }
} catch (_: SecurityException) { null }
catch (_: IllegalArgumentException) { null }
catch (_: SQLException) { null }

@RequiresApi(30)
private fun historicalExits(context: Context): Pair<ExitHistoryAvailability, List<ReportErrorEvent>> = try {
    val manager = context.getSystemService(ActivityManager::class.java)
    val entries = manager.getHistoricalProcessExitReasons(context.packageName, 0, 20)
        .filter { it.reason in setOf(ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED) }
        .map { ReportErrorEvent(ReportErrorKind.PROCESS_EXIT, it.timestamp, platformCode = it.reason) }
    ExitHistoryAvailability.AVAILABLE to entries
} catch (_: RuntimeException) {
    // Platform/ROM service failures must not break startup; expose unavailability without raw text.
    ExitHistoryAvailability.PLATFORM_UNAVAILABLE to emptyList()
}
