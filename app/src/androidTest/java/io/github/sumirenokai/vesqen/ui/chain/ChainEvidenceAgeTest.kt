package io.github.sumirenokai.vesqen.ui.chain

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Resolves the real plurals, which the JVM tests can only check for presence. */
class ChainEvidenceAgeTest {
    @Test
    fun evidenceAgeReadsInTheLargestUnitInEnglishAndChinese() {
        // 399368 s is the age the 2026-09-24 device review showed in seconds.
        val ageSeconds = listOf(0L, 1L, 59L, 60L, 3_599L, 7_200L, 86_400L, 399_368L)

        assertEquals(
            listOf(
                "Now", "1 second ago", "59 seconds ago", "1 minute ago",
                "59 minutes ago", "2 hours ago", "1 day ago", "4 days ago",
            ),
            ageSeconds.map { format(Locale.US, it) },
        )
        assertEquals(
            listOf("刚刚", "1 秒前", "59 秒前", "1 分钟前", "59 分钟前", "2 小时前", "1 天前", "4 天前"),
            ageSeconds.map { format(Locale.SIMPLIFIED_CHINESE, it) },
        )
    }

    private fun format(locale: Locale, ageSeconds: Long): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val observedAtMs = 1_000L
        val evidence = TelemetryEvidence.Measured(
            reading = TelemetryReading.Text("FLAC"),
            source = TelemetryDataSource(TelemetrySourceId("test.telemetry")),
            observedAtEpochMs = observedAtMs,
            observedAtElapsedRealtimeMs = observedAtMs,
        )
        return telemetryEvidenceAge(
            context.createConfigurationContext(configuration),
            evidence,
            nowElapsedRealtimeMs = observedAtMs + ageSeconds * 1_000,
        )
    }
}
