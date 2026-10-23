package io.github.sumirenokai.vesqen.audio

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AudioRouteSourceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /**
     * #155: MediaRouter2 sends an app the system's route changes only while the app holds a router
     * registration, which a controller callback alone never creates. Without it the selected route
     * stays whatever the process first read, so Chain kept naming Bluetooth earbuds after Bluetooth
     * was turned off. Real route switches need hardware; this checks the registration they depend on.
     */
    @Test
    fun observingRegistersForSystemRouteUpdatesAndStoppingReleasesIt() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val source = AudioRouteSource(context)
        val listener: (AudioRouteState) -> Unit = {}
        assertTrue("No router registration before observing", awaitRegistration(false))
        instrumentation.runOnMainSync { source.addListener(listener) }
        try {
            assertTrue("Observing must register for route updates", awaitRegistration(true))
        } finally {
            instrumentation.runOnMainSync { source.removeListener(listener) }
        }
        assertTrue("The last listener must release the registration", awaitRegistration(false))
    }

    private fun awaitRegistration(expected: Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            if (hasRouterRecord() == expected) return true
            if (SystemClock.uptimeMillis() > deadline) return false
            SystemClock.sleep(100)
        }
    }

    private fun hasRouterRecord(): Boolean {
        val dump = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("dumpsys media_router"),
        ).bufferedReader().use { it.readText() }
        val routerRecords = dump.substringAfter("Router Records:", "").substringBefore("Manager Records:")
        return Regex("""mPackageName=${Regex.escape(context.packageName)}\s*$""", RegexOption.MULTILINE)
            .containsMatchIn(routerRecords)
    }
}
