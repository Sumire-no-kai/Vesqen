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
                // #151: the support question counts plays across the library.
                assertEquals(2L, store.totalPlayCount())
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

                // The card's file is gone, but its favorite, play and playlist place are kept on a
                // hidden row that the file reattaches if it comes back (#143 Q2).
                assertEquals(setOf(card.id), store.readDisplacedTrackIds())
                assertEquals(
                    listOf("Primary"),
                    store.readTracks(listOf(source.id))
                        .filterNot { it.id in store.readDisplacedTrackIds() }
                        .map(AudioTrack::title),
                )
                assertEquals(listOf(card.id), store.readPlaylists().single().trackIds)
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

    @Test
    fun mediaStoreUnknownPlaceholderReadsAsMissingTag() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-unknown-tag-${System.nanoTime()}.db"
        try {
            LibraryCatalogStore(context, databaseName).use { store ->
                val source = store.ensureDeviceSource()
                val session = store.beginSourceScan(source.id)
                // Rows written before the fix still hold the placeholder, so it must be handled on read.
                store.upsertTrack(
                    session,
                    candidate("fixture-unknown", "Untitled").copy(
                        artist = "<unknown>",
                        album = "<unknown>",
                        albumArtist = "<unknown>",
                        genre = "<unknown>",
                    ),
                )
                store.finishSourceScan(source.id)

                val track = store.readTracks(listOf(source.id)).single()
                assertEquals("", track.artist)
                assertEquals("", track.album)
                assertEquals("", track.albumArtist)
                assertEquals("", track.genre)
                assertEquals("", track.displaySubtitle())
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun mediaStoreRebuildThatSwapsIdsKeepsUserStateOnEachFile() = withStore("library-rebuild-swap") { store ->
        store.scanMediaStore(songA.at(1), songB.at(2))
        val before = store.tracksByTitle()
        val a = before.getValue("A")
        val b = before.getValue("B")
        store.setFavorite(a.id, true)
        store.recordPlayback(b.id, playedAtMs = 100)
        store.recordPlayback(b.id, playedAtMs = 200)
        val playlistId = requireNotNull(store.createPlaylist("Mix"))
        store.addTrackToPlaylist(playlistId, b.id)
        store.addTrackToPlaylist(playlistId, a.id)

        store.scanMediaStore(songB.at(1), songA.at(2))

        val after = store.tracksByTitle()
        assertEquals(2, after.size)
        assertEquals(a.id, after.getValue("A").id)
        assertEquals(songA.at(2).contentUri, after.getValue("A").contentUri)
        assertTrue(after.getValue("A").isFavorite)
        assertEquals(b.id, after.getValue("B").id)
        assertEquals(songB.at(1).contentUri, after.getValue("B").contentUri)
        assertEquals(2, after.getValue("B").playCount)
        assertEquals(listOf(b.id, a.id), store.readPlaylists().single().trackIds)
        assertTrue(store.readDisplacedTrackIds().isEmpty())
    }

    @Test
    fun anotherSongOnAReusedIdGetsANewRowWhileTheOldRowFollowsItsFile() = withStore("library-rebuild-reuse") { store ->
        store.scanMediaStore(songA.at(1))
        val a = store.tracksByTitle().getValue("A")
        store.setFavorite(a.id, true)
        val playlistId = requireNotNull(store.createPlaylist("Mix"))
        store.addTrackToPlaylist(playlistId, a.id)

        store.scanMediaStore(songC.at(1), songA.at(5))

        val after = store.tracksByTitle()
        assertEquals(a.id, after.getValue("A").id)
        assertEquals(songA.at(5).contentUri, after.getValue("A").contentUri)
        assertTrue(after.getValue("A").isFavorite)
        assertFalse(after.getValue("C").id == a.id)
        assertFalse(after.getValue("C").isFavorite)
        assertEquals(listOf(a.id), store.readPlaylists().single().trackIds)
    }

    @Test
    fun aDisplacedRowWithUserDataStaysHiddenUntilItsFileReturns() = withStore("library-rebuild-hidden") { store ->
        // A completed scan once pruned it with its favorite and playlist place (#143 Q2).
        store.scanMediaStore(songA.at(1), songB.at(2))
        val a = store.tracksByTitle().getValue("A")
        store.setFavorite(a.id, true)
        val playlistId = requireNotNull(store.createPlaylist("Mix"))
        store.addTrackToPlaylist(playlistId, a.id)

        store.scanMediaStore(songC.at(1))

        assertEquals(setOf(a.id), store.readDisplacedTrackIds())
        // B had no user data, so its row is pruned as before.
        assertEquals(setOf("A", "C"), store.readTracks(listOf(LibrarySourceId.DEVICE)).map(AudioTrack::title).toSet())
        assertFalse(store.tracksByTitle().getValue("C").isFavorite)
        assertEquals(listOf(a.id), store.readPlaylists().single().trackIds)

        store.scanMediaStore(songC.at(1), songA.at(9))

        val back = store.tracksByTitle().getValue("A")
        assertEquals(a.id, back.id)
        assertEquals(songA.at(9).contentUri, back.contentUri)
        assertTrue(back.isFavorite)
        assertTrue(store.readDisplacedTrackIds().isEmpty())
        assertEquals(listOf(a.id), store.readPlaylists().single().trackIds)
    }

    @Test
    fun aScanOfAClearedMediaStoreKeepsUserDataUntilTheFilesAreListedAgain() = withStore("library-cleared") { store ->
        // Media Storage cleared and Vesqen opened before MediaStore lists the files again (#143 Q2).
        store.scanMediaStore(songA.at(1), songB.at(2), songC.at(3))
        val before = store.tracksByTitle()
        store.setFavorite(before.getValue("A").id, true)
        store.recordPlayback(before.getValue("B").id, playedAtMs = 100)

        store.scanMediaStore()

        assertEquals(setOf(before.getValue("A").id, before.getValue("B").id), store.readDisplacedTrackIds())
        assertFalse(store.readTracks(listOf(LibrarySourceId.DEVICE)).any { it.title == "C" })

        store.scanMediaStore(songA.at(4), songB.at(5), songC.at(6))

        val after = store.tracksByTitle()
        assertEquals(before.getValue("A").id, after.getValue("A").id)
        assertTrue(after.getValue("A").isFavorite)
        assertEquals(before.getValue("B").id, after.getValue("B").id)
        assertEquals(1, after.getValue("B").playCount)
        assertFalse(after.getValue("C").id == before.getValue("C").id)
        assertTrue(store.readDisplacedTrackIds().isEmpty())
    }

    @Test
    fun aRenameKeepsTheRow() = assertEditKeepsRow { it.copy(fileName = "renamed.flac") }

    @Test
    fun aTagEditKeepsTheRow() = assertEditKeepsRow { it.copy(title = "A (remaster)", sizeBytes = 12_345) }

    @Test
    fun aRenameTogetherWithATagEditKeepsTheRow() = assertEditKeepsRow {
        it.copy(title = "A (remaster)", fileName = "renamed.flac", sizeBytes = 12_345, durationMs = 200_400)
    }

    @Test
    fun aSameNamedSongInAnotherFolderDoesNotTakeOverTheRow() = withStore("library-rebuild-same-name") { store ->
        val firstTrackOfA = songA.copy(fileName = "01.flac", folderName = "Music/Album A")
        val firstTrackOfB = songB.copy(fileName = "01.flac", folderName = "Music/Album B")
        store.scanMediaStore(firstTrackOfA.at(1), firstTrackOfB.at(2))
        val a = store.tracksByTitle().getValue("A")
        val b = store.tracksByTitle().getValue("B")
        store.setFavorite(a.id, true)

        store.scanMediaStore(firstTrackOfB.at(1), firstTrackOfA.at(2))

        val after = store.tracksByTitle()
        assertEquals(a.id, after.getValue("A").id)
        assertEquals(firstTrackOfA.at(2).contentUri, after.getValue("A").contentUri)
        assertTrue(after.getValue("A").isFavorite)
        assertEquals(b.id, after.getValue("B").id)
        assertFalse(after.getValue("B").isFavorite)
    }

    @Test
    fun aFileDeletedAndCopiedBackKeepsItsRow() = withStore("library-copy-back") { store ->
        store.scanMediaStore(songA.at(1), songB.at(2))
        val b = store.tracksByTitle().getValue("B")
        store.setFavorite(b.id, true)

        store.scanMediaStore(songA.at(1), songB.at(9))

        val after = store.tracksByTitle()
        assertEquals(2, after.size)
        assertEquals(b.id, after.getValue("B").id)
        assertEquals(songB.at(9).contentUri, after.getValue("B").contentUri)
        assertTrue(after.getValue("B").isFavorite)
    }

    @Test
    fun anAmbiguousMatchBecomesANewRow() = withStore("library-ambiguous-file") { store ->
        // Before Android 10 a row records only its parent directory's name.
        val first = songA.copy(folderName = "Album").at(1, volumeName = LEGACY_VOLUME)
        val second = songA.copy(title = "A copy", folderName = "Album").at(2, volumeName = LEGACY_VOLUME)
        store.scanMediaStore(first, second, volumeName = LEGACY_VOLUME)
        store.readTracks(listOf(LibrarySourceId.DEVICE)).forEach { store.setFavorite(it.id, true) }
        val previousIds = store.readTracks(listOf(LibrarySourceId.DEVICE)).map(AudioTrack::id).toSet()

        store.scanMediaStore(
            songA.copy(folderName = "Album").at(3, volumeName = LEGACY_VOLUME),
            volumeName = LEGACY_VOLUME,
        )

        val hidden = store.readDisplacedTrackIds()
        val track = store.readTracks(listOf(LibrarySourceId.DEVICE)).single { it.id !in hidden }
        assertFalse(track.id in previousIds)
        assertFalse(track.isFavorite)
        // Neither old row is attached to the new file; their favorites wait on hidden rows (#143 Q2).
        assertEquals(previousIds, hidden)
    }

    @Test
    fun anInterruptedRebuildScanKeepsTheDisplacedRowUntilItsFileReturns() = withStore("library-rebuild-interrupted") { store ->
        store.scanMediaStore(songA.at(1), songB.at(2))
        val a = store.tracksByTitle().getValue("A")
        val b = store.tracksByTitle().getValue("B")
        store.setFavorite(a.id, true)

        store.scanMediaStore(songB.at(1), complete = false)
        store.markSourceInterrupted(LibrarySourceId.DEVICE)

        val interrupted = store.readTracks(listOf(LibrarySourceId.DEVICE)).associateBy(AudioTrack::id)
        assertEquals(setOf(a.id), store.readDisplacedTrackIds())
        assertTrue(interrupted.getValue(a.id).isFavorite)
        assertEquals(songB.at(1).contentUri, interrupted.getValue(b.id).contentUri)

        store.scanMediaStore(songB.at(1), songA.at(2))

        val after = store.tracksByTitle()
        assertEquals(a.id, after.getValue("A").id)
        assertEquals(songA.at(2).contentUri, after.getValue("A").contentUri)
        assertTrue(after.getValue("A").isFavorite)
        assertEquals(b.id, after.getValue("B").id)
        assertTrue(store.readDisplacedTrackIds().isEmpty())
    }

    @Test
    fun aScanKeepsARowItsChangingCursorSkippedAndTakesARepeatedRowOnce() = withStore("library-shifting-cursor") { store ->
        // MediaStore refills a long cursor by querying again. A file removed earlier in the order
        // during the scan made it skip B (#136 R14); one added made it offer D twice.
        store.scanMediaStore(songA.at(1), songB.at(2), songC.at(3))
        val before = store.tracksByTitle()
        val songD = MediaStoreFile(title = "D", fileName = "d.flac", sizeBytes = 40_000, durationMs = 500_000)
        val scan = store.beginMediaStoreScan()
        listOf(songA.at(1), songD.at(4), songD.at(4)).forEach { candidate ->
            if (scan.firstOffer(candidate) && !store.markSeenIfUnchanged(scan, candidate)) {
                store.upsertMediaStoreTrack(scan, candidate)
            }
        }
        // MediaStore still lists B; C was deleted.
        store.keepTracksStillListed(scan, listOf(songA.at(1), songB.at(2), songD.at(4)).map { it.listed() })
        store.completeMediaStoreScan(scan.session, generation = null, scannedVolumeNames = listOf(PRIMARY_VOLUME))

        val after = store.tracksByTitle()
        assertEquals(setOf("A", "B", "D"), after.keys)
        assertEquals(before.getValue("A").id, after.getValue("A").id)
        assertEquals(before.getValue("B").id, after.getValue("B").id)
        assertEquals(1, store.readTracks(listOf(LibrarySourceId.DEVICE)).count { it.title == "D" })
    }

    @Test
    fun aNewSongOnAReusedIdDoesNotTakeARowWhoseFileIsNotListedYet() = withStore("library-rebuild-partial") { store ->
        // MediaStore was rebuilt and lists only part of the files so far. A new song got A's old _ID,
        // and its duration is within a second of A's (#159).
        store.scanMediaStore(songA.at(1))
        val a = store.tracksByTitle().getValue("A")
        store.setFavorite(a.id, true)
        val playlistId = requireNotNull(store.createPlaylist("Mix"))
        store.addTrackToPlaylist(playlistId, a.id)
        val newSong = MediaStoreFile(
            title = "N",
            fileName = "n.flac",
            sizeBytes = 55_555,
            durationMs = songA.durationMs + 400,
            folderName = "Music/New",
        )

        store.scanMediaStore(newSong.at(1))

        assertEquals(setOf(a.id), store.readDisplacedTrackIds())
        val n = store.tracksByTitle().getValue("N")
        assertFalse(n.id == a.id)
        assertFalse(n.isFavorite)
        assertEquals(listOf(a.id), store.readPlaylists().single().trackIds)

        store.scanMediaStore(newSong.at(1), songA.at(2))

        val back = store.tracksByTitle().getValue("A")
        assertEquals(a.id, back.id)
        assertEquals(songA.at(2).contentUri, back.contentUri)
        assertTrue(back.isFavorite)
        assertTrue(store.readDisplacedTrackIds().isEmpty())
    }

    @Test
    fun aRebuildScanKeepsNoRowByAnIdNowListedForAnotherFile() = withStore("library-rebuild-listed") { store ->
        // MediaStore lists files while it rebuilds. C took A's old _ID; B's old _ID went to N after the
        // scan's cursor had passed, so only the listing at the end shows it (#159).
        store.scanMediaStore(songA.at(1), songB.at(2))
        val b = store.tracksByTitle().getValue("B")
        store.setFavorite(b.id, true)
        val newSong = MediaStoreFile(title = "N", fileName = "n.flac", sizeBytes = 55_555, durationMs = 250_000)
        val scan = store.beginMediaStoreScan(listedFiles = listOf(songC.at(1).listed()))
        if (scan.firstOffer(songC.at(1)) && !store.markSeenIfUnchanged(scan, songC.at(1))) {
            store.upsertMediaStoreTrack(scan, songC.at(1))
        }
        store.keepTracksStillListed(scan, listOf(songC.at(1), newSong.at(2)).map { it.listed() })
        store.completeMediaStoreScan(scan.session, generation = null, scannedVolumeNames = listOf(PRIMARY_VOLUME))

        // B waits hidden with its favorite; A had no user data and is pruned.
        assertEquals(setOf(b.id), store.readDisplacedTrackIds())
        assertEquals(setOf("B", "C"), store.tracksByTitle().keys)

        store.scanMediaStore(songC.at(1), newSong.at(2), songB.at(3))

        val after = store.tracksByTitle()
        assertEquals(b.id, after.getValue("B").id)
        assertTrue(after.getValue("B").isFavorite)
        assertFalse(after.getValue("N").isFavorite)
        assertTrue(store.readDisplacedTrackIds().isEmpty())
    }

    private fun assertEditKeepsRow(edit: (MediaStoreFile) -> MediaStoreFile) = withStore("library-edit") { store ->
        store.scanMediaStore(songA.at(1))
        val a = store.readTracks(listOf(LibrarySourceId.DEVICE)).single()
        store.setFavorite(a.id, true)
        val edited = edit(songA).at(1)

        store.scanMediaStore(edited, volumesWithStableIds = setOf(PRIMARY_VOLUME))

        val track = store.readTracks(listOf(LibrarySourceId.DEVICE)).single()
        assertEquals(a.id, track.id)
        assertEquals(edited.title, track.title)
        assertEquals(edited.fileName, track.fileName)
        assertTrue(track.isFavorite)
    }

    @Test
    fun aFileRenamedOrMovedInAnAddedFolderKeepsItsUserData() = withStore("library-folder-rename") { store ->
        // A rename or move inside an added folder gives the file a new document ID (#164).
        val source = store.addTestFolder()
        store.scanFolder(source, folderFile("doc:1", "Music/A", "Aira.flac", 1_000, 60_000), folderFile("doc:2", "Music/A", "Tide.flac", 2_000, 90_000))
        val before = store.readTracks(listOf(source)).associateBy(AudioTrack::fileName)
        val aira = before.getValue("Aira.flac")
        val tide = before.getValue("Tide.flac")
        store.setFavorite(aira.id, true)
        store.recordPlayback(tide.id, playedAtMs = 100)
        val playlistId = requireNotNull(store.createPlaylist("Mix"))
        store.addTrackToPlaylist(playlistId, aira.id)

        // Aira moves to a subfolder; Tide is renamed.
        store.scanFolder(source, folderFile("doc:3", "Music/A/Live", "Aira.flac", 1_000, 60_000), folderFile("doc:4", "Music/A", "02 Tide.flac", 2_000, 90_000))

        val after = store.readTracks(listOf(source)).associateBy(AudioTrack::id)
        assertEquals(setOf(aira.id, tide.id), after.keys)
        assertEquals("Music/A/Live", after.getValue(aira.id).folderName)
        assertEquals("content://tree/doc:3", after.getValue(aira.id).contentUri)
        assertTrue(after.getValue(aira.id).isFavorite)
        assertEquals("02 Tide.flac", after.getValue(tide.id).fileName)
        assertEquals(1, after.getValue(tide.id).playCount)
        assertEquals(listOf(aira.id), store.readPlaylists().single().trackIds)
        assertTrue(store.readHiddenFolderTrackIds().isEmpty())
        // The next scan sees the reattached rows as unchanged.
        store.scanFolder(source, folderFile("doc:3", "Music/A/Live", "Aira.flac", 1_000, 60_000), folderFile("doc:4", "Music/A", "02 Tide.flac", 2_000, 90_000))
        assertEquals(setOf(aira.id, tide.id), store.readTracks(listOf(source)).map(AudioTrack::id).toSet())
    }

    @Test
    fun aFolderFileThatLeavesStaysHiddenUntilItIsListedAgain() = withStore("library-folder-hidden") { store ->
        val source = store.addTestFolder()
        store.scanFolder(source, folderFile("doc:1", "Music", "Aira.flac", 1_000, 60_000), folderFile("doc:2", "Music", "Plain.flac", 3_000, 30_000))
        val aira = store.readTracks(listOf(source)).single { it.fileName == "Aira.flac" }
        store.setFavorite(aira.id, true)

        store.scanFolder(source)

        // Plain had no user data and is pruned as before; Aira is kept but hidden and not counted.
        assertEquals(setOf(aira.id), store.readHiddenFolderTrackIds())
        assertEquals(listOf(aira.id), store.readTracks(listOf(source)).map(AudioTrack::id))
        assertEquals(0, store.readSources().single { it.id == source }.trackCount)

        store.scanFolder(source, folderFile("doc:9", "Music/Back", "Aira.flac", 1_000, 60_000))

        val back = store.readTracks(listOf(source)).single()
        assertEquals(aira.id, back.id)
        assertEquals("content://tree/doc:9", back.contentUri)
        assertTrue(back.isFavorite)
        assertTrue(store.readHiddenFolderTrackIds().isEmpty())
        assertEquals(1, store.readSources().single { it.id == source }.trackCount)
    }

    @Test
    fun anAmbiguousFolderMatchKeepsTheDataHidden() = withStore("library-folder-ambiguous") { store ->
        val source = store.addTestFolder()
        store.scanFolder(source, folderFile("doc:1", "Music", "Aira.flac", 1_000, 60_000))
        val aira = store.readTracks(listOf(source)).single()
        store.setFavorite(aira.id, true)

        // Two renamed copies: neither may take the favorite.
        store.scanFolder(source, folderFile("doc:2", "Music", "x.flac", 1_000, 60_000), folderFile("doc:3", "Music", "y.flac", 1_000, 60_000))

        assertEquals(setOf(aira.id), store.readHiddenFolderTrackIds())
        assertEquals(2, store.readTracks(listOf(source)).count { !it.isFavorite })
    }

    @Test
    fun aDeletedFavoriteDoesNotMoveToAnotherFileOfTheSameSize() = withStore("library-folder-twin") { store ->
        // Uncompressed rips with the same sector count share size and duration, not their modified time.
        val source = store.addTestFolder()
        store.scanFolder(source, folderFile("doc:1", "Music/A", "07.wav", 30_000_044, 170_000, modifiedSeconds = 100), folderFile("doc:2", "Music/B", "05.wav", 30_000_044, 170_000, modifiedSeconds = 175))
        val seven = store.readTracks(listOf(source)).single { it.fileName == "07.wav" }
        val five = store.readTracks(listOf(source)).single { it.fileName == "05.wav" }
        store.setFavorite(seven.id, true)

        store.scanFolder(source, folderFile("doc:2", "Music/B", "05.wav", 30_000_044, 170_000, modifiedSeconds = 175))

        assertEquals(setOf(seven.id), store.readHiddenFolderTrackIds())
        val row = store.readTracks(listOf(source)).single { it.id == five.id }
        assertFalse(row.isFavorite)
        assertEquals("content://tree/doc:2", row.contentUri)
    }

    @Test
    fun anIdenticalCopyThatWasThereAllAlongKeepsItsRow() = withStore("library-folder-copy") { store ->
        val source = store.addTestFolder()
        store.scanFolder(source, folderFile("doc:1", "Music/Album", "Aira.flac", 1_000, 60_000), folderFile("doc:2", "Music/Backup", "Aira.flac", 1_000, 60_000))
        val rows = store.readTracks(listOf(source)).associateBy(AudioTrack::contentUri)
        val album = rows.getValue("content://tree/doc:1")
        val backup = rows.getValue("content://tree/doc:2")
        store.setFavorite(album.id, true)

        // The album copy is deleted; the backup copy was listed when the album copy was last seen.
        store.scanFolder(source, folderFile("doc:2", "Music/Backup", "Aira.flac", 1_000, 60_000))

        assertEquals(setOf(album.id), store.readHiddenFolderTrackIds())
        assertTrue(store.readTracks(listOf(source)).any { it.id == backup.id && !it.isFavorite })
    }

    @Test
    fun aCopyRewrittenByTheScanDoesNotTakeOverADeletedFile() = withStore("library-folder-rewrite") { store ->
        val source = store.addTestFolder()
        store.scanFolder(source, folderFile("doc:1", "Music/Album", "Aira.flac", 1_000, 60_000), folderFile("doc:2", "Music/Backup", "Aira.flac", 1_000, 60_000))
        val album = store.readTracks(listOf(source)).single { it.contentUri == "content://tree/doc:1" }
        store.setFavorite(album.id, true)

        // The album copy is deleted while the backup copy's fingerprint changes, as after a tag edit
        // that kept the time or a new metadata revision: the scan rewrites the backup's row.
        store.scanFolder(source, folderFile("doc:2", "Music/Backup", "Aira.flac", 1_000, 60_000, revision = 2))

        assertEquals(setOf(album.id), store.readHiddenFolderTrackIds())
        assertTrue(store.readTracks(listOf(source)).single { it.contentUri == "content://tree/doc:2" }.let { !it.isFavorite })
    }

    @Test
    fun aMovedFileThatPlayedDuringAPausedScanMergesIntoItsOldRow() = withStore("library-folder-paused-play") { store ->
        val source = store.addTestFolder()
        store.scanFolder(source, folderFile("doc:1", "Music", "Aira.flac", 1_000, 60_000))
        val aira = store.readTracks(listOf(source)).single()
        store.setFavorite(aira.id, true)
        store.recordPlayback(aira.id, playedAtMs = 100)
        val playlistId = requireNotNull(store.createPlaylist("Mix"))

        // A paused scan lists the moved file as a new row, which plays and joins a playlist.
        store.scanFolder(source, folderFile("doc:2", "Music/Live", "Aira.flac", 1_000, 60_000), complete = false)
        val moved = store.readTracks(listOf(source)).single { it.contentUri == "content://tree/doc:2" }
        store.recordPlayback(moved.id, playedAtMs = 200)
        store.addTrackToPlaylist(playlistId, moved.id)

        store.scanFolder(source, folderFile("doc:2", "Music/Live", "Aira.flac", 1_000, 60_000))

        val row = store.readTracks(listOf(source)).single()
        assertEquals(aira.id, row.id)
        assertEquals("content://tree/doc:2", row.contentUri)
        assertTrue(row.isFavorite)
        assertEquals(2, row.playCount)
        assertEquals(listOf(aira.id), store.readPlaylists().single().trackIds)
        assertTrue(store.readHiddenFolderTrackIds().isEmpty())
    }

    @Test
    fun aPausedFolderScanHidesAndMovesNothing() = withStore("library-folder-paused") { store ->
        val source = store.addTestFolder()
        store.scanFolder(source, folderFile("doc:1", "Music", "Aira.flac", 1_000, 60_000))
        val aira = store.readTracks(listOf(source)).single()
        store.setFavorite(aira.id, true)

        store.scanFolder(source, folderFile("doc:2", "Music/Live", "Aira.flac", 1_000, 60_000), complete = false)

        val rows = store.readTracks(listOf(source)).associateBy(AudioTrack::id)
        assertEquals("content://tree/doc:1", rows.getValue(aira.id).contentUri)
        assertTrue(rows.getValue(aira.id).isFavorite)
        assertTrue(store.readHiddenFolderTrackIds().isEmpty())
    }

    private fun LibraryCatalogStore.addTestFolder(): String {
        val treeUri = "content://tree/test"
        upsertFolderSource(treeUri, "Test music")
        return LibrarySourceId.forTree(treeUri)
    }

    /** Runs a folder scan the way `AndroidLibraryCatalog.scanTreeSource` does, without the tree walk. */
    private fun LibraryCatalogStore.scanFolder(sourceId: String, vararg files: LibraryTrackCandidate, complete: Boolean = true) {
        val session = beginSourceScan(sourceId)
        files.forEach { file ->
            if (!markSeenIfFingerprintMatches(session, file.remoteId, file.fingerprint)) upsertTrack(session, file)
        }
        if (complete) completeSourceScan(session) else markSourcePaused(sourceId)
    }

    /** A rename or a move keeps the file's size and modified time ([modifiedSeconds] defaults by size). */
    private fun folderFile(
        documentId: String,
        folder: String,
        fileName: String,
        sizeBytes: Long,
        durationMs: Long,
        modifiedSeconds: Long = 1_700_000_000 + sizeBytes,
        revision: Int = 1,
    ) = LibraryTrackCandidate(
        remoteId = documentId,
        contentUri = "content://tree/$documentId",
        title = fileName.substringBeforeLast('.'),
        artist = "Artist",
        album = "Album",
        durationMs = durationMs,
        dateModifiedSeconds = modifiedSeconds,
        sizeBytes = sizeBytes,
        fileName = fileName,
        folderName = folder,
        fingerprint = "$documentId|$sizeBytes|$modifiedSeconds|$revision",
    )

    private fun withStore(name: String, block: (LibraryCatalogStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "$name-${System.nanoTime()}.db"
        try {
            LibraryCatalogStore(context, databaseName).use { store ->
                store.ensureDeviceSource()
                block(store)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /** Runs the device scan the way `AndroidLibraryCatalog` does, without MediaStore or enrichment. */
    private fun LibraryCatalogStore.scanMediaStore(
        vararg candidates: LibraryTrackCandidate,
        volumeName: String = PRIMARY_VOLUME,
        complete: Boolean = true,
        volumesWithStableIds: Set<String> = emptySet(),
    ) {
        val scan = beginMediaStoreScan(volumesWithStableIds = volumesWithStableIds)
        candidates.forEach { candidate ->
            if (!markSeenIfUnchanged(scan, candidate)) upsertMediaStoreTrack(scan, candidate)
        }
        if (complete) {
            completeMediaStoreScan(scan.session, generation = null, scannedVolumeNames = listOf(volumeName))
        }
    }

    private fun LibraryTrackCandidate.listed() = ListedMediaStoreFile(
        remoteId = remoteId,
        contentUri = contentUri,
        folderName = folderName,
        fileName = fileName,
        sizeBytes = sizeBytes,
    )

    private fun LibraryCatalogStore.tracksByTitle(): Map<String, AudioTrack> =
        readTracks(listOf(LibrarySourceId.DEVICE)).associateBy(AudioTrack::title)

    private val songA = MediaStoreFile(title = "A", fileName = "a.flac", sizeBytes = 10_000, durationMs = 200_000)
    private val songB = MediaStoreFile(title = "B", fileName = "b.flac", sizeBytes = 20_000, durationMs = 300_000)
    private val songC = MediaStoreFile(title = "C", fileName = "c.flac", sizeBytes = 30_000, durationMs = 400_000)

    private data class MediaStoreFile(
        val title: String,
        val fileName: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val folderName: String = "Music/Album",
    ) {
        fun at(mediaId: Long, volumeName: String = PRIMARY_VOLUME): LibraryTrackCandidate {
            val contentUri = "content://media/$volumeName/audio/media/$mediaId"
            return LibraryTrackCandidate(
                remoteId = mediaStoreRemoteId(volumeName, mediaId),
                contentUri = contentUri,
                title = title,
                artist = "Artist",
                album = "Album",
                durationMs = durationMs,
                sizeBytes = sizeBytes,
                fileName = fileName,
                folderName = folderName,
                fingerprint = libraryFingerprint(contentUri, title, fileName, sizeBytes, durationMs, folderName),
            )
        }
    }

    private companion object {
        const val PRIMARY_VOLUME = "external_primary"
        const val LEGACY_VOLUME = "external"
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
