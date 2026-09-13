package com.andrerinas.openheadunit.ride.domain

/**
 * One "which ride/segment achieved this" record - [value]'s unit depends on which field of
 * [RideLifetimeRecords] holds it (meters, milliseconds, or metres/second), documented there.
 */
data class RideRecordEntry(
    val rideId: Long,
    val value: Double,
    val achievedAtMs: Long,
)

/** A single calendar day's (device-local) total distance - the day with the most riding. */
data class DayDistanceRecord(
    val distanceMeters: Double,
    /** A timestamp that actually falls within that day - enough for the UI to format a date
     *  string from, without this domain type needing its own date-formatting logic. */
    val sampleTimestampMs: Long,
)

/** A single calendar month's (device-local) total distance - the month with the most riding. */
data class MonthDistanceRecord(
    val year: Int,
    val month: Int,
    val distanceMeters: Double,
    val sampleTimestampMs: Long,
)

/**
 * Lifetime "personal records" derived entirely from recorded [Ride]s and their accepted points -
 * see [RideLifetimeRecordsCalculator]. Deliberately never persisted as its own stored truth (the
 * user's own explicit direction): computed fresh from Rides + accepted points + Home each time,
 * so a future improvement to [RideSegmenter] or [RouteStatisticsCalculator] is reflected
 * immediately rather than requiring a migration of stale stored numbers.
 */
data class RideLifetimeRecords(
    /** meters - [Ride.distanceMeters] of the single longest recorded Trip. */
    val longestTripByDistance: RideRecordEntry?,
    /** milliseconds - [Ride.durationMs] of the longest recorded Trip. */
    val longestTripByDuration: RideRecordEntry?,
    /** milliseconds - the longest single MOVING [RideSegment] across every ride: how long the
     *  rider kept moving before a genuinely meaningful stop, not the whole Trip's clock time. */
    val longestContinuousRideByDuration: RideRecordEntry?,
    /** meters - the longest single MOVING [RideSegment]'s distance across every ride. */
    val longestContinuousRideByDistance: RideRecordEntry?,
    /** meters/second - the fastest instantaneous/leg speed seen on any ride. */
    val highestRecordedSpeed: RideRecordEntry?,
    /** meters/second - the highest whole-ride average moving speed (distance / moving time). */
    val highestAverageMovingSpeed: RideRecordEntry?,
    val mostDistanceInADay: DayDistanceRecord?,
    val mostDistanceInAMonth: MonthDistanceRecord?,
    /** meters - the single farthest accepted GPS fix, across every ride, from the saved Home
     *  place - null if Home has never been set, never a fabricated distance. */
    val farthestPointFromHome: RideRecordEntry?,
    val totalLifetimeDistanceMeters: Double,
    val totalRideCount: Int,
)
