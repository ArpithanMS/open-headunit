package com.andrerinas.openheadunit.ride.data

import com.andrerinas.openheadunit.ride.domain.SavedPlace
import com.andrerinas.openheadunit.ride.domain.SavedPlaceRole

fun SavedPlaceEntity.toDomain(): SavedPlace = SavedPlace(
    role = SavedPlaceRole.valueOf(role),
    latitude = latitude,
    longitude = longitude,
    radiusMeters = radiusMeters,
)

fun SavedPlace.toEntity(): SavedPlaceEntity = SavedPlaceEntity(
    role = role.name,
    latitude = latitude,
    longitude = longitude,
    radiusMeters = radiusMeters,
)
