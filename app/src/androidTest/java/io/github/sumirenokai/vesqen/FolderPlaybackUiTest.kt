package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryBrowseMode
import io.github.sumirenokai.vesqen.library.buildLibraryCollections
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.screens.LibraryScreen
import io.github.sumirenokai.vesqen.ui.screens.NowScreen
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FolderPlaybackUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val tracks = listOf(track(1, "A"), track(2, "A"), track(3, "B"), track(4, "B"))

    @Test fun folderScopeChangesOnlyTheNextQueueAndSurvivesUiRestoration() {
        val restoration = StateRestorationTester(compose)
        val queues = mutableListOf<Pair<List<Long>, Int>>()
        restoration.setContent {
            VesqenTheme {
                LibraryScreen(
                    state = LibraryUiState(musicAccess = MusicAccess.GRANTED, tracks = tracks),
                    playback = PlaybackSnapshot(),
                    onRequestMusicAccess = {}, onOpenAppSettings = {}, onOpenNotificationSettings = {},
                    onRescan = {}, onTrackSelected = {},
                    onPlayQueue = { queue, index -> queues += queue.map(AudioTrack::id) to index },
                    motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                )
            }
        }
        openFolder("B")
        compose.onNodeWithTag("vesqen.library.track.4").performClick()
        compose.runOnIdle { assertEquals(listOf(3L, 4L) to 1, queues.single()) }

        chooseContinuous()
        compose.runOnIdle { assertEquals(1, queues.size) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("vesqen.folder.scope").assertExists()
        compose.onNodeWithTag("vesqen.library.track.4").performClick()
        compose.runOnIdle { assertEquals(listOf(1L, 2L, 3L, 4L) to 3, queues.last()) }
        compose.onNodeWithTag("vesqen.library.collection.play").performClick()
        compose.runOnIdle { assertEquals(listOf(1L, 2L, 3L, 4L) to 2, queues.last()) }
    }

    @Test fun continuousPlaybackKeepsTheSearchResultsAsItsBoundary() {
        var queued = emptyList<Long>()
        compose.setContent {
            VesqenTheme {
                LibraryScreen(
                    state = LibraryUiState(musicAccess = MusicAccess.GRANTED, tracks = tracks),
                    playback = PlaybackSnapshot(),
                    onRequestMusicAccess = {}, onOpenAppSettings = {}, onOpenNotificationSettings = {},
                    onRescan = {}, onTrackSelected = {},
                    onPlayQueue = { queue, _ -> queued = queue.map(AudioTrack::id) },
                    motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                )
            }
        }
        compose.onNodeWithTag("vesqen.library.mode.folders").performClick()
        compose.onNodeWithTag("vesqen.library.search-toggle").performClick()
        compose.onNodeWithTag("vesqen.library.search").performTextInput("Track 3")
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("vesqen.library.collection.${folderKey("B")}"))
                .fetchSemanticsNodes().size == 1 &&
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("vesqen.library.collection.${folderKey("A")}"))
                    .fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("vesqen.library.collection.${folderKey("B")}").performClick()
        chooseContinuous()
        compose.onNodeWithTag("vesqen.library.collection.play").performClick()
        compose.runOnIdle { assertEquals(listOf(3L), queued) }
    }

    @Test fun nowMenuExposesEnabledFolderActionsAndUpdatesWhileOpen() {
        val state = mutableStateOf(PlaybackSnapshot(
            isControllerReady = true, trackId = 1, title = "Track 1", queueSize = 4,
            canSkipPreviousFolder = false, canSkipNextFolder = true,
        ))
        var previous = 0
        var next = 0
        compose.setContent {
            VesqenTheme {
                NowScreen(
                    snapshot = state.value, currentTrack = tracks.first(), artworkTrack = null,
                    onBackToLibrary = {}, onOpenChain = {}, onCyclePlaybackOrder = {},
                    onPrevious = {}, onPlayPause = {}, onNext = {},
                    onPreviousFolder = { previous++ }, onNextFolder = { next++ },
                    onSeek = { _, _ -> }, onPlayQueueIndex = {}, onRemoveQueueItem = {},
                    onMoveQueueItem = { _, _ -> }, onClearQueue = {}, onRetryPlayback = {},
                    onToggleOrientation = {}, showOrientationToggle = false, isLandscape = false,
                    motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                )
            }
        }
        compose.onNodeWithTag("vesqen.now.more").performClick()
        compose.onNodeWithTag("vesqen.now.previous-folder").assertIsNotEnabled()
        compose.onNodeWithTag("vesqen.now.next-folder").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, next) }
        compose.onNodeWithTag("vesqen.now.more").performClick()
        compose.runOnIdle { state.value = state.value.copy(canSkipPreviousFolder = true, canSkipNextFolder = false) }
        compose.onNodeWithTag("vesqen.now.next-folder").assertIsNotEnabled()
        compose.onNodeWithTag("vesqen.now.previous-folder").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, previous) }
    }

    private fun openFolder(name: String) {
        compose.onNodeWithTag("vesqen.library.mode.folders").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("vesqen.library.collection.${folderKey(name)}"))
                .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("vesqen.library.collection.${folderKey(name)}").performClick()
    }

    private fun folderKey(name: String): String = buildLibraryCollections(
        LibraryBrowseMode.FOLDERS, tracks, emptyList(),
    ).first { it.title == name }.key

    private fun chooseContinuous() {
        compose.onNodeWithTag("vesqen.folder.scope").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.folder_scope_continuous)).performClick()
    }

    private fun track(id: Long, folder: String) = AudioTrack(
        id, "content://media/external_primary/audio/media/$id", "Track $id", "Artist", "Album", 20_000,
        folderName = folder,
    )
}
