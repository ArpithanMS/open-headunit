package com.andrerinas.openheadunit.ride.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSpeedColorScaleTest {

    private val palette = RouteSpeedPalette(
        slowest = 0xFFFF0000.toInt(),
        belowAverage = 0xFFFF8800.toInt(),
        average = 0xFFFFFF00.toInt(),
        aboveAverage = 0xFF00FF00.toInt(),
        fast = 0xFF00FFFF.toInt(),
        fastest = 0xFF8800FF.toInt(),
    )

    @Test
    fun `breakpoints is null for an empty segment list`() {
        assertNull(RouteSpeedColorScale.breakpoints(emptyList()))
    }

    @Test
    fun `breakpoints computes nearest-rank percentiles over one hundred evenly spaced speeds`() {
        val segments = (1..100).map { segment(speedMetersPerSecond = it.toDouble()) }
        val bp = RouteSpeedColorScale.breakpoints(segments)!!
        assertEquals(16.0, bp.p15, 0.0)
        assertEquals(41.0, bp.p40, 0.0)
        assertEquals(60.0, bp.p60, 0.0)
        assertEquals(85.0, bp.p85, 0.0)
        assertEquals(95.0, bp.p95, 0.0)
        assertEquals(100.0, bp.max, 0.0)
    }

    @Test
    fun `hasMeaningfulSpread is true when speeds actually vary`() {
        val segments = (1..100).map { segment(speedMetersPerSecond = it.toDouble()) }
        val bp = RouteSpeedColorScale.breakpoints(segments)!!
        assertTrue(bp.hasMeaningfulSpread)
    }

    @Test
    fun `hasMeaningfulSpread is false for a uniform-speed ride`() {
        val segments = List(10) { segment(speedMetersPerSecond = 12.5) }
        val bp = RouteSpeedColorScale.breakpoints(segments)!!
        assertFalse(bp.hasMeaningfulSpread)
    }

    @Test
    fun `colorForSpeed lands exactly on each palette color at its own band boundary`() {
        val segments = (1..100).map { segment(speedMetersPerSecond = it.toDouble()) }
        val bp = RouteSpeedColorScale.breakpoints(segments)!!

        assertEquals(palette.slowest, RouteSpeedColorScale.colorForSpeed(bp.p15, bp, palette))
        assertEquals(palette.belowAverage, RouteSpeedColorScale.colorForSpeed(bp.p40, bp, palette))
        assertEquals(palette.average, RouteSpeedColorScale.colorForSpeed(bp.p60, bp, palette))
        assertEquals(palette.aboveAverage, RouteSpeedColorScale.colorForSpeed(bp.p85, bp, palette))
        assertEquals(palette.fast, RouteSpeedColorScale.colorForSpeed(bp.p95, bp, palette))
        assertEquals(palette.fastest, RouteSpeedColorScale.colorForSpeed(bp.max, bp, palette))
    }

    @Test
    fun `colorForSpeed below p15 also clamps to the slowest color, not something darker`() {
        val segments = (1..100).map { segment(speedMetersPerSecond = it.toDouble()) }
        val bp = RouteSpeedColorScale.breakpoints(segments)!!
        assertEquals(palette.slowest, RouteSpeedColorScale.colorForSpeed(-5.0, bp, palette))
    }

    @Test
    fun `blendArgb at fraction zero and one returns the endpoints exactly`() {
        val from = 0xFF112233.toInt()
        val to = 0xFF445566.toInt()
        assertEquals(from, RouteSpeedColorScale.blendArgb(from, to, 0f))
        assertEquals(to, RouteSpeedColorScale.blendArgb(from, to, 1f))
    }

    @Test
    fun `blendArgb clamps fractions outside zero to one`() {
        val from = 0xFF112233.toInt()
        val to = 0xFF445566.toInt()
        assertEquals(from, RouteSpeedColorScale.blendArgb(from, to, -1f))
        assertEquals(to, RouteSpeedColorScale.blendArgb(from, to, 2f))
    }

    @Test
    fun `blendArgb at fraction one half averages each channel`() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        // Every channel from 0 to 255 at the midpoint rounds to 128 (Math.round-style half-up).
        assertEquals(0xFF808080.toInt(), RouteSpeedColorScale.blendArgb(black, white, 0.5f))
    }

    private fun segment(speedMetersPerSecond: Double) = RouteSpeedSegment(
        start = RidePoint(timestampMs = 0L, latitude = 0.0, longitude = 0.0, accuracyMeters = 5f),
        end = RidePoint(timestampMs = 1_000L, latitude = 0.0, longitude = 0.0, accuracyMeters = 5f),
        distanceMeters = speedMetersPerSecond,
        speedMetersPerSecond = speedMetersPerSecond,
    )
}
