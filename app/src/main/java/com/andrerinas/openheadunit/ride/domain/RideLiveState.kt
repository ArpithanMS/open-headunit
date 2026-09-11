package com.andrerinas.openheadunit.ride.domain

/**
 * A snapshot of an actively-recording ride exactly as [com.andrerinas.openheadunit.ride.service.RideTrackingService]
 * sees it right now - not what's persisted, since Room only gets raw samples in batches (see the
 * service's own FLUSH_EVERY_N_SAMPLES). This is what lets the UI reflect live progress the moment
 * it happens instead of polling the database for a value the service already holds in memory.
 */
data class RideLiveState(
    val rideId: Long,
    val runningDistanceMeters: Double,
    /** Accuracy of the most recently *accepted* fix - null before any fix has been accepted yet
     *  (e.g. immediately after Start, while GPS is still acquiring). */
    val lastAcceptedAccuracyMeters: Float?,
    /** The most recently *accepted* fix's own reported speed (real Doppler/provider speed, from
     *  [RidePoint.speedMetersPerSecond]) - null both before any fix has been accepted, and when
     *  an accepted fix simply didn't report a speed (Location.hasSpeed() == false). Never a
     *  position-diff estimate; that's a retrospective analysis for a finished ride's route (see
     *  [RouteSpeedSegment]), not something to present as a live instantaneous reading. */
    val lastAcceptedSpeedMetersPerSecond: Float?,
)
