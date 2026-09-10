package com.andrerinas.openheadunit.ride.location

import android.location.Location
import com.andrerinas.openheadunit.ride.domain.RidePoint

/**
 * Converts a platform GPS fix into the Android-free [RidePoint] the ride domain layer works with.
 * Nullable fields become null exactly when [Location] says the value wasn't carried by this fix
 * (hasAltitude/hasSpeed/hasBearing), rather than defaulting to a misleading zero.
 */
fun Location.toRidePoint(): RidePoint = RidePoint(
    timestampMs = time,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracy,
    altitudeMeters = if (hasAltitude()) altitude else null,
    speedMetersPerSecond = if (hasSpeed()) speed else null,
    bearingDegrees = if (hasBearing()) bearing else null,
)
