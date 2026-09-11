package com.andrerinas.openheadunit.ride.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSpeedSegmenterTest {

    @Test
    fun `empty point list yields no segments`() {
        assertEquals(emptyList<RouteSpeedSegment>(), RouteSpeedSegmenter.segment(emptyList()))
    }

    @Test
    fun `single point yields no segments`() {
        val points = listOf(point(0L, 0.0, 0.0))
        assertEquals(emptyList<RouteSpeedSegment>(), RouteSpeedSegmenter.segment(points))
    }

    @Test
    fun `two points yield one segment with the leg's distance and implied speed`() {
        val start = point(0L, 0.0, 0.0)
        val end = point(1_000L, 0.000247, 0.0) // ~27.5m north, matches RouteStatisticsCalculatorTest's fixture
        val segments = RouteSpeedSegmenter.segment(listOf(start, end))

        assertEquals(1, segments.size)
        val expectedDistance = RideMetrics.haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude)
        assertEquals(expectedDistance, segments[0].distanceMeters, 1e-6)
        assertEquals(expectedDistance / 1.0, segments[0].speedMetersPerSecond, 1e-6)
    }

    @Test
    fun `three points yield two consecutive segments in order`() {
        val points = listOf(
            point(0L, 0.0, 0.0),
            point(1_000L, 0.000247, 0.0),
            point(3_000L, 0.000494, 0.0),
        )
        val segments = RouteSpeedSegmenter.segment(points)

        assertEquals(2, segments.size)
        assertEquals(points[0], segments[0].start)
        assertEquals(points[1], segments[0].end)
        assertEquals(points[1], segments[1].start)
        assertEquals(points[2], segments[1].end)
    }

    @Test
    fun `zero-distance leg yields zero speed`() {
        val points = listOf(point(0L, 10.0, 20.0), point(5_000L, 10.0, 20.0))
        val segments = RouteSpeedSegmenter.segment(points)
        assertEquals(0.0, segments[0].distanceMeters, 0.0)
        assertEquals(0.0, segments[0].speedMetersPerSecond, 0.0)
    }

    @Test
    fun `non-increasing timestamps yield zero speed rather than a divide-by-zero or negative speed`() {
        val sameTimestamp = RouteSpeedSegmenter.segment(listOf(point(1_000L, 0.0, 0.0), point(1_000L, 0.0001, 0.0)))
        assertEquals(0.0, sameTimestamp[0].speedMetersPerSecond, 0.0)

        val decreasingTimestamp = RouteSpeedSegmenter.segment(listOf(point(2_000L, 0.0, 0.0), point(1_000L, 0.0001, 0.0)))
        assertEquals(0.0, decreasingTimestamp[0].speedMetersPerSecond, 0.0)
    }

    @Test
    fun `a stop mixed with moving legs produces one zero-speed segment among the moving ones`() {
        val points = listOf(
            point(0L, 0.0, 0.0),
            point(1_000L, 0.000247, 0.0), // moving leg
            point(6_000L, 0.000247, 0.0), // stopped for 5s - no movement
            point(7_000L, 0.000494, 0.0), // moving again
        )
        val segments = RouteSpeedSegmenter.segment(points)

        assertEquals(3, segments.size)
        assertTrue("expected leg 0 to be moving", segments[0].speedMetersPerSecond > 0.0)
        assertEquals(0.0, segments[1].speedMetersPerSecond, 0.0)
        assertTrue("expected leg 2 to be moving", segments[2].speedMetersPerSecond > 0.0)
    }

    @Test
    fun `uniform speed across every leg yields identical speed values`() {
        val points = listOf(
            point(0L, 0.0, 0.0),
            point(1_000L, 0.000247, 0.0),
            point(2_000L, 0.000494, 0.0),
            point(3_000L, 0.000741, 0.0),
        )
        val segments = RouteSpeedSegmenter.segment(points)
        val speeds = segments.map { it.speedMetersPerSecond }
        assertEquals(speeds[0], speeds[1], 1e-6)
        assertEquals(speeds[1], speeds[2], 1e-6)
    }

    private fun point(timestampMs: Long, lat: Double, lon: Double) = RidePoint(
        timestampMs = timestampMs,
        latitude = lat,
        longitude = lon,
        accuracyMeters = 5f,
    )
}
