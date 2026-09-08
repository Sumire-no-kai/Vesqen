package io.github.sumirenokai.vesqen.ui.chain

import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainMetricLabelsTest {
    @Test
    fun `every catalog metric has a localized UI label`() {
        val missing = TelemetryMetricCatalog.descriptors
            .map { it.id.value }
            .filter { telemetryMetricLabelResource(it) == null }

        assertTrue("Missing Chain metric labels: $missing", missing.isEmpty())
    }

    @Test
    fun `estimated production methods retain their specific traceability labels`() {
        assertEquals(
            R.string.chain_method_pcm_data_rate,
            telemetryEstimatedMethodLabelResource("audio_track.request_format_pcm_data_rate"),
        )
        assertEquals(
            R.string.chain_method_rate_conversion,
            telemetryEstimatedMethodLabelResource(
                "processing.compare_decoder_input_and_audio_track_rates",
            ),
        )
        val derivedMethods = setOf(
            "rate.data_source_bytes_per_window",
            "rate.current_media_bytes_per_window",
            "rate.decoder_input_buffers_per_window",
            "rate.decoder_output_buffers_per_window",
            "rate.process_cpu_one_core",
        )
        val estimatedMethods = setOf(
            "decoder.path_from_public_runtime_facts",
            "power.whole_device_current_times_voltage",
            "media3.position_from_last_event",
            "audio_track.request_format_pcm_data_rate",
            "processing.compare_decoder_input_and_audio_track_rates",
        )

        assertTrue(derivedMethods.all { telemetryDerivedMethodLabelResource(it) != null })
        assertTrue(estimatedMethods.all { telemetryEstimatedMethodLabelResource(it) != null })
    }

    @Test
    fun `every production telemetry source has a specific label`() {
        val productionSourceIds = setOf(
            "library.metadata",
            "media3.analytics",
            "media3.decoder_counters",
            "media3.player",
            "media3.data_source",
            "media3.current_media_data_source",
            "media3.audio_track",
            "android.media_codec",
            "android.process",
            "android.power",
            "android.build",
            "android.battery",
            "android.audio_route",
            "android.runtime",
            "android.system_media_route",
            "android.direct_playback_support",
            "android.mixer_attributes",
            "android.usb_public_api",
            "vesqen.configuration",
            "vesqen.output_coordinator",
        )

        val missing = productionSourceIds.filter { telemetrySourceLabelResource(it) == null }

        assertTrue("Missing Chain source labels: $missing", missing.isEmpty())
    }
}
