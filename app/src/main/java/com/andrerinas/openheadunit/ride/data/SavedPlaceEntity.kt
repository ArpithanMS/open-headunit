package com.andrerinas.openheadunit.ride.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** [role] (a [com.andrerinas.openheadunit.ride.domain.SavedPlaceRole] name) is the primary key
 *  rather than an auto-generated id - there is exactly one Home and one Work, an upsert-by-role
 *  table rather than an open-ended list. */
@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey val role: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
)
