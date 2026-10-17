package com.resqroute.wording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carve-out must stay narrow.
 *
 * [WordingGate] strips ADR 0005 §6.3's mandated phrase "time not verified" before checking, because
 * that phrase is an admission of uncertainty that happens to contain a banned word. A carve-out is
 * only safe if it cannot be widened by accident, so this asserts both directions.
 */
class WordingGateTest {

    @Test
    fun theMandatedAdmissionForAnUndecidableAgeIsPermitted() {
        assertTrue(WordingGate.violations("reported, time not verified").isEmpty())
        assertTrue(WordingGate.violations("reported 4 h ago").isEmpty())
        assertTrue(WordingGate.violations("no reports for 300 m of this 400 m route").isEmpty())
    }

    @Test
    fun claimingVerificationIsStillRefused() {
        for (claim in listOf(
            "this route is verified",
            "verified by two devices",
            "report confirmed",
            "the road is safe",
            "2 people reported this",
            "confidence: high",
            "90 percent of the route is covered",
        )) {
            assertFalse("\"$claim\" must be refused", WordingGate.violations(claim).isEmpty())
        }
    }

    @Test
    fun theAdmissionCannotSmuggleAClaim() {
        // Removing the approved phrase must not remove an adjacent claim as well.
        assertFalse(
            WordingGate.violations("time not verified and the road is safe").isEmpty(),
        )
        assertFalse(
            WordingGate.violations("time not verified, confirmed").isEmpty(),
        )
    }

    @Test
    fun onlyRenderedTextRefusesAPercentage() {
        // Raw resources contain `%1$s`, which is a placeholder rather than a claim.
        assertTrue(WordingGate.violations("Route: %1\$s m").isEmpty())
        assertEquals(listOf("%"), WordingGate.renderedViolations("Route: 400 m of 500 m (80%)"))
        assertTrue(WordingGate.renderedViolations("Route: 400 m").isEmpty())
    }
}
