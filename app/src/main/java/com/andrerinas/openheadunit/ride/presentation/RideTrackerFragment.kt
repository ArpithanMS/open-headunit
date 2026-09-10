package com.andrerinas.openheadunit.ride.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ride.domain.Ride
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.util.Locale

/** MVP screen: manual Start/Stop plus a link to ride history. See RideTrackerViewModel. */
class RideTrackerFragment : Fragment() {

    private val viewModel: RideTrackerViewModel by viewModels()

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRide()
    }

    private lateinit var statusText: TextView
    private lateinit var startStopButton: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_ride_tracker, container, false)

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        statusText = view.findViewById(R.id.ride_status_text)
        startStopButton = view.findViewById(R.id.start_stop_button)
        startStopButton.setOnClickListener { onStartStopClicked() }

        view.findViewById<MaterialButton>(R.id.ride_history_button).setOnClickListener {
            findNavController().navigate(R.id.action_rideTrackerFragment_to_rideHistoryFragment)
        }

        viewModel.activeRide.observe(viewLifecycleOwner) { ride -> render(ride) }

        return view
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun onStartStopClicked() {
        if (viewModel.activeRide.value != null) {
            viewModel.stopRide()
            return
        }
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            viewModel.startRide()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun render(ride: Ride?) {
        if (ride == null) {
            statusText.text = getString(R.string.ride_status_idle)
            startStopButton.text = getString(R.string.ride_action_start)
        } else {
            val distanceKm = ride.distanceMeters / 1000.0
            statusText.text = getString(
                R.string.ride_status_riding,
                String.format(Locale.getDefault(), "%.2f", distanceKm)
            )
            startStopButton.text = getString(R.string.ride_action_stop)
        }
    }
}
