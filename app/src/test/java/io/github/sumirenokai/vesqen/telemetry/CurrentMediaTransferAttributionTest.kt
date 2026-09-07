package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentMediaTransferAttributionTest {
    @Test
    fun `adding duplicate without transition invalidates provenance until the next occurrence`() {
        val attribution = CurrentMediaTransferAttribution<Any>()
        val current = Any()
        val duplicate = Any()
        attribution.onMediaSessionChanged("a", "content://music/current", true)
        attribution.onTransferStart(current, "content://music/current")
        attribution.onBytesTransferred(current, "content://music/current", 100)
        attribution.onTimelineChanged { true }
        assertEquals(100L, attribution.snapshot().bytesRead)

        attribution.onTimelineChanged { false }
        attribution.onTransferStart(duplicate, "content://music/current")
        attribution.onBytesTransferred(duplicate, "content://music/current", 900)
        attribution.onBytesTransferred(current, "content://music/current", 200)
        assertNull(attribution.snapshot().bytesRead)

        attribution.onTimelineChanged { true }
        assertEquals(TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE, attribution.snapshot().unavailableReason)
        attribution.onMediaSessionChanged("b", "content://music/current", true)
        attribution.onBytesTransferred(duplicate, "content://music/current", 900)
        assertNull(attribution.snapshot().bytesRead)
        val fresh = Any()
        attribution.onTransferStart(fresh, "content://music/current")
        attribution.onBytesTransferred(fresh, "content://music/current", 300)
        assertEquals(300L, attribution.snapshot().bytesRead)
    }

    @Test
    fun `a unique current URI promotes its existing load and ignores another URI prefetch`() {
        val attribution = CurrentMediaTransferAttribution<Any>()
        val current = Any()
        val prefetch = Any()
        attribution.onTransferStart(current, "content://music/current")
        attribution.onTransferStart(prefetch, "content://music/next")

        attribution.onMediaSessionChanged(
            playbackSessionId = "session-current",
            mediaUri = "content://music/current",
            mediaUriIsUnique = true,
        )
        attribution.onBytesTransferred(prefetch, "content://music/next", 4_096)
        attribution.onBytesTransferred(current, "content://music/current", 1_024)

        assertEquals(
            CurrentMediaReadSnapshot("session-current", 1_024, unavailableReason = null),
            attribution.snapshot(),
        )
    }

    @Test
    fun `an old transfer with the same URI is not attributed across playback sessions`() {
        val attribution = CurrentMediaTransferAttribution<Any>()
        val oldSource = Any()
        attribution.onMediaSessionChanged("session-a", "content://music/repeat", true)
        attribution.onTransferStart(oldSource, "content://music/repeat")
        attribution.onBytesTransferred(oldSource, "content://music/repeat", 100)

        attribution.onMediaSessionChanged("session-b", "content://music/repeat", true)
        attribution.onBytesTransferred(oldSource, "content://music/repeat", 900)

        assertEquals(TelemetryUnavailableReason.WARMING_UP, attribution.snapshot().unavailableReason)
        assertNull(attribution.snapshot().bytesRead)

        val newSource = Any()
        attribution.onTransferStart(newSource, "content://music/repeat")
        attribution.onBytesTransferred(newSource, "content://music/repeat", 200)
        assertEquals(
            CurrentMediaReadSnapshot("session-b", 200, unavailableReason = null),
            attribution.snapshot(),
        )
    }

    @Test
    fun `a seek starts a new source in the same session without resetting attributed bytes`() {
        val attribution = CurrentMediaTransferAttribution<Any>()
        val initialSource = Any()
        attribution.onMediaSessionChanged("session", "content://music/song", true)
        attribution.onTransferStart(initialSource, "content://music/song")
        attribution.onBytesTransferred(initialSource, "content://music/song", 100)
        attribution.onTransferEnd(initialSource)

        val seekSource = Any()
        attribution.onTransferStart(seekSource, "content://music/song")
        attribution.onBytesTransferred(seekSource, "content://music/song", 250)

        assertEquals(350L, attribution.snapshot().bytesRead)
    }

    @Test
    fun `missing or duplicate current URIs remain unavailable`() {
        val attribution = CurrentMediaTransferAttribution<Any>()
        attribution.onMediaSessionChanged("missing", mediaUri = null, mediaUriIsUnique = false)
        assertEquals(TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT, attribution.snapshot().unavailableReason)

        attribution.onMediaSessionChanged("duplicate", "content://music/repeated", false)
        val duplicateSource = Any()
        attribution.onTransferStart(duplicateSource, "content://music/repeated")
        attribution.onBytesTransferred(duplicateSource, "content://music/repeated", 1_000)
        assertEquals(
            TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
            attribution.snapshot().unavailableReason,
        )
        assertNull(attribution.snapshot().bytesRead)
    }

    @Test
    fun `a URI mismatch on a bound source invalidates the current attribution`() {
        val attribution = CurrentMediaTransferAttribution<Any>()
        val source = Any()
        attribution.onMediaSessionChanged("session", "content://music/current", true)
        attribution.onTransferStart(source, "content://music/current")
        attribution.onBytesTransferred(source, "content://music/redirected", 100)

        assertEquals(
            TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
            attribution.snapshot().unavailableReason,
        )
    }

    @Test
    fun `active transfer tracking stays bounded and fails closed if current evidence is evicted`() {
        val attribution = CurrentMediaTransferAttribution<Any>(maximumActiveTransfers = 1)
        val current = Any()
        attribution.onMediaSessionChanged("session", "content://music/current", true)
        attribution.onTransferStart(current, "content://music/current")

        attribution.onTransferStart(Any(), "content://music/prefetch")

        assertEquals(
            TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
            attribution.snapshot().unavailableReason,
        )
    }
}
