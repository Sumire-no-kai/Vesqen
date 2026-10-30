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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.screens.NowLyricsState
import io.github.sumirenokai.vesqen.ui.screens.NowScreen
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Lyrics on Now (#169): the stage switch, the states, the highlight and tap-to-seek. */
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
    fun theCoverAndTheSwitchToggleBetweenCoverAndLyrics() {
        var shown by mutableStateOf(false)
        setNow(lyricsShown = { shown }, onToggleLyrics = { shown = !shown })

        compose.onNodeWithTag("vesqen.now.artwork-stage").assertIsDisplayed()
        compose.onAllNodesWithTag("vesqen.now.lyrics").assertCountEquals(0)
        compose.onNodeWithTag("vesqen.now.artwork-stage").performClick()
        compose.onNodeWithTag("vesqen.now.lyrics").assertIsDisplayed()
        compose.onAllNodesWithTag("vesqen.now.artwork-stage").assertCountEquals(0)
        compose.onNodeWithTag("vesqen.now.lyrics-toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onNodeWithTag("vesqen.now.lyrics-toggle").performClick()
        compose.onNodeWithTag("vesqen.now.artwork-stage").assertIsDisplayed()
        assertFalse(shown)
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
    fun tappingALineSeeksToItsTime() {
        val seeks = mutableListOf<Long>()
        setNow(onSeek = { seeks += it })

        compose.onNodeWithTag("vesqen.now.lyrics.line.2").performClick()
        compose.onNodeWithTag("vesqen.now.lyrics.line.0").performClick()
        assertEquals(listOf(9_000L, 1_000L), seeks)
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
        setNow(lyrics = TrackLyrics.Unreadable)

        compose.onNodeWithText(context.getString(R.string.lyrics_unreadable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lyrics_unreadable_body)).assertIsDisplayed()
        compose.onNodeWithTag("vesqen.now.lyrics.import").assertIsDisplayed()
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
        setNow(width = 640.dp, height = 320.dp, landscape = true)

        compose.onNodeWithTag("vesqen.now.landscape-player").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.now.lyrics").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.now.player-page")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange))
    }

    private fun setNow(
        lyricsShown: () -> Boolean = { true },
        lyrics: TrackLyrics = TrackLyrics.Found(LyricsSource.SameNameFile, synced),
        imported: Set<Long> = emptySet(),
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
                        onSeek = onSeek,
                        onPlayQueueIndex = {},
                        onRemoveQueueItem = {},
                        onMoveQueueItem = { _, _ -> },
                        onClearQueue = {},
                        onRetryPlayback = {},
                        onToggleOrientation = {},
                        showOrientationToggle = false,
                        isLandscape = landscape,
                        motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                        lyrics = NowLyricsState(shown = lyricsShown(), imported = ImportedLyrics(imported, revision = 1)),
                        loadLyrics = { lyrics },
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
