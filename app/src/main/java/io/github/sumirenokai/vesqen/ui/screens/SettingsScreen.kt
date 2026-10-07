package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.NO_BIT_PERFECT_MIXER_CODE
import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.service.ServiceState
import io.github.sumirenokai.vesqen.ui.components.OutputStatusChip
import io.github.sumirenokai.vesqen.ui.components.PaperCard
import io.github.sumirenokai.vesqen.ui.components.PaperDivider
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy
import io.github.sumirenokai.vesqen.updates.AppUpdater
import io.github.sumirenokai.vesqen.usage.UsageStatistics
import io.github.sumirenokai.vesqen.verification.OutputVerificationImportFailure
import io.github.sumirenokai.vesqen.verification.OutputVerificationImportResult
import io.github.sumirenokai.vesqen.verification.OutputVerificationMatch
import io.github.sumirenokai.vesqen.verification.OutputVerificationRegistryState

/**
 * B · Paper & Sound settings (#35): serif group titles over paper-raised cards with hairline rows,
 * without slogans, per-group subtitles or icon tiles. The group order follows PRD F14.
 */
@Composable
fun SettingsScreen(
    outputStatus: UsbOutputStatus,
    onSetUsbOutputMode: (UsbOutputMode) -> Unit,
    onOpenPlaybackChain: () -> Unit,
    onImportVerificationRegistry: () -> Unit,
    onOpenAbout: () -> Unit,
    versionName: String,
    modifier: Modifier = Modifier,
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    appUpdater: AppUpdater? = null,
    usageStatistics: UsageStatistics? = null,
    onOpenUsageStatistics: () -> Unit = {},
    deviceReportAvailable: Boolean = false,
    onOpenDeviceReport: () -> Unit = {},
    outputModeSelectionEnabled: Boolean = true,
    outputVerification: OutputVerificationMatch? = null,
    verificationRegistryState: OutputVerificationRegistryState = OutputVerificationRegistryState.Empty,
    verificationImportResult: OutputVerificationImportResult? = null,
    onExplainStrictUsbUnavailable: () -> Unit = {},
) {
    val unsupportedPlatform = outputStatus.officialMixerApiSupport?.takeUnless { it.mixerApiAvailable }
    val strictSelected = outputStatus.mode == UsbOutputMode.STRICT_BIT_PERFECT
    val motionMillis = rememberVesqenMotionPolicy().stateChangeMillis
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.settings"),
            contentPadding = PaddingValues(bottom = VesqenSpacing.xl),
        ) {
            item { SettingsTitle() }
            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_playback_output),
                    modifier = Modifier.testTag("vesqen.settings.section.playback-output"),
                    footer = {
                        if (!outputModeSelectionEnabled) SettingsNote(stringResource(R.string.playback_controls_connecting))
                        AnimatedVisibility(
                            visible = strictSelected,
                            enter = fadeIn(tween(motionMillis)) + expandVertically(tween(motionMillis)),
                            exit = fadeOut(tween(motionMillis)) + shrinkVertically(tween(motionMillis)),
                        ) { StrictOutputStatusBox(outputStatus) }
                    },
                ) {
                    Column(Modifier.selectableGroup().testTag("vesqen.settings.output-modes")) {
                        SettingsRadioRow(
                            title = stringResource(R.string.settings_system_output),
                            description = stringResource(R.string.settings_system_output_body),
                            selected = outputStatus.mode == UsbOutputMode.SYSTEM,
                            enabled = outputModeSelectionEnabled,
                            onClick = { onSetUsbOutputMode(UsbOutputMode.SYSTEM) },
                            modifier = Modifier.testTag("vesqen.settings.output.system"),
                        )
                        PaperDivider()
                        SettingsRadioRow(
                            title = stringResource(R.string.settings_strict_usb_output),
                            description = unsupportedPlatform?.let {
                                stringResource(R.string.settings_strict_usb_platform_unavailable, it.androidRelease, it.apiLevel)
                            } ?: stringResource(R.string.settings_strict_usb_output_body),
                            selected = strictSelected,
                            enabled = outputModeSelectionEnabled && unsupportedPlatform == null,
                            onClick = { onSetUsbOutputMode(UsbOutputMode.STRICT_BIT_PERFECT) },
                            onUnavailableClick = onExplainStrictUsbUnavailable.takeIf {
                                outputModeSelectionEnabled && unsupportedPlatform != null
                            },
                            modifier = Modifier.testTag("vesqen.settings.output.strict-usb"),
                        )
                    }
                }
            }
            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_audio_proof),
                    modifier = Modifier.testTag("vesqen.settings.section.audio-proof"),
                ) {
                    SettingsRow(
                        title = stringResource(R.string.settings_playback_chain),
                        description = stringResource(R.string.settings_playback_chain_body),
                        onClick = onOpenPlaybackChain,
                        modifier = Modifier.testTag("vesqen.settings.playback-chain"),
                    )
                }
            }
            item { SettingsPrivacyGroup(usageStatistics, onOpenUsageStatistics, deviceReportAvailable, onOpenDeviceReport) }
            if (appUpdater != null) item { SettingsUpdatesGroup(appUpdater) }
            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_application),
                    modifier = Modifier.testTag("vesqen.settings.section.application"),
                ) {
                    SettingsRow(
                        title = stringResource(R.string.settings_about_vesqen),
                        value = versionName,
                        onClick = onOpenAbout,
                        modifier = Modifier.testTag("vesqen.settings.about"),
                    )
                    PaperDivider()
                    SettingsRow(
                        title = stringResource(R.string.privacy_policy_title),
                        onClick = onOpenPrivacyPolicy,
                        modifier = Modifier.testTag("vesqen.settings.privacy-policy"),
                    )
                    PaperDivider()
                    SettingsRow(
                        title = stringResource(R.string.licenses_title),
                        onClick = onOpenLicenses,
                        modifier = Modifier.testTag("vesqen.settings.licenses"),
                    )
                }
            }
            item {
                // #35: verification records are a maintainer tool, so they leave the main groups.
                SettingsGroup(
                    title = stringResource(R.string.settings_advanced),
                    modifier = Modifier.testTag("vesqen.settings.section.advanced"),
                ) {
                    SettingsRow(
                        title = stringResource(R.string.settings_verification_registry),
                        description = verificationRegistryBody(
                            outputVerification = outputVerification,
                            registryState = verificationRegistryState,
                            importResult = verificationImportResult,
                        ),
                        onClick = onImportVerificationRegistry,
                        modifier = Modifier.testTag("vesqen.settings.verification-registry"),
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.settings_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.lg)
                        .testTag("vesqen.settings.footer"),
                )
            }
        }
    }
}

