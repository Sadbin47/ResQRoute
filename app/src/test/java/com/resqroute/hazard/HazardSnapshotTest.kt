package com.resqroute.hazard

import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.HazardSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Snapshot value semantics and the O(1) blocked lookup used by A*. */
class HazardSnapshotTest {

    private val blocked = setOf(SegmentId(3), SegmentId(17), SegmentId(10_000))

    @Test
    fun blockedLookupMatchesTheSet() {
        val snapshot = HazardSnapshot(HazardFixtures.contextAlpha, revision = 5, blockedSegments = blocked)
        assertTrue(snapshot.isBlockedSegment(3))
        assertTrue(snapshot.isBlockedSegment(17))
        assertTrue(snapshot.isBlockedSegment(10_000))

        assertFalse(snapshot.isBlockedSegment(0))
        assertFalse(snapshot.isBlockedSegment(4))
        assertFalse(snapshot.isBlockedSegment(10_001))
        assertFalse(snapshot.isBlockedSegment(-1))
        assertEquals(3, snapshot.blockedSegments.size)
        assertEquals(5L, snapshot.revision)
    }

    @Test
    fun equalityUsesContextRevisionAndSetOnly() {
        val a = HazardSnapshot(HazardFixtures.contextAlpha, revision = 1, blockedSegments = setOf(SegmentId(1)))
        val b = HazardSnapshot(HazardFixtures.contextAlpha, revision = 1, blockedSegments = setOf(SegmentId(1)))
        val c = HazardSnapshot(HazardFixtures.contextAlpha, revision = 2, blockedSegments = setOf(SegmentId(1)))
        val d = HazardSnapshot(HazardFixtures.contextAlpha, revision = 1, blockedSegments = setOf(SegmentId(2)))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertNotEquals(a, d)
    }

    @Test
    fun emptySnapshotBlocksNothingAndIsAValidReadyState() {
        val snapshot = HazardSnapshot(HazardFixtures.contextAlpha, revision = 0, blockedSegments = emptySet())
        assertTrue(snapshot.blockedSegments.isEmpty())
        assertFalse(snapshot.isBlockedSegment(0))
        assertFalse(snapshot.isBlockedSegment(Int.MAX_VALUE))
    }
}
