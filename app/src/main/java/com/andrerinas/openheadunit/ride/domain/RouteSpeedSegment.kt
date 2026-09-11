package com.andrerinas.openheadunit.ride.domain

/**
 * One leg between two consecutive accepted [RidePoint]s, with its own implied speed - what the
 * route map's speed-colored line is built from. Pure position/time math, same inputs
 * [RouteStatisticsCalculator] already uses for `averageMovingSpeedMetersPerSecond`, just kept
 * per-leg instead of reduced to a single ride-wide number.
 */
data class RouteSpeedSegment(
    val start: RidePoint,
    val end: RidePoint,
    val distanceMeters: Double,
    val speedMetersPerSecond: Double,
)

/** Splits a ride's accepted points into consecutive [RouteSpeedSegment]s. Pure, no Android deps. */
object RouteSpeedSegmenter {

    fun segment(points: List<RidePoint>): List<RouteSpeedSegment> {
        if (points.size < 2) return emptyList()
        return (1 until points.size).map { i ->
            val previous = points[i - 1]
            val current = points[i]
            val legDistanceMeters = RideMetrics.haversineMeters(
                previous.latitude, previous.longitude, current.latitude, current.longitude
            )
            val legDurationS = (current.timestampMs - previous.timestampMs) / 1000.0
            val speed = if (legDurationS > 0) legDistanceMeters / legDurationS else 0.0
            RouteSpeedSegment(previous, current, legDistanceMeters, speed)
        }
    }
}
