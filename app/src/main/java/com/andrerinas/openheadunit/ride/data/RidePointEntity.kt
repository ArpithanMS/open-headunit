package com.andrerinas.openheadunit.ride.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted row for one raw GPS fix recorded during a ride. Deliberately mirrors
 * [com.andrerinas.openheadunit.ride.domain.RidePoint] field-for-field; RideDataMapper.kt is the
 * only place that converts between them, keeping Room's annotations out of the domain layer.
 *
 * Cascades on delete from [RideEntity]: nothing in the MVP deletes a ride, but a raw-points table
 * that outlives its parent row would be exactly the kind of orphaned data this schema shouldn't
 * quietly accumulate once ride deletion does exist.
 */
@Entity(
    tableName = "ride_points",
    foreignKeys = [
        ForeignKey(
            entity = RideEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("rideId")],
)
data class RidePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val rideId: Long,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    /**
     * Whether [com.andrerinas.openheadunit.ride.domain.RidePointQualityPolicy] accepted this fix
     * into the route at recording time. The row is written either way - see
     * [com.andrerinas.openheadunit.ride.domain.RideRawSample] - so this is a recorded decision,
     * not a filter applied before storage.
     */
    val accepted: Boolean,
    /** A [com.andrerinas.openheadunit.ride.domain.RidePointQualityPolicy.Rejection] name, stored
     * by name for the same reason [RideEntity.state] is - null exactly when [accepted] is true. */
    val rejectionReason: String?,
)
