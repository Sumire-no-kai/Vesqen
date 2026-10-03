package io.github.sumirenokai.vesqen.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contrast floors from docs/redesign/B_PAPER_AND_SOUND.md §2.2 and §2.4. */
class PaperAndSoundColorTest {
    @Test
    fun lightTokensKeepTheirSpecifiedContrast() {
        assertContrast(Ink, Paper, 15.2)
        assertContrast(Ink, PaperRaised, 16.5)
        assertContrast(InkMuted, Paper, 5.95)
        assertContrast(InkMuted, PaperRaised, 6.5)
        assertContrast(InkMuted, PaperNav, 6.2)
        assertContrast(MossDeep, Paper, 5.2)
        assertContrast(OnMoss, MossDeep, 6.0)
        assertContrast(AmberDeep, Paper, 6.2)
    }

    @Test
    fun darkTokensKeepTheirSpecifiedContrast() {
        assertContrast(NightText, Night, 15.0)
        assertContrast(NightMuted, NightRaised, 6.65)
        assertContrast(NightMuted, NightNav, 6.95)
        assertContrast(NightMuted, Night, 7.2)
        assertContrast(MossBright, Night, 11.4)
        assertContrast(Night, MossBright, 11.4)
        assertContrast(AmberBright, Night, 11.2)
    }

    @Test
    fun everyHueAndChromaKeepsTextReadableOnAlbumTints() {
        for (hue in 0 until 360) for (chroma in listOf(0f, .05f, .12f, .4f)) {
            val album = AlbumHue(hue.toFloat(), chroma)
            val light = albumBackground(album, dark = false)
            assertContrast(Ink, light, 13.3)
            assertContrast(InkMuted, light, 5.2)
            val dark = albumBackground(album, dark = true)
            assertContrast(NightText, dark, 14.3)
            assertContrast(NightMuted, dark, 6.85)
            assertContrast(MossBright, dark, 10.85)
        }
    }

    @Test
    fun darkTintRuleReproducesTheSpecifiedArtboardColors() {
        assertEquals(0xFF141A19.toInt(), albumBackground(AlbumHue(191f, .044f), dark = true).toArgb())
        assertEquals(0xFF21150F.toInt(), albumBackground(AlbumHue(46f, .117f), dark = true).toArgb())
        assertEquals(0xFF16191B.toInt(), albumBackground(AlbumHue(240f, .031f), dark = true).toArgb())
    }

    @Test
    fun achromaticOrMissingCoversUsePlainPaperAndNight() {
        assertEquals(Paper, albumBackground(null, dark = false))
        assertEquals(Night, albumBackground(null, dark = true))
        assertNull(dominantAlbumHue(IntArray(64 * 64) { 0xFF808080.toInt() }))
        assertNull(dominantAlbumHue(IntArray(0)))
        // Near-black and near-white do not count as colour.
        assertNull(dominantAlbumHue(IntArray(100) { if (it % 2 == 0) 0xFF050505.toInt() else 0xFFFAFAFA.toInt() }))
    }

    @Test
    fun coloursBelowEightPercentOfTheOpaqueCoverAreIgnored() {
        val red = Color(0xFFC0392B).toArgb()
        val grey = 0xFF777777.toInt()
        assertNull(dominantAlbumHue(IntArray(100) { if (it < 7) red else grey }))
        val hue = dominantAlbumHue(IntArray(100) { if (it < 9) red else grey })
        assertEquals(argbToOklch(red).hue, hue!!.hueDegrees, .01f)
    }

    @Test
    fun theStrongestHueBandWinsAndTransparentPixelsAreSkipped() {
        val blue = Color(0xFF1F4E9A).toArgb()
        val orange = Color(0xFFC77A2E).toArgb()
        val transparentOrange = orange and 0x00FFFFFF
        val pixels = IntArray(100) { index ->
            when {
                index < 60 -> blue
                index < 70 -> orange
                else -> transparentOrange
            }
        }
        val hue = dominantAlbumHue(pixels)!!
        assertEquals(argbToOklch(blue).hue, hue.hueDegrees, .01f)
        assertEquals(argbToOklch(blue).chroma, hue.chroma, .0001f)
    }

    @Test
    fun oklchConversionRoundTripsSrgb() {
        for (argb in intArrayOf(0xFF536B1E.toInt(), 0xFFF3EFE6.toInt(), 0xFF1F4E9A.toInt(), 0xFFC0392B.toInt())) {
            val oklch = argbToOklch(argb)
            assertEquals(argb, oklchToColor(oklch.lightness, oklch.chroma, oklch.hue).toArgb())
        }
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Double) {
        val a = foreground.luminance() + .05
        val b = background.luminance() + .05
        val ratio = max(a, b) / min(a, b)
        assertTrue("$foreground on $background is $ratio, below $minimum", ratio >= minimum)
    }
}
