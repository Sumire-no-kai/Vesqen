package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class NeighborSeekRouteTest {
    @Test
    fun `prefers the shuffle aware indexed route when it is available`() {
        assertEquals(
            NeighborSeekRoute.BY_INDEX,
            selectNeighborSeekRoute(
                hasNeighbor = true,
                neighborIndex = 2,
                canSeekToMediaItem = true,
                canUseDedicatedCommand = true,
            ),
        )
    }

    @Test
    fun `falls back to the dedicated route when indexed seeking is unavailable`() {
        assertEquals(
            NeighborSeekRoute.DEDICATED,
            selectNeighborSeekRoute(
                hasNeighbor = true,
                neighborIndex = 2,
                canSeekToMediaItem = false,
                canUseDedicatedCommand = true,
            ),
        )
    }

    @Test
    fun `uses the dedicated route when Media3 has no addressable neighbor index`() {
        assertEquals(
            NeighborSeekRoute.DEDICATED,
            selectNeighborSeekRoute(
                hasNeighbor = true,
                neighborIndex = C.INDEX_UNSET,
                canSeekToMediaItem = true,
                canUseDedicatedCommand = true,
            ),
        )
    }

    @Test
    fun `never enables a neighbor route without a valid target or granted command`() {
        assertEquals(
            NeighborSeekRoute.UNAVAILABLE,
            selectNeighborSeekRoute(
                hasNeighbor = true,
                neighborIndex = C.INDEX_UNSET,
                canSeekToMediaItem = true,
                canUseDedicatedCommand = false,
            ),
        )
        assertEquals(
            NeighborSeekRoute.UNAVAILABLE,
            selectNeighborSeekRoute(
                hasNeighbor = false,
                neighborIndex = 0,
                canSeekToMediaItem = true,
                canUseDedicatedCommand = true,
            ),
        )
    }
}
