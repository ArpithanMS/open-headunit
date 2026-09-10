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
)