@Composable
private fun verificationRegistryBody(
    outputVerification: OutputVerificationMatch?,
    registryState: OutputVerificationRegistryState,
    importResult: OutputVerificationImportResult?,
): String {
    if (outputVerification != null) {
        return stringResource(
            R.string.settings_verification_active,
            outputVerification.record.recordId,
        )
    }
    return when (importResult) {
        is OutputVerificationImportResult.Success -> stringResource(
            R.string.settings_verification_imported,
            importResult.recordCount,
            importResult.applicableInstallRecordCount,
        )
        is OutputVerificationImportResult.Failure -> stringResource(
            R.string.settings_verification_import_failed,
            verificationFailureLabel(importResult.reason),
        )
        null -> when (registryState) {
            OutputVerificationRegistryState.Loading -> stringResource(R.string.settings_verification_loading)
            OutputVerificationRegistryState.Empty -> stringResource(R.string.settings_verification_empty)
            is OutputVerificationRegistryState.Ready -> stringResource(
                R.string.settings_verification_loaded,
                registryState.records.size,
                registryState.applicableInstallRecordCount,
            )
            is OutputVerificationRegistryState.Invalid -> stringResource(
                R.string.settings_verification_invalid,
                verificationFailureLabel(registryState.reason),
            )
        }
    }
}

@Composable
private fun verificationFailureLabel(failure: OutputVerificationImportFailure): String = stringResource(
    when (failure) {
        OutputVerificationImportFailure.INPUT_TOO_LARGE -> R.string.verification_failure_too_large
        OutputVerificationImportFailure.MALFORMED_DOCUMENT -> R.string.verification_failure_malformed
        OutputVerificationImportFailure.UNSUPPORTED_SCHEMA -> R.string.verification_failure_schema
        OutputVerificationImportFailure.UNSUPPORTED_SIGNATURE_ALGORITHM ->
            R.string.verification_failure_algorithm
        OutputVerificationImportFailure.UNKNOWN_SIGNING_KEY -> R.string.verification_failure_unknown_issuer
        OutputVerificationImportFailure.SIGNATURE_MISMATCH -> R.string.verification_failure_signature
        OutputVerificationImportFailure.NO_TRUSTED_ISSUER -> R.string.verification_failure_issuer_unavailable
        OutputVerificationImportFailure.IO_ERROR -> R.string.verification_failure_io
    },
)


