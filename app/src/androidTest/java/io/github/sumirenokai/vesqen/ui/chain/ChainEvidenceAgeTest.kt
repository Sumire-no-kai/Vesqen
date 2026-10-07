package io.github.sumirenokai.vesqen.ui.chain

import android.content.Context
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
                "Just now", "Just now", "Just now", "1 minute ago",
                "59 minutes ago", "2 hours ago", "1 day ago", "4 days ago",
            ),
            ageSeconds.map { format(Locale.US, it) },
        )
        assertEquals(
            listOf("刚刚", "刚刚", "刚刚", "1 分钟前", "59 分钟前", "2 小时前", "1 天前", "4 天前"),
            ageSeconds.map { format(Locale.SIMPLIFIED_CHINESE, it) },
        )
    }

    private val evidence = TelemetryEvidence.Measured(
        reading = TelemetryReading.Text("FLAC"),
        source = TelemetryDataSource(TelemetrySourceId("test.telemetry")),
        observedAtEpochMs = ObservedAtMs,
        observedAtElapsedRealtimeMs = ObservedAtMs,
    )

    private fun localized(locale: Locale): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration)
    }

    private fun format(locale: Locale, ageSeconds: Long): String =
        telemetryEvidenceAge(localized(locale), evidence, nowElapsedRealtimeMs = ObservedAtMs + ageSeconds * 1_000)

    private companion object {
        const val ObservedAtMs = 1_000L
    }
}
