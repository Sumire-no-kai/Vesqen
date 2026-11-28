package io.github.sumirenokai.vesqen.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.StorageScan
import io.github.sumirenokai.vesqen.library.StorageScanAccess

/** #249: the GitHub build's whole-storage scan, for Settings and the Library; null in the Play build. */
internal val LocalStorageScan = staticCompositionLocalOf<StorageScan?> { null }

internal enum class StorageScanDialog { INTRO, TURN_OFF }

/**
 * #249: turning the whole-storage scan on and off, shared by Settings and the Library's empty state so
 * both explain the permission alike. On Android 11 and later the scan needs "All files access", which
 * only system settings give: Vesqen explains it first, opens the page and reads the answer on return.
 * Android 9 and 10 use the storage permission the music library already asks for.
 */
@Stable
internal class StorageScanFlow(
    val scan: StorageScan,
    private val context: Context,
    private val awaitingSystemSettings: MutableState<Boolean>,
    private val dialogState: MutableState<StorageScanDialog?>,
    private val onChanged: () -> Unit,
) {
    var access by mutableStateOf(scan.access())
        private set

    val dialog: StorageScanDialog? get() = dialogState.value

    /** Why the switch stayed off after the last answer, shown under it until the screen is left. */
    @get:StringRes
    var note: Int? by mutableStateOf(null)
        private set

    /** Android 9 and 10: the storage permission was refused for good, so only app settings can give it. */
    var storageRefused by mutableStateOf(false)
        private set

    internal var requestStoragePermission: () -> Unit = {}

    fun turnOn() {
        note = null
        dialogState.value = StorageScanDialog.INTRO
    }

    fun turnOff() {
        dialogState.value = StorageScanDialog.TURN_OFF
    }

    fun dismissDialog() {
        dialogState.value = null
    }

    fun confirmIntro() {
        dialogState.value = null
        when {
            access == StorageScanAccess.GRANTED -> enable()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (openAllFilesAccessSettings(context)) awaitingSystemSettings.value = true
                else note = R.string.full_scan_settings_unavailable
            }
            else -> requestStoragePermission()
        }
    }

    fun confirmTurnOff() {
        dialogState.value = null
        scan.setEnabled(false)
        onChanged()
    }

    /** "Allow again" and "Remove the permission in system settings": the page itself, already explained. */
    fun openPermissionPage() {
        val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openAllFilesAccessSettings(context)
        } else {
            openAppDetails(context)
        }
        if (!opened) note = R.string.full_scan_settings_unavailable
    }

    internal fun onResume() {
        val previous = access
        access = scan.access()
        if (awaitingSystemSettings.value) {
            awaitingSystemSettings.value = false
            if (access == StorageScanAccess.GRANTED) enable() else note = R.string.settings_full_scan_not_allowed
        } else if (scan.enabled.value && previous != access) {
            // Given back or taken away in system settings: the library shows or hides the scan's songs.
            onChanged()
        }
    }

    internal fun onStoragePermissionResult(granted: Boolean, refusedForGood: Boolean) {
        access = scan.access()
        if (granted && access == StorageScanAccess.GRANTED) {
            storageRefused = false
            enable()
        } else {
            note = R.string.settings_full_scan_storage_not_allowed
            storageRefused = refusedForGood
        }
    }

    private fun enable() {
        note = null
        scan.setEnabled(true)
        onChanged()
    }
}

/** The flow for [scan]; [onChanged] runs after the switch or Android's answer changed what the library holds. */
@Composable
internal fun rememberStorageScanFlow(scan: StorageScan, onChanged: () -> Unit): StorageScanFlow {
    val context = LocalContext.current
    val currentOnChanged by rememberUpdatedState(onChanged)
    // Saved, so the answer is still read when the activity was recreated while system settings were open.
    val awaiting = rememberSaveable { mutableStateOf(false) }
    val dialog = rememberSaveable { mutableStateOf<StorageScanDialog?>(null) }
    val flow = remember(scan) { StorageScanFlow(scan, context, awaiting, dialog) { currentOnChanged() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context.findActivity()
        val refusedForGood = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
        flow.onStoragePermissionResult(granted, refusedForGood)
    }
    flow.requestStoragePermission = { launcher.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, flow) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) flow.onResume() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return flow
}

/** The explanation before turning on, and the confirmation before turning off. */
@Composable
internal fun StorageScanDialogs(flow: StorageScanFlow) {
    when (flow.dialog) {
        StorageScanDialog.INTRO -> StorageScanDialogFrame(
            title = stringResource(R.string.settings_full_scan),
            body = stringResource(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) R.string.full_scan_intro_body else R.string.full_scan_intro_body_legacy,
            ),
            // Already allowed, the scan turns on here; otherwise Android 11 and later send to system settings.
            confirmLabel = stringResource(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && flow.access != StorageScanAccess.GRANTED) {
                    R.string.screen_off_guidance_open_settings
                } else {
                    R.string.full_scan_turn_on
                },
            ),
            onConfirm = flow::confirmIntro,
            onDismiss = flow::dismissDialog,
            tag = "vesqen.storage-scan.intro",
        )
        StorageScanDialog.TURN_OFF -> StorageScanDialogFrame(
            title = stringResource(R.string.full_scan_off_title),
            body = stringResource(R.string.full_scan_off_body),
            confirmLabel = stringResource(R.string.full_scan_turn_off),
            onConfirm = flow::confirmTurnOff,
            onDismiss = flow::dismissDialog,
            tag = "vesqen.storage-scan.turn-off",
        )
        null -> Unit
    }
}

@Composable
private fun StorageScanDialogFrame(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tag: String,
) {
    AlertDialog(
        modifier = Modifier.testTag(tag),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(body) } },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag.confirm")) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag.cancel")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * What a withdrawn permission is called (#363 S5). Android 9 and 10 have no "All files access": the
 * scan reads shared storage with the storage permission there.
 */
@StringRes
internal fun storageScanPermissionOffText(sdkInt: Int = Build.VERSION.SDK_INT): Int =
    if (sdkInt >= Build.VERSION_CODES.R) R.string.settings_full_scan_permission_off else R.string.settings_full_scan_storage_permission_off

/** The music sources sheet's line for the same state; see [storageScanPermissionOffText]. */
@StringRes
internal fun storageScanNeedsPermissionText(sdkInt: Int = Build.VERSION.SDK_INT): Int =
    if (sdkInt >= Build.VERSION_CODES.R) R.string.library_full_scan_needs_permission else R.string.library_full_scan_needs_storage_permission

/**
 * Android 11 and later: Vesqen's own switch for "All files access", or the list of apps where a phone
 * has no such page.
 */
private fun openAllFilesAccessSettings(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    return listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null)),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    ).any { intent -> startActivity(context, intent) }
}

private fun openAppDetails(context: Context): Boolean = startActivity(
    context,
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
)

private fun startActivity(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
