package com.resqroute.hazard

import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationValidation
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.WaterLevelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v2 observation model: two independent axes, validated construction, and the
 * legacy mapping that keeps every v1 report an exclusion.
 *
 * Covers XPH-67 acceptance criteria and rows 1 and 2 of the mandatory test matrix.
 */
class RoadObservationTest {

    // ---------------------------------------------------------------- codes

    @Test
    fun waterLevelCodesRoundTrip() {
        for (value in WaterLevelCategory.entries) {
            assertEquals(value, WaterLevelCategory.fromCode(value.code))
        }
    }

    @Test
    fun passabilityCodesRoundTrip() {
        for (value in PassabilityStatus.entries) {
            assertEquals(value, PassabilityStatus.fromCode(value.code))
        }
    }

    @Test
    fun unknownCodesAreRejectedRatherThanGuessed() {
        assertNull(WaterLevelCategory.fromCode("DEEP"))
        assertNull(PassabilityStatus.fromCode("MAYBE"))
        assertNull(HazardReason.fromCode("FLOODED"))
    }

    @Test
    fun codesAreUniqueAndPinned() {
        assertEquals(
            WaterLevelCategory.entries.size,
            WaterLevelCategory.entries.map { it.code }.toSet().size,
        )
        assertEquals(
            PassabilityStatus.entries.size,
            PassabilityStatus.entries.map { it.code }.toSet().size,
        )
        // The legacy codes are the ones already persisted and on the wire.
        assertEquals("WATERLOGGED", HazardReason.WATERLOGGED.code)
        assertEquals("BLOCKED", HazardReason.BLOCKED.code)
    }

    // ------------------------------------------------------------ axis rules

    @Test
    fun impassableIsTheOnlyValueThatExcludesTraversal() {
        for (value in PassabilityStatus.entries) {
            assertEquals(
                "excludesTraversal must be true only for IMPASSABLE but was $value",
                value == PassabilityStatus.IMPASSABLE,
                value.excludesTraversal,
            )
        }
    }

    @Test
    fun unknownIsTheOnlyValueThatStatesNothing() {
        for (value in WaterLevelCategory.entries) {
            assertEquals(value != WaterLevelCategory.UNKNOWN, value.statesSomething)
        }
        for (value in PassabilityStatus.entries) {
            assertEquals(value != PassabilityStatus.UNKNOWN, value.statesSomething)
        }
    }

    // ------------------------------------------------------ valid reports

    @Test
    fun aWaterLevelAloneIsRepresentable() {
        val observation = valid(
            RoadObservation.create(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.UNKNOWN),
        )
        assertTrue(observation.statesSomething)
        assertFalse(observation.excludesTraversal)
        assertEquals(WaterLevelCategory.KNEE, observation.waterLevel)
        assertEquals(PassabilityStatus.UNKNOWN, observation.passability)
    }

    @Test
    fun aPassabilityVerdictAloneIsRepresentable() {
        val observation = valid(
            RoadObservation.create(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE),
        )
        assertTrue(observation.statesSomething)
        assertTrue(observation.excludesTraversal)
        assertEquals(WaterLevelCategory.UNKNOWN, observation.waterLevel)
    }

    @Test
    fun bothAxesTogetherAreRepresentable() {
        val observation = valid(
            RoadObservation.create(HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.AVOID),
        )
        assertTrue(observation.statesSomething)
        assertFalse(observation.excludesTraversal)
    }

    @Test
    fun aCollapsedBridgeIsRepresentableWithoutAnyWater() {
        // The reason the two axes exist: impassable with no water at all.
        val observation = valid(
            RoadObservation.create(
                HazardReason.BLOCKED,
                WaterLevelCategory.NO_VISIBLE_WATER,
                PassabilityStatus.IMPASSABLE,
            ),
        )
        assertTrue(observation.excludesTraversal)
        assertEquals(WaterLevelCategory.NO_VISIBLE_WATER, observation.waterLevel)
    }

    @Test
    fun deepWaterWithAnAvoidVerdictIsRepresentable() {
        val observation = valid(
            RoadObservation.create(HazardReason.WATERLOGGED, WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID),
        )
        assertFalse(observation.excludesTraversal)
    }

    @Test
    fun deepWaterWithAnImpassableVerdictIsRepresentable() {
        val observation = valid(
            RoadObservation.create(
                HazardReason.WATERLOGGED,
                WaterLevelCategory.ABOVE_WAIST,
                PassabilityStatus.IMPASSABLE,
            ),
        )
        assertTrue(observation.excludesTraversal)
    }

    // ---------------------------------------------------- invalid reports

    @Test
    fun aReportThatStatesNothingIsRejected() {
        val outcome = RoadObservation.create(
            HazardReason.WATERLOGGED,
            WaterLevelCategory.UNKNOWN,
            PassabilityStatus.UNKNOWN,
        )
        assertTrue("expected Invalid but was $outcome", outcome is ObservationValidation.Invalid)
        assertEquals(
            RoadObservation.PROBLEM_STATES_NOTHING,
            (outcome as ObservationValidation.Invalid).problem,
        )
    }

