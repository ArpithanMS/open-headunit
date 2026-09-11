package com.andrerinas.openheadunit.ride.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.andrerinas.openheadunit.ride.RideComponent
import com.andrerinas.openheadunit.ride.domain.Ride
import com.andrerinas.openheadunit.ride.domain.RidePoint
import com.andrerinas.openheadunit.ride.domain.RouteSpeedSegment
import com.andrerinas.openheadunit.ride.domain.RouteSpeedSegmenter
import com.andrerinas.openheadunit.ride.domain.RouteStatistics
import com.andrerinas.openheadunit.ride.domain.RouteStatisticsCalculator
import kotlinx.coroutines.launch

/**
 * Loads one ride's stored summary plus its full route statistics, both derived on-demand from its
 * raw samples (see RideRepository's KDoc) - nothing here is precomputed/cached in the database
 * beyond what RideEntity already stores for the history list.
 */
class RideDetailViewModel(application: Application, private val rideId: Long) : AndroidViewModel(application) {

    private val repository = RideComponent.get(application).repository

    /** Explicit states rather than a nullable [Found.ride] - a ride ID that doesn't resolve (a
     *  stale deep link, a deleted ride) should never silently render as a real ride with zeroed
     *  stats and an empty map; the Fragment must be able to tell the two apart. */
    sealed class UiState {
        data class Found(
            val ride: Ride,
            val routeStatistics: RouteStatistics,
            val acceptedRoutePoints: List<RidePoint>,
            val speedSegments: List<RouteSpeedSegment>,
        ) : UiState()

        object NotFound : UiState()
    }

    private val _uiState = MutableLiveData<UiState?>()
    val uiState: LiveData<UiState?> = _uiState

    init {
        viewModelScope.launch {
            val ride = repository.rideById(rideId)
            if (ride == null) {
                _uiState.value = UiState.NotFound
                return@launch
            }
            val samples = repository.rawSamplesForRide(rideId)
            val acceptedPoints = samples.filter { it.accepted }.map { it.point }
            _uiState.value = UiState.Found(
                ride = ride,
                routeStatistics = RouteStatisticsCalculator.compute(samples),
                acceptedRoutePoints = acceptedPoints,
                speedSegments = RouteSpeedSegmenter.segment(acceptedPoints),
            )
        }
    }
}
