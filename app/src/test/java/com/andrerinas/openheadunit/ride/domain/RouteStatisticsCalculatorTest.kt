package com.andrerinas.openheadunit.ride.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStatisticsCalculatorTest {

    @Test
    fun `empty sample list yields RouteStatistics EMPTY`() {
        val stats = RouteStatisticsCalculator.compute(emptyList())
        assertEquals(RouteStatistics.EMPTY, stats)
    }

    @Test
    fun `all-rejected samples yield zeroed route fields but a non-zero rejected count`() {
        val samples = listOf(
            RideRawSample.rejected(point(1_000L, 0.0, 0.0), RidePointQualityPolicy.Rejection.POOR_ACCURACY),
            RideRawSample.rejected(point(2_000L, 0.0, 0.0), RidePointQualityPolicy.Rejection.POOR_ACCURACY),
        )
        val stats = RouteStatisticsCalculator.compute(samples)
        assertEquals(2, stats.totalPointCount)
        assertEquals(0, stats.acceptedPointCount)
        assertEquals(2, stats.rejectedPointCount)
        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(0L, stats.durationMs)
    }

    @Test
    fun `a single accepted point yields zero distance and duration but counts as accepted`() {
        val samples = listOf(RideRawSample.accepted(point(1_000L, 10.0, 20.0)))
        val stats = RouteStatisticsCalculator.compute(samples)
        assertEquals(1, stats.acceptedPointCount)
        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(0L, stats.durationMs)
        assertEquals(0L, stats.movingDurationMs)
        assertEquals(0L, stats.stoppedDurationMs)
    }

    @Test
    fun `a fast leg counts as moving time and a near-stationary leg counts as stopped time`() {
        val samples = listOf(
            // Leg 1->2: ~27.5 m in 1s (~99 km/h) - moving.
            RideRawSample.accepted(point(0L, 0.0, 0.0)),
            RideRawSample.accepted(point(1_000L, 0.000247, 0.0)),
            // Leg 2->3: effectively no movement over 5s - stopped.
            RideRawSample.accepted(point(6_000L, 0.000247, 0.0)),
        )
        val stats = RouteStatisticsCalculator.compute(samples)
        assertEquals(1_000L, stats.movingDurationMs)
        assertEquals(5_000L, stats.stoppedDurationMs)
        assertEquals(6_000L, stats.durationMs)
    }

    @Test
    fun `rejected points in the middle do not break the accepted-only leg chain`() {
        val acceptedStart = point(0L, 0.0, 0.0)
        val acceptedEnd = point(2_000L, 0.000247, 0.0)
        val samples = listOf(
            RideRawSample.accepted(acceptedStart),
            RideRawSample.rejected(point(1_000L, 99.0, 99.0), RidePointQualityPolicy.Rejection.IMPOSSIBLE_JUMP),
            RideRawSample.accepted(acceptedEnd),
        )
        val stats = RouteStatisticsCalculator.compute(samples)
        assertEquals(3, stats.totalPointCount)
        assertEquals(2, stats.acceptedPointCount)
        assertEquals(1, stats.rejectedPointCount)
        // Distance/duration must be computed from the two accepted points directly, skipping the
        // rejected one in between entirely - not from the full raw list.
        val expectedDistance = RideMetrics.haversineMeters(
            acceptedStart.latitude, acceptedStart.longitude, acceptedEnd.latitude, acceptedEnd.longitude
        )
        assertEquals(expectedDistance, stats.distanceMeters, 1e-6)
        assertEquals(2_000L, stats.durationMs)
    }

    @Test
    fun `max speed prefers the fix's own reported speed over the computed leg speed`() {
        val samples = listOf(
            RideRawSample.accepted(point(0L, 0.0, 0.0)),
            // Leg speed here is tiny, but the fix itself reports a much higher instantaneous speed.
            RideRawSample.accepted(point(1_000L, 0.0000001, 0.0, speed = 40f)),
        )
        val stats = RouteStatisticsCalculator.compute(samples)
        assertEquals(40.0, stats.maxSpeedMetersPerSecond, 1e-6)
    }

    @Test
    fun `average and worst accuracy are computed over accepted points only`() {
        val samples = listOf(
            RideRawSample.accepted(point(0L, 0.0, 0.0, accuracy = 4f)),
            RideRawSample.accepted(point(1_000L, 0.0001, 0.0, accuracy = 8f)),
            RideRawSample.rejected(point(2_000L, 0.0002, 0.0, accuracy = 500f), RidePointQualityPolicy.Rejection.POOR_ACCURACY),
        )
        val stats = RouteStatisticsCalculator.compute(samples)
        assertEquals(6.0, stats.averageAccuracyMeters, 1e-6)
        assertEquals(8f, stats.worstAcceptedAccuracyMeters, 1e-6f)
    }

    @Test
    fun `average moving speed is zero when the ride never moved`() {
        val samples = listOf(
            RideRawSample.accepted(point(0L, 0.0, 0.0)),
            RideRawSample.accepted(point(5_000L, 0.0, 0.0)),
        )
        val stats = RouteStatisticsCalculator.compute(samples)
        assertEquals(0.0, stats.averageMovingSpeedMetersPerSecond, 1e-6)
    }

    private fun point(
        timestampMs: Long,
        lat: Double,
        lon: Double,
        accuracy: Float = 5f,
        speed: Float? = null,
    ) = RidePoint(
        timestampMs = timestampMs,
        latitude = lat,
        longitude = lon,
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
    )
}
