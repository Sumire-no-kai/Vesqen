package io.github.sumirenokai.vesqen.library

import android.content.Context

/**
 * #249: the GitHub build offers the whole-storage scan. Its manifest declares `MANAGE_EXTERNAL_STORAGE`
 * and `requestLegacyExternalStorage`; the Play build declares neither (Play's policy does not allow the
 * permission for media access) and has no scan.
 */
internal fun createStorageScan(context: Context): StorageScan? {
    val appContext = context.applicationContext
    return StorageScan(
        preferences = appContext.getSharedPreferences(StorageScan.PREFERENCES, Context.MODE_PRIVATE),
        access = { androidStorageScanAccess(appContext) },
        volumes = { mountedStorageVolumes(appContext) },
    )
}
