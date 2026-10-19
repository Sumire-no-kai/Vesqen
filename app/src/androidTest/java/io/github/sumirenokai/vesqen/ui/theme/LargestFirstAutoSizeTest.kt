package io.github.sumirenokai.vesqen.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.sumirenokai.vesqen.ui.screens.PinnedValueAutoSize
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #50: [LargestFirstAutoSize] keeps the largest size whenever the text fits at it, and otherwise
 * picks what Compose's step search picks. (The step search alone ends a little below the largest
 * size at some font scales, as its steps are counted in pixels.)
 */
class LargestFirstAutoSizeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val fontScales = listOf(0.85f, 1f, 1.15f, 1.3f, 2f)

    @Test
    fun top_level_titles_keep_32_sp_when_they_fit_and_otherwise_step_down() {
        val titles = listOf("Library", "Settings", "Chain", "设置", "播放链路", "ライブラリ")
        val widths = listOf(60, 90, 120, 160, 220, 360)
        val stepSearch = TextAutoSize.StepBased(minFontSize = 21.sp, maxFontSize = 32.sp, stepSize = 1.sp)
        val sizes = mutableStateMapOf<String, Float>()
        val fitsAt32 = mutableStateMapOf<String, Boolean>()
        composeRule.setContent {
            VesqenTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val density = LocalDensity.current
                    for (scale in fontScales) CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        val style = MaterialTheme.typography.displayMedium.pageTitle()
                        for (title in titles) for (width in widths) {
                            val case = "$scale|$title|$width"
                            for ((name, autoSize) in listOf("largest-first" to TopLevelTitleAutoSize, "step" to stepSearch)) {
                                BasicText(
                                    text = title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, autoSize = autoSize,
                                    modifier = Modifier.width(width.dp),
                                    onTextLayout = { sizes["$name|$case"] = it.layoutInput.style.fontSize.value },
                                )
                            }
                            BasicText(
                                text = title, style = style.copy(fontSize = 32.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(width.dp),
                                onTextLayout = { fitsAt32[case] = it.lineCount == 1 && !it.isLineEllipsized(0) },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val cases = fontScales.flatMap { scale -> titles.flatMap { title -> widths.map { "$scale|$title|$it" } } }
        for (case in cases) {
            val actual = sizes.getValue("largest-first|$case")
            val expected = if (fitsAt32.getValue(case)) 32f else sizes.getValue("step|$case")
            // The step search converts through pixels, so its result can differ in the last float digit.
            assertTrue("$case: $actual sp, expected $expected sp", abs(actual - expected) < 0.01f)
        }
        assertTrue("some title keeps 32 sp", cases.any { fitsAt32.getValue(it) })
        assertTrue("some title steps down", cases.any { !fitsAt32.getValue(it) })
    }

    /**
     * Chain's pinned readings: one clipped line in a box sized for 34 sp. Capping the search at 27 sp
     * must keep exactly the size the 12–34 sp search gave (owner, 2026-10-18).
     */
    @Test
    fun pinned_readings_keep_the_size_of_the_full_step_search() {
        val values = listOf("—", "0", "FLAC", "44.1 kHz", "192 kHz", "PCM 24-bit", "Speaker", "扬声器", "有线耳机")
        val widths = listOf(48, 72, 96, 120, 150, 180, 300)
        val fullSearch = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 34.sp, stepSize = 1.sp)
        val sizes = mutableStateMapOf<String, Float>()
        composeRule.setContent {
            VesqenTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val density = LocalDensity.current
                    for (scale in fontScales) CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        val height = with(LocalDensity.current) { 34.sp.toDp() * 1.15f }
                        val style = MaterialTheme.typography.displayLarge.pageTitle().copy(fontFeatureSettings = "tnum", lineHeight = 1.15f.em)
                        for (value in values) for (width in widths) {
                            for ((name, autoSize) in listOf("capped" to PinnedValueAutoSize, "full" to fullSearch)) {
                                BasicText(
                                    text = value, style = style, maxLines = 1, softWrap = false, autoSize = autoSize,
                                    modifier = Modifier.width(width.dp).height(height),
                                    onTextLayout = { sizes["$name|$scale|$value|$width"] = it.layoutInput.style.fontSize.value },
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val cases = fontScales.flatMap { scale -> values.flatMap { value -> widths.map { "$scale|$value|$it" } } }
        for (case in cases) {
            val actual = sizes.getValue("capped|$case")
            val expected = sizes.getValue("full|$case")
            assertTrue("$case: $actual sp, full search $expected sp", abs(actual - expected) < 0.01f)
        }
        assertTrue("some reading keeps 27 sp", cases.any { abs(sizes.getValue("capped|$it") - 27f) < 0.01f })
        assertTrue("some reading steps down", cases.any { sizes.getValue("capped|$it") < 26.01f })
    }
}
