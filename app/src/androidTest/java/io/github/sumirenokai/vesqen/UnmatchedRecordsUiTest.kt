package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryPlaylist
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.LibrarySourceKind
import io.github.sumirenokai.vesqen.library.UnmatchedLibraryRecord
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.VesqenAppContent
import io.github.sumirenokai.vesqen.ui.VesqenUiState
import io.github.sumirenokai.vesqen.ui.screens.LibraryScreen
import io.github.sumirenokai.vesqen.ui.screens.UnmatchedRecordsList
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** #139: the sources card mentions unmatched records, and its sheet takes the user's choice. */
class UnmatchedRecordsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val record = AudioTrack(1, "content://media/external/audio/media/1", "Aira", "Artist", "Album A", 200_000, isFavorite = true, playCount = 3)
    private val aira = AudioTrack(11, "content://media/external/audio/media/11", "Aira", "Artist", "Album A", 200_000)
    private val tide = AudioTrack(12, "content://media/external/audio/media/12", "Tide", "Artist", "Album B", 300_000)

    private fun state(dismissed: Set<Long> = emptySet()) = LibraryUiState(
        musicAccess = MusicAccess.GRANTED,
        tracks = listOf(aira, tide),
        playlists = listOf(LibraryPlaylist(id = 1, name = "Mix", trackIds = listOf(record.id), createdAtMs = 0, updatedAtMs = 0)),
        unmatchedRecords = listOf(UnmatchedLibraryRecord(record, listOf(aira, tide))),
        dismissedUnmatchedRecordIds = dismissed,
    )

    @Test
    fun theSheetAssignsOrDiscardsARecord() {
        var assigned: Pair<Long, Long>? = null
        var discarded: Long? = null
        compose.setContent {
            VesqenTheme {
                LibraryScreen(
                    state = state(),
                    playback = PlaybackSnapshot(), onRequestMusicAccess = {}, onOpenAppSettings = {},
                    onOpenNotificationSettings = {}, onRescan = {}, onTrackSelected = {},
                    onAssignUnmatchedRecord = { recordId, trackId -> assigned = recordId to trackId },
                    onDiscardUnmatchedRecord = { discarded = it },
                )
            }
        }

        compose.onNodeWithTag("vesqen.library.unmatched.review").performClick()
        compose.onNodeWithTag("vesqen.library.unmatched-records").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.library.unmatched.1.12").performClick()
        compose.runOnIdle { assertEquals(1L to 12L, assigned) }

        compose.onNodeWithTag("vesqen.library.unmatched.1.discard").performClick()
        compose.onNodeWithTag("vesqen.library.unmatched.discard-confirm").assertIsDisplayed()
        compose.runOnIdle { assertNull(discarded) }
        compose.onNodeWithTag("vesqen.library.unmatched.discard-confirm.confirm").performClick()
        compose.runOnIdle { assertEquals(1L, discarded) }
    }

    @Test
    fun laterHidesTheLineUntilAnotherRecordWaits() {
        val dismissed = mutableStateOf(emptySet<Long>())
        compose.setContent {
            VesqenTheme {
                LibraryScreen(
                    state = state(dismissed.value),
                    playback = PlaybackSnapshot(), onRequestMusicAccess = {}, onOpenAppSettings = {},
                    onOpenNotificationSettings = {}, onRescan = {}, onTrackSelected = {},
                    onDismissUnmatchedRecords = { dismissed.value = setOf(record.id) },
                )
            }
        }

        compose.onNodeWithTag("vesqen.library.unmatched.later").performClick()
        compose.onNodeWithTag("vesqen.library.unmatched.review").assertDoesNotExist()
        // Without folders, a scan or a failure, the card itself goes.
        compose.onNodeWithTag("vesqen.library.sources").assertDoesNotExist()
        compose.runOnIdle { dismissed.value = setOf(99L) }
        compose.onNodeWithTag("vesqen.library.unmatched.review").assertIsDisplayed()
    }

    @Test
    fun theSheetDoesNotOpenByItselfForALaterRecord() {
        val records = mutableStateOf(state().unmatchedRecords)
        compose.setContent {
            VesqenTheme {
                LibraryScreen(
                    state = state().copy(unmatchedRecords = records.value),
                    playback = PlaybackSnapshot(), onRequestMusicAccess = {}, onOpenAppSettings = {},
                    onOpenNotificationSettings = {}, onRescan = {}, onTrackSelected = {},
                )
            }
        }

        compose.onNodeWithTag("vesqen.library.unmatched.review").performClick()
        compose.onNodeWithTag("vesqen.library.unmatched-records").assertIsDisplayed()
        // The last record was handled; the sheet goes with it.
        compose.runOnIdle { records.value = emptyList() }
        compose.onNodeWithTag("vesqen.library.unmatched-records").assertDoesNotExist()
        // #191 D10: a record that waits later opened the sheet by itself, past "Later".
        compose.runOnIdle { records.value = state().unmatchedRecords }
        compose.onNodeWithTag("vesqen.library.unmatched.review").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.library.unmatched-records").assertDoesNotExist()
    }

    @Test
    fun songsKeepRoomUnderTheNoticesOnASmallScreenWithDoubleText() {
        // #191 C2: at 320 x 533 dp and 2.0x text the sources card with a folder and this line took
        // the whole Library header, and the song list under it had no height.
        val folder = LibrarySource("folder:music", LibrarySourceKind.FOLDER, "Music")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                VesqenTheme {
                    Box(Modifier.size(width = 320.dp, height = 533.dp)) {
                        VesqenAppContent(
                            state = VesqenUiState(library = state().copy(sources = listOf(folder))),
                            onRequestMusicAccess = {}, onOpenAppSettings = {}, onOpenNotificationSettings = {},
                            onRescan = {}, onTrackSelected = {}, onPrevious = {}, onPlayPause = {}, onNext = {},
                            onSeek = {}, onCyclePlaybackOrder = {},
                            motionPolicy = VesqenMotionPolicy(reduceMotion = true), supportUrl = null,
                        )
                    }
                }
            }
        }

        val row = compose.onNodeWithTag("vesqen.library.track.11").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val visible = with(compose.density) { row.height.toDp() }
        assertTrue("The first song shows $visible of its row", visible >= 48.dp)
        // The card scrolls inside its share of the header, so the line's buttons stay reachable.
        compose.onNodeWithTag("vesqen.library.unmatched.review").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aSmallScreenWithDoubleTextStillReachesTheRecords() {
        // #191 D4: at 320 x 533 dp and 2.0x text the title and explanation filled the sheet, and the
        // list under them had no height. The sheet's own window takes no size from a test, so its
        // content is measured alone in the room the sheet has there.
        var assigned: Pair<Long, Long>? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                VesqenTheme {
                    Box(Modifier.size(width = 320.dp, height = 450.dp)) {
                        UnmatchedRecordsList(
                            records = state().unmatchedRecords,
                            playlists = state().playlists,
                            onAssign = { recordId, trackId -> assigned = recordId to trackId },
                            onDiscard = {},
                        )
                    }
                }
            }
        }

        // The records have the sheet's room, below the title and explanation as the list scrolls.
        val list = compose.onNodeWithTag("vesqen.library.unmatched-records.list").getUnclippedBoundsInRoot()
        assertTrue("The list is ${list.height} tall", list.height >= 400.dp)
        compose.onNodeWithTag("vesqen.library.unmatched-records.list")
            .performScrollToNode(hasTestTag("vesqen.library.unmatched.1.11"))
        compose.onNodeWithTag("vesqen.library.unmatched.1.11").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1L to 11L, assigned) }
    }
}
