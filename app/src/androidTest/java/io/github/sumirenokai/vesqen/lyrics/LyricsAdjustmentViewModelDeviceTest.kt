package io.github.sumirenokai.vesqen.lyrics

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryTrackCandidate
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.ui.VesqenViewModel
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the catalog identity guard and application-owned resolver without a rendered UI. */
class LyricsAdjustmentViewModelDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()

    @Test
    fun adjustmentAndResetUseOneEffectiveTimelineWithoutChangingTheImportedFile() = runBlocking {
        withFixture { viewModel, track ->
            val original = "[offset:500]\n[00:02.00]One\n[00:05.00]Two".toByteArray()
            import(track, original)
            val before = app.lyricsResolver.load(track) as TrackLyrics.Found
            assertEquals(1_500L, before.synced().lines.first().timeMs)

            assertEquals(LyricsAdjustmentResult.APPLIED, adjust(viewModel, track, before, LyricsAdjustmentAction.ADVANCE))
            val advanced = app.lyricsResolver.load(track) as TrackLyrics.Found
            assertEquals(500L, advanced.userAdvanceMs)
            assertEquals(1_000L, advanced.synced().lines.first().timeMs)
            assertEquals(0, advanced.synced().lineIndexAt(1_000))

            assertEquals(LyricsAdjustmentResult.APPLIED, adjust(viewModel, track, advanced, LyricsAdjustmentAction.RESET))
            val reset = app.lyricsResolver.load(track) as TrackLyrics.Found
            assertEquals(0L, reset.userAdvanceMs)
            assertEquals(1_500L, reset.synced().lines.first().timeMs)
            assertArrayEquals(original, app.lyricsStore.read(track.id))
        }
    }

    @Test
    fun replacingTheLyricsRejectsAnAdjustmentForThePreviouslyReadContent() = runBlocking {
        withFixture { viewModel, track ->
            import(track, "[00:02.00]Old version".toByteArray())
            val previous = app.lyricsResolver.load(track) as TrackLyrics.Found
            import(track, "[00:07.00]New version".toByteArray())

            assertEquals(
                LyricsAdjustmentResult.LYRICS_CHANGED,
                adjust(viewModel, track, previous, LyricsAdjustmentAction.ADVANCE),
            )
            val current = app.lyricsResolver.load(track) as TrackLyrics.Found
            assertEquals(0L, current.userAdvanceMs)
            assertEquals(7_000L, current.synced().lines.first().timeMs)
        }
    }

    @Test
    fun theCatalogGuardRefusesAWriteWhenTheIdNoLongerNamesTheRequestedUri() = runBlocking {
        withFixture { viewModel, track ->
            import(track, "[00:02.00]A stable import".toByteArray())
            val current = app.lyricsResolver.load(track) as TrackLyrics.Found
            val staleIdentity = track.copy(contentUri = "content://fixture/another-file")

            assertEquals(
                LyricsAdjustmentResult.TRACK_CHANGED,
                adjust(viewModel, staleIdentity, current, LyricsAdjustmentAction.ADVANCE),
            )
            val unchanged = app.lyricsResolver.load(track) as TrackLyrics.Found
            assertEquals(0L, unchanged.userAdvanceMs)
            assertEquals(2_000L, unchanged.synced().lines.first().timeMs)
        }
    }

    private suspend fun adjust(
        viewModel: VesqenViewModel,
        track: AudioTrack,
        expected: TrackLyrics.Found,
        action: LyricsAdjustmentAction,
    ): LyricsAdjustmentResult = withContext(Dispatchers.Main) {
        viewModel.adjustLyricsTime(track, expected, action)
    }

    private fun import(track: AudioTrack, bytes: ByteArray) {
        assertEquals(LyricsImportResult.IMPORTED, app.lyricsStore.import(track.id, bytes, app.lyricsStore.generation()))
    }

    private suspend fun withFixture(test: suspend (VesqenViewModel, AudioTrack) -> Unit) = withTimeout(10_000) {
        val catalog = app.libraryCatalogStore
        val id = "lyrics-adjustment-${UUID.randomUUID()}"
        val source = catalog.ensureDeviceSource()
        val session = catalog.beginSourceScan(source.id)
        val contentUri = "content://fixture/$id"
        catalog.upsertTrack(
            session,
            LibraryTrackCandidate(
                remoteId = id,
                contentUri = contentUri,
                title = "Lyrics adjustment fixture",
                artist = "Artist",
                album = "Album",
                durationMs = 60_000,
                fingerprint = id,
            ),
        )
        catalog.finishSourceScan(source.id)
        val track = catalog.readTracks(listOf(source.id)).single { it.contentUri == contentUri }
        val models = ViewModelStore()
        lateinit var viewModel: VesqenViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[VesqenViewModel::class.java]
        }
        try {
            test(viewModel, track)
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            app.lyricsStore.remove(track.id)
        }
    }

    private fun TrackLyrics.Found.synced(): Lyrics.Synced = lyrics as Lyrics.Synced
}
