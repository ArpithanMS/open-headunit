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
)
