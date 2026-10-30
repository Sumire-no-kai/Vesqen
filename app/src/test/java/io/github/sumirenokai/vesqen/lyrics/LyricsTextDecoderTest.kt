package io.github.sumirenokai.vesqen.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class LyricsTextDecoderTest {
    @Test
    fun utf8IsReadWithAndWithoutItsByteOrderMark() {
        assertEquals(SIMPLIFIED, decodeLyricsText(SIMPLIFIED.toByteArray(Charsets.UTF_8)))
        assertEquals(
            SIMPLIFIED,
            decodeLyricsText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + SIMPLIFIED.toByteArray()),
        )
        assertEquals("[00:01.00]plain ASCII", decodeLyricsText("[00:01.00]plain ASCII".toByteArray()))
    }

    @Test
    fun utf16IsReadOnlyWithItsByteOrderMark() {
        assertEquals(JAPANESE, decodeLyricsText(JAPANESE.toByteArray(Charsets.UTF_16)))
        assertEquals(
            JAPANESE,
            decodeLyricsText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + JAPANESE.toByteArray(Charsets.UTF_16LE)),
        )
        // Without a mark the ASCII half of every character is a NUL, which is refused.
        assertNull(decodeLyricsText("[00:01.00]no mark".toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun utf32IsRefusedEvenThoughItsMarkStartsLikeUtf16() {
        val utf32Le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + "[00:01.00]x".toByteArray(UTF_32LE)
        val utf32Be = byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte()) + "[00:01.00]x".toByteArray(UTF_32BE)

        assertNull(decodeLyricsText(utf32Le))
        assertNull(decodeLyricsText(utf32Be))
    }

    @Test
    fun legacyChineseAndJapaneseEncodingsAreDetected() {
        assertEquals(SIMPLIFIED, decodeLyricsText(SIMPLIFIED.toByteArray(Charset.forName("GBK"))))
        assertEquals(SIMPLIFIED, decodeLyricsText(SIMPLIFIED.toByteArray(Charset.forName("GB18030"))))
        assertEquals(TRADITIONAL, decodeLyricsText(TRADITIONAL.toByteArray(Charset.forName("Big5"))))
        assertEquals(JAPANESE, decodeLyricsText(JAPANESE.toByteArray(Charset.forName("Shift_JIS"))))
        assertEquals(JAPANESE, decodeLyricsText(JAPANESE.toByteArray(Charset.forName("windows-31j"))))
    }

    @Test
    fun shortOrAmbiguousLegacyTextIsUnreadable() {
        // Two hanzi read as common characters in both GBK and Big5.
        assertNull(decodeLyricsText("你好".toByteArray(Charset.forName("GBK"))))
        assertNull(decodeLyricsText("[00:01.00]晚风".toByteArray(Charset.forName("GBK"))))
        // Western European and Korean files are not among the supported encodings.
        assertNull(decodeLyricsText("Café à la crème, été".toByteArray(Charsets.ISO_8859_1)))
        assertNull(decodeLyricsText("[00:01.00]바람이 부는 저녁 거리".toByteArray(Charset.forName("EUC-KR"))))
    }

    @Test
    fun koreanAndEucJpTextIsUnreadableRatherThanReadAsChinese() {
        // Hangul rows are GB2312 common hanzi and EUC-JP kana rows are Big5 common hanzi, so both
        // decode cleanly in a supported encoding; the rival readings make them tie.
        assertNull(decodeLyricsText(KOREAN.toByteArray(Charset.forName("EUC-KR"))))
        assertNull(decodeLyricsText(JAPANESE.toByteArray(Charset.forName("EUC-JP"))))
        assertNull(decodeLatinLabelledLyricsText(KOREAN.toByteArray(Charset.forName("EUC-KR"))))
        assertNull(decodeLatinLabelledLyricsText(JAPANESE.toByteArray(Charset.forName("EUC-JP"))))
    }

    @Test
    fun moreChineseSamplesAreStillDetected() {
        listOf(
            "我在这里等你回来\n心里有一句话想说\n这一路走来都是你\n不要让时间带走我们的梦",
            "雨下了一整夜\n窗外的树也睡了\n我把信折好放在桌上\n等天亮再寄给远方的你",
            "城市的夜晚很长\n路口的红灯亮了又灭\n有人在等车\n有人在唱一首旧歌",
            // Short, and nearly all its hanzi sit in rows EUC-JP reads as level-1 kanji.
            "[00:01.00]晚风吹过安静的街道\n[00:05.00]我们沿着河岸慢慢走\n[00:09.00]灯光落在水面上",
        ).forEach { sample ->
            assertEquals(sample, decodeLyricsText(sample.toByteArray(Charset.forName("GBK"))))
        }
        val traditional = "我在這裡等你回來\n心裡有一句話想說\n這一路走來都是你\n不要讓時間帶走我們的夢"
        assertEquals(traditional, decodeLyricsText(traditional.toByteArray(Charset.forName("Big5"))))
        val japanese = "窓の外に桜が咲いて\n春の風が街を渡る\n約束の場所へ急ごう"
        assertEquals(japanese, decodeLyricsText(japanese.toByteArray(Charset.forName("Shift_JIS"))))
    }

    @Test
    fun binaryDataAndReplacementCharactersAreRefused() {
        assertNull(decodeLyricsText("[00:01.00]a\u0000b".toByteArray()))
        assertNull(decodeLyricsText("[00:01.00]\uFFFD broken".toByteArray()))
        assertNull(decodeLyricsText("\u0001\u0002\u0003\u0004[00:01.00]x".toByteArray()))
        assertEquals("[00:01.00]padded", decodeLyricsText("[00:01.00]padded\u0000\u0000\u001A".toByteArray()))
        assertEquals("[00:01.00]one\u000Btab", decodeLyricsText("[00:01.00]one\u000Btab".toByteArray()))
    }

    @Test
    fun oversizedFilesAreRefusedBeforeDecoding() {
        assertNull(decodeLyricsText(ByteArray(MAX_LYRICS_TEXT_BYTES + 1) { 'a'.code.toByte() }))
        assertEquals(MAX_LYRICS_TEXT_BYTES, decodeLyricsText(ByteArray(MAX_LYRICS_TEXT_BYTES) { 'a'.code.toByte() })?.length)
    }

    @Test
    fun latinLabelledTagTextTriesUtf8AndCjkBeforeWesternEuropean() {
        assertEquals(SIMPLIFIED, decodeLatinLabelledLyricsText(SIMPLIFIED.toByteArray(Charsets.UTF_8)))
        assertEquals(SIMPLIFIED, decodeLatinLabelledLyricsText(SIMPLIFIED.toByteArray(Charset.forName("GBK"))))
        assertEquals(JAPANESE, decodeLatinLabelledLyricsText(JAPANESE.toByteArray(Charset.forName("Shift_JIS"))))
        val western = "Don\u2019t wait for the café \u2014 été"
        assertEquals(western, decodeLatinLabelledLyricsText(western.toByteArray(Charset.forName("windows-1252"))))
        // 0x81 is undefined in windows-1252 and is kept as ISO-8859-1 declares it.
        assertEquals("a\u0081b", decodeLatinLabelledLyricsText(byteArrayOf(0x61, 0x81.toByte(), 0x62)))
    }

    // #191 B1: an ID3 frame labelled ISO-8859-1 that holds GBK traditional Chinese, or only a few
    // hanzi, has no clear encoding but is plainly East Asian. It is unreadable, never mojibake.
    @Test
    fun latinLabelledEastAsianTextWithoutAClearEncodingIsUnreadable() {
        val gbk = Charset.forName("GBK")
        assertNull(decodeLatinLabelledLyricsText(GBK_TRADITIONAL.toByteArray(gbk)))
        assertNull(decodeLatinLabelledLyricsText("纯音乐".toByteArray(gbk)))
        assertNull(decodeLatinLabelledLyricsText("[00:01.00]纯音乐".toByteArray(gbk)))
        // A few English words do not make it Western.
        assertNull(decodeLatinLabelledLyricsText("[00:01.00]Baby 還記得那年的夏天".toByteArray(gbk)))
        assertNull(decodeLyricsText(GBK_TRADITIONAL.toByteArray(gbk)))
    }

    // #191 B1, the other direction: Western lyrics labelled ISO-8859-1 still read as Western.
    @Test
    fun latinLabelledWesternTextStillReadsAsWestern() {
        val windows1252 = Charset.forName("windows-1252")
        WESTERN_SAMPLES.forEach { sample ->
            assertEquals(sample, decodeLatinLabelledLyricsText(sample.toByteArray(windows1252)))
        }
    }

    @Test
    fun markedUtf16NeedsItsByteOrderMark() {
        assertEquals("歌", decodeMarkedUtf16LyricsText(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x6B, 0x4C)))
        assertEquals("歌", decodeMarkedUtf16LyricsText(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x4C, 0x6B)))
        assertEquals("", decodeMarkedUtf16LyricsText(byteArrayOf()))
        assertNull(decodeMarkedUtf16LyricsText(byteArrayOf(0x4C, 0x6B)))
    }

    @Test
    fun declaredCharsetIsDecodedStrictly() {
        assertEquals("歌", decodeLyricsText(byteArrayOf(0x6B, 0x4C), Charsets.UTF_16BE))
        assertNull(decodeLyricsText(byteArrayOf(0xC3.toByte(), 0x28), Charsets.UTF_8))
    }

    private companion object {
        // Sample lines written for these tests.
        // Traditional Chinese as mainland taggers stored it: GBK reads it, with many hanzi outside the
        // common block (們, 過, 還, 記, 說).
        const val GBK_TRADITIONAL = "[00:01.00]我們一起走過的路\n[00:05.00]還記得那年的夏天\n" +
            "[00:09.00]說好了不再回頭"
        // Sample lines written for these tests: German, French, Spanish, Portuguese, Danish, Italian.
        val WESTERN_SAMPLES = listOf(
            "[00:01.00]Über die Brücke gehen wir langsam\n[00:05.00]Die Straßen sind leer, der Fluss ist still\n" +
                "[00:09.00]Morgen früh fährt der Zug nach Süden",
            "[00:01.00]Le soleil se lève sur la forêt\n[00:05.00]Nous marchons près de la rivière\n" +
                "[00:09.00]Où est passé l'été ?",
            "[00:01.00]El corazón late más fuerte\n[00:05.00]Cuando la noche llega a la ciudad\n" +
                "[00:09.00]Mañana será otro día",
            "[00:01.00]Meu coração não esquece\n[00:05.00]A canção que você cantou",
            "CORAÇÃO",
            "AÇÃO, CANÇÃO E CORAÇÃO",
            "[00:01.00]Søren går på stranden\n[00:05.00]Æblerne er røde i år",
            "[00:01.00]Perché la città è così bella\n[00:05.00]Più forte del vento",
        )
        const val SIMPLIFIED = "[00:01.00]晚风吹过安静的街道\n[00:05.00]我们沿着河岸慢慢走\n" +
            "[00:09.00]灯光落在水面上，像一首没唱完的歌\n[00:13.00]明天还会再见面吗？"
        const val TRADITIONAL = "[00:01.00]晚風吹過安靜的街道\n[00:05.00]我們沿著河岸慢慢走\n" +
            "[00:09.00]燈光落在水面上，像一首沒唱完的歌\n[00:13.00]明天還會再見面嗎？"
        const val JAPANESE = "[00:01.00]夕暮れの駅で君を待つ\n[00:05.00]静かな雨が降っている\n" +
            "[00:09.00]明日もきっと晴れるだろう"
        const val KOREAN = "[00:01.00]바람이 부는 저녁 거리\n[00:05.00]우리는 강을 따라 천천히 걸었다\n" +
            "[00:09.00]내일 다시 만날 수 있을까\n[00:13.00]오늘 밤의 달을 기억해 줘"
        val UTF_32LE: Charset = Charset.forName("UTF-32LE")
        val UTF_32BE: Charset = Charset.forName("UTF-32BE")
    }
}
