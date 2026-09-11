package com.andrerinas.openheadunit.ride.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSpeedGradientTest {

    @Test
    fun `empty points yield no stops`() {
        assertEquals(emptyList<RouteSpeedGradientStop>(), RouteSpeedGradient.buildStops(emptyList(), emptyList(), maxStops = 256))
    }

    @Test
    fun `a single point yields no stops`() {
        val points = listOf(point(0L, 0.0, 0.0))
        assertEquals(emptyList<RouteSpeedGradientStop>(), RouteSpeedGradient.buildStops(points, emptyList(), maxStops = 256))
    }

    @Test
    fun `maxStops under two yields no stops`() {
        val points = listOf(point(0L, 0.0, 0.0), point(1_000L, 0.000247, 0.0))
        val segments = RouteSpeedSegmenter.segment(points)
        assertEquals(emptyList<RouteSpeedGradientStop>(), RouteSpeedGradient.buildStops(points, segments, maxStops = 1))
    }

    @Test
    fun `a route that never moved yields no stops rather than dividing by zero total distance`() {
        val points = listOf(point(0L, 10.0, 20.0), point(1_000L, 10.0, 20.0), point(2_000L, 10.0, 20.0))
        val segments = RouteSpeedSegmenter.segment(points)
        assertEquals(emptyList<RouteSpeedGradientStop>(), RouteSpeedGradient.buildStops(points, segments, maxStops = 256))
    }

    @Test
    fun `two points yield exactly a start stop at progress zero and an end stop at progress one`() {
        val points = listOf(point(0L, 0.0, 0.0), point(1_000L, 0.000247, 0.0))
        val segments = RouteSpeedSegmenter.segment(points)
        val stops = RouteSpeedGradient.buildStops(points, segments, maxStops = 256)

        assertEquals(2, stops.size)
        assertEquals(0.0, stops[0].progress, 0.0)
        assertEquals(1.0, stops.last().progress, 0.0)
        // Only one leg exists, so both ends of it carry that leg's own speed.
        assertEquals(segments[0].speedMetersPerSecond, stops[0].speedMetersPerSecond, 1e-9)
        assertEquals(segments[0].speedMetersPerSecond, stops.last().speedMetersPerSecond, 1e-9)
    }

    @Test
    fun `stop progress is strictly increasing`() {
        val points = (0 until 20).map { i -> point(i * 1_000L, i * 0.0001, 0.0) }
        val segments = RouteSpeedSegmenter.segment(points)
        val stops = RouteSpeedGradient.buildStops(points, segments, maxStops = 256)

        for (i in 1 until stops.size) {
            assertTrue("stop $i progress must exceed stop ${i - 1}", stops[i].progress > stops[i - 1].progress)
        }
        assertEquals(0.0, stops.first().progress, 0.0)
        assertEquals(1.0, stops.last().progress, 0.0)
    }

    @Test
    fun `a long route is thinned to at most maxStops while still spanning the full route`() {
        // 1000 points, far more than a small maxStops cap - this is the "12,000+ point ride"
        // scenario the gradient expression has to stay a reasonable size for.
        val points = (0 until 1000).map { i -> point(i * 1_000L, i * 0.00001, 0.0) }
        val segments = RouteSpeedSegmenter.segment(points)
        val stops = RouteSpeedGradient.buildStops(points, segments, maxStops = 10)

        assertTrue("expected at most 10 stops, got ${stops.size}", stops.size <= 10)
        assertEquals(0.0, stops.first().progress, 0.0)
        assertEquals(1.0, stops.last().progress, 0.0)
        for (i in 1 until stops.size) {
            assertTrue(stops[i].progress > stops[i - 1].progress)
        }
    }

    private fun point(timestampMs: Long, lat: Double, lon: Double) = RidePoint(
        timestampMs = timestampMs,
        latitude = lat,
        longitude = lon,
        accuracyMeters = 5f,
    )
}
