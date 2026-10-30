package io.github.sumirenokai.vesqen.ui

import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.ArtistSplitSettings
import io.github.sumirenokai.vesqen.library.DefaultArtistSeparators
import io.github.sumirenokai.vesqen.library.DefaultKeptArtistNames
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ArtistSplitPreferencesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "artist-split-preferences-test"

    @Before
    @After
    fun clear() {
        context.deleteSharedPreferences(name)
    }

    @Test
    fun savedSettingsSurviveANewStore() {
        val saved = ArtistSplitSettings(
            enabled = false,
            separators = listOf("/", "&", "feat."),
            keptNames = listOf("AC/DC", "Earth, Wind & Fire"),
        )
        ArtistSplitPreferences(context, name).save(saved)

        assertEquals(saved, ArtistSplitPreferences(context, name).settings.value)
    }

    @Test
    fun aListThatWasNeverEditedFollowsTheDefaults() {
        val store = ArtistSplitPreferences(context, name)
        assertEquals(ArtistSplitSettings(), store.settings.value)

        store.save(store.settings.value.copy(enabled = false))

        // Only the switch was written, so a later version's default lists still apply.
        val stored = context.getSharedPreferences(name, 0)
        assertFalse(stored.contains("artist-separators"))
        assertFalse(stored.contains("artist-kept-names"))
        val restored = ArtistSplitPreferences(context, name).settings.value
        assertEquals(false, restored.enabled)
        assertEquals(DefaultArtistSeparators, restored.separators)
        assertEquals(DefaultKeptArtistNames, restored.keptNames)
    }

    @Test
    fun anEmptiedListStaysEmpty() {
        ArtistSplitPreferences(context, name).save(ArtistSplitSettings(separators = emptyList(), keptNames = emptyList()))

        val restored = ArtistSplitPreferences(context, name).settings.value
        assertEquals(emptyList<String>(), restored.separators)
        assertEquals(emptyList<String>(), restored.keptNames)
    }

    @Test
    fun restoringTheDefaultsFollowsLaterDefaultsAgain() {
        val store = ArtistSplitPreferences(context, name)
        store.save(ArtistSplitSettings(separators = listOf("/"), keptNames = listOf("AC/DC")))
        // #191 D14: "Restore defaults" stored the defaults, so a later version's defaults did not apply.
        store.save(ArtistSplitSettings(separators = DefaultArtistSeparators, keptNames = DefaultKeptArtistNames))

        val stored = context.getSharedPreferences(name, 0)
        assertFalse(stored.contains("artist-separators"))
        assertFalse(stored.contains("artist-kept-names"))
        assertEquals(ArtistSplitSettings(), ArtistSplitPreferences(context, name).settings.value)
    }
}
