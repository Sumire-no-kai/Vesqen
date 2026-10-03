package io.github.sumirenokai.vesqen.telemetry

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.audio.AudioOutputType
import io.github.sumirenokai.vesqen.audio.AudioRouteSource
import io.github.sumirenokai.vesqen.playback.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class StrictUsbStartupDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()

    /** Run in a fresh instrumentation process, with no USB audio output attached. */
    @Test
    fun pausedRecoveryRetainsFailureOriginUntilAnExplicitPlayCommand() = runBlocking {
        val outputs = app.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val usbTypes = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY)
        assertFalse("Disconnect USB audio for this case", outputs.any { it.type in usbTypes })
        assertEquals("Run in a fresh instrumentation process", 0L, app.usbOutputStateRepository.snapshot().generation)
        val fixture = speakerFixture(app, 48_000, 24)
        val preferences = app.getSharedPreferences("usb_output_preferences", 0)
        assertTrue(preferences.edit().putString("mode", UsbOutputMode.STRICT_BIT_PERFECT.name).commit())
        PlaybackStateStore(app).save(PersistedPlaybackState(
            listOf(fixture.id, fixture.id), fixture.id, 12_345, true, PlaybackRepeatMode.ALL, 1,
        ))
        val snapshots = AtomicReference(PlaybackSnapshot())
        var controller: PlaybackController? = null
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        suspend fun await(predicate: (PlaybackSnapshot) -> Boolean) = withTimeout(15_000) {
            while (!predicate(snapshots.get())) {
                main { controller?.refreshPosition() }
                delay(25)
            }
        }
        try {
            main { controller = PlaybackController(app) { snapshots.set(it) } }
            await { it.isControllerReady }
            main { controller!!.syncLibrary(listOf(fixture)) }
            await { it.usbOutputStatus.phase == UsbOutputPhase.FAILED &&
                it.usbOutputStatus.failureOrigin == UsbOutputFailureOrigin.QUEUE_RESTORE }
            val recovered = snapshots.get()
            assertFalse(recovered.isPlaying)
            assertFalse(recovered.showsPauseAction)
            assertEquals(12_345L, recovered.positionMs)
            assertEquals(if (Build.VERSION.SDK_INT >= 34) UsbOutputFailure.NO_USB_AUDIO_DEVICE
                else UsbOutputFailure.UNSUPPORTED_ANDROID_VERSION, recovered.usbOutputStatus.failure)
            main { controller!!.togglePlayback() }
            await { it.usbOutputStatus.phase == UsbOutputPhase.FAILED &&
                it.usbOutputStatus.failureOrigin == UsbOutputFailureOrigin.USER_PLAYBACK &&
                it.usbOutputStatus.generation > recovered.usbOutputStatus.generation }
            assertFalse(snapshots.get().isPlaying)
            assertFalse(snapshots.get().showsPauseAction)
            val route = AudioRouteSource(app).readState()
            if (Build.VERSION.SDK_INT >= 34) assertEquals(AudioOutputType.PHONE_SPEAKER, route.activeRoute?.outputType)
            File(app.filesDir, "strict-startup-evidence.txt").writeText(
                "recoveryOrigin=${recovered.usbOutputStatus.failureOrigin}\n" +
                    "playOrigin=${snapshots.get().usbOutputStatus.failureOrigin}\n" +
                    "failure=${snapshots.get().usbOutputStatus.failure}\n" +
                    "selectedRouteType=${route.activeRoute?.outputType}\n" +
                    "usbOutputCount=${outputs.count { it.type in usbTypes }}\n",
            )
        } finally {
            main { controller?.setUsbOutputMode(UsbOutputMode.SYSTEM) }
            if (controller != null) await { it.usbOutputStatus.mode == UsbOutputMode.SYSTEM }
            main { controller?.clearQueue(); controller?.release() }
        }
    }
}
