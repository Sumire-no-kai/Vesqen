package io.github.sumirenokai.vesqen.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.PlaybackInterruption
import io.github.sumirenokai.vesqen.playback.PlaybackProblem
import io.github.sumirenokai.vesqen.playback.UnplayableTrackSettings
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.ui.screens.SettingsScreen
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** #344: the dialog that explains a stop, and the auto-skip switch in Settings. */
class PlaybackProblemUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val actions = mutableListOf<String>()

    @After fun clearPreferences() {
        context.getSharedPreferences("unplayable_tracks", 0).edit().clear().commit()
    }

    private fun showDialog(kind: PlaybackProblem, canSkipNext: Boolean = true, showAutoSkipNote: Boolean = true) {
        compose.setContent {
            VesqenTheme(darkTheme = false) {
                PlaybackProblemDialog(
                    interruption = PlaybackInterruption(1, kind, "1", "Night Voyage"),
                    canSkipNext = canSkipNext,
                    showAutoSkipNote = showAutoSkipNote,
                    onRetry = { actions += "retry" },
                    onNext = { actions += "next" },
                    onDismiss = { actions += "ok" },
                )
            }
        }
    }

    private fun tagCount(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Test fun anUndecodableTrackNamesTheTrackAndOffersNextTrack() {
        showDialog(PlaybackProblem.UNPLAYABLE_TRACK)

        compose.onNodeWithText(context.getString(R.string.playback_problem_unplayable_body, "Night Voyage")).assertIsDisplayed()
        compose.onNodeWithTag("vesqen.playback-problem.note").assertIsDisplayed()
        assertEquals("retrying fails the same way", 0, tagCount("vesqen.playback-problem.retry"))
        compose.onNodeWithTag("vesqen.playback-problem.next").performClick()
        compose.runOnIdle { assertEquals(listOf("next"), actions) }
    }

    @Test fun theNoteGoesOnceAutoSkipIsOn() {
        showDialog(PlaybackProblem.UNPLAYABLE_TRACK, showAutoSkipNote = false)

        assertEquals(0, tagCount("vesqen.playback-problem.note"))
        compose.onNodeWithTag("vesqen.playback-problem.ok").performClick()
        compose.runOnIdle { assertEquals(listOf("ok"), actions) }
    }

    @Test fun aPlaybackErrorOffersRetryAndUsesItsOwnText() {
        showDialog(PlaybackProblem.SOURCE_UNAVAILABLE)

        compose.onNodeWithText(context.getString(R.string.playback_problem_source)).assertIsDisplayed()
        assertEquals("the note concerns undecodable tracks only", 0, tagCount("vesqen.playback-problem.note"))
        compose.onNodeWithTag("vesqen.playback-problem.retry").performClick()
        compose.runOnIdle { assertEquals(listOf("retry"), actions) }
    }

    @Test fun withoutANextTrackOkStandsAlone() {
        showDialog(PlaybackProblem.UNPLAYABLE_TRACK, canSkipNext = false)

        assertEquals(0, tagCount("vesqen.playback-problem.next"))
        compose.onNodeWithTag("vesqen.playback-problem.ok").assertIsDisplayed()
    }

    @Test fun theSkipSwitchIsLastInPlaybackOutputAndSavesTheChoice() {
        val settings = UnplayableTrackSettings(context)
        compose.setContent {
            VesqenTheme(darkTheme = false) {
                CompositionLocalProvider(LocalUnplayableTrackSettings provides settings) {
                    SettingsScreen(
                        outputStatus = UsbOutputStatus(mode = UsbOutputMode.SYSTEM, phase = UsbOutputPhase.SYSTEM),
                        onOpenPlaybackChain = {},
                        onImportVerificationRegistry = {},
                        onOpenAbout = {},
                        versionName = "2.0.0",
                    )
                }
            }
        }

        assertFalse(settings.skip.value)
        compose.onNodeWithTag("vesqen.settings.skip-unplayable").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("vesqen.settings.skip-unplayable").assertIsOn()
        assertTrue(settings.skip.value)
        assertTrue(UnplayableTrackSettings(context).skip.value)
        // Inside the Playback output group.
        val group = compose.onNodeWithTag("vesqen.settings.section.playback-output").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("vesqen.settings.skip-unplayable").fetchSemanticsNode().boundsInRoot
        assertTrue("$row is outside $group", row.top >= group.top && row.bottom <= group.bottom)
    }
}
