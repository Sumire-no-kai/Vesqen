package io.github.sumirenokai.vesqen.diagnostics

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class DiagnosticRecordingStart(
    val id: String,
    val startedAt: DiagnosticTimestamp,
    val limits: DiagnosticRecordingLimits,
)

internal data class DiagnosticRecordingBatch(
    val recordingId: String,
    val snapshot: TelemetrySnapshot,
    val events: List<TelemetryEvent>,
    val progress: DiagnosticRecordingProgress,
)

/** Persistence seam for the bounded diagnostic recording owned by [DiagnosticRecorder]. */
internal interface DiagnosticRecordingStore {
    suspend fun restore(): DiagnosticRecordingSummary?
    suspend fun begin(recording: DiagnosticRecordingStart): Boolean
    suspend fun append(batch: DiagnosticRecordingBatch): Boolean
    suspend fun finish(summary: DiagnosticRecordingSummary): Boolean
    suspend fun clear(recordingId: String): Boolean
    suspend fun export(recordingId: String, output: OutputStream): DiagnosticExportResult
}

/** Keeps ordinary recorder tests and previews free of Android persistence. */
internal object VolatileDiagnosticRecordingStore : DiagnosticRecordingStore {
    override suspend fun restore(): DiagnosticRecordingSummary? = null
    override suspend fun begin(recording: DiagnosticRecordingStart): Boolean = true
    override suspend fun append(batch: DiagnosticRecordingBatch): Boolean = true
    override suspend fun finish(summary: DiagnosticRecordingSummary): Boolean = true
    override suspend fun clear(recordingId: String): Boolean = true
    override suspend fun export(recordingId: String, output: OutputStream) =
        DiagnosticExportResult.Failure(DiagnosticExportFailure.NO_STOPPED_RECORDING)
}

/**
 * Debug/Internal adapter backed by a dedicated private SQLite journal.
 *
 * Each observed sample is privacy-filtered before entering the database. Inserts, retention pruning,
 * progress updates and stop markers share one transaction, so an interrupted process can restore the
 * last complete sample without retaining media paths, route names, USB product names or exception text.
 */
internal class AndroidDiagnosticRecordingStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiagnosticRecordingStore {
    private val databaseFile = File(context.noBackupFilesDir, DATABASE_DIRECTORY)
        .resolve(DATABASE_NAME)
    private val mutation = Mutex()
    private var fragmentEncoder = DiagnosticJsonFragmentEncoder()
    private var openedDatabase: SQLiteDatabase? = null

