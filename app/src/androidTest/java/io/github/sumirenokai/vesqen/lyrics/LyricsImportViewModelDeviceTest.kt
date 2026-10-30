package io.github.sumirenokai.vesqen.lyrics

import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.LibraryTrackCandidate
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.ui.LyricsImportNotice
import io.github.sumirenokai.vesqen.ui.LyricsImportTarget
import io.github.sumirenokai.vesqen.ui.VesqenViewModel
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #169: an import picked for a catalog row goes through the view model, the catalog check and the
 * store to the notice the snackbar shows, and removal goes back through the store. Runs on the
 * isolated deviceTest application, whose catalog gets one fixture row.
 */
class LyricsImportViewModelDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()
    private val fixture = "lyrics-import-${UUID.randomUUID()}"

    @Test
    fun importsAndRemovalsReachTheStoreAndTheNotice() {
        val catalog = app.libraryCatalogStore
        val source = catalog.ensureDeviceSource()
        val session = catalog.beginSourceScan(source.id)
        val contentUri = "content://fixture/$fixture"
        catalog.upsertTrack(
            session,
            LibraryTrackCandidate(
                remoteId = fixture,
                contentUri = contentUri,
                title = "Lyrics import fixture",
                artist = "Artist",
                album = "Album",
                durationMs = 60_000,
                fingerprint = fixture,
            ),
        )
        catalog.finishSourceScan(source.id)
        val track = catalog.readTracks(listOf(source.id)).single { it.contentUri == contentUri }
        val lyricsFile = File(app.cacheDir, "$fixture.lrc").apply { writeText("[00:01.00]Imported line") }
        val korean = File(app.cacheDir, "$fixture-ko.lrc").apply {
            writeBytes("[00:01.00]바람이 부는 저녁 거리\n[00:05.00]우리는 강을 따라 천천히 걸었다\n[00:09.00]내일 다시 만날 수 있을까".toByteArray(Charset.forName("EUC-KR")))
        }
        lateinit var viewModel: VesqenViewModel
        instrumentation.runOnMainSync { viewModel = VesqenViewModel(app) }
        val store = app.lyricsStore
        try {
            val generation = store.generation()
            val target = LyricsImportTarget(track.id, contentUri, generation)

            assertEquals(LyricsImportResult.IMPORTED, importAndAwait(viewModel, target, lyricsFile).result)
            assertTrue(track.id in store.importedLyrics.value.trackIds)
            assertEquals("[00:01.00]Imported line", store.read(track.id)?.decodeToString())

            // A refused file leaves the earlier import alone.
            assertEquals(LyricsImportResult.UNREADABLE, importAndAwait(viewModel, target, korean).result)
            assertEquals("[00:01.00]Imported line", store.read(track.id)?.decodeToString())

            // The ID names another file now, or the catalog was rebuilt since the picker opened.
            assertEquals(
                LyricsImportResult.TRACK_CHANGED,
                importAndAwait(viewModel, target.copy(contentUri = "content://fixture/another"), lyricsFile).result,
            )
            assertEquals(
                LyricsImportResult.TRACK_CHANGED,
                importAndAwait(viewModel, target.copy(generation = generation + 1), lyricsFile).result,
            )

            instrumentation.runOnMainSync { viewModel.removeLyrics(track.id) }
            awaitTrue("The removal did not reach the store") { track.id !in store.importedLyrics.value.trackIds }
            assertEquals(null, store.read(track.id))
        } finally {
            store.remove(track.id)
            lyricsFile.delete()
            korean.delete()
        }
    }

    private fun importAndAwait(viewModel: VesqenViewModel, target: LyricsImportTarget, file: File): LyricsImportNotice {
        var before: LyricsImportNotice? = null
        instrumentation.runOnMainSync {
            before = viewModel.uiState.lyricsImportNotice
            viewModel.importLyrics(target, Uri.fromFile(file))
        }
        lateinit var notice: LyricsImportNotice
        awaitTrue("No notice for the import") {
            var current: LyricsImportNotice? = null
            instrumentation.runOnMainSync { current = viewModel.uiState.lyricsImportNotice }
            current?.takeIf { it != before }?.also { notice = it } != null
        }
        instrumentation.runOnMainSync { viewModel.lyricsImportNoticeShown(notice.id) }
        instrumentation.runOnMainSync { assertFalse(viewModel.uiState.lyricsImportNotice == notice) }
        return notice
    }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            Thread.sleep(20)
        }
    }
}
