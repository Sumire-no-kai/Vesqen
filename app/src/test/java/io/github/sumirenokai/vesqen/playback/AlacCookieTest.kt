package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #270: the config Qualcomm's ALAC decoders accept, built from the one Media3 passes. */
class AlacCookieTest {
    @Test
    fun `Media3's config becomes the one the phone's own extractor reports`() {
        // Recorded on an iQOO 9 for two 44.1 kHz files: the config as the file stores it, which Media3
        // passes, and the csd-0 of Android's MediaExtractor. afconvert wrote the first, ffmpeg the second.
        val recordings = listOf(
            "000010000010280a0e0200ff0000079b0002612b0000ac44" to "001000000010280a0e02ff009b0700002b61020044ac0000",
            "000010000010280a0e02000000004004001588800000ac44" to "001000000010280a0e020000044000008088150044ac0000",
        )
        for ((stored, platform) in recordings) {
            assertArrayEquals(stored, hex(platform), alacLittleEndianCookie(hex(stored), 44_100))
        }
    }

    @Test
    fun `a config already little-endian, of another rate or another size is left alone`() {
        val stored = hex("000010000010280a0e0200ff0000079b0002612b0000ac44")
        assertNull(alacLittleEndianCookie(alacLittleEndianCookie(stored, 44_100)!!, 44_100))
        assertNull(alacLittleEndianCookie(stored, 48_000))
        assertNull(alacLittleEndianCookie(stored.copyOf(23), 44_100))
        assertNull(alacLittleEndianCookie(byteArrayOf(0, 0, 0, 0x24, 0x61, 0x6c, 0x61, 0x63, 0, 0, 0, 0) + stored, 44_100))
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
