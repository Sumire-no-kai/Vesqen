package io.github.sumirenokai.vesqen.ui.screens

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.updates.AppUpdater
import io.github.sumirenokai.vesqen.updates.UpdateFailure
import io.github.sumirenokai.vesqen.updates.UpdateInstallationSource
import io.github.sumirenokai.vesqen.updates.UpdateLanguage
import io.github.sumirenokai.vesqen.updates.UpdateRelease
import io.github.sumirenokai.vesqen.updates.UpdateSnapshot
import io.github.sumirenokai.vesqen.updates.UpdateState

/**
 * #68 in Settings. Every download and install is an explicit tap; the runtime opens the system
 * install permission itself, and a second Install continues after the user returns.
 */
@Composable
internal fun SettingsUpdatesGroup(appUpdater: AppUpdater) {
    val snapshot by appUpdater.snapshot.collectAsStateWithLifecycle()
    val state = snapshot.state
    val managedByPlay = snapshot.installationSource == UpdateInstallationSource.GOOGLE_PLAY
    SettingsGroup(
        title = stringResource(R.string.settings_updates),
        modifier = Modifier.testTag("vesqen.settings.section.updates"),
        footer = { updateRelease(state)?.let { release -> UpdateReleaseCard(state, release, appUpdater) } },
    ) {
        if (!managedByPlay) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_auto_update),
                description = stringResource(R.string.settings_auto_update_body),
                checked = snapshot.automaticChecksEnabled,
                onCheckedChange = appUpdater::setAutomaticChecksEnabled,
                modifier = Modifier.testTag("vesqen.settings.updates.automatic"),
            )
            SettingsDivider()
        }
        SettingsRow(
            title = stringResource(R.string.settings_check_update),
            description = updateStatusText(snapshot),
            onClick = appUpdater::checkNow.takeUnless { managedByPlay || state.isUpdateBusy() },
            showChevron = false,
            modifier = Modifier.testTag("vesqen.settings.updates.check"),
        )
    }
}

@Composable
private fun updateStatusText(snapshot: UpdateSnapshot): String = when (val state = snapshot.state) {
    UpdateState.Checking -> stringResource(R.string.update_status_checking)
    UpdateState.UpToDate -> stringResource(R.string.update_status_up_to_date)
    is UpdateState.Failed -> if (state.release == null) stringResource(updateFailureLabel(state.reason)) else lastCheckedText(snapshot)
    else -> when (snapshot.installationSource) {
        UpdateInstallationSource.GOOGLE_PLAY, UpdateInstallationSource.OTHER_UPDATER ->
            updaterName(snapshot.installerPackageName)?.let { stringResource(R.string.update_managed, it) }
                ?: stringResource(R.string.update_managed_unknown)
        UpdateInstallationSource.DIRECT, UpdateInstallationSource.UNKNOWN -> lastCheckedText(snapshot)
    }
}

@Composable
private fun lastCheckedText(snapshot: UpdateSnapshot): String {
    val context = LocalContext.current
    val checkedAt = snapshot.lastSuccessfulCheckEpochMs ?: return stringResource(R.string.update_status_never)
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    return stringResource(R.string.update_status_last, DateUtils.formatDateTime(context, checkedAt, flags))
}

@Composable
private fun UpdateReleaseCard(state: UpdateState, release: UpdateRelease, appUpdater: AppUpdater) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .padding(top = VesqenSpacing.sm)
            .fillMaxWidth()
            .testTag("vesqen.settings.updates.release"),
        shape = RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalVesqenColors.current.hairline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm)) {
            Text(
                text = stringResource(R.string.update_new_version),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = release.versionName,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag("vesqen.settings.updates.version"),
            )
            ReleaseNotes(release)
            when (state) {
                is UpdateState.Available -> {
                    if (state.skipped) UpdateNote(stringResource(R.string.update_skipped))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UpdateButton(R.string.update_download, "download", appUpdater::downloadUpdate)
                        if (!state.skipped) {
                            TextButton(
                                onClick = { appUpdater.skipVersion(release.versionCode) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("vesqen.settings.updates.skip"),
                            ) { Text(stringResource(R.string.update_skip)) }
                        }
                    }
                }
                is UpdateState.Downloading -> {
                    val total = state.totalBytes?.takeIf { it > 0 }
                    // An unknown size shows an indeterminate bar; never an invented percentage.
                    if (total != null) {
                        LinearProgressIndicator(
                            progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().testTag("vesqen.settings.updates.progress"),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("vesqen.settings.updates.progress"))
                    }
                    val downloaded = Formatter.formatShortFileSize(context, state.downloadedBytes)
                    UpdateNote(
                        if (total == null) stringResource(R.string.update_downloading_unknown, downloaded)
                        else stringResource(R.string.update_downloading, downloaded, Formatter.formatShortFileSize(context, total)),
                    )
                }
                is UpdateState.Verifying -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("vesqen.settings.updates.progress"))
                    UpdateNote(stringResource(R.string.update_verifying))
                }
                is UpdateState.ReadyToInstall -> {
                    if (state.requiresInstallPermission) UpdateNote(stringResource(R.string.update_permission_needed))
                    UpdateButton(R.string.update_install, "install", appUpdater::installUpdate)
                }
                is UpdateState.Installing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("vesqen.settings.updates.progress"))
                    UpdateNote(stringResource(R.string.update_installing))
                }
                is UpdateState.Failed -> {
                    val unsupported = state.reason == UpdateFailure.UNSUPPORTED_ANDROID_VERSION
                    Text(
                        text = if (unsupported) stringResource(R.string.update_requires_android, release.minimumAndroidApi)
                            else stringResource(updateFailureLabel(state.reason)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("vesqen.settings.updates.failure"),
                    )
                    // A failed install deleted the package, so trying again downloads it again.
                    if (!unsupported) UpdateButton(R.string.update_retry, "retry", appUpdater::downloadUpdate)
                }
                UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate, is UpdateState.ManagedExternally -> Unit
            }
        }
    }
}

