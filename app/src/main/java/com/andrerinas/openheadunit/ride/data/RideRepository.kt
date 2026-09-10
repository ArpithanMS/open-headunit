package com.andrerinas.openheadunit.ride.data

import com.andrerinas.openheadunit.ride.domain.Ride
import com.andrerinas.openheadunit.ride.domain.RideRawSample
import com.andrerinas.openheadunit.ride.domain.RideState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single seam through which every consumer of ride data - the tracking service, a future
 * standalone tracker UI, a future HUD, a future custom launcher - reaches persisted rides. Wraps
 * [RideDao] and keeps Room's annotated entity types from leaking past ride/data: callers only
 * ever see the plain types in ride/domain.
 */
class RideRepository(private val dao: RideDao) {

    /** Starts a new ride row in RIDING state with no points yet. Returns its generated id. */
    suspend fun startRide(startTimestampMs: Long): Long = dao.insertRide(
        RideEntity(
            startTimestampMs = startTimestampMs,
            endTimestampMs = null,
            state = RideState.RIDING.name,
            distanceMeters = 0.0,
            durationMs = 0L,
        )
    )

    /**
     * Appends a batch of raw samples to [rideId] - accepted or not, per
     * [com.andrerinas.openheadunit.ride.domain.RidePointQualityPolicy]. A no-op for an empty batch.
     */
    suspend fun appendSamples(rideId: Long, samples: List<RideRawSample>) {
        if (samples.isEmpty()) return
        dao.insertPoints(samples.map { it.toEntity(rideId) })
    }

    /** Marks [rideId] FINISHED with its final computed totals. */
    suspend fun finishRide(rideId: Long, endTimestampMs: Long, distanceMeters: Double, durationMs: Long) {
        dao.finishRide(rideId, endTimestampMs, RideState.FINISHED.name, distanceMeters, durationMs)
    }

    /**
     * The one ride still missing an end timestamp, if any - a ride that was RIDING when the
     * process died. RideTrackingService consults this on startup to resume tracking instead of
     * silently losing it.
     */
    suspend fun activeRide(): Ride? = dao.activeRide()?.toDomain()

    /** A specific ride's stored summary, or null if it doesn't exist. */
    suspend fun rideById(rideId: Long): Ride? = dao.rideById(rideId)?.toDomain()

    /** Every raw sample recorded for [rideId], accepted or not, in recording order. */
    suspend fun rawSamplesForRide(rideId: Long): List<RideRawSample> =
        dao.rawSamplesForRide(rideId).map { it.toDomain() }

    /** Finished rides, most recent first, for a ride history list. */
    fun observeRideHistory(): Flow<List<Ride>> =
        dao.observeFinishedRides().map { entities -> entities.map { it.toDomain() } }
}
