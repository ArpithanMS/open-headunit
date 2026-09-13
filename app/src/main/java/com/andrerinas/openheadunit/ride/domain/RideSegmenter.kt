package com.andrerinas.openheadunit.ride.domain

/**
 * Splits a ride's already-recorded route into [RideSegment]s - MOVING vs. a **meaningful** STOPPED
 * pause - retrospective analysis of real GPS samples, never a live/predictive decision (that's
 * [AutoRideStateMachine]'s job, a completely different problem: this runs once, after a ride is
 * already fully recorded, and can look both forward and backward through the whole route).
 *
 * The core problem this solves, from the user's own product direction: `speed == 0` is not a stop.
 * A traffic light, crawling traffic, or wandering a few metres around a parking spot must NOT
 * fragment one continuous ride into a dozen tiny ones - only a stop that is both stationary *and*
 * sustained for [Config.meaningfulStopDurationMs] counts. And once a stop has started, only a
 * clearly sustained departure ends it - a GPS jitter blip or a few metres of repositioning must
 * not "resume" a ride that never actually left.
 *
 * Algorithm (single left-to-right sweep, each pointer only ever moves forward):
 *  1. From index `i`, grow a candidate stop cluster `i..j` for as long as every point stays within
 *     [Config.stationaryRadiusMeters] of `points[i]` (the cluster's anchor).
 *  2. If `points[j].timestampMs - points[i].timestampMs >= [Config.meaningfulStopDurationMs]`,
 *     `i..j` is a confirmed stop. Extend `j` further past the radius while any excursions beyond
 *     it stay within [Config.resumeDisplacementMeters] of the anchor - absorbing exactly the
 *     "slight movement should not reset the timer" case the user described (a few metres out and
 *     back is still the same stop). The moment a point is clearly beyond `radius +
 *     resumeDisplacementMeters` from the anchor, that is a real, sustained departure and the stop
 *     ends there.
 *  3. If the cluster never reaches the duration threshold, it was not a meaningful stop (a traffic
 *     light) - advance `i` by one and keep scanning; those points stay inside the surrounding
 *     MOVING segment.
 *
 * [ALGORITHM_VERSION] exists because this logic will keep improving as real ride data accumulates
 * (the user's own point) - segments are always derived from the underlying accepted [RidePoint]s,
 * never treated as permanent stored truth, so a future version can re-segment historical rides
 * rather than being stuck with whatever a rougher first pass produced.
 */
object RideSegmenter {

    const val ALGORITHM_VERSION = 1

    data class Config(
        /** How far a point may sit from a stop's anchor and still count as "the same place". An
         *  initial/tunable placeholder (this session's own estimate, not measured field data) -
         *  bigger than typical GPS jitter/accuracy noise, small enough to distinguish "stopped
         *  right here" from "already riding away". */
        val stationaryRadiusMeters: Double = 60.0,
        /** How long a stationary cluster must last before it counts as a meaningful stop rather
         *  than a traffic light or brief slowdown. Initial/tunable placeholder - the user's own
         *  examples (a 1-minute traffic-light stop stays part of the ride, an 18-minute tea break
         *  ends it) both sit comfortably on either side of this. */
        val meaningfulStopDurationMs: Long = 5 * 60_000L,
        /** Once inside a confirmed stop, a point beyond `stationaryRadiusMeters +
         *  resumeDisplacementMeters` from the anchor is a real, sustained departure, not GPS
         *  jitter or repositioning within the same parking spot. Initial/tunable placeholder. */
        val resumeDisplacementMeters: Double = 80.0,
    )

    fun segment(points: List<RidePoint>, config: Config = Config()): List<RideSegment> {
        if (points.size < 2) return emptyList()

        val segments = mutableListOf<RideSegment>()
        var movingStart = 0
        var i = 0

        while (i < points.size - 1) {
            var j = i
            while (j + 1 < points.size && distanceMeters(points[i], points[j + 1]) <= config.stationaryRadiusMeters) {
                j++
            }

            val clusterDurationMs = points[j].timestampMs - points[i].timestampMs
            if (clusterDurationMs < config.meaningfulStopDurationMs) {
                // Not sustained enough to be a meaningful stop (a traffic light) - these points
                // stay inside whichever MOVING segment ends up containing them.
                i++
                continue
            }

            var k = j
            val departureRadius = config.stationaryRadiusMeters + config.resumeDisplacementMeters
            while (k + 1 < points.size && distanceMeters(points[i], points[k + 1]) <= departureRadius) {
                k++
            }

            if (movingStart < i) {
                segments += buildMovingSegment(points, movingStart, i)
            }
            segments += buildStoppedSegment(points, i, k)
            movingStart = k
            i = k + 1
        }

        if (movingStart < points.size - 1) {
            segments += buildMovingSegment(points, movingStart, points.size - 1)
        }

        return segments
    }

    private fun buildMovingSegment(points: List<RidePoint>, startIndex: Int, endIndex: Int): RideSegment {
        var distance = 0.0
        for (idx in startIndex until endIndex) {
            distance += distanceMeters(points[idx], points[idx + 1])
        }
        val start = points[startIndex]
        val end = points[endIndex]
        return RideSegment(
            type = RideSegmentType.MOVING,
            startTimestampMs = start.timestampMs,
            endTimestampMs = end.timestampMs,
            distanceMeters = distance,
            startLatitude = start.latitude, startLongitude = start.longitude,
            endLatitude = end.latitude, endLongitude = end.longitude,
        )
    }

    private fun buildStoppedSegment(points: List<RidePoint>, startIndex: Int, endIndex: Int): RideSegment {
        val start = points[startIndex]
        val end = points[endIndex]
        return RideSegment(
            type = RideSegmentType.STOPPED,
            startTimestampMs = start.timestampMs,
            endTimestampMs = end.timestampMs,
            distanceMeters = 0.0,
            startLatitude = start.latitude, startLongitude = start.longitude,
            endLatitude = end.latitude, endLongitude = end.longitude,
        )
    }

    private fun distanceMeters(a: RidePoint, b: RidePoint): Double =
        RideMetrics.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
}
