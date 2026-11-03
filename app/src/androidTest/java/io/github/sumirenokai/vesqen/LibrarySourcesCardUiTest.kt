package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.LibrarySourceKind
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.screens.LibraryScreen
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The sources card speaks for the whole library, so it stays out of a folder's page. */
class LibrarySourcesCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        compose.registerIdlingResource(LibraryProjectionIdling)
    }

    private val track = AudioTrack(11, "content://media/external/audio/media/11", "Aira", "Artist", "Album A", 1_000)
    private val state = LibraryUiState(
        musicAccess = MusicAccess.GRANTED,
        tracks = listOf(track.copy(folderName = "Music/Quiet Rooms/")),
        sources = listOf(
            LibrarySource(
                id = "tree:test",
                kind = LibrarySourceKind.FOLDER,
                displayName = "Quiet Rooms",
                scanState = LibraryScanState.IDLE,
                trackCount = 1,
            ),
        ),
    )

    private fun show() = compose.setContent {
        VesqenTheme {
            LibraryScreen(
                state = state,
                playback = PlaybackSnapshot(), onRequestMusicAccess = {}, onOpenAppSettings = {},
                onOpenNotificationSettings = {}, onRescan = {}, onTrackSelected = {},
            )
        }
    }

    @Test
    fun onARootListItFollowsTheTabsAndStaysOneRowHigh() {
        show()
        val tabs = compose.onNodeWithTag("vesqen.library.mode.songs").getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag("vesqen.library.sources").getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("vesqen.library.track.11").getUnclippedBoundsInRoot()
        assertTrue("The card at ${card.top} should be under the tabs ending at ${tabs.bottom}", card.top >= tabs.bottom)
        assertTrue("The list at ${row.top} should be under the card ending at ${card.bottom}", row.top >= card.bottom)
        assertTrue("The card should be one row, and is ${card.height} high", card.height <= 56.dp)
    }

    @Test
    fun aFolderPageGoesWithoutItAndComesBackToIt() {
        show()
        compose.onNodeWithTag("vesqen.library.mode.folders").performClick()
        compose.onNodeWithTag("vesqen.library.sources").assertIsDisplayed()
        compose.onNodeWithText("Quiet Rooms").performClick()
        compose.onNodeWithTag("vesqen.library.track.11").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.library.sources").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
        compose.onNodeWithTag("vesqen.library.sources").assertIsDisplayed()
    }

    @Test
    fun favoritesGoWithoutIt() {
        show()
        compose.onNodeWithTag("vesqen.library.favorites").performClick()
        compose.onNodeWithTag("vesqen.library.title.favorites").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.library.sources").assertDoesNotExist()
    }

    @Test
    fun largeTextOnANarrowPhoneKeepsTheCountReadable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.size(320.dp, 533.dp)) {
                    VesqenTheme {
                        LibraryScreen(
                            state = state,
                            playback = PlaybackSnapshot(), onRequestMusicAccess = {}, onOpenAppSettings = {},
                            onOpenNotificationSettings = {}, onRescan = {}, onTrackSelected = {},
                        )
                    }
                }
            }
        }
        val count = compose.activity.resources.getQuantityString(R.plurals.imported_folders_count, 1, 1)
        val text = compose.onNodeWithText(count).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("\"$count\" should not wrap to several lines, and is ${text.height} high", text.height <= 48.dp)
        compose.onNodeWithTag("vesqen.library.sources.manage").assertIsDisplayed()
    }
}
