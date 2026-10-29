package io.github.sumirenokai.vesqen.library

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * #165: after a corrupt catalog was replaced by an empty one, the app says once what was lost. It
 * shows the first time the app is open after the rebuild, at once when the app is open already.
 */
interface LibraryRebuildNotice {
    /** True from a rebuild until the notice was on screen. */
    val due: StateFlow<Boolean>

    /** The notice is on screen; it is not shown again until another rebuild. */
    fun markShown()
}

internal class AndroidLibraryRebuildNotice(context: Context) : LibraryRebuildNotice {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val dueState = MutableStateFlow(preferences.getBoolean(KEY_DUE, false))
    override val due: StateFlow<Boolean> = dueState.asStateFlow()

    /** On disk before the catalog is replaced, so an open after the process ended still shows it. */
    fun markDue() {
        preferences.edit(commit = true) { putBoolean(KEY_DUE, true) }
        dueState.value = true
    }

    override fun markShown() {
        preferences.edit { remove(KEY_DUE) }
        dueState.value = false
    }

    private companion object {
        const val PREFERENCES_NAME = "library-rebuild-notice"
        const val KEY_DUE = "due"
    }
}
