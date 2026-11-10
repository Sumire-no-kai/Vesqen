package io.github.sumirenokai.vesqen.playback

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class UsbCommunicationMonitorTest {
    private val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
    private val main = Looper.getMainLooper()
    private val events = mutableListOf<Boolean>()
    private val monitor = UsbCommunicationMonitor(audio, Handler(main), events::add)

    @Test
    @Config(sdk = [28])
    fun legacyModeChecksStopWhenDisabled() {
        monitor.setEnabled(true)
        audio.mode = AudioManager.MODE_RINGTONE
        shadowOf(main).idleFor(Duration.ofMillis(250))
        audio.mode = AudioManager.MODE_IN_CALL
        shadowOf(main).idleFor(Duration.ofMillis(250))
        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(main).idleFor(Duration.ofMillis(250))
        assertEquals(listOf(true, false), events)
        monitor.close()
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        shadowOf(main).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(true, false), events)
    }

    @Test
    @Config(sdk = [35])
    fun modernModeCallbacksIncludeAnExistingCallAndUnregisterOnClose() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        monitor.setEnabled(true)
        assertEquals(listOf(true), events)
        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(main).idle()
        assertEquals(listOf(true, false), events)
        monitor.close()
        audio.mode = AudioManager.MODE_IN_CALL
        shadowOf(main).idle()
        assertEquals(listOf(true, false), events)
    }
}
