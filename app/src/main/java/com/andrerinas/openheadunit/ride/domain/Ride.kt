package com.andrerinas.openheadunit.ride.domain

/**
 * A ride as persisted/summarized data - what a history list or a recovery check works with, as
 * opposed to [RidePoint], which is one raw sample within it. Pure Kotlin, same as the rest of
 * ride/domain, so the persistence layer (ride/data) never has to leak its Room-annotated entity
 * types to any consumer.
 *
 * A null [endTimestampMs] means the ride is still open: either actively [RideState.RIDING], or
 * abandoned mid-ride by a process death - see RideRepository.activeRide() in ride/data, which
 * uses exactly that to recover an interrupted ride on next launch.
 */
data class Ride(
    val id: Long,
    val startTimestampMs: Long,
    val endTimestampMs: Long?,
    val state: RideState,
    val distanceMeters: Double,
    val durationMs: Long,
    /** The first/last *accepted* fix's position - null until the ride finishes (or if it finished
     *  with zero accepted fixes; never a fabricated 0,0). What [com.andrerinas.openheadunit.ride.domain.RideClassifier]
     *  compares against saved Home/Work places to label a finished ride in history. */
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val endLatitude: Double? = null,
    val endLongitude: Double? = null,
)
