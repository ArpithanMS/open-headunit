package com.andrerinas.openheadunit.ride.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.ride.RideComponent
import com.andrerinas.openheadunit.ride.data.RideRepository
import com.andrerinas.openheadunit.ride.domain.RideEvent
import com.andrerinas.openheadunit.ride.domain.RideLiveState
import com.andrerinas.openheadunit.ride.domain.RideMetrics
import com.andrerinas.openheadunit.ride.domain.RidePoint
import com.andrerinas.openheadunit.ride.domain.RidePointQualityPolicy
import com.andrerinas.openheadunit.ride.domain.RideRawSample
import com.andrerinas.openheadunit.ride.domain.RideState
import com.andrerinas.openheadunit.ride.domain.RideStateMachine
import com.andrerinas.openheadunit.ride.location.RideLocationEngine
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns an active ride end to end: turns Start/Stop actions into [RideStateMachine] transitions,
 * drives [RideLocationEngine] while RIDING, evaluates each fix through [RidePointQualityPolicy],
 * batches the resulting [RideRawSample]s into [RideRepository], and keeps a running
 * distance/duration in memory (over accepted fixes only) so finishing a ride never needs to
 * re-read every point back out of the database. Every raw fix is still persisted regardless of
 * the verdict - see [RideRawSample]'s KDoc.
 *
 * Deliberately its own foreground service, independent of AapService: a rider tracking a ride
 * without ever connecting to Android Auto still needs this running, and a service whose lifecycle
 * were tied to the AA connection would not give them that (see RideLocationEngine's KDoc for the
 * same reasoning applied one layer down).
 *
 * All mutable state below (rideId/state/buffer/running totals) is only ever touched inside
 * [mutex] - both Start/Stop actions and location callbacks funnel through it, so a stray callback
 * arriving mid-Stop can't corrupt a ride that's in the middle of being finished.
 */
class RideTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository: RideRepository by lazy { RideComponent.get(this).repository }
    private val locationEngine: RideLocationEngine by lazy { RideLocationEngine(this) }
    private val mutex = Mutex()

    private var rideId: Long? = null
    private var state: RideState = RideState.IDLE
    private var sampleBuffer = mutableListOf<RideRawSample>()
    private var runningDistanceMeters = 0.0
    /** The last *accepted* fix - what new candidates are evaluated against, and what the running
     * distance is measured from. A rejected fix never becomes this. */
    private var previousAccepted: RidePoint? = null
    private var firstAcceptedTimestampMs: Long? = null
    /** The ride's own wall-clock start (Start tap, or the recovered Ride row's start after a
     * restart) - what [Ride.durationMs] is measured from. Deliberately NOT first-accepted-fix to
     * last-accepted-fix: that basis silently excludes GPS-acquisition time and would disagree
     * with the elapsed clock RideTrackerFragment shows throughout the ride, which ticks from this
     * same wall-clock start. */
    private var rideStartTimestampMs: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            AppLog.e("RideTrackingService: could not start foreground, stopping", e)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_RIDE -> scope.launch { handleStartRequested() }
            ACTION_STOP_RIDE -> scope.launch { handleStopRequested() }
            // A null action means the system restarted this service (START_STICKY) after the
            // process died mid-ride, not a fresh user-initiated start.
            null -> scope.launch { recoverActiveRideIfAny() }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        locationEngine.stop()
        scope.cancel()
        isRunning = false
        _liveState.value = null
        super.onDestroy()
    }

    private suspend fun handleStartRequested() = mutex.withLock {
        val result = RideStateMachine.transition(state, RideEvent.StartRequested)
        if (result !is RideStateMachine.Result.Accepted) {
            AppLog.i("RideTrackingService: StartRequested rejected from $state")
            return@withLock
        }
        val startedAtMs = System.currentTimeMillis()
        val newRideId = repository.startRide(startedAtMs)
        rideId = newRideId
        rideStartTimestampMs = startedAtMs
        state = result.newState
        sampleBuffer = mutableListOf()
        runningDistanceMeters = 0.0
        previousAccepted = null
        firstAcceptedTimestampMs = null
        _liveState.value = RideLiveState(
            newRideId, runningDistanceMeters,
            lastAcceptedAccuracyMeters = null, lastAcceptedSpeedMetersPerSecond = null,
        )
        locationEngine.start(RideLocationEngine.Listener { point -> onRidePoint(point) })
        AppLog.i("RideTrackingService: ride $newRideId started")
    }

    private suspend fun handleStopRequested() = mutex.withLock {
        val result = RideStateMachine.transition(state, RideEvent.StopRequested)
        if (result !is RideStateMachine.Result.Accepted) {
            AppLog.i("RideTrackingService: StopRequested rejected from $state")
            return@withLock
        }
        locationEngine.stop()
        val id = rideId
        val startedAtMs = rideStartTimestampMs
        if (id != null) {
            flushBufferLocked(id)
            val lastMs = previousAccepted?.timestampMs
            val endTimestampMs = lastMs ?: System.currentTimeMillis()
            val durationMs = if (startedAtMs != null) (endTimestampMs - startedAtMs).coerceAtLeast(0L) else 0L
            repository.finishRide(id, endTimestampMs, runningDistanceMeters, durationMs)
            AppLog.i(
                "RideTrackingService: ride $id finished, " +
                    "distance=${runningDistanceMeters}m duration=${durationMs}ms"
            )
        }
        state = result.newState
        rideId = null
        rideStartTimestampMs = null
        _liveState.value = null
        stopForeground(true)
        stopSelf()
    }

    private suspend fun recoverActiveRideIfAny() = mutex.withLock {
        if (state != RideState.IDLE) return@withLock // already tracking; nothing to recover
        val active = repository.activeRide()
        if (active == null) {
            // Nothing was interrupted - a bare restart with no ride to resume, so there is
            // nothing for this foreground service to be doing.
            stopForeground(true)
            stopSelf()
            return@withLock
        }
        val existingSamples = repository.rawSamplesForRide(active.id)
        val existingAccepted = existingSamples.filter { it.accepted }.map { it.point }
        rideId = active.id
        rideStartTimestampMs = active.startTimestampMs
        state = RideState.RIDING
        sampleBuffer = mutableListOf()
        runningDistanceMeters = RideMetrics.totalDistanceMeters(existingAccepted)
        previousAccepted = existingAccepted.lastOrNull()
        firstAcceptedTimestampMs = existingAccepted.firstOrNull()?.timestampMs
        _liveState.value = RideLiveState(
            active.id, runningDistanceMeters,
            lastAcceptedAccuracyMeters = previousAccepted?.accuracyMeters,
            lastAcceptedSpeedMetersPerSecond = previousAccepted?.speedMetersPerSecond,
        )
        locationEngine.start(RideLocationEngine.Listener { point -> onRidePoint(point) })
        AppLog.i("RideTrackingService: recovered active ride ${active.id} after restart")
    }

    private fun onRidePoint(point: RidePoint) {
        scope.launch { handleRidePoint(point) }
    }

    private suspend fun handleRidePoint(point: RidePoint) = mutex.withLock {
        val id = rideId ?: return@withLock

        val verdict = RidePointQualityPolicy.evaluate(point, previousAccepted)
        val sample = when (verdict) {
            is RidePointQualityPolicy.Result.Accepted -> {
                previousAccepted?.let { previous ->
                    runningDistanceMeters += RideMetrics.haversineMeters(
                        previous.latitude, previous.longitude, point.latitude, point.longitude
                    )
                }
                if (firstAcceptedTimestampMs == null) firstAcceptedTimestampMs = point.timestampMs
                previousAccepted = point
                _liveState.value = RideLiveState(
                    id, runningDistanceMeters,
                    lastAcceptedAccuracyMeters = point.accuracyMeters,
                    lastAcceptedSpeedMetersPerSecond = point.speedMetersPerSecond,
                )
                RideRawSample.accepted(point)
            }

            is RidePointQualityPolicy.Result.Rejected -> {
                AppLog.i("RideTrackingService: rejected a fix (${verdict.reason})")
                RideRawSample.rejected(point, verdict.reason)
            }
        }

        // Every fix is buffered and eventually persisted regardless of the verdict - see
        // RideRawSample's KDoc on why a rejected point is never simply dropped.
        sampleBuffer.add(sample)
        if (sampleBuffer.size >= FLUSH_EVERY_N_SAMPLES) {
            flushBufferLocked(id)
        }
    }

    /** Caller must hold [mutex]. */
    private suspend fun flushBufferLocked(id: Long) {
        if (sampleBuffer.isEmpty()) return
        repository.appendSamples(id, sampleBuffer)
        sampleBuffer = mutableListOf()
    }

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
            .setContentTitle(getString(R.string.ride_tracking_notification_title))
            .setContentText(getString(R.string.ride_tracking_notification_text))
            .setSmallIcon(R.drawable.ic_stat_ride_tracking)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "ride_tracking_service_v1"
        private const val NOTIFICATION_ID = 2001
        private const val FLUSH_EVERY_N_SAMPLES = 10

        private const val ACTION_START_RIDE = "com.andrerinas.openheadunit.ride.ACTION_START_RIDE"
        private const val ACTION_STOP_RIDE = "com.andrerinas.openheadunit.ride.ACTION_STOP_RIDE"

        /**
         * True only while an actual instance of this service is alive in this process - not a
         * persisted flag, so a fresh process (a normal relaunch, or one following `adb shell am
         * force-stop`) always starts out false regardless of what the last process left behind.
         * This is what lets RideTrackerViewModel tell "still recording" apart from "the DB still
         * says RIDING but nothing is actually collecting points for it" - see
         * RideTrackerViewModel.reconcileIfStale().
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * The service's own in-memory ride progress, published the moment it changes - null
         * whenever no ride is actively being tracked in this process. This is what lets
         * RideTrackerViewModel show live progress without polling Room for a value the service
         * already holds; when this is null (service not running), the ViewModel falls back to its
         * existing DB-based reconciliation/refresh logic instead.
         */
        private val _liveState = MutableStateFlow<RideLiveState?>(null)
        val liveState: StateFlow<RideLiveState?> = _liveState.asStateFlow()

        /**
         * Clears a ride-tracking notification left behind by a service instance that never got to
         * run its own onDestroy() (e.g. `am force-stop`, or the process being swiped from
         * Recents) - the notification otherwise outlives the process that posted it.
         */
        fun cancelStaleNotification(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        fun startRideIntent(context: Context): Intent =
            Intent(context, RideTrackingService::class.java).setAction(ACTION_START_RIDE)

        fun stopRideIntent(context: Context): Intent =
            Intent(context, RideTrackingService::class.java).setAction(ACTION_STOP_RIDE)

        /** Call once, at process start (see App.onCreate()), same as AapNavigation's channel. */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ride_tracking_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
