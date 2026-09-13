package com.andrerinas.openheadunit.ride.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RideLifetimeRecordsCalculatorTest {

    @Test
    fun `no rides yields all-null records and zero totals`() {
        val records = RideLifetimeRecordsCalculator.compute(emptyList(), emptyMap(), home = null)

        assertNull(records.longestTripByDistance)
        assertNull(records.longestTripByDuration)
        assertNull(records.longestContinuousRideByDuration)
        assertNull(records.longestContinuousRideByDistance)
        assertNull(records.highestRecordedSpeed)
        assertNull(records.highestAverageMovingSpeed)
        assertNull(records.mostDistanceInADay)
        assertNull(records.mostDistanceInAMonth)
        assertNull(records.farthestPointFromHome)
        assertEquals(0.0, records.totalLifetimeDistanceMeters, 0.0)
        assertEquals(0, records.totalRideCount)
    }

    @Test
    fun `longest trip by distance and by duration can point at different rides`() {
        // Ride A: long distance, short duration (a fast highway run).
        val rideA = ride(id = 1, distanceMeters = 100_000.0, durationMs = 3_600_000L, atDay(2026, Calendar.JANUARY, 1))
        // Ride B: shorter distance, but much longer duration (a slow, stop-heavy city ride).
        val rideB = ride(id = 2, distanceMeters = 20_000.0, durationMs = 7_200_000L, atDay(2026, Calendar.JANUARY, 2))

        val records = RideLifetimeRecordsCalculator.compute(listOf(rideA, rideB), emptyMap(), home = null)

        assertEquals(1L, records.longestTripByDistance?.rideId)
        assertEquals(100_000.0, records.longestTripByDistance?.value)
        assertEquals(2L, records.longestTripByDuration?.rideId)
        assertEquals(7_200_000.0, records.longestTripByDuration?.value)
        assertEquals(120_000.0, records.totalLifetimeDistanceMeters, 0.0)
        assertEquals(2, records.totalRideCount)
    }

    @Test
    fun `highest recorded speed and highest average moving speed are read from raw samples`() {
        val rideId = 1L
        val ride = ride(id = rideId, distanceMeters = 10_000.0, durationMs = 600_000L, atDay(2026, Calendar.JANUARY, 1))
        // Steady 20 m/s (72 km/h) for 10 legs, 10s apart, ~2000m total.
        val samples = (0..10).map { i ->
            RideRawSample.accepted(point(atMs = i * 10_000L, metersNorth = i * 200.0))
        }

        val records = RideLifetimeRecordsCalculator.compute(
            listOf(ride), mapOf(rideId to samples), home = null
        )

        assertTrue("expected ~20 m/s, was ${records.highestRecordedSpeed?.value}",
            records.highestRecordedSpeed!!.value in 19.0..21.0)
        assertTrue("expected ~20 m/s avg, was ${records.highestAverageMovingSpeed?.value}",
            records.highestAverageMovingSpeed!!.value in 19.0..21.0)
    }

    @Test
    fun `longest continuous ride reflects the moving segment, not the whole trip`() {
        val rideId = 1L
        val ride = ride(id = rideId, distanceMeters = 5_000.0, durationMs = 1_800_000L, atDay(2026, Calendar.JANUARY, 1))
        val samples = mutableListOf<RideRawSample>()
        var t = 0L
        // Ride 5 minutes.
        repeat(30) { i -> samples += RideRawSample.accepted(point(atMs = t, metersNorth = i * 50.0)); t += 10_000L }
        val stopAnchor = 29 * 50.0
        // Stop for 10 minutes (well past a meaningful-stop threshold).
        repeat(10) { samples += RideRawSample.accepted(point(atMs = t, metersNorth = stopAnchor)); t += 60_000L }
        // Ride again for 2 minutes.
        repeat(12) { i -> t += 10_000L; samples += RideRawSample.accepted(point(atMs = t, metersNorth = stopAnchor + 500.0 + i * 50.0)) }

        val records = RideLifetimeRecordsCalculator.compute(
            listOf(ride), mapOf(rideId to samples), home = null
        )

        // The longest continuous ride is the first (5-minute) moving leg, not the ~19-minute Trip.
        assertTrue(records.longestContinuousRideByDuration!!.value < ride.durationMs.toDouble())
        assertTrue(
            "expected close to 5 minutes, was ${records.longestContinuousRideByDuration!!.value}",
            records.longestContinuousRideByDuration!!.value in 280_000.0..300_000.0
        )
    }

    @Test
    fun `most distance in a day sums same-day rides across separate trips`() {
        val day1 = atDay(2026, Calendar.MARCH, 10)
        val day2 = atDay(2026, Calendar.MARCH, 11)
        val rideMorning = ride(id = 1, distanceMeters = 30_000.0, durationMs = 1_000L, day1 + 3_600_000L)
        val rideEvening = ride(id = 2, distanceMeters = 25_000.0, durationMs = 1_000L, day1 + 8 * 3_600_000L)
        val rideNextDay = ride(id = 3, distanceMeters = 40_000.0, durationMs = 1_000L, day2)

        val records = RideLifetimeRecordsCalculator.compute(
            listOf(rideMorning, rideEvening, rideNextDay), emptyMap(), home = null
        )

        // day1's two rides (30k + 25k = 55k) beat day2's single 40k ride.
        assertEquals(55_000.0, records.mostDistanceInADay?.distanceMeters)
    }

    @Test
    fun `most distance in a month sums rides across the same calendar month`() {
        val rideA = ride(id = 1, distanceMeters = 50_000.0, durationMs = 1_000L, atDay(2026, Calendar.APRIL, 1))
        val rideB = ride(id = 2, distanceMeters = 50_000.0, durationMs = 1_000L, atDay(2026, Calendar.APRIL, 20))
        val rideOtherMonth = ride(id = 3, distanceMeters = 70_000.0, durationMs = 1_000L, atDay(2026, Calendar.MAY, 1))

        val records = RideLifetimeRecordsCalculator.compute(
            listOf(rideA, rideB, rideOtherMonth), emptyMap(), home = null
        )

        assertEquals(100_000.0, records.mostDistanceInAMonth?.distanceMeters)
        assertEquals(Calendar.APRIL, records.mostDistanceInAMonth?.month)
    }

    @Test
    fun `farthest point from home is null when no home is set, and correct when one is`() {
        val rideId = 1L
        val ride = ride(id = rideId, distanceMeters = 1_000.0, durationMs = 60_000L, atDay(2026, Calendar.JANUARY, 1))
        val samples = listOf(
            RideRawSample.accepted(point(atMs = 0L, metersNorth = 0.0)),
            RideRawSample.accepted(point(atMs = 1_000L, metersNorth = 1_000.0)),
            RideRawSample.accepted(point(atMs = 2_000L, metersNorth = 5_000.0)),
        )

        val withoutHome = RideLifetimeRecordsCalculator.compute(listOf(ride), mapOf(rideId to samples), home = null)
        assertNull(withoutHome.farthestPointFromHome)

        val home = SavedPlace(SavedPlaceRole.HOME, latitude = 0.0, longitude = 0.0, radiusMeters = 100f)
        val withHome = RideLifetimeRecordsCalculator.compute(listOf(ride), mapOf(rideId to samples), home)
        assertTrue(
            "expected close to 5000m, was ${withHome.farthestPointFromHome?.value}",
            withHome.farthestPointFromHome!!.value in 4_900.0..5_100.0
        )
    }

    private fun ride(id: Long, distanceMeters: Double, durationMs: Long, startAtMs: Long) = Ride(
        id = id,
        startTimestampMs = startAtMs,
        endTimestampMs = startAtMs + durationMs,
        state = RideState.FINISHED,
        distanceMeters = distanceMeters,
        durationMs = durationMs,
    )

    private fun atDay(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun point(atMs: Long, metersNorth: Double, metersEast: Double = 0.0): RidePoint {
        val lat = metersNorth / 111_320.0
        val lon = metersEast / 111_320.0
        return RidePoint(timestampMs = atMs, latitude = lat, longitude = lon, accuracyMeters = 5f, speedMetersPerSecond = null)
    }
}
