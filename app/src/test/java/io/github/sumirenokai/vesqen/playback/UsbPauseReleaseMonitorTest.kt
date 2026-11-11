package io.github.sumirenokai.vesqen.playback

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsbPauseReleaseMonitorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val main = Looper.getMainLooper()
    private var releases = 0
    private val monitor = UsbPauseReleaseMonitor(context, Handler(main)) { releases++ }
    private val power = shadowOf(context.getSystemService(PowerManager::class.java))

    @After fun close() = monitor.close()

    @Test fun aShortPauseCancelsItsDeadlineAndANewPauseGetsThirtySeconds() {
        power.setIsInteractive(true)
        monitor.setPaused(true)
        shadowOf(main).idleFor(Duration.ofSeconds(29))
        assertEquals(0, releases)
        monitor.setPaused(false)
        monitor.setPaused(true)
        shadowOf(main).idleFor(Duration.ofSeconds(29))
        assertEquals(0, releases)
        shadowOf(main).idleFor(Duration.ofSeconds(1))
        assertEquals(1, releases)
        shadowOf(main).idleFor(Duration.ofSeconds(30))
        assertEquals(1, releases)
    }

    @Test fun pausingAfterTheScreenTurnsOffReleasesImmediately() {
        power.setIsInteractive(false)
        monitor.setPaused(true)
        assertEquals(1, releases)
    }

    @Test fun turningTheScreenOffDuringPauseReleasesAndCancelsTheDeadline() {
        power.setIsInteractive(true)
        monitor.setPaused(true)
        power.setIsInteractive(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(main).idle()
        assertEquals(1, releases)
        shadowOf(main).idleFor(Duration.ofSeconds(30))
        assertEquals(1, releases)
    }

    @Test fun screenOffStillReleasesWhenTheScreenWakesBeforeDelivery() {
        power.setIsInteractive(true)
        monitor.setPaused(true)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(main).idle()
        assertEquals(1, releases)
    }

    @Test fun otherPlaybackReleasesOnlyWhileTheMonitorIsArmed() {
        power.setIsInteractive(true)
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        monitor.setPaused(true)
        audio.setActivePlaybackConfigurationsFor(listOf(AudioAttributes.Builder().build()), true)
        shadowOf(main).idle()
        assertEquals(1, releases)
        monitor.setPaused(true)
        assertEquals(2, releases)
        monitor.close()
        audio.setActivePlaybackConfigurationsFor(listOf(AudioAttributes.Builder().build()), true)
        shadowOf(main).idle()
        assertEquals(2, releases)
    }
}
