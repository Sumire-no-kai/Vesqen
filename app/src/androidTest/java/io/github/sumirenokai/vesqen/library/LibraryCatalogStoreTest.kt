package io.github.sumirenokai.vesqen.library

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogStoreTest {
    @Test
    fun rich_metadata_history_and_playlists_survive_reopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-test-${System.nanoTime()}.db"
        try {
            val trackId = LibraryCatalogStore(context, databaseName).use { store ->
                val source = store.ensureDeviceSource()
                val session = store.beginSourceScan(source.id)
                store.upsertTrack(
                    session,
                    LibraryTrackCandidate(
                        remoteId = "fixture-1",
                        contentUri = "content://fixture/1",
                        title = "Signal",
                        artist = "Mori",
                        album = "Quiet",
                        durationMs = 60_000,
                        sizeBytes = 1_024,
                        mimeType = "audio/flac",
                        albumArtist = "Mori",
                        trackNumber = 1,
                        discNumber = 1,
                        year = 2026,
                        genre = "Ambient",
                        fileName = "signal.flac",
                        folderName = "Music/Quiet",
                        codec = "FLAC",
                        channelCount = 2,
                        bitDepth = 24,
                        sampleRateHz = 96_000,
                        bitrate = 2_304_000,
                        fingerprint = "fixture",
                    ),
                )
                store.finishSourceScan(source.id)
                val inserted = store.readTracks(listOf(source.id)).single()
                store.setFavorite(inserted.id, true)
                store.recordPlayback(inserted.id, 123_456)
                val playlistId = requireNotNull(store.createPlaylist("Night"))
                store.addTrackToPlaylist(playlistId, inserted.id)
                inserted.id
            }

            LibraryCatalogStore(context, databaseName).use { reopened ->
                val sourceId = reopened.ensureDeviceSource().id
                val track = reopened.readTracks(listOf(sourceId)).single()
                assertEquals(trackId, track.id)
                assertEquals("FLAC", track.codec)
                assertEquals(24, track.bitDepth)
                assertEquals(96_000, track.sampleRateHz)
                assertTrue(track.isFavorite)
                assertEquals(1, track.playCount)
                assertEquals(listOf(trackId), reopened.readPlaylists().single().trackIds)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun playbackHistoryWriteReturnsCommittedValuesAndIgnoresMissingTracks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-history-${System.nanoTime()}.db"
        try {
            LibraryCatalogStore(context, databaseName).use { store ->
                val source = store.ensureDeviceSource()
                val scan = store.beginSourceScan(source.id)
                store.upsertTrack(scan, candidate("history", "History"))
                store.completeSourceScan(scan, generation = "1")
                val trackId = store.readTracks(listOf(source.id)).single().id

                assertEquals(
                    TrackPlaybackHistory(playCount = 1, lastPlayedAtMs = 100),
                    store.recordPlayback(trackId, playedAtMs = 100),
                )
                assertEquals(
                    TrackPlaybackHistory(playCount = 2, lastPlayedAtMs = 200),
                    store.recordPlayback(trackId, playedAtMs = 200),
                )
                assertNull(store.recordPlayback(trackId + 999, playedAtMs = 300))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun playlistSnapshotPreservesEmptyPlaylistsAndItemOrderAcrossMultiplePlaylists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-playlists-${System.nanoTime()}.db"
        try {
            LibraryCatalogStore(context, databaseName).use { store ->
                val source = store.ensureDeviceSource()
                val scan = store.beginSourceScan(source.id)
                store.upsertTrack(scan, candidate("first", "First"))
                store.upsertTrack(scan, candidate("second", "Second"))
                store.completeSourceScan(scan, generation = "1")
                val tracks = store.readTracks(listOf(source.id)).associateBy(AudioTrack::title)
                val emptyId = requireNotNull(store.createPlaylist("A Empty"))
                val mixId = requireNotNull(store.createPlaylist("B Mix"))
                store.addTrackToPlaylist(mixId, requireNotNull(tracks["Second"]).id)
                store.addTrackToPlaylist(mixId, requireNotNull(tracks["First"]).id)

                val playlists = store.readPlaylists()

                assertEquals(listOf(emptyId, mixId), playlists.map(LibraryPlaylist::id))
                assertTrue(playlists.first().trackIds.isEmpty())
                assertEquals(
                    listOf(requireNotNull(tracks["Second"]).id, requireNotNull(tracks["First"]).id),
                    playlists.last().trackIds,
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun version1UpgradeInvalidatesOldScanValidatorsAndAddsRichMetadataSchema() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-v1-${System.nanoTime()}.db"
        try {
            createLegacyFixture(context, databaseName, version = 1)

            LibraryCatalogStore(context, databaseName).use { upgraded ->
                val source = upgraded.readSources().single()
                val track = upgraded.readTracks(listOf(source.id)).single()

                assertNull(source.generation)
                assertEquals("", track.codec)
                assertEquals(0, track.playCount)
                assertEquals(4, upgraded.readableDatabase.version)
            }
            assertEquals("", readTrackFingerprint(context, databaseName))
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun version2UpgradeForcesOneReconciliationWithoutLosingUserState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-v2-${System.nanoTime()}.db"
        try {
            createLegacyFixture(context, databaseName, version = 2)

            LibraryCatalogStore(context, databaseName).use { upgraded ->
                val source = upgraded.readSources().single()
                val track = upgraded.readTracks(listOf(source.id)).single()

                assertNull(source.generation)
                assertTrue(track.isFavorite)
                assertEquals(4, track.playCount)
                assertEquals(456_000, track.lastPlayedAtMs)
                assertEquals(listOf(track.id), upgraded.readPlaylists().single().trackIds)
                assertEquals(4, upgraded.readableDatabase.version)
            }
            assertEquals("", readTrackFingerprint(context, databaseName))
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun completingScanPrunesAndPublishesGenerationTogether() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-complete-${System.nanoTime()}.db"
        try {
            LibraryCatalogStore(context, databaseName).use { store ->
                val source = store.ensureDeviceSource()
                val firstSession = store.beginSourceScan(source.id)
                store.upsertTrack(firstSession, candidate("old", "Old"))
                store.completeSourceScan(firstSession, generation = "1")

                val secondSession = store.beginSourceScan(source.id)
                store.upsertTrack(secondSession, candidate("new", "New"))
                store.writableDatabase.execSQL(
                    """
                    CREATE TRIGGER fail_scan_completion
                    BEFORE UPDATE ON library_sources
                    BEGIN
                        SELECT RAISE(ABORT, 'forced completion failure');
                    END
                    """.trimIndent(),
                )
                try {
                    store.completeSourceScan(secondSession, generation = "2")
                    throw AssertionError("Expected the fixture trigger to abort completion")
                } catch (_: SQLiteException) {
                    // Expected: the unseen-row delete must roll back with the source update.
                } finally {
                    store.writableDatabase.execSQL("DROP TRIGGER fail_scan_completion")
                }

                assertEquals(
                    listOf("New", "Old"),
                    store.readTracks(listOf(source.id)).map(AudioTrack::title),
                )
                val interruptedCompletion = store.readSources().single()
                assertEquals("1", interruptedCompletion.generation)
                assertEquals(LibraryScanState.SCANNING, interruptedCompletion.scanState)

                store.completeSourceScan(secondSession, generation = "2")

                assertEquals(listOf("New"), store.readTracks(listOf(source.id)).map(AudioTrack::title))
                val completedSource = store.readSources().single()
                assertEquals("2", completedSource.generation)
                assertEquals(LibraryScanState.IDLE, completedSource.scanState)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun legacySyntheticVolumeMigrationPreservesStableIdAndMergesInterruptedDuplicateState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-legacy-volume-${System.nanoTime()}.db"
        try {
            LibraryCatalogStore(context, databaseName).use { store ->
                val source = store.ensureDeviceSource()
                val legacySession = store.beginSourceScan(source.id)
                store.upsertTrack(
                    legacySession,
                    candidate(
                        remoteId = "7",
                        title = "Legacy card track",
                        contentUri = "content://media/external/audio/media/7",
                    ),
                )
                store.completeSourceScan(legacySession, generation = "legacy-generation")
                val legacy = store.readTracks(listOf(source.id)).single()
                store.setFavorite(legacy.id, true)
                store.recordPlayback(legacy.id, playedAtMs = 100)
                val playlistId = requireNotNull(store.createPlaylist("Legacy card"))
                store.addTrackToPlaylist(playlistId, legacy.id)

                val targetRemoteId = mediaStoreRemoteId("1234-5678", 7)
                val interruptedSession = store.beginSourceScan(source.id)
                store.upsertTrack(
                    interruptedSession,
                    candidate(
                        remoteId = targetRemoteId,
                        title = "Concrete duplicate",
                        contentUri = "content://media/1234-5678/audio/media/7",
                    ),
                )
                val duplicate = store.readTracks(listOf(source.id)).single {
                    it.title == "Concrete duplicate"
                }
                store.recordPlayback(duplicate.id, playedAtMs = 200)

                assertTrue(
                    store.migrateLegacyMediaStoreIdentities(
                        listOf(
                            MediaStoreLegacyIdentity(
                                legacyRemoteId = "7",
                                targetRemoteId = targetRemoteId,
                                targetContentUri = "content://media/1234-5678/audio/media/7",
                            ),
                        ),
                    ),
                )

                val migrated = store.readTracks(listOf(source.id)).single()
                assertEquals(legacy.id, migrated.id)
                assertEquals("content://media/1234-5678/audio/media/7", migrated.contentUri)
                assertTrue(migrated.isFavorite)
                assertEquals(2, migrated.playCount)
                assertEquals(200, migrated.lastPlayedAtMs)
                assertEquals(listOf(legacy.id), store.readPlaylists().single().trackIds)
                assertNull(store.readSources().single().generation)

                val reconciliation = store.beginSourceScan(source.id)
                store.upsertTrack(
                    reconciliation,
                    candidate(
                        remoteId = targetRemoteId,
                        title = "Current card track",
                        contentUri = "content://media/1234-5678/audio/media/7",
                    ),
                )
                store.completeMediaStoreScan(
                    reconciliation,
                    generation = "concrete-generation",
                    scannedVolumeNames = listOf("1234-5678"),
                )
                assertEquals(legacy.id, store.readTracks(listOf(source.id)).single().id)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun mediaStoreCompletionRetainsUnmountedVolumesAndPrunesDeletedTracksOnMountedVolumes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-volumes-${System.nanoTime()}.db"
        try {
            LibraryCatalogStore(context, databaseName).use { store ->
                val source = store.ensureDeviceSource()
                val primaryRemoteId = mediaStoreRemoteId("external_primary", 1)
                val firstSession = store.beginSourceScan(source.id)
                store.upsertTrack(
                    firstSession,
                    candidate(
                        remoteId = "1",
                        title = "Primary",
                        contentUri = "content://media/external/audio/media/1",
                    ),
                )
                store.upsertTrack(
                    firstSession,
                    candidate(
                        remoteId = mediaStoreRemoteId("external_primary", 3),
                        title = "Deleted primary",
                        contentUri = "content://media/external_primary/audio/media/3",
                    ),
                )
                store.upsertTrack(
                    firstSession,
                    candidate(
                        remoteId = mediaStoreRemoteId("1234-5678", 2),
                        title = "Card",
                        contentUri = "content://media/1234-5678/audio/media/2",
                    ),
                )
                store.completeMediaStoreScan(
                    firstSession,
                    generation = "both-mounted",
                    scannedVolumeNames = listOf("external_primary", "1234-5678"),
                )
                assertTrue(
                    store.migrateLegacyMediaStoreIdentities(
                        listOf(
                            MediaStoreLegacyIdentity(
                                legacyRemoteId = "1",
                                targetRemoteId = primaryRemoteId,
                                targetContentUri = "content://media/external_primary/audio/media/1",
                            ),
                        ),
                    ),
                )
                val primary = store.readTracks(listOf(source.id)).single { it.title == "Primary" }
                val card = store.readTracks(listOf(source.id)).single { it.title == "Card" }
                store.setFavorite(card.id, true)
                store.recordPlayback(card.id, playedAtMs = 123_456)
                val playlistId = requireNotNull(store.createPlaylist("Card tracks"))
                store.addTrackToPlaylist(playlistId, card.id)

                val primaryOnlySession = store.beginSourceScan(source.id)
                store.upsertTrack(
                    primaryOnlySession,
                    candidate(
                        remoteId = primaryRemoteId,
                        title = "Primary",
                        contentUri = "content://media/external_primary/audio/media/1",
                    ),
                )
                store.completeMediaStoreScan(
                    primaryOnlySession,
                    generation = "card-unmounted",
                    scannedVolumeNames = listOf("external_primary"),
                )

                val retainedCard = store.readTracks(listOf(source.id)).single { it.title == "Card" }
                val migratedPrimary = store.readTracks(listOf(source.id)).single { it.title == "Primary" }
                assertEquals(primary.id, migratedPrimary.id)
                assertEquals("content://media/external_primary/audio/media/1", migratedPrimary.contentUri)
                assertTrue(
                    store.readTracks(listOf(source.id)).none { it.title == "Deleted primary" },
                )
                assertEquals(card.id, retainedCard.id)
                assertTrue(retainedCard.isFavorite)
                assertEquals(1, retainedCard.playCount)
                assertEquals(listOf(card.id), store.readPlaylists().single().trackIds)

                val cardMountedAgainSession = store.beginSourceScan(source.id)
                store.upsertTrack(
                    cardMountedAgainSession,
                    candidate(
                        remoteId = primaryRemoteId,
                        title = "Primary",
                        contentUri = "content://media/external_primary/audio/media/1",
                    ),
                )
                store.completeMediaStoreScan(
                    cardMountedAgainSession,
                    generation = "card-mounted-again",
                    scannedVolumeNames = listOf("external_primary", "1234-5678"),
                )

                assertEquals(
                    listOf("Primary"),
                    store.readTracks(listOf(source.id)).map(AudioTrack::title),
                )
                assertTrue(store.readPlaylists().single().trackIds.isEmpty())
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun ambiguousLegacySyntheticIdentityIsRetainedWithoutStealingAConcreteTrack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-ambiguous-volume-${System.nanoTime()}.db"
        try {
            LibraryCatalogStore(context, databaseName).use { store ->
                val source = store.ensureDeviceSource()
                val legacySession = store.beginSourceScan(source.id)
                store.upsertTrack(
                    legacySession,
                    candidate(
                        remoteId = "9",
                        title = "Ambiguous legacy track",
                        contentUri = "content://media/external/audio/media/9",
                    ),
                )
                store.completeSourceScan(legacySession, generation = "legacy-generation")
                val legacyId = store.readTracks(listOf(source.id)).single().id
                store.setFavorite(legacyId, true)

                assertTrue(store.hasLegacyMediaStoreIdentities())
                assertTrue(store.migrateLegacyMediaStoreIdentities(emptyList()))
                assertFalse(store.hasLegacyMediaStoreIdentities())

                val concreteSession = store.beginSourceScan(source.id)
                store.upsertTrack(
                    concreteSession,
                    candidate(
                        remoteId = mediaStoreRemoteId("external_primary", 9),
                        title = "Concrete primary track",
                        contentUri = "content://media/external_primary/audio/media/9",
                    ),
                )
                store.completeMediaStoreScan(
                    concreteSession,
                    generation = "concrete-generation",
                    scannedVolumeNames = listOf("external_primary"),
                )

                val retained = store.readTracks(listOf(source.id))
                val legacy = retained.single { it.title == "Ambiguous legacy track" }
                val concrete = retained.single { it.title == "Concrete primary track" }
                assertEquals(legacyId, legacy.id)
                assertTrue(legacy.isFavorite)
                assertFalse(concrete.isFavorite)
                assertFalse(legacy.id == concrete.id)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun createLegacyFixture(context: Context, databaseName: String, version: Int) {
        require(version == 1 || version == 2)
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL(
                """
                CREATE TABLE library_sources (
                    source_id TEXT PRIMARY KEY NOT NULL,
                    source_kind TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    tree_uri TEXT,
                    scan_state TEXT NOT NULL,
                    generation TEXT,
                    scan_epoch INTEGER NOT NULL DEFAULT 0,
                    last_scanned_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE library_tracks (
                    track_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_id TEXT NOT NULL,
                    remote_id TEXT NOT NULL,
                    content_uri TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    album_id INTEGER,
                    album_artwork_uri TEXT,
                    date_modified_seconds INTEGER NOT NULL DEFAULT 0,
                    size_bytes INTEGER NOT NULL DEFAULT 0,
                    mime_type TEXT NOT NULL DEFAULT '',
                    fingerprint TEXT NOT NULL,
                    artwork_revision INTEGER NOT NULL DEFAULT 0,
                    seen_epoch INTEGER NOT NULL DEFAULT 0,
                    UNIQUE (source_id, remote_id),
                    FOREIGN KEY (source_id) REFERENCES library_sources(source_id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX index_library_tracks_source_seen ON library_tracks(source_id, seen_epoch)")
            database.execSQL("CREATE INDEX index_library_tracks_title ON library_tracks(title)")
            database.execSQL(
                "INSERT INTO library_sources VALUES ('device', 'DEVICE', 'Device music', NULL, 'IDLE', '77', 1, 123)",
            )
            database.execSQL(
                """
                INSERT INTO library_tracks (
                    source_id, remote_id, content_uri, title, artist, album, duration_ms,
                    date_modified_seconds, size_bytes, mime_type, fingerprint, artwork_revision, seen_epoch
                ) VALUES ('device', 'legacy', 'content://legacy/1', 'Legacy', 'Artist', 'Album', 1000,
                    12, 34, 'audio/flac', 'old-fingerprint', 1, 1)
                """.trimIndent(),
            )
            if (version == 2) migrateFixtureToVersion2(database)
            database.version = version
        }
    }

    private fun migrateFixtureToVersion2(database: SQLiteDatabase) {
        listOf(
            "album_artist TEXT NOT NULL DEFAULT ''",
            "track_number INTEGER",
            "disc_number INTEGER",
            "year INTEGER",
            "genre TEXT NOT NULL DEFAULT ''",
            "file_name TEXT NOT NULL DEFAULT ''",
            "folder_name TEXT NOT NULL DEFAULT ''",
            "codec TEXT NOT NULL DEFAULT ''",
            "channel_count INTEGER",
            "bit_depth INTEGER",
            "sample_rate_hz INTEGER",
            "bitrate INTEGER",
            "is_favorite INTEGER NOT NULL DEFAULT 0",
            "last_played_at_ms INTEGER NOT NULL DEFAULT 0",
            "play_count INTEGER NOT NULL DEFAULT 0",
        ).forEach { definition -> database.execSQL("ALTER TABLE library_tracks ADD COLUMN $definition") }
        database.execSQL("UPDATE library_tracks SET is_favorite = 1, last_played_at_ms = 456000, play_count = 4")
        database.execSQL("CREATE INDEX index_library_tracks_album ON library_tracks(album)")
        database.execSQL("CREATE INDEX index_library_tracks_artist ON library_tracks(artist)")
        database.execSQL("CREATE INDEX index_library_tracks_folder ON library_tracks(folder_name)")
        database.execSQL("CREATE INDEX index_library_tracks_recent ON library_tracks(last_played_at_ms)")
        database.execSQL(
            """
            CREATE TABLE library_playlists (
                playlist_id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE library_playlist_items (
                playlist_id INTEGER NOT NULL,
                track_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, track_id),
                FOREIGN KEY (playlist_id) REFERENCES library_playlists(playlist_id) ON DELETE CASCADE,
                FOREIGN KEY (track_id) REFERENCES library_tracks(track_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX index_playlist_items_order ON library_playlist_items(playlist_id, position)")
        database.execSQL("INSERT INTO library_playlists VALUES (7, 'Legacy mix', 1, 2)")
        database.execSQL("INSERT INTO library_playlist_items VALUES (7, 1, 0, 3)")
    }

    private fun readTrackFingerprint(context: Context, databaseName: String): String =
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.rawQuery("SELECT fingerprint FROM library_tracks", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
        }

    @Test
    fun favoriteOrderSurvivesReopenAndNewFavoritesAppend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-order-${System.nanoTime()}.db"
        try {
            val ids = LibraryCatalogStore(context, databaseName).use { store ->
                val source = store.ensureDeviceSource()
                val scan = store.beginSourceScan(source.id)
                for (title in listOf("A", "B", "C")) store.upsertTrack(scan, candidate(title, title))
                store.completeSourceScan(scan, "1")
                val tracks = store.readTracks(listOf(source.id))
                tracks.forEach { store.setFavorite(it.id, true) }
                val order = tracks.map(AudioTrack::id).reversed()
                store.saveTrackOrder(null, order)
                order
            }
            LibraryCatalogStore(context, databaseName).use { store ->
                fun order() = store.readTracks(listOf(store.ensureDeviceSource().id))
                    .filter(AudioTrack::isFavorite).sortedBy(AudioTrack::favoritePosition).map(AudioTrack::id)
                assertEquals(ids, order())
                store.setFavorite(ids.first(), true)
                assertEquals(ids, order())
                store.setFavorite(ids.first(), false)
                store.setFavorite(ids.first(), true)
                assertEquals(ids.drop(1) + ids.first(), order())
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun titleIndexUsesPinyinAndKeepsUnclassifiedTitlesLast() {
        fun track(id: Long, title: String) = AudioTrack(id, "content://fixture/$id", title, "", "", 1000)
        val index = LibraryTitleIndex.build(listOf(track(1, "# Intro"), track(2, "中文"), track(3, "apple"), track(4, "北京")))
        assertEquals(listOf("apple", "北京", "中文", "# Intro"), index.tracks.map(AudioTrack::title))
        assertEquals(mapOf("A" to 0, "B" to 1, "Z" to 2, "#" to 3), index.sections)
    }

    private fun candidate(
        remoteId: String,
        title: String,
        contentUri: String = "content://fixture/$remoteId",
    ) = LibraryTrackCandidate(
        remoteId = remoteId,
        contentUri = contentUri,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = 1_000,
        fingerprint = remoteId,
    )
}
