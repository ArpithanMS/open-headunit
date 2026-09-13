package com.andrerinas.openheadunit.ride.domain

enum class RideSegmentType { MOVING, STOPPED }

/**
 * One contiguous span of a ride's already-recorded route, classified in hindsight as either
 * actual travel or a meaningful pause - see [RideSegmenter]. A "Ride" (one Start-to-Stop or
 * auto-start-to-auto-stop recording) is what the user calls a **Trip**; the [MOVING][RideSegmentType.MOVING]
 * segments within it are **Continuous Rides** - traffic lights, crawling traffic, and short stops
 * stay inside one moving segment rather than fragmenting it (see [RideSegmenter]'s KDoc).
 */
data class RideSegment(
    val type: RideSegmentType,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    /** 0.0 for a STOPPED segment - a stop's whole point is that it contributed no travel. */
    val distanceMeters: Double,
    val startLatitude: Double,
    val startLongitude: Double,
    val endLatitude: Double,
    val endLongitude: Double,
) {
    val durationMs: Long get() = endTimestampMs - startTimestampMs
}
