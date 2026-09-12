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
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
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
 * GPS duty-cycling: real on-device measurement this session (`dumpsys batterystats`) showed GNSS
 * alone at ~82% of this app's total attributed battery cost whenever it's kept on - by a huge
 * margin over CPU/screen/wakelocks combined - so this service deliberately avoids ever polling
 * GPS_PROVIDER continuously while genuinely stationary:
 *  - At rest (no ride active, no motion suspected), it only arms a one-shot
 *    [Sensor.TYPE_SIGNIFICANT_MOTION] hardware trigger (near-zero power - batched on the sensor
 *    hub, never touches the GNSS chip) and does not request any GPS fixes at all.
 *  - The moment that trigger fires, it opens a bounded GPS "watch window": a coarse,
 *    low-frequency [LocationManager] subscription (see [IDLE_POLL_INTERVAL_MS]) feeding
 *    [AutoRideStateMachine], just enough resolution to notice *sustained* motion, not to track a
 *    route. If nothing escalates past IDLE within [QUIET_TIMEOUT_MS] (a false alarm - the sensor
 *    firing on a single bump/pickup, not real departure), the window closes and the significant-
 *    motion trigger re-arms - back to near-zero cost.
 *  - If a device genuinely lacks [Sensor.TYPE_SIGNIFICANT_MOTION] (checked at runtime, not
 *    assumed), this falls back to the old always-on 15s poll rather than never watching at all.
 *  - The instant a ride becomes active (started manually, recovered after a process restart, or
 *    started by this service's own [AutoRideDecision.StartRide]), this service closes any GPS
 *    watch window and instead rides along on [RideTrackingService]'s existing 1Hz fixes via its
 *    `liveState` flow - there is no reason to hold two independent GPS subscriptions open at
 *    once, and RideTrackingService's fixes are strictly higher quality anyway.
 *
 * This is what lets [AutoRideStateMachine] keep evaluating auto-stop dwell/grace during a
 * *manually* started ride too, not only a self-started one: from this service's perspective a
 * ride is a ride regardless of who started it, and forgetting to tap End Ride should eventually
 * still resolve itself.
 *
 * A manual End Ride (or this service's own [AutoRideDecision.StopRide]) always makes
 * `liveState` go back to null, which resyncs the machine back to IDLE and resumes significant-
 * motion watching - see `onLiveStateChanged`.
 */
class AutoRideMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateMachine = AutoRideStateMachine()
    private val locationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val significantMotionSensor by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    }
    private val handler = Handler(Looper.getMainLooper())

    private var idlePollingActive = false
    private var significantMotionArmed = false

    /** Recurring watchdog for an open GPS watch window - re-posts itself every [QUIET_TIMEOUT_MS]
     *  while the window stays open. Driven by wall-clock time rather than incoming GPS samples so
     *  a dead GPS zone (no fix ever arrives after the motion sensor fires) still gets caught,
     *  not just a window where fixes arrive but never escalate past IDLE. */
    private val quietTimeoutRunnable = Runnable { onQuietTimeoutFired() }

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

    private val significantMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // TYPE_SIGNIFICANT_MOTION is one-shot: it already auto-disabled itself delivering
            // this callback, so there is nothing to disarm here - only a decision to open a
            // GPS watch window (see openWatchWindow's KDoc for what happens if it's a false alarm).
            significantMotionArmed = false
            AppLog.i("AutoRideMonitorService: significant motion detected, opening a GPS watch window")
            openWatchWindow()
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
        disarmSignificantMotionTrigger()
        scope.cancel()
        super.onDestroy()
    }

    private fun onLiveStateChanged(liveState: RideLiveState?) {
        if (liveState == null) {
            stateMachine.onRideStopped()
            closeWatchWindow()
            armSignificantMotionTrigger()
            return
        }

        closeWatchWindow()
        disarmSignificantMotionTrigger()
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
            closeWatchWindow()
            startService(RideTrackingService.startRideIntent(this))
        }
    }

    /** Fires every [QUIET_TIMEOUT_MS] while a watch window is open. If the state machine is still
     *  sitting in IDLE (no sustained motion ever showed up - a false alarm, or GPS never even got
     *  a fix in a dead zone) and a significant-motion sensor exists to fall back to, this closes
     *  the window and goes back to near-zero-cost watching. If the state machine is mid-escalation
     *  (SUSPECTED_MOTION/RIDING_CANDIDATE), or there's no sensor to fall back to, it just
     *  reschedules itself rather than interrupting a real evaluation or a device that has no
     *  cheaper alternative to always-on polling. */
    private fun onQuietTimeoutFired() {
        if (!idlePollingActive) return // window already closed for another reason
        val canFallBackToSensor = significantMotionSensor != null
        if (stateMachine.state == AutoRideState.IDLE && canFallBackToSensor) {
            AppLog.i("AutoRideMonitorService: GPS watch window timed out with no sustained motion, back to significant-motion watching")
            closeWatchWindow()
            armSignificantMotionTrigger()
        } else {
            scheduleQuietTimeoutCheck()
        }
    }

    /** No-op if a window is already open. Falls back to always-on polling (the pre-existing
     *  behavior) if the sensor is unavailable on this device (checked at runtime, not assumed) or
     *  the trigger request itself fails - never ends up watching nothing at all. */
    private fun armSignificantMotionTrigger() {
        if (significantMotionArmed || idlePollingActive) return
        val sensor = significantMotionSensor
        if (sensor == null) {
            AppLog.w("AutoRideMonitorService: no TYPE_SIGNIFICANT_MOTION sensor, falling back to always-on idle polling")
            openWatchWindow()
            return
        }
        significantMotionArmed = sensorManager.requestTriggerSensor(significantMotionListener, sensor)
        if (!significantMotionArmed) openWatchWindow() // request itself failed - same fallback
    }

    private fun disarmSignificantMotionTrigger() {
        if (!significantMotionArmed) return
        significantMotionSensor?.let { sensorManager.cancelTriggerSensor(significantMotionListener, it) }
        significantMotionArmed = false
    }

    private fun openWatchWindow() {
        startIdlePolling()
        scheduleQuietTimeoutCheck()
    }

    private fun closeWatchWindow() {
        handler.removeCallbacks(quietTimeoutRunnable)
        stopIdlePolling()
    }

    private fun scheduleQuietTimeoutCheck() {
        handler.postDelayed(quietTimeoutRunnable, QUIET_TIMEOUT_MS)
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

        /** How long a GPS watch window stays open with no escalation past IDLE before giving up
         *  and falling back to significant-motion-only watching. An initial/tunable placeholder,
         *  not measured from real false-alarm-rate field data - long enough that a genuine slow
         *  pull-away isn't cut off mid-evaluation (the state machine's own
         *  [AutoRideStateMachine.Config.startSustainedDurationMs] is 30s), short enough that a
         *  single bump/pickup doesn't leave GPS polling for the rest of the day. */
        private const val QUIET_TIMEOUT_MS = 2 * 60_000L

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
