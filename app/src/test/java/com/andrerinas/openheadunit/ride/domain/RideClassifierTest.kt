package com.andrerinas.openheadunit.ride.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideClassifierTest {

    private val home = SavedPlace(SavedPlaceRole.HOME, latitude = 10.0, longitude = 20.0, radiusMeters = 100f)
    private val work = SavedPlace(SavedPlaceRole.WORK, latitude = 10.01, longitude = 20.01, radiusMeters = 100f)

    @Test
    fun `a ride with no persisted coordinates is not classified`() {
        val ride = ride(startLat = null, startLon = null, endLat = null, endLon = null)
        assertNull(RideClassifier.classify(ride, listOf(home, work)))
    }

    @Test
    fun `no saved places yields no classification`() {
        val ride = ride(startLat = 10.0, startLon = 20.0, endLat = 10.01, endLon = 20.01)
        assertNull(RideClassifier.classify(ride, emptyList()))
    }

    @Test
    fun `a ride starting at home and ending at work is classified both ways`() {
        val ride = ride(startLat = 10.0, startLon = 20.0, endLat = 10.01, endLon = 20.01)
        val result = RideClassifier.classify(ride, listOf(home, work))
        assertEquals(RideClassification(SavedPlaceRole.HOME, SavedPlaceRole.WORK), result)
    }

    @Test
    fun `a ride starting and ending at home is classified as home to home`() {
        val ride = ride(startLat = 10.0, startLon = 20.0, endLat = 10.0, endLon = 20.0)
        val result = RideClassifier.classify(ride, listOf(home, work))
        assertEquals(RideClassification(SavedPlaceRole.HOME, SavedPlaceRole.HOME), result)
    }

    @Test
    fun `a ride with only one endpoint near a saved place still classifies that endpoint`() {
        val ride = ride(startLat = 10.0, startLon = 20.0, endLat = 50.0, endLon = 90.0)
        val result = RideClassifier.classify(ride, listOf(home, work))
        assertEquals(RideClassification(SavedPlaceRole.HOME, null), result)
    }

    @Test
    fun `a ride with neither endpoint near a saved place is not classified`() {
        val ride = ride(startLat = 50.0, startLon = 90.0, endLat = 51.0, endLon = 91.0)
        assertNull(RideClassifier.classify(ride, listOf(home, work)))
    }

    @Test
    fun `an endpoint just outside the radius does not classify`() {
        // ~111km per degree of latitude - 0.01 deg is ~1.1km, well past a 100m radius.
        val farRide = ride(startLat = 10.01, startLon = 20.0, endLat = 50.0, endLon = 90.0)
        assertNull(RideClassifier.classify(farRide, listOf(home)))
    }

    @Test
    fun `a ride never travels through unrelated intermediate points - only start and end matter`() {
        // Classification only ever looks at persisted start/end coordinates - there's no route
        // geometry involved, so this is really just re-confirming classify()'s inputs are exactly
        // ride.start/endLatitude/Longitude and nothing else.
        val ride = ride(startLat = 10.0, startLon = 20.0, endLat = 10.01, endLon = 20.01)
        assertEquals(
            RideClassification(SavedPlaceRole.HOME, SavedPlaceRole.WORK),
            RideClassifier.classify(ride, listOf(home, work)),
        )
    }

    private fun ride(
        startLat: Double?,
        startLon: Double?,
        endLat: Double?,
        endLon: Double?,
    ) = Ride(
        id = 1L,
        startTimestampMs = 0L,
        endTimestampMs = 1_000L,
        state = RideState.FINISHED,
        distanceMeters = 0.0,
        durationMs = 1_000L,
        startLatitude = startLat,
        startLongitude = startLon,
        endLatitude = endLat,
        endLongitude = endLon,
    )
}
