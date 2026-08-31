package io.github.sumirenokai.vesqen.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.sumirenokai.vesqen.ui.screens.isArtworkReflectionSupported
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedPlayerMaterialTest {
    @Test
    fun focused_player_material_is_opaque_and_keeps_artwork_reflection_in_its_safe_budget() {
        assertEquals(1f, FocusedPlayerMaterial.Canvas.alpha, 0f)
        assertEquals(1f, FocusedPlayerMaterial.Dock.alpha, 0f)
        assertEquals(1f, FocusedPlayerMaterial.Raised.alpha, 0f)
        assertEquals(1f, FocusedPlayerMaterial.ArtworkFrame.alpha, 0f)
        assertEquals(.0396f, FocusedPlayerMaterial.VisibleArtworkReflection, .0001f)
    }

    @Test
    fun focused_player_keeps_readable_fixed_foregrounds_over_its_material_ladder() {
        assertTrue(contrastRatio(InkLight, FocusedPlayerMaterial.Canvas) >= 4.5)
        assertTrue(contrastRatio(InkLight, FocusedPlayerMaterial.Dock) >= 4.5)
        assertTrue(contrastRatio(MutedDark, FocusedPlayerMaterial.Raised) >= 4.5)
        assertTrue(contrastRatio(SignalMossBright, FocusedPlayerMaterial.Dock) >= 4.5)
    }

    @Test
    fun artwork_reflection_requires_a_platform_with_real_blur_support() {
        assertTrue(!isArtworkReflectionSupported(30))
        assertTrue(isArtworkReflectionSupported(31))
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val foregroundLuminance = relativeLuminance(foreground)
        val backgroundLuminance = relativeLuminance(background)
        return (max(foregroundLuminance, backgroundLuminance) + .05) /
            (min(foregroundLuminance, backgroundLuminance) + .05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double = if (channel <= .04045f) {
            channel / 12.92
        } else {
            ((channel + .055) / 1.055).pow(2.4)
        }

        return .2126 * linear(color.red) +
            .7152 * linear(color.green) +
            .0722 * linear(color.blue)
    }

    private fun Double.pow(exponent: Double): Double = Math.pow(this, exponent)
}
