package com.andrerinas.openheadunit.ride.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {

    /** Returns the new row's generated id. */
    @Insert
    suspend fun insertRide(ride: RideEntity): Long

    @Insert
    suspend fun insertPoints(points: List<RidePointEntity>)

    /**
     * A targeted UPDATE rather than a full-row @Update: finishing a ride only ever changes these
     * four columns, and this avoids a read-modify-write round trip through the caller.
     */
    @Query(
        "UPDATE rides SET endTimestampMs = :endTimestampMs, state = :state, " +
            "distanceMeters = :distanceMeters, durationMs = :durationMs WHERE id = :rideId"
    )
    suspend fun finishRide(
        rideId: Long,
        endTimestampMs: Long,
        state: String,
        distanceMeters: Double,
        durationMs: Long,
    )

    /** The one ride still missing an end timestamp, if any. See RideRepository.activeRide(). */
    @Query("SELECT * FROM rides WHERE endTimestampMs IS NULL LIMIT 1")
    suspend fun activeRide(): RideEntity?

    @Query("SELECT * FROM rides WHERE id = :rideId")
    suspend fun rideById(rideId: Long): RideEntity?

    /** Every raw fix recorded for [rideId], accepted or not, in recording order. */
    @Query("SELECT * FROM ride_points WHERE rideId = :rideId ORDER BY timestampMs ASC")
    suspend fun rawSamplesForRide(rideId: Long): List<RidePointEntity>

    @Query("SELECT * FROM rides WHERE endTimestampMs IS NOT NULL ORDER BY startTimestampMs DESC")
    fun observeFinishedRides(): Flow<List<RideEntity>>
}
