package io.github.sumirenokai.vesqen.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.sumirenokai.vesqen.library.ArtistSplitSettings
import io.github.sumirenokai.vesqen.library.DefaultArtistSeparators
import io.github.sumirenokai.vesqen.library.DefaultKeptArtistNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Artists page's split settings (#168), beside the other Library browse preferences. Lists are
 * stored one entry per line: Settings splits separators at spaces and names at line breaks, so no
 * entry holds one. A list that was never edited follows the defaults of the installed version.
 */
class ArtistSplitPreferences(context: Context, preferencesName: String = PREFERENCES_NAME) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<ArtistSplitSettings> = state.asStateFlow()

    fun save(settings: ArtistSplitSettings) {
        val current = state.value
        preferences.edit {
            if (settings.enabled != current.enabled) putBoolean(KEY_ENABLED, settings.enabled)
            if (settings.separators != current.separators) putString(KEY_SEPARATORS, settings.separators.joinToString("\n"))
            if (settings.keptNames != current.keptNames) putString(KEY_KEPT_NAMES, settings.keptNames.joinToString("\n"))
        }
        state.value = settings
    }

    private fun read() = ArtistSplitSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, true),
        separators = preferences.list(KEY_SEPARATORS) ?: DefaultArtistSeparators,
        keptNames = preferences.list(KEY_KEPT_NAMES) ?: DefaultKeptArtistNames,
    )

    private fun SharedPreferences.list(key: String): List<String>? =
        getString(key, null)?.split('\n')?.filter(String::isNotEmpty)

    private companion object {
        const val PREFERENCES_NAME = "library-browse"
        const val KEY_ENABLED = "artist-split"
        const val KEY_SEPARATORS = "artist-separators"
        const val KEY_KEPT_NAMES = "artist-kept-names"
    }
}
