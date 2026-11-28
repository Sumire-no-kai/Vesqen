package io.github.sumirenokai.vesqen.playback

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.sumirenokai.vesqen.library.DSD_CODEC_LABEL
import io.github.sumirenokai.vesqen.telemetry.TelemetryMediaItemExtras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The DSD settings on the Audio output page (#249, owner 2026-11-26). A DSD stream reads them when
 * its file opens, on Media3's loading thread; the stream that is playing keeps what it opened with.
 */
internal class DsdConversionSettings(context: Context) {
    private val preferences = context.getSharedPreferences("dsd_conversion", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val values: StateFlow<DsdConversionValues> = state.asStateFlow()

    fun setGainEnabled(enabled: Boolean) = update(state.value.copy(gainEnabled = enabled))

    fun setGainDb(gainDb: Float) = update(state.value.copy(gainDb = dsdGainStep(gainDb)))

    fun setRateMode(mode: DsdOutputRateMode) = update(state.value.copy(rateMode = mode))

    /** What a DSD stream that opens now converts with. */
    fun conversion(): DsdConversion = state.value.conversion()

    private fun update(values: DsdConversionValues) {
        if (values == state.value) return
        preferences.edit {
            putBoolean(GAIN_ENABLED, values.gainEnabled)
            putFloat(GAIN_DB, values.gainDb)
            putString(RATE_MODE, values.rateMode.key)
        }
        state.value = values
    }

    private fun read() = DsdConversionValues(
        gainEnabled = preferences.getBoolean(GAIN_ENABLED, false),
        gainDb = dsdGainStep(preferences.getFloat(GAIN_DB, DSD_FIRST_GAIN_DB)),
        rateMode = preferences.getString(RATE_MODE, null)
            ?.let { key -> DsdOutputRateMode.entries.firstOrNull { it.key == key } }
            ?: DsdOutputRateMode.UNIFORM,
    )

    private companion object {
        const val GAIN_ENABLED = "gain_enabled"
        const val GAIN_DB = "gain_db"
        const val RATE_MODE = "rate_mode"
    }
}

/** The stored settings. The gain is kept while the switch is off, so turning it on again restores it. */
internal data class DsdConversionValues(
    val gainEnabled: Boolean = false,
    val gainDb: Float = DSD_FIRST_GAIN_DB,
    val rateMode: DsdOutputRateMode = DsdOutputRateMode.UNIFORM,
) {
    fun conversion() = DsdConversion(rateMode, gainDb = if (gainEnabled) gainDb else 0f)
}

internal const val DSD_MIN_GAIN_DB = 0.5f
internal const val DSD_GAIN_STEP_DB = 0.5f

/** The gain the switch first turns on with: the most that leaves recordings within the SACD level standard unclipped. */
internal const val DSD_FIRST_GAIN_DB = 3f

/** [gainDb] on the 0.5 dB grid from +0.5 to +6 dB; a value that cannot be read is the first gain. */
internal fun dsdGainStep(gainDb: Float): Float {
    if (gainDb.isNaN()) return DSD_FIRST_GAIN_DB
    return Math.round(gainDb.coerceIn(DSD_MIN_GAIN_DB, DSD_MAX_GAIN_DB) / DSD_GAIN_STEP_DB) * DSD_GAIN_STEP_DB
}

/**
 * Media3 can open several tracks ahead. Replacing the first upcoming DSD source discards its buffered
 * period and the periods queued after it. Follow playback order past any PCM tracks and leave the
 * current source in place. PlayOrder keeps the replacement in its original shuffle slot.
 *
 * Media3 only re-creates an item whose cache key changed, so each settings change brings a new
 * [generation] for the key. The key records which change re-created the item, never which settings
 * it opened with: on repeat a track opens again later with whatever applies then, so a key derived
 * from the settings could match a change the track never saw (#356 review).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ExoPlayer.reopenUpcomingDsd(generation: Long) {
    val timeline = currentTimeline
    if (timeline.isEmpty || repeatMode == Player.REPEAT_MODE_ONE) return
    val current = currentMediaItemIndex
    val key = "vesqen-dsd:$generation"
    var next = timeline.getNextWindowIndex(current, repeatMode, shuffleModeEnabled)
    while (next != C.INDEX_UNSET && next != current) {
        val item = getMediaItemAt(next)
        if (TelemetryMediaItemExtras.read(item.mediaMetadata.extras).codecLabel == DSD_CODEC_LABEL) {
            if (item.localConfiguration?.customCacheKey != key) {
                replaceMediaItem(next, item.buildUpon().setCustomCacheKey(key).build())
            }
            return
        }
        next = timeline.getNextWindowIndex(next, repeatMode, shuffleModeEnabled)
    }
}
