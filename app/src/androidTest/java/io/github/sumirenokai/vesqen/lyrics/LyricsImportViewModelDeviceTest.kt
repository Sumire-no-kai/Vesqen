package io.github.sumirenokai.vesqen.lyrics

import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.AndroidLibraryCatalog
import io.github.sumirenokai.vesqen.library.LibrarySourceId
import io.github.sumirenokai.vesqen.library.LibraryTrackCandidate
import io.github.sumirenokai.vesqen.library.mediaStoreRemoteId
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.testing.FakeDocumentsProvider
import io.github.sumirenokai.vesqen.ui.LyricsImportNotice
import io.github.sumirenokai.vesqen.ui.LyricsImportTarget
import io.github.sumirenokai.vesqen.ui.VesqenViewModel
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

    @Test
    fun aTrackMergedWhileReadingCannotReceiveAnOrphanedImport() {
        val catalog = app.libraryCatalogStore
        val (from, into) = seedUnmatchedTracks()
        val target = LyricsImportTarget(from, requireNotNull(catalog.readTrackContentUri(from)), app.lyricsStore.generation())
        val documentId = "music/import-during-merge.lrc"
        val document = DocumentsContract.buildDocumentUri(FakeDocumentsProvider.authority(app.packageName), documentId)
        FakeDocumentsProvider.files[documentId] = "[00:01.00]Imported during merge".toByteArray()
        lateinit var viewModel: VesqenViewModel
        instrumentation.runOnMainSync { viewModel = VesqenViewModel(app) }
        try {
            FakeDocumentsProvider.beforeOpen = { opened ->
                check(opened == documentId)
                FakeDocumentsProvider.beforeOpen = null
                AndroidLibraryCatalog(app, catalog).use { otherCatalog ->
                    assertTrue(runBlocking { otherCatalog.assignUnmatchedRecord(into, from) })
                }
            }
            val notice = importAndAwait(viewModel, target, document)
            assertEquals(null, catalog.readTrackContentUri(from))
            assertEquals(LyricsImportResult.TRACK_CHANGED, notice.result)
            assertEquals(null, app.lyricsStore.read(from))
            assertEquals(null, app.lyricsStore.read(into))
        } finally {
            FakeDocumentsProvider.reset()
            app.lyricsStore.remove(from)
            app.lyricsStore.remove(into)
        }
    }

    @Test
    fun aMergeFromAnotherCatalogWaitsUntilTheLyricsWriteFinishes() = runBlocking {
        val (from, into) = seedUnmatchedTracks()
        val writing = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val store = app.lyricsStore
        val generation = store.generation()
        val bytes = "[00:01.00]Saved before the merge".toByteArray()
        val uri = requireNotNull(app.libraryCatalogStore.readTrackContentUri(from))
        val catalog = AndroidLibraryCatalog(app, app.libraryCatalogStore)
        val otherCatalog = AndroidLibraryCatalog(app, app.libraryCatalogStore)
        try {
            val imported = async(Dispatchers.IO) {
                catalog.withTrackContentUri(from) { currentUri ->
                    assertEquals(uri, currentUri)
                    writing.countDown()
                    check(finishWrite.await(10, TimeUnit.SECONDS)) { "The test did not release the lyrics write" }
                    store.import(from, bytes, generation)
                }
            }
            assertTrue("The lyrics write did not start", writing.await(10, TimeUnit.SECONDS))
            // Starts inline and reaches the shared catalog lock before returning to this test.
            val merged = async(start = CoroutineStart.UNDISPATCHED) { otherCatalog.assignUnmatchedRecord(into, from) }
            try {
                assertFalse("A merge must not delete the row between the identity check and save", merged.isCompleted)
            } finally {
                finishWrite.countDown()
            }
            assertEquals(LyricsImportResult.IMPORTED, imported.await())
            assertTrue(merged.await())
            assertEquals(null, store.read(from))
            assertEquals(bytes.decodeToString(), store.read(into)?.decodeToString())
        } finally {
            finishWrite.countDown()
            catalog.close()
            otherCatalog.close()
            store.remove(from)
            store.remove(into)
        }
    }

    private fun seedUnmatchedTracks(): Pair<Long, Long> {
        val catalog = app.libraryCatalogStore
        fun candidate(id: Long, title: String) = LibraryTrackCandidate(
            remoteId = mediaStoreRemoteId("external", id), contentUri = "content://media/external/audio/media/$id",
            title = title, artist = "Artist", album = title, durationMs = if (title == "A") 200_000 else 300_000,
            sizeBytes = 10_000, fileName = "01.flac", folderName = "CD1", fingerprint = "$id-$title",
        )
        fun scan(first: Long, second: Long) {
            val scan = catalog.beginMediaStoreScan(pathIdentifiesFile = false)
            listOf(candidate(first, "A"), candidate(second, "B")).forEach { track ->
                if (scan.firstOffer(track) && !catalog.markSeenIfUnchanged(scan, track)) catalog.upsertMediaStoreTrack(scan, track)
            }
            catalog.completeMediaStoreScan(scan.session, null, listOf("external"))
        }
        catalog.ensureDeviceSource()
        scan(81_001, 81_002)
        val record = catalog.readTracks(listOf(LibrarySourceId.DEVICE)).first { it.title == "A" }
        catalog.setFavorite(record.id, true)
        scan(81_003, 81_004)
        scan(81_003, 81_004)
        return catalog.readUnmatchedRecords().getValue(record.id).first() to record.id
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
