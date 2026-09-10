package com.andrerinas.openheadunit.ride.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RidePointQualityPolicyTest {

    @Test
    fun `first point of a ride is always accepted regardless of previous`() {
        val result = RidePointQualityPolicy.evaluate(point(1_000L, 10.0, 20.0), previousAccepted = null)
        assertEquals(RidePointQualityPolicy.Result.Accepted, result)
    }

    @Test
    fun `rejects a fix with worse accuracy than the threshold`() {
        val candidate = point(2_000L, 10.0001, 20.0001, accuracy = RidePointQualityPolicy.MAX_ACCEPTED_ACCURACY_METERS + 1f)
        val result = RidePointQualityPolicy.evaluate(candidate, previousAccepted = point(1_000L, 10.0, 20.0))
        assertEquals(
            RidePointQualityPolicy.Result.Rejected(RidePointQualityPolicy.Rejection.POOR_ACCURACY),
            result
        )
    }

    @Test
    fun `accepts a fix exactly at the accuracy threshold`() {
        val candidate = point(2_000L, 10.0001, 20.0001, accuracy = RidePointQualityPolicy.MAX_ACCEPTED_ACCURACY_METERS)
        val result = RidePointQualityPolicy.evaluate(candidate, previousAccepted = point(1_000L, 10.0, 20.0))
        assertEquals(RidePointQualityPolicy.Result.Accepted, result)
    }

    @Test
    fun `rejects a non-monotonic timestamp`() {
        val previous = point(5_000L, 10.0, 20.0)
        val candidate = point(4_000L, 10.0001, 20.0001)
        val result = RidePointQualityPolicy.evaluate(candidate, previous)
        assertEquals(
            RidePointQualityPolicy.Result.Rejected(RidePointQualityPolicy.Rejection.NON_MONOTONIC_TIMESTAMP),
            result
        )
    }

    @Test
    fun `rejects an identical timestamp as non-monotonic`() {
        val previous = point(5_000L, 10.0, 20.0)
        val candidate = point(5_000L, 10.0001, 20.0001)
        val result = RidePointQualityPolicy.evaluate(candidate, previous)
        assertEquals(
            RidePointQualityPolicy.Result.Rejected(RidePointQualityPolicy.Rejection.NON_MONOTONIC_TIMESTAMP),
            result
        )
    }

    @Test
    fun `rejects a fix arriving too soon after the previous as a duplicate`() {
        val previous = point(1_000L, 10.0, 20.0)
        val candidate = point(1_000L + RidePointQualityPolicy.MIN_INTERVAL_MS - 1, 10.0, 20.0)
        val result = RidePointQualityPolicy.evaluate(candidate, previous)
        assertEquals(
            RidePointQualityPolicy.Result.Rejected(RidePointQualityPolicy.Rejection.DUPLICATE),
            result
        )
    }

    @Test
    fun `accepts a fix arriving exactly at the minimum interval`() {
        val previous = point(1_000L, 10.0, 20.0)
        val candidate = point(1_000L + RidePointQualityPolicy.MIN_INTERVAL_MS, 10.0001, 20.0001)
        val result = RidePointQualityPolicy.evaluate(candidate, previous)
        assertEquals(RidePointQualityPolicy.Result.Accepted, result)
    }

    @Test
    fun `rejects an impossible jump between consecutive fixes`() {
        // ~111km apart (1 degree of latitude) one second later - far beyond any plausible speed.
        val previous = point(1_000L, 0.0, 0.0)
        val candidate = point(2_000L, 1.0, 0.0)
        val result = RidePointQualityPolicy.evaluate(candidate, previous)
        assertEquals(
            RidePointQualityPolicy.Result.Rejected(RidePointQualityPolicy.Rejection.IMPOSSIBLE_JUMP),
            result
        )
    }

    @Test
    fun `accepts a plausible motorcycle-speed leg`() {
        // ~27.5m in one second is ~99 km/h - a real, plausible motorcycle speed.
        val previous = point(1_000L, 0.0, 0.0)
        val candidate = point(2_000L, 0.000247, 0.0)
        val result = RidePointQualityPolicy.evaluate(candidate, previous)
        assertEquals(RidePointQualityPolicy.Result.Accepted, result)
    }

    @Test
    fun `rejects a fix whose own reported speed is implausible`() {
        val previous = point(1_000L, 0.0, 0.0)
        val candidate = point(
            2_000L, 0.0001, 0.0,
            speed = RidePointQualityPolicy.MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND + 1f
        )
        val result = RidePointQualityPolicy.evaluate(candidate, previous)
        assertEquals(
            RidePointQualityPolicy.Result.Rejected(RidePointQualityPolicy.Rejection.IMPLAUSIBLE_SPEED),
            result
        )
    }

    @Test
    fun `implausible reported speed on the very first point is still rejected`() {
        val candidate = point(1_000L, 0.0, 0.0, speed = RidePointQualityPolicy.MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND + 1f)
        val result = RidePointQualityPolicy.evaluate(candidate, previousAccepted = null)
        assertEquals(
            RidePointQualityPolicy.Result.Rejected(RidePointQualityPolicy.Rejection.IMPLAUSIBLE_SPEED),
            result
        )
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
