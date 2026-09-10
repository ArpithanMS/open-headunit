package com.andrerinas.openheadunit.ride.data

import com.andrerinas.openheadunit.ride.domain.Ride
import com.andrerinas.openheadunit.ride.domain.RidePoint
import com.andrerinas.openheadunit.ride.domain.RidePointQualityPolicy
import com.andrerinas.openheadunit.ride.domain.RideRawSample
import com.andrerinas.openheadunit.ride.domain.RideState

/** Converts persisted rows into the plain ride/domain types every consumer outside ride/data sees. */
fun RideEntity.toDomain(): Ride = Ride(
    id = id,
    startTimestampMs = startTimestampMs,
    endTimestampMs = endTimestampMs,
    state = RideState.valueOf(state),
    distanceMeters = distanceMeters,
    durationMs = durationMs,
)

fun RideRawSample.toEntity(rideId: Long): RidePointEntity = RidePointEntity(
    rideId = rideId,
    timestampMs = point.timestampMs,
    latitude = point.latitude,
    longitude = point.longitude,
    accuracyMeters = point.accuracyMeters,
    altitudeMeters = point.altitudeMeters,
    speedMetersPerSecond = point.speedMetersPerSecond,
    bearingDegrees = point.bearingDegrees,
    accepted = accepted,
    rejectionReason = rejectionReason?.name,
)

fun RidePointEntity.toDomain(): RideRawSample = RideRawSample(
    point = RidePoint(
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        altitudeMeters = altitudeMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        bearingDegrees = bearingDegrees,
    ),
    accepted = accepted,
    rejectionReason = rejectionReason?.let { RidePointQualityPolicy.Rejection.valueOf(it) },
)