@Composable
private fun ReleaseNotes(release: UpdateRelease) {
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val notes = release.releaseNotes[if (chinese) UpdateLanguage.SIMPLIFIED_CHINESE else UpdateLanguage.ENGLISH]
        ?: release.releaseNotes.values.firstOrNull()
        ?: return
    var expanded by rememberSaveable(release.versionCode) { mutableStateOf(false) }
    var overflows by remember(release.versionCode) { mutableStateOf(false) }
    // Plain text only: notes never render HTML or turn into tappable links.
    Text(
        text = notes,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 6,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        modifier = Modifier.testTag("vesqen.settings.updates.notes"),
    )
    if (overflows || expanded) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag("vesqen.settings.updates.notes-toggle"),
        ) { Text(stringResource(if (expanded) R.string.update_notes_less else R.string.update_notes_more)) }
    }
}

@Composable
private fun UpdateButton(@StringRes label: Int, tag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("vesqen.settings.updates.$tag"),
    ) { Text(stringResource(label)) }
}

@Composable
private fun UpdateNote(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun UpdateState.isUpdateBusy(): Boolean =
    this is UpdateState.Checking || this is UpdateState.Downloading ||
        this is UpdateState.Verifying || this is UpdateState.Installing

/** The release a card describes, from the first offer until installation finishes or fails. */
internal fun updateRelease(state: UpdateState): UpdateRelease? = when (state) {
    is UpdateState.Available -> state.release
    is UpdateState.Downloading -> state.release
    is UpdateState.Verifying -> state.release
    is UpdateState.ReadyToInstall -> state.release
    is UpdateState.Installing -> state.release
    is UpdateState.Failed -> state.release
    UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate, is UpdateState.ManagedExternally -> null
}

@StringRes
internal fun updateFailureLabel(failure: UpdateFailure): Int = when (failure) {
    UpdateFailure.NETWORK_UNAVAILABLE -> R.string.update_failure_network
    UpdateFailure.INVALID_MANIFEST -> R.string.update_failure_manifest
    UpdateFailure.UNSUPPORTED_ANDROID_VERSION -> R.string.update_failure_android
    UpdateFailure.DOWNLOAD_FAILED -> R.string.update_failure_download
    UpdateFailure.INSUFFICIENT_STORAGE -> R.string.update_failure_space
    UpdateFailure.STORAGE_UNAVAILABLE -> R.string.update_failure_storage
    UpdateFailure.HASH_MISMATCH -> R.string.update_failure_hash
    UpdateFailure.INVALID_APK -> R.string.update_failure_apk
    UpdateFailure.PACKAGE_MISMATCH -> R.string.update_failure_package
    UpdateFailure.VERSION_MISMATCH -> R.string.update_failure_version
    UpdateFailure.SIGNATURE_MISMATCH -> R.string.update_failure_signature
    UpdateFailure.INSTALL_CANCELLED -> R.string.update_failure_cancelled
    UpdateFailure.INSTALL_FAILED -> R.string.update_failure_install
}

/** Names for the installers #78 treats as owning updates; a raw package name is never shown. */
internal fun updaterName(packageName: String?): String? = when (packageName) {
    "com.android.vending" -> "Google Play"
    "org.fdroid.fdroid", "org.fdroid.basic" -> "F-Droid"
    "com.looker.droidify" -> "Droid-ify"
    "com.machiav3lli.fdroid" -> "Neo Store"
    "dev.imranr.obtainium", "dev.imranr.obtainium.fdroid" -> "Obtainium"
    "com.aurora.store" -> "Aurora Store"
    else -> null
}
