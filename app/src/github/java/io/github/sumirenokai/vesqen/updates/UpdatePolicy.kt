package io.github.sumirenokai.vesqen.updates

import java.net.URI

internal const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

internal data class UpdatePreferences(
    val automatic: Boolean,
    val skippedVersion: Long? = null,
    val lastSuccess: Long? = null,
    val lastAutomaticAttempt: Long? = null,
)

internal interface UpdatePreferencesStore {
    fun read(): UpdatePreferences
    fun write(value: UpdatePreferences)
}

internal fun automaticCheckDue(now: Long, preferences: UpdatePreferences): Boolean =
    preferences.automatic && listOfNotNull(preferences.lastSuccess, preferences.lastAutomaticAttempt)
        .all { now > it && now - it > UPDATE_CHECK_INTERVAL_MS }

internal fun defaultUpdateChannel(versionName: String): UpdateChannel =
    if ('-' in versionName) UpdateChannel.BETA else UpdateChannel.STABLE

internal fun isHttpsDownloadUrl(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null
} catch (_: java.net.URISyntaxException) { false }

/** The UI renders notes as plain text and consults this before opening a user-selected link. */
fun isAllowedUpdateNotesLink(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme == "https" && uri.rawUserInfo == null &&
        uri.host?.lowercase() in setOf("vesqen.sumirenokai.com", "github.com")
} catch (_: java.net.URISyntaxException) { false }

internal data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

internal fun validateApkIdentity(installed: ApkIdentity, archive: ApkIdentity, release: UpdateRelease) {
    when {
        archive.packageName != installed.packageName -> throw UpdateOperationException(UpdateFailure.PACKAGE_MISMATCH)
        archive.versionCode != release.versionCode || archive.versionCode <= installed.versionCode ->
            throw UpdateOperationException(UpdateFailure.VERSION_MISMATCH)
        installed.signers.isEmpty() || archive.signers != installed.signers ->
            throw UpdateOperationException(UpdateFailure.SIGNATURE_MISMATCH)
    }
}

internal class UpdateOperationException(val reason: UpdateFailure, cause: Throwable? = null) :
    Exception(reason.name, cause)
