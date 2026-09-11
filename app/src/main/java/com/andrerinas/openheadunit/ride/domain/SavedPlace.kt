package com.andrerinas.openheadunit.ride.domain

enum class SavedPlaceRole { HOME, WORK }

/**
 * A user-designated Home or Work location - context for labeling a *finished* ride in history
 * (see [RideClassifier]), never a gate on whether a ride gets recorded in the first place. At most
 * one [SavedPlace] exists per [SavedPlaceRole] (see [com.andrerinas.openheadunit.ride.data.SavedPlaceRepository]).
 */
data class SavedPlace(
    val role: SavedPlaceRole,
    val latitude: Double,
    val longitude: Double,
    /** How close a ride's start/end point must be to count as "at" this place. */
    val radiusMeters: Float,
)
