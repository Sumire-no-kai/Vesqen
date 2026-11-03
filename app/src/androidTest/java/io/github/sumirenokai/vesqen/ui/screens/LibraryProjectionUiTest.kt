package io.github.sumirenokai.vesqen.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/** The Library keeps showing whole lists while new ones are worked out off the main thread (#223). */
class LibraryProjectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Holds the Library's work back, so a test can look at the page while it is pending. */
    private class GatedDispatcher : CoroutineDispatcher() {
        private val worker = Executors.newSingleThreadExecutor()
        private val waiting = ArrayDeque<Runnable>()
        private var open = true
        val runs = AtomicInteger()

        @Synchronized fun hold() { open = false }

        @Synchronized fun release() {
            open = true
            while (waiting.isNotEmpty()) worker.execute(waiting.removeFirst())
        }

        @Synchronized override fun dispatch(context: CoroutineContext, block: Runnable) {
            runs.incrementAndGet()
            if (open) worker.execute(block) else waiting.addLast(block)
        }
    }

    private val gate = GatedDispatcher()
    private val projections = LibraryProjections(gate)
    private val tracks = mutableStateOf(listOf(track(1, "Alder"), track(2, "Bell"), track(3, "Beta")))
    private val visible = mutableStateOf(true)

    private fun showLibrary(onSaveTrackOrder: suspend (Long?, List<Long>) -> Boolean = { _, _ -> true }) {
        compose.setContent {
            VesqenTheme {
                if (visible.value) {
                    LibraryScreen(
                        state = LibraryUiState(musicAccess = MusicAccess.GRANTED, tracks = tracks.value),
                        playback = PlaybackSnapshot(),
                        onRequestMusicAccess = {},
                        onOpenAppSettings = {},
                        onOpenNotificationSettings = {},
                        onRescan = {},
                        onTrackSelected = {},
                        onSaveTrackOrder = onSaveTrackOrder,
                        projections = projections,
                    )
                }
            }
        }
    }

    /** The title order also arrives on its own, so the lists have settled only once they include it. */
    private fun settle() {
        compose.waitUntil(5_000) {
            projections.title != null && projections.shown?.input?.title === projections.title &&
                libraryProjectionSettled.get()
        }
        compose.waitForIdle()
    }

    private fun row(id: Long) = compose.onNodeWithTag("vesqen.library.track.$id")

    private fun waitForRowCount(id: Long, count: Int) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag("vesqen.library.track.$id").fetchSemanticsNodes().size == count
    }

    private fun noResults() = compose.onNodeWithText(compose.activity.getString(R.string.no_search_results))

    private fun search(text: String) = compose.onNode(hasSetTextAction()).performTextReplacement(text)

    @Test fun aNewQueryKeepsTheLastListUntilItsListIsReady() {
        showLibrary()
        settle()
        compose.onNodeWithTag("vesqen.library.search-toggle").performClick()

        gate.hold()
        search("Beta")
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertTextEquals("Beta")
        row(1).assertIsDisplayed()
        noResults().assertDoesNotExist()

        gate.release()
        waitForRowCount(1, 0)
        row(3).assertIsDisplayed()
    }

    @Test fun aQueryWithNoMatchesSaysSoOnlyOnceItsListIsReady() {
        showLibrary()
        settle()
        compose.onNodeWithTag("vesqen.library.search-toggle").performClick()

        gate.hold()
        search("nothing like this")
        compose.waitForIdle()
        row(1).assertIsDisplayed()
        noResults().assertDoesNotExist()

        gate.release()
        compose.waitUntil(5_000) { libraryProjectionSettled.get() }
        compose.waitForIdle()
        noResults().assertIsDisplayed()

        // Clearing the search leaves the message up until the whole list is back.
        gate.hold()
        search("")
        compose.waitForIdle()
        noResults().assertIsDisplayed()
        gate.release()
        waitForRowCount(1, 1)
        noResults().assertDoesNotExist()
    }

    @Test fun tracksThatArriveDoNotSayNoResultsBeforeTheirListsAreReady() {
        tracks.value = emptyList()
        showLibrary()
        compose.waitForIdle()

        gate.hold()
        tracks.value = listOf(track(1, "Alder"))
        compose.waitForIdle()
        noResults().assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.no_local_music)).assertDoesNotExist()

        gate.release()
        waitForRowCount(1, 1)
        row(1).assertIsDisplayed()
    }

    @Test fun aNewModeKeepsTheListAndItsScrollPositionUntilItIsReady() {
        tracks.value = (0L..149L).map { track(it, "Track ${it.toString().padStart(3, '0')}") }
        showLibrary()
        settle()
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Track", substring = true)))
            .performScrollToIndex(80)
        compose.onNodeWithText("Track 080").assertIsDisplayed()

        gate.hold()
        compose.onNodeWithTag("vesqen.library.mode.albums").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Track 080").assertIsDisplayed()

        gate.release()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("vesqen.library.track.80").fetchSemanticsNodes().isEmpty()
        }

        compose.onNodeWithTag("vesqen.library.mode.songs").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("vesqen.library.track.80").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("Track 080").assertIsDisplayed()
    }

    @Test fun aReturnToThePageShowsItsListsWithoutWorkingThemOut() {
        showLibrary()
        settle()
        val shown = projections.shown
        val runs = gate.runs.get()

        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()

        row(1).assertIsDisplayed()
        assertSame(shown, projections.shown)
        assertEquals(runs, gate.runs.get())
    }

    @Test fun inputsOvertakenWhileAnotherRunsAreSkipped() {
        showLibrary()
        settle()
        compose.onNodeWithTag("vesqen.library.search-toggle").performClick()
        val runs = gate.runs.get()

        gate.hold()
        search("B")
        compose.waitUntil(5_000) { gate.runs.get() == runs + 1 }
        search("Be")
        compose.waitForIdle()
        search("Bet")
        compose.waitForIdle()
        search("Beta")
        compose.waitForIdle()
        assertEquals(runs + 1, gate.runs.get())

        gate.release()
        waitForRowCount(2, 0)
        row(3).assertIsDisplayed()
        // The one that was running, then only the newest.
        assertEquals(runs + 2, gate.runs.get())
    }

    @Test fun aSavedOrderStaysUntilItsListIsReady() {
        tracks.value = listOf(
            track(1, "Alder", favoritePosition = 1),
            track(2, "Bell", favoritePosition = 2),
        )
        showLibrary(onSaveTrackOrder = { _, _ ->
            tracks.value = tracks.value.map { it.copy(favoritePosition = 3 - it.favoritePosition!!) }
            true
        })
        settle()
        compose.onNodeWithTag("vesqen.library.favorites").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("vesqen.library.edit-order").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("vesqen.library.edit-order").performClick()

        gate.hold()
        compose.onNodeWithTag("vesqen.library.save-order").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("vesqen.library.save-order").assertIsDisplayed()

        gate.release()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("vesqen.library.save-order").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(listOf(2L, 1L), projections.shown?.favoriteVisibleTracks?.map(AudioTrack::id))
    }

    private fun track(id: Long, title: String, favoritePosition: Long? = null) = AudioTrack(
        id = id,
        contentUri = "content://fixture/$id",
        title = title,
        artist = "",
        album = "",
        durationMs = 1_000,
        isFavorite = favoritePosition != null,
        favoritePosition = favoritePosition,
    )
}
