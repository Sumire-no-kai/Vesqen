package io.github.sumirenokai.vesqen.lyrics

import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryTrackCandidate
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.testing.FakeDocumentsProvider
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
        val contentUri = "content://fixture/$fixture"
        val track = addFixtureTrack(contentUri)
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

            val imported = importAndAwait(viewModel, target, Uri.fromFile(lyricsFile))
            assertEquals(LyricsImportResult.IMPORTED, imported.result)
            assertEquals(track.id, imported.trackId)
            assertTrue(track.id in store.importedLyrics.value.trackIds)
            assertEquals("[00:01.00]Imported line", store.read(track.id)?.decodeToString())

            // A refused file leaves the earlier import alone.
            assertEquals(LyricsImportResult.UNREADABLE, importAndAwait(viewModel, target, Uri.fromFile(korean)).result)
            assertEquals("[00:01.00]Imported line", store.read(track.id)?.decodeToString())

            // The ID names another file now, or the catalog was rebuilt since the picker opened.
            assertEquals(
                LyricsImportResult.TRACK_CHANGED,
                importAndAwait(viewModel, target.copy(contentUri = "content://fixture/another"), Uri.fromFile(lyricsFile)).result,
            )
            assertEquals(
                LyricsImportResult.TRACK_CHANGED,
                importAndAwait(viewModel, target.copy(generation = generation + 1), Uri.fromFile(lyricsFile)).result,
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

    // #191 D3: with targetSdk 26+ ContentResolver rethrows what the provider threw. Each of these
    // used to escape the import's coroutine and end the app; now the import fails and says so.
    @Test
    fun whatAProviderThrowsFailsTheImportWithoutEndingTheApp() {
        val contentUri = "content://fixture/$fixture"
        val track = addFixtureTrack(contentUri)
        val document = DocumentsContract.buildDocumentUri(FakeDocumentsProvider.authority(app.packageName), "music/song.lrc")
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]From a provider".toByteArray()
        lateinit var viewModel: VesqenViewModel
        instrumentation.runOnMainSync { viewModel = VesqenViewModel(app) }
        val target = LyricsImportTarget(track.id, contentUri, app.lyricsStore.generation())
        try {
            listOf(
                IllegalStateException("provider"),
                UnsupportedOperationException("provider"),
                IllegalArgumentException("provider"),
                NullPointerException("provider died"),
            ).forEach { failure ->
                FakeDocumentsProvider.openFailures["music/song.lrc"] = failure
                assertEquals(failure.toString(), LyricsImportResult.FAILED, importAndAwait(viewModel, target, document).result)
            }
            assertFalse(track.id in app.lyricsStore.importedLyrics.value.trackIds)

            // The same provider answering again imports as usual.
            FakeDocumentsProvider.openFailures.clear()
            assertEquals(LyricsImportResult.IMPORTED, importAndAwait(viewModel, target, document).result)
        } finally {
            FakeDocumentsProvider.reset()
            app.lyricsStore.remove(track.id)
        }
    }

    private fun addFixtureTrack(contentUri: String): AudioTrack {
        val catalog = app.libraryCatalogStore
        val source = catalog.ensureDeviceSource()
        val session = catalog.beginSourceScan(source.id)
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
        return catalog.readTracks(listOf(source.id)).single { it.contentUri == contentUri }
    }

    private fun importAndAwait(viewModel: VesqenViewModel, target: LyricsImportTarget, source: Uri): LyricsImportNotice {
        var before: LyricsImportNotice? = null
        instrumentation.runOnMainSync {
            before = viewModel.uiState.lyricsImportNotice
            viewModel.importLyrics(target, source)
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
