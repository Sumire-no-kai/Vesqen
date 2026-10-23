package io.github.sumirenokai.vesqen.ui.chain

import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import io.github.sumirenokai.vesqen.playback.telemetryLabel
import io.github.sumirenokai.vesqen.reports.DeviceReportVocabulary
import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceId
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import io.github.sumirenokai.vesqen.ui.components.outputDeclarationLabelResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `every token a producer emits has a label`() {
        val tokens = DeviceReportVocabulary.routeTypes + DeviceReportVocabulary.playbackStates +
            DeviceReportVocabulary.directModes + DecoderPaths
        val missing = tokens.filter { telemetryTextLabelResource(it) == null }

        assertTrue("Telemetry tokens shown untranslated: $missing", missing.isEmpty())
    }

    @Test
    fun `a name that reads like a token is shown as it is`() {
        assertNull(telemetryTextLabelResource("Dock"))
        assertNull(telemetryTextLabelResource("c2.android.flac.decoder"))
    }

    @Test
    fun `the output declaration card writes each declaration as its chip does`() {
        OutputDeclaration.entries.forEach { declaration ->
            assertEquals(outputDeclarationLabelResource(declaration), telemetryTextLabelResource(declaration.telemetryLabel))
        }
        assertEquals(R.string.bit_perfect_failed, telemetryTextLabelResource("BIT PERFECT FAILED"))
    }

    @Test
    fun `only facts from a signed verification record drop the not verified suffix`() {
        fun measured(sourceId: String) = TelemetryEvidence.Measured(
            TelemetryReading.Text("BIT PERFECT VERIFIED"),
            TelemetryDataSource(TelemetrySourceId(sourceId)),
            observedAtEpochMs = 1,
        )

        assertEquals(
            R.string.chain_confidence_signed_record,
            telemetryConfidenceLabelResource(measured("vesqen.external_output_verification")),
        )
        assertEquals(R.string.chain_confidence_measured, telemetryConfidenceLabelResource(measured("vesqen.output_coordinator")))
        assertEquals(
            R.string.unavailable,
            telemetryConfidenceLabelResource(
                TelemetryEvidence.Unavailable(
                    TelemetryUnavailableReason.NOT_APPLICABLE,
                    observedAtEpochMs = 1,
                    source = TelemetryDataSource(TelemetrySourceId("vesqen.external_output_verification")),
                ),
            ),
        )
    }

    private companion object {
        /**
         * The decoder.path values of AndroidPlaybackTelemetry, which has no shared vocabulary for
         * them; pcm_bypass is a WAV or AIFF file played without a codec.
         */
        val DecoderPaths = setOf("offload", "software", "platform_hardware", "platform_unspecified", "pcm_bypass")
    }
}
