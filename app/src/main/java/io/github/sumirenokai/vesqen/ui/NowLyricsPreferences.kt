package io.github.sumirenokai.vesqen.ui

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Now shows the lyrics or the cover (#169). It stays as the listener left it when Now
 * closes, the track changes or the app restarts (owner decision 3).
 */
class NowLyricsPreferences(context: Context, preferencesName: String = PREFERENCES_NAME) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getBoolean(KEY_SHOWN, false))
    val shown: StateFlow<Boolean> = state.asStateFlow()

    fun setShown(shown: Boolean) {
        if (shown == state.value) return
        preferences.edit { putBoolean(KEY_SHOWN, shown) }
        state.value = shown
    }

    private companion object {
        const val PREFERENCES_NAME = "now"
        const val KEY_SHOWN = "lyrics-shown"
    }
}
