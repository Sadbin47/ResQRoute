package com.resqroute.presentation

import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.RouteRequest
import com.resqroute.core.routing.RoutingPolicy
import com.resqroute.core.routing.RoutingSnapshot
import com.resqroute.core.graph.GraphBlocker
import com.resqroute.feature.map.presentation.RoutingInput
import com.resqroute.feature.map.presentation.isRoutePublishable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The publication guard in isolation (Phase 7 acceptance criterion 2).
 *
 * A result is publishable only when it belongs to the newest generation and its
 * dataset, incident and hazard revision still match a delayed old search cannot
 * overwrite a newer route.
 */
class RoutePublicationGuardTest {

    private val dataset = DatasetIdentity("dataset-a", 1)
    private val otherDataset = DatasetIdentity("dataset-b", 1)

    private fun input(
        generation: Long = 5,
        datasetIdentity: DatasetIdentity = dataset,
        incidentId: String = "incident-a",
        hazardRevision: Long = 3,
        policy: RoutingPolicy = RoutingPolicy.DISTANCE_ONLY,
    ) = RoutingInput(
        generation = generation,
        dataset = datasetIdentity,
        incidentId = incidentId,
        hazardRevision = hazardRevision,
        startNodeId = NodeId(1),
        destinationNodeId = NodeId(2),
        snapshot = RoutingSnapshot(
            dataset = datasetIdentity,
            incidentId = incidentId,
            hazardRevision = hazardRevision,
            graph = com.resqroute.dataset.buildTestGraph(
                nodes = listOf(
                    com.resqroute.dataset.NodeSpec(1, 23.0, 91.4),
                    com.resqroute.dataset.NodeSpec(2, 23.001, 91.401),
                ),
                segments = listOf(
                    com.resqroute.dataset.SegmentSpec(
                        0,
                        1,
                        100.0,
                        listOf(23.0 to 91.4, 23.001 to 91.401),
                    ),
                ),
                identity = datasetIdentity,
            ),
            blocker = GraphBlocker.NONE,
        ),
        request = RouteRequest(generation, NodeId(1), NodeId(2), policy),
    )

    @Test
    fun currentInputIsPublishable() {
        assertTrue(
            isRoutePublishable(
                input = input(),
                currentGeneration = 5,
                currentDataset = dataset,
                currentIncidentId = "incident-a",
                currentHazardRevision = 3,
            ),
        )
    }

    @Test
    fun olderGenerationIsDroppedEvenIfItFinishesLast() {
        assertFalse(
            isRoutePublishable(
                input = input(generation = 4),
                currentGeneration = 5,
                currentDataset = dataset,
                currentIncidentId = "incident-a",
                currentHazardRevision = 3,
            ),
        )
    }

    @Test
    fun changedHazardRevisionDropsTheOldResult() {
        assertFalse(
            isRoutePublishable(
                input = input(hazardRevision = 3),
                currentGeneration = 5,
                currentDataset = dataset,
                currentIncidentId = "incident-a",
                currentHazardRevision = 4,
            ),
        )
    }

    @Test
    fun changedDatasetOrIncidentDropsTheResult() {
        assertFalse(
            isRoutePublishable(
                input = input(datasetIdentity = otherDataset),
                currentGeneration = 5,
                currentDataset = dataset,
                currentIncidentId = "incident-a",
                currentHazardRevision = 3,
            ),
        )
        assertFalse(
            isRoutePublishable(
                input = input(incidentId = "incident-b"),
                currentGeneration = 5,
                currentDataset = dataset,
                currentIncidentId = "incident-a",
                currentHazardRevision = 3,
            ),
        )
    }

    @Test
    fun nothingIsPublishableBeforeHazardTruthIsReady() {
        assertFalse(
            isRoutePublishable(
                input = input(hazardRevision = 0),
                currentGeneration = 5,
                currentDataset = dataset,
                currentIncidentId = "incident-a",
                currentHazardRevision = -1,
            ),
        )
    }

    @Test
    fun aWeightedRequestIsPublishableOnExactlyTheSameTerms() {
        // The guard keys on generation, dataset, incident and hazard revision. It never reads a
        // cost, so adding penalties cannot change when a result may be published - asserted rather
        // than assumed, because "the route changed but the guard did not" is easy to get wrong.
        val weighted = RoutingPolicy(mapOf(SegmentId(0) to 250.0))

        assertTrue(
            isRoutePublishable(
                input = input(policy = weighted),
                currentGeneration = 5,
                currentDataset = dataset,
                currentIncidentId = "incident-a",
                currentHazardRevision = 3,
            ),
        )
        assertFalse(
            "an older weighted generation must still be dropped",
            isRoutePublishable(
                input = input(generation = 4, policy = weighted),
                currentGeneration = 5,
                currentDataset = dataset,
                currentIncidentId = "incident-a",
                currentHazardRevision = 3,
            ),
        )
        assertFalse(
            "a stale hazard revision must still drop a weighted result",
            isRoutePublishable(
                input = input(policy = weighted),
                currentGeneration = 5,
                currentDataset = dataset,
                currentIncidentId = "incident-a",
                currentHazardRevision = 4,
            ),
        )
    }
}
