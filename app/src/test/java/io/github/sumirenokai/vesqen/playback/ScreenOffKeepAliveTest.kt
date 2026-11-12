package io.github.sumirenokai.vesqen.playback

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** D13 (#333): the keep-alive track runs only while switched on, delivering and with the screen off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenOffKeepAliveTest {
    private val context = RuntimeEnvironment.getApplication()
    private val main = Looper.getMainLooper()
    private val power = shadowOf(context.getSystemService(PowerManager::class.java))
    private var started = 0
    private var closed = 0
    private var refuse = false
    private val keepAlive = ScreenOffKeepAlive {
        if (refuse) null else AutoCloseable { closed++ }.also { started++ }
    }
    private var controller: ScreenOffKeepAliveController? = null

    @After fun close() {
        controller?.close()
    }

    @Test fun startsOnlyWhenAllThreeConditionsHold() {
        keepAlive.update(enabled = true, delivering = true, screenOn = true)
        keepAlive.update(enabled = false, delivering = true, screenOn = false)
        keepAlive.update(enabled = true, delivering = false, screenOn = false)
        assertEquals(0, started)
        keepAlive.update(enabled = true, delivering = true, screenOn = false)
        assertEquals(1, started)
        assertTrue(keepAlive.running)
    }

    @Test fun eachConditionEndingStopsItAtOnce() {
        for (end in listOf<(ScreenOffKeepAlive) -> Unit>(
            { it.update(screenOn = true) },
            { it.update(delivering = false) },
            { it.update(enabled = false) },
            ScreenOffKeepAlive::close,
        )) {
            keepAlive.update(enabled = true, delivering = true, screenOn = false)
            val before = closed
            end(keepAlive)
            assertEquals(before + 1, closed)
            assertFalse(keepAlive.running)
            keepAlive.update(enabled = false, delivering = false, screenOn = true)
        }
    }

    @Test fun repeatedReportsDoNotRestartTheTrack() {
        keepAlive.update(enabled = true, delivering = true, screenOn = false)
        repeat(3) { keepAlive.update(delivering = true) }
        assertEquals(1, started)
        assertEquals(0, closed)
    }

    @Test fun aRefusedTrackWaitsForTheNextChange() {
        refuse = true
        keepAlive.update(enabled = true, delivering = true, screenOn = false)
        refuse = false
        keepAlive.update(delivering = true)
        assertEquals(0, started)
        keepAlive.update(screenOn = true)
        keepAlive.update(screenOn = false)
        assertEquals(1, started)
    }

    @Test fun theControllerFollowsTheScreenTheStatusAndTheSwitch() {
        power.setIsInteractive(true)
        val control = ScreenOffKeepAliveController(context, Handler(main), keepAlive).also { controller = it }
        control.setEnabled(true)
        control.statusListener(direct(UsbOutputPhase.ACTIVE, "direct_usb.active"))
        shadowOf(main).idle()
        assertEquals("Not with the screen on", 0, started)

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(main).idle()
        assertEquals(1, started)

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(main).idle()
        assertEquals(1, closed)

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(main).idle()
        assertEquals(2, started)
        control.statusListener(direct(UsbOutputPhase.APPLYING, "direct_usb.configured_not_playing"))
        shadowOf(main).idle()
        assertEquals("A pause stops it", 2, closed)

        control.statusListener(direct(UsbOutputPhase.ACTIVE, "direct_usb.active"))
        shadowOf(main).idle()
        assertEquals(3, started)
        control.setEnabled(false)
        shadowOf(main).idle()
        assertEquals(3, closed)
    }

    @Test fun theControllerStartsFromTheCurrentScreenState() {
        power.setIsInteractive(false)
        val control = ScreenOffKeepAliveController(context, Handler(main), keepAlive).also { controller = it }
        control.setEnabled(true)
        control.statusListener(direct(UsbOutputPhase.ACTIVE, "direct_usb.active"))
        shadowOf(main).idle()
        assertEquals(1, started)
    }

    @Test fun theOfficialPathNeverNeedsIt() {
        power.setIsInteractive(false)
        val control = ScreenOffKeepAliveController(context, Handler(main), keepAlive).also { controller = it }
        control.setEnabled(true)
        control.statusListener(direct(UsbOutputPhase.ACTIVE, "strict_usb.active"))
        shadowOf(main).idle()
        assertEquals(0, started)
    }

    @Test fun aClosedControllerIgnoresTheScreen() {
        power.setIsInteractive(true)
        val control = ScreenOffKeepAliveController(context, Handler(main), keepAlive)
        control.setEnabled(true)
        control.statusListener(direct(UsbOutputPhase.ACTIVE, "direct_usb.active"))
        shadowOf(main).idle()
        control.close()
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(main).idle()
        assertEquals(0, started)
    }

    @Test fun theSwitchIsOffByDefaultAndPersists() {
        val settings = ScreenOffKeepAliveSettings(context, vivoFamily = false)
        assertFalse(settings.enabled.value)
        settings.setEnabled(true)
        assertTrue(ScreenOffKeepAliveSettings(context, vivoFamily = false).enabled.value)
    }

    @Test fun guidanceIsDueOnceAndOnlyOnVivoFamilyPhones() {
        assertFalse(ScreenOffKeepAliveSettings(context, vivoFamily = false).guidanceDue.value)
        val vivo = ScreenOffKeepAliveSettings(context, vivoFamily = true)
        assertTrue(vivo.guidanceDue.value)
        vivo.finishGuidance()
        assertFalse(vivo.guidanceDue.value)
        assertFalse(ScreenOffKeepAliveSettings(context, vivoFamily = true).guidanceDue.value)
    }

    @Test fun vivoFamilyIncludesIqooPhones() {
        assertTrue(isVivoFamily(manufacturer = "vivo", brand = "iQOO"))
        assertTrue(isVivoFamily(manufacturer = "vivo", brand = "vivo"))
        assertTrue(isVivoFamily(manufacturer = "VIVO", brand = "unknown"))
        assertFalse(isVivoFamily(manufacturer = "HONOR", brand = "HONOR"))
        assertFalse(isVivoFamily(manufacturer = "Xiaomi", brand = "Redmi"))
    }

    private fun direct(phase: UsbOutputPhase, code: String) = UsbOutputStatus(
        mode = UsbOutputMode.STRICT_BIT_PERFECT,
        phase = phase,
        decisionCode = code,
    )
}
