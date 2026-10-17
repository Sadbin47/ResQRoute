package com.resqroute.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stable installation identity.
 *
 * A single row (`singletonId = 1`) persists one random device id across process
 * restarts. It is the provenance recorded on locally created events and is
 * unrelated to any Nearby endpoint id.
 */
@Entity(tableName = "device_identity")
data class DeviceIdentityEntity(
    @PrimaryKey
    val singletonId: Int = 1,
    val deviceId: String,
)