    override suspend fun restore(): DiagnosticRecordingSummary? = onDatabase {
        try {
            val database = database()
            val current = readStoredState(database) ?: return@onDatabase null
            if (!current.active) return@onDatabase current.summary

            val stoppedAt = latestObservation(database, current.summary.startedAt)
            database.transaction {
                val values = ContentValues().apply {
                    put(COLUMN_ACTIVE, 0)
                    put(COLUMN_STOPPED_EPOCH_MS, stoppedAt.epochMs)
                    put(COLUMN_STOPPED_ELAPSED_MS, stoppedAt.elapsedRealtimeMs)
                    put(COLUMN_TERMINATION, DiagnosticRecordingTermination.PROCESS_TERMINATED.name)
                }
                update(
                    TABLE_RECORDING,
                    values,
                    "$COLUMN_SINGLETON = ?",
                    arrayOf(SINGLETON_ID.toString()),
                )
            }
            readStoredState(database)?.summary
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun begin(recording: DiagnosticRecordingStart): Boolean = onDatabase {
        try {
            require(recording.id.matches(RECORDING_ID_PATTERN)) { "Unsafe diagnostic recording id" }
            val database = database()
            fragmentEncoder = DiagnosticJsonFragmentEncoder()
            database.transaction {
                delete(TABLE_SNAPSHOT, null, null)
                delete(TABLE_EVENT, null, null)
                delete(TABLE_RECORDING, null, null)
                val values = ContentValues().apply {
                    put(COLUMN_SINGLETON, SINGLETON_ID)
                    put(COLUMN_RECORDING_ID, recording.id)
                    put(COLUMN_STARTED_EPOCH_MS, recording.startedAt.epochMs)
                    put(COLUMN_STARTED_ELAPSED_MS, recording.startedAt.elapsedRealtimeMs)
                    put(COLUMN_MAX_SNAPSHOTS, recording.limits.maxSnapshots)
                    put(COLUMN_MAX_EVENTS, recording.limits.maxEvents)
                    put(COLUMN_ACTIVE, 1)
                    put(COLUMN_DROPPED_SNAPSHOTS, 0L)
                    put(COLUMN_DROPPED_EVENTS, 0L)
                    put(COLUMN_SEQUENCE_GAPS, 0L)
                }
                insertOrThrow(TABLE_RECORDING, null, values)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun append(batch: DiagnosticRecordingBatch): Boolean = onDatabase {
        try {
            val database = database()
            if (!isCurrentActiveRecording(database, batch.recordingId)) return@onDatabase false
            val snapshotJson = fragmentEncoder.snapshot(batch.snapshot)
            val eventJson = batch.events.map(fragmentEncoder::event)
            database.transaction {
                insertOrThrow(
                    TABLE_SNAPSHOT,
                    null,
                    ContentValues().apply {
                        put(COLUMN_OBSERVED_EPOCH_MS, batch.snapshot.capturedAtEpochMs)
                        put(COLUMN_OBSERVED_ELAPSED_MS, batch.snapshot.capturedAtElapsedRealtimeMs)
                        put(COLUMN_JSON, snapshotJson)
                    },
                )
                batch.events.zip(eventJson).forEach { (event, json) ->
                    insertWithOnConflict(
                        TABLE_EVENT,
                        null,
                        ContentValues().apply {
                            put(COLUMN_EVENT_SEQUENCE, event.sequence)
                            put(COLUMN_OBSERVED_EPOCH_MS, event.occurredAtEpochMs)
                            put(COLUMN_OBSERVED_ELAPSED_MS, event.occurredAtElapsedRealtimeMs)
                            put(COLUMN_JSON, json)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
                pruneToNewest(TABLE_SNAPSHOT, COLUMN_SEQUENCE, batch.progress.snapshotCount)
                pruneToNewest(TABLE_EVENT, COLUMN_EVENT_SEQUENCE, batch.progress.eventCount)
                updateProgress(batch.recordingId, batch.progress)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun finish(summary: DiagnosticRecordingSummary): Boolean = onDatabase {
        try {
            val database = database()
            if (!isCurrentRecording(database, summary.id)) return@onDatabase false
            database.transaction {
                val values = ContentValues().apply {
                    put(COLUMN_ACTIVE, 0)
                    put(COLUMN_STOPPED_EPOCH_MS, summary.stoppedAt.epochMs)
                    put(COLUMN_STOPPED_ELAPSED_MS, summary.stoppedAt.elapsedRealtimeMs)
                    put(COLUMN_TERMINATION, summary.termination.name)
                    put(COLUMN_DROPPED_SNAPSHOTS, summary.droppedSnapshotCount)
                    put(COLUMN_DROPPED_EVENTS, summary.droppedEventCount)
                    put(COLUMN_SEQUENCE_GAPS, summary.observedEventSequenceGapCount)
                }
                update(
                    TABLE_RECORDING,
                    values,
                    "$COLUMN_RECORDING_ID = ?",
                    arrayOf(summary.id),
                )
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun clear(recordingId: String): Boolean = onDatabase {
        try {
            val database = database()
            val current = readStoredState(database) ?: return@onDatabase true
            if (current.summary.id != recordingId) return@onDatabase false
            database.transaction {
                delete(TABLE_SNAPSHOT, null, null)
                delete(TABLE_EVENT, null, null)
                delete(TABLE_RECORDING, "$COLUMN_RECORDING_ID = ?", arrayOf(recordingId))
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun export(
        recordingId: String,
        output: OutputStream,
    ): DiagnosticExportResult = onDatabase {
        try {
            val database = database()
            val stored = readStoredState(database)
                ?.takeIf { state -> state.summary.id == recordingId && !state.active }
                ?: return@onDatabase DiagnosticExportResult.Failure(
                    DiagnosticExportFailure.NO_STOPPED_RECORDING,
                )
            writeStoredDiagnosticRecording(database, stored.summary, output)
            DiagnosticExportResult.Success(
                snapshotCount = stored.summary.snapshotCount,
                eventCount = stored.summary.eventCount,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DiagnosticExportResult.Failure(DiagnosticExportFailure.WRITE_FAILED)
        }
    }

    private suspend fun <T> onDatabase(block: () -> T): T = withContext(ioDispatcher) {
        mutation.withLock { block() }
    }

    private fun database(): SQLiteDatabase {
        openedDatabase?.takeIf(SQLiteDatabase::isOpen)?.let { return it }
        databaseFile.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        when (database.version) {
            0 -> database.transaction {
                execSQL(CREATE_RECORDING_TABLE)
                execSQL(CREATE_SNAPSHOT_TABLE)
                execSQL(CREATE_EVENT_TABLE)
                version = DATABASE_VERSION
            }
            DATABASE_VERSION -> {
                database.execSQL(CREATE_RECORDING_TABLE)
                database.execSQL(CREATE_SNAPSHOT_TABLE)
                database.execSQL(CREATE_EVENT_TABLE)
            }
            else -> {
                val unsupportedVersion = database.version
                database.close()
                error("Unsupported diagnostic database schema $unsupportedVersion")
            }
        }
        openedDatabase = database
        return database
    }

    private fun SQLiteDatabase.updateProgress(
        recordingId: String,
        progress: DiagnosticRecordingProgress,
    ) {
        update(
            TABLE_RECORDING,
            ContentValues().apply {
                put(COLUMN_DROPPED_SNAPSHOTS, progress.droppedSnapshotCount)
                put(COLUMN_DROPPED_EVENTS, progress.droppedEventCount)
                put(COLUMN_SEQUENCE_GAPS, progress.observedEventSequenceGapCount)
            },
            "$COLUMN_RECORDING_ID = ?",
            arrayOf(recordingId),
        )
    }

    private fun SQLiteDatabase.pruneToNewest(table: String, orderColumn: String, retainedCount: Int) {
        if (retainedCount <= 0) {
            delete(table, null, null)
            return
        }
        execSQL(
            "DELETE FROM $table WHERE $orderColumn NOT IN " +
                "(SELECT $orderColumn FROM $table ORDER BY $orderColumn DESC LIMIT $retainedCount)",
        )
    }

    private fun readStoredState(database: SQLiteDatabase): StoredState? {
        val metadata = database.query(
            TABLE_RECORDING,
            RECORDING_COLUMNS,
            "$COLUMN_SINGLETON = ?",
            arrayOf(SINGLETON_ID.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            StoredMetadata(
                id = cursor.string(COLUMN_RECORDING_ID),
                startedAt = DiagnosticTimestamp(
                    cursor.long(COLUMN_STARTED_EPOCH_MS),
                    cursor.long(COLUMN_STARTED_ELAPSED_MS),
                ),
                stoppedAt = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_STOPPED_EPOCH_MS))) {
                    null
                } else {
                    DiagnosticTimestamp(
                        cursor.long(COLUMN_STOPPED_EPOCH_MS),
                        cursor.long(COLUMN_STOPPED_ELAPSED_MS),
                    )
                },
                termination = cursor.optionalString(COLUMN_TERMINATION)?.let(
                    DiagnosticRecordingTermination::valueOf,
                ),
                limits = DiagnosticRecordingLimits(
                    cursor.int(COLUMN_MAX_SNAPSHOTS),
                    cursor.int(COLUMN_MAX_EVENTS),
                ),
                droppedSnapshots = cursor.long(COLUMN_DROPPED_SNAPSHOTS),
                droppedEvents = cursor.long(COLUMN_DROPPED_EVENTS),
                sequenceGaps = cursor.long(COLUMN_SEQUENCE_GAPS),
                active = cursor.int(COLUMN_ACTIVE) == 1,
            )
        }
        require(metadata.id.matches(RECORDING_ID_PATTERN)) { "Unsafe stored recording id" }
        val stoppedAt = metadata.stoppedAt ?: metadata.startedAt
        val termination = metadata.termination ?: DiagnosticRecordingTermination.PROCESS_TERMINATED
        val summary = DiagnosticRecordingSummary(
            id = metadata.id,
            startedAt = metadata.startedAt,
            stoppedAt = stoppedAt,
            termination = termination,
            limits = metadata.limits,
            snapshotCount = database.rowCount(TABLE_SNAPSHOT),
            eventCount = database.rowCount(TABLE_EVENT),
            droppedSnapshotCount = metadata.droppedSnapshots,
            droppedEventCount = metadata.droppedEvents,
            observedEventSequenceGapCount = metadata.sequenceGaps,
        )
        return StoredState(summary, metadata.active)
    }

    private fun latestObservation(
        database: SQLiteDatabase,
        fallback: DiagnosticTimestamp,
    ): DiagnosticTimestamp {
        val candidates = listOfNotNull(
            database.latestTimestamp(TABLE_SNAPSHOT, COLUMN_SEQUENCE),
            database.latestTimestamp(TABLE_EVENT, COLUMN_OBSERVED_ELAPSED_MS),
        )
        return candidates.maxByOrNull(DiagnosticTimestamp::elapsedRealtimeMs)
            ?.takeIf { it.elapsedRealtimeMs >= fallback.elapsedRealtimeMs }
            ?: fallback
    }

    private fun isCurrentRecording(database: SQLiteDatabase, recordingId: String): Boolean =
        database.query(
            TABLE_RECORDING,
            arrayOf(COLUMN_RECORDING_ID),
            "$COLUMN_SINGLETON = ? AND $COLUMN_RECORDING_ID = ?",
            arrayOf(SINGLETON_ID.toString(), recordingId),
            null,
            null,
            null,
            "1",
        ).use(Cursor::moveToFirst)

    private fun isCurrentActiveRecording(database: SQLiteDatabase, recordingId: String): Boolean =
        database.query(
            TABLE_RECORDING,
            arrayOf(COLUMN_RECORDING_ID),
            "$COLUMN_SINGLETON = ? AND $COLUMN_RECORDING_ID = ? AND $COLUMN_ACTIVE = 1",
            arrayOf(SINGLETON_ID.toString(), recordingId),
            null,
            null,
            null,
            "1",
        ).use(Cursor::moveToFirst)

    private fun SQLiteDatabase.rowCount(table: String): Int = rawQuery(
        "SELECT COUNT(*) FROM $table",
        null,
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun SQLiteDatabase.latestTimestamp(table: String, orderColumn: String): DiagnosticTimestamp? = query(
        table,
        arrayOf(COLUMN_OBSERVED_EPOCH_MS, COLUMN_OBSERVED_ELAPSED_MS),
        null,
        null,
        null,
        null,
        "$orderColumn DESC",
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else DiagnosticTimestamp(cursor.getLong(0), cursor.getLong(1))
    }

    private fun writeStoredDiagnosticRecording(
        database: SQLiteDatabase,
        summary: DiagnosticRecordingSummary,
        output: OutputStream,
    ) {
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        writer.append("{\"schemaVersion\":")
        writer.append(DiagnosticJsonExporter.SCHEMA_VERSION.toString())
        writer.append(",\"recording\":{")
        writer.append("\"id\":\"").append(summary.id).append("\",")
        writer.append("\"startedAt\":")
        writer.timestamp(summary.startedAt)
        writer.append(",\"stoppedAt\":")
        writer.timestamp(summary.stoppedAt)
        writer.append(",\"termination\":\"")
            .append(summary.termination.name.lowercase(Locale.ROOT))
            .append("\",")
        writer.append("\"limits\":{\"maxSnapshots\":")
            .append(summary.limits.maxSnapshots.toString())
            .append(",\"maxEvents\":")
            .append(summary.limits.maxEvents.toString())
            .append("},")
        writer.append("\"retention\":{\"snapshotCount\":")
            .append(summary.snapshotCount.toString())
            .append(",\"eventCount\":")
            .append(summary.eventCount.toString())
            .append(",\"droppedSnapshots\":")
            .append(summary.droppedSnapshotCount.toString())
            .append(",\"droppedEvents\":")
            .append(summary.droppedEventCount.toString())
            .append(",\"observedEventSequenceGaps\":")
            .append(summary.observedEventSequenceGapCount.toString())
            .append("},\"snapshots\":[")
        database.appendJsonRows(TABLE_SNAPSHOT, COLUMN_SEQUENCE, writer)
        writer.append("],\"events\":[")
        database.appendJsonRows(TABLE_EVENT, COLUMN_EVENT_SEQUENCE, writer)
        writer.append("]}}\n")
        writer.flush()
    }

    private fun SQLiteDatabase.appendJsonRows(
        table: String,
        orderColumn: String,
        writer: BufferedWriter,
    ) {
        query(table, arrayOf(COLUMN_JSON), null, null, null, null, "$orderColumn ASC").use { cursor ->
            var first = true
            while (cursor.moveToNext()) {
                val fragment = cursor.getString(0)
                require(fragment.startsWith('{') && fragment.endsWith('}')) {
                    "Corrupt diagnostic JSON fragment"
                }
                if (!first) writer.append(',')
                first = false
                writer.append(fragment)
            }
        }
    }

    private fun BufferedWriter.timestamp(timestamp: DiagnosticTimestamp) {
        append("{\"epochMs\":")
        append(timestamp.epochMs.toString())
        append(",\"elapsedRealtimeMs\":")
        append(timestamp.elapsedRealtimeMs.toString())
        append('}')
    }

    private data class StoredMetadata(
        val id: String,
        val startedAt: DiagnosticTimestamp,
        val stoppedAt: DiagnosticTimestamp?,
        val termination: DiagnosticRecordingTermination?,
        val limits: DiagnosticRecordingLimits,
        val droppedSnapshots: Long,
        val droppedEvents: Long,
        val sequenceGaps: Long,
        val active: Boolean,
    )

    private data class StoredState(
        val summary: DiagnosticRecordingSummary,
        val active: Boolean,
    )

    private companion object {
        const val DATABASE_DIRECTORY = "diagnostics"
        const val DATABASE_NAME = "recordings.db"
        const val DATABASE_VERSION = 1
        const val SINGLETON_ID = 1

        const val TABLE_RECORDING = "diagnostic_recording"
        const val TABLE_SNAPSHOT = "diagnostic_snapshot"
        const val TABLE_EVENT = "diagnostic_event"

        const val COLUMN_SINGLETON = "singleton_id"
        const val COLUMN_RECORDING_ID = "recording_id"
        const val COLUMN_STARTED_EPOCH_MS = "started_epoch_ms"
        const val COLUMN_STARTED_ELAPSED_MS = "started_elapsed_ms"
        const val COLUMN_STOPPED_EPOCH_MS = "stopped_epoch_ms"
        const val COLUMN_STOPPED_ELAPSED_MS = "stopped_elapsed_ms"
        const val COLUMN_TERMINATION = "termination"
        const val COLUMN_MAX_SNAPSHOTS = "max_snapshots"
        const val COLUMN_MAX_EVENTS = "max_events"
        const val COLUMN_DROPPED_SNAPSHOTS = "dropped_snapshots"
        const val COLUMN_DROPPED_EVENTS = "dropped_events"
        const val COLUMN_SEQUENCE_GAPS = "sequence_gaps"
        const val COLUMN_ACTIVE = "active"
        const val COLUMN_SEQUENCE = "sequence_id"
        const val COLUMN_EVENT_SEQUENCE = "event_sequence"
        const val COLUMN_OBSERVED_EPOCH_MS = "observed_epoch_ms"
        const val COLUMN_OBSERVED_ELAPSED_MS = "observed_elapsed_ms"
        const val COLUMN_JSON = "json"

        val RECORDING_COLUMNS = arrayOf(
            COLUMN_RECORDING_ID,
            COLUMN_STARTED_EPOCH_MS,
            COLUMN_STARTED_ELAPSED_MS,
            COLUMN_STOPPED_EPOCH_MS,
            COLUMN_STOPPED_ELAPSED_MS,
            COLUMN_TERMINATION,
            COLUMN_MAX_SNAPSHOTS,
            COLUMN_MAX_EVENTS,
            COLUMN_DROPPED_SNAPSHOTS,
            COLUMN_DROPPED_EVENTS,
            COLUMN_SEQUENCE_GAPS,
            COLUMN_ACTIVE,
        )

        val RECORDING_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")

        const val CREATE_RECORDING_TABLE = """
            CREATE TABLE IF NOT EXISTS diagnostic_recording (
                singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
                recording_id TEXT NOT NULL,
                started_epoch_ms INTEGER NOT NULL,
                started_elapsed_ms INTEGER NOT NULL,
                stopped_epoch_ms INTEGER,
                stopped_elapsed_ms INTEGER,
                termination TEXT,
                max_snapshots INTEGER NOT NULL,
                max_events INTEGER NOT NULL,
                dropped_snapshots INTEGER NOT NULL,
                dropped_events INTEGER NOT NULL,
                sequence_gaps INTEGER NOT NULL,
                active INTEGER NOT NULL CHECK (active IN (0, 1))
            )
        """
        const val CREATE_SNAPSHOT_TABLE = """
            CREATE TABLE IF NOT EXISTS diagnostic_snapshot (
                sequence_id INTEGER PRIMARY KEY AUTOINCREMENT,
                observed_epoch_ms INTEGER NOT NULL,
                observed_elapsed_ms INTEGER NOT NULL,
                json TEXT NOT NULL
            )
        """
        const val CREATE_EVENT_TABLE = """
            CREATE TABLE IF NOT EXISTS diagnostic_event (
                event_sequence INTEGER PRIMARY KEY,
                observed_epoch_ms INTEGER NOT NULL,
                observed_elapsed_ms INTEGER NOT NULL,
                json TEXT NOT NULL
            )
        """
    }
}

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}

private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
private fun Cursor.optionalString(column: String): String? =
    getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }
private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
