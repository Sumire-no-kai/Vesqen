package io.github.sumirenokai.vesqen.library

import android.content.ContentUris
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.playback.writeSilentWav
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
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

    @Test
    fun aScanStopsWhenAnotherCallerFindsTheCatalogCorrupt() = runBlocking {
        // Without the music permission, Android 10 and later list this app's own audio only, which
        // this test adds to MediaStore and removes again.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val handler = LibraryCatalogCorruptionHandler(beforeRebuild = {})
        val store = LibraryCatalogStore(context, databaseName, handler)
        val catalog = AndroidLibraryCatalog(context, store)
        val inserted = mutableListOf<Uri>()
        fun LibraryRefreshResult.fixtureIds() =
            snapshot.tracks.map { it.contentUri.substringAfterLast('/').toLong() }.filter { id -> inserted.any { ContentUris.parseId(it) == id } }.toSet()
        try {
            repeat(3) { inserted += insertAudio("catalog-rebuild-scan-$it") }
            awaitMediaStoreSettled()
            assertEquals(3, catalog.refresh(includeDeviceLibrary = true) {}.fixtureIds().size)
            // A new file moves MediaStore's generation, so the next scan reads every row again.
            inserted += insertAudio("catalog-rebuild-scan-3")

            // #165 U5: another caller finds the catalog corrupt as the scan starts on its rows.
            var reported = false
            val stopped = catalog.refresh(includeDeviceLibrary = true) {
                if (!reported) {
                    reported = true
                    handler.onCorruption(store.writableDatabase)
                }
            }
            assertTrue(reported)
            assertEquals(1, store.rebuilds.value)
            assertFalse("The scan stops instead of failing on the new catalog", stopped.hadFailure)
            assertEquals(emptyList<AudioTrack>(), stopped.snapshot.tracks)

            // The next scan reads the new catalog in full.
            awaitMediaStoreSettled()
            val rescanned = catalog.refresh(includeDeviceLibrary = true) {}
            assertFalse("Rescan: ${rescanned.snapshot.sources}", rescanned.hadFailure)
            assertEquals(inserted.map(ContentUris::parseId).toSet(), rescanned.fixtureIds())
        } finally {
            inserted.forEach { context.contentResolver.delete(it, null, null) }
            catalog.close()
            store.close()
        }
    }

    /** Adds one second of silence as this app's own audio, and waits until MediaStore lists it as music. */
    private fun insertAudio(name: String): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(
            resolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.wav")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/VesqenTest")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                },
            ),
        )
        val wav = File(context.cacheDir, "$name.wav").also { writeSilentWav(it, durationMs = 1_000) }
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output -> wav.inputStream().use { it.copyTo(output) } }
        } finally {
            wav.delete()
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!listedAsMusic(uri)) {
            check(SystemClock.uptimeMillis() < deadline) { "MediaStore never listed $name as music" }
            Thread.sleep(100)
        }
        return uri
    }

    /**
     * MediaStore keeps working on files for a while after they are added, moving its generation. The
     * scans that must complete wait until it stays put for a second, so they do not race that work.
     */
    private fun awaitMediaStoreSettled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        fun generation() = MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val deadline = SystemClock.uptimeMillis() + 20_000
        var last = generation()
        var unchangedSince = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - unchangedSince < 1_000) {
            check(SystemClock.uptimeMillis() < deadline) { "MediaStore kept changing" }
            Thread.sleep(100)
            val now = generation()
            if (now != last) {
                last = now
                unchangedSince = SystemClock.uptimeMillis()
            }
        }
    }

    private fun listedAsMusic(uri: Uri): Boolean = context.contentResolver.query(
        uri,
        arrayOf(MediaStore.Audio.Media.IS_MUSIC, MediaStore.Audio.Media.DURATION),
        null,
        null,
        null,
    ).use { cursor -> cursor != null && cursor.moveToFirst() && cursor.getInt(0) != 0 && cursor.getLong(1) > 0 }

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
