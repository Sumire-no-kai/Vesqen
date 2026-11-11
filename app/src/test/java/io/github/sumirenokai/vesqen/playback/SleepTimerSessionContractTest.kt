package io.github.sumirenokai.vesqen.playback

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SleepTimerSessionContractTest {
    @Test fun validRequestsRoundTrip() {
        val requests = listOf(
            SleepTimerRequest.Cancel,
            SleepTimerRequest.Countdown(60_000, true),
            SleepTimerRequest.Countdown(5_000, false),
            SleepTimerRequest.EndOfTrack("queue-occurrence"),
        )
        requests.forEach { assertEquals(it, SleepTimerSessionContract.readRequest(SleepTimerSessionContract.arguments(it))) }
    }

    @Test fun malformedRequestsAreRejectedInsteadOfUsingDefaultValues() {
        assertNull(SleepTimerSessionContract.readRequest(Bundle()))
        assertNull(SleepTimerSessionContract.readRequest(Bundle().apply { putString("mode", "not-a-mode") }))
        for (duration in listOf(0L, -1L)) {
            assertNull(SleepTimerSessionContract.readRequest(
                SleepTimerSessionContract.arguments(SleepTimerRequest.Countdown(duration, false)),
            ))
        }
        val valid = SleepTimerSessionContract.arguments(SleepTimerRequest.Countdown(5_000, true))
        assertNull(SleepTimerSessionContract.readRequest(Bundle(valid).apply { remove("duration_ms") }))
        assertNull(SleepTimerSessionContract.readRequest(Bundle(valid).apply { remove("finish_current_track") }))
        assertNull(SleepTimerSessionContract.readRequest(Bundle(valid).apply { putString("duration_ms", "5000") }))
        assertNull(SleepTimerSessionContract.readRequest(Bundle(valid).apply { putInt("finish_current_track", 1) }))
        assertNull(SleepTimerSessionContract.readRequest(
            SleepTimerSessionContract.arguments(SleepTimerRequest.EndOfTrack(" ")),
        ))
    }

    @Test fun stateRoundTripsWithoutOverwritingUsbOrUnrelatedSessionExtras() {
        val output = UsbOutputStatus()
        val original = UsbOutputSessionContract.toBundle(output).apply { putString("unrelated", "kept") }
        val state = SleepTimerState(SleepTimerMode.COUNTDOWN, 123_456, true, waitingForTrackEnd = true, revision = 3)
        val combined = SleepTimerSessionContract.withState(original, state)
        assertEquals(state, SleepTimerSessionContract.fromExtras(combined))
        assertEquals(output, UsbOutputSessionContract.fromBundle(combined))
        assertEquals("kept", combined.getString("unrelated"))
        assertNull(SleepTimerSessionContract.fromExtras(original))
        assertEquals(SleepTimerState(), SleepTimerSessionContract.fromExtras(
            SleepTimerSessionContract.withState(combined, SleepTimerState()),
        ))
    }

    @Test fun invalidSerializedStatesAreNotPresentedAsLiveTimers() {
        assertNull(SleepTimerSessionContract.fromExtras(Bundle()))
        for (state in listOf(
            SleepTimerState(SleepTimerMode.COUNTDOWN),
            SleepTimerState(SleepTimerMode.COUNTDOWN, -1),
            SleepTimerState(SleepTimerMode.COUNTDOWN, 10, false, waitingForTrackEnd = true),
            SleepTimerState(waitingForTrackEnd = true),
            SleepTimerState(revision = -1),
        )) {
            assertNull(SleepTimerSessionContract.fromExtras(SleepTimerSessionContract.withState(Bundle.EMPTY, state)))
        }
    }

    @Test fun clearedUsbFailureAndDeviceFieldsDoNotSurviveTimerExtrasMerging() {
        val failed = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.FAILED,
            failure = UsbOutputFailure.DEVICE_DISCONNECTED,
            deviceName = "Previous DAC",
            sourceFormat = AudioFormatSummary(96_000, 2, "PCM_24BIT"),
            directDetails = DirectUsbDetails(sourceCodec = "FLAC"),
        )
        val timer = SleepTimerState(SleepTimerMode.COUNTDOWN, 10_000, revision = 2)
        val old = SleepTimerSessionContract.withState(UsbOutputSessionContract.toBundle(failed), timer)
            .apply { putString("unrelated", "kept") }
        val clean = UsbOutputStatus(generation = 3)
        val replaced = UsbOutputSessionContract.withStatus(old, clean)
        assertEquals(clean, UsbOutputSessionContract.fromBundle(replaced))
        assertEquals(timer, SleepTimerSessionContract.fromExtras(replaced))
        assertEquals("kept", replaced.getString("unrelated"))
        assertEquals(failed, UsbOutputSessionContract.fromBundle(old))
    }

    @Test fun disconnectRemovesTimerStateAndCapability() {
        val original = PlaybackSnapshot(
            isControllerReady = true,
            canSetSleepTimer = true,
            sleepTimer = SleepTimerState(SleepTimerMode.COUNTDOWN, 10_000),
        )
        val disconnected = original.withDisconnectedController(123, 456)
        assertEquals(false, disconnected.canSetSleepTimer)
        assertEquals(SleepTimerState(), disconnected.sleepTimer)
        assertThrows(IllegalArgumentException::class.java) { PlaybackSnapshot(canSetSleepTimer = true) }
    }
}
