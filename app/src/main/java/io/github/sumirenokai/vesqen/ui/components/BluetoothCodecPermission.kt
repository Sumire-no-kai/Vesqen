package io.github.sumirenokai.vesqen.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

/**
 * Asks for "Nearby devices" (BLUETOOTH_CONNECT) only when the listener taps it, to receive the codec
 * Android reports (#73). Playback never needs it. After a refusal the button opens the app's system
 * settings, as the music permission does, because Android may stop showing its own prompt.
 */
@Composable
internal fun BluetoothCodecPermissionButton(modifier: Modifier = Modifier) {
    // Android reports the codec through public classes only from API 33.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) BluetoothCodecPermissionContent(modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun BluetoothCodecPermissionContent(modifier: Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(bluetoothConnectGranted(context)) }
    var refused by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(context) {
        granted = bluetoothConnectGranted(context)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        refused = !result
    }
    if (granted) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs)) {
        Text(
            text = stringResource(R.string.bluetooth_codec_permission_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                if (refused) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                } else {
                    launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            },
            modifier = Modifier.testTag("vesqen.bluetooth-codec.permission"),
        ) {
            Text(stringResource(if (refused) R.string.allow_music_access_in_settings else R.string.bluetooth_codec_permission_action))
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun bluetoothConnectGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
