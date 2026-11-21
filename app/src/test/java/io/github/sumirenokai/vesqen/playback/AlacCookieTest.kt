package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #270: the cookie Qualcomm's ALAC decoders accept, built from the box Media3 passes. */
class AlacCookieTest {
    @Test
    fun `the box becomes the cookie the phone's own extractor reports`() {
        // Recorded on an iQOO 9 for two files: Media3's box, and the csd-0 of Android's MediaExtractor.
        // `apple.m4a` came from afconvert, `tagged.m4a` from ffmpeg.
        val recordings = listOf(
            "00000024616c616300000000000010000010280a0e0200ff0000079b0002612b0000ac44" to
                "001000000010280a0e02ff009b0700002b61020044ac0000",
            "00000024616c616300000000000010000010280a0e02000000004004001588800000ac44" to
                "001000000010280a0e020000044000008088150044ac0000",
        )
        for ((box, platform) in recordings) {
            assertArrayEquals(box, hex(platform), alacLittleEndianCookie(hex(box)))
        }
    }

    @Test
    fun `anything but a version 0 alac box of 36 bytes is left alone`() {
        val box = hex("00000024616c616300000000000010000010280a0e0200ff0000079b0002612b0000ac44")
        assertNull(alacLittleEndianCookie(box.copyOfRange(12, 36)))
        assertNull(alacLittleEndianCookie(box + 0))
        assertNull(alacLittleEndianCookie(box.copyOf().also { it[3] = 0x25 }))
        assertNull(alacLittleEndianCookie(box.copyOf().also { it[7] = 'd'.code.toByte() }))
        assertNull(alacLittleEndianCookie(box.copyOf().also { it[8] = 1 }))
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
