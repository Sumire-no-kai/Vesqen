package io.github.sumirenokai.vesqen.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.OfficialMixerApiSupport
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.ui.screens.strictUsbFailureLabel

@Composable
internal fun StrictUsbUnavailableDialog(
    support: OfficialMixerApiSupport?,
    onDismiss: () -> Unit,
) {
    support?.takeUnless(OfficialMixerApiSupport::mixerApiAvailable) ?: return
    AlertDialog(
        modifier = Modifier.testTag("vesqen.output.unavailable-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.strict_usb_unavailable_title)) },
        text = {
            Text(
                stringResource(
                    R.string.strict_usb_unavailable_body,
                    support.androidRelease,
                    support.apiLevel,
                ),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("vesqen.output.unavailable-dismiss"),
            ) { Text(stringResource(R.string.player_output_done)) }
        },
    )
}

/** The app shell owns feedback, including failures started from Library or the mini-player. */
@Composable
internal fun StrictUsbFailureDialog(
    status: UsbOutputStatus,
    canChangeOutputMode: Boolean,
    onSetUsbOutputMode: (UsbOutputMode) -> Unit,
) {
    var dismissedFailure by rememberSaveable { mutableStateOf<String?>(null) }
    val failure = status.failure ?: return
    // A fresh failed attempt has a new coordinator generation. Telemetry refreshes do not.
    val failureKey = "${status.generation}:${status.observedAtEpochMs}:${failure.name}"
    if (dismissedFailure == failureKey) return

    AlertDialog(
        modifier = Modifier.testTag("vesqen.output.failure-dialog"),
        onDismissRequest = { dismissedFailure = failureKey },
        title = { Text(stringResource(R.string.chain_strict_failed_title)) },
        text = {
            Text(
                stringResource(R.string.player_strict_failure_body, strictUsbFailureLabel(failure)),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSetUsbOutputMode(UsbOutputMode.SYSTEM)
                },
                enabled = canChangeOutputMode,
                modifier = Modifier.testTag("vesqen.output.use-system"),
            ) { Text(stringResource(R.string.player_use_system_output)) }
        },
        dismissButton = {
            TextButton(
                onClick = { dismissedFailure = failureKey },
                modifier = Modifier.testTag("vesqen.output.keep-strict"),
            ) { Text(stringResource(R.string.player_keep_strict_output)) }
        },
    )
}
