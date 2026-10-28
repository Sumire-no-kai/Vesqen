package io.github.sumirenokai.vesqen.library

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** #165: a corrupt catalog is set aside as one copy instead of deleted, then rebuilt empty. */
class LibraryCatalogCorruptionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "library-catalog-corruption-test-${System.nanoTime()}.db"
    private val database = context.getDatabasePath(databaseName)
    private val copy = File(database.path + ".corrupt")

    @After
    fun removeDatabases() {
        SQLiteDatabase.deleteDatabase(database)
        SQLiteDatabase.deleteDatabase(copy)
    }

    @Test
    fun aCatalogThatCannotBeOpenedIsKeptAsOneCopyAndStartsEmpty() {
        LibraryCatalogStore(context, databaseName).use { store -> store.createPlaylist("Night") }
        // SQLite reports a damaged header as "file is not a database", which Android treats as corruption.
        val damaged = damage(database, fromByte = 0, length = 16)
        var queueClearedWithTheCatalogInPlace = false
        var beforeRebuildCalls = 0

        LibraryCatalogStore(
            context,
            databaseName,
            beforeRebuild = {
                beforeRebuildCalls++
                queueClearedWithTheCatalogInPlace = database.exists() && !copy.exists()
            },
        ).use { store ->
            assertEquals(emptyList<LibraryPlaylist>(), store.readPlaylists())
            assertEquals(1, store.rebuilds.value)
        }
        assertEquals(1, beforeRebuildCalls)
        assertTrue(queueClearedWithTheCatalogInPlace)
        assertArrayEquals(damaged, copy.readBytes())

        // A later corruption replaces the copy: the device keeps one at most.
        val damagedAgain = damage(database, fromByte = 0, length = 16)
        LibraryCatalogStore(context, databaseName).use { store -> store.readPlaylists() }
        assertArrayEquals(damagedAgain, copy.readBytes())
        val databases = database.parentFile!!.list()!!
            .filter { it.startsWith(databaseName) && JOURNAL_SUFFIXES.none(it::endsWith) }
        assertEquals(setOf(databaseName, copy.name), databases.toSet())
    }

    @Test
    fun aPageFoundCorruptWhileReadingIsKeptAndTheNextReadStartsEmpty() {
        val sourceId = LibraryCatalogStore(context, databaseName).use { store ->
            val source = store.ensureDeviceSource()
            val session = store.beginSourceScan(source.id)
            repeat(600) { index -> store.upsertTrack(session, candidate(index)) }
            store.finishSourceScan(source.id)
            source.id
        }
        // The schema stays readable, so the catalog opens; the last quarter of its pages holds rows.
        val pageSize = 4_096
        val pages = (database.length() / pageSize).toInt()
        damage(database, fromByte = (pages - pages / 4).toLong() * pageSize, length = pages / 4 * pageSize)
        var beforeRebuildCalls = 0

        LibraryCatalogStore(context, databaseName, beforeRebuild = { beforeRebuildCalls++ }).use { store ->
            try {
                store.readTracks(listOf(sourceId))
                fail("Reading the damaged pages must report corruption")
            } catch (expected: SQLiteDatabaseCorruptException) {
                // The handler ran before the exception reached here.
            }
            assertTrue(copy.exists())
            assertEquals(emptyList<AudioTrack>(), store.readTracks(listOf(store.ensureDeviceSource().id)))
            assertEquals(1, store.rebuilds.value)
        }
        assertEquals(1, beforeRebuildCalls)
    }

    /** Overwrites [length] bytes from [fromByte] and returns the whole damaged file. */
    private fun damage(file: File, fromByte: Long, length: Int): ByteArray {
        RandomAccessFile(file, "rw").use { access ->
            access.seek(fromByte)
            access.write(ByteArray(length) { 0x5A })
        }
        return file.readBytes()
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

    private companion object {
        val JOURNAL_SUFFIXES = listOf("-journal", "-wal", "-shm")
    }
}
