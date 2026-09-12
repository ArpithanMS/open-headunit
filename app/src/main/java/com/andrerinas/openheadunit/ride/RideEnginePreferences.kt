package com.andrerinas.openheadunit.ride

import android.content.Context

/**
 * The Ride Engine's own tiny preference store - deliberately not the app-wide [Settings]
 * ([com.andrerinas.openheadunit.utils.Settings]): this module stays extractable on purpose (see
 * [RideComponent]'s KDoc), so it keeps its own SharedPreferences file rather than plugging into
 * the app's general one.
 */
object RideEnginePreferences {
    private const val PREFS_NAME = "ride-engine-prefs"
    private const val KEY_AUTO_DETECTION_ENABLED = "auto-ride-detection-enabled"

    /**
     * Off by default. Turning this on starts an always-on foreground service
     * ([com.andrerinas.openheadunit.ride.service.AutoRideMonitorService]) that watches for motion
     * and can auto-start/auto-stop ride recordings without a confirmation prompt - a real change
     * to the app's background behavior, battery use, and the permanent notification it shows, so
     * this must be an explicit opt-in rather than defaulting on.
     */
    fun isAutoDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_DETECTION_ENABLED, false)

    fun setAutoDetectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_DETECTION_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
