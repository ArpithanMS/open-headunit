package com.andrerinas.openheadunit.ride.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted row for one ride. A null [endTimestampMs] means the ride is still open - either
 * actively RIDING, or abandoned mid-ride by a process death - which is exactly the signal
 * [RideDao.activeRide] uses to recover it on next launch.
 */
@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startTimestampMs: Long,
    val endTimestampMs: Long?,
    /**
     * A [com.andrerinas.openheadunit.ride.domain.RideState] name, stored by name rather than
     * ordinal so this column survives the enum being reordered or extended - the same reasoning
     * Settings.kt already applies to some of its own persisted enums.
     */
    val state: String,
    val distanceMeters: Double,
    val durationMs: Long,
    /** See [com.andrerinas.openheadunit.ride.domain.Ride]'s KDoc - null until finishRide(). */
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val endLatitude: Double? = null,
    val endLongitude: Double? = null,
)
