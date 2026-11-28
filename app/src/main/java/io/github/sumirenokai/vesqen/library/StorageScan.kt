package io.github.sumirenokai.vesqen.library

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** #249: what Android allows the whole-storage scan now. */
internal enum class StorageScanAccess {
    GRANTED,

    /** Android 11 and later: "All files access" is off; Android 9 and 10: storage access is not granted. */
    NEEDS_PERMISSION,

    /** Android 10 without legacy storage, where only the added-folder way works. */
    UNAVAILABLE,
}

/**
 * #249: the GitHub build's whole-storage scan; the Play build has none (see the flavours'
 * `createStorageScan`). [enabled] is the user's switch, kept while Android withdraws the permission;
 * [access] asks Android each time, as the permission can be taken away in system settings at any moment.
 */
internal class StorageScan(
    private val preferences: SharedPreferences,
    val access: () -> StorageScanAccess,
    val volumes: () -> List<StorageVolumeRoot>,
) {
    // Each activation has its own token so turning the switch back on cannot revive a stopped scan.
    @Volatile
    private var activation = if (preferences.getBoolean(KEY_ENABLED, false)) Any() else null
    private val enabledState = MutableStateFlow(activation != null)
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabled == (activation != null)) return
        activation = if (enabled) Any() else null
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        enabledState.value = enabled
    }

    /** The scan runs, and the rows it found play, only while the switch is on and Android allows it. */
    fun isActive(): Boolean = activation != null && access() == StorageScanAccess.GRANTED

    /** A check for one traversal, cheap enough to run before every file. */
    fun enabledForScan(): (() -> Boolean)? {
        val token = activation ?: return null
        return { activation === token }
    }

    companion object {
        const val PREFERENCES = "storage_scan"
        private const val KEY_ENABLED = "enabled"
    }
}

/**
 * Android 11 and later grant "All files access" (`MANAGE_EXTERNAL_STORAGE`). Android 9 reads shared
 * storage with the storage permission the music library already asks for, and so does Android 10
 * where the GitHub manifest's `requestLegacyExternalStorage` takes effect. Android does not document
 * that for an app that targets a newer version, so it is checked rather than assumed.
 */
internal fun androidStorageScanAccess(context: Context): StorageScanAccess = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
        if (Environment.isExternalStorageManager()) StorageScanAccess.GRANTED else StorageScanAccess.NEEDS_PERMISSION
    Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && !Environment.isExternalStorageLegacy() -> StorageScanAccess.UNAVAILABLE
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ->
        StorageScanAccess.GRANTED
    else -> StorageScanAccess.NEEDS_PERMISSION
}
