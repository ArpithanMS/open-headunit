package com.andrerinas.openheadunit.ride.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RideMetricsTest {

    @Test
    fun `empty point list yields zero distance and zero duration`() {
        assertEquals(0.0, RideMetrics.totalDistanceMeters(emptyList()), 0.0)
        assertEquals(0L, RideMetrics.durationMs(emptyList()))
    }

    @Test
    fun `single point yields zero distance and zero duration`() {
        val points = listOf(point(timestampMs = 1_000L, lat = 10.0, lon = 20.0))
        assertEquals(0.0, RideMetrics.totalDistanceMeters(points), 0.0)
        assertEquals(0L, RideMetrics.durationMs(points))
    }

    @Test
    fun `haversine distance from a point to itself is zero`() {
        val distance = RideMetrics.haversineMeters(12.34, 56.78, 12.34, 56.78)
        assertEquals(0.0, distance, 1e-6)
    }

    @Test
    fun `haversine distance between two known coordinates matches expected value within tolerance`() {
        // One degree of latitude apart on the same meridian (no east-west component) is a
        // great-circle arc of exactly (pi / 180) radians, so its length is analytically
        // R * (pi / 180) - no external reference distance needed.
        val expectedMeters = RideMetrics.EARTH_RADIUS_METERS * Math.PI / 180.0

        val distance = RideMetrics.haversineMeters(0.0, 0.0, 1.0, 0.0)

        assertEquals(expectedMeters, distance, 1.0)
    }

    @Test
    fun `total distance sums consecutive legs, not endpoint to endpoint`() {
        // A right-angle bend: 1 degree north, then 1 degree east from there. The sum of the two
        // legs must exceed the direct (shorter) distance from start to end.
        val points = listOf(
            point(timestampMs = 0L, lat = 0.0, lon = 0.0),
            point(timestampMs = 1_000L, lat = 1.0, lon = 0.0),
            point(timestampMs = 2_000L, lat = 1.0, lon = 1.0),
        )

        val legOne = RideMetrics.haversineMeters(0.0, 0.0, 1.0, 0.0)
        val legTwo = RideMetrics.haversineMeters(1.0, 0.0, 1.0, 1.0)
        val directEndToEnd = RideMetrics.haversineMeters(0.0, 0.0, 1.0, 1.0)

        val total = RideMetrics.totalDistanceMeters(points)

        assertEquals(legOne + legTwo, total, 1e-6)
        assertTrueDistanceExceeds(total, directEndToEnd)
    }

    @Test
    fun `total distance over out-of-order points still sums consecutive pairs as given`() {
        val chronological = listOf(
            point(timestampMs = 0L, lat = 0.0, lon = 0.0),
            point(timestampMs = 1_000L, lat = 1.0, lon = 0.0),
            point(timestampMs = 2_000L, lat = 2.0, lon = 0.0),
        )
        val outOfOrder = listOf(chronological[1], chronological[0], chronological[2])

        val chronologicalTotal = RideMetrics.totalDistanceMeters(chronological)
        val outOfOrderTotal = RideMetrics.totalDistanceMeters(outOfOrder)

        // Same points, different order -> a different sum of legs. This pins that
        // totalDistanceMeters never reorders its input before summing.
        assertTrueDistanceExceeds(outOfOrderTotal, chronologicalTotal)
    }

    @Test
    fun `duration is the last timestamp minus the first, regardless of order`() {
        val forward = listOf(
            point(timestampMs = 1_000L, lat = 0.0, lon = 0.0),
            point(timestampMs = 5_000L, lat = 0.0, lon = 0.0),
        )
        assertEquals(4_000L, RideMetrics.durationMs(forward))

        val reversed = listOf(
            point(timestampMs = 5_000L, lat = 0.0, lon = 0.0),
            point(timestampMs = 1_000L, lat = 0.0, lon = 0.0),
        )
        // Intentionally negative: out-of-order input is a caller bug meant to stay visible,
        // not something this function clamps or corrects.
        assertEquals(-4_000L, RideMetrics.durationMs(reversed))
    }

    @Test
    fun `duration ignores points in between and only looks at the first and last`() {
        val points = listOf(
            point(timestampMs = 1_000L, lat = 0.0, lon = 0.0),
            point(timestampMs = 999_999_999L, lat = 0.0, lon = 0.0),
            point(timestampMs = 9_000L, lat = 0.0, lon = 0.0),
        )
        assertEquals(8_000L, RideMetrics.durationMs(points))
    }

    private fun point(timestampMs: Long, lat: Double, lon: Double) = RidePoint(
        timestampMs = timestampMs,
        latitude = lat,
        longitude = lon,
        accuracyMeters = 5f,
    )

    private fun assertTrueDistanceExceeds(larger: Double, smaller: Double) {
        org.junit.Assert.assertTrue(
            "expected $larger to exceed $smaller",
            larger > smaller
        )
    }
}
