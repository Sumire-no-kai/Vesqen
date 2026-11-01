package io.github.sumirenokai.vesqen.library

import android.content.Context
import androidx.core.content.edit

/**
 * #191 D20: a rebuilt catalog lists no folders, but Android keeps the grants of the folders the
 * corrupt one listed. A new catalog marks the folders due, and the next scan adds them back from
 * those grants. On a first install there are none.
 */
internal class LibraryFolderRestore(context: Context, databaseName: String) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    // One mark per catalog file, so tests with their own files do not share one.
    private val key = databaseName

    /** On disk at once, so a scan after the process ended still adds the folders back. */
    @Synchronized
    fun markDue() {
        preferences.edit(commit = true) { putLong(key, preferences.getLong(key, 0) + 1) }
    }

    /** Zero when no folders are due; otherwise the mark that [restored] clears. */
    @Synchronized
    fun due(): Long = preferences.getLong(key, 0)

    /** Clears [due], unless a catalog created since the scan read it marked the folders due again. */
    @Synchronized
    fun restored(due: Long) {
        if (preferences.getLong(key, 0) == due) preferences.edit(commit = true) { remove(key) }
    }

    private companion object {
        const val PREFERENCES_NAME = "library-folder-restore"
    }
}
