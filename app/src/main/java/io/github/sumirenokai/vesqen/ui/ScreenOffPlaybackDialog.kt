package io.github.sumirenokai.vesqen.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.ScreenOffKeepAliveSettings

/** D13 (#333): null where Vesqen's own USB output is not built in. */
internal val LocalScreenOffKeepAlive = staticCompositionLocalOf<ScreenOffKeepAliveSettings?> { null }

/** Shown once on a vivo-family phone, the first time the own output plays (owner, 2026-11-12). */
@Composable
internal fun ScreenOffPlaybackDialog(
    onOpenSystemSettings: () -> Unit,
    onKeepAlive: () -> Unit,
    onNotNow: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("vesqen.screen-off-guidance"),
        // Back or a tap outside is the same as "Not now".
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.screen_off_guidance_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.screen_off_guidance_body))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onOpenSystemSettings,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.screen-off-guidance.settings"),
            ) { Text(stringResource(R.string.screen_off_guidance_open_settings)) }
        },
        // Two buttons in this slot wrap onto their own lines with large text, as in SupportPromptDialog.
        dismissButton = {
            TextButton(
                onClick = onKeepAlive,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.screen-off-guidance.keep-alive"),
            ) { Text(stringResource(R.string.screen_off_guidance_keep_alive)) }
            TextButton(
                onClick = onNotNow,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.screen-off-guidance.not-now"),
            ) { Text(stringResource(R.string.screen_off_guidance_not_now)) }
        },
    )
}

/**
 * Opens vivo's page for "allow high background power consumption" where the phone has it, and
 * Vesqen's app details otherwise. The vivo page is not public API, so a missing or non-exported
 * activity only moves on to the next one.
 */
internal fun openBackgroundPowerSettings(context: Context): Boolean {
    val candidates = listOf(
        Intent().setComponent(ComponentName(VIVO_POWER_PACKAGE, VIVO_POWER_ACTIVITY)),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Not on this phone: try the next page.
        } catch (_: SecurityException) {
            // Present but not exported: try the next page.
        }
    }
    return false
}

private const val VIVO_POWER_PACKAGE = "com.vivo.abe"
private const val VIVO_POWER_ACTIVITY = "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
