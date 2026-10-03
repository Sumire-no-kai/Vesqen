package io.github.sumirenokai.vesqen.updates

import kotlinx.coroutines.flow.StateFlow

/** UI-facing update contract. Commands never start a download or installation without an explicit call. */
interface AppUpdater {
    val snapshot: StateFlow<UpdateSnapshot>

    fun checkNow()
    fun skipVersion(versionCode: Long)
    fun downloadUpdate()
    fun installUpdate()
    fun setAutomaticChecksEnabled(enabled: Boolean)
}

enum class UpdateChannel { STABLE, BETA }
enum class UpdateLanguage { ENGLISH, SIMPLIFIED_CHINESE }
enum class UpdateInstallationSource { DIRECT, GOOGLE_PLAY, OTHER_UPDATER, UNKNOWN }

data class UpdateRelease(
    val versionName: String,
    val versionCode: Long,
    val minimumAndroidApi: Int,
    val apkUrls: List<String>,
    val sha256: String,
    /** Plain text only. The renderer must not interpret HTML or automatically activate arbitrary links. */
    val releaseNotes: Map<UpdateLanguage, String>,
)

enum class UpdateFailure {
    NETWORK_UNAVAILABLE,
    INVALID_MANIFEST,
    UNSUPPORTED_ANDROID_VERSION,
    DOWNLOAD_FAILED,
    INSUFFICIENT_STORAGE,
    STORAGE_UNAVAILABLE,
    HASH_MISMATCH,
    INVALID_APK,
    PACKAGE_MISMATCH,
    VERSION_MISMATCH,
    SIGNATURE_MISMATCH,
    INSTALL_CANCELLED,
    INSTALL_FAILED,
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: UpdateRelease, val skipped: Boolean = false) : UpdateState
    /** totalBytes may be unknown; callers must not invent a percentage in that case. */
    data class Downloading(val release: UpdateRelease, val downloadedBytes: Long, val totalBytes: Long?) : UpdateState
    data class Verifying(val release: UpdateRelease) : UpdateState
    /** Installation permission is a separate system confirmation, not a download failure. */
    data class ReadyToInstall(val release: UpdateRelease, val requiresInstallPermission: Boolean) : UpdateState
    data class Installing(val release: UpdateRelease) : UpdateState
    data class Failed(val reason: UpdateFailure, val release: UpdateRelease? = null) : UpdateState
    data class ManagedExternally(val source: UpdateInstallationSource, val installerPackageName: String?) : UpdateState
}

data class UpdateSnapshot(
    val state: UpdateState = UpdateState.Idle,
    val channel: UpdateChannel = UpdateChannel.BETA,
    val automaticChecksEnabled: Boolean = true,
    val skippedVersionCode: Long? = null,
    val lastSuccessfulCheckEpochMs: Long? = null,
    val installationSource: UpdateInstallationSource = UpdateInstallationSource.DIRECT,
    val installerPackageName: String? = null,
)
