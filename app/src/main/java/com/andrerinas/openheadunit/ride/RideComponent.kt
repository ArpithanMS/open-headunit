package com.andrerinas.openheadunit.ride

import android.content.Context
import androidx.room.Room
import com.andrerinas.openheadunit.ride.data.MIGRATION_2_3
import com.andrerinas.openheadunit.ride.data.RideDatabase
import com.andrerinas.openheadunit.ride.data.RideRepository
import com.andrerinas.openheadunit.ride.data.SavedPlaceRepository

/**
 * The Ride Engine's own object graph. Deliberately separate from
 * [com.andrerinas.openheadunit.AppComponent] rather than folded into it: the Ride Engine is meant
 * to stay extractable (a future standalone tracker app, a future library module), and giving it
 * its own small locator here - instead of accreting properties onto the app's general-purpose
 * component - is what keeps that possible.
 */
class RideComponent private constructor(context: Context) {

    // MIGRATION_2_3 is real (see RideDatabase's KDoc) - this database now holds real recorded
    // rides. fallbackToDestructiveMigration() stays only as a safety net for any gap this
    // explicit migration doesn't cover; Room always prefers a provided Migration over it.
    private val database = Room.databaseBuilder(
        context, RideDatabase::class.java, DATABASE_NAME
    ).addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration().build()

    val repository = RideRepository(database.rideDao())
    val savedPlaceRepository = SavedPlaceRepository(database.savedPlaceDao())

    companion object {
        private const val DATABASE_NAME = "ride-tracker.db"

        @Volatile
        private var instance: RideComponent? = null

        fun get(context: Context): RideComponent =
            instance ?: synchronized(this) {
                instance ?: RideComponent(context.applicationContext).also { instance = it }
            }
    }
}
