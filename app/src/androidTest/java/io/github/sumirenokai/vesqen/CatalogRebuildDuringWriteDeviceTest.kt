package io.github.sumirenokai.vesqen

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryTrackCandidate
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.VesqenViewModel
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #165 U4: a favorite that finds the catalog corrupt runs in a transaction, which then ends on the
 * database the corruption handler closed and fails with IllegalStateException. The app must not
 * crash; the Library handles it as the rebuild. Run on the isolated deviceTest application, whose
 * catalog this test replaces.
 */
class CatalogRebuildDuringWriteDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()

    @Test
    fun aFavoriteThatFindsTheCatalogCorruptIsHandledAsTheRebuild() {
        val store = app.libraryCatalogStore
        val database = app.getDatabasePath("library-catalog.db")
        val copy = File(database.path + ".corrupt")
        store.close()
        SQLiteDatabase.deleteDatabase(database)
        SQLiteDatabase.deleteDatabase(copy)
        val source = store.ensureDeviceSource()
        val session = store.beginSourceScan(source.id)
        repeat(600) { store.upsertTrack(session, candidate(it)) }
        store.finishSourceScan(source.id)
        val trackId = store.readTracks(listOf(source.id)).maxOf(AudioTrack::id)
        // Closing drops the cached pages, so the favorite's read of the tracks table meets the damage
        // in the last quarter of the file, inside its transaction.
        store.close()
        val pageSize = 4_096
        val pages = (database.length() / pageSize).toInt()
        RandomAccessFile(database, "rw").use { file ->
            file.seek((pages - pages / 4).toLong() * pageSize)
            file.write(ByteArray(pages / 4 * pageSize) { 0x5A })
        }
        val rebuildsBefore = store.rebuilds.value

        lateinit var viewModel: VesqenViewModel
        instrumentation.runOnMainSync { viewModel = VesqenViewModel(app) }
        try {
            instrumentation.runOnMainSync { viewModel.setFavorite(trackId, true) }
            // Without the fix, the write's IllegalStateException ends this process here.
            val deadline = SystemClock.uptimeMillis() + 20_000
            var library: LibraryUiState
            while (true) {
                lateinit var current: LibraryUiState
                instrumentation.runOnMainSync { current = viewModel.uiState.library }
                // The view model starts loading; only the scan that follows the rebuild finishes it.
                if (store.rebuilds.value != rebuildsBefore && !current.isLoading) {
                    library = current
                    break
                }
                check(SystemClock.uptimeMillis() < deadline) {
                    "The rebuild was not handled: rebuilds ${store.rebuilds.value}, loading ${current.isLoading}"
                }
                Thread.sleep(50)
            }
            assertEquals(rebuildsBefore + 1, store.rebuilds.value)
            assertFalse("A rebuild is not reported as a failed edit", library.catalogMutationFailed)
            assertEquals(emptyList<AudioTrack>(), library.tracks)
            assertTrue(copy.exists())
            assertTrue(app.libraryRebuildNotice.due.value)
        } finally {
            app.libraryRebuildNotice.markShown()
            SQLiteDatabase.deleteDatabase(copy)
        }
    }

    private fun candidate(index: Int) = LibraryTrackCandidate(
        remoteId = "fixture-$index",
        contentUri = "content://fixture/$index",
        title = "Track $index with a title long enough to fill pages",
        artist = "Artist $index",
        album = "Album ${index / 10}",
        durationMs = 60_000,
        fingerprint = "fixture-$index",
    )
}
