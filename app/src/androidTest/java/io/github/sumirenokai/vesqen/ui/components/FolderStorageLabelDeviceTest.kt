package io.github.sumirenokai.vesqen.ui.components

import android.os.Build
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** #218: the storage named on folder rows that share a name and path. */
class FolderStorageLabelDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aMediaLibraryVolumeTakesAndroidsOwnName() {
        // Android 9 has no per-volume names in the media library.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val primary = context.getSystemService(StorageManager::class.java).primaryStorageVolume
        assertEquals(
            primary.getDescription(context),
            folderStorageLabel(context, "media:${MediaStore.VOLUME_EXTERNAL_PRIMARY}"),
        )
    }

    @Test
    fun otherStorageKeysTakeVesqensOwnLabels() {
        // The single "external" volume of Android 9, or a volume that is not mounted now.
        assertEquals(context.getString(R.string.library_storage_device_music), folderStorageLabel(context, "media:external"))
        assertEquals(
            context.getString(R.string.library_storage_added_folder),
            folderStorageLabel(context, "tree:com.android.externalstorage.documents/primary%3AMusic"),
        )
        assertNull(folderStorageLabel(context, ""))
    }
}
