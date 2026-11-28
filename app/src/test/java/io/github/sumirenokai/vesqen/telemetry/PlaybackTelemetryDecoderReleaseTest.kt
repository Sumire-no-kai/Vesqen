package io.github.sumirenokai.vesqen.telemetry

import android.app.Application
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackTelemetryDecoderReleaseTest {
    private val telemetry = AndroidPlaybackTelemetry(RuntimeEnvironment.getApplication())
    private val first = MediaSource.MediaPeriodId("first", 1)
    private val next = MediaSource.MediaPeriodId("next", 2)

    @Test
    fun `PCM facts survive an old decoder release before its bypass output is observed`() = runBlocking {
        val firstEvent = event(first, first)
        val raw = inputFormat(MimeTypes.AUDIO_RAW)
        telemetry.onMediaItemTransition(firstEvent, MediaItem.fromUri("file:///test/first.flac"), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        telemetry.onAudioDecoderInitialized(firstEvent, DECODER, 0, 0)
        telemetry.onAudioInputFormatChanged(firstEvent, inputFormat(MimeTypes.AUDIO_FLAC), null)
        telemetry.onAudioInputFormatChanged(event(next, first), raw, null)
        telemetry.onMediaItemTransition(event(next, next), MediaItem.fromUri("file:///test/next.wav"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        val inputBeforeRelease = snapshot().metric(TelemetryMetricCatalog.DECODER_INPUT_SAMPLE_RATE)?.evidence
        assertTrue(inputBeforeRelease is TelemetryEvidence.Measured)
        assertEquals(TelemetryReading.Integer(44_100L, TelemetryUnit.HERTZ), inputBeforeRelease?.reading)

        // Media3 can stamp a retiring codec with the new reading period.
        telemetry.onAudioDecoderReleased(event(next, next), DECODER)
        telemetry.onDecoderOutputFormat(raw, DecoderOutputFormat(C.ENCODING_PCM_16BIT, 44_100, fromCodec = false))

        val afterRelease = snapshot()
        assertEquals(inputBeforeRelease, afterRelease.metric(TelemetryMetricCatalog.DECODER_INPUT_SAMPLE_RATE)?.evidence)
        assertEquals(TelemetryReading.Text("pcm_bypass"), afterRelease.metric(TelemetryMetricCatalog.DECODER_PATH)?.evidence?.reading)
        assertEquals(TelemetryReading.Integer(44_100L, TelemetryUnit.HERTZ), afterRelease.metric(TelemetryMetricCatalog.DECODER_OUTPUT_SAMPLE_RATE)?.evidence?.reading)
    }

    @Test
    fun `releasing a codec clears its own input and output facts even for raw input`() = runBlocking {
        val firstEvent = event(first, first)
        val raw = inputFormat(MimeTypes.AUDIO_RAW)
        telemetry.onMediaItemTransition(firstEvent, MediaItem.fromUri("file:///test/first.wav"), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        telemetry.onAudioDecoderInitialized(firstEvent, DECODER, 0, 0)
        telemetry.onAudioInputFormatChanged(firstEvent, raw, null)
        telemetry.onDecoderOutputFormat(raw, DecoderOutputFormat(C.ENCODING_PCM_16BIT, 44_100, fromCodec = true))
        assertEquals(TelemetryReading.Integer(44_100L, TelemetryUnit.HERTZ), snapshot().metric(TelemetryMetricCatalog.DECODER_INPUT_SAMPLE_RATE)?.evidence?.reading)

        telemetry.onAudioDecoderReleased(firstEvent, DECODER)

        val afterRelease = snapshot()
        assertTrue(afterRelease.metric(TelemetryMetricCatalog.DECODER_INPUT_SAMPLE_RATE)?.evidence is TelemetryEvidence.Unavailable)
        assertTrue(afterRelease.metric(TelemetryMetricCatalog.DECODER_OUTPUT_SAMPLE_RATE)?.evidence is TelemetryEvidence.Unavailable)
    }

    private suspend fun snapshot(): TelemetrySnapshot = withTimeout(5_000) {
        telemetry.observe(TelemetryObservation(selection = TelemetryMetricSelection.Explicit(setOf(
            TelemetryMetricCatalog.DECODER_INPUT_SAMPLE_RATE,
            TelemetryMetricCatalog.DECODER_OUTPUT_SAMPLE_RATE,
            TelemetryMetricCatalog.DECODER_PATH,
        )))).first()
    }

    private fun inputFormat(mime: String) = Format.Builder().setSampleMimeType(mime)
        .setSampleRate(44_100).setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_16BIT).build()

    private fun event(reading: MediaSource.MediaPeriodId, playing: MediaSource.MediaPeriodId) =
        AnalyticsListener.EventTime(SystemClock.elapsedRealtime(), Timeline.EMPTY, 0, reading, 0, Timeline.EMPTY, 0, playing, 0, 0)

    private companion object {
        const val DECODER = "test.audio.decoder"
    }
}
