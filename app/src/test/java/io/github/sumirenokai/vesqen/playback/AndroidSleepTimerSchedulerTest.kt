package io.github.sumirenokai.vesqen.playback

import android.app.AlarmManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 32, 35])
class AndroidSleepTimerSchedulerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val main = Looper.getMainLooper()
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val scheduler = AndroidSleepTimerScheduler(context, Handler(main))

    @Test fun elapsedAlarmUsesThePlatformAppropriatePermissionFreePolicy() {
        val at = SystemClock.elapsedRealtime() + 1_000
        val cancel = scheduler.schedule(at) {}
        try {
            val alarm = alarms.scheduledAlarms.single()
            assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
            assertEquals(at, alarm.triggerAtMs)
            assertFalse(alarm.isAllowWhileIdle)
            if (Build.VERSION.SDK_INT in 31..33) assertTrue(alarm.windowLengthMs != 0L)
            else assertEquals(0L, alarm.windowLengthMs)
        } finally {
            cancel()
        }
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun cancellationRemovesBothDeliveryRoutesAndRejectsAnAlreadyQueuedListener() {
        var callbacks = 0
        val cancel = scheduler.schedule(SystemClock.elapsedRealtime() + 1_000) { callbacks++ }
        val alarm = alarms.scheduledAlarms.single()
        cancel()
        shadowOf(main).idleFor(Duration.ofSeconds(2))
        requireNotNull(alarm.onAlarmListener).onAlarm()
        assertEquals(0, callbacks)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun handlerHonorsAShortDeadlineOnEveryVersionAndRejectsTheLaterAlarm() {
        var callbacks = 0
        val cancel = scheduler.schedule(SystemClock.elapsedRealtime() + 100) { callbacks++ }
        try {
            val alarm = alarms.scheduledAlarms.single()
            shadowOf(main).idleFor(Duration.ofMillis(99))
            assertEquals(0, callbacks)
            shadowOf(main).idleFor(Duration.ofMillis(1))
            assertEquals(1, callbacks)
            requireNotNull(alarm.onAlarmListener).onAlarm()
            requireNotNull(alarm.onAlarmListener).onAlarm()
            assertEquals(1, callbacks)
            assertTrue(alarms.scheduledAlarms.isEmpty())
        } finally {
            cancel()
        }
    }

    @Test fun alarmDeliveryRemovesThePendingHandlerDeadline() {
        var callbacks = 0
        val cancel = scheduler.schedule(SystemClock.elapsedRealtime() + 1_000) { callbacks++ }
        try {
            requireNotNull(alarms.scheduledAlarms.single().onAlarmListener).onAlarm()
            assertEquals(1, callbacks)
            shadowOf(main).idleFor(Duration.ofSeconds(2))
            assertEquals(1, callbacks)
            assertTrue(alarms.scheduledAlarms.isEmpty())
        } finally {
            cancel()
        }
    }
}
