package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputFailureOrigin
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason

internal enum class ReportErrorKind { PLAYBACK, STRICT_OUTPUT, PROCESS_EXIT }
internal enum class ErrorFormatSource { LIBRARY_METADATA, STRICT_OUTPUT_REQUEST }

internal data class FailedTrackFormat(
    val container: String? = null,
    val codecMime: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val source: ErrorFormatSource = ErrorFormatSource.LIBRARY_METADATA,
    val privacyFiltered: Boolean = false,
    val codecLabel: String? = null,
)

/** No exception message, URI, track ID, title, album, artist, endpoint name or process name field. */
internal data class ReportErrorEvent(
    val kind: ReportErrorKind,
    val occurredAtEpochMs: Long,
    val occurredAtElapsedRealtimeMs: Long? = null,
    val platformCode: Int? = null,
    val strictFailure: UsbOutputFailure? = null,
    val strictOrigin: UsbOutputFailureOrigin? = null,
    val format: FailedTrackFormat? = null,
    val fileName: String? = null,
) {
    init {
        require(occurredAtEpochMs >= 0 && (occurredAtElapsedRealtimeMs == null || occurredAtElapsedRealtimeMs >= 0))
        require(if (kind == ReportErrorKind.STRICT_OUTPUT) strictFailure != null && platformCode == null
            else platformCode != null && strictFailure == null && strictOrigin == null)
        require(kind != ReportErrorKind.PROCESS_EXIT || (format == null && fileName == null))
    }
}

internal enum class ErrorHistoryAvailability { AVAILABLE, STORAGE_UNAVAILABLE, QUEUE_OVERFLOW }
internal enum class ExitHistoryAvailability { AVAILABLE, UNSUPPORTED_ANDROID_VERSION, PLATFORM_UNAVAILABLE }

internal data class ErrorHistorySnapshot(
    val events: List<ReportErrorEvent> = emptyList(),
    val availability: ErrorHistoryAvailability = ErrorHistoryAvailability.AVAILABLE,
    val exitHistory: ExitHistoryAvailability = ExitHistoryAvailability.AVAILABLE,
)

internal data class DeviceReportBasic(
    val appVersion: String,
    val versionCode: Int,
    val buildType: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val androidApi: Int,
    val romBuild: String,
)

internal data class DeviceReportData(
    val basic: DeviceReportBasic,
    val generatedAtEpochMs: Long,
    val telemetry: TelemetrySnapshot? = null,
    val history: ErrorHistorySnapshot = ErrorHistorySnapshot(),
    val telemetryUnavailableReason: TelemetryUnavailableReason = TelemetryUnavailableReason.NOT_SAMPLED,
)
