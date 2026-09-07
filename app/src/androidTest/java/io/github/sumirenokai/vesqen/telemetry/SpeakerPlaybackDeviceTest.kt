package io.github.sumirenokai.vesqen.telemetry

import android.os.Debug
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.os.SystemClock
import android.util.JsonReader
import android.util.JsonToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.VesqenApplication
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticExportResult
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecordingTermination
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackController
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Physical speaker tests. Run explicitly by method; the soak takes 75 minutes.
 * The lab runner backs up user data before running and restores the playback checkpoint afterward.
 * Private generated fixtures never enter MediaStore or the user's catalog.
 */
class SpeakerPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as VesqenApplication
    private val telemetry = app.telemetryRuntime
    private val playback = AtomicReference(PlaybackSnapshot())
    private lateinit var controller: PlaybackController
    private val artifacts = File(app.filesDir, "speaker-acceptance").apply { mkdirs() }
    private val allMetrics = TelemetryMetricSelection.Explicit(TelemetryMetricCatalog.allIds)

    @Test
    fun playbackSwitchSeekPauseAndObserverRelease() = runBlocking {
        val first = speakerFixture(app, 44_100, 16)
        val second = speakerFixture(app, 48_000, 24)
        try {
            start(listOf(first, second))
            val initial = currentSnapshot(first.sampleRateHz!!)
            val selectedRoute = initial.metric(TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_TYPE)?.evidence
            val sdk = android.os.Build.VERSION.SDK_INT
            @Suppress("DEPRECATION")
            val legacyReportsSpeaker = sdk < 30 && app.getSystemService(android.media.MediaRouter::class.java)
                .getSelectedRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO).deviceType ==
                android.media.MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER
            if (sdk >= 34 || legacyReportsSpeaker) {
                assertEquals("phone_speaker", (selectedRoute?.reading as? TelemetryReading.Text)?.value)
            } else {
                // MediaRouter route type and AudioManager's anticipated route are separate evidence.
                assertTrue(selectedRoute is TelemetryEvidence.Unavailable)
                assertEquals(if (sdk >= 30) TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM
                    else TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT,
                    (selectedRoute as TelemetryEvidence.Unavailable).reason)
            }
            if (sdk < 33) {
                val anticipated = initial.metric(TelemetryMetricCatalog.ROUTE_ANTICIPATED_TYPE)?.evidence
                assertTrue(anticipated is TelemetryEvidence.Unavailable)
                assertEquals(TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION,
                    (anticipated as TelemetryEvidence.Unavailable).reason)
            }
            main { controller.skipToNext() }
            awaitPlayback { it.trackId == second.id && it.isPlaying }
            val next = currentSnapshot(second.sampleRateHz!!)
            assertNotEquals(initial.playbackSessionId, next.playbackSessionId)
            main { controller.seekTo(15_000) }
            awaitPlayback { it.positionMs >= 15_000 }
            main { controller.togglePlayback() }
            awaitPlayback { !it.isPlaying && !it.showsPauseAction }
            main { controller.togglePlayback() }
            awaitPlayback { it.isPlaying }
            main { controller.skipToPrevious() }
            awaitPlayback { it.trackId == first.id && it.isPlaying }
            currentSnapshot(first.sampleRateHz!!)

            for (interval in TelemetryRefreshInterval.entries) {
                coroutineScope {
                    val observer = launch { telemetry.observe(TelemetryObservation(
                        refreshInterval = interval, selection = allMetrics,
                    )).collect {} }
                    withTimeout(5_000) {
                        while (telemetry.debugSamplingState().activeIntervalMs != interval.milliseconds) delay(25)
                    }
                    observer.cancelAndJoin()
                }
                awaitNoObservers()
            }
            coroutineScope {
                val observer = launch { telemetry.observe(TelemetryObservation(
                    refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
                    powerMode = TelemetryPowerMode.LOW_POWER, selection = allMetrics,
                )).collect {} }
                withTimeout(5_000) {
                    while (telemetry.debugSamplingState().activeIntervalMs != 2_000L) delay(25)
                }
                observer.cancelAndJoin()
            }
            awaitNoObservers()
            repeat(100) {
                currentSnapshot(first.sampleRateHz!!)
                awaitNoObservers()
            }
            assertTrue(app.diagnosticRecorder.start())
            delay(3_100)
            val recording = requireNotNull(app.diagnosticRecorder.stop())
            assertEquals(DiagnosticRecordingTermination.USER_STOPPED, recording.termination)
            assertTrue(recording.snapshots.isNotEmpty())
            exportAndCheckPrivacy("short-recording.json")
            assertTrue(app.diagnosticRecorder.clear())
            awaitNoObservers()
            val manualSeconds = InstrumentationRegistry.getArguments().getString("m2ManualChecksSeconds")?.toLong() ?: 0L
            require(manualSeconds in 0..1_800)
            if (manualSeconds > 0) {
                // Keep private fixtures available for adb-driven visual/SAF/TalkBack checks.
                // This window's manual results are recorded separately from the assertions above.
                val finished = File(artifacts, "finish-manual")
                check(!finished.exists() || finished.delete())
                main { controller.cyclePlaybackOrderMode(); controller.cyclePlaybackOrderMode() }
                File(artifacts, "phase.txt").writeText("MANUAL")
                withTimeout(manualSeconds * 1_000) {
                    while (!finished.exists()) delay(1_000)
                }
                check(finished.delete())
            }
        } finally {
            finish()
        }
    }

    @Test
    fun samplingComparisonSoak() = runBlocking {
        val track = speakerFixture(app, 48_000, 24)
        try {
            start(listOf(track))
            // Sequential -> shuffle -> repeat all, retaining a continuous session between laps.
            main { controller.cyclePlaybackOrderMode(); controller.cyclePlaybackOrderMode() }
            currentSnapshot(track.sampleRateHz!!)
            delay(30_000) // Explicit warmup, excluded from each measurement interval.
            for (interval in listOf(null, TelemetryRefreshInterval.ONE_SECOND, TelemetryRefreshInterval.QUARTER_SECOND)) {
                val name = interval?.name ?: "OFF"
                val sampleCount = AtomicInteger()
                val audioEvents = linkedMapOf<Long, TelemetryEvent>()
                coroutineScope {
                    val observer = interval?.let {
                        launch { telemetry.observe(TelemetryObservation(refreshInterval = it, selection = allMetrics)).collect { snapshot ->
                            sampleCount.incrementAndGet()
                            snapshot.recentEvents.filter { event -> event.kind == TelemetryEventKind.UNDERRUN || event.kind == TelemetryEventKind.ERROR }
                                .forEach { event -> audioEvents[event.sequence] = event }
                        } }
                    }
                    if (interval == null) awaitNoObservers() else withTimeout(5_000) {
                        while (telemetry.debugSamplingState().activeIntervalMs != interval.milliseconds) delay(25)
                    }
                    measure(name, 15 * 60_000L)
                    observer?.cancelAndJoin()
                }
                File(artifacts, "$name-observations.json").writeText(JSONObject().apply {
                    put("sampleCount", sampleCount.get())
                    put("audioEventCount", if (interval == null) JSONObject.NULL else audioEvents.size)
                    put("note", if (interval == null) "No telemetry observer during baseline; transient audio events were not sampled" else "Events retained across playback-session changes")
                }.toString())
                File(artifacts, "$name-audio-events.txt").writeText(audioEvents.values.joinToString("\n"))
                awaitNoObservers()
                File(artifacts, "$name-end-evidence.txt").writeText(currentSnapshot(track.sampleRateHz!!).toString())
            }
        } finally {
            finish()
        }
    }

    @Test
    fun diagnosticRecordingSoak() = runBlocking {
        try {
            start(listOf(speakerFixture(app, 48_000, 24)))
            main { controller.cyclePlaybackOrderMode(); controller.cyclePlaybackOrderMode() }
            currentSnapshot(48_000)
            awaitNoObservers()
            recordForThirtyMinutes()
        } finally {
            finish()
        }
    }

    private suspend fun recordForThirtyMinutes() {
        assertTrue(app.diagnosticRecorder.start())
        measure("RECORDING", 30 * 60_000L)
        val recording = requireNotNull(app.diagnosticRecorder.stop())
        assertTrue(recording.stoppedAt.elapsedRealtimeMs - recording.startedAt.elapsedRealtimeMs >= 30 * 60_000L)
        assertEquals(DiagnosticRecordingTermination.USER_STOPPED, recording.termination)
        assertTrue(recording.snapshots.size <= recording.limits.maxSnapshots)
        assertTrue(recording.events.size <= recording.limits.maxEvents)
        exportAndCheckPrivacy("soak-recording.json")
        assertTrue(app.diagnosticRecorder.clear())
        awaitNoObservers()
    }

    private suspend fun measure(phase: String, durationMs: Long) {
        File(artifacts, "phase.txt").writeText(phase)
        val started = SystemClock.elapsedRealtime()
        File(artifacts, "$phase.jsonl").bufferedWriter().use { writer ->
            do {
                main { controller.refreshPosition() }
                val snapshot = playback.get()
                assertNull("Playback error during $phase", snapshot.problem)
                assertTrue("Playback stopped during $phase", snapshot.isPlaying)
                val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                val runtime = Runtime.getRuntime()
                val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val current = app.getSystemService(BatteryManager::class.java)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                writer.appendLine(JSONObject().apply {
                    put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    put("cpuMs", Process.getElapsedCpuTime())
                    put("pssKb", memory.totalPss)
                    put("javaHeapBytes", runtime.totalMemory() - runtime.freeMemory())
                    put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                    put("gcCount", Debug.getRuntimeStat("art.gc.gc-count"))
                    put("gcTimeMs", Debug.getRuntimeStat("art.gc.gc-time"))
                    put("positionMs", snapshot.positionMs)
                    put("observers", telemetry.debugSamplingState().activeObservationCount)
                    put("intervalMs", telemetry.debugSamplingState().activeIntervalMs ?: JSONObject.NULL)
                    put("batteryTemperatureTenthsC", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
                    put("batteryPlugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
                    put("batteryCurrentMicroamps", if (current == Int.MIN_VALUE) JSONObject.NULL else current)
                }.toString())
                writer.flush()
                delay(5_000)
            } while (SystemClock.elapsedRealtime() - started < durationMs)
        }
        File(artifacts, "$phase-duration.json").writeText(JSONObject().apply {
            put("durationMs", SystemClock.elapsedRealtime() - started)
            put("requestedDurationMs", durationMs)
        }.toString())
    }

    private suspend fun start(tracks: List<AudioTrack>) {
        main { controller = PlaybackController(app) { playback.set(it) } }
        awaitPlayback { it.isControllerReady }
        main { controller.playQueue(tracks, 0) }
        awaitPlayback { it.trackId == tracks.first().id && it.isPlaying }
    }

    private suspend fun awaitPlayback(predicate: (PlaybackSnapshot) -> Boolean) = withTimeout(15_000) {
        while (true) {
            main { controller.refreshPosition() }
            if (predicate(playback.get())) break
            delay(50)
        }
    }

    private suspend fun currentSnapshot(rate: Int): TelemetrySnapshot = withTimeout(10_000) {
        telemetry.observe(TelemetryObservation(selection = allMetrics)).first {
            it.playbackSessionId != null &&
                (it.metric(TelemetryMetricCatalog.SOURCE_SAMPLE_RATE)?.evidence?.reading as? TelemetryReading.Integer)?.value == rate.toLong()
        }
    }

    private suspend fun awaitNoObservers() = withTimeout(5_000) {
        while (telemetry.debugSamplingState().activeObservationCount != 0) delay(25)
        assertNull(telemetry.debugSamplingState().activeIntervalMs)
    }

    private suspend fun exportAndCheckPrivacy(name: String) {
        val file = File(artifacts, name)
        file.outputStream().buffered().use { output ->
            assertTrue(app.diagnosticRecorder.exportTo(output) is DiagnosticExportResult.Success)
        }
        // Keep the validator bounded as well as the product exporter; a 30-minute report
        // must not be duplicated into a String and a complete JSONObject tree on the phone.
        JsonReader(file.bufferedReader()).use { reader ->
            fun checkText(value: String) {
                for (privateText in listOf("VESQEN_QA_PRIVATE", "file://", app.cacheDir.absolutePath)) {
                    assertFalse("Private fixture data in diagnostic export", value.contains(privateText))
                }
            }
            fun value() {
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        while (reader.hasNext()) { checkText(reader.nextName()); value() }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) value()
                        reader.endArray()
                    }
                    JsonToken.STRING -> checkText(reader.nextString())
                    JsonToken.NUMBER -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> error("Unexpected diagnostic JSON token: ${reader.peek()}")
                }
            }
            assertEquals(JsonToken.BEGIN_OBJECT, reader.peek())
            value()
            assertEquals(JsonToken.END_DOCUMENT, reader.peek())
        }
    }

    private suspend fun finish() {
        app.diagnosticRecorder.stop()
        app.diagnosticRecorder.clear()
        if (::controller.isInitialized) main { controller.clearQueue(); controller.release() }
        awaitNoObservers()
        File(artifacts, "phase.txt").writeText("FINISHED")
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
}

