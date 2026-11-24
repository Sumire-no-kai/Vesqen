package io.github.sumirenokai.vesqen.playback

import android.media.AudioDeviceInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D13 (#333): the measured keep-alive track is accepted as configured and plays on the speaker. */
class SpeakerKeepAliveTrackDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun theTrackPlaysOnTheBuiltInSpeakerUntilClosed() {
        val track = checkNotNull(SpeakerKeepAliveTrack.start(context)) { "The keep-alive track did not start" }
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (track.routedDeviceType() != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, track.routedDeviceType())
            assertTrue(track.playing())
        } finally {
            track.close()
        }
    }
}
