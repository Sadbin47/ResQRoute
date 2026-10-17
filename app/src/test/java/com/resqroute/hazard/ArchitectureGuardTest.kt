package com.resqroute.hazard

import com.resqroute.domain.hazard.SegmentObservationAggregator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guards for ADR 0005 §8 requirements 13 and 14.
 *
 * Those two requirements are about *coupling*, not behaviour, so they cannot be proved by
 * driving the app: everything would still pass if the derivation quietly started consulting
 * a clock, and the failure would only surface as a road reopening on somebody's phone. The
 * project has no lint or architecture-test framework, so these read the sources directly —
 * the ADR explicitly prefers "a source-level check or review gate".
 *
 * The guards are deliberately narrow, so that they cannot be satisfied by accident and do
 * not block legitimate work. In particular, **display code is allowed** to read the freshness
 * policy: the whole point of it is to label what a person sees.
 */
class ArchitectureGuardTest {

    // ------------------------------------------- requirement 14: display only

    @Test
    fun theFreshnessPolicyIsReferencedOnlyByItselfAndByDisplayCode() {
        val offenders = mainSources()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val path = file.inProject()
                val isThePolicyItself = path.endsWith("domain/hazard/FreshnessPolicy.kt")
                val isDisplayCode = path.contains("/feature/")
                !isThePolicyItself && !isDisplayCode && FRESHNESS_NAMES.any { file.readText().contains(it) }
            }
            .map { it.inProject() }

        assertTrue(
            "freshness decides nothing but wording, so only display code may read it. " +
                "Found references in: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun noFreshnessConceptLeaksIntoTheDerivationOrTheRouter() {
        for (relative in ROUTING_PATH_FILES) {
            val text = source(relative)
            for (name in FRESHNESS_NAMES) {
                assertFalse(
                    "$relative is on the routing path and must not see $name",
                    text.contains(name),
                )
            }
        }
    }

    // ------------------------------ requirement 13: no clock on the routing path

    @Test
    fun nothingOnTheRoutingPathCallsTheSystemClock() {
        // Calling a clock is what is forbidden; *naming* one as the default of an injected
        // seam (`clock: () -> Long = System::currentTimeMillis`) is how the tests stay
        // deterministic, and is therefore allowed.
        for (relative in ROUTING_PATH_FILES) {
            val text = source(relative)
            for (call in SYSTEM_CLOCK_CALLS) {
                assertFalse(
                    "$relative calls the system clock ($call). Inject it instead so the " +
                        "derivation stays deterministic and a wrong phone clock cannot change a route",
                    text.contains(call),
                )
            }
        }
    }

    // ------------------------------------- the aggregation cannot take a clock

    @Test
    fun theAggregationCannotBeHandedAClock() {
        // Kotlin mangles the JVM name of a function that takes an inline value class
        // (`SegmentId`), so `aggregateSegment` compiles to `aggregateSegment-GuP9pfI`. Match
        // by prefix rather than by exact name.
        val methods = SegmentObservationAggregator::class.java.declaredMethods
            .filter { it.name.startsWith("aggregate") && !it.isSynthetic }
        assertTrue(
            "the aggregator's entry points moved; declared: " +
                SegmentObservationAggregator::class.java.declaredMethods.map { it.name }.sorted(),
            methods.size >= 2,
        )
        for (method in methods) {
            assertTrue(
                "${method.name} accepts a time value. The aggregation must be a function of the " +
                    "report set alone, or a phone's clock would change what the router may do",
                method.parameterTypes.none { it == java.lang.Long::class.java || it == Long::class.javaPrimitiveType },
            )
            assertTrue(
                "${method.name} accepts a clock seam. Injecting `now` belongs to labelling, " +
                    "never to deriving the excluded set",
                method.parameterTypes.none {
                    java.util.function.Supplier::class.java.isAssignableFrom(it) ||
                        kotlin.jvm.functions.Function0::class.java.isAssignableFrom(it)
                },
            )
        }
    }

    // ---------------------------- the penalty is a cost, never a heuristic

    @Test
    fun theEngineAppliesAPenaltyToTheCostAndNeverToTheHeuristic() {
        // The admissibility proof depends on this: the heuristic must stay the unpenalised
        // Haversine estimate, or it could overestimate and A* would return a wrong route.
        val engineSource = source("com/resqroute/core/routing/AStarRoutingEngine.kt")
        val penaltyLines = engineSource.lines().withIndex()
            .filter { (_, line) -> line.contains("penaltyMeters(") }
            .map { it.index + 1 to it.value.trim() }

        assertEquals("the engine should read a penalty in exactly one place: $penaltyLines", 1, penaltyLines.size)
        val (lineNumber, line) = penaltyLines.single()
        assertTrue(
            "line $lineNumber reads a penalty outside the cost accumulation: $line",
            line.contains("edgeCostMeters(") && line.contains("step"),
        )
        assertTrue(
            "a penalty must not reach the priority term: $line",
            !line.contains("heuristic("),
        )
    }

    // ---------------------------------------------------------------- helpers

    /** Sources under `app/src/main/java`, so the guard cannot be satisfied by a doc or a test. */
    private fun mainSources(): List<File> = File(projectRoot(), MAIN_SOURCES).walkTopDown().toList()

    private fun source(relativePath: String): String {
        val file = File(projectRoot(), "$MAIN_SOURCES/$relativePath")
        assertTrue("expected $relativePath to exist at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun File.inProject(): String = absolutePath.replace(File.separatorChar, '/')

    /**
     * Walks up from the test working directory to the Gradle root.
     *
     * Fails loudly rather than skipping: a guard that silently declines to run is worse than
     * no guard, because it reads as coverage.
     */
    private fun projectRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile || File(directory, "settings.gradle").isFile) {
                return directory
            }
            directory = directory.parentFile
        }
        error("could not locate the project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val MAIN_SOURCES = "app/src/main/java"

        val FRESHNESS_NAMES = listOf(
            "FreshnessPolicy",
            "ClosureFreshness",
            "SegmentFreshness",
            "STALE_AFTER_MILLIS",
            "RECHECK_DEADLINE_AFTER_MILLIS",
            "FUTURE_TIME_TOLERANCE_MILLIS",
            "EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS",
        )

        /** Everything that decides what the router may do, or derives the state it reads. */
        val ROUTING_PATH_FILES = listOf(
            "com/resqroute/data/repository/RoomHazardRepository.kt",
            "com/resqroute/data/local/HazardDao.kt",
            "com/resqroute/domain/hazard/ObservationAggregation.kt",
            // The reopening rule decides an excluded set, so it is on the routing path too: it may read
            // two *claimed* times to compare a clearance against a closure (ADR 0005 §5.1) and nothing
            // else — no clock, no age, no staleness window. Adding it here is what keeps that promise
            // structural rather than a comment somebody can forget (XPH-79).
            "com/resqroute/domain/hazard/Clearance.kt",
            "com/resqroute/domain/hazard/HazardModels.kt",
            "com/resqroute/domain/hazard/HazardRepository.kt",
            "com/resqroute/domain/risk/RiskPenaltyPolicy.kt",
            "com/resqroute/core/routing/AStarRoutingEngine.kt",
        )

        val SYSTEM_CLOCK_CALLS = listOf(
            "currentTimeMillis(",
            "nanoTime(",
            "elapsedRealtime(",
            "SystemClock.",
            "Instant.now(",
        )
    }
}
