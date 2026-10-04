package io.github.sumirenokai.vesqen.reports

import java.security.MessageDigest
import kotlinx.coroutines.flow.StateFlow

/** Basic information is mandatory; optional groups and filenames require an explicit selection. */
data class DeviceReportOptions(
    val audioCapabilities: Boolean = false,
    val chainEvidence: Boolean = false,
    val recentErrors: Boolean = false,
    val failedTrackFormats: Boolean = false,
    val includeFileNames: Boolean = false,
)

/** The only report payload: preview, sharing and upload all consume these exact UTF-8 bytes. */
class DeviceReportArtifact internal constructor(bytes: ByteArray) {
    private val content = bytes.copyOf()
    val previewText: String = content.decodeToString(throwOnInvalidSequence = true)
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(content)
        .joinToString("") { "%02x".format(it) }
    fun copyBytes(): ByteArray = content.copyOf()
}

enum class DeviceReportFailure {
    GENERATION_FAILED, SHARE_FAILED, NO_SHARE_APPLICATION, UPLOAD_NOT_CONFIGURED, UPLOAD_FAILED,
}
enum class DeviceReportDelivery { SHARE, EMAIL, UPLOAD }
sealed interface DeviceReportState {
    data object Editing : DeviceReportState
    data object Generating : DeviceReportState
    data class Preview(val report: DeviceReportArtifact) : DeviceReportState
    data class Sending(val report: DeviceReportArtifact, val delivery: DeviceReportDelivery) : DeviceReportState
    /** The share sheet opened or the upload was accepted; the same report can be sent again. */
    data class Sent(
        val report: DeviceReportArtifact,
        val delivery: DeviceReportDelivery,
        val reportId: String? = null,
    ) : DeviceReportState
    data class Failed(val reason: DeviceReportFailure, val report: DeviceReportArtifact? = null) : DeviceReportState
}
data class DeviceReportSnapshot(
    val options: DeviceReportOptions = DeviceReportOptions(),
    val state: DeviceReportState = DeviceReportState.Editing,
)

interface DeviceReporter {
    val snapshot: StateFlow<DeviceReportSnapshot>
    fun setOptions(options: DeviceReportOptions)
    fun generate()
    /** Explicit user action after preview. Never regenerates data or implicitly uploads. */
    fun send(delivery: DeviceReportDelivery)
    fun discard()
}

sealed interface DeviceReportUploadResult {
    data class Uploaded(val reportId: String) : DeviceReportUploadResult
    data class Failed(val reason: DeviceReportFailure) : DeviceReportUploadResult
}

interface DeviceReportUploader {
    suspend fun upload(report: DeviceReportArtifact): DeviceReportUploadResult
}

internal object UnconfiguredDeviceReportUploader : DeviceReportUploader {
    override suspend fun upload(report: DeviceReportArtifact) =
        DeviceReportUploadResult.Failed(DeviceReportFailure.UPLOAD_NOT_CONFIGURED)
}

internal fun interface DeviceReportSharer {
    suspend fun share(report: DeviceReportArtifact, email: Boolean): DeviceReportFailure?
}
