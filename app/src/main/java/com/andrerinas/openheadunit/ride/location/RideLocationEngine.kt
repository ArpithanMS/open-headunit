package com.andrerinas.openheadunit.ride.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.location.LocationHolder
import com.andrerinas.openheadunit.ride.domain.RidePoint
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Thin Android adapter that turns live GPS fixes into [RidePoint]s for an active ride.
 *
 * Deliberately independent of GpsLocation/GpsLocationService: those only run while AapService has
 * an established Android Auto connection (started/stopped around connection establish/teardown
 * in AapService), so a rider tracking a ride without ever connecting to Android Auto would
 * otherwise have no active GPS subscription to read from. This engine owns its own
 * [LocationManager] subscription instead, started and stopped by ride state, never by AA
 * connection state.
 *
 * Every fix is also pushed into [LocationHolder] - the process-wide "freshest known fix" sink
 * that already exists precisely for this kind of consultation from unrelated components. That
 * makes the relationship one-directional (ride -> location, never the reverse): a simultaneous
 * Android Auto session benefits from the extra freshness at no additional GPS-chip cost (the
 * provider keeps running regardless of how many listeners are registered on it), and these are
 * genuine GPS_PROVIDER fixes, so they satisfy the same provider check
 * [LocationHolder.currentGpsFix] already relies on.
 *
 * Not unit tested: like GpsLocation and LocationHolder, this touches android.location.Location
 * APIs the plain-JUnit setup in this module cannot exercise (no Robolectric, no
 * `returnDefaultValues`). Verification is manual/on-device, the same boundary this codebase
 * already draws around its other Location-touching classes.
 */
class RideLocationEngine(private val context: Context) : LocationListener {

    fun interface Listener {
        fun onRidePoint(point: RidePoint)
    }

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: Listener? = null
    private var requested = false

    /**
     * Subscribes to GPS updates and delivers each fix to [listener] as a [RidePoint]. A no-op if
     * already subscribed - matching GpsLocation's start(), which the same double-start situation
     * (e.g. a ride resumed after process death re-entering RIDING) can trigger here too.
     */
    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        this.listener = listener
        if (requested) return

        val hasPermission = PermissionChecker.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PermissionChecker.PERMISSION_GRANTED
        val providerEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!hasPermission || !providerEnabled) {
            AppLog.i(
                "RideLocationEngine: not requesting updates, " +
                    "ACCESS_FINE_LOCATION granted=$hasPermission GPS_PROVIDER enabled=$providerEnabled"
            )
            return
        }

        // Same cadence and time-only gate as GpsLocation: 1Hz, no minDistance gate (Android ANDs
        // time and distance, so any non-zero distance would stop delivery for a stopped rider),
        // matching the automotive GPS norm this app already tunes its Android Auto feed to.
        //
        // The Looper is passed explicitly (unlike GpsLocation, which relies on always being
        // called from the main thread): RideTrackingService legitimately calls start()/stop()
        // from a background coroutine dispatcher, which has no prepared Looper of its own, and
        // the 4-arg overload's implicit "new Handler() on the calling thread" would crash there.
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, this, Looper.getMainLooper()
        )
        requested = true
    }

    /** Unsubscribes from GPS updates. Safe to call whether or not [start] was ever called. */
    fun stop() {
        requested = false
        listener = null
        locationManager.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        // A callback dispatched before removeUpdates() took effect must not reach a listener a
        // stop() just cleared.
        if (!requested) return
        LocationHolder.update(location)
        listener?.onRidePoint(location.toRidePoint())
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {
        AppLog.i("RideLocationEngine: $provider disabled")
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1000L
    }
}
