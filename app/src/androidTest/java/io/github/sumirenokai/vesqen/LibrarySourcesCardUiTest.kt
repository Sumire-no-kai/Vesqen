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
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryScanProgress
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

/** The sources card appears only when something needs the listener, and only on the root lists. */
class LibrarySourcesCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        compose.registerIdlingResource(LibraryProjectionIdling)
    }

    private val track = AudioTrack(11, "content://media/external/audio/media/11", "Aira", "Artist", "Album A", 1_000)
    private val folder = LibrarySource(
        id = "tree:test",
        kind = LibrarySourceKind.FOLDER,
        displayName = "Quiet Rooms",
        scanState = LibraryScanState.IDLE,
        trackCount = 1,
    )
    private val quiet = LibraryUiState(
        musicAccess = MusicAccess.GRANTED,
        tracks = listOf(track.copy(folderName = "Music/Quiet Rooms/")),
        sources = listOf(folder),
    )
    private val scanning = quiet.copy(
        scanProgress = LibraryScanProgress(sourceId = folder.id, sourceName = folder.displayName, scannedTrackCount = 3),
    )

    private fun show(state: LibraryUiState) = compose.setContent {
        VesqenTheme {
            LibraryScreen(
                state = state,
                playback = PlaybackSnapshot(), onRequestMusicAccess = {}, onOpenAppSettings = {},
                onOpenNotificationSettings = {}, onRescan = {}, onTrackSelected = {},
            )
        }
    }

    @Test
    fun aQuietLibraryShowsNoCardAndTheListFollowsTheTabs() {
        show(quiet)
        compose.onNodeWithTag("vesqen.library.track.11").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.library.sources").assertDoesNotExist()
        val tabs = compose.onNodeWithTag("vesqen.library.mode.songs").getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("vesqen.library.track.11").getUnclippedBoundsInRoot()
        assertTrue("The list at ${row.top} should sit right under the tabs at ${tabs.bottom}", row.top - tabs.bottom < 24.dp)
    }

    @Test
    fun aScanShowsTheCardUnderTheTabs() {
        show(scanning)
        val tabs = compose.onNodeWithTag("vesqen.library.mode.songs").getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag("vesqen.library.sources").getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("vesqen.library.track.11").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("vesqen.library.pause-scan").assertIsDisplayed()
        assertTrue("The card at ${card.top} should be under the tabs ending at ${tabs.bottom}", card.top >= tabs.bottom)
        assertTrue("The list at ${row.top} should be under the card ending at ${card.bottom}", row.top >= card.bottom)
    }

    @Test
    fun aFolderPageGoesWithoutItAndComesBackToIt() {
        show(scanning)
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
        show(scanning)
        compose.onNodeWithTag("vesqen.library.favorites").performClick()
        compose.onNodeWithTag("vesqen.library.title.favorites").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.library.sources").assertDoesNotExist()
    }

    @Test
    fun theMenuManagesFoldersOnceThereAreAny() {
        show(quiet)
        compose.onNodeWithTag("vesqen.library.menu").performClick()
        compose.onNodeWithTag("vesqen.library.manage-sources").performClick()
        compose.onNodeWithTag("vesqen.library.source-manager").assertIsDisplayed()
    }

    @Test
    fun theMenuHasNothingToManageWithoutFolders() {
        show(quiet.copy(sources = emptyList()))
        compose.onNodeWithTag("vesqen.library.menu").performClick()
        compose.onNodeWithTag("vesqen.library.add-folder").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.library.manage-sources").assertDoesNotExist()
    }

    @Test
    fun aFolderThatLostItsAccessLeadsToManagingIt() {
        show(quiet.copy(sources = listOf(folder.copy(isAvailable = false))))
        compose.onNodeWithText(compose.activity.getString(R.string.library_folder_access_needs_renewal)).assertIsDisplayed()
        compose.onNodeWithTag("vesqen.library.sources.manage").performClick()
        compose.onNodeWithTag("vesqen.library.source-manager").assertIsDisplayed()
    }

    @Test
    fun aFailedSourceSaysSoWithoutAButtonForDeviceMusic() {
        val device = LibrarySource(
            id = "media:external",
            kind = LibrarySourceKind.DEVICE,
            displayName = "Device music",
            scanState = LibraryScanState.FAILED,
            trackCount = 1,
        )
        show(quiet.copy(sources = listOf(device)))
        compose.onNodeWithText(compose.activity.getString(R.string.library_source_scan_failed)).assertIsDisplayed()
        compose.onNodeWithTag("vesqen.library.sources.manage").assertDoesNotExist()
    }

    @Test
    fun largeTextOnANarrowPhoneKeepsTheMessageAndItsButton() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.size(320.dp, 533.dp)) {
                    VesqenTheme {
                        LibraryScreen(
                            state = quiet.copy(sources = listOf(folder.copy(isAvailable = false))),
                            playback = PlaybackSnapshot(), onRequestMusicAccess = {}, onOpenAppSettings = {},
                            onOpenNotificationSettings = {}, onRescan = {}, onTrackSelected = {},
                        )
                    }
                }
            }
        }
        val button = compose.onNodeWithTag("vesqen.library.sources.manage").getUnclippedBoundsInRoot()
        val text = compose.onNodeWithText(compose.activity.getString(R.string.library_folder_access_needs_renewal))
            .getUnclippedBoundsInRoot()
        assertTrue("The message should span the card, and is ${text.right - text.left} wide", text.right - text.left > 200.dp)
        assertTrue("The button should follow the message", button.top >= text.bottom)
    }
}
