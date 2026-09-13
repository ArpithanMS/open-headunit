package com.andrerinas.openheadunit.ride.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideSegmenterTest {

    // Round, easy-to-reason-about thresholds rather than the real defaults.
    private val config = RideSegmenter.Config(
        stationaryRadiusMeters = 50.0,
        meaningfulStopDurationMs = 5 * 60_000L,
        resumeDisplacementMeters = 80.0,
    )

    @Test
    fun `fewer than two points yields no segments`() {
        assertEquals(emptyList<RideSegment>(), RideSegmenter.segment(emptyList(), config))
        assertEquals(emptyList<RideSegment>(), RideSegmenter.segment(listOf(point(0L, metersNorth = 0.0))), )
    }

    @Test
    fun `a continuous ride with no stops is a single MOVING segment`() {
        // One point every second for 10 minutes at a steady clip - never stationary.
        val points = (0..600).map { i -> point(atMs = i * 1_000L, metersNorth = i * 10.0) }

        val segments = RideSegmenter.segment(points, config)

        assertEquals(1, segments.size)
        assertEquals(RideSegmentType.MOVING, segments[0].type)
        assertEquals(points.first().timestampMs, segments[0].startTimestampMs)
        assertEquals(points.last().timestampMs, segments[0].endTimestampMs)
    }

    @Test
    fun `a one-minute traffic-light stop does not split the ride`() {
        val points = ridingThenStoppedThenRiding(stopDurationMs = 60_000L)

        val segments = RideSegmenter.segment(points, config)

        // Still one continuous MOVING segment spanning the whole thing - a stop this short isn't
        // "meaningful" per the user's own traffic-light example.
        assertEquals(1, segments.size)
        assertEquals(RideSegmentType.MOVING, segments[0].type)
    }

    @Test
    fun `an eighteen-minute tea break splits the ride into moving, stopped, moving`() {
        val points = ridingThenStoppedThenRiding(stopDurationMs = 18 * 60_000L)

        val segments = RideSegmenter.segment(points, config)

        assertEquals(3, segments.size)
        assertEquals(RideSegmentType.MOVING, segments[0].type)
        assertEquals(RideSegmentType.STOPPED, segments[1].type)
        assertEquals(RideSegmentType.MOVING, segments[2].type)
        assertEquals(0.0, segments[1].distanceMeters, 0.0)
        assertTrue("tea break duration should be >= 18 minutes", segments[1].durationMs >= 18 * 60_000L)
    }

    @Test
    fun `a twelve-minute petrol stop ends the continuous ride under default-scale thresholds`() {
        val points = ridingThenStoppedThenRiding(stopDurationMs = 12 * 60_000L)

        val segments = RideSegmenter.segment(points, config)

        assertEquals(3, segments.size)
        assertEquals(RideSegmentType.STOPPED, segments[1].type)
    }

    @Test
    fun `wandering a few metres within a stop does not fragment it into multiple stops`() {
        // 12:00 stopped, 12:05 moved 4m, 12:06 stopped, 12:10 moved 2m, 12:15 stopped - the user's
        // own parking-lot example. All well within stationaryRadiusMeters (50m), so this must
        // read as one continuous stop, not "resumed then stopped again" several times.
        val points = mutableListOf<RidePoint>()
        var t = 0L
        val anchor = 0.0
        repeat(20) { // steady stationary presence for 0..~19 minutes, with small jitter below
            points += point(atMs = t, metersNorth = anchor + (it % 3) * 3.0) // 0m/3m/6m jitter
            t += 60_000L
        }

        val segments = RideSegmenter.segment(points, config)

        assertEquals(1, segments.size)
        assertEquals(RideSegmentType.STOPPED, segments[0].type)
        assertEquals(points.first().timestampMs, segments[0].startTimestampMs)
        assertEquals(points.last().timestampMs, segments[0].endTimestampMs)
    }

    @Test
    fun `a sustained departure well beyond the resume displacement ends the stop`() {
        val points = ridingThenStoppedThenRiding(stopDurationMs = 10 * 60_000L)

        val segments = RideSegmenter.segment(points, config)

        assertEquals(3, segments.size)
        // The second MOVING segment must start at (or after) the point that actually left the
        // stationary+resume radius, not drift back into the stop.
        val resumedMoving = segments[2]
        assertEquals(RideSegmentType.MOVING, resumedMoving.type)
        assertTrue(resumedMoving.distanceMeters > 0.0)
    }

    @Test
    fun `a MOVING segment's distance sums consecutive legs, not a straight line between endpoints`() {
        // A right-angle dogleg: 100m north, then 100m north again but offset east - the straight
        // line from start to end is shorter than the two legs summed.
        val points = listOf(
            point(atMs = 0L, metersNorth = 0.0, metersEast = 0.0),
            point(atMs = 10_000L, metersNorth = 100.0, metersEast = 0.0),
            point(atMs = 20_000L, metersNorth = 100.0, metersEast = 100.0),
        )

        val segments = RideSegmenter.segment(points, config)

        assertEquals(1, segments.size)
        assertTrue(
            "expected close to 200m of travel, was ${segments[0].distanceMeters}",
            segments[0].distanceMeters in 195.0..205.0
        )
    }

    /** Riding away, a stationary dwell of [stopDurationMs] at a fixed spot, then riding away again -
     *  the shared shape behind the traffic-light/tea-break/petrol-stop scenarios above. */
    private fun ridingThenStoppedThenRiding(stopDurationMs: Long): List<RidePoint> {
        val points = mutableListOf<RidePoint>()
        var t = 0L

        // Ride out for 5 minutes, one fix every 10s, ~20 km/h.
        val ridingLegs = 30
        repeat(ridingLegs) { i ->
            points += point(atMs = t, metersNorth = i * 55.0)
            t += 10_000L
        }
        val stopAnchor = (ridingLegs - 1) * 55.0

        // Stationary for the requested duration, one fix a minute.
        var stoppedElapsed = 0L
        while (stoppedElapsed < stopDurationMs) {
            points += point(atMs = t, metersNorth = stopAnchor)
            t += 60_000L
            stoppedElapsed += 60_000L
        }
        points += point(atMs = t, metersNorth = stopAnchor)
        t += 1_000L

        // Ride away again, well clear of the stop.
        repeat(30) { i ->
            t += 10_000L
            points += point(atMs = t, metersNorth = stopAnchor + 500.0 + i * 55.0)
        }

        return points
    }

    private fun point(atMs: Long, metersNorth: Double, metersEast: Double = 0.0): RidePoint {
        // ~111,320m per degree of latitude/longitude near the equator - precise enough for these
        // synthetic fixtures, no need to round-trip through RideMetrics.haversineMeters here.
        val lat = metersNorth / 111_320.0
        val lon = metersEast / 111_320.0
        return RidePoint(timestampMs = atMs, latitude = lat, longitude = lon, accuracyMeters = 5f)
    }
}
