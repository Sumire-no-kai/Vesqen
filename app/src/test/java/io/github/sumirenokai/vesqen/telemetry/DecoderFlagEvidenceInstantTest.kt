package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderFlagEvidenceInstantTest {
    @Test
    fun `codec flags use asynchronous query completion time`() {
        val queryCompletedAt = TelemetryInstant(epochMs = 2_000, elapsedRealtimeMs = 200)
        val snapshotAt = TelemetryInstant(epochMs = 3_000, elapsedRealtimeMs = 300)

        assertEquals(
            queryCompletedAt,
            decoderFlagEvidenceInstant(
                hasActivePlayback = true,
                flagsObservedAt = queryCompletedAt,
                snapshotInstant = snapshotAt,
            ),
        )
    }

    @Test
    fun `unresolved or inactive codec flags use current snapshot time`() {
        val oldQuery = TelemetryInstant(epochMs = 1_000, elapsedRealtimeMs = 100)
        val snapshotAt = TelemetryInstant(epochMs = 3_000, elapsedRealtimeMs = 300)

        assertEquals(
            snapshotAt,
            decoderFlagEvidenceInstant(
                hasActivePlayback = true,
                flagsObservedAt = null,
                snapshotInstant = snapshotAt,
            ),
        )
        assertEquals(
            snapshotAt,
            decoderFlagEvidenceInstant(
                hasActivePlayback = false,
                flagsObservedAt = oldQuery,
                snapshotInstant = snapshotAt,
            ),
        )
    }
}
