package io.github.sumirenokai.vesqen.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Private, small SQLite store for the discoverable catalog. It contains only metadata and opaque
 * content URIs; no audio, artwork, or filesystem paths are copied into app storage.
 */
internal class LibraryCatalogStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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

    fun upsertFolderSource(treeUri: String, displayName: String): StoredLibrarySource {
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
        return requireNotNull(findSource(sourceId))
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

    /** Removal is deferred until a source finishes successfully, preserving cached rows on pause/failure. */
    fun pruneUnseenTracks(session: SourceScanSession) {
        writableDatabase.delete(
            TRACKS_TABLE,
            "$TRACK_SOURCE_ID = ? AND $TRACK_SEEN_EPOCH != ?",
            arrayOf(session.sourceId, session.epoch.toString()),
        )
    }

    fun finishSourceScan(sourceId: String, generation: String? = null) {
        writableDatabase.update(
            SOURCES_TABLE,
            ContentValues().apply {
                put(SOURCE_SCAN_STATE, LibraryScanState.IDLE.name)
                put(SOURCE_LAST_SCANNED_AT, System.currentTimeMillis())
                if (generation != null) put(SOURCE_GENERATION, generation)
            },
            "$SOURCE_ID = ?",
            arrayOf(sourceId),
        )
    }

    fun markSourcePaused(sourceId: String) = setSourceState(sourceId, LibraryScanState.PAUSED)

    fun markSourceScanning(sourceId: String) = setSourceState(sourceId, LibraryScanState.SCANNING)

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

    private fun ContentValues.putNullableString(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    companion object {
        private const val DATABASE_NAME = "library-catalog.db"
        private const val DATABASE_VERSION = 1
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
        private const val TRACK_FINGERPRINT = "fingerprint"
        private const val TRACK_ARTWORK_REVISION = "artwork_revision"
        private const val TRACK_SEEN_EPOCH = "seen_epoch"

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
