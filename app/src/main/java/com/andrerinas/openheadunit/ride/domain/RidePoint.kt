package com.andrerinas.openheadunit.ride.domain

/**
 * One raw GPS sample recorded during a ride. Deliberately Android-free (no android.location
 * dependency) so it is reusable outside a live tracking session - a future Google Maps Timeline
 * importer, or a unit test, can construct one without an Android runtime.
 *
 * Field types mirror android.location.Location's getters (latitude/longitude as Double, accuracy/
 * speed/bearing as Float) so the future location-adapter layer that turns live fixes into these
 * is a trivial 1:1 field copy. The nullable fields stand in for Location.hasAltitude()/
 * hasSpeed()/hasBearing() - null means "this fix didn't carry that value", not "zero".
 *
 * No range validation on latitude/longitude/accuracy: like GeofenceLocation, the other lat/lon
 * data holder in this codebase, this type trusts its caller. Whether a fix is plausible is a
 * concern for whatever produces it (the future location adapter), not for this plain data holder.
 */
data class RidePoint(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
)
