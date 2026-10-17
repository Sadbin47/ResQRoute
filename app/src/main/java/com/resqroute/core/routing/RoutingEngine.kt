package com.resqroute.core.routing

/**
 * Routing over one immutable [RoutingSnapshot].
 *
 * Implementations run on the caller's dispatcher (Phase 7 uses `Dispatchers.Default`),
 * are cooperatively cancellable, and never touch Android or persistence.
 */
interface RoutingEngine {

    /**
     * Finds the lowest-cost path that never crosses a blocked segment.
     *
     * Throws [kotlinx.coroutines.CancellationException] when the calling
     * coroutine is cancelled; cancellation is not a route failure.
     */
    suspend fun findRoute(
        snapshot: RoutingSnapshot,
        request: RouteRequest,
    ): RouteResult
}
