package com.andrerinas.openheadunit.ride.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * exportSchema is off for now: nothing depends on exported schema history yet. Turn it on, with a
 * schema directory wired into the kapt args, the first time this matters for CI schema-diffing.
 *
 * version 2 added [RidePointEntity.accepted]/[RidePointEntity.rejectionReason] - no shipped
 * release had used version 1 yet, so that one went through `fallbackToDestructiveMigration()`
 * with no real user data to preserve.
 *
 * version 3 (this one) is different: by now this database holds real recorded rides on real
 * devices (the whole point of this app), so [MIGRATION_2_3] is a genuine migration, not a
 * destructive wipe - adds [SavedPlaceEntity]'s table and the four new nullable start/end
 * coordinate columns on `rides` (see [RideEntity]) without touching existing rows.
 */
@Database(
    entities = [RideEntity::class, RidePointEntity::class, SavedPlaceEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun savedPlaceDao(): SavedPlaceDao
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `saved_places` (" +
                "`role` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
                "`radiusMeters` REAL NOT NULL, PRIMARY KEY(`role`))"
        )
        db.execSQL("ALTER TABLE `rides` ADD COLUMN `startLatitude` REAL")
        db.execSQL("ALTER TABLE `rides` ADD COLUMN `startLongitude` REAL")
        db.execSQL("ALTER TABLE `rides` ADD COLUMN `endLatitude` REAL")
        db.execSQL("ALTER TABLE `rides` ADD COLUMN `endLongitude` REAL")
    }
}
