package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.screens.LibraryScreen
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Rule
import org.junit.Test

class LibraryRefreshUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun historyAndFavoriteUpdatesKeepTheScrolledTrackVisible() {
        val tracks = mutableStateOf((0L..149L).map {
            AudioTrack(it, "content://fixture/$it", "Track ${it.toString().padStart(3, '0')}", "", "", 1000)
        })
        compose.setContent {
            VesqenTheme {
                LibraryScreen(
                    state = LibraryUiState(musicAccess = MusicAccess.GRANTED, tracks = tracks.value),
                    playback = PlaybackSnapshot(), onRequestMusicAccess = {}, onOpenAppSettings = {},
                    onOpenNotificationSettings = {}, onRescan = {}, onTrackSelected = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Track", substring = true)))
            .performScrollToIndex(80)
        compose.onNodeWithText("Track 080").assertIsDisplayed()
        compose.runOnIdle {
            tracks.value = tracks.value.map { it.copy(playCount = 1, lastPlayedAtMs = 1000, isFavorite = it.id == 80L) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Track 080").assertIsDisplayed()
    }

    @Test
    fun songsAndFavoritesRestoreIndependentScrollPositions() {
        val tracks = (0L..149L).map { id ->
            AudioTrack(
                id = id,
                contentUri = "content://fixture/$id",
                title = "Track ${id.toString().padStart(3, '0')}",
                artist = "",
                album = "",
                durationMs = 1_000,
                isFavorite = id < 100,
                favoritePosition = id.takeIf { it < 100 },
            )
        }
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
                )
            }
        }

        compose.onNodeWithTag("vesqen.library.tracks").performScrollToIndex(110)
        compose.onNodeWithText("Track 110").assertIsDisplayed()

        compose.onNodeWithTag("vesqen.library.favorites").performClick()
        compose.onNodeWithTag("vesqen.library.tracks").performScrollToIndex(50)
        compose.onNodeWithText("Track 050").assertIsDisplayed()

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.show_all_music))
            .performClick()
        compose.onNodeWithText("Track 110").assertIsDisplayed()

        compose.onNodeWithTag("vesqen.library.favorites").performClick()
        compose.onNodeWithText("Track 050").assertIsDisplayed()
    }

    @Test
    fun disappearingCollectionReturnsSafelyAndClampsRestoredScroll() {
        val albumATracks = (0L..99L).map { id ->
            AudioTrack(
                id = id,
                contentUri = "content://fixture/album-a/$id",
                title = "A ${id.toString().padStart(3, '0')}",
                artist = "",
                album = "Album A",
                durationMs = 1_000,
            )
        }
        val albumBTrack = AudioTrack(
            id = 1_000,
            contentUri = "content://fixture/album-b/1000",
            title = "B 000",
            artist = "",
            album = "Album B",
            durationMs = 1_000,
        )
        val tracks = mutableStateOf(albumATracks + albumBTrack)
        compose.setContent {
            VesqenTheme {
                LibraryScreen(
                    state = LibraryUiState(musicAccess = MusicAccess.GRANTED, tracks = tracks.value),
                    playback = PlaybackSnapshot(),
                    onRequestMusicAccess = {},
                    onOpenAppSettings = {},
                    onOpenNotificationSettings = {},
                    onRescan = {},
                    onTrackSelected = {},
                )
            }
        }

        compose.onNodeWithTag("vesqen.library.mode.albums").performClick()
        compose.onNodeWithText("Album A").performClick()
        compose.onNodeWithTag("vesqen.library.tracks").performScrollToIndex(80)
        compose.onNodeWithText("A 080").assertIsDisplayed()

        compose.runOnIdle { tracks.value = listOf(albumBTrack) }
        compose.waitForIdle()
        compose.onNodeWithText("Album B").assertIsDisplayed()

        compose.runOnIdle { tracks.value = albumATracks.take(40) + albumBTrack }
        compose.waitForIdle()
        compose.onNodeWithText("A 039").assertIsDisplayed()
    }
}
