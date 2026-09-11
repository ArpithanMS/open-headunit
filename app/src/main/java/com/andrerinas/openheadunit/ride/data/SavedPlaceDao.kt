package com.andrerinas.openheadunit.ride.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SavedPlaceDao {

    /** Replaces any existing row for this role - there is at most one place per role. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: SavedPlaceEntity)

    @Query("SELECT * FROM saved_places")
    suspend fun all(): List<SavedPlaceEntity>

    @Query("DELETE FROM saved_places WHERE role = :role")
    suspend fun delete(role: String)
}
