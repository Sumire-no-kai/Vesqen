package io.github.sumirenokai.vesqen.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.io.Closeable

/**
 * Private, small SQLite store for the discoverable catalog. It contains only metadata and opaque
 * content URIs; no audio, artwork, or filesystem paths are copied into app storage.
 */
internal class LibraryCatalogStore(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName,
    null,
    DATABASE_VERSION,
), Closeable {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $SOURCES_TABLE (
                $SOURCE_ID TEXT PRIMARY KEY NOT NULL,
                $SOURCE_KIND TEXT NOT NULL,
                $SOURCE_DISPLAY_NAME TEXT NOT NULL,
                $SOURCE_TREE_URI TEXT,
                $SOURCE_SCAN_STATE TEXT NOT NULL,
                $SOURCE_GENERATION TEXT,
                $SOURCE_SCAN_EPOCH INTEGER NOT NULL DEFAULT 0,
                $SOURCE_LAST_SCANNED_AT INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TRACKS_TABLE (
                $TRACK_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $TRACK_SOURCE_ID TEXT NOT NULL,
                $TRACK_REMOTE_ID TEXT NOT NULL,
                $TRACK_CONTENT_URI TEXT NOT NULL,
                $TRACK_TITLE TEXT NOT NULL,
                $TRACK_ARTIST TEXT NOT NULL,
                $TRACK_ALBUM TEXT NOT NULL,
                $TRACK_DURATION_MS INTEGER NOT NULL,
                $TRACK_ALBUM_ID INTEGER,
                $TRACK_ALBUM_ARTWORK_URI TEXT,
                $TRACK_DATE_MODIFIED_SECONDS INTEGER NOT NULL DEFAULT 0,
                $TRACK_SIZE_BYTES INTEGER NOT NULL DEFAULT 0,
                $TRACK_MIME_TYPE TEXT NOT NULL DEFAULT '',
                $TRACK_ALBUM_ARTIST TEXT NOT NULL DEFAULT '',
                $TRACK_NUMBER INTEGER,
                $TRACK_DISC_NUMBER INTEGER,
                $TRACK_YEAR INTEGER,
                $TRACK_GENRE TEXT NOT NULL DEFAULT '',
                $TRACK_FILE_NAME TEXT NOT NULL DEFAULT '',
                $TRACK_FOLDER_NAME TEXT NOT NULL DEFAULT '',
                $TRACK_CODEC TEXT NOT NULL DEFAULT '',
                $TRACK_CHANNEL_COUNT INTEGER,
                $TRACK_BIT_DEPTH INTEGER,
                $TRACK_SAMPLE_RATE_HZ INTEGER,
                $TRACK_BITRATE INTEGER,
                $TRACK_IS_FAVORITE INTEGER NOT NULL DEFAULT 0,
                $TRACK_FAVORITE_POSITION INTEGER,
                $TRACK_LAST_PLAYED_AT_MS INTEGER NOT NULL DEFAULT 0,
                $TRACK_PLAY_COUNT INTEGER NOT NULL DEFAULT 0,
                $TRACK_FINGERPRINT TEXT NOT NULL,
                $TRACK_ARTWORK_REVISION INTEGER NOT NULL DEFAULT 0,
                $TRACK_SEEN_EPOCH INTEGER NOT NULL DEFAULT 0,
                UNIQUE ($TRACK_SOURCE_ID, $TRACK_REMOTE_ID),
                FOREIGN KEY ($TRACK_SOURCE_ID) REFERENCES $SOURCES_TABLE($SOURCE_ID)
                    ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX index_library_tracks_source_seen ON $TRACKS_TABLE($TRACK_SOURCE_ID, $TRACK_SEEN_EPOCH)",
        )
        db.execSQL(
            "CREATE INDEX index_library_tracks_title ON $TRACKS_TABLE($TRACK_TITLE)",
        )
        db.execSQL("CREATE INDEX index_library_tracks_album ON $TRACKS_TABLE($TRACK_ALBUM)")
        db.execSQL("CREATE INDEX index_library_tracks_artist ON $TRACKS_TABLE($TRACK_ARTIST)")
        db.execSQL("CREATE INDEX index_library_tracks_folder ON $TRACKS_TABLE($TRACK_FOLDER_NAME)")
        db.execSQL("CREATE INDEX index_library_tracks_recent ON $TRACKS_TABLE($TRACK_LAST_PLAYED_AT_MS)")
        createPlaylistTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateToVersion2(db)
        if (oldVersion < 3) invalidateCatalogForMetadataReconciliation(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TRACKS_TABLE ADD COLUMN $TRACK_FAVORITE_POSITION INTEGER")
            val oldFavorites = db.query(TRACKS_TABLE, TRACK_COLUMNS, "$TRACK_IS_FAVORITE = 1", null, null, null, null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.toAudioTrack()) }
            }
            val favorites = LibraryTitleIndex.build(oldFavorites).tracks
            favorites.forEachIndexed { index, track ->
                db.execSQL("UPDATE $TRACKS_TABLE SET $TRACK_FAVORITE_POSITION = ? WHERE $TRACK_ID = ?", arrayOf<Any>(index, track.id))
            }
        }
    }

    fun ensureDeviceSource(): StoredLibrarySource {
        findSource(LibrarySourceId.DEVICE)?.let { return it }
        writableDatabase.insertOrThrow(
            SOURCES_TABLE,
            null,
            ContentValues().apply {
                put(SOURCE_ID, LibrarySourceId.DEVICE)
                put(SOURCE_KIND, LibrarySourceKind.DEVICE.name)
                put(SOURCE_DISPLAY_NAME, DEVICE_SOURCE_NAME)
                put(SOURCE_SCAN_STATE, LibraryScanState.IDLE.name)
            },
        )
        return requireNotNull(findSource(LibrarySourceId.DEVICE))
    }

    fun upsertFolderSource(treeUri: String, displayName: String) {
        val sourceId = LibrarySourceId.forTree(treeUri)
        val values = ContentValues().apply {
            put(SOURCE_DISPLAY_NAME, displayName)
            put(SOURCE_TREE_URI, treeUri)
            put(SOURCE_SCAN_STATE, LibraryScanState.IDLE.name)
        }
        if (findSource(sourceId) == null) {
            values.put(SOURCE_ID, sourceId)
            values.put(SOURCE_KIND, LibrarySourceKind.FOLDER.name)
            writableDatabase.insertOrThrow(SOURCES_TABLE, null, values)
        } else {
            writableDatabase.update(SOURCES_TABLE, values, "$SOURCE_ID = ?", arrayOf(sourceId))
        }
    }

    fun removeFolderSource(sourceId: String): String? {
        val source = findSource(sourceId) ?: return null
        if (source.kind != LibrarySourceKind.FOLDER) return null
        val database = writableDatabase
        database.beginTransaction()
        return try {
            database.delete(TRACKS_TABLE, "$TRACK_SOURCE_ID = ?", arrayOf(sourceId))
            database.delete(SOURCES_TABLE, "$SOURCE_ID = ?", arrayOf(sourceId))
            database.setTransactionSuccessful()
            source.treeUri
        } finally {
            database.endTransaction()
        }
    }

    fun readSources(): List<StoredLibrarySource> = readableDatabase.rawQuery(
        """
        SELECT s.$SOURCE_ID, s.$SOURCE_KIND, s.$SOURCE_DISPLAY_NAME, s.$SOURCE_TREE_URI,
               s.$SOURCE_SCAN_STATE, s.$SOURCE_GENERATION, s.$SOURCE_SCAN_EPOCH,
               COUNT(t.$TRACK_ID) AS track_count
          FROM $SOURCES_TABLE s
          LEFT JOIN $TRACKS_TABLE t ON t.$TRACK_SOURCE_ID = s.$SOURCE_ID
         GROUP BY s.$SOURCE_ID
         ORDER BY s.$SOURCE_KIND ASC, s.$SOURCE_DISPLAY_NAME COLLATE NOCASE ASC
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toStoredSource())
        }
    }

    fun readTracks(sourceIds: Collection<String>): List<AudioTrack> {
        if (sourceIds.isEmpty()) return emptyList()
        val placeholders = sourceIds.joinToString(separator = ",") { "?" }
        return readableDatabase.query(
            TRACKS_TABLE,
            TRACK_COLUMNS,
            "$TRACK_SOURCE_ID IN ($placeholders)",
            sourceIds.toTypedArray(),
            null,
            null,
            "$TRACK_TITLE COLLATE NOCASE ASC, $TRACK_ID ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toAudioTrack())
            }
        }
    }

    /** Lightweight source-owned locations used to build the currently playable projection. */
    fun readTrackContentUris(sourceId: String): Map<Long, String> = readableDatabase.query(
        TRACKS_TABLE,
        arrayOf(TRACK_ID, TRACK_CONTENT_URI),
        "$TRACK_SOURCE_ID = ?",
        arrayOf(sourceId),
        null,
        null,
        null,
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1))
        }
    }

    fun hasLegacyMediaStoreIdentities(): Boolean = readableDatabase.query(
        TRACKS_TABLE,
        arrayOf(TRACK_ID),
        "$TRACK_SOURCE_ID = ? AND $TRACK_CONTENT_URI GLOB ? AND $TRACK_REMOTE_ID NOT GLOB ?",
        arrayOf(
            LibrarySourceId.DEVICE,
            "content://media/external/*",
            "$UNRESOLVED_LEGACY_MEDIASTORE_PREFIX*",
        ),
        null,
        null,
        null,
        "1",
    ).use(Cursor::moveToFirst)

    /**
     * Re-keys rows written through MediaStore's synthetic `external` view without changing their
     * stable catalog ID. If an interrupted newer scan already inserted the concrete identity, its
     * user state and playlist memberships are folded back into the legacy row before deletion.
     * Rows that cannot be attributed to exactly one concrete volume are assigned an isolated
     * remote identity and retained. This prevents a later primary-volume scan from attaching their
     * favorites, history, or playlist membership to a different song with the same provider ID.
     */
    fun migrateLegacyMediaStoreIdentities(
        identities: Collection<MediaStoreLegacyIdentity>,
    ): Boolean {
        var changed = false
        writableDatabase.transaction {
            identities.forEach { identity ->
                val legacy = readTrackMigrationState(
                    database = this,
                    remoteId = identity.legacyRemoteId,
                    requireSyntheticExternalUri = true,
                ) ?: return@forEach
                val target = readTrackMigrationState(
                    database = this,
                    remoteId = identity.targetRemoteId,
                    requireSyntheticExternalUri = false,
                )
                if (target != null && target.trackId != legacy.trackId) {
                    val mergedFavorite = legacy.isFavorite || target.isFavorite
                    val mergedFavoritePosition = when {
                        !mergedFavorite -> null
                        legacy.isFavorite && target.isFavorite ->
                            listOfNotNull(legacy.favoritePosition, target.favoritePosition).minOrNull()
                        legacy.isFavorite -> legacy.favoritePosition
                        else -> target.favoritePosition
                    }
                    update(
                        TRACKS_TABLE,
                        ContentValues().apply {
                            put(TRACK_IS_FAVORITE, if (mergedFavorite) 1 else 0)
                            putNullableLong(TRACK_FAVORITE_POSITION, mergedFavoritePosition)
                            put(
                                TRACK_PLAY_COUNT,
                                (legacy.playCount.toLong() + target.playCount.toLong())
                                    .coerceAtMost(Int.MAX_VALUE.toLong()),
                            )
                            put(
                                TRACK_LAST_PLAYED_AT_MS,
                                maxOf(legacy.lastPlayedAtMs, target.lastPlayedAtMs),
                            )
                        },
                        "$TRACK_ID = ?",
                        arrayOf(legacy.trackId.toString()),
                    )
                    execSQL(
                        """
                        INSERT OR IGNORE INTO $PLAYLIST_ITEMS_TABLE (
                            $PLAYLIST_ITEM_PLAYLIST_ID,
                            $PLAYLIST_ITEM_TRACK_ID,
                            $PLAYLIST_ITEM_POSITION,
                            $PLAYLIST_ITEM_ADDED_AT_MS
                        )
                        SELECT $PLAYLIST_ITEM_PLAYLIST_ID, ?, $PLAYLIST_ITEM_POSITION,
                               $PLAYLIST_ITEM_ADDED_AT_MS
                          FROM $PLAYLIST_ITEMS_TABLE
                         WHERE $PLAYLIST_ITEM_TRACK_ID = ?
                        """.trimIndent(),
                        arrayOf(legacy.trackId, target.trackId),
                    )
                    delete(TRACKS_TABLE, "$TRACK_ID = ?", arrayOf(target.trackId.toString()))
                }
                val updated = update(
                    TRACKS_TABLE,
                    ContentValues().apply {
                        put(TRACK_REMOTE_ID, identity.targetRemoteId)
                        put(TRACK_CONTENT_URI, identity.targetContentUri)
                    },
                    "$TRACK_ID = ?",
                    arrayOf(legacy.trackId.toString()),
                )
                if (updated == 1) changed = true
            }
            val unresolved = query(
                TRACKS_TABLE,
                arrayOf(TRACK_ID, TRACK_REMOTE_ID),
                "$TRACK_SOURCE_ID = ? AND $TRACK_CONTENT_URI GLOB ? AND " +
                    "$TRACK_REMOTE_ID NOT GLOB ?",
                arrayOf(
                    LibrarySourceId.DEVICE,
                    "content://media/external/*",
                    "$UNRESOLVED_LEGACY_MEDIASTORE_PREFIX*",
                ),
                null,
                null,
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1))
                }
            }
            unresolved.forEach { (trackId, remoteId) ->
                val updated = update(
                    TRACKS_TABLE,
                    ContentValues().apply {
                        put(
                            TRACK_REMOTE_ID,
                            "$UNRESOLVED_LEGACY_MEDIASTORE_PREFIX$trackId:$remoteId",
                        )
                    },
                    "$TRACK_ID = ?",
                    arrayOf(trackId.toString()),
                )
                if (updated == 1) changed = true
            }
            if (changed) {
                update(
                    SOURCES_TABLE,
                    ContentValues().apply { putNull(SOURCE_GENERATION) },
                    "$SOURCE_ID = ?",
                    arrayOf(LibrarySourceId.DEVICE),
                )
            }
        }
        return changed
    }

    fun setFavorite(trackId: Long, favorite: Boolean) {
        val db = writableDatabase
        db.transaction {
            if (favorite) {
                db.execSQL(
                    "UPDATE $TRACKS_TABLE SET $TRACK_IS_FAVORITE = 1, $TRACK_FAVORITE_POSITION = " +
                        "(SELECT COALESCE(MAX($TRACK_FAVORITE_POSITION), -1) + 1 FROM $TRACKS_TABLE WHERE $TRACK_IS_FAVORITE = 1) " +
                        "WHERE $TRACK_ID = ? AND $TRACK_IS_FAVORITE = 0", arrayOf(trackId),
                )
            } else {
                db.execSQL("UPDATE $TRACKS_TABLE SET $TRACK_IS_FAVORITE = 0, $TRACK_FAVORITE_POSITION = NULL WHERE $TRACK_ID = ?", arrayOf(trackId))
            }
        }
    }

    fun saveTrackOrder(playlistId: Long?, requested: List<Long>) {
        val db = writableDatabase
        db.transaction {
            val current = if (playlistId != null) readPlaylistTrackIds(db, playlistId) else {
                db.rawQuery("SELECT $TRACK_ID FROM $TRACKS_TABLE WHERE $TRACK_IS_FAVORITE = 1 ORDER BY $TRACK_FAVORITE_POSITION, $TRACK_ID", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
                }
            }
            mergeLibraryOrder(current, requested).forEachIndexed { index, id ->
                if (playlistId == null) {
                    db.execSQL("UPDATE $TRACKS_TABLE SET $TRACK_FAVORITE_POSITION = ? WHERE $TRACK_ID = ?", arrayOf<Any>(index, id))
                } else {
                    db.execSQL("UPDATE $PLAYLIST_ITEMS_TABLE SET $PLAYLIST_ITEM_POSITION = ? WHERE $PLAYLIST_ITEM_PLAYLIST_ID = ? AND $PLAYLIST_ITEM_TRACK_ID = ?", arrayOf<Any>(index, playlistId, id))
                }
            }
            if (playlistId != null) touchPlaylist(db, playlistId)
        }
    }

    fun recordPlayback(trackId: Long, playedAtMs: Long): TrackPlaybackHistory? =
        writableDatabase.transaction {
            execSQL(
                """
                UPDATE $TRACKS_TABLE
                   SET $TRACK_PLAY_COUNT = $TRACK_PLAY_COUNT + 1,
                       $TRACK_LAST_PLAYED_AT_MS = ?
                 WHERE $TRACK_ID = ?
                """.trimIndent(),
                arrayOf(playedAtMs, trackId),
            )
            query(
                TRACKS_TABLE,
                arrayOf(TRACK_PLAY_COUNT, TRACK_LAST_PLAYED_AT_MS),
                "$TRACK_ID = ?",
                arrayOf(trackId.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    TrackPlaybackHistory(
                        playCount = cursor.getInt(0),
                        lastPlayedAtMs = cursor.getLong(1),
                    )
                }
            }
        }

    fun readPlaylists(): List<LibraryPlaylist> {
        val database = readableDatabase
        val playlists = database.query(
            PLAYLISTS_TABLE,
            arrayOf(PLAYLIST_ID, PLAYLIST_NAME, PLAYLIST_CREATED_AT_MS, PLAYLIST_UPDATED_AT_MS),
            null,
            null,
            null,
            null,
            "$PLAYLIST_NAME COLLATE NOCASE ASC, $PLAYLIST_ID ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LibraryPlaylist(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow(PLAYLIST_ID)),
                            name = cursor.getString(cursor.getColumnIndexOrThrow(PLAYLIST_NAME)),
                            trackIds = emptyList(),
                            createdAtMs = cursor.getLong(
                                cursor.getColumnIndexOrThrow(PLAYLIST_CREATED_AT_MS),
                            ),
                            updatedAtMs = cursor.getLong(
                                cursor.getColumnIndexOrThrow(PLAYLIST_UPDATED_AT_MS),
                            ),
                        ),
                    )
                }
            }
        }
        if (playlists.isEmpty()) return playlists

        val trackIdsByPlaylist = database.query(
            PLAYLIST_ITEMS_TABLE,
            arrayOf(PLAYLIST_ITEM_PLAYLIST_ID, PLAYLIST_ITEM_TRACK_ID),
            null,
            null,
            null,
            null,
            "$PLAYLIST_ITEM_PLAYLIST_ID ASC, $PLAYLIST_ITEM_POSITION ASC, " +
                "$PLAYLIST_ITEM_ADDED_AT_MS ASC, $PLAYLIST_ITEM_TRACK_ID ASC",
        ).use { cursor ->
            buildMap<Long, MutableList<Long>> {
                while (cursor.moveToNext()) {
                    getOrPut(cursor.getLong(0), ::mutableListOf).add(cursor.getLong(1))
                }
            }
        }
        return playlists.map { playlist ->
            playlist.copy(trackIds = trackIdsByPlaylist[playlist.id].orEmpty())
        }
    }

    fun createPlaylist(name: String): Long? {
        val normalized = name.trim().takeIf(String::isNotEmpty) ?: return null
        val now = System.currentTimeMillis()
        return writableDatabase.insertWithOnConflict(
            PLAYLISTS_TABLE,
            null,
            ContentValues().apply {
                put(PLAYLIST_NAME, normalized)
                put(PLAYLIST_CREATED_AT_MS, now)
                put(PLAYLIST_UPDATED_AT_MS, now)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        ).takeIf { it >= 0 }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        val normalized = name.trim().takeIf(String::isNotEmpty) ?: return
        writableDatabase.updateWithOnConflict(
            PLAYLISTS_TABLE,
            ContentValues().apply {
                put(PLAYLIST_NAME, normalized)
                put(PLAYLIST_UPDATED_AT_MS, System.currentTimeMillis())
            },
            "$PLAYLIST_ID = ?",
            arrayOf(playlistId.toString()),
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun deletePlaylist(playlistId: Long) {
        writableDatabase.delete(PLAYLISTS_TABLE, "$PLAYLIST_ID = ?", arrayOf(playlistId.toString()))
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        val database = writableDatabase
        database.transaction {
            val nextPosition = database.rawQuery(
                "SELECT COALESCE(MAX($PLAYLIST_ITEM_POSITION), -1) + 1 FROM $PLAYLIST_ITEMS_TABLE WHERE $PLAYLIST_ITEM_PLAYLIST_ID = ?",
                arrayOf(playlistId.toString()),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            val inserted = database.insertWithOnConflict(
                PLAYLIST_ITEMS_TABLE,
                null,
                ContentValues().apply {
                    put(PLAYLIST_ITEM_PLAYLIST_ID, playlistId)
                    put(PLAYLIST_ITEM_TRACK_ID, trackId)
                    put(PLAYLIST_ITEM_POSITION, nextPosition)
                    put(PLAYLIST_ITEM_ADDED_AT_MS, System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (inserted >= 0) touchPlaylist(database, playlistId)
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        val database = writableDatabase
        database.transaction {
            database.delete(
                PLAYLIST_ITEMS_TABLE,
                "$PLAYLIST_ITEM_PLAYLIST_ID = ? AND $PLAYLIST_ITEM_TRACK_ID = ?",
                arrayOf(playlistId.toString(), trackId.toString()),
            )
            normalizePlaylistPositions(database, playlistId)
            touchPlaylist(database, playlistId)
        }
    }

    fun movePlaylistTrack(playlistId: Long, fromIndex: Int, toIndex: Int) {
        val database = writableDatabase
        database.transaction {
            val trackIds = readPlaylistTrackIds(database, playlistId).toMutableList()
            if (fromIndex !in trackIds.indices || toIndex !in trackIds.indices || fromIndex == toIndex) {
                return@transaction
            }
            val moved = trackIds.removeAt(fromIndex)
            trackIds.add(toIndex, moved)
            trackIds.forEachIndexed { index, trackId ->
                database.update(
                    PLAYLIST_ITEMS_TABLE,
                    ContentValues().apply { put(PLAYLIST_ITEM_POSITION, index) },
                    "$PLAYLIST_ITEM_PLAYLIST_ID = ? AND $PLAYLIST_ITEM_TRACK_ID = ?",
                    arrayOf(playlistId.toString(), trackId.toString()),
                )
            }
            touchPlaylist(database, playlistId)
        }
    }

    fun markInterruptedScans() {
        writableDatabase.update(
            SOURCES_TABLE,
            ContentValues().apply { put(SOURCE_SCAN_STATE, LibraryScanState.INTERRUPTED.name) },
            "$SOURCE_SCAN_STATE = ?",
            arrayOf(LibraryScanState.SCANNING.name),
        )
    }

    fun preparePausedSourcesForResume() {
        writableDatabase.update(
            SOURCES_TABLE,
            ContentValues().apply { put(SOURCE_SCAN_STATE, LibraryScanState.IDLE.name) },
            "$SOURCE_SCAN_STATE = ?",
            arrayOf(LibraryScanState.PAUSED.name),
        )
    }

    fun beginSourceScan(sourceId: String): SourceScanSession {
        val database = writableDatabase
        database.beginTransaction()
        return try {
            val previousEpoch = database.query(
                SOURCES_TABLE,
                arrayOf(SOURCE_SCAN_EPOCH),
                "$SOURCE_ID = ?",
                arrayOf(sourceId),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            val nextEpoch = previousEpoch + 1
            database.update(
                SOURCES_TABLE,
                ContentValues().apply {
                    put(SOURCE_SCAN_STATE, LibraryScanState.SCANNING.name)
                    put(SOURCE_SCAN_EPOCH, nextEpoch)
                },
                "$SOURCE_ID = ?",
                arrayOf(sourceId),
            )
            database.setTransactionSuccessful()
            SourceScanSession(sourceId = sourceId, epoch = nextEpoch)
        } finally {
            database.endTransaction()
        }
    }

    /** Marks an unchanged record as present without rewriting its metadata or artwork revision. */
    fun markSeenIfFingerprintMatches(session: SourceScanSession, remoteId: String, fingerprint: String): Boolean {
        val matches = readableDatabase.query(
            TRACKS_TABLE,
            arrayOf(TRACK_FINGERPRINT),
            "$TRACK_SOURCE_ID = ? AND $TRACK_REMOTE_ID = ?",
            arrayOf(session.sourceId, remoteId),
            null,
            null,
            null,
        ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == fingerprint }
        if (matches) {
            writableDatabase.update(
                TRACKS_TABLE,
                ContentValues().apply { put(TRACK_SEEN_EPOCH, session.epoch) },
                "$TRACK_SOURCE_ID = ? AND $TRACK_REMOTE_ID = ?",
                arrayOf(session.sourceId, remoteId),
            )
        }
        return matches
    }

    fun upsertTrack(session: SourceScanSession, candidate: LibraryTrackCandidate) {
        val existing = readableDatabase.query(
            TRACKS_TABLE,
            arrayOf(TRACK_ID),
            "$TRACK_SOURCE_ID = ? AND $TRACK_REMOTE_ID = ?",
            arrayOf(session.sourceId, candidate.remoteId),
            null,
            null,
            null,
        ).use { cursor -> cursor.moveToFirst() }
        val values = candidate.toContentValues(session)
        if (existing) {
            writableDatabase.update(
                TRACKS_TABLE,
                values,
                "$TRACK_SOURCE_ID = ? AND $TRACK_REMOTE_ID = ?",
                arrayOf(session.sourceId, candidate.remoteId),
            )
        } else {
            values.put(TRACK_SOURCE_ID, session.sourceId)
            writableDatabase.insertOrThrow(TRACKS_TABLE, null, values)
        }
    }

    /**
     * Publishes unseen-row removal and the completed generation/state as one transaction. A
     * paused or failed scan never prunes rows that were not reached by that iteration.
     */
    fun completeSourceScan(session: SourceScanSession, generation: String? = null) {
        completeSourceScan(
            session = session,
            generation = generation,
            prunableMediaStoreVolumes = null,
        )
    }

    /**
     * Completes a device scan without treating an unmounted volume as an empty one. Tracks on
     * volumes absent from this scan retain their catalog rows and user-owned metadata.
     */
    fun completeMediaStoreScan(
        session: SourceScanSession,
        generation: String?,
        scannedVolumeNames: Collection<String>,
    ) {
        require(session.sourceId == LibrarySourceId.DEVICE)
        require(scannedVolumeNames.isNotEmpty())
        completeSourceScan(
            session = session,
            generation = generation,
            prunableMediaStoreVolumes = mediaStorePrunableVolumeNames(scannedVolumeNames),
        )
    }

    private fun completeSourceScan(
        session: SourceScanSession,
        generation: String?,
        prunableMediaStoreVolumes: Set<String>?,
    ) {
        val database = writableDatabase
        database.transaction {
            val prunePatterns = prunableMediaStoreVolumes?.map { volumeName ->
                "content://media/$volumeName/*"
            }
            val pruneSelection = buildString {
                append("$TRACK_SOURCE_ID = ? AND $TRACK_SEEN_EPOCH != ?")
                if (prunePatterns != null) {
                    append(" AND (")
                    append(
                        prunePatterns.joinToString(separator = " OR ") {
                            "$TRACK_CONTENT_URI GLOB ?"
                        },
                    )
                    append(")")
                }
            }
            val pruneArguments = buildList {
                add(session.sourceId)
                add(session.epoch.toString())
                if (prunePatterns != null) addAll(prunePatterns)
            }.toTypedArray()
            database.delete(
                TRACKS_TABLE,
                pruneSelection,
                pruneArguments,
            )
            check(updateCompletedSource(database, session.sourceId, generation) == 1) {
                "Cannot complete a scan for a missing source"
            }
        }
    }

    fun finishSourceScan(sourceId: String, generation: String? = null) {
        updateCompletedSource(writableDatabase, sourceId, generation)
    }

    private fun updateCompletedSource(
        database: SQLiteDatabase,
        sourceId: String,
        generation: String?,
    ): Int = database.update(
        SOURCES_TABLE,
        ContentValues().apply {
            put(SOURCE_SCAN_STATE, LibraryScanState.IDLE.name)
            put(SOURCE_LAST_SCANNED_AT, System.currentTimeMillis())
            if (generation != null) put(SOURCE_GENERATION, generation)
        },
        "$SOURCE_ID = ?",
        arrayOf(sourceId),
    )

    fun markSourcePaused(sourceId: String) = setSourceState(sourceId, LibraryScanState.PAUSED)

    fun markSourceScanning(sourceId: String) = setSourceState(sourceId, LibraryScanState.SCANNING)

    fun markSourceInterrupted(sourceId: String) = setSourceState(sourceId, LibraryScanState.INTERRUPTED)

    fun markSourceFailed(sourceId: String) = setSourceState(sourceId, LibraryScanState.FAILED)

    private fun setSourceState(sourceId: String, state: LibraryScanState) {
        writableDatabase.update(
            SOURCES_TABLE,
            ContentValues().apply { put(SOURCE_SCAN_STATE, state.name) },
            "$SOURCE_ID = ?",
            arrayOf(sourceId),
        )
    }

    private fun findSource(sourceId: String): StoredLibrarySource? = readableDatabase.query(
        SOURCES_TABLE,
        SOURCE_COLUMNS,
        "$SOURCE_ID = ?",
        arrayOf(sourceId),
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toStoredSource(trackCount = 0) else null }

    private fun Cursor.toStoredSource(trackCount: Int? = null): StoredLibrarySource = StoredLibrarySource(
        id = getString(getColumnIndexOrThrow(SOURCE_ID)),
        kind = LibrarySourceKind.valueOf(getString(getColumnIndexOrThrow(SOURCE_KIND))),
        displayName = getString(getColumnIndexOrThrow(SOURCE_DISPLAY_NAME)),
        treeUri = getStringOrNull(SOURCE_TREE_URI),
        scanState = LibraryScanState.valueOf(getString(getColumnIndexOrThrow(SOURCE_SCAN_STATE))),
        generation = getStringOrNull(SOURCE_GENERATION),
        scanEpoch = getLong(getColumnIndexOrThrow(SOURCE_SCAN_EPOCH)),
        trackCount = trackCount ?: getInt(getColumnIndexOrThrow("track_count")),
    )

    private fun readTrackMigrationState(
        database: SQLiteDatabase,
        remoteId: String,
        requireSyntheticExternalUri: Boolean,
    ): TrackMigrationState? {
        val selection = buildString {
            append("$TRACK_SOURCE_ID = ? AND $TRACK_REMOTE_ID = ?")
            if (requireSyntheticExternalUri) {
                append(" AND $TRACK_CONTENT_URI GLOB ?")
            }
        }
        val arguments = buildList {
            add(LibrarySourceId.DEVICE)
            add(remoteId)
            if (requireSyntheticExternalUri) add("content://media/external/*")
        }.toTypedArray()
        return database.query(
            TRACKS_TABLE,
            arrayOf(
                TRACK_ID,
                TRACK_IS_FAVORITE,
                TRACK_FAVORITE_POSITION,
                TRACK_LAST_PLAYED_AT_MS,
                TRACK_PLAY_COUNT,
            ),
            selection,
            arguments,
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                TrackMigrationState(
                    trackId = cursor.getLong(0),
                    isFavorite = cursor.getInt(1) != 0,
                    favoritePosition = if (cursor.isNull(2)) null else cursor.getLong(2),
                    lastPlayedAtMs = cursor.getLong(3),
                    playCount = cursor.getInt(4),
                )
            }
        }
    }

    private fun Cursor.toAudioTrack(): AudioTrack = AudioTrack(
        id = getLong(getColumnIndexOrThrow(TRACK_ID)),
        contentUri = getString(getColumnIndexOrThrow(TRACK_CONTENT_URI)),
        title = getString(getColumnIndexOrThrow(TRACK_TITLE)),
        artist = getString(getColumnIndexOrThrow(TRACK_ARTIST)),
        album = getString(getColumnIndexOrThrow(TRACK_ALBUM)),
        durationMs = getLong(getColumnIndexOrThrow(TRACK_DURATION_MS)),
        albumId = getLongOrNull(TRACK_ALBUM_ID),
        albumArtworkUri = getStringOrNull(TRACK_ALBUM_ARTWORK_URI),
        dateModifiedSeconds = getLong(getColumnIndexOrThrow(TRACK_DATE_MODIFIED_SECONDS)),
        artworkRevision = getLong(getColumnIndexOrThrow(TRACK_ARTWORK_REVISION)),
        albumArtist = getString(getColumnIndexOrThrow(TRACK_ALBUM_ARTIST)),
        trackNumber = getIntOrNull(TRACK_NUMBER),
        discNumber = getIntOrNull(TRACK_DISC_NUMBER),
        year = getIntOrNull(TRACK_YEAR),
        genre = getString(getColumnIndexOrThrow(TRACK_GENRE)),
        fileName = getString(getColumnIndexOrThrow(TRACK_FILE_NAME)),
        folderName = getString(getColumnIndexOrThrow(TRACK_FOLDER_NAME)),
        fileSizeBytes = getLong(getColumnIndexOrThrow(TRACK_SIZE_BYTES)),
        mimeType = getString(getColumnIndexOrThrow(TRACK_MIME_TYPE)),
        codec = getString(getColumnIndexOrThrow(TRACK_CODEC)),
        channelCount = getIntOrNull(TRACK_CHANNEL_COUNT),
        bitDepth = getIntOrNull(TRACK_BIT_DEPTH),
        sampleRateHz = getIntOrNull(TRACK_SAMPLE_RATE_HZ),
        bitrate = getIntOrNull(TRACK_BITRATE),
        isFavorite = getInt(getColumnIndexOrThrow(TRACK_IS_FAVORITE)) != 0,
        favoritePosition = getLongOrNull(TRACK_FAVORITE_POSITION),
        lastPlayedAtMs = getLong(getColumnIndexOrThrow(TRACK_LAST_PLAYED_AT_MS)),
        playCount = getInt(getColumnIndexOrThrow(TRACK_PLAY_COUNT)),
    )

    private fun LibraryTrackCandidate.toContentValues(session: SourceScanSession): ContentValues = ContentValues().apply {
        put(TRACK_REMOTE_ID, remoteId)
        put(TRACK_CONTENT_URI, contentUri)
        put(TRACK_TITLE, title)
        put(TRACK_ARTIST, artist)
        put(TRACK_ALBUM, album)
        put(TRACK_DURATION_MS, durationMs)
        putNullableLong(TRACK_ALBUM_ID, albumId)
        putNullableString(TRACK_ALBUM_ARTWORK_URI, albumArtworkUri)
        put(TRACK_DATE_MODIFIED_SECONDS, dateModifiedSeconds)
        put(TRACK_SIZE_BYTES, sizeBytes)
        put(TRACK_MIME_TYPE, mimeType)
        put(TRACK_ALBUM_ARTIST, albumArtist)
        putNullableInt(TRACK_NUMBER, trackNumber)
        putNullableInt(TRACK_DISC_NUMBER, discNumber)
        putNullableInt(TRACK_YEAR, year)
        put(TRACK_GENRE, genre)
        put(TRACK_FILE_NAME, fileName)
        put(TRACK_FOLDER_NAME, folderName)
        put(TRACK_CODEC, codec)
        putNullableInt(TRACK_CHANNEL_COUNT, channelCount)
        putNullableInt(TRACK_BIT_DEPTH, bitDepth)
        putNullableInt(TRACK_SAMPLE_RATE_HZ, sampleRateHz)
        putNullableInt(TRACK_BITRATE, bitrate)
        put(TRACK_FINGERPRINT, fingerprint)
        put(TRACK_ARTWORK_REVISION, session.epoch)
        put(TRACK_SEEN_EPOCH, session.epoch)
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.getIntOrNull(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private fun ContentValues.putNullableString(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullableInt(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun readPlaylistTrackIds(playlistId: Long): List<Long> =
        readPlaylistTrackIds(readableDatabase, playlistId)

    private fun readPlaylistTrackIds(database: SQLiteDatabase, playlistId: Long): List<Long> = database.query(
        PLAYLIST_ITEMS_TABLE,
        arrayOf(PLAYLIST_ITEM_TRACK_ID),
        "$PLAYLIST_ITEM_PLAYLIST_ID = ?",
        arrayOf(playlistId.toString()),
        null,
        null,
        "$PLAYLIST_ITEM_POSITION ASC, $PLAYLIST_ITEM_ADDED_AT_MS ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getLong(0))
        }
    }

    private fun normalizePlaylistPositions(database: SQLiteDatabase, playlistId: Long) {
        val ids = database.query(
            PLAYLIST_ITEMS_TABLE,
            arrayOf(PLAYLIST_ITEM_TRACK_ID),
            "$PLAYLIST_ITEM_PLAYLIST_ID = ?",
            arrayOf(playlistId.toString()),
            null,
            null,
            "$PLAYLIST_ITEM_POSITION ASC, $PLAYLIST_ITEM_ADDED_AT_MS ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
        ids.forEachIndexed { index, trackId ->
            database.update(
                PLAYLIST_ITEMS_TABLE,
                ContentValues().apply { put(PLAYLIST_ITEM_POSITION, index) },
                "$PLAYLIST_ITEM_PLAYLIST_ID = ? AND $PLAYLIST_ITEM_TRACK_ID = ?",
                arrayOf(playlistId.toString(), trackId.toString()),
            )
        }
    }

    private fun touchPlaylist(database: SQLiteDatabase, playlistId: Long) {
        database.update(
            PLAYLISTS_TABLE,
            ContentValues().apply { put(PLAYLIST_UPDATED_AT_MS, System.currentTimeMillis()) },
            "$PLAYLIST_ID = ?",
            arrayOf(playlistId.toString()),
        )
    }

    private fun migrateToVersion2(db: SQLiteDatabase) {
        listOf(
            "$TRACK_ALBUM_ARTIST TEXT NOT NULL DEFAULT ''",
            "$TRACK_NUMBER INTEGER",
            "$TRACK_DISC_NUMBER INTEGER",
            "$TRACK_YEAR INTEGER",
            "$TRACK_GENRE TEXT NOT NULL DEFAULT ''",
            "$TRACK_FILE_NAME TEXT NOT NULL DEFAULT ''",
            "$TRACK_FOLDER_NAME TEXT NOT NULL DEFAULT ''",
            "$TRACK_CODEC TEXT NOT NULL DEFAULT ''",
            "$TRACK_CHANNEL_COUNT INTEGER",
            "$TRACK_BIT_DEPTH INTEGER",
            "$TRACK_SAMPLE_RATE_HZ INTEGER",
            "$TRACK_BITRATE INTEGER",
            "$TRACK_IS_FAVORITE INTEGER NOT NULL DEFAULT 0",
            "$TRACK_LAST_PLAYED_AT_MS INTEGER NOT NULL DEFAULT 0",
            "$TRACK_PLAY_COUNT INTEGER NOT NULL DEFAULT 0",
        ).forEach { definition ->
            db.execSQL("ALTER TABLE $TRACKS_TABLE ADD COLUMN $definition")
        }
        db.execSQL("CREATE INDEX index_library_tracks_album ON $TRACKS_TABLE($TRACK_ALBUM)")
        db.execSQL("CREATE INDEX index_library_tracks_artist ON $TRACKS_TABLE($TRACK_ARTIST)")
        db.execSQL("CREATE INDEX index_library_tracks_folder ON $TRACKS_TABLE($TRACK_FOLDER_NAME)")
        db.execSQL("CREATE INDEX index_library_tracks_recent ON $TRACKS_TABLE($TRACK_LAST_PLAYED_AT_MS)")
        createPlaylistTables(db)
    }

    /**
     * v2 added fields that are populated by provider/container metadata reads, but its original
     * migration retained the old fingerprints and MediaStore generation. Existing rows could
     * therefore be treated as unchanged forever. v3 invalidates only those cache validators so
     * the next normal scan refreshes metadata without discarding favorites, history, or playlists.
     */
    private fun invalidateCatalogForMetadataReconciliation(db: SQLiteDatabase) {
        db.execSQL("UPDATE $SOURCES_TABLE SET $SOURCE_GENERATION = NULL")
        db.execSQL("UPDATE $TRACKS_TABLE SET $TRACK_FINGERPRINT = ''")
    }

    private fun createPlaylistTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $PLAYLISTS_TABLE (
                $PLAYLIST_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $PLAYLIST_NAME TEXT NOT NULL COLLATE NOCASE UNIQUE,
                $PLAYLIST_CREATED_AT_MS INTEGER NOT NULL,
                $PLAYLIST_UPDATED_AT_MS INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $PLAYLIST_ITEMS_TABLE (
                $PLAYLIST_ITEM_PLAYLIST_ID INTEGER NOT NULL,
                $PLAYLIST_ITEM_TRACK_ID INTEGER NOT NULL,
                $PLAYLIST_ITEM_POSITION INTEGER NOT NULL,
                $PLAYLIST_ITEM_ADDED_AT_MS INTEGER NOT NULL,
                PRIMARY KEY ($PLAYLIST_ITEM_PLAYLIST_ID, $PLAYLIST_ITEM_TRACK_ID),
                FOREIGN KEY ($PLAYLIST_ITEM_PLAYLIST_ID) REFERENCES $PLAYLISTS_TABLE($PLAYLIST_ID)
                    ON DELETE CASCADE,
                FOREIGN KEY ($PLAYLIST_ITEM_TRACK_ID) REFERENCES $TRACKS_TABLE($TRACK_ID)
                    ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_playlist_items_order ON $PLAYLIST_ITEMS_TABLE($PLAYLIST_ITEM_PLAYLIST_ID, $PLAYLIST_ITEM_POSITION)",
        )
    }

    companion object {
        private const val DATABASE_NAME = "library-catalog.db"
        private const val DATABASE_VERSION = 4
        private const val DEVICE_SOURCE_NAME = "Device music"

        private const val SOURCES_TABLE = "library_sources"
        private const val SOURCE_ID = "source_id"
        private const val SOURCE_KIND = "source_kind"
        private const val SOURCE_DISPLAY_NAME = "display_name"
        private const val SOURCE_TREE_URI = "tree_uri"
        private const val SOURCE_SCAN_STATE = "scan_state"
        private const val SOURCE_GENERATION = "generation"
        private const val SOURCE_SCAN_EPOCH = "scan_epoch"
        private const val SOURCE_LAST_SCANNED_AT = "last_scanned_at"

        private const val TRACKS_TABLE = "library_tracks"
        private const val TRACK_ID = "track_id"
        private const val TRACK_SOURCE_ID = "source_id"
        private const val TRACK_REMOTE_ID = "remote_id"
        private const val TRACK_CONTENT_URI = "content_uri"
        private const val TRACK_TITLE = "title"
        private const val TRACK_ARTIST = "artist"
        private const val TRACK_ALBUM = "album"
        private const val TRACK_DURATION_MS = "duration_ms"
        private const val TRACK_ALBUM_ID = "album_id"
        private const val TRACK_ALBUM_ARTWORK_URI = "album_artwork_uri"
        private const val TRACK_DATE_MODIFIED_SECONDS = "date_modified_seconds"
        private const val TRACK_SIZE_BYTES = "size_bytes"
        private const val TRACK_MIME_TYPE = "mime_type"
        private const val TRACK_ALBUM_ARTIST = "album_artist"
        private const val TRACK_NUMBER = "track_number"
        private const val TRACK_DISC_NUMBER = "disc_number"
        private const val TRACK_YEAR = "year"
        private const val TRACK_GENRE = "genre"
        private const val TRACK_FILE_NAME = "file_name"
        private const val TRACK_FOLDER_NAME = "folder_name"
        private const val TRACK_CODEC = "codec"
        private const val TRACK_CHANNEL_COUNT = "channel_count"
        private const val TRACK_BIT_DEPTH = "bit_depth"
        private const val TRACK_SAMPLE_RATE_HZ = "sample_rate_hz"
        private const val TRACK_BITRATE = "bitrate"
        private const val TRACK_IS_FAVORITE = "is_favorite"
        private const val TRACK_FAVORITE_POSITION = "favorite_position"
        private const val TRACK_LAST_PLAYED_AT_MS = "last_played_at_ms"
        private const val TRACK_PLAY_COUNT = "play_count"
        private const val TRACK_FINGERPRINT = "fingerprint"
        private const val TRACK_ARTWORK_REVISION = "artwork_revision"
        private const val TRACK_SEEN_EPOCH = "seen_epoch"
        private const val UNRESOLVED_LEGACY_MEDIASTORE_PREFIX = "legacy-unresolved:"

        private const val PLAYLISTS_TABLE = "library_playlists"
        private const val PLAYLIST_ID = "playlist_id"
        private const val PLAYLIST_NAME = "name"
        private const val PLAYLIST_CREATED_AT_MS = "created_at_ms"
        private const val PLAYLIST_UPDATED_AT_MS = "updated_at_ms"

        private const val PLAYLIST_ITEMS_TABLE = "library_playlist_items"
        private const val PLAYLIST_ITEM_PLAYLIST_ID = "playlist_id"
        private const val PLAYLIST_ITEM_TRACK_ID = "track_id"
        private const val PLAYLIST_ITEM_POSITION = "position"
        private const val PLAYLIST_ITEM_ADDED_AT_MS = "added_at_ms"

        private val SOURCE_COLUMNS = arrayOf(
            SOURCE_ID,
            SOURCE_KIND,
            SOURCE_DISPLAY_NAME,
            SOURCE_TREE_URI,
            SOURCE_SCAN_STATE,
            SOURCE_GENERATION,
            SOURCE_SCAN_EPOCH,
        )
        private val TRACK_COLUMNS = arrayOf(
            TRACK_ID,
            TRACK_CONTENT_URI,
            TRACK_TITLE,
            TRACK_ARTIST,
            TRACK_ALBUM,
            TRACK_DURATION_MS,
            TRACK_ALBUM_ID,
            TRACK_ALBUM_ARTWORK_URI,
            TRACK_DATE_MODIFIED_SECONDS,
            TRACK_ARTWORK_REVISION,
            TRACK_SIZE_BYTES,
            TRACK_MIME_TYPE,
            TRACK_ALBUM_ARTIST,
            TRACK_NUMBER,
            TRACK_DISC_NUMBER,
            TRACK_YEAR,
            TRACK_GENRE,
            TRACK_FILE_NAME,
            TRACK_FOLDER_NAME,
            TRACK_CODEC,
            TRACK_CHANNEL_COUNT,
            TRACK_BIT_DEPTH,
            TRACK_SAMPLE_RATE_HZ,
            TRACK_BITRATE,
            TRACK_IS_FAVORITE,
            TRACK_FAVORITE_POSITION,
            TRACK_LAST_PLAYED_AT_MS,
            TRACK_PLAY_COUNT,
        )
    }
}

internal data class StoredLibrarySource(
    val id: String,
    val kind: LibrarySourceKind,
    val displayName: String,
    val treeUri: String?,
    val scanState: LibraryScanState,
    val generation: String?,
    val scanEpoch: Long,
    val trackCount: Int,
)

internal data class SourceScanSession(
    val sourceId: String,
    val epoch: Long,
)

private data class TrackMigrationState(
    val trackId: Long,
    val isFavorite: Boolean,
    val favoritePosition: Long?,
    val lastPlayedAtMs: Long,
    val playCount: Int,
)