@Composable
internal fun strictUsbOutputBody(status: UsbOutputStatus): String {
    if (status.mode != UsbOutputMode.STRICT_BIT_PERFECT) {
        status.officialMixerApiSupport?.takeUnless { it.mixerApiAvailable }?.let { support ->
            return stringResource(
                R.string.settings_strict_usb_platform_unavailable,
                support.androidRelease,
                support.apiLevel,
            )
        }
        return stringResource(R.string.settings_strict_usb_output_body)
    }
    val device = status.deviceName ?: stringResource(R.string.settings_usb_device_unknown)
    return when (status.phase) {
        // Strict is chosen but nothing has played yet: say what happens next, not the option again.
        UsbOutputPhase.SYSTEM -> stringResource(R.string.settings_strict_usb_waiting)
        UsbOutputPhase.AVAILABLE -> stringResource(R.string.settings_strict_usb_available, device)
        UsbOutputPhase.APPLYING -> stringResource(R.string.settings_strict_usb_applying, device)
        UsbOutputPhase.ACTIVE -> stringResource(
            R.string.settings_strict_usb_active,
            device,
            status.sinkFormat?.displayName ?: stringResource(R.string.settings_format_unknown),
        )
        UsbOutputPhase.FAILED -> stringResource(
            R.string.settings_strict_usb_failed,
            strictUsbFailureLabel(requireNotNull(status.failure), status.decisionCode),
        )
    }
}

/** [decisionCode] tells "this phone offers no bit-perfect path" from "the DAC lacks this format". */
@Composable
internal fun strictUsbFailureLabel(failure: UsbOutputFailure, decisionCode: String? = null): String = stringResource(
    when (failure) {
        UsbOutputFailure.UNSUPPORTED_ANDROID_VERSION -> R.string.usb_failure_android_version
        UsbOutputFailure.USB_HOST_UNAVAILABLE -> R.string.usb_failure_host_unavailable
        UsbOutputFailure.MODIFY_AUDIO_SETTINGS_DENIED -> R.string.usb_failure_permission
        UsbOutputFailure.NO_USB_AUDIO_DEVICE -> R.string.usb_failure_no_device
        UsbOutputFailure.SOURCE_FORMAT_UNKNOWN -> R.string.usb_failure_source_unknown
        UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED -> R.string.usb_failure_source_unsupported
        UsbOutputFailure.MIXER_QUERY_FAILED -> R.string.usb_failure_query
        UsbOutputFailure.NO_MATCHING_MIXER_ATTRIBUTE ->
            if (decisionCode == NO_BIT_PERFECT_MIXER_CODE) R.string.usb_failure_no_bit_perfect else R.string.usb_failure_no_profile
        UsbOutputFailure.MIXER_REQUEST_REJECTED -> R.string.usb_failure_request
        UsbOutputFailure.MIXER_READBACK_MISMATCH -> R.string.usb_failure_readback
        UsbOutputFailure.MIXER_CLEAR_FAILED -> R.string.usb_failure_clear
        UsbOutputFailure.AUDIO_TRACK_FORMAT_MISMATCH -> R.string.usb_failure_track_format
        UsbOutputFailure.PREFERRED_DEVICE_REJECTED -> R.string.usb_failure_preferred_device
        UsbOutputFailure.ROUTE_UNAVAILABLE -> R.string.usb_failure_route_unavailable
        UsbOutputFailure.ROUTE_MISMATCH -> R.string.usb_failure_route
        UsbOutputFailure.DEVICE_DISCONNECTED -> R.string.usb_failure_disconnected
        UsbOutputFailure.PROCESSING_NOT_NEUTRAL -> R.string.usb_failure_processing
        UsbOutputFailure.SERVICE_STOPPED -> R.string.usb_failure_service_stopped
        UsbOutputFailure.PLATFORM_ERROR -> R.string.usb_failure_platform
    },
)


/**
 * Usage statistics appear only when this build has a server to send them to; the device report
 * works without one (share and email).
 */
