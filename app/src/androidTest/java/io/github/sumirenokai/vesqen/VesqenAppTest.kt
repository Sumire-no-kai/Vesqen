package io.github.sumirenokai.vesqen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class VesqenAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchRequestsLocalMusicAccess() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.onNodeWithText(appContext.getString(R.string.grant_music_access)).assertIsDisplayed()
    }
}
