package io.github.sumirenokai.vesqen.ui.components

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import io.github.sumirenokai.vesqen.R
import java.util.Locale

/**
 * Names the storage of a folder row whose name and path another row shares (#218). A media library
 * volume takes Android's own name for it, such as "SanDisk SD card". An added folder is labelled as
 * one: apps cannot ask a documents provider which volume a granted folder is on.
 */
internal fun folderStorageLabel(context: Context, storageKey: String): String? = when {
    storageKey.startsWith(MEDIA_PREFIX) -> context.getSystemService(StorageManager::class.java)
        ?.storageVolumes
        ?.firstOrNull { it.isMediaStoreVolume(storageKey.removePrefix(MEDIA_PREFIX)) }
        ?.getDescription(context)
        ?.takeIf(String::isNotBlank)
        // Android 9 names every volume "external", so its music cannot be placed on one.
        ?: context.getString(R.string.library_storage_device_music)
    storageKey.startsWith(TREE_PREFIX) -> context.getString(R.string.library_storage_added_folder)
    else -> null
}

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