    @Test
    fun aboveWaistWaterCannotBeReportedPassable() {
        val outcome = RoadObservation.create(
            HazardReason.WATERLOGGED,
            WaterLevelCategory.ABOVE_WAIST,
            PassabilityStatus.PASSABLE_REPORTED,
        )
        assertTrue("expected Invalid but was $outcome", outcome is ObservationValidation.Invalid)
        assertEquals(
            RoadObservation.PROBLEM_ABOVE_WAIST_IS_NOT_PASSABLE,
            (outcome as ObservationValidation.Invalid).problem,
        )
    }

    @Test
    fun theInvalidSetIsExactlyTheTwoDocumentedRules() {
        // Total over every reason x water level x passability: validation never
        // throws, and exactly the documented combinations are invalid.
        val invalid = ArrayList<String>()
        var valid = 0
        for (reason in HazardReason.entries) {
            for (water in WaterLevelCategory.entries) {
                for (passability in PassabilityStatus.entries) {
                    when (RoadObservation.create(reason, water, passability)) {
                        is ObservationValidation.Valid -> valid++
                        is ObservationValidation.Invalid -> invalid += "$reason/$water/$passability"
                    }
                }
            }
        }

        val combinations = HazardReason.entries.size *
            WaterLevelCategory.entries.size *
            PassabilityStatus.entries.size
        assertEquals(combinations, valid + invalid.size)

        // R1 fires for both reasons; R2 likewise. Nothing else is rejected.
        assertEquals(4, invalid.size)
        assertEquals(2, invalid.count { it.contains(WaterLevelCategory.UNKNOWN.name) && it.contains(PassabilityStatus.UNKNOWN.name) })
        assertEquals(2, invalid.count { it.contains(WaterLevelCategory.ABOVE_WAIST.name) && it.contains(PassabilityStatus.PASSABLE_REPORTED.name) })
    }

    @Test
    fun anUnknownAxisIsNeverInferredFromTheOther() {
        val waterOnly = valid(
            RoadObservation.create(HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.UNKNOWN),
        )
        assertEquals(PassabilityStatus.UNKNOWN, waterOnly.passability)

        val verdictOnly = valid(
            RoadObservation.create(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.AVOID),
        )
        assertEquals(WaterLevelCategory.UNKNOWN, verdictOnly.waterLevel)
    }

    // ------------------------------------------------------- legacy mapping

    @Test
    fun everyLegacyReasonStaysAnExclusion() {
        for (reason in HazardReason.entries) {
            val observation = RoadObservation.fromLegacy(reason)
            assertTrue(
                "legacy $reason must remain an exclusion",
                observation.excludesTraversal,
            )
            assertEquals(PassabilityStatus.IMPASSABLE, observation.passability)
        }
    }

    @Test
    fun noLegacyReportCanBeTraversed() {
        // The mapping's whole point (ADR 0001 D2): a legacy report cannot produce a
        // traversable observation, and a finite penalty only ever applies to a
        // traversable one - so no finite penalty is reachable for legacy input.
        for (reason in HazardReason.entries) {
            val observation = RoadObservation.fromLegacy(reason)
            assertTrue("legacy $reason must exclude traversal", observation.excludesTraversal)
            assertTrue(
                "legacy $reason must be impassable",
                observation.passability == PassabilityStatus.IMPASSABLE,
            )
        }
    }

    @Test
    fun theLegacyMappingInventsNoWaterLevel() {
        // v1 never recorded a depth, so nothing may be invented - in particular the
        // mapping must not claim "no visible water" or a depth category.
        for (reason in HazardReason.entries) {
            assertEquals(
                WaterLevelCategory.UNKNOWN,
                RoadObservation.fromLegacy(reason).waterLevel,
            )
        }
    }

    @Test
    fun theLegacyMappingPreservesProvenance() {
        assertEquals(HazardReason.WATERLOGGED, RoadObservation.fromLegacy(HazardReason.WATERLOGGED).reason)
        assertEquals(HazardReason.BLOCKED, RoadObservation.fromLegacy(HazardReason.BLOCKED).reason)
    }

    @Test
    fun legacyObservationsStateSomethingAndAreValidInput() {
        for (reason in HazardReason.entries) {
            val observation = RoadObservation.fromLegacy(reason)
            assertTrue(observation.statesSomething)
            // Round-tripping the mapped values through validation must succeed, which
            // proves the mapping never produces a combination its own rules reject.
            val revalidated = RoadObservation.create(
                observation.reason,
                observation.waterLevel,
                observation.passability,
            )
            assertTrue("legacy $reason must revalidate", revalidated is ObservationValidation.Valid)
        }
    }

    // -------------------------------------------------------- value semantics

    @Test
    fun valueEqualityIgnoresInstanceIdentity() {
        val first = RoadObservation.fromLegacy(HazardReason.BLOCKED)
        val second = RoadObservation.fromLegacy(HazardReason.BLOCKED)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())

        val other = RoadObservation.fromLegacy(HazardReason.WATERLOGGED)
        assertFalse(first == other)
    }

    @Test
    fun toStringCarriesTheThreeCodes() {
        val text = RoadObservation.fromLegacy(HazardReason.WATERLOGGED).toString()
        assertTrue(text.contains("WATERLOGGED"))
        assertTrue(text.contains(WaterLevelCategory.UNKNOWN.code))
        assertTrue(text.contains(PassabilityStatus.IMPASSABLE.code))
    }

    private fun valid(outcome: ObservationValidation): RoadObservation {
        assertTrue("expected Valid but was $outcome", outcome is ObservationValidation.Valid)
        return (outcome as ObservationValidation.Valid).observation
    }
}
