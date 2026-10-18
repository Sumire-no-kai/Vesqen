package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #50: list rows draw the fallback cover from one cached picture instead of drawing its strokes
 * in every row. The picture is rasterized on the CPU and the strokes on the GPU, so antialiased
 * edges and translucent fills may differ by a few levels. Anything a person could notice (a
 * moved, thicker, missing or recoloured stroke, or a missing circle) must not.
 *
 * Honor STF-AL00 (Android 9), 2026-10-18: of 20,736 pixels 3,280 differed, by 0.35 levels on
 * average and at most 42; no pixel differed by more than 64.
 */
class TwinPathsPainterTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listPictureMatchesTheDrawnStrokesInTheLightTheme() = assertSamePlaceholder(darkTheme = false)

    @Test
    fun listPictureMatchesTheDrawnStrokesInTheDarkTheme() = assertSamePlaceholder(darkTheme = true)

    private fun assertSamePlaceholder(darkTheme: Boolean) {
        composeRule.setContent {
            VesqenTheme(darkTheme = darkTheme) {
                Row {
                    AlbumArtwork(modifier = Modifier.size(48.dp).testTag("drawn"))
                    AlbumArtwork(
                        modifier = Modifier.size(48.dp).testTag("picture"),
                        fallbackPainter = rememberTwinPathsPainter(),
                    )
                }
            }
        }
        val drawn = composeRule.onNodeWithTag("drawn").captureToImage().toPixelMap()
        val picture = composeRule.onNodeWithTag("picture").captureToImage().toPixelMap()
        assertEquals(drawn.width, picture.width)
        assertEquals(drawn.height, picture.height)

        var total = 0L
        var large = 0
        for (y in 0 until drawn.height) {
            for (x in 0 until drawn.width) {
                val a = drawn[x, y]
                val b = picture[x, y]
                val difference = (
                    maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue)) * 255
                    ).roundToInt()
                total += difference
                if (difference > 64) large++
            }
        }
        val pixels = drawn.width * drawn.height
        val mean = total.toDouble() / pixels
        // A stroke one pixel off moves both of its edges, hundreds of pixels by 100+ levels; a
        // missing circle raises the mean by several levels.
        assertTrue("mean difference $mean levels", mean <= 2.0)
        assertTrue("$large of $pixels pixels differ by more than 64 levels", large <= pixels / 200)
    }
}
