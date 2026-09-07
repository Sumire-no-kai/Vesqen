package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
}
