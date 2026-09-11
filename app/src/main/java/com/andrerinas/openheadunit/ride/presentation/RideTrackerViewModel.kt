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
 * [com.andrerinas.openheadunit.ride.data.RideRepository] is the reuse seam the whole Ride Engine
 * is built around, so [activeRide]/[lastCompletedRide] ask it who the active ride is - the same
 * way any other future consumer (a HUD, a custom launcher) will. [distanceMeters] and
 * [lastAcceptedAccuracyMeters], though, come straight from [RideTrackingService.liveState] while
 * it's running: the service already holds this in memory (Room only gets it in flushed batches -
 * see the service's own KDoc), so polling the database for a value the service already has would
 * just add latency. When the service isn't running, [reconcileIfStale] is what keeps [activeRide]
 * honest instead.
 *
 * What this deliberately does NOT expose yet: a live GPS acquiring/ready/degraded distinction -
 * see RideTrackerFragment's class doc on why that needs more than a raw accuracy number.
 */
class RideTrackerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RideComponent.get(application).repository

    private val _activeRide = MutableLiveData<Ride?>()
    val activeRide: LiveData<Ride?> = _activeRide

    private val _distanceMeters = MutableLiveData(0.0)
    val distanceMeters: LiveData<Double> = _distanceMeters

    private val _lastAcceptedAccuracyMeters = MutableLiveData<Float?>(null)
    val lastAcceptedAccuracyMeters: LiveData<Float?> = _lastAcceptedAccuracyMeters

    private val _lastCompletedRide = MutableLiveData<Ride?>()
    val lastCompletedRide: LiveData<Ride?> = _lastCompletedRide

    init {
        refresh()
        viewModelScope.launch {
            repository.observeRideHistory().collect { rides ->
                _lastCompletedRide.value = rides.firstOrNull()
            }
        }
        viewModelScope.launch {
            RideTrackingService.liveState.collect { state ->
                // A null emission means the service stopped tracking (a normal Stop, already
                // handled explicitly in stopRide() below, or the service dying unexpectedly) -
                // freeze whatever was last shown rather than snapping to 0/null, since that would
                // claim data was lost when it may just be pending reconciliation (see refresh()).
                if (state != null) {
                    _distanceMeters.value = state.runningDistanceMeters
                    _lastAcceptedAccuracyMeters.value = state.lastAcceptedAccuracyMeters
                }
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

    fun startRide() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, RideTrackingService.startRideIntent(context))
        refreshAfterDelay()
    }

    fun stopRide() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, RideTrackingService.stopRideIntent(context))
        _distanceMeters.value = 0.0
        _lastAcceptedAccuracyMeters.value = null
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
