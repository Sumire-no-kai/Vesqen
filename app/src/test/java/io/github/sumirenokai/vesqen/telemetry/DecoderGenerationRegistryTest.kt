package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderGenerationRegistryTest {
    @Test
    fun `gapless preload promotion binds promoted generation through transition and release`() {
        val registry = DecoderGenerationRegistry<String>()

        registry.onDecoderInitialized(
            periodId = "current-period",
            generation = 1,
            decoderName = "shared-codec",
            belongsToActivePeriod = true,
        )
        registry.onDecoderInitialized(
            periodId = "preloaded-period",
            generation = 2,
            decoderName = "shared-codec",
            belongsToActivePeriod = false,
        )

        registry.onMediaItemTransition(
            periodId = "preloaded-period",
            promotedGeneration = 2,
        )

        assertEquals(2L, registry.activeGeneration)
        assertEquals(2L, registry.generationFor("preloaded-period"))

        val oldDecoderRelease = registry.onDecoderReleased(
            // Media3 associates decoder callbacks with the renderer's current reading period, not
            // with a stable decoder instance. After promotion this can already be the new period.
            periodId = "preloaded-period",
            decoderName = "shared-codec",
            allowActiveFallback = false,
        )

        assertEquals(1L, oldDecoderRelease.releasedGeneration)
        assertFalse(oldDecoderRelease.clearsActiveDecoder)
        assertEquals(2L, registry.activeGeneration)

        val release = registry.onDecoderReleased(
            periodId = "preloaded-period",
            decoderName = "shared-codec",
            allowActiveFallback = false,
        )

        assertEquals(2L, release.releasedGeneration)
        assertTrue(release.clearsActiveDecoder)
        assertNull(registry.activeGeneration)
        assertNull(registry.generationFor("preloaded-period"))
    }

    @Test
    fun `active initialization replaces decoder inherited at transition`() {
        val registry = DecoderGenerationRegistry<String>()
        registry.onDecoderInitialized(
            periodId = "first-period",
            generation = 10,
            decoderName = "old-codec",
            belongsToActivePeriod = true,
        )

        registry.onMediaItemTransition(
            periodId = "second-period",
            promotedGeneration = null,
        )
        assertEquals(10L, registry.generationFor("second-period"))

        registry.onDecoderInitialized(
            periodId = "second-period",
            generation = 11,
            decoderName = "new-codec",
            belongsToActivePeriod = true,
        )
        assertEquals(11L, registry.activeGeneration)
        assertEquals(11L, registry.generationFor("second-period"))

        val staleRelease = registry.onDecoderReleased(
            periodId = "second-period",
            decoderName = "old-codec",
            allowActiveFallback = false,
        )
        assertEquals(10L, staleRelease.releasedGeneration)
        assertFalse(staleRelease.clearsActiveDecoder)
        assertEquals(11L, registry.activeGeneration)

        val activeRelease = registry.onDecoderReleased(
            periodId = "second-period",
            decoderName = "new-codec",
            allowActiveFallback = false,
        )
        assertTrue(activeRelease.clearsActiveDecoder)
        assertNull(registry.activeGeneration)
    }

    @Test
    fun `cancelled preload releases pending decoder before active decoder with same name`() {
        val registry = DecoderGenerationRegistry<String>()
        registry.onDecoderInitialized(
            periodId = "current-period",
            generation = 20,
            decoderName = "shared-codec",
            belongsToActivePeriod = true,
        )
        registry.onDecoderInitialized(
            periodId = "cancelled-preload",
            generation = 21,
            decoderName = "shared-codec",
            belongsToActivePeriod = false,
        )

        val cancelledRelease = registry.onDecoderReleased(
            periodId = "cancelled-preload",
            decoderName = "shared-codec",
            allowActiveFallback = false,
        )

        assertEquals(21L, cancelledRelease.releasedGeneration)
        assertFalse(cancelledRelease.clearsActiveDecoder)
        assertEquals(20L, registry.activeGeneration)
        assertNull(registry.generationFor("cancelled-preload"))
    }

    @Test
    fun `stale active release carrying current pending period preserves pending generation`() {
        val registry = DecoderGenerationRegistry<String>()
        registry.onDecoderInitialized(
            periodId = "active-period",
            generation = 30,
            decoderName = "old-codec",
            belongsToActivePeriod = true,
        )
        registry.onDecoderInitialized(
            periodId = "pending-period",
            generation = 31,
            decoderName = "new-codec",
            belongsToActivePeriod = false,
        )

        val staleRelease = registry.onDecoderReleased(
            // Media3 may already report the reading period of a pending decoder while releasing
            // the old active decoder. The callback period must not evict that pending generation.
            periodId = "pending-period",
            decoderName = "old-codec",
            allowActiveFallback = false,
        )

        assertEquals(30L, staleRelease.releasedGeneration)
        assertTrue(staleRelease.clearsActiveDecoder)
        assertNull(registry.activeGeneration)
        assertEquals(31L, registry.generationFor("pending-period"))
    }
}
