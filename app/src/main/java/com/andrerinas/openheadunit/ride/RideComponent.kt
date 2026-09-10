package com.andrerinas.openheadunit.ride

import android.content.Context
import androidx.room.Room
import com.andrerinas.openheadunit.ride.data.RideDatabase
import com.andrerinas.openheadunit.ride.data.RideRepository

/**
 * The Ride Engine's own object graph. Deliberately separate from
 * [com.andrerinas.openheadunit.AppComponent] rather than folded into it: the Ride Engine is meant
 * to stay extractable (a future standalone tracker app, a future library module), and giving it
 * its own small locator here - instead of accreting properties onto the app's general-purpose
 * component - is what keeps that possible.
 */
class RideComponent private constructor(context: Context) {

    // See RideDatabase's KDoc: no shipped release has used an earlier schema version, so a
    // destructive fallback is correct here - there is no real user data to preserve yet. This
    // must become a real Migration before this ever ships with a schema change.
    private val database = Room.databaseBuilder(
        context, RideDatabase::class.java, DATABASE_NAME
    ).fallbackToDestructiveMigration().build()

    val repository = RideRepository(database.rideDao())

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
