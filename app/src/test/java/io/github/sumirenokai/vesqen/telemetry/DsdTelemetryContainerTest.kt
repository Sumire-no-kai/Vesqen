package io.github.sumirenokai.vesqen.telemetry

import io.github.sumirenokai.vesqen.library.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #249: Chain and the device report name a DSD file's container, as they do for PCM files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DsdTelemetryContainerTest {
    @Test
    fun `DSF and DSDIFF files carry their container into the telemetry facts`() {
        mapOf("Track.dsf" to "dsf", "Track.DFF" to "dsdiff", "Track.flac" to "flac").forEach { (fileName, container) ->
            assertEquals(fileName, container, TelemetryMediaItemExtras.read(TelemetryMediaItemExtras.from(track(fileName))).container)
        }
    }

    private fun track(fileName: String) = AudioTrack(
        id = 1,
        contentUri = "content://media/1",
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 1_000,
        fileName = fileName,
    )
}
