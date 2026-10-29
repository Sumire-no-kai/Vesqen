package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.ArtistSplitSettings
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.DefaultArtistSeparators
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.VesqenAppContent
import io.github.sumirenokai.vesqen.ui.VesqenUiState
import io.github.sumirenokai.vesqen.ui.screens.LibraryScreen
import io.github.sumirenokai.vesqen.ui.screens.SettingsLibraryGroup
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Artists page with shared artist tags, and the Settings that change it (#168). */
class ArtistSplitUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val tracks = listOf(
        AudioTrack(1, "content://fixture/1", "Theme", "塞壬唱片-MSR/Adam Gubman", "Arknights OST", 1_000, folderName = "Music/OST"),
        AudioTrack(2, "content://fixture/2", "Solo", "Adam Gubman", "Solo", 1_000, folderName = "Music/Solo"),
        AudioTrack(3, "content://fixture/3", "Duet", "Adam Gubman feat. Nari", "Solo", 1_000, folderName = "Music/Solo"),
        AudioTrack(4, "content://fixture/4", "Mrs. Robinson", "Simon & Garfunkel", "Bookends", 1_000, folderName = "Music/Bookends"),
    )

    @Test
    fun aSharedTagIsListedUnderEachArtistAndWholeWhenSwitchedOff() {
        var split by mutableStateOf(ArtistSplitSettings())
        compose.setContent {
            VesqenTheme {
                LibraryScreen(
                    state = LibraryUiState(musicAccess = MusicAccess.GRANTED, tracks = tracks),
                    playback = PlaybackSnapshot(),
                    onRequestMusicAccess = {},
                    onOpenAppSettings = {},
                    onOpenNotificationSettings = {},
                    onRescan = {},
                    onTrackSelected = {},
                    motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                    artistSplit = split,
                )
            }
        }
        compose.onNodeWithTag("vesqen.library.mode.artists").performClick()

        listOf("Adam Gubman", "Nari", "塞壬唱片-MSR", "Simon & Garfunkel").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onAllNodesWithText("塞壬唱片-MSR/Adam Gubman").assertCountEquals(0)
        compose.onAllNodes(collectionRow).assertCountEquals(4)
        // Adam Gubman's songs come from two albums, Arknights OST and Solo.
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.collection_album_count, 2, 2)).assertIsDisplayed()

        compose.onNodeWithText("Adam Gubman").performClick()
        listOf(1L, 2L, 3L).forEach { compose.onNodeWithTag("vesqen.library.track.$it").assertIsDisplayed() }
        compose.onAllNodesWithTag("vesqen.library.track.4").assertCountEquals(0)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.runOnIdle { split = split.copy(enabled = false) }
        listOf("塞壬唱片-MSR/Adam Gubman", "Adam Gubman", "Adam Gubman feat. Nari", "Simon & Garfunkel")
            .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onAllNodesWithText("Nari").assertCountEquals(0)
        compose.onAllNodesWithText("塞壬唱片-MSR").assertCountEquals(0)
    }

    @Test
    fun settingsChangeTheSwitchAndBothLists() {
        var split by mutableStateOf(ArtistSplitSettings())
        renderApp(getSplit = { split }, onSplitChange = { split = it })
        compose.onNodeWithTag("vesqen.nav.settings").performClick()
        compose.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.artist-kept-names"))
        compose.onNodeWithText("/ ; ； 、 feat. ft.").assertIsDisplayed()
        compose.onNodeWithText("AC/DC").assertIsDisplayed()

        compose.onNodeWithTag("vesqen.settings.artist-separators").performClick()
        compose.onNodeWithTag("vesqen.settings.artist-separators.dialog.input").performTextReplacement("/  &　feat. /")
        compose.onNodeWithTag("vesqen.settings.artist-separators.dialog.save").performClick()
        compose.runOnIdle { assertEquals(listOf("/", "&", "feat."), split.separators) }
        compose.onNodeWithText("/ & feat.").assertIsDisplayed()

        compose.onNodeWithTag("vesqen.settings.artist-kept-names").performClick()
        compose.onNodeWithTag("vesqen.settings.artist-kept-names.dialog.input").performTextReplacement("AC/DC\n\n Simon & Garfunkel \n")
        compose.onNodeWithTag("vesqen.settings.artist-kept-names.dialog.save").performClick()
        compose.runOnIdle { assertEquals(listOf("AC/DC", "Simon & Garfunkel"), split.keptNames) }

        // Restore fills the field with the defaults; nothing changes until Save.
        compose.onNodeWithTag("vesqen.settings.artist-separators").performClick()
        compose.onNodeWithTag("vesqen.settings.artist-separators.dialog.restore").performClick()
        compose.runOnIdle { assertEquals(listOf("/", "&", "feat."), split.separators) }
        compose.onNodeWithTag("vesqen.settings.artist-separators.dialog.save").performClick()
        compose.runOnIdle { assertEquals(DefaultArtistSeparators, split.separators) }

        compose.onNodeWithTag("vesqen.settings.artist-split").performClick()
        compose.runOnIdle { assertEquals(false, split.enabled) }
        compose.onAllNodesWithTag("vesqen.settings.artist-separators").assertCountEquals(0)
    }

    @Test
    fun turningSplittingOffInSettingsRegroupsTheArtistsPage() {
        var split by mutableStateOf(ArtistSplitSettings())
        renderApp(getSplit = { split }, onSplitChange = { split = it })
        compose.onNodeWithTag("vesqen.library.mode.artists").performClick()
        compose.onNodeWithText("Nari").assertIsDisplayed()

        compose.onNodeWithTag("vesqen.nav.settings").performClick()
        compose.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.artist-split"))
        compose.onNodeWithTag("vesqen.settings.artist-split").performClick()
        compose.onNodeWithTag("vesqen.nav.library").performClick()

        compose.onNodeWithText("Adam Gubman feat. Nari").assertIsDisplayed()
        compose.onAllNodesWithText("Nari").assertCountEquals(0)
    }

    @Test
    fun settingsRowsFitANarrowScreenAtTwiceTheTextSize() {
        compose.setContent {
            VesqenTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    Box(Modifier.width(320.dp).verticalScroll(rememberScrollState()).testTag("narrow")) {
                        Column { SettingsLibraryGroup(ArtistSplitSettings(keptNames = List(12) { "Long Artist Name $it" }), {}) }
                    }
                }
            }
        }
        val width = compose.onNodeWithTag("narrow").fetchSemanticsNode().boundsInRoot.width
        listOf("vesqen.settings.artist-split", "vesqen.settings.artist-separators", "vesqen.settings.artist-kept-names").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag fits the width", bounds.right <= width + 1f)
        }
        // Twelve names stay within two lines instead of making a tall row.
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("Long Artist Name 0", substring = true), useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().hasVisualOverflow)

        // The dialog is its own window, which takes the device's text size rather than this test's.
        compose.onNodeWithTag("vesqen.settings.artist-kept-names").performClick()
        val minimum = with(compose.density) { 48.dp.toPx() }
        listOf("save", "restore").forEach { button ->
            val bounds = compose.onNodeWithTag("vesqen.settings.artist-kept-names.dialog.$button").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue("$button is at least 48 dp tall", bounds.height >= minimum - 1f)
        }
        compose.onNodeWithTag("vesqen.settings.artist-kept-names.dialog.input").assertIsDisplayed()
    }

    private val collectionRow = SemanticsMatcher("a Library collection row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("vesqen.library.collection.") == true
    }

    private fun renderApp(getSplit: () -> ArtistSplitSettings, onSplitChange: (ArtistSplitSettings) -> Unit) {
        compose.setContent {
            VesqenTheme {
                VesqenAppContent(
                    state = VesqenUiState(library = LibraryUiState(musicAccess = MusicAccess.GRANTED, tracks = tracks)),
                    onRequestMusicAccess = {},
                    onOpenAppSettings = {},
                    onOpenNotificationSettings = {},
                    onRescan = {},
                    onTrackSelected = {},
                    onPrevious = {},
                    onPlayPause = {},
                    onNext = {},
                    onSeek = {},
                    onCyclePlaybackOrder = {},
                    motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                    supportUrl = null,
                    artistSplit = getSplit(),
                    onArtistSplitChange = onSplitChange,
                )
            }
        }
    }
}
