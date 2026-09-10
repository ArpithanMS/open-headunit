package com.andrerinas.openheadunit.ride.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * exportSchema is off for now: nothing depends on exported schema history yet. Turn it on, with a
 * schema directory wired into the kapt args, the first time an actual migration is written.
 *
 * version 2 added [RidePointEntity.accepted]/[RidePointEntity.rejectionReason]. No shipped release
 * has used version 1 yet, so [RideComponent] pairs this with `fallbackToDestructiveMigration()`
 * rather than a real [androidx.room.migration.Migration] - there is no real user data to preserve.
 * The first schema change after a real release will need an actual migration instead.
 */
@Database(entities = [RideEntity::class, RidePointEntity::class], version = 2, exportSchema = false)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
}
