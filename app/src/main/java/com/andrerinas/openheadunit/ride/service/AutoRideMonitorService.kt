package com.andrerinas.openheadunit.ride.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.ride.domain.AutoRideDecision
import com.andrerinas.openheadunit.ride.domain.AutoRideSample
import com.andrerinas.openheadunit.ride.domain.AutoRideState
import com.andrerinas.openheadunit.ride.domain.AutoRideStateMachine
import com.andrerinas.openheadunit.ride.domain.RideLiveState
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Always-on foreground service that watches for ride-start/ride-stop evidence via
 * [AutoRideStateMachine] and, when the user has opted in (see [com.andrerinas.openheadunit.ride.RideEnginePreferences]),
 * drives [RideTrackingService] the same way a manual Start/End Ride tap would - see this
 * session's product decision to accept the battery/permission/notification cost of true
 * always-on detection rather than a foreground-app-only compromise.
 *
 * Two distinct GPS sources feed the same state machine, never both at once:
 *  - While no ride is active ([RideTrackingService.liveState] is null), this service runs its
 *    own coarse, low-frequency [LocationManager] subscription (see [IDLE_POLL_INTERVAL_MS]) -
 *    just enough resolution to notice sustained motion, not to track a route.
 *  - The instant a ride becomes active (started manually, recovered after a process restart, or
 *    started by this service's own [AutoRideDecision.StartRide]), this service stops its own
 *    polling entirely and instead rides along on [RideTrackingService]'s existing 1Hz fixes via
 *    its `liveState` flow - there is no reason to hold two independent GPS subscriptions
 *    open at once, and RideTrackingService's fixes are strictly higher quality anyway.
 *
 * This is what lets [AutoRideStateMachine] keep evaluating auto-stop dwell/grace during a
 * *manually* started ride too, not only a self-started one: from this service's perspective a
 * ride is a ride regardless of who started it, and forgetting to tap End Ride should eventually
 * still resolve itself.
 *
 * A manual End Ride (or this service's own [AutoRideDecision.StopRide]) always makes
 * `liveState` go back to null, which resyncs the machine back to IDLE and resumes idle polling -
 * see `onLiveStateChanged`.
 */
class AutoRideMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateMachine = AutoRideStateMachine()
    private val locationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    private var idlePollingActive = false

    private val idleLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!idlePollingActive) return
            onIdleSample(AutoRideSample(location.speedIfPresent(), location.time))
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            AppLog.i("AutoRideMonitorService: $provider disabled")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            AppLog.e("AutoRideMonitorService: could not start foreground, stopping", e)
            stopSelf()
            return
        }

        if (!hasLocationPermission()) {
            AppLog.w("AutoRideMonitorService: ACCESS_FINE_LOCATION not granted, stopping")
            stopSelf()
            return
        }

        scope.launch {
            RideTrackingService.liveState.collect { onLiveStateChanged(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopIdlePolling()
        scope.cancel()
        super.onDestroy()
    }

    private fun onLiveStateChanged(liveState: RideLiveState?) {
        if (liveState == null) {
            stateMachine.onRideStopped()
            startIdlePolling()
            return
        }

        stopIdlePolling()
        if (stateMachine.state != AutoRideState.RECORDING && stateMachine.state != AutoRideState.STOP_CANDIDATE) {
            stateMachine.onRideStarted()
        }
        val sample = AutoRideSample(
            speedMetersPerSecond = liveState.lastAcceptedSpeedMetersPerSecond?.toDouble(),
            timestampMs = System.currentTimeMillis(),
        )
        if (stateMachine.onSample(sample) == AutoRideDecision.StopRide) {
            AppLog.i("AutoRideMonitorService: auto-stopping ride ${liveState.rideId} (sustained stationary dwell)")
            startService(RideTrackingService.stopRideIntent(this))
        }
    }

    private fun onIdleSample(sample: AutoRideSample) {
        if (stateMachine.onSample(sample) == AutoRideDecision.StartRide) {
            AppLog.i("AutoRideMonitorService: auto-starting a ride (sustained motion)")
            stopIdlePolling()
            startService(RideTrackingService.startRideIntent(this))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startIdlePolling() {
        if (idlePollingActive) return
        if (!hasLocationPermission() || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        idlePollingActive = true
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, IDLE_POLL_INTERVAL_MS, 0f, idleLocationListener, Looper.getMainLooper()
        )
    }

    private fun stopIdlePolling() {
        if (!idlePollingActive) return
        idlePollingActive = false
        locationManager.removeUpdates(idleLocationListener)
    }

    private fun hasLocationPermission(): Boolean = PermissionChecker.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PermissionChecker.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ride_auto_detect_notification_title))
            .setContentText(getString(R.string.ride_auto_detect_notification_text))
            .setSmallIcon(R.drawable.ic_stat_ride_tracking)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "auto_ride_monitor_v1"
        private const val NOTIFICATION_ID = 2002

        /** Coarse on purpose - this is only meant to notice sustained motion starting, not to
         *  track a route. An initial/tunable placeholder (this session's own estimate), not a
         *  value derived from measured field data. */
        private const val IDLE_POLL_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, AutoRideMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoRideMonitorService::class.java))
        }

        /** Call once, at process start (see App.onCreate()), same as RideTrackingService's own. */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ride_auto_detect_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

/** Location.speed is always a valid (if possibly zero/stale) float - hasSpeed() is what actually
 *  tells you whether this fix reported a real value, matching RidePoint's own convention. */
private fun Location.speedIfPresent(): Double? = if (hasSpeed()) speed.toDouble() else null
