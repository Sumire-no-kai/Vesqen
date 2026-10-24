package io.github.sumirenokai.vesqen.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteStateTest {
    @Test
    fun `a Bluetooth or wired route needs that output to be connected`() {
        // #155: right after Chain opens, MediaRouter2 can still name the earbuds Bluetooth was turned off for.
        val speakerOnly = setOf(AudioOutputType.PHONE_SPEAKER)
        assertFalse(ActiveAudioRoute("WF-1000XM4", AudioOutputType.BLUETOOTH).couldBeSelectedWith(speakerOnly))
        assertFalse(ActiveAudioRoute("USB DAC", AudioOutputType.WIRED_OR_USB).couldBeSelectedWith(speakerOnly))
        assertTrue(
            ActiveAudioRoute("WF-1000XM4", AudioOutputType.BLUETOOTH)
                .couldBeSelectedWith(speakerOnly + AudioOutputType.BLUETOOTH),
        )
        // The speaker is always there, and a route without a type cannot be checked.
        assertTrue(ActiveAudioRoute("Phone", AudioOutputType.PHONE_SPEAKER).couldBeSelectedWith(speakerOnly))
        assertTrue(ActiveAudioRoute("WF-1000XM4", null).couldBeSelectedWith(speakerOnly))
    }
}