internal fun speakerFixture(app: VesqenApplication, rate: Int, bits: Int): AudioTrack {
    val bytesPerSample = bits / 8
    val frames = rate * 60
    val dataSize = frames * 2 * bytesPerSample
    val file = File(app.cacheDir, "VESQEN_QA_PRIVATE_${rate}_$bits.wav")
    file.outputStream().buffered().use { output ->
        output.write(ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(rate)
            putInt(rate * 2 * bytesPerSample); putShort((2 * bytesPerSample).toShort())
            putShort(bits.toShort()); put("data".toByteArray()); putInt(dataSize)
        }.array())
        val frame = ByteArray(2 * bytesPerSample)
        repeat(frames) { index ->
            val value = (sin(index * 2 * Math.PI * 220 / rate) * ((1L shl (bits - 1)) - 1) * 0.005).toInt()
            repeat(2) { channel -> repeat(bytesPerSample) { byte ->
                frame[channel * bytesPerSample + byte] = (value shr (8 * byte)).toByte()
            } }
            output.write(frame)
        }
    }
    return AudioTrack(-rate.toLong(), file.toURI().toString(), "VESQEN_QA_PRIVATE", "", "", 60_000,
        fileName = file.name, fileSizeBytes = file.length(), mimeType = "audio/wav", codec = "PCM",
        sampleRateHz = rate, bitDepth = bits, channelCount = 2, bitrate = rate * 2 * bits)
}
