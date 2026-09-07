package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStateStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferencesName = "playback-state-test-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val isolatedContext = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }

    @After
    fun removeTestPreferences() {
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun queueOccurrenceSurvivesNewStoreAndProgressOnlyCheckpoint() {
        val store = PlaybackStateStore(isolatedContext)
        store.save(
            PersistedPlaybackState(
                queueTrackIds = listOf(1, 2, 1),
                currentTrackId = 1,
                currentQueueIndex = 0,
                positionMs = 0,
                shuffleEnabled = false,
                repeatMode = PlaybackRepeatMode.OFF,
            ),
        )
        store.saveProgress(
            currentTrackId = 1,
            currentQueueIndex = 2,
            positionMs = 12_345,
            shuffleEnabled = true,
            repeatMode = PlaybackRepeatMode.ALL,
        )

        val loaded = PlaybackStateStore(isolatedContext).load()!!
        assertEquals(listOf(1L, 2L, 1L), loaded.queueTrackIds)
        assertEquals(2, loaded.currentQueueIndex)
        assertEquals(12_345, loaded.positionMs)
        assertEquals(true, loaded.shuffleEnabled)
        assertEquals(PlaybackRepeatMode.ALL, loaded.repeatMode)
    }

    @Test
    fun legacyPreferencesWithoutOccurrenceStillLoad() {
        preferences.edit()
            .putString("queue_track_ids", "1,2,1")
            .putLong("current_track_id", 1)
            .putLong("position_ms", 9_876)
            .commit()

        val loaded = PlaybackStateStore(isolatedContext).load()!!
        assertNull(loaded.currentQueueIndex)
        assertEquals(listOf(1L, 2L, 1L), loaded.queueTrackIds)
        assertEquals(9_876, loaded.positionMs)
    }
}
