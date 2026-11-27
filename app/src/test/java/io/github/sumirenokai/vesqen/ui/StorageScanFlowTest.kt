package io.github.sumirenokai.vesqen.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.StorageScan
import io.github.sumirenokai.vesqen.library.StorageScanAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** #249: the switch follows Android's answer, and the library is refreshed when what it may show changes. */
@RunWith(RobolectricTestRunner::class)
class StorageScanFlowTest {
    private val context = RuntimeEnvironment.getApplication()
    private var access = StorageScanAccess.NEEDS_PERMISSION
    private var changes = 0
    private val scan = StorageScan(
        preferences = context.getSharedPreferences("storage-scan-test-${System.nanoTime()}", Context.MODE_PRIVATE),
        access = { access },
        volumes = { emptyList() },
    )
    private val awaiting = mutableStateOf(false)
    private val flow = StorageScanFlow(scan, context, awaiting, mutableStateOf(null)) { changes++ }

    @Test
    fun `coming back from system settings with the permission turns the scan on and refreshes the library`() {
        awaiting.value = true
        access = StorageScanAccess.GRANTED

        flow.onResume()

        assertTrue(scan.enabled.value)
        assertFalse(awaiting.value)
        assertEquals(1, changes)
        assertNull(flow.note)
    }

    @Test
    fun `coming back without the permission keeps it off and says why`() {
        awaiting.value = true

        flow.onResume()

        assertFalse(scan.enabled.value)
        assertEquals(R.string.settings_full_scan_not_allowed, flow.note)
        assertEquals(0, changes)
    }

    @Test
    fun `a permission taken away or given back while the scan is on refreshes the library once`() {
        access = StorageScanAccess.GRANTED
        flow.onResume()
        scan.setEnabled(true)

        access = StorageScanAccess.NEEDS_PERMISSION
        flow.onResume()
        flow.onResume()

        assertTrue(scan.enabled.value)
        assertEquals(1, changes)
    }

    @Test
    fun `turning off keeps no switch on and refreshes the library`() {
        scan.setEnabled(true)
        flow.turnOff()
        assertEquals(StorageScanDialog.TURN_OFF, flow.dialog)

        flow.confirmTurnOff()

        assertFalse(scan.enabled.value)
        assertNull(flow.dialog)
        assertEquals(1, changes)
    }

    @Test
    fun `Android 9 and 10 turn on with the storage permission, and say so when it is refused`() {
        flow.onStoragePermissionResult(granted = false, refusedForGood = true)
        assertFalse(scan.enabled.value)
        assertEquals(R.string.settings_full_scan_storage_not_allowed, flow.note)
        assertTrue(flow.storageRefused)

        access = StorageScanAccess.GRANTED
        flow.onStoragePermissionResult(granted = true, refusedForGood = false)
        assertTrue(scan.enabled.value)
        assertFalse(flow.storageRefused)
        assertEquals(1, changes)
    }
}
