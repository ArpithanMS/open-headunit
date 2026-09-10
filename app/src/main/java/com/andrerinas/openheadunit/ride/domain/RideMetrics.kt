package com.andrerinas.openheadunit.ride.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distance and duration over a recorded ride. Pure functions over [RidePoint] lists - no Android
 * dependency, no I/O.
 */
object RideMetrics {

    /** Mean Earth radius in meters, per the standard Haversine approximation. */
    const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Great-circle distance between two coordinates, in meters, via the Haversine formula.
     *
     * This exists only because the one distance function already in this codebase,
     * GeofenceLocation.distanceTo, hard-depends on android.location.Location and so is unusable
     * from a pure-Kotlin type like [RidePoint]. Haversine's ~0.3-0.5% error against an ellipsoidal
     * model (what Location.distanceTo uses) is a deliberate tradeoff: adequate for aggregate ride
     * distance, not a claim of survey-grade precision.
     */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLatRad = Math.toRadians(lat2 - lat1)
        val deltaLonRad = Math.toRadians(lon2 - lon1)

        val a = sin(deltaLatRad / 2) * sin(deltaLatRad / 2) +
            cos(lat1Rad) * cos(lat2Rad) * sin(deltaLonRad / 2) * sin(deltaLonRad / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Total distance covered, in meters: the sum of the great-circle distance between each
     * consecutive pair of [points], taken strictly in list order. An empty or single-point list
     * has nothing to sum and returns 0.0.
     *
     * Does not sort or otherwise validate ordering - like every pure function elsewhere in this
     * codebase (see e.g. LocationHolder, which only ever picks the best of a handful of
     * candidates and never reorders a caller's list), this trusts the shape it's given.
     * RideTrackingService will append live fixes serially, so the list is chronological by
     * construction; a future Timeline importer is responsible for sorting before calling in. Out-
     * of-order input inflating the total (by summing zig-zag legs instead of the true path) is
     * meant to be a visible symptom of a caller bug, not something silently corrected here.
     */
    fun totalDistanceMeters(points: List<RidePoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            total += haversineMeters(
                previous.latitude, previous.longitude,
                current.latitude, current.longitude
            )
        }
        return total
    }

    /**
     * Wall-clock span of the ride, in milliseconds: the last point's timestamp minus the first's,
     * regardless of what's in between. An empty or single-point list has no span and returns 0L.
     *
     * Same no-sort stance as [totalDistanceMeters]: points passed out of chronological order
     * yield a negative duration rather than being silently clamped or reordered, so the caller
     * bug that produced them stays visible.
     */
    fun durationMs(points: List<RidePoint>): Long {
        if (points.size < 2) return 0L
        return points.last().timestampMs - points.first().timestampMs
    }
}
