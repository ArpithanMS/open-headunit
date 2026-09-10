package com.andrerinas.openheadunit.ride.presentation

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.andrerinas.openheadunit.ride.RideComponent
import com.andrerinas.openheadunit.ride.domain.Ride
import com.andrerinas.openheadunit.ride.service.RideTrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Deliberately not bound to [RideTrackingService]: [com.andrerinas.openheadunit.ride.data.RideRepository]
 * is the reuse seam the whole Ride Engine is built around, so this just asks it who the active
 * ride is - the same way any other future consumer (a HUD, a custom launcher) will.
 */
class RideTrackerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RideComponent.get(application).repository

    private val _activeRide = MutableLiveData<Ride?>()
    val activeRide: LiveData<Ride?> = _activeRide

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _activeRide.value = repository.activeRide()
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
    }
}
