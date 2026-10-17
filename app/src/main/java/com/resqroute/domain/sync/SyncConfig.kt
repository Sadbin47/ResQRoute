package com.resqroute.domain.sync

/**
 * Shared sync configuration.
 *
 * One incident id identifies the demo session. Both phones must join the same
 * incident, and Phase 8 refuses a peer whose incident differs.
 */
object SyncConfig {
    const val INCIDENT_ID = "demo-incident-2026"
}
