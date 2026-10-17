package com.resqroute.feature.map.ui

/**
 * The two mark types a user can report for a physical road segment.
 *
 * Both are effective blocks (infinite cost in routing); the value only records
 * provenance. Phase 6 replaces this UI-level type with the persisted hazard
 * reason and Phase 8 puts it on the wire.
 */
enum class RoadMarkType(val label: String) {
    WATERLOGGED("Waterlogged"),
    BLOCKED("Blocked"),
}
