package com.andrerinas.openheadunit.ride.domain

/**
 * Everything worth showing about a completed (or in-progress) ride's route, derived entirely from
 * its [RideRawSample]s. Never stored directly - always recomputed by [RouteStatisticsCalculator]
 * from the raw samples, so a future change to the quality thresholds or the statistics themselves
 * only has to be recomputed, not migrated.
 */
data class RouteStatistics(
    val totalPointCount: Int,
    val acceptedPointCount: Int,
    val rejectedPointCount: Int,
    val distanceMeters: Double,
    /** Wall-clock span from the first to the last accepted fix. */
    val durationMs: Long,
    /** Sum of the legs between accepted fixes where the implied speed was at least [RouteStatisticsCalculator.MOVING_SPEED_THRESHOLD_METERS_PER_SECOND]. */
    val movingDurationMs: Long,
    /** [durationMs] minus [movingDurationMs]. */
    val stoppedDurationMs: Long,
    /** [distanceMeters] over [movingDurationMs]; 0 if the ride never moved. */
    val averageMovingSpeedMetersPerSecond: Double,
    /** The fastest leg or reported instantaneous speed seen among accepted fixes; 0 if none. */
    val maxSpeedMetersPerSecond: Double,
    /** Mean [RidePoint.accuracyMeters] over accepted fixes; 0 if none. */
    val averageAccuracyMeters: Double,
    /** Worst (largest) [RidePoint.accuracyMeters] that was still accepted; 0 if none. */
    val worstAcceptedAccuracyMeters: Float,
) {
    companion object {
        /** The answer for a ride with no samples at all. */
        val EMPTY = RouteStatistics(
            totalPointCount = 0,
            acceptedPointCount = 0,
            rejectedPointCount = 0,
            distanceMeters = 0.0,
            durationMs = 0L,
            movingDurationMs = 0L,
            stoppedDurationMs = 0L,
            averageMovingSpeedMetersPerSecond = 0.0,
            maxSpeedMetersPerSecond = 0.0,
            averageAccuracyMeters = 0.0,
            worstAcceptedAccuracyMeters = 0f,
        )
    }
}

/**
 * Computes [RouteStatistics] from a ride's raw samples. Pure, and always over the *accepted*
 * subset for anything route-shaped (distance/duration/speed) - rejected samples only ever
 * contribute to the point-count and quality fields.
 */
object RouteStatisticsCalculator {

    /**
     * Below this implied speed, a leg counts as "stopped" rather than "moving" - roughly walking
     * pace, well under anything a moving motorcycle produces, and above typical GPS position
     * jitter for a genuinely stationary rider.
     */
    const val MOVING_SPEED_THRESHOLD_METERS_PER_SECOND = 1.0

    fun compute(samples: List<RideRawSample>): RouteStatistics {
        if (samples.isEmpty()) return RouteStatistics.EMPTY

        val accepted = samples.filter { it.accepted }.map { it.point }
        val rejectedCount = samples.size - accepted.size

        if (accepted.isEmpty()) {
            return RouteStatistics.EMPTY.copy(
                totalPointCount = samples.size,
                rejectedPointCount = rejectedCount,
            )
        }

        var movingDurationMs = 0L
        var stoppedDurationMs = 0L
        var maxSpeed = 0.0

        accepted.first().speedMetersPerSecond?.let { maxSpeed = maxOf(maxSpeed, it.toDouble()) }

        for (i in 1 until accepted.size) {
            val previous = accepted[i - 1]
            val current = accepted[i]
            val legDurationMs = current.timestampMs - previous.timestampMs
            val legDistanceMeters = RideMetrics.haversineMeters(
                previous.latitude, previous.longitude, current.latitude, current.longitude
            )
            val legSpeed = if (legDurationMs > 0) legDistanceMeters / (legDurationMs / 1000.0) else 0.0

            if (legSpeed >= MOVING_SPEED_THRESHOLD_METERS_PER_SECOND) {
                movingDurationMs += legDurationMs
            } else {
                stoppedDurationMs += legDurationMs
            }

            // Prefer the fix's own Doppler-derived speed when available - it's a direct velocity
            // measurement, more accurate than a position-diff estimate over a single 1Hz leg.
            val candidateSpeed = current.speedMetersPerSecond?.toDouble() ?: legSpeed
            maxSpeed = maxOf(maxSpeed, candidateSpeed, legSpeed)
        }

        val distanceMeters = RideMetrics.totalDistanceMeters(accepted)
        val durationMs = RideMetrics.durationMs(accepted)

        return RouteStatistics(
            totalPointCount = samples.size,
            acceptedPointCount = accepted.size,
            rejectedPointCount = rejectedCount,
            distanceMeters = distanceMeters,
            durationMs = durationMs,
            movingDurationMs = movingDurationMs,
            stoppedDurationMs = stoppedDurationMs,
            averageMovingSpeedMetersPerSecond =
                if (movingDurationMs > 0) distanceMeters / (movingDurationMs / 1000.0) else 0.0,
            maxSpeedMetersPerSecond = maxSpeed,
            averageAccuracyMeters = accepted.map { it.accuracyMeters }.average(),
            worstAcceptedAccuracyMeters = accepted.maxOf { it.accuracyMeters },
        )
    }
}
