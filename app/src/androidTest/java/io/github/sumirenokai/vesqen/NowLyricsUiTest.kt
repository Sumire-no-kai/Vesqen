package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.lyrics.ImportedLyrics
import io.github.sumirenokai.vesqen.lyrics.LyricLine
import io.github.sumirenokai.vesqen.lyrics.Lyrics
import io.github.sumirenokai.vesqen.lyrics.LyricsSource
import io.github.sumirenokai.vesqen.lyrics.TrackLyrics
import io.github.sumirenokai.vesqen.lyrics.UnreadableReason
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.formatDuration
import io.github.sumirenokai.vesqen.ui.screens.NowLyricsState
import io.github.sumirenokai.vesqen.ui.screens.NowScreen
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Lyrics on Now (#169, #209): the stage switch, the states, the highlight and the seek line. */
class NowLyricsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val track = AudioTrack(
        id = 7,
        contentUri = "content://media/external/audio/media/7",
        title = "Evening",
        artist = "Sample Artist",
        album = "Sample Album",
        durationMs = 60_000,
        fileName = "Evening.flac",
    )
    private val synced = Lyrics.Synced(
        listOf(
            LyricLine(1_000, "Morning light", listOf("晨光")),
            LyricLine(5_000, "Over the hill"),
            LyricLine(9_000, "Into the valley"),
        ),
    )

    @Test
    fun aTapOnTheCoverOrTheLyricsSwitchesBetweenThem() {
        var shown by mutableStateOf(false)
        setNow(lyricsShown = { shown }, onToggleLyrics = { shown = !shown })

        compose.onAllNodesWithTag("vesqen.now.lyrics-toggle").assertCountEquals(0)
        compose.onNodeWithTag("vesqen.now.artwork-stage").assertIsDisplayed()
        compose.onAllNodesWithTag("vesqen.now.lyrics").assertCountEquals(0)
        compose.onNodeWithTag("vesqen.now.artwork-stage").performClick()
        compose.onNodeWithTag("vesqen.now.lyrics").assertIsDisplayed()
        compose.onAllNodesWithTag("vesqen.now.artwork-stage").assertCountEquals(0)
        compose.onNodeWithTag("vesqen.now.lyrics").performClick()
        compose.onNodeWithTag("vesqen.now.artwork-stage").assertIsDisplayed()
        assertFalse(shown)
    }

    @Test
    fun theStageAroundTheCoverCountsAndTheControlsKeepTheirActions() {
        var toggles = 0
        val beside = with(compose.density) { 8.dp.toPx() }
        fun tapPage(position: (cover: Rect) -> Offset) {
            val page = compose.onNodeWithTag("vesqen.now.player-page").fetchSemanticsNode().boundsInRoot
            val cover = compose.onNodeWithTag("vesqen.now.artwork-stage").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("vesqen.now.player-page").performTouchInput {
                click(position(cover) - page.topLeft)
            }
        }
        // A tall page leaves room above the cover, a short one beside it.
        setNow(lyricsShown = { false }, onToggleLyrics = { toggles++ }, width = 360.dp, height = 700.dp)
        tapPage { cover -> Offset(cover.center.x, cover.top - beside) }
        compose.runOnIdle { assertEquals(1, toggles) }
        setNow(lyricsShown = { false }, onToggleLyrics = { toggles++ }, width = 360.dp, height = 480.dp)
        tapPage { cover -> Offset(cover.left - beside, cover.center.y) }
        compose.runOnIdle { assertEquals(2, toggles) }

        compose.onNodeWithTag("vesqen.now.progress").performClick()
        compose.onNodeWithTag("vesqen.now.playback-order").performClick()
        compose.onNodeWithTag("vesqen.now.back").performClick()
        compose.runOnIdle { assertEquals(2, toggles) }
    }

    @Test
    fun aTapBringsTheCoverBackInEveryLyricsState() {
        listOf(
            TrackLyrics.Found(LyricsSource.Embedded, Lyrics.Plain(listOf("Plain line"))),
            TrackLyrics.None(folderHint = true),
            TrackLyrics.Unreadable(UnreadableReason.Encoding),
        ).forEach { state ->
            var toggles = 0
            setNow(lyrics = state, onToggleLyrics = { toggles++ })
            compose.onNodeWithTag("vesqen.now.lyrics").performClick()
            compose.runOnIdle { assertEquals(state.toString(), 1, toggles) }
        }
        var toggles = 0
        setNow(neverLoads = true, onToggleLyrics = { toggles++ })
        compose.onNodeWithTag("vesqen.now.lyrics").performClick()
        compose.runOnIdle { assertEquals("while loading", 1, toggles) }
    }

    @Test
    fun theImportButtonDoesNotSwitchTheView() {
        var toggles = 0
        val imports = mutableListOf<Long>()
        setNow(lyrics = TrackLyrics.None(folderHint = false), onToggleLyrics = { toggles++ }, onImportLyrics = { imports += it.id })

        compose.onNodeWithTag("vesqen.now.lyrics.import").performClick()
        compose.runOnIdle {
            assertEquals(listOf(track.id), imports)
            assertEquals(0, toggles)
        }
    }

    @Test
    fun accessibilityServicesReachTheCoverSwitchAndEachLine() {
        var toggles = 0
        val seeks = mutableListOf<Long>()
        setNow(onToggleLyrics = { toggles++ }, onSeek = { seeks += it })

        compose.onNodeWithTag("vesqen.now.lyrics.show-cover")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(context.getString(R.string.lyrics_show_cover))))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, toggles) }

        val goToLine = context.getString(R.string.lyrics_play_from_line)
        compose.onNodeWithTag("vesqen.now.lyrics.line.2").assert(
            SemanticsMatcher("Clicks with a named action") { it.config[SemanticsActions.OnClick].label == goToLine },
        )
        compose.onNodeWithTag("vesqen.now.lyrics.line.2").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("vesqen.now.lyrics.line.0").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(listOf(9_000L, 1_000L), seeks)
            assertEquals(1, toggles)
        }
    }

    @Test
    fun theCurrentLineIsBoldAndMovesWithThePosition() {
        var position by mutableStateOf(5_500L)
        setNow(positionMs = { position })

        compose.onNodeWithText("Over the hill", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(isBold("Over the hill"))
        assertFalse(isBold("Morning light"))
        assertFalse(isBold("晨光"))
        position = 1_200
        compose.waitForIdle()
        assertTrue(isBold("Morning light"))
        assertFalse(isBold("Over the hill"))
    }

    @Test
    fun aTapOnALineSwitchesTheViewAndDoesNotSeek() {
        var toggles = 0
        val seeks = mutableListOf<Long>()
        setNow(onToggleLyrics = { toggles++ }, onSeek = { seeks += it })

        compose.onNodeWithTag("vesqen.now.lyrics.line.2").performClick()
        compose.runOnIdle {
            assertEquals(1, toggles)
            assertTrue(seeks.isEmpty())
        }
    }

    @Test
    fun scrollingByTouchShowsTheSeekLineWithoutSwitching() {
        val long = Lyrics.Synced((0 until 40).map { LyricLine(it * 5_000L, "Line $it") })
        var toggles = 0
        setNow(lyrics = TrackLyrics.Found(LyricsSource.Embedded, long), positionMs = { 30 * 5_000L + 100 }, onToggleLyrics = { toggles++ })
        compose.onAllNodesWithTag("vesqen.now.lyrics.seek").assertCountEquals(0)

        compose.onNodeWithTag("vesqen.now.lyrics.lines").performTouchInput { swipeDown() }
        compose.onNodeWithTag("vesqen.now.lyrics.seek").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, toggles) }
    }

    @Test
    fun theSeekButtonGoesToTheTimeItShowsAndFollowingResumes() {
        val long = Lyrics.Synced((0 until 40).map { LyricLine(it * 5_000L, "Line $it") })
        var toggles = 0
        val seeks = mutableListOf<Long>()
        setNow(
            lyrics = TrackLyrics.Found(LyricsSource.Embedded, long),
            positionMs = { 30 * 5_000L + 100 },
            onToggleLyrics = { toggles++ },
            onSeek = { seeks += it },
        )

        // Still, so the time read below is the one the button uses.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("vesqen.now.lyrics.lines").performScrollToIndex(10)
        compose.mainClock.advanceTimeBy(1_000)
        val shownTime = compose.onNodeWithTag("vesqen.now.lyrics.seek-time")
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        compose.onNodeWithTag("vesqen.now.lyrics.seek").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(shownTime, formatDuration(seeks.single()))
        assertEquals(0, toggles)
        // The list goes straight back to following, well before the pause would have ended.
        compose.onAllNodesWithTag("vesqen.now.lyrics.seek").assertCountEquals(0)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun theSeekLineLeavesWhenFollowingResumesAndATapStillSwitches() {
        val long = Lyrics.Synced((0 until 40).map { LyricLine(it * 5_000L, "Line $it") })
        var toggles = 0
        setNow(lyrics = TrackLyrics.Found(LyricsSource.Embedded, long), positionMs = { 30 * 5_000L + 100 }, onToggleLyrics = { toggles++ })

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("vesqen.now.lyrics.lines").performScrollToIndex(1)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("vesqen.now.lyrics.seek").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.now.lyrics").performTouchInput { click(topCenter + Offset(0f, 4f)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1, toggles) }
        compose.mainClock.advanceTimeBy(3_500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onAllNodesWithTag("vesqen.now.lyrics.seek").assertCountEquals(0)
    }

    @Test
    fun aTapDuringAFlingOnlyStopsIt() {
        val long = Lyrics.Synced((0 until 40).map { LyricLine(it * 5_000L, "Line $it") })
        var toggles = 0
        setNow(lyrics = TrackLyrics.Found(LyricsSource.Embedded, long), onToggleLyrics = { toggles++ })

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("vesqen.now.lyrics.lines").performTouchInput { swipeUp(durationMillis = 80) }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithTag("vesqen.now.lyrics.lines").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(0, toggles) }
    }

    @Test
    fun onlyTheLyricsScrollAndNoLineIsALiveRegion() {
        setNow()

        compose.onNodeWithTag("vesqen.now.player-page")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange))
        compose.onNodeWithTag("vesqen.now.lyrics.lines")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        compose.onAllNodesWithTag("vesqen.now.lyrics.line.0").assertCountEquals(1)
        compose.onNodeWithTag("vesqen.now.lyrics.line.0")
            .assert(hasText("Morning light"))
            .assert(hasText("晨光"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun scrollingPausesFollowingUntilFourSecondsAfterItStops() {
        val long = Lyrics.Synced((0 until 40).map { LyricLine(it * 5_000L, "Line $it") })
        setNow(lyrics = TrackLyrics.Found(LyricsSource.Embedded, long), positionMs = { 30 * 5_000L + 100 })
        compose.onNodeWithTag("vesqen.now.lyrics.line.30").assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        // A scroll through the semantics action, as Switch Access and Voice Access send it.
        compose.onNodeWithTag("vesqen.now.lyrics.lines").performScrollToIndex(1)
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("vesqen.now.lyrics.line.30").assertIsNotDisplayed()
        compose.mainClock.advanceTimeBy(1_500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("vesqen.now.lyrics.line.30").assertIsDisplayed()
    }

    @Test
    fun aDragHeldStillKeepsFollowingPausedUntilItEnds() {
        val long = Lyrics.Synced((0 until 40).map { LyricLine(it * 5_000L, "Line $it") })
        setNow(lyrics = TrackLyrics.Found(LyricsSource.Embedded, long), positionMs = { 30 * 5_000L + 100 })
        compose.onNodeWithTag("vesqen.now.lyrics.line.30").assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("vesqen.now.lyrics.lines").performTouchInput {
            down(center)
            moveBy(Offset(0f, height * 0.8f))
            moveBy(Offset(0f, height * 0.8f))
        }
        // Held still for longer than the pause: following must not pull the list back.
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("vesqen.now.lyrics.line.30").assertIsNotDisplayed()
        compose.onNodeWithTag("vesqen.now.lyrics.lines").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("vesqen.now.lyrics.line.30").assertIsNotDisplayed()
        compose.mainClock.advanceTimeBy(1_500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("vesqen.now.lyrics.line.30").assertIsDisplayed()
    }

    @Test
    fun aNewSourcesRevisionReadsTheLyricsAgain() {
        var found by mutableStateOf(false)
        var revision by mutableStateOf(0L)
        setNow(
            loader = { if (found) TrackLyrics.Found(LyricsSource.SameNameFile, synced) else TrackLyrics.None(folderHint = false) },
            sourcesRevision = { revision },
        )
        compose.onNodeWithText(context.getString(R.string.lyrics_none)).assertIsDisplayed()

        // A same-name file appeared while the app was in the background.
        found = true
        revision = 1
        compose.waitForIdle()
        compose.onNodeWithTag("vesqen.now.lyrics.line.0").assertIsDisplayed()
    }

    @Test
    fun theRemoveConfirmationClosesWhenTheTrackChanges() {
        val next = track.copy(id = 8, contentUri = "content://media/external/audio/media/8", title = "Morning")
        var current by mutableStateOf(track)
        val removals = mutableListOf<Long>()
        setNow(imported = setOf(track.id, next.id), currentTrack = { current }, onRemoveLyrics = { removals += it })

        compose.onNodeWithTag("vesqen.now.more").performClick()
        compose.onNodeWithTag("vesqen.now.remove-lyrics").performClick()
        compose.onNodeWithText(context.getString(R.string.lyrics_remove_confirm_body)).assertIsDisplayed()
        current = next
        compose.waitForIdle()
        compose.onAllNodesWithText(context.getString(R.string.lyrics_remove_confirm_body)).assertCountEquals(0)

        compose.onNodeWithTag("vesqen.now.more").performClick()
        compose.onNodeWithTag("vesqen.now.remove-lyrics").performClick()
        compose.onNodeWithText(context.getString(R.string.lyrics_remove_confirm_action)).performClick()
        assertEquals(listOf(next.id), removals)
    }

    @Test
    fun importFromTrackInformationClosesTheSheetFirst() {
        val imports = mutableListOf<Long>()
        setNow(onImportLyrics = { imports += it.id })

        compose.onNodeWithTag("vesqen.now.more").performClick()
        compose.onNodeWithTag("vesqen.now.more.info").performClick()
        compose.onNodeWithTag("vesqen.track-details.import-lyrics").performScrollTo().performClick()

        assertEquals(listOf(track.id), imports)
        compose.onAllNodesWithTag("vesqen.track-details").assertCountEquals(0)
    }

    @Test
    fun withoutLyricsTheStageOffersImportAndTheFolderHint() {
        val imports = mutableListOf<Long>()
        setNow(lyrics = TrackLyrics.None(folderHint = true), onImportLyrics = { imports += it.id })

        compose.onNodeWithText(context.getString(R.string.lyrics_none)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lyrics_folder_hint)).assertIsDisplayed()
        compose.onNodeWithTag("vesqen.now.lyrics.import").performClick()
        assertEquals(listOf(track.id), imports)
    }

    @Test
    fun unreadableLyricsSayWhatToDo() {
        setNow(lyrics = TrackLyrics.Unreadable(UnreadableReason.Encoding))

        compose.onNodeWithText(context.getString(R.string.lyrics_unreadable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lyrics_unreadable_body)).assertIsDisplayed()
        compose.onNodeWithTag("vesqen.now.lyrics.import").assertIsDisplayed()
    }

    // #191 C6: a file too large, or one that could not be read just now, is not blamed on its encoding.
    @Test
    fun unreadableLyricsSayWhy() {
        var reason by mutableStateOf(UnreadableReason.TooLarge)
        var revision by mutableStateOf(0L)
        setNow(loader = { TrackLyrics.Unreadable(reason) }, sourcesRevision = { revision })

        compose.onNodeWithText(context.getString(R.string.lyrics_too_large_body)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.lyrics_unreadable_body)).assertCountEquals(0)

        reason = UnreadableReason.ReadFailed
        revision = 1
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.lyrics_read_failed_body)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.lyrics_unreadable_body)).assertCountEquals(0)
    }

    @Test
    fun moreMenuImportsAndRemovesAfterConfirmation() {
        val imports = mutableListOf<Long>()
        val removals = mutableListOf<Long>()
        setNow(
            imported = setOf(track.id),
            onImportLyrics = { imports += it.id },
            onRemoveLyrics = { removals += it },
        )

        compose.onNodeWithTag("vesqen.now.more").performClick()
        compose.onNodeWithTag("vesqen.now.import-lyrics").performClick()
        assertEquals(listOf(track.id), imports)
        compose.onNodeWithTag("vesqen.now.more").performClick()
        compose.onNodeWithTag("vesqen.now.remove-lyrics").performClick()
        compose.onNodeWithText(context.getString(R.string.lyrics_remove_confirm_body)).assertIsDisplayed()
        assertTrue(removals.isEmpty())
        compose.onNodeWithText(context.getString(R.string.lyrics_remove_confirm_action)).performClick()
        assertEquals(listOf(track.id), removals)
    }

    @Test
    fun removeIsOfferedOnlyForImportedLyricsAndTrackInformationNamesTheSource() {
        setNow(lyrics = TrackLyrics.Found(LyricsSource.Embedded, synced))

        compose.onNodeWithTag("vesqen.now.more").performClick()
        compose.onAllNodesWithTag("vesqen.now.remove-lyrics").assertCountEquals(0)
        compose.onNodeWithTag("vesqen.now.more.info").performClick()
        compose.onNodeWithText(context.getString(R.string.lyrics_source_embedded)).assertExists()
    }

    @Test
    fun narrowLargeTextAndLandscapeKeepThePagesStill() {
        listOf(
            Triple(320.dp, 533.dp, 2f),
            Triple(360.dp, 720.dp, 1f),
        ).forEach { (width, height, fontScale) ->
            setNow(width = width, height = height, fontScale = fontScale)
            compose.onNodeWithTag("vesqen.now.lyrics").assertIsDisplayed()
            compose.onNodeWithTag("vesqen.now.player-page")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange))
            compose.onNodeWithTag("vesqen.now.transport-dock").assertIsDisplayed()
        }
    }

    @Test
    fun landscapePutsTheLyricsInTheLeftPane() {
        var shown by mutableStateOf(true)
        setNow(lyricsShown = { shown }, onToggleLyrics = { shown = !shown }, width = 640.dp, height = 320.dp, landscape = true)

        compose.onNodeWithTag("vesqen.now.landscape-player").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.now.lyrics").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.now.player-page")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange))
        compose.onNodeWithTag("vesqen.now.lyrics").performClick()
        compose.onNodeWithTag("vesqen.now.artwork-stage").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.now.artwork-stage").performClick()
        compose.onNodeWithTag("vesqen.now.lyrics").assertIsDisplayed()
    }

    private fun setNow(
        lyricsShown: () -> Boolean = { true },
        lyrics: TrackLyrics = TrackLyrics.Found(LyricsSource.SameNameFile, synced),
        loader: (() -> TrackLyrics)? = null,
        neverLoads: Boolean = false,
        sourcesRevision: () -> Long = { 0L },
        imported: Set<Long> = emptySet(),
        currentTrack: () -> AudioTrack = { track },
        positionMs: () -> Long = { 0L },
        onToggleLyrics: () -> Unit = {},
        onSeek: (Long) -> Unit = {},
        onImportLyrics: (AudioTrack) -> Unit = {},
        onRemoveLyrics: (Long) -> Unit = {},
        width: Dp = 360.dp,
        height: Dp = 720.dp,
        fontScale: Float = 1f,
        landscape: Boolean = false,
    ) {
        screen.value = {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.size(width, height)) {
                    val track = currentTrack()
                    NowScreen(
                        snapshot = PlaybackSnapshot(
                            isControllerReady = true,
                            isPlaying = false,
                            trackId = track.id,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            durationMs = track.durationMs,
                            positionMs = positionMs(),
                            queueSize = 1,
                        ),
                        currentTrack = track,
                        artworkTrack = track,
                        onBackToLibrary = {},
                        onOpenChain = {},
                        onCyclePlaybackOrder = {},
                        onPrevious = {},
                        onPlayPause = {},
                        onNext = {},
                        onSeek = { position, _ -> onSeek(position) },
                        onPlayQueueIndex = {},
                        onRemoveQueueItem = {},
                        onMoveQueueItem = { _, _ -> },
                        onClearQueue = {},
                        onRetryPlayback = {},
                        onToggleOrientation = {},
                        showOrientationToggle = false,
                        isLandscape = landscape,
                        motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                        lyrics = NowLyricsState(
                            shown = lyricsShown(),
                            imported = ImportedLyrics(imported, revision = 1),
                            sourcesRevision = sourcesRevision(),
                        ),
                        loadLyrics = {
                            if (neverLoads) awaitCancellation()
                            loader?.invoke() ?: lyrics
                        },
                        onToggleLyrics = onToggleLyrics,
                        onImportLyrics = onImportLyrics,
                        onRemoveLyrics = onRemoveLyrics,
                    )
                }
            }
        }
        // One composition per test; a later call swaps what it shows.
        if (!contentSet) {
            compose.setContent { VesqenTheme(darkTheme = true) { screen.value?.invoke() } }
            contentSet = true
        }
        compose.waitForIdle()
    }

    private var contentSet = false
    private val screen = mutableStateOf<(@Composable () -> Unit)?>(null)

    private fun isBold(text: String): Boolean {
        val node = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.fontWeight == FontWeight.Bold
    }
}
