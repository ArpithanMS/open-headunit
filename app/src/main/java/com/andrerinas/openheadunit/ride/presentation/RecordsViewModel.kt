package com.andrerinas.openheadunit.ride.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.andrerinas.openheadunit.ride.RideComponent
import com.andrerinas.openheadunit.ride.domain.RideLifetimeRecords
import com.andrerinas.openheadunit.ride.domain.RideLifetimeRecordsCalculator
import com.andrerinas.openheadunit.ride.domain.SavedPlaceRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Loads every finished ride plus its raw samples and the saved Home place, then derives
 * [RideLifetimeRecords] on demand - see [RideLifetimeRecordsCalculator]'s KDoc on why these are
 * never themselves persisted. A one-shot load (like [RideDetailViewModel]'s), not a live Flow:
 * lifetime records only change when a *different* screen finishes a new ride, not while this one
 * is open.
 */
class RecordsViewModel(application: Application) : AndroidViewModel(application) {

    private val component = RideComponent.get(application)

    private val _records = MutableLiveData<RideLifetimeRecords?>()
    val records: LiveData<RideLifetimeRecords?> = _records

    init {
        viewModelScope.launch {
            val rides = component.repository.observeRideHistory().first()
            val samplesByRideId = rides.associate { ride ->
                ride.id to component.repository.rawSamplesForRide(ride.id)
            }
            val home = component.savedPlaceRepository.all().firstOrNull { it.role == SavedPlaceRole.HOME }
            _records.value = RideLifetimeRecordsCalculator.compute(rides, samplesByRideId, home)
        }
    }
}
