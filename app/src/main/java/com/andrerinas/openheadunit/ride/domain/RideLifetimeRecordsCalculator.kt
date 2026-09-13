package com.andrerinas.openheadunit.ride.domain

import java.util.Calendar
import java.util.TimeZone

/**
 * Computes [RideLifetimeRecords] from every finished [Ride] plus its raw samples. Pure Kotlin (no
 * Room/Android dependency) - the caller (a ViewModel) is responsible for loading
 * [RideRepository.observeRideHistory]'s rides and [RideRepository.rawSamplesForRide] for each one,
 * this only ever touches the plain domain types.
 *
 * Reuses [RouteStatisticsCalculator] (max speed, average moving speed) and [RideSegmenter]
 * (continuous-ride duration/distance) rather than redefining "moving"/"stopped" a third way - the
 * whole point of building those first was for records to inherit their now-validated behavior.
 */
object RideLifetimeRecordsCalculator {

    fun compute(
        rides: List<Ride>,
        samplesByRideId: Map<Long, List<RideRawSample>>,
        home: SavedPlace?,
    ): RideLifetimeRecords {
        var longestTripByDistance: RideRecordEntry? = null
        var longestTripByDuration: RideRecordEntry? = null
        var longestContinuousByDuration: RideRecordEntry? = null
        var longestContinuousByDistance: RideRecordEntry? = null
        var highestSpeed: RideRecordEntry? = null
        var highestAvgMovingSpeed: RideRecordEntry? = null
        var farthestFromHome: RideRecordEntry? = null
        var totalDistance = 0.0

        val dayTotals = LinkedHashMap<Long, DayDistanceRecord>()
        val monthTotals = LinkedHashMap<Long, MonthDistanceRecord>()

        for (ride in rides) {
            totalDistance += ride.distanceMeters

            longestTripByDistance = maxByValue(
                longestTripByDistance, ride.distanceMeters, ride.id, ride.startTimestampMs
            )
            longestTripByDuration = maxByValue(
                longestTripByDuration, ride.durationMs.toDouble(), ride.id, ride.startTimestampMs
            )

            accumulateDayAndMonth(dayTotals, monthTotals, ride)

            val samples = samplesByRideId[ride.id].orEmpty()
            if (samples.isEmpty()) continue
            val stats = RouteStatisticsCalculator.compute(samples)

            highestSpeed = maxByValue(highestSpeed, stats.maxSpeedMetersPerSecond, ride.id, ride.startTimestampMs)
            if (stats.movingDurationMs > 0) {
                highestAvgMovingSpeed = maxByValue(
                    highestAvgMovingSpeed, stats.averageMovingSpeedMetersPerSecond, ride.id, ride.startTimestampMs
                )
            }

            val acceptedPoints = samples.filter { it.accepted }.map { it.point }
            for (segment in RideSegmenter.segment(acceptedPoints)) {
                if (segment.type != RideSegmentType.MOVING) continue
                longestContinuousByDuration = maxByValue(
                    longestContinuousByDuration, segment.durationMs.toDouble(), ride.id, segment.startTimestampMs
                )
                longestContinuousByDistance = maxByValue(
                    longestContinuousByDistance, segment.distanceMeters, ride.id, segment.startTimestampMs
                )
            }

            if (home != null) {
                for (point in acceptedPoints) {
                    val distanceFromHome = RideMetrics.haversineMeters(
                        home.latitude, home.longitude, point.latitude, point.longitude
                    )
                    farthestFromHome = maxByValue(farthestFromHome, distanceFromHome, ride.id, point.timestampMs)
                }
            }
        }

        return RideLifetimeRecords(
            longestTripByDistance = longestTripByDistance,
            longestTripByDuration = longestTripByDuration,
            longestContinuousRideByDuration = longestContinuousByDuration,
            longestContinuousRideByDistance = longestContinuousByDistance,
            highestRecordedSpeed = highestSpeed,
            highestAverageMovingSpeed = highestAvgMovingSpeed,
            mostDistanceInADay = dayTotals.values.maxByOrNull { it.distanceMeters },
            mostDistanceInAMonth = monthTotals.values.maxByOrNull { it.distanceMeters },
            farthestPointFromHome = farthestFromHome,
            totalLifetimeDistanceMeters = totalDistance,
            totalRideCount = rides.size,
        )
    }

    private fun maxByValue(current: RideRecordEntry?, value: Double, rideId: Long, achievedAtMs: Long): RideRecordEntry =
        if (current == null || value > current.value) RideRecordEntry(rideId, value, achievedAtMs) else current

    private fun accumulateDayAndMonth(
        dayTotals: MutableMap<Long, DayDistanceRecord>,
        monthTotals: MutableMap<Long, MonthDistanceRecord>,
        ride: Ride,
    ) {
        val calendar = Calendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = ride.startTimestampMs
        }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        // A stable per-device-local-day key: (year * 400 + dayOfYear) is unique enough across any
        // realistic ride history without needing java.time (this project targets minSdk 16).
        val dayKey = year * 400L + calendar.get(Calendar.DAY_OF_YEAR)
        val monthKey = year * 12L + month

        val existingDay = dayTotals[dayKey]
        dayTotals[dayKey] = DayDistanceRecord(
            distanceMeters = (existingDay?.distanceMeters ?: 0.0) + ride.distanceMeters,
            sampleTimestampMs = ride.startTimestampMs,
        )

        val existingMonth = monthTotals[monthKey]
        monthTotals[monthKey] = MonthDistanceRecord(
            year = year,
            month = month,
            distanceMeters = (existingMonth?.distanceMeters ?: 0.0) + ride.distanceMeters,
            sampleTimestampMs = ride.startTimestampMs,
        )
    }
}
