package com.resqroute.dataset

import com.resqroute.feature.map.ui.CameraKey
import com.resqroute.feature.map.ui.RoadsPathCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 acceptance criterion 3: the static road layer is rebuilt only when
 * the camera changes. A hazard-only repaint must reuse the cached path.
 */
class RoadsPathCacheTest {

    private fun key(zoom: Double = 13.0) = CameraKey(
        zoom = zoom,
        latNorth = 23.01,
        latSouth = 23.00,
        lonEast = 91.41,
        lonWest = 91.40,
        width = 1080,
        height = 720,
    )

    @Test
    fun rebuildsOnlyWhenTheCameraChanges() {
        val cache = RoadsPathCache()
        var builds = 0

        assertTrue(cache.ensure(key()) { builds++ })
        assertEquals(1, builds)

        // Repeated repaints with the same camera (e.g. hazard updates) reuse the path.
        assertFalse(cache.ensure(key()) { builds++ })
        assertFalse(cache.ensure(key()) { builds++ })
        assertEquals(1, builds)
        assertEquals(1, cache.rebuildCount)

        // A camera change rebuilds exactly once.
        assertTrue(cache.ensure(key(zoom = 14.0)) { builds++ })
        assertEquals(2, builds)

        // A pan within the same zoom also rebuilds.
        assertTrue(
            cache.ensure(key(zoom = 14.0).copy(lonEast = 91.42, lonWest = 91.41)) { builds++ },
        )
        assertEquals(3, builds)
    }

    @Test
    fun invalidateForcesARebuild() {
        val cache = RoadsPathCache()
        var builds = 0
        cache.ensure(key()) { builds++ }
        cache.ensure(key()) { builds++ }
        assertEquals(1, builds)

        cache.invalidate()
        cache.ensure(key()) { builds++ }
        assertEquals(2, builds)
    }
}
