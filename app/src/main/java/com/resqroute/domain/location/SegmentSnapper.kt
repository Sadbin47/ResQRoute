package com.resqroute.domain.location

import com.resqroute.core.graph.SegmentHit
import com.resqroute.core.graph.SegmentSpatialIndex
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.SegmentId

/** One location fix, as the device reported it. Accuracy is a radius, never a guarantee. */
data class LocationFix(
    val coordinate: GeoCoordinate,
    /** The provider's own accuracy radius in metres, or null when it does not report one. */
    val accuracyMeters: Double?,
)

/**
 * Where a fix comes from.
 *
 * A `fun interface` so tests supply a **fake provider** rather than a location framework, and so the
 * failure paths — denied, no fix, unusable — are ordinary values rather than exceptions to catch. On
 * a phone standing in water, "no fix yet" is a normal state, not an error.
 */
fun interface LocationSource {

    /** The last known fix, or why there is none. Never blocks: this is a last-known read. */
    suspend fun lastKnownFix(): LocationOutcome

    companion object {
        /** A source that never has a fix, for callers that do not need location at all. */
        val Unavailable: LocationSource = LocationSource { LocationOutcome.NoFix }
    }
}

/** Why a fix could not be used, kept distinct because the fallback message differs. */
sealed interface LocationOutcome {

    data class Fix(val fix: LocationFix) : LocationOutcome

    /** The user has not granted location, or granted it and later revoked it. */
    data object PermissionDenied : LocationOutcome

    /** Permission is granted but the device has no fix yet — indoors, or the provider is off. */
    data object NoFix : LocationOutcome

    /** The provider itself failed. Carries an operator-readable reason. */
    data class Failed(val message: String) : LocationOutcome
}

/**
 * Something about a snap the person should know before it is treated as their report.
 *
 * These do not block the suggestion — the flow always asks for confirmation anyway — they change
 * what the confirmation **says**, so a doubtful match is never presented as certainty.
 */
enum class SnapConcern {
    /** The nearest road is farther than the comfortable distance; it may not be the road they are on. */
    FAR_FROM_ROAD,

    /** Two or more distinct roads are within the ambiguity margin: do not choose between them silently. */
    AMBIGUOUS,

    /** The fix's own accuracy is worse than the threshold, so the snap inherits that doubt. */
    POOR_ACCURACY,
}

/** What the spatial index made of a fix. */
sealed interface SegmentSnap {

    /**
     * A road to offer. [concerns] empty means a lone road at a comfortable distance with a good fix —
     * the only case the UI may describe without hedging.
     */
    data class Suggest(
        val best: SegmentHit,
        val accuracyMeters: Double?,
        val concerns: Set<SnapConcern>,
        /** The other roads within the ambiguity margin, for a UI that lets the person pick. */
        val alternatives: List<SegmentId>,
    ) : SegmentSnap

    /** Nothing within the search radius at all: not a road we can guess at. */
    data object NoRoadNearby : SegmentSnap
}

/**
 * Snaps a fix to a road using the **existing** spatial index (XPH-90).
 *
 * Pure and threshold-driven, so every branch is testable with a fake provider and a fake index. The
 * thresholds live here, in one documented place, and are deliberately generous: this feature raises
 * report volume by removing the need to aim a long-press, and it must never invent a road the person
 * is not on. A doubtful snap is *shown with its doubt*, never silently dropped and never silently
 * trusted — the manual long-press path is always still available.
 */
object SegmentSnapper {

    /** How far we look for any road at all. Beyond this there is nothing to offer. */
    const val SEARCH_RADIUS_METERS: Double = 150.0

    /** At or under this, a lone road is treated as the road they are on. */
    const val TRUSTED_SNAP_METERS: Double = 25.0

    /** Two distinct roads closer together than this is an ambiguity, not a choice we may make. */
    const val AMBIGUITY_MARGIN_METERS: Double = 8.0

    /** A fix worse than this is not trusted to identify a road on its own. */
    const val TRUSTED_ACCURACY_METERS: Double = 50.0

    /** How many candidates to gather, enough to see an ambiguity without scanning the map. */
    private const val CANDIDATE_LIMIT = 8

    suspend fun snap(
        index: SegmentSpatialIndex,
        coordinate: GeoCoordinate,
        accuracyMeters: Double?,
    ): SegmentSnap {
        val candidates = index.nearbySegments(coordinate, SEARCH_RADIUS_METERS, CANDIDATE_LIMIT)
        val best = candidates.firstOrNull() ?: return SegmentSnap.NoRoadNearby

        // Roads within the ambiguity margin of the best one. Equal distances are broken by segment
        // index by the index itself, but that break decides *ordering*, never confidence: a tie here
        // is exactly the case this feature must not resolve on its own.
        val alternatives = candidates
            .drop(1)
            .filter { it.distanceMeters <= best.distanceMeters + AMBIGUITY_MARGIN_METERS }
            .map { it.segmentId }

        val concerns = buildSet {
            if (best.distanceMeters > TRUSTED_SNAP_METERS) add(SnapConcern.FAR_FROM_ROAD)
            if (alternatives.isNotEmpty()) add(SnapConcern.AMBIGUOUS)
            if (accuracyMeters != null && accuracyMeters > TRUSTED_ACCURACY_METERS) {
                add(SnapConcern.POOR_ACCURACY)
            }
        }

        return SegmentSnap.Suggest(
            best = best,
            accuracyMeters = accuracyMeters,
            concerns = concerns,
            alternatives = alternatives,
        )
    }
}
