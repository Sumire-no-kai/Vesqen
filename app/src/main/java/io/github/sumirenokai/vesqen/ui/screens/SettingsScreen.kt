package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun SettingsScreen(
    outputStatus: UsbOutputStatus,
    onSetUsbOutputMode: (UsbOutputMode) -> Unit,
    onOpenPlaybackChain: () -> Unit,
    onOpenAbout: () -> Unit,
    versionName: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.settings"),
            contentPadding = PaddingValues(
                horizontal = VesqenSpacing.lg,
                vertical = VesqenSpacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
        ) {
            item {
                Text(
                    text = stringResource(R.string.destination_settings),
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            item {
                SettingsSectionLabel(stringResource(R.string.settings_audio_output))
            }
            item {
                SettingsChoiceRow(
                    icon = { Icon(Icons.Filled.Speaker, contentDescription = null) },
                    title = stringResource(R.string.settings_system_output),
                    body = stringResource(R.string.settings_system_output_body),
                    selected = outputStatus.mode == UsbOutputMode.SYSTEM,
                    onClick = { onSetUsbOutputMode(UsbOutputMode.SYSTEM) },
                    modifier = Modifier.testTag("vesqen.settings.output.system"),
                )
            }
            item {
                SettingsChoiceRow(
                    icon = { Icon(Icons.Filled.Usb, contentDescription = null) },
                    title = stringResource(R.string.settings_strict_usb_output),
                    body = strictUsbOutputBody(outputStatus),
                    selected = outputStatus.mode == UsbOutputMode.STRICT_BIT_PERFECT,
                    onClick = { onSetUsbOutputMode(UsbOutputMode.STRICT_BIT_PERFECT) },
                    modifier = Modifier.testTag("vesqen.settings.output.strict-usb"),
                )
            }
            item {
                SettingsActionRow(
                    icon = { Icon(Icons.Filled.AccountTree, contentDescription = null) },
                    title = stringResource(R.string.settings_playback_chain),
                    body = stringResource(R.string.settings_playback_chain_body),
                    onClick = onOpenPlaybackChain,
                    modifier = Modifier.testTag("vesqen.settings.playback-chain"),
                )
            }
            item {
                SettingsSectionLabel(stringResource(R.string.settings_about))
            }
            item {
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

@Composable
private fun SettingsChoiceRow(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.surface),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.padding(VesqenSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
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
        UsbOutputFailure.ROUTE_MISMATCH -> R.string.usb_failure_route
        UsbOutputFailure.DEVICE_DISCONNECTED -> R.string.usb_failure_disconnected
        UsbOutputFailure.PROCESSING_NOT_NEUTRAL -> R.string.usb_failure_processing
        UsbOutputFailure.SERVICE_STOPPED -> R.string.usb_failure_service_stopped
        UsbOutputFailure.PLATFORM_ERROR -> R.string.usb_failure_platform
    },
)

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = VesqenSpacing.xs),
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        SettingsRowContent(icon = icon, title = title, body = body, showChevron = true)
    }
}

@Composable
private fun SettingsRowContent(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    showChevron: Boolean,
) {
    Row(
        modifier = Modifier.padding(VesqenSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
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
        if (showChevron) {
            Spacer(Modifier.width(VesqenSpacing.xs))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
