package io.github.sumirenokai.vesqen.library

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * #165: Android's default handler deletes a database SQLite reports corrupt, and with the catalog
 * every favorite, play count, playlist and added folder. This one moves the damaged files aside as
 * one copy that can still be read later, then lets the helper create an empty catalog as before.
 *
 * [beforeRebuild] runs first, while the damaged catalog is still in place: anything keyed by its
 * catalog IDs must be dropped by then, because the new catalog gives the same IDs to other songs.
 */
internal class LibraryCatalogCorruptionHandler(
    private val beforeRebuild: () -> Unit,
) : DatabaseErrorHandler {
    private val handled = Collections.newSetFromMap(WeakHashMap<SQLiteDatabase, Boolean>())
    private val rebuildCount = MutableStateFlow(0)

    /** Rebuilds in this process. Catalog IDs read before one name other songs, or none, after it. */
    val rebuilds: StateFlow<Int> = rebuildCount.asStateFlow()

    /** Holds this handler's lock, which [LibraryCatalogStore] also takes to open the database. */
    @Synchronized
    override fun onCorruption(dbObj: SQLiteDatabase) {
        // Each statement that fails on the same open database reports it again.
        if (!handled.add(dbObj)) return
        beforeRebuild()
        // Counted before the close: a caller whose statement then fails on the closed database already
        // sees this rebuild and treats the failure as it (#191 D6). Opening waits for this lock, so no
        // one reads the new catalog before the files below are set aside.
        rebuildCount.update { it + 1 }
        try {
            // Later statements on this database fail; the helper opens a new one for the next caller.
            if (dbObj.isOpen) dbObj.close()
        } finally {
            // A close that fails reaches the caller, but the files are still set aside: a connection
            // left open keeps reading the copy, and the next open starts the empty catalog.
            val path = dbObj.path
            if (path != IN_MEMORY_PATH && path.isNotBlank()) keepCorruptCopy(File(path))
        }
    }

    private companion object {
        const val IN_MEMORY_PATH = ":memory:"
    }
}

/**
 * Moves [database] and its journals to `<name>.corrupt`, replacing an earlier copy, and removes the
 * rest as the default handler does. A file that cannot be moved is deleted, as before #165. Without
 * a file at [database], an earlier call already moved it and the copy stays.
 */
internal fun keepCorruptCopy(database: File) {
    if (database.exists()) {
        val copy = File(database.path + CORRUPT_COPY_SUFFIX)
        SQLiteDatabase.deleteDatabase(copy)
        if (database.renameTo(copy)) {
            // SQLite finds a database's journals by its name, so they move with the copy.
            CORRUPT_COPY_JOURNALS.forEach { suffix -> File(database.path + suffix).renameTo(File(copy.path + suffix)) }
        }
    }
    SQLiteDatabase.deleteDatabase(database)
}

internal const val CORRUPT_COPY_SUFFIX = ".corrupt"
private val CORRUPT_COPY_JOURNALS = listOf("-wal", "-journal")
