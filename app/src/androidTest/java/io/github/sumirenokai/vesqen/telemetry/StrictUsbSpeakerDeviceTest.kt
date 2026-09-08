package io.github.sumirenokai.vesqen.telemetry

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.VesqenApplication
import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import io.github.sumirenokai.vesqen.playback.PlaybackController
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Run explicitly on API 34+ with no USB audio output. The lab preserves the user checkpoint. */
class StrictUsbSpeakerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as VesqenApplication
    private val playback = AtomicReference(PlaybackSnapshot())
    private lateinit var controller: PlaybackController

    @Test
    fun noUsbFailsClosedAndSystemPlaybackRecovers() = runBlocking {
        assertTrue("This acceptance case requires Android 14+", Build.VERSION.SDK_INT >= 34)
        val usbTypes = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY)
        assertFalse("Disconnect USB audio before this speaker case",
            app.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type in usbTypes })
        val statuses = CopyOnWriteArrayList<UsbOutputStatus>()
        val listener: (UsbOutputStatus) -> Unit = { statuses.add(it) }
        val fixture = speakerFixture(app, 48_000, 24)
        app.usbOutputStateRepository.addListener(listener)
        try {
            connect()
            main { controller.setUsbOutputMode(UsbOutputMode.SYSTEM) }
            awaitPlayback { it.usbOutputStatus.mode == UsbOutputMode.SYSTEM }
            main { controller.playQueue(listOf(fixture), 0) }
            awaitPlayback { it.isPlaying && it.trackId == fixture.id }
            repeat(8) {
                val previousGeneration = playback.get().usbOutputStatus.generation
                main { controller.setUsbOutputMode(UsbOutputMode.STRICT_BIT_PERFECT) }
                awaitPlayback {
                    it.usbOutputStatus.generation > previousGeneration &&
                        it.usbOutputStatus.phase == UsbOutputPhase.FAILED && !it.showsPauseAction && !it.isPlaying
                }
                val failed = playback.get().usbOutputStatus
                assertEquals(UsbOutputFailure.NO_USB_AUDIO_DEVICE, failed.failure)
                assertEquals(OutputDeclaration.BIT_PERFECT_FAILED, failed.declaration)
                assertEquals(48_000, failed.sourceFormat?.sampleRateHz)
                assertNull(failed.sinkFormat)
                assertEquals(failed, app.usbOutputStateRepository.snapshot())

                // A newly connected controller must receive the service failure, not a SYSTEM default.
                main { controller.release() }
                connect()
                awaitPlayback { it.usbOutputStatus.generation >= failed.generation }
                assertEquals(UsbOutputFailure.NO_USB_AUDIO_DEVICE, playback.get().usbOutputStatus.failure)
                main { controller.setUsbOutputMode(UsbOutputMode.SYSTEM) }
                awaitPlayback { it.usbOutputStatus.phase == UsbOutputPhase.SYSTEM }
                main { controller.togglePlayback() }
                awaitPlayback { it.isPlaying && it.trackId == fixture.id }
                assertEquals(OutputDeclaration.SYSTEM_MIXED, playback.get().usbOutputStatus.declaration)
            }
            // Queue several commands without waiting for an intermediate state. The last request wins.
            main {
                repeat(8) {
                    controller.setUsbOutputMode(UsbOutputMode.STRICT_BIT_PERFECT)
                    controller.setUsbOutputMode(UsbOutputMode.SYSTEM)
                }
                controller.setUsbOutputMode(UsbOutputMode.STRICT_BIT_PERFECT)
            }
            awaitPlayback { it.usbOutputStatus.phase == UsbOutputPhase.FAILED && !it.isPlaying }
            assertEquals(UsbOutputFailure.NO_USB_AUDIO_DEVICE, playback.get().usbOutputStatus.failure)
            assertTrue(statuses.zipWithNext().all { (before, after) -> after.generation > before.generation })
            assertFalse(statuses.any { it.phase == UsbOutputPhase.ACTIVE || it.phase == UsbOutputPhase.AVAILABLE })
        } finally {
            if (::controller.isInitialized) {
                main { controller.setUsbOutputMode(UsbOutputMode.SYSTEM) }
                awaitPlayback { it.usbOutputStatus.phase == UsbOutputPhase.SYSTEM }
                main { controller.clearQueue(); controller.release() }
            }
            app.usbOutputStateRepository.removeListener(listener)
            val artifacts = File(app.filesDir, "m3-speaker-acceptance").apply { mkdirs() }
            File(artifacts, "usb-states.txt").writeText(statuses.joinToString("\n"))
        }
    }

    private suspend fun connect() {
        playback.set(PlaybackSnapshot())
        main { controller = PlaybackController(app) { playback.set(it) } }
        awaitPlayback { it.isControllerReady }
    }

    private suspend fun awaitPlayback(predicate: (PlaybackSnapshot) -> Boolean) = withTimeout(15_000) {
        while (true) {
            main { controller.refreshPosition() }
            if (predicate(playback.get())) break
            delay(50)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
