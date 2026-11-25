package io.github.sumirenokai.vesqen.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardGroup
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardPreferences
import io.github.sumirenokai.vesqen.ui.chain.telemetryMetricLabel
import io.github.sumirenokai.vesqen.ui.chain.telemetrySectionLabel
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The dashboard customizer chosen on 2026-11-24: a folded, searchable Metrics index with one
 * checkbox per row, and a Layout tab that pins and orders only the shown metrics.
 */
class ChainCustomizerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val preferences = mutableStateOf(ChainDashboardPreferences().normalized())

    private fun render(
        initial: ChainDashboardPreferences = ChainDashboardPreferences(),
        fontScale: Float = 1f,
        widthDp: Int = 411,
    ) {
        preferences.value = initial.normalized()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                VesqenTheme {
                    Box(Modifier.size(widthDp.dp, 720.dp)) {
                        ChainCustomizerContent(
                            preferences = preferences.value,
                            onPreferencesChanged = { preferences.value = it.normalized() },
                            onResetRequest = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun node(tag: String): SemanticsNodeInteraction = composeRule.onNodeWithTag(tag)

    private fun select(id: TelemetryMetricId) = node("vesqen.chain.customizer.select.${id.value}")

    private fun section(section: TelemetrySection) = node("vesqen.chain.customizer.section.${section.name.lowercase()}")

    private fun scrollMetricsTo(tag: String) {
        node("vesqen.chain.customizer.metrics").performScrollToNode(hasTestTag(tag))
    }

    private fun toggleState(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    private fun customActions(interaction: SemanticsNodeInteraction) =
        interaction.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun runAction(interaction: SemanticsNodeInteraction, label: String) {
        val action = customActions(interaction).single { it.label == label }
        composeRule.runOnIdle { action.action() }
        composeRule.waitForIdle()
    }

    @Test
    fun metricsIndexStartsFoldedAndOpensASection() {
        render()

        // Eight section rows and no metric rows: the index fits on a phone screen.
        TelemetrySection.entries.forEach { scrollMetricsTo("vesqen.chain.customizer.section.${it.name.lowercase()}") }
        composeRule.onAllNodesWithTag("vesqen.chain.customizer.select.${Metrics.SOURCE_SAMPLE_RATE.value}").assertCountEquals(0)

        val sourceLabel = telemetrySectionLabel(context, TelemetrySection.SOURCE)
        val sourceIds = Metrics.descriptors.filter { it.section == TelemetrySection.SOURCE }.map { it.id }
        val shownInSource = sourceIds.count { it in preferences.value.selectedMetricIds }
        scrollMetricsTo("vesqen.chain.customizer.section.source")
        section(TelemetrySection.SOURCE)
            .assert(hasContentDescription(context.getString(R.string.chain_customize_section_shown, sourceLabel, shownInSource, sourceIds.size)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.now_notes_collapsed)))
        composeRule.onNodeWithTag("vesqen.chain.customizer.count.source", useUnmergedTree = true).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("$shownInSource / ${sourceIds.size}"))),
        )

        section(TelemetrySection.SOURCE).performClick()
        composeRule.waitForIdle()

        section(TelemetrySection.SOURCE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.now_notes_expanded)))
        sourceIds.forEach { id ->
            scrollMetricsTo("vesqen.chain.customizer.select.${id.value}")
            select(id).assert(toggleState(ToggleableState(id in preferences.value.selectedMetricIds)))
        }
    }

    @Test
    fun theLastShownMetricKeepsItsBoxAndHidingAPinnedMetricUnpinsIt() {
        render(ChainDashboardPreferences(selectedMetricIds = listOf(Metrics.SOURCE_SAMPLE_RATE)))
        section(TelemetrySection.SOURCE).performClick()
        composeRule.waitForIdle()

        select(Metrics.SOURCE_SAMPLE_RATE).assert(toggleState(ToggleableState.On)).assertIsNotEnabled()

        select(Metrics.SOURCE_BIT_DEPTH).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(Metrics.SOURCE_SAMPLE_RATE, Metrics.SOURCE_BIT_DEPTH), preferences.value.selectedMetricIds)
        select(Metrics.SOURCE_SAMPLE_RATE).assertIsEnabled()

        composeRule.runOnIdle {
            preferences.value = preferences.value.withMetricPinned(Metrics.SOURCE_BIT_DEPTH, pinned = true)
        }
        val depthLabel = telemetryMetricLabel(context, Metrics.SOURCE_BIT_DEPTH)
        select(Metrics.SOURCE_BIT_DEPTH)
            .assert(hasContentDescription(context.getString(R.string.chain_customize_metric_pinned, depthLabel)))

        select(Metrics.SOURCE_BIT_DEPTH).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(Metrics.SOURCE_SAMPLE_RATE), preferences.value.selectedMetricIds)
        assertEquals(emptyList<TelemetryMetricId>(), preferences.value.pinnedMetricIds)
    }

    @Test
    fun searchFindsMetricsAcrossSectionsAndSaysWhenNothingMatches() {
        render(ChainDashboardPreferences(selectedMetricIds = listOf(Metrics.SOURCE_SAMPLE_RATE)))
        val search = composeRule.onNode(hasSetTextAction() and hasContentDescription(context.getString(R.string.chain_customize_search)))

        // Every word of the query must appear; the words of one label find it across sections.
        val audioTrackRate = telemetryMetricLabel(context, Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE)
        search.performTextInput(audioTrackRate.lowercase())
        composeRule.waitForIdle()
        select(Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE).performClick()
        composeRule.waitForIdle()
        assertTrue(Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE in preferences.value.selectedMetricIds)
        composeRule.onAllNodesWithTag("vesqen.chain.customizer.select.${Metrics.SOURCE_BIT_DEPTH.value}").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.chain.customizer.section.source").assertCountEquals(0)

        // A section name finds the section's metrics, including ones whose own names do not say it.
        search.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(telemetrySectionLabel(context, TelemetrySection.USB))) }
        composeRule.waitForIdle()
        Metrics.descriptors.filter { it.section == TelemetrySection.USB }.forEach {
            scrollMetricsTo("vesqen.chain.customizer.select.${it.id.value}")
        }

        search.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("zzqx")) }
        composeRule.waitForIdle()
        node("vesqen.chain.customizer.no-results").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.Text,
                listOf(AnnotatedString(context.getString(R.string.chain_customize_no_results, "zzqx"))),
            ),
        )
    }

    @Test
    fun layoutListsShownMetricsInDashboardGroupsAndPinsMoveRows() {
        render(
            ChainDashboardPreferences(
                selectedMetricIds = listOf(Metrics.SOURCE_SAMPLE_RATE, Metrics.SOURCE_BIT_DEPTH, Metrics.PLAYBACK_UNDERRUN_COUNT),
            ),
        )
        node("vesqen.chain.customizer.tab.layout").performClick()
        composeRule.waitForIdle()

        node("vesqen.chain.customizer.layout.heading.source")
        node("vesqen.chain.customizer.layout.heading.playback")
        composeRule.onAllNodesWithTag("vesqen.chain.customizer.layout.heading.pinned").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.chain.customizer.layout.heading.usb").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.chain.customizer.layout.row.${Metrics.SOURCE_FILE_SIZE.value}").assertCountEquals(0)

        val pin = node("vesqen.chain.customizer.pin.${Metrics.SOURCE_BIT_DEPTH.value}")
        pin.assert(toggleState(ToggleableState.Off)).assertIsEnabled()
        pin.performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(Metrics.SOURCE_BIT_DEPTH), preferences.value.pinnedMetricIds)
        node("vesqen.chain.customizer.layout.heading.pinned")
        node("vesqen.chain.customizer.pin.${Metrics.SOURCE_BIT_DEPTH.value}").assert(toggleState(ToggleableState.On))
        assertEquals(
            listOf(
                ChainDashboardGroup(null, listOf(Metrics.SOURCE_BIT_DEPTH)),
                ChainDashboardGroup(TelemetrySection.SOURCE, listOf(Metrics.SOURCE_SAMPLE_RATE)),
                ChainDashboardGroup(TelemetrySection.PLAYBACK, listOf(Metrics.PLAYBACK_UNDERRUN_COUNT)),
            ),
            preferences.value.dashboardGroups(),
        )
    }

    @Test
    fun moveActionsReorderMetricsAndSections() {
        render(
            ChainDashboardPreferences(
                selectedMetricIds = listOf(Metrics.SOURCE_SAMPLE_RATE, Metrics.SOURCE_BIT_DEPTH, Metrics.PLAYBACK_UNDERRUN_COUNT),
            ),
        )
        node("vesqen.chain.customizer.tab.layout").performClick()
        composeRule.waitForIdle()
        val up = context.getString(R.string.move_up)
        val down = context.getString(R.string.move_down)

        val rate = node("vesqen.chain.customizer.layout.label.${Metrics.SOURCE_SAMPLE_RATE.value}")
        assertEquals(listOf(down), customActions(rate).map { it.label })
        runAction(rate, down)
        assertEquals(
            listOf(Metrics.SOURCE_BIT_DEPTH, Metrics.SOURCE_SAMPLE_RATE),
            preferences.value.dashboardGroups().first().metricIds,
        )

        val source = node("vesqen.chain.customizer.layout.heading.source")
        assertEquals(listOf(down), customActions(source).map { it.label })
        runAction(source, down)
        assertEquals(
            listOf(TelemetrySection.PLAYBACK, TelemetrySection.SOURCE),
            preferences.value.dashboardGroups().map { it.section },
        )
        assertEquals(listOf(up), customActions(node("vesqen.chain.customizer.layout.heading.source")).map { it.label })
    }

    @Test
    fun dragHandleMovesARowWithinItsGroup() {
        val sourceDefaults = listOf(Metrics.SOURCE_CONTAINER, Metrics.SOURCE_CODEC_LABEL, Metrics.SOURCE_SAMPLE_RATE, Metrics.SOURCE_BIT_DEPTH)
        render(ChainDashboardPreferences(selectedMetricIds = sourceDefaults + Metrics.PLAYBACK_UNDERRUN_COUNT))
        node("vesqen.chain.customizer.tab.layout").performClick()
        composeRule.waitForIdle()

        drag(
            from = "vesqen.chain.customizer.drag.row.${Metrics.SOURCE_CONTAINER.value}",
            to = "vesqen.chain.customizer.drag.row.${Metrics.SOURCE_BIT_DEPTH.value}",
        )

        composeRule.runOnIdle {
            assertEquals(
                listOf(Metrics.SOURCE_CODEC_LABEL, Metrics.SOURCE_SAMPLE_RATE, Metrics.SOURCE_BIT_DEPTH, Metrics.SOURCE_CONTAINER),
                preferences.value.dashboardGroups().first().metricIds,
            )
            // A row never leaves its group.
            assertEquals(listOf(Metrics.PLAYBACK_UNDERRUN_COUNT), preferences.value.dashboardGroups()[1].metricIds)
        }
    }

    @Test
    fun draggingASectionHeadingFoldsTheGroupsAndMovesTheSection() {
        render(
            ChainDashboardPreferences(
                selectedMetricIds = listOf(
                    Metrics.SOURCE_CONTAINER,
                    Metrics.SOURCE_CODEC_LABEL,
                    Metrics.SOURCE_SAMPLE_RATE,
                    Metrics.PLAYBACK_UNDERRUN_COUNT,
                    Metrics.USB_AUDIO_DEVICE_COUNT,
                ),
            ),
        )
        node("vesqen.chain.customizer.tab.layout").performClick()
        composeRule.waitForIdle()

        var folded = false
        drag(
            from = "vesqen.chain.customizer.drag.section.source",
            to = "vesqen.chain.customizer.drag.section.usb",
            duringDrag = {
                folded = composeRule.onAllNodesWithTag("vesqen.chain.customizer.layout.row.${Metrics.SOURCE_SAMPLE_RATE.value}")
                    .fetchSemanticsNodes().isEmpty()
            },
        )

        assertTrue("The rows fold away while a section is dragged", folded)
        composeRule.runOnIdle {
            assertEquals(
                listOf(TelemetrySection.PLAYBACK, TelemetrySection.USB, TelemetrySection.SOURCE),
                preferences.value.dashboardGroups().map { it.section },
            )
        }
        // The groups open again after the drop.
        node("vesqen.chain.customizer.layout").performScrollToNode(
            hasTestTag("vesqen.chain.customizer.layout.row.${Metrics.SOURCE_SAMPLE_RATE.value}"),
        )
    }

    @Test
    fun controlsKeep48dpTargetsAndTextFitsAt320dpWithTwiceTheFontSize() {
        render(
            ChainDashboardPreferences(selectedMetricIds = listOf(Metrics.SOURCE_CONTAINER, Metrics.POWER_DEVICE_ESTIMATE)),
            fontScale = 2f,
            widthDp = 320,
        )
        node("vesqen.chain.customizer.tab.metrics").assertHeightIsAtLeast(48.dp)
        scrollMetricsTo("vesqen.chain.customizer.section.power")
        section(TelemetrySection.POWER).assertHeightIsAtLeast(48.dp)
        // The count keeps its one line whole; the name beside it wraps instead. A one-line text is
        // laid out at the slot's full width, so its flags report overflow; compare the line's end.
        val count = textLayout(composeRule.onNodeWithTag("vesqen.chain.customizer.count.power", useUnmergedTree = true))
        assertEquals(1, count.lineCount)
        assertFalse(count.isLineEllipsized(0))
        assertTrue(
            "count line ends at ${count.getLineRight(0)} px in a ${count.size.width} px box",
            count.getLineRight(0) <= count.size.width + 1f,
        )
        section(TelemetrySection.POWER).performClick()
        composeRule.waitForIdle()
        scrollMetricsTo("vesqen.chain.customizer.select.${Metrics.POWER_DEVICE_ESTIMATE.value}")
        select(Metrics.POWER_DEVICE_ESTIMATE).assertHeightIsAtLeast(48.dp)

        node("vesqen.chain.customizer.tab.layout").performClick()
        composeRule.waitForIdle()
        node("vesqen.chain.customizer.pin.${Metrics.SOURCE_CONTAINER.value}").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        node("vesqen.chain.customizer.drag.row.${Metrics.SOURCE_CONTAINER.value}").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val label = textLayout(node("vesqen.chain.customizer.layout.label.${Metrics.SOURCE_CONTAINER.value}"))
        assertFalse("The metric name wraps instead of overflowing", label.hasVisualOverflow)
    }

    private fun textLayout(interaction: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    /** Drags a handle onto another, advancing the frame clock with each move (see LibraryOrderUiTest). */
    private fun drag(from: String, to: String, duringDrag: () -> Unit = {}) {
        val list = node("vesqen.chain.customizer.layout")
        val origin = list.fetchSemanticsNode().boundsInRoot.topLeft
        val start = composeRule.onNodeWithTag(from, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center - origin
        val end = composeRule.onNodeWithTag(to, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center - origin
        composeRule.mainClock.autoAdvance = false
        try {
            list.performTouchInput { down(start) }
            composeRule.mainClock.advanceTimeByFrame()
            repeat(30) { step ->
                list.performTouchInput { moveTo(start + (end - start) * ((step + 1) / 30f)) }
                composeRule.mainClock.advanceTimeByFrame()
            }
            duringDrag()
            list.performTouchInput { up() }
            composeRule.mainClock.advanceTimeByFrame()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
    }
}
