package io.github.sumirenokai.vesqen.playback

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #270: the empty output buffer Qualcomm's ALAC decoders send before any input never reaches Media3. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(UnstableApi::class)
class EmptyOutputDroppingAdapterTest {
    private val released = mutableListOf<Int>()

    @Test
    fun `an empty buffer without flags is released and the next one returned`() {
        val adapter = EmptyOutputDroppingAdapter(
            fakeAdapter(Output(0, 0, 0), Output(1, 4096, 0), Output(2, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)),
        )
        val info = MediaCodec.BufferInfo()
        assertEquals(1, adapter.dequeueOutputBufferIndex(info))
        assertEquals(4096, info.size)
        assertEquals(listOf(0), released)
        // The end of the stream is empty too, but it carries a flag Media3 needs.
        assertEquals(2, adapter.dequeueOutputBufferIndex(info))
        assertEquals(MediaCodec.BUFFER_FLAG_END_OF_STREAM, info.flags)
        assertEquals(MediaCodec.INFO_TRY_AGAIN_LATER, adapter.dequeueOutputBufferIndex(info))
        assertEquals(listOf(0), released)
    }

    @Test
    fun `only Qualcomm's ALAC decoders get the dropping adapter`() {
        val plain = fakeAdapter()
        val factory = EmptyOutputDroppingAdapterFactory { plain }
        fun adapterFor(name: String) = factory.createAdapter(
            MediaCodecAdapter.Configuration.createForAudioDecoding(
                MediaCodecInfo.newInstance(name, "audio/alac", "audio/alac", null, false, true, true, false, false),
                MediaFormat(), Format.Builder().build(), null, null,
            ),
        )
        assertTrue(adapterFor("c2.qti.alac.sw.decoder") is EmptyOutputDroppingAdapter)
        assertSame(plain, adapterFor("c2.vivo.alac.decoder"))
        assertSame(plain, adapterFor("c2.android.flac.decoder"))
    }

    private data class Output(val index: Int, val size: Int, val flags: Int)

    /** Hands out [outputs] in order, then reports that none is ready; anything else is unexpected. */
    private fun fakeAdapter(vararg outputs: Output): MediaCodecAdapter {
        val pending = ArrayDeque(outputs.toList())
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MediaCodecAdapter::class.java)) { _, method, args ->
            when (method.name) {
                "dequeueOutputBufferIndex" -> pending.removeFirstOrNull()?.let { output ->
                    (args[0] as MediaCodec.BufferInfo).set(0, output.size, 0L, output.flags)
                    output.index
                } ?: MediaCodec.INFO_TRY_AGAIN_LATER
                "releaseOutputBuffer" -> released += args[0] as Int
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "toString" -> "fake adapter"
                else -> error("Unexpected call to ${method.name}")
            }
        } as MediaCodecAdapter
    }
}
