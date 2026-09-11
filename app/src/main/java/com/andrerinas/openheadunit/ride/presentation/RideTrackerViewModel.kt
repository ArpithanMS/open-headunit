package com.andrerinas.openheadunit.ride.presentation

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.andrerinas.openheadunit.ride.RideComponent
import com.andrerinas.openheadunit.ride.domain.Ride
import com.andrerinas.openheadunit.ride.domain.RideMetrics
import com.andrerinas.openheadunit.ride.service.RideTrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Deliberately not bound to [RideTrackingService]: [com.andrerinas.openheadunit.ride.data.RideRepository]
 * is the reuse seam the whole Ride Engine is built around, so this just asks it who the active
 * ride is - the same way any other future consumer (a HUD, a custom launcher) will.
 *
 * What this deliberately does NOT expose yet: current speed, or a live GPS acquiring/ready/
 * degraded distinction. Both need a live signal from RideLocationEngine that isn't wired up to
 * any presentation layer today - see RideTrackerFragment's class doc. [distanceMeters] is real,
 * live-recomputed distance (not the DB's own Ride.distanceMeters column, which the tracking
 * service only writes once at finish - see [refreshLiveDistance]), not a placeholder.
 */
class RideTrackerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RideComponent.get(application).repository

    private val _activeRide = MutableLiveData<Ride?>()
    val activeRide: LiveData<Ride?> = _activeRide

    private val _distanceMeters = MutableLiveData(0.0)
    val distanceMeters: LiveData<Double> = _distanceMeters

    private val _lastCompletedRide = MutableLiveData<Ride?>()
    val lastCompletedRide: LiveData<Ride?> = _lastCompletedRide

    init {
        refresh()
        viewModelScope.launch {
            repository.observeRideHistory().collect { rides ->
                _lastCompletedRide.value = rides.firstOrNull()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val active = repository.activeRide()
            val wasReconciled = reconcileIfStale(active)
            _activeRide.value = if (wasReconciled) null else active
        }
    }

    /**
     * The DB alone can't tell a live ride from one whose tracking service silently died -
     * RideRepository.activeRide() only means "a row with no end timestamp", regardless of whether
     * anything is still collecting points for it. Swiping the app from Recents, a low-memory kill
     * the OS hasn't gotten around to restarting yet, or `adb shell am force-stop` (which Android
     * deliberately does NOT auto-restart from - see RideTrackingService's KDoc) all leave exactly
     * that row behind, with the UI and notification both still claiming RECORDING.
     *
     * This does not attempt to resume tracking - RideTrackingService.recoverActiveRideIfAny()
     * already owns that, and only it decides whether recovery is possible. This only stops the
     * presentation layer from confidently lying: if the service isn't alive, close the ride out
     * with whatever was actually recorded, the same way a normal Stop would.
     *
     * The grace delay exists solely so this doesn't race a legitimately sticky-restarting service
     * (a plain low-memory kill, not a force-stop) into being wrongly declared dead the instant the
     * UI happens to check - not a recovery mechanism, just avoiding a false positive on top of one.
     */
    private suspend fun reconcileIfStale(active: Ride?): Boolean {
        if (active == null || RideTrackingService.isRunning) return false
        delay(STALE_RIDE_GRACE_MS)
        if (RideTrackingService.isRunning) return false

        val accepted = repository.rawSamplesForRide(active.id).filter { it.accepted }.map { it.point }
        val distanceMeters = RideMetrics.totalDistanceMeters(accepted)
        val lastTimestampMs = accepted.lastOrNull()?.timestampMs ?: active.startTimestampMs
        val durationMs = (lastTimestampMs - active.startTimestampMs).coerceAtLeast(0L)
        repository.finishRide(active.id, lastTimestampMs, distanceMeters, durationMs)
        RideTrackingService.cancelStaleNotification(getApplication())
        return true
    }

    /**
     * Recomputes distance from this ride's actually-accepted raw samples, the same way
     * RideTrackingService.recoverActiveRideIfAny() does - the DB's Ride.distanceMeters column
     * itself is only written once, at finishRide(), so it reads 0 for the whole active ride
     * otherwise. Call periodically while riding (see RideTrackerFragment's ticker); a Room read
     * every second would be wasteful, so this is intentionally caller-paced, not automatic.
     */
    fun refreshLiveDistance(rideId: Long) {
        viewModelScope.launch {
            val accepted = repository.rawSamplesForRide(rideId).filter { it.accepted }.map { it.point }
            _distanceMeters.value = RideMetrics.totalDistanceMeters(accepted)
        }
    }

    fun startRide() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, RideTrackingService.startRideIntent(context))
        refreshAfterDelay()
    }

    fun stopRide() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, RideTrackingService.stopRideIntent(context))
        _distanceMeters.value = 0.0
        refreshAfterDelay()
    }

    /**
     * The service's own DB write for a Start/Stop is a single fast Room operation; this margin is
     * enough for it to land before re-reading, without wiring up a bound-service round trip just
     * for this. The next onResume() (or another refresh()) is the fallback if it isn't.
     */
    private fun refreshAfterDelay() {
        viewModelScope.launch {
            delay(REFRESH_DELAY_MS)
            refresh()
        }
    }

    private companion object {
        const val REFRESH_DELAY_MS = 300L
        const val STALE_RIDE_GRACE_MS = 2000L
    }
}
