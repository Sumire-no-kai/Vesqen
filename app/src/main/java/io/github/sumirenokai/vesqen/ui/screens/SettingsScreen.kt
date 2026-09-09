package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy
import io.github.sumirenokai.vesqen.verification.OutputVerificationImportFailure
import io.github.sumirenokai.vesqen.verification.OutputVerificationImportResult
import io.github.sumirenokai.vesqen.verification.OutputVerificationMatch
import io.github.sumirenokai.vesqen.verification.OutputVerificationRegistryState

private val SettingsStateEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun SettingsScreen(
    outputStatus: UsbOutputStatus,
    onSetUsbOutputMode: (UsbOutputMode) -> Unit,
    onOpenPlaybackChain: () -> Unit,
    onImportVerificationRegistry: () -> Unit,
    onOpenAbout: () -> Unit,
    versionName: String,
    modifier: Modifier = Modifier,
    outputVerification: OutputVerificationMatch? = null,
    verificationRegistryState: OutputVerificationRegistryState = OutputVerificationRegistryState.Empty,
    verificationImportResult: OutputVerificationImportResult? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.settings"),
            contentPadding = PaddingValues(
                horizontal = VesqenSpacing.lg,
                vertical = VesqenSpacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.lg),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs)) {
                    Text(
                        text = stringResource(R.string.destination_settings),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SettingsSection(
                    title = stringResource(R.string.settings_playback_output),
                    body = stringResource(R.string.settings_playback_output_body),
                    modifier = Modifier.testTag("vesqen.settings.section.playback-output"),
                ) {
                    SettingsChoiceRow(
                        icon = { Icon(Icons.Filled.Speaker, contentDescription = null) },
                        title = stringResource(R.string.settings_system_output),
                        body = stringResource(R.string.settings_system_output_body),
                        selected = outputStatus.mode == UsbOutputMode.SYSTEM,
                        onClick = { onSetUsbOutputMode(UsbOutputMode.SYSTEM) },
                        modifier = Modifier.testTag("vesqen.settings.output.system"),
                    )
                    SettingsDivider()
                    SettingsChoiceRow(
                        icon = { Icon(Icons.Filled.Usb, contentDescription = null) },
                        title = stringResource(R.string.settings_strict_usb_output),
                        body = strictUsbOutputBody(outputStatus),
                        selected = outputStatus.mode == UsbOutputMode.STRICT_BIT_PERFECT,
                        onClick = { onSetUsbOutputMode(UsbOutputMode.STRICT_BIT_PERFECT) },
                        modifier = Modifier.testTag("vesqen.settings.output.strict-usb"),
                    )
                }
            }
            item {
                SettingsSection(
                    title = stringResource(R.string.settings_audio_proof),
                    body = stringResource(R.string.settings_audio_proof_body),
                    modifier = Modifier.testTag("vesqen.settings.section.audio-proof"),
                ) {
                    SettingsActionRow(
                        icon = { Icon(Icons.Filled.AccountTree, contentDescription = null) },
                        title = stringResource(R.string.settings_playback_chain),
                        body = stringResource(R.string.settings_playback_chain_body),
                        onClick = onOpenPlaybackChain,
                        modifier = Modifier.testTag("vesqen.settings.playback-chain"),
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        icon = {
                            Icon(
                                if (outputVerification == null) Icons.Filled.FileOpen else Icons.Filled.VerifiedUser,
                                contentDescription = null,
                            )
                        },
                        title = stringResource(R.string.settings_verification_registry),
                        body = verificationRegistryBody(
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
                SettingsSection(
                    title = stringResource(R.string.settings_application),
                    body = stringResource(R.string.settings_application_body),
                    modifier = Modifier.testTag("vesqen.settings.section.application"),
                ) {
                    SettingsActionRow(
                        icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        title = stringResource(R.string.settings_about_vesqen),
                        body = stringResource(R.string.settings_version, versionName),
                        onClick = onOpenAbout,
                        modifier = Modifier.testTag("vesqen.settings.about"),
                    )
                }
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
        OutputVerificationImportFailure.SIGNATURE_MISMATCH -> R.string.verification_failure_signature
        OutputVerificationImportFailure.NO_SIGNING_CERTIFICATE -> R.string.verification_failure_certificate
        OutputVerificationImportFailure.IO_ERROR -> R.string.verification_failure_io
    },
)

@Composable
private fun SettingsChoiceRow(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionPolicy = rememberVesqenMotionPolicy()
    val durationMillis = motionPolicy.stateChangeMillis
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(durationMillis, easing = SettingsStateEasing),
        label = "vesqen.settings-choice-background",
    )
    val iconContainerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(durationMillis, easing = SettingsStateEasing),
        label = "vesqen.settings-choice-icon-container",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected && !motionPolicy.reduceMotion) 1.08f else 1f,
        animationSpec = tween(
            durationMillis = if (motionPolicy.reduceMotion) 0 else durationMillis,
            easing = SettingsStateEasing,
        ),
        label = "vesqen.settings-choice-icon-scale",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .defaultMinSize(minHeight = 88.dp)
            .padding(VesqenSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(
            containerColor = iconContainerColor,
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            scale = iconScale,
            icon = icon,
        )
        Spacer(Modifier.width(VesqenSpacing.sm))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(VesqenSpacing.xs))
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun strictUsbOutputBody(status: UsbOutputStatus): String {
    if (status.mode != UsbOutputMode.STRICT_BIT_PERFECT) {
        return stringResource(R.string.settings_strict_usb_output_body)
    }
    val device = status.deviceName ?: stringResource(R.string.settings_usb_device_unknown)
    return when (status.phase) {
        UsbOutputPhase.SYSTEM -> stringResource(R.string.settings_strict_usb_output_body)
        UsbOutputPhase.AVAILABLE -> stringResource(R.string.settings_strict_usb_available, device)
        UsbOutputPhase.APPLYING -> stringResource(R.string.settings_strict_usb_applying, device)
        UsbOutputPhase.ACTIVE -> stringResource(
            R.string.settings_strict_usb_active,
            device,
            status.sinkFormat?.displayName ?: stringResource(R.string.settings_format_unknown),
        )
        UsbOutputPhase.FAILED -> stringResource(
            R.string.settings_strict_usb_failed,
            strictUsbFailureLabel(requireNotNull(status.failure)),
        )
    }
}

@Composable
private fun strictUsbFailureLabel(failure: UsbOutputFailure): String = stringResource(
    when (failure) {
        UsbOutputFailure.UNSUPPORTED_ANDROID_VERSION -> R.string.usb_failure_android_version
        UsbOutputFailure.USB_HOST_UNAVAILABLE -> R.string.usb_failure_host_unavailable
        UsbOutputFailure.MODIFY_AUDIO_SETTINGS_DENIED -> R.string.usb_failure_permission
        UsbOutputFailure.NO_USB_AUDIO_DEVICE -> R.string.usb_failure_no_device
        UsbOutputFailure.SOURCE_FORMAT_UNKNOWN -> R.string.usb_failure_source_unknown
        UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED -> R.string.usb_failure_source_unsupported
        UsbOutputFailure.MIXER_QUERY_FAILED -> R.string.usb_failure_query
        UsbOutputFailure.NO_MATCHING_MIXER_ATTRIBUTE -> R.string.usb_failure_no_profile
        UsbOutputFailure.MIXER_REQUEST_REJECTED -> R.string.usb_failure_request
        UsbOutputFailure.MIXER_READBACK_MISMATCH -> R.string.usb_failure_readback
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

@Composable
private fun SettingsSection(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = VesqenSpacing.xxs),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.surface),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
private fun SettingsActionRow(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsRowContent(
        modifier = modifier.clickable(onClick = onClick),
        icon = icon,
        title = title,
        body = body,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsRowContent(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 80.dp)
            .padding(VesqenSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = icon,
        )
        Spacer(Modifier.width(VesqenSpacing.sm))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(VesqenSpacing.xs))
        trailing()
    }
}

@Composable
private fun SettingsIcon(
    containerColor: Color,
    contentColor: Color,
    icon: @Composable () -> Unit,
    scale: Float = 1f,
) {
    Surface(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}
