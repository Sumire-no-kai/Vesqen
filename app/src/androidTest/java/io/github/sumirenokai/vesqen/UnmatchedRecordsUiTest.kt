package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryPlaylist
import io.github.sumirenokai.vesqen.library.UnmatchedLibraryRecord
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.screens.LibraryScreen
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
