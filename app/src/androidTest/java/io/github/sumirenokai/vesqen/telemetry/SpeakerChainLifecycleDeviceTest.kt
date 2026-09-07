package io.github.sumirenokai.vesqen.telemetry

import android.content.res.Configuration
import android.os.Debug
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.sumirenokai.vesqen.MainActivity
import io.github.sumirenokai.vesqen.VesqenApplication
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticExportResult
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecordingTermination
import io.github.sumirenokai.vesqen.playback.PlaybackController
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI synchronization belongs here; the wall-clock performance soak must not use a Compose test clock. */
class SpeakerChainLifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as VesqenApplication
    private val telemetry = app.telemetryRuntime
    private lateinit var controller: PlaybackController

    @Test
    fun chainNavigationAndRecordingLifecycle() = runBlocking {
        val playback = AtomicReference(PlaybackSnapshot())
        val artifacts = File(app.filesDir, "speaker-acceptance").apply { mkdirs() }
        try {
            main { controller = PlaybackController(app) { playback.set(it) } }
            compose.waitUntil(15_000) { playback.get().isControllerReady }
            val track = speakerFixture(app, 48_000, 24)
            main { controller.playQueue(listOf(track), 0) }
            compose.waitUntil(15_000) { playback.get().trackId == track.id && playback.get().isPlaying }
            main { controller.cyclePlaybackOrderMode(); controller.cyclePlaybackOrderMode() }
            compose.onNodeWithTag("vesqen.nav.settings").performClick()
            var initialNavigationVisible = false
            compose.waitUntil(5_000) { resumedActivityMatches { activity ->
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                initialNavigationVisible = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars()) == true
                insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) == true
            } }
            File(artifacts, "system-bars-before.json").writeText(JSONObject().apply {
                put("navigationVisible", initialNavigationVisible)
            }.toString())
            File(artifacts, "chain-visits.jsonl").bufferedWriter().use { writer ->
                repeat(100) { index ->
                    compose.onNodeWithTag("vesqen.settings.playback-chain").performClick()
                    compose.waitUntil(5_000) { telemetry.debugSamplingState().activeObservationCount > 0 }
                    compose.onNodeWithTag("vesqen.chain.back").performClick()
                    compose.waitUntil(5_000) { telemetry.debugSamplingState().activeObservationCount == 0 }
                    assertNull(telemetry.debugSamplingState().activeIntervalMs)
                    writer.appendLine(JSONObject().apply {
                        put("completedVisits", index + 1)
                        put("observersAfterExit", telemetry.debugSamplingState().activeObservationCount)
                        put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                        val runtime = Runtime.getRuntime()
                        put("javaHeapBytes", runtime.totalMemory() - runtime.freeMemory())
                        put("pssKb", if (index % 10 == 0 || index == 99)
                            Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss else JSONObject.NULL)
                    }.toString())
                    writer.flush()
                }
            }
            captureBroadcasts(File(artifacts, "ui-observers-released-broadcasts.txt"))
            assertTrue(app.diagnosticRecorder.start())
            compose.waitUntil(5_000) { telemetry.debugSamplingState().activeObservationCount == 1 }
            captureBroadcasts(File(artifacts, "ui-recording-active-broadcasts.txt"))
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            delay(2_000)
            assertEquals(1, telemetry.debugSamplingState().activeObservationCount)
            // Bring the existing test activity back as a user would from Recents. A
            // background self-launch may be blocked; a new launcher task loses the scenario.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "am start -W --activity-reorder-to-front --activity-single-top " +
                        "-n ${app.packageName}/.MainActivity",
                ),
            ).bufferedReader().use { reader ->
                val result = reader.readText()
                File(artifacts, "ui-return-to-foreground.txt").writeText(result)
                assertTrue("Return to existing activity must complete", result.contains("Status: ok"))
            }
            compose.waitForIdle()
            compose.onNodeWithTag("vesqen.nav.now").performClick()
            // Use the product's explicit control; Library and Chain intentionally stay portrait.
            compose.onNodeWithTag("vesqen.now.orientation-toggle").performClick()
            compose.waitUntil(5_000) { resumedActivityMatches { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE } }
            compose.onNodeWithTag("vesqen.now.orientation-toggle").performClick()
            compose.waitUntil(5_000) { resumedActivityMatches { it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT } }
            compose.onNodeWithTag("vesqen.now.back").performClick()
            compose.waitUntil(5_000) { resumedActivityMatches { activity ->
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) == true &&
                    insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars()) == initialNavigationVisible &&
                    // Hardware/gesture navigation can have zero insets on old Android. Restore
                    // its initial state and also verify that the app cleared its immersive flags.
                    activity.window.decorView.systemUiVisibility and
                        (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0
            } }
            assertEquals(1, telemetry.debugSamplingState().activeObservationCount)
            val recording = requireNotNull(app.diagnosticRecorder.stop())
            assertEquals(DiagnosticRecordingTermination.USER_STOPPED, recording.termination)
            compose.waitUntil(5_000) { telemetry.debugSamplingState().activeObservationCount == 0 }
            captureBroadcasts(File(artifacts, "ui-recording-stopped-broadcasts.txt"))
            val bytes = ByteArrayOutputStream()
            assertTrue(app.diagnosticRecorder.exportTo(bytes) is DiagnosticExportResult.Success)
            val json = bytes.toString(Charsets.UTF_8.name())
            JSONObject(json)
            assertFalse(json.contains("VESQEN_QA_PRIVATE"))
            assertFalse(json.contains(app.cacheDir.absolutePath))
            File(artifacts, "navigation-recording.json").writeText(json)
        } finally {
            app.diagnosticRecorder.stop()
            app.diagnosticRecorder.clear()
            if (::controller.isInitialized) main { controller.clearQueue(); controller.release() }
            compose.waitUntil(5_000) { telemetry.debugSamplingState().activeObservationCount == 0 }
            assertNull(telemetry.debugSamplingState().activeIntervalMs)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    // Orientation can destroy the old Activity before its replacement resumes. Inspect the
    // current lifecycle owner instead of throwing from ActivityScenario.activity in that gap.
    private fun resumedActivityMatches(predicate: (MainActivity) -> Boolean): Boolean {
        var matches = false
        main {
            matches = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull()?.let(predicate) == true
        }
        return matches
    }

    private fun captureBroadcasts(file: File) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("dumpsys activity broadcasts"),
        ).bufferedReader().use { file.writeText(it.readText()) }
    }
}
