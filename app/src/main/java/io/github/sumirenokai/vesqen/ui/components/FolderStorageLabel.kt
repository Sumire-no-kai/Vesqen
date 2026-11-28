package io.github.sumirenokai.vesqen.ui.components

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.FOLDER_STORAGE_VOLUME_PREFIX
import io.github.sumirenokai.vesqen.library.PRIMARY_STORAGE_VOLUME
import java.util.Locale

/**
 * Names the storage of a folder row whose name and path another row shares (#218, #368).
 * A known volume takes Android's name for it, such as "SanDisk SD card". Other added folders use
 * the generic label because their document provider may not expose a volume.
 */
internal fun folderStorageLabel(context: Context, storageKey: String): String? = when {
    storageKey.startsWith(FOLDER_STORAGE_VOLUME_PREFIX) -> {
        val volumeId = storageKey.removePrefix(FOLDER_STORAGE_VOLUME_PREFIX)
        context.storageVolumeLabel { volume ->
            if (volumeId == PRIMARY_STORAGE_VOLUME) volume.isPrimary else volume.uuid.equals(volumeId, ignoreCase = true)
        } ?: if (volumeId == PRIMARY_STORAGE_VOLUME) context.getString(R.string.library_storage_device_music) else volumeId
    }
    storageKey.startsWith(MEDIA_PREFIX) ->
        context.storageVolumeLabel { it.isMediaStoreVolume(storageKey.removePrefix(MEDIA_PREFIX)) }
        // Android 9 names every volume "external", so its music cannot be placed on one.
        ?: context.getString(R.string.library_storage_device_music)
    storageKey.startsWith(TREE_PREFIX) -> context.getString(R.string.library_storage_added_folder)
    else -> null
}

private fun Context.storageVolumeLabel(matches: (StorageVolume) -> Boolean): String? =
    getSystemService(StorageManager::class.java)?.storageVolumes?.firstOrNull(matches)
        ?.getDescription(this)?.takeIf(String::isNotBlank)

private fun StorageVolume.isMediaStoreVolume(volumeName: String): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        mediaStoreVolumeName == volumeName
    } else {
        // Android 10 names the primary volume as below and the others by their UUID in lower case.
        if (isPrimary) volumeName == PRIMARY_VOLUME else uuid?.lowercase(Locale.ROOT) == volumeName
    }

private const val MEDIA_PREFIX = "media:"
private const val TREE_PREFIX = "tree:"

/** `MediaStore.VOLUME_EXTERNAL_PRIMARY`, which the SDK adds in Android 10. */
private const val PRIMARY_VOLUME = "external_primary"
