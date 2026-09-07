package io.github.sumirenokai.vesqen.telemetry

import android.app.Notification
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.VesqenApplication
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticExportResult
import io.github.sumirenokai.vesqen.library.AndroidLibraryCatalog
import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import io.github.sumirenokai.vesqen.playback.PlaybackController
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in device acceptance: stage a header-verified manifest and its MediaStore audio files first.
 * The lab preserves the user's checkpoint; recordings and source names stay in private QA storage.
 */
class RealLosslessDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as VesqenApplication
    private val telemetry = app.telemetryRuntime
    private val playback = AtomicReference(PlaybackSnapshot())
    private lateinit var controller: PlaybackController
    private val artifacts = File(app.filesDir, "real-lossless-acceptance")

    @Test
    fun importedSourcesAndAdvancedEvidence() = runBlocking {
        assumeTrue("External-audio acceptance requires an explicitly staged device fixture",
            InstrumentationRegistry.getArguments().getString("realLosslessAcceptance") == "true")
        val manifest = JSONArray(File(artifacts, "audio-manifest.json").readText())
        require(manifest.length() > 0)
        File(artifacts, "phase.txt").writeText("RUNNING")
        val catalog = AndroidLibraryCatalog(app)
        try {
            catalog.refresh(includeDeviceLibrary = true, onProgress = {})
            val catalogTracks = catalog.snapshot(includeDeviceLibrary = true).tracks
            val tracks = (0 until manifest.length()).map { index ->
                val expected = manifest.getJSONObject(index)
                catalogTracks.single { it.fileName == expected.getString("file") &&
                    it.fileSizeBytes == expected.getLong("bytes") }
            }
            main { controller = PlaybackController(app) { playback.set(it) } }
            awaitPlayback { it.isControllerReady }
            var previousSession: String? = null
            tracks.forEachIndexed { index, track ->
                val expected = manifest.getJSONObject(index)
                main { controller.playQueue(tracks, index) }
                awaitPlayback { it.trackId == track.id && it.isPlaying }
                val snapshot = withTimeout(15_000) {
                    telemetry.observe(TelemetryObservation(selection = TelemetryMetricSelection.Explicit(TelemetryMetricCatalog.allIds)))
                        .first { it.playbackSessionId != null && it.playbackSessionId != previousSession &&
                            integer(it, TelemetryMetricCatalog.SOURCE_SAMPLE_RATE) == expected.getLong("sampleRate") &&
                            integer(it, TelemetryMetricCatalog.DECODER_INPUT_SAMPLE_RATE) == expected.getLong("sampleRate") &&
                            integer(it, TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE) != null }
                }
                previousSession = snapshot.playbackSessionId
                assertEquals(expected.getLong("bits"), integer(snapshot, TelemetryMetricCatalog.SOURCE_BIT_DEPTH))
                assertEquals(OutputDeclaration.SYSTEM_MIXED, playback.get().declaration)
                withTimeout(10_000) {
                    val manager = app.getSystemService(NotificationManager::class.java)
                    while (true) {
                        val notification = manager.activeNotifications.firstOrNull { it.id == 1001 }?.notification
                        val titleMatches = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == track.title
                        val coverReady = !expected.optBoolean("expectArtwork", false) || notification?.getLargeIcon() != null
                        if (titleMatches && coverReady && notification?.contentIntent != null) break
                        delay(100)
                    }
                }
                File(artifacts, "source-$index-evidence.txt").writeText(snapshot.toString())
                assertTrue(app.diagnosticRecorder.start())
                try {
                    coroutineScope {
                        val events = linkedMapOf<Long, TelemetryEvent>()
                        var count = 0
                        val observer = launch {
                            telemetry.observe(TelemetryObservation(
                                refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
                                selection = TelemetryMetricSelection.Explicit(TelemetryMetricCatalog.allIds),
                            )).collect { current ->
                                count++
                                current.recentEvents.filter { it.kind == TelemetryEventKind.ERROR || it.kind == TelemetryEventKind.UNDERRUN }
                                    .forEach { events[it.sequence] = it }
                            }
                        }
                        delay(30_000)
                        main { controller.seekTo(60_000) }
                        awaitPlayback { it.positionMs >= 60_000 && it.isPlaying }
                        main { controller.togglePlayback() }
                        awaitPlayback { !it.isPlaying && !it.showsPauseAction }
                        main { controller.togglePlayback() }
                        awaitPlayback { it.isPlaying }
                        delay(3_000)
                        observer.cancelAndJoin()
                        File(artifacts, "source-$index-result.json").writeText(JSONObject().apply {
                            put("sourceIndex", index); put("snapshotCount", count)
                            put("audioEvents", JSONArray(events.values.map { it.code }))
                        }.toString())
                        assertTrue("Nonempty high-frequency evidence is required", count > 0)
                        assertTrue("Playback error event", events.values.none { it.kind == TelemetryEventKind.ERROR })
                    }
                } finally { app.diagnosticRecorder.stop() }
                val export = File(artifacts, "source-$index-diagnostic.json")
                export.outputStream().buffered().use { assertTrue(app.diagnosticRecorder.exportTo(it) is DiagnosticExportResult.Success) }
                val json = export.readText()
                JSONObject(json)
                listOf(track.title, track.artist, track.album, track.fileName, track.contentUri)
                    .filter { it.length >= 4 }.forEach { assertFalse("Identifying source text in diagnostic", json.contains(it)) }
                assertTrue(app.diagnosticRecorder.clear())
                awaitNoObservers()
            }
            val seconds = InstrumentationRegistry.getArguments().getString("manualSeconds")?.toLong() ?: 0
            require(seconds in 0..1_800)
            if (seconds > 0) {
                val finished = File(artifacts, "finish-manual")
                check(!finished.exists() || finished.delete())
                main { controller.cyclePlaybackOrderMode(); controller.cyclePlaybackOrderMode() }
                File(artifacts, "phase.txt").writeText("MANUAL")
                withTimeout(seconds * 1_000) { while (!finished.exists()) delay(500) }
            }
        } finally {
            app.diagnosticRecorder.stop(); app.diagnosticRecorder.clear()
            if (::controller.isInitialized) main { controller.clearQueue(); controller.release() }
            catalog.close()
            awaitNoObservers()
        }
    }

    private fun integer(snapshot: TelemetrySnapshot, id: TelemetryMetricId): Long? =
        (snapshot.metric(id)?.evidence?.reading as? TelemetryReading.Integer)?.value

    private suspend fun awaitPlayback(predicate: (PlaybackSnapshot) -> Boolean) = withTimeout(15_000) {
        while (true) {
            main { controller.refreshPosition() }
            if (predicate(playback.get())) break
            delay(50)
        }
    }

    private suspend fun awaitNoObservers() = withTimeout(5_000) {
        while (telemetry.debugSamplingState().activeObservationCount != 0) delay(25)
        assertNull(telemetry.debugSamplingState().activeIntervalMs)
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
