package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.sumirenokai.vesqen.usb.session.UsbDeviceIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** D4: the own USB output's hardware volume as the UI, the volume keys and the media session use it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(UnstableApi::class)
class DirectUsbVolumeTest {
    private val context = RuntimeEnvironment.getApplication()
    private val player = ExoPlayer.Builder(context).build()

    // The JBL Flip 7: -60.16 dB to 0 dB in steps of about 0.6 dB.
    private val jbl = DirectUsbVolume(current = -7700, minimum = -15400, maximum = 0, resolution = 154)

    @After
    fun releasePlayer() {
        player.release()
    }

    @Test
    fun `consent survives a replug and recreation only for this vendor and product`() {
        val first = UsbDeviceIdentity(1, 0x1234, 0x5678)
        val memory = PreferencesDirectUsbVolumeMemory(context)
        assertFalse(memory.fixedVolumeConfirmed(first))
        assertTrue(memory.confirmFixedVolume(first))
        val reopened = PreferencesDirectUsbVolumeMemory(context)
        assertTrue(reopened.fixedVolumeConfirmed(first.copy(deviceId = 2)))
        assertFalse(reopened.fixedVolumeConfirmed(first.copy(productId = 12)))
        assertFalse(reopened.fixedVolumeConfirmed(first.copy(vendorId = 13)))
        assertEquals(-30 * 256, reopened.startVolume(first))
    }

