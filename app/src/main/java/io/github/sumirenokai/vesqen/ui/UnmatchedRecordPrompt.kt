package io.github.sumirenokai.vesqen.ui

import android.content.Context
import androidx.core.content.edit

/**
 * "Later" on the Library's line about unmatched records (#139): the records it was pressed for. The
 * line shows again once another record waits, and each press replaces the set.
 */
internal class UnmatchedRecordPrompt(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun dismissedRecordIds(): Set<Long> =
        preferences.getString(KEY_DISMISSED_RECORD_IDS, null).orEmpty().split(',').mapNotNullTo(HashSet(), String::toLongOrNull)

    fun dismiss(recordIds: Set<Long>) {
        preferences.edit { putString(KEY_DISMISSED_RECORD_IDS, recordIds.joinToString(",")) }
    }

    private companion object {
        const val PREFERENCES_NAME = "library-unmatched-records"
        const val KEY_DISMISSED_RECORD_IDS = "dismissed_record_ids"
    }
}
