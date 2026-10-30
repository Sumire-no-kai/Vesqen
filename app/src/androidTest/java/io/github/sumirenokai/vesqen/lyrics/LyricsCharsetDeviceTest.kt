package io.github.sumirenokai.vesqen.lyrics

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #169: lyrics detection relies on the platform's charset tables (Android's ICU, not the JDK the
 * unit tests run on). The bytes below were encoded with JDK 25; the phone must read them back to the
 * same text and refuse the Korean and EUC-JP files, on API 28 as on current releases.
 */
@RunWith(AndroidJUnit4::class)
class LyricsCharsetDeviceTest {
    @Test
    fun detectionCharsetsExist() {
        assertTrue(Charset.isSupported("GB18030"))
        assertTrue(Charset.isSupported("Big5"))
        assertTrue(Charset.isSupported("windows-31j") || Charset.isSupported("Shift_JIS"))
        assertTrue(Charset.isSupported("EUC-JP"))
        assertTrue(Charset.isSupported("EUC-KR"))
        assertTrue(Charset.isSupported("windows-1252"))
    }

    @Test
    fun phoneTablesEncodeTheSamplesLikeTheJdk() {
        assertArrayEquals(GB18030.bytes(), SIMPLIFIED.toByteArray(Charset.forName("GB18030")))
        assertArrayEquals(BIG5.bytes(), TRADITIONAL.toByteArray(Charset.forName("Big5")))
        val shiftJis = Charset.forName(if (Charset.isSupported("windows-31j")) "windows-31j" else "Shift_JIS")
        assertArrayEquals(SHIFT_JIS.bytes(), JAPANESE.toByteArray(shiftJis))
    }

    @Test
    fun legacySamplesDecodeToTheirText() {
        assertEquals(SIMPLIFIED, decodeLyricsText(GB18030.bytes()))
        assertEquals(TRADITIONAL, decodeLyricsText(BIG5.bytes()))
        assertEquals(JAPANESE, decodeLyricsText(SHIFT_JIS.bytes()))
        assertEquals(SIMPLIFIED, decodeLatinLabelledLyricsText(GB18030.bytes()))
    }

    @Test
    fun koreanAndEucJpSamplesAreUnreadable() {
        assertNull(decodeLyricsText(EUC_KR.bytes()))
        assertNull(decodeLyricsText(EUC_JP.bytes()))
        assertNull(decodeLatinLabelledLyricsText(EUC_KR.bytes()))
    }

    private fun String.bytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        // Sample lines written for these tests.
        const val SIMPLIFIED = "[00:01.00]晚风吹过安静的街道\n[00:05.00]我们沿着河岸慢慢走\n" +
            "[00:09.00]灯光落在水面上，像一首没唱完的歌\n[00:13.00]明天还会再见面吗？"
        const val TRADITIONAL = "[00:01.00]晚風吹過安靜的街道\n[00:05.00]我們沿著河岸慢慢走\n" +
            "[00:09.00]燈光落在水面上，像一首沒唱完的歌\n[00:13.00]明天還會再見面嗎？"
        const val JAPANESE = "[00:01.00]夕暮れの駅で君を待つ\n[00:05.00]静かな雨が降っている\n" +
            "[00:09.00]明日もきっと晴れるだろう"

        const val GB18030 =
            "5b30303a30312e30305dcdedb7e7b4b5b9fdb0b2beb2b5c4bdd6b5c00a5b30303a30352e30305dced2c3c7d1d8d7c5ba" +
                "d3b0b6c2fdc2fdd7df0a5b30303a30392e30305db5c6b9e2c2e4d4dacbaec3e6c9cfa3accff1d2bbcad7c3bbb3aacdea" +
                "b5c4b8e80a5b30303a31332e30305dc3f7ccecbbb9bbe1d4d9bcfbc3e6c2f0a3bf"
        const val BIG5 =
            "5b30303a30312e30305db1dfadb7a76ab94ca677c052aabab5f3b9440a5b30303a30352e30305da7daadccaa75b5dbaa" +
                "65a9a4ba43ba43a8ab0a5b30303a30392e30305dbf4fa5fab8a8a662a4f4adb1a457a141b9b3a440adbaa853b0dba7b9" +
                "aababa710a5b30303a31332e30305da9faa4d1c1d9b77ca641a8a3adb1b6dca148"
        const val SHIFT_JIS =
            "5b30303a30312e30305d975b95e982ea82cc897782c58c4e82f091d282c20a5b30303a30352e30305d90c382a982c889" +
                "4a82aa8d7e82c182c482a282e90a5b30303a30392e30305d96be93fa82e082ab82c182c690b082ea82e982be82eb82a4"
        const val EUC_JP =
            "5b30303a30312e30305dcdbccaeba4eca4ceb1d8a4c7b7afa4f2c2d4a4c40a5b30303a30352e30305dc0c5a4aba4cab1" +
                "aba4acb9dfa4c3a4c6a4a4a4eb0a5b30303a30392e30305dccc0c6fca4e2a4ada4c3a4c8c0b2a4eca4eba4c0a4eda4a6"
        const val EUC_KR =
            "5b30303a30312e30305db9d9b6f7c0cc20baceb4c220c0fab3e120b0c5b8ae0a5b30303a30352e30305dbfecb8aeb4c2" +
                "20b0adc0bb20b5fbb6f320c3b5c3b5c8f720b0c9befab4d90a5b30303a30392e30305db3bbc0cf20b4d9bdc320b8b8b3" +
                "af20bcf620c0d6c0bbb1ee0a5b30303a31332e30305dbfc0b4c320b9e3c0c720b4dec0bb20b1e2beefc7d820c1e0"
    }
}