    @Test
    fun `fixed remote provider has no adjustment commands and consumes keys without a request`() {
        val control = DirectUsbVolumeControl()
        control.attach { throw AssertionError("fixed output should receive no volume request") }
        val session = DeviceVolumeSessionPlayer(player, control)
        try {
            control.publish(null, fixed = true)
            assertNull(control.state.value)
            assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, session.deviceInfo.playbackType)
            assertTrue(session.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME))
            for (cmd in listOf(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) {
                assertFalse(session.availableCommands.contains(cmd))
                assertFalse(session.isCommandAvailable(cmd))
            }
            assertTrue(control.pressKeys(1))
            session.setDeviceVolume(10, 0)
            session.decreaseDeviceVolume(0)
            session.setDeviceMuted(true, 0)
            assertEquals(0L, control.keyPresses.value)
            control.publish(jbl)
            assertTrue(session.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
            control.publish(null)
            assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, session.deviceInfo.playbackType)
            assertFalse(control.pressKeys(-1))
        } finally {
            session.stopReporting()
        }
    }

    @Test
    fun `a key press is about 2 dB in whole device steps and never leaves the range`() {
        assertEquals(462, jbl.keyStep)
        assertEquals(34, jbl.keySteps)
        assertEquals(-7238, jbl.afterKeys(1))
        assertEquals(-8162, jbl.afterKeys(-1))
        assertEquals(0, jbl.copy(current = -154).afterKeys(1))
        assertEquals(-15400, jbl.copy(current = -15400).afterKeys(-1))
        // A device with 1/256 dB steps still moves 2 dB a press; one with 4 dB steps moves one step.
        assertEquals(512, jbl.copy(resolution = 1).keyStep)
        assertEquals(1024, jbl.copy(resolution = 1024).keyStep)
    }

    @Test
    fun `the session's volume index covers the range with the maximum as the last index`() {
        assertEquals(0, jbl.copy(current = -15400).keyIndex)
        assertEquals(34, jbl.copy(current = 0).keyIndex)
        assertEquals(17, jbl.keyIndex)
        assertEquals(-15400, jbl.atKeyIndex(0))
        assertEquals(-15400 + 17 * 462, jbl.atKeyIndex(17))
        // 34 key steps of 462 would pass the maximum, which the last index stops at.
        assertEquals(0, jbl.atKeyIndex(34))
        assertEquals(0, jbl.atKeyIndex(99))
    }

    @Test
    fun `the control passes requests to the route and reports what it publishes`() {
        val control = DirectUsbVolumeControl()
        val asked = mutableListOf<Int>()
        assertFalse(control.pressKeys(1))
        control.set(-5000)
        assertTrue(asked.isEmpty())

        val setter: (Int) -> Unit = { asked += it }
        control.attach(setter)
        control.publish(jbl)
        assertEquals(jbl, control.state.value)
        assertTrue(control.pressKeys(1))
        assertTrue(control.pressKeys(-1, shown = false))
        control.set(-5000)
        assertEquals("An up and a down press end where they began", listOf(-7238, -7700, -5000), asked)
        assertEquals("Only the press the UI should show is counted", 1L, control.keyPresses.value)

        // A route that is no longer the attached one ends nothing.
        control.detach { }
        assertEquals(jbl, control.state.value)
        control.detach(setter)
        assertNull(control.state.value)
        assertFalse(control.pressKeys(1))
    }

    @Test
    fun `key presses add up while the device has not answered the one before`() {
        val control = DirectUsbVolumeControl()
        val asked = mutableListOf<Int>()
        control.attach { asked += it }
        control.publish(jbl)

        // A held key: the second press comes before the first read-back.
        control.pressKeys(1)
        control.pressKeys(1)
        assertEquals(listOf(-7238, -6776), asked)
        // The first read-back arrives while the second request still waits: the next press builds on the request.
        control.publish(jbl.copy(current = -7238), settled = false)
        control.pressKeys(1)
        assertEquals(-6314, asked.last())

        // Once the device has answered the last request, its read-back is the base again, whatever was asked.
        control.publish(jbl.copy(current = -6468), settled = true)
        control.pressKeys(1)
        assertEquals(-6006, asked.last())

        // A refused write settles on the unchanged level, so the next press asks for the same step again.
        control.publish(jbl.copy(current = -6468), settled = true)
        control.pressKeys(1)
        assertEquals(-6006, asked.last())

        // A press right after a slider request builds on that request, and never leaves the range.
        control.set(-300)
        control.pressKeys(1)
        control.pressKeys(1)
        assertEquals(listOf(-300, 0, 0), asked.takeLast(3))
    }

    @Test
    fun `a takeover starts at the level chosen in this connection or at most at about -30 dB after a replug`() {
        context.getSharedPreferences("direct_usb_volume", Context.MODE_PRIVATE).edit().clear().commit()
        val memory = PreferencesDirectUsbVolumeMemory(context)
        val connected = UsbDeviceIdentity(deviceId = 1003, vendorId = 0x0ecb, productId = 0x2107)
        assertEquals(-7680, memory.startVolume(connected))

        memory.remember(connected, -2560)
        assertEquals("The same connection gets the listener's level back", -2560, memory.startVolume(connected))

        val replugged = connected.copy(deviceId = 1004)
        assertEquals("A louder remembered level does not survive a replug", -7680, memory.startVolume(replugged))
        memory.forgetConnection()
        assertEquals(-7680, memory.startVolume(connected))

        memory.remember(replugged, -10240)
        memory.forgetConnection()
        assertEquals("A quieter remembered level does", -10240, PreferencesDirectUsbVolumeMemory(context).startVolume(connected))
        assertEquals("Another model starts at about -30 dB", -7680, memory.startVolume(UsbDeviceIdentity(1005, 0x1234, 0x5678)))
    }

    @Test
    fun `the session player reports remote volume only while the own output holds a DAC`() {
        val control = DirectUsbVolumeControl()
        val asked = mutableListOf<Int>()
        control.attach { asked += it }
        val sessionPlayer = DeviceVolumeSessionPlayer(player, control)
        val infos = mutableListOf<Int>()
        val commands = mutableListOf<Player.Commands>()
        val volumes = mutableListOf<Int>()
        sessionPlayer.addListener(object : Player.Listener {
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                infos += deviceInfo.playbackType
            }

            override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
                volumes += volume
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                commands += availableCommands
            }
        })
        idle()
        infos.clear()
        volumes.clear()
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, sessionPlayer.deviceInfo.playbackType)
        assertFalse(sessionPlayer.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))

        control.publish(jbl)
        assertEquals(listOf(DeviceInfo.PLAYBACK_TYPE_REMOTE), infos)
        // Without these the media session's volume is fixed and it takes no volume key (iQOO 9, 2026-11-10).
        val deviceVolumeCommands = listOf(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
        deviceVolumeCommands.forEach { command ->
            assertTrue(sessionPlayer.isCommandAvailable(command))
            assertTrue(commands.last().contains(command))
        }
        assertEquals(listOf(17), volumes)
        assertEquals(34, sessionPlayer.deviceInfo.maxVolume)
        assertEquals(17, sessionPlayer.deviceVolume)
        assertFalse(sessionPlayer.isDeviceMuted)

        // The media session's volume provider: a key press, and an absolute index.
        sessionPlayer.increaseDeviceVolume(0)
        sessionPlayer.decreaseDeviceVolume(0)
        sessionPlayer.setDeviceVolume(34, 0)
        sessionPlayer.setDeviceMuted(true, 0)
        assertEquals(listOf(-7238, -7700, 0), asked)
        assertEquals("A session key press is not shown by the app's overlay", 0L, control.keyPresses.value)

        // Less than one key step is no new index for the session.
        control.publish(jbl.copy(current = -7546))
        assertEquals(listOf(17), volumes)
        control.publish(jbl.copy(current = -7238))
        assertEquals(listOf(17, 18), volumes)

        control.publish(null)
        assertEquals(listOf(DeviceInfo.PLAYBACK_TYPE_REMOTE, DeviceInfo.PLAYBACK_TYPE_LOCAL), infos)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, sessionPlayer.deviceInfo.playbackType)
        assertFalse(commands.last().contains(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
        sessionPlayer.stopReporting()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
