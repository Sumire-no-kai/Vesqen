package io.github.sumirenokai.vesqen.playback

import androidx.media3.exoplayer.audio.AudioOutput
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictGatedAudioOutputTest {
    @Test
    fun `pcm writes stay blocked while the route probe is allowed to start`() {
        val delegatedVolumes = mutableListOf<Float>()
        val delegatedWrites = mutableListOf<Int>()
        val playbackEvents = mutableListOf<String>()
        var reverificationRequests = 0
        val output = StrictGatedAudioOutput(
            delegateOutput = recordingAudioOutput(delegatedVolumes, delegatedWrites, playbackEvents),
            onGatedPlay = { reverificationRequests++ },
        )
        val pcm = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))

        assertFalse(output.isReadyForActivation())
        output.play()
        assertEquals(listOf("play"), playbackEvents)
        assertEquals(1, reverificationRequests)
        assertTrue(output.isReadyForActivation())
        assertFalse(output.write(pcm, 1, 0))
        assertTrue(delegatedWrites.isEmpty())

        output.setVolume(1f)
        assertTrue(output.activate())
        assertEquals(listOf("play", "play"), playbackEvents)
        assertTrue(output.write(pcm, 1, 0))
        assertEquals(listOf(1), delegatedWrites)

        output.pause()
        assertFalse(output.isReadyForActivation())
        output.play()
        assertEquals(listOf("play", "play", "pause", "play"), playbackEvents)
        assertEquals(2, reverificationRequests)
        assertFalse(output.write(pcm, 1, 0))
        assertEquals(listOf(1), delegatedWrites)

        assertTrue(output.activate())
        assertEquals("play", playbackEvents.last())
        assertTrue(output.write(pcm, 1, 0))
        assertEquals(listOf(1, 1), delegatedWrites)

        output.mute()
        output.play()
        assertEquals(listOf("play", "play", "pause", "play", "play", "play"), playbackEvents)
        assertEquals(3, reverificationRequests)
        assertFalse(output.write(pcm, 1, 0))
        assertEquals(listOf(1, 1), delegatedWrites)
        assertTrue(output.activate())
        assertTrue(output.write(pcm, 1, 0))
        assertEquals(listOf(1, 1, 1), delegatedWrites)
    }

    @Test
    fun `non neutral sink volume permanently invalidates that output instance`() {
        val delegatedVolumes = mutableListOf<Float>()
        val violations = mutableListOf<Float>()
        var reverificationRequests = 0
        val output = StrictGatedAudioOutput(
            delegateOutput = recordingAudioOutput(delegatedVolumes),
            onGatedPlay = { reverificationRequests++ },
            onNonNeutralVolume = violations::add,
        )

        assertEquals(listOf(0f), delegatedVolumes)
        output.setVolume(1f)
        output.play()
        assertTrue(output.activate())
        assertEquals(listOf(0f, 0f, 1f), delegatedVolumes)
        assertEquals(1, reverificationRequests)

        output.setVolume(0.2f)

        assertEquals(0f, delegatedVolumes.last())
        assertEquals(listOf(0.2f), violations)
        assertFalse(output.isReadyForActivation())
        assertFalse(output.activate())
        assertEquals(0f, delegatedVolumes.last())

        output.setVolume(1f)
        assertEquals(0f, delegatedVolumes.last())
        assertFalse(output.activate())
        assertEquals(0f, delegatedVolumes.last())

        output.mute()
        assertEquals(0f, delegatedVolumes.last())
        output.setVolume(1f)
        assertEquals(0f, delegatedVolumes.last())
        assertFalse(output.activate())
        assertEquals(0f, delegatedVolumes.last())
        output.play()
        assertEquals(1, reverificationRequests)
    }

    @Test
    fun `failed activation keeps pcm writes gated`() {
        var failPlay = false
        val delegatedWrites = mutableListOf<Int>()
        val delegate = Proxy.newProxyInstance(
            AudioOutput::class.java.classLoader,
            arrayOf(AudioOutput::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "play" -> if (failPlay) throw IllegalStateException("play failed")
                "write" -> {
                    delegatedWrites += arguments?.get(1) as Int
                    return@newProxyInstance true
                }
            }
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as AudioOutput
        val output = StrictGatedAudioOutput(delegate)
        val pcm = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))

        output.play()
        failPlay = true

        assertFalse(output.activate())
        assertFalse(output.write(pcm, 1, 0))
        assertTrue(delegatedWrites.isEmpty())
    }

    private fun recordingAudioOutput(
        volumes: MutableList<Float>,
        writes: MutableList<Int> = mutableListOf(),
        playbackEvents: MutableList<String> = mutableListOf(),
    ): AudioOutput =
        Proxy.newProxyInstance(
            AudioOutput::class.java.classLoader,
            arrayOf(AudioOutput::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "setVolume" -> volumes += arguments?.single() as Float
                "write" -> {
                    writes += arguments?.get(1) as Int
                    return@newProxyInstance true
                }
                "play", "pause" -> playbackEvents += method.name
            }
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as AudioOutput
}