@Composable
private fun SettingsPrivacyGroup(
    usageStatistics: UsageStatistics?,
    onOpenUsageStatistics: () -> Unit,
    deviceReportAvailable: Boolean,
    onOpenDeviceReport: () -> Unit,
) {
    // #96: a retired service leaves Settings; a paused one says so instead of On or Off.
    val usage = usageStatistics?.snapshot?.collectAsStateWithLifecycle()?.value
        ?.takeIf { it.endpointConfigured && it.service != ServiceState.RETIRED }
    if (usage == null && !deviceReportAvailable) return
    SettingsGroup(
        title = stringResource(R.string.settings_privacy_data),
        modifier = Modifier.testTag("vesqen.settings.section.privacy-data"),
    ) {
        if (usage != null) {
            SettingsRow(
                title = stringResource(R.string.settings_usage_statistics),
                value = stringResource(
                    when {
                        usage.service == ServiceState.PAUSED -> R.string.settings_usage_paused
                        usage.enabled -> R.string.settings_usage_on
                        else -> R.string.settings_usage_off
                    },
                ),
                onClick = onOpenUsageStatistics,
                modifier = Modifier.testTag("vesqen.settings.usage-statistics"),
            )
        }
        if (usage != null && deviceReportAvailable) PaperDivider()
        if (deviceReportAvailable) {
            SettingsRow(
                title = stringResource(R.string.settings_device_report),
                onClick = onOpenDeviceReport,
                modifier = Modifier.testTag("vesqen.settings.device-report"),
            )
        }
    }
}

@Composable
private fun SettingsTitle() {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Same title band as Library: 32 sp serif, smaller where it would crowd the width.
        val compact = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = VesqenSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.destination_settings),
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displayMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .semantics { heading() }
                    .testTag("vesqen.settings.title"),
            )
        }
    }
}

/** Header for pages opened from Settings: back arrow and a serif title. */
@Composable
internal fun SettingsDetailHeader(title: String, backTag: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = VesqenSpacing.xxs, end = VesqenSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag(backTag)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Spacer(Modifier.width(VesqenSpacing.xs))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** A serif group title over one paper-raised card; [footer] sits under the card. */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = VesqenSpacing.lg, end = VesqenSpacing.lg, top = 28.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(start = VesqenSpacing.xxs, bottom = 10.dp)
                .semantics { heading() },
        )
        PaperCard(content = content)
        footer()
    }
}

/** B list row: 56 dp, or 64 dp with a description; a value sits left of the chevron. */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // One spoken unit for TalkBack, also while a busy row is not clickable.
            .semantics(mergeDescendants = true) {}
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .heightIn(min = if (description == null) 56.dp else 64.dp)
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // #35: above 130 % text a value beside the title squeezed the title into one letter per
        // line ("Ver/sio/n"), so the value moves under it.
        val valueBelow = value != null && LocalDensity.current.fontScale > 1.3f
        SettingsRowText(title, description, Modifier.weight(1f), value.takeIf { valueBelow })
        if (value != null && !valueBelow) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = VesqenSpacing.sm),
            )
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = VesqenSpacing.xxs)
                    .size(20.dp),
            )
        }
    }
}

@Composable
private fun SettingsRowText(title: String, description: String?, modifier: Modifier = Modifier, value: String? = null) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onUnavailableClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    enabled -> Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                    onUnavailableClick != null -> Modifier.clickable(onClick = onUnavailableClick, role = Role.Button)
                    else -> Modifier.selectable(selected = selected, enabled = false, onClick = onClick, role = Role.RadioButton)
                },
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.sm)
            .alpha(if (enabled) 1f else .56f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = LocalVesqenColors.current.radioIdle,
            ),
        )
        Spacer(Modifier.width(VesqenSpacing.sm))
        SettingsRowText(title, description, Modifier.weight(1f))
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = if (description == null) 56.dp else 64.dp)
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowText(title, description, Modifier.weight(1f))
        Spacer(Modifier.width(VesqenSpacing.sm))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = VesqenSpacing.xxs, top = 10.dp),
    )
}

/** B §5: appears under the output choices while strict USB is selected. */
@Composable
private fun StrictOutputStatusBox(status: UsbOutputStatus) {
    Column(
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .border(1.dp, LocalVesqenColors.current.hairline, RoundedCornerShape(VesqenRadii.control))
            .padding(14.dp)
            .testTag("vesqen.settings.output.strict-status"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutputStatusChip(declaration = status.declaration)
        Text(text = strictUsbOutputBody(status), style = MaterialTheme.typography.bodySmall)
    }
}
