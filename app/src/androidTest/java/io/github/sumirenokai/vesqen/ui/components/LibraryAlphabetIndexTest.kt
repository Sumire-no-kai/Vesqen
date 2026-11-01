package io.github.sumirenokai.vesqen.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** #221: the default A–Z strip also opens the letter picker without touch. */
class LibraryAlphabetIndexTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val jump get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.jump_to_letter)
    private lateinit var listState: LazyListState

    /** Tall enough, at the default font size and without touch exploration, for the strip. */
    private fun setIndex() = compose.setContent {
        VesqenTheme(darkTheme = false) {
            listState = rememberLazyListState()
            Row(Modifier.size(360.dp, 640.dp)) {
                LazyColumn(Modifier.weight(1f), state = listState) {
                    items(100) { Text("Row $it", Modifier.height(48.dp)) }
                }
                LibraryAlphabetIndex(sections = mapOf("A" to 0, "M" to 50), listState = listState)
            }
        }
    }

    @Test
    fun theStripIsANamedButtonThatOpensTheLetterPicker() {
        setIndex()
        compose.onNodeWithTag("vesqen.library.alphabet")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertContentDescriptionEquals(jump)
            .performSemanticsAction(SemanticsActions.OnClick)
        // The letter bubble would show for 700 ms beside an old touch; a letter from the picker shows none.
        compose.onNodeWithText("M").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("M").performClick()
        compose.mainClock.advanceTimeBy(200)
        compose.onAllNodesWithText("M").assertCountEquals(0)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(50, listState.firstVisibleItemIndex)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun enterOnTheFocusedStripOpensTheLetterPicker() {
        setIndex()
        compose.onNodeWithTag("vesqen.library.alphabet")
            .requestFocus()
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText(jump).assertIsDisplayed()
    }

    @Test
    fun aTapOnALetterStillJumpsToIt() {
        setIndex()
        // The strip spreads A–Z and # evenly; M is the 13th of 27.
        compose.onNodeWithTag("vesqen.library.alphabet").performTouchInput {
            click(Offset(centerX, height * 12.5f / 27f))
        }
        compose.waitForIdle()
        assertEquals(50, listState.firstVisibleItemIndex)
        compose.onNodeWithText(jump).assertDoesNotExist()
    }
}
