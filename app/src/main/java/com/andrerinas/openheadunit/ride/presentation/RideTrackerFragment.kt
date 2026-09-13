package com.andrerinas.openheadunit.ride.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ride.domain.Ride
import com.andrerinas.openheadunit.utils.RideInstrumentStyler
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Ride Tracker's idle/recording instrument. Two content groups (idle_content/recording_content
 * in fragment_ride_tracker.xml) share the screen; this toggles which is visible rather than
 * navigating to a second screen, since it's the same instrument reflecting whichever state real
 * data supports.
 *
 * Distance and the GPS accuracy figure shown while recording both come live from
 * RideTrackingService's own in-memory state (see RideTrackerViewModel), not a placeholder or a
 * polled recomputation. Deliberately still NOT shown: a live speed metric (needs its own signal
 * from RideLocationEngine, not wired up yet), or a "GPS acquiring/ready/degraded" verdict - a raw
 * accuracy number isn't the same claim as a designed readiness/quality model, and inventing one
 * here would violate the "no fabricated telemetry" rule this screen is otherwise built around.
 */
class RideTrackerFragment : Fragment() {

    private val viewModel: RideTrackerViewModel by viewModels()

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRide()
        render(viewModel.activeRide.value)
    }

    private lateinit var rootView: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recordingIndicatorRow: View

    // Idle content
    private lateinit var idleContent: View
    private lateinit var locationLabel: TextView
    private lateinit var locationStatusText: TextView
    private lateinit var readyLabel: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var lastRideLabel: TextView
    private lateinit var lastRideSummary: TextView
    private lateinit var historyLink: TextView
    private lateinit var savedPlacesLink: TextView
    private lateinit var recordsLink: TextView

    // Recording content
    private lateinit var recordingContent: View
    private lateinit var distanceLabel: TextView
    private lateinit var distanceValue: TextView
    private lateinit var elapsedLabel: TextView
    private lateinit var elapsedValue: TextView
    private lateinit var speedLabel: TextView
    private lateinit var speedValue: TextView
    private lateinit var gpsLabel: TextView
    private lateinit var gpsStatusText: TextView
    private lateinit var endRideButton: MaterialButton

    private var elapsedTickerJob: kotlinx.coroutines.Job? = null
    private var lastAcceptedAccuracyMeters: Float? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_ride_tracker, container, false)
        rootView = view

        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        recordingIndicatorRow = view.findViewById(R.id.recording_indicator_row)

        idleContent = view.findViewById(R.id.idle_content)
        locationLabel = view.findViewById(R.id.location_label)
        locationStatusText = view.findViewById(R.id.location_status_text)
        readyLabel = view.findViewById(R.id.ready_label)
        startStopButton = view.findViewById(R.id.start_stop_button)
        startStopButton.setOnClickListener { onStartStopClicked() }
        lastRideLabel = view.findViewById(R.id.last_ride_label)
        lastRideSummary = view.findViewById(R.id.last_ride_summary)
        historyLink = view.findViewById(R.id.history_link)
        historyLink.setOnClickListener {
            findNavController().navigate(R.id.action_rideTrackerFragment_to_rideHistoryFragment)
        }
        savedPlacesLink = view.findViewById(R.id.saved_places_link)
        savedPlacesLink.setOnClickListener {
            findNavController().navigate(R.id.action_rideTrackerFragment_to_savedPlacesFragment)
        }
        recordsLink = view.findViewById(R.id.records_link)
        recordsLink.setOnClickListener {
            findNavController().navigate(R.id.action_rideTrackerFragment_to_recordsFragment)
        }

        recordingContent = view.findViewById(R.id.recording_content)
        distanceLabel = view.findViewById<View>(R.id.stat_distance).findViewById(R.id.stat_label)
        distanceValue = view.findViewById<View>(R.id.stat_distance).findViewById(R.id.stat_value)
        distanceLabel.text = getString(R.string.ride_stat_distance_label)
        elapsedLabel = view.findViewById<View>(R.id.stat_elapsed).findViewById(R.id.stat_label)
        elapsedValue = view.findViewById<View>(R.id.stat_elapsed).findViewById(R.id.stat_value)
        elapsedLabel.text = getString(R.string.ride_stat_elapsed_label)
        speedLabel = view.findViewById<View>(R.id.stat_speed).findViewById(R.id.stat_label)
        speedValue = view.findViewById<View>(R.id.stat_speed).findViewById(R.id.stat_value)
        speedLabel.text = getString(R.string.ride_stat_speed_label)
        speedValue.text = getString(R.string.ride_stat_speed_unavailable)
        gpsLabel = view.findViewById(R.id.gps_label)
        gpsStatusText = view.findViewById(R.id.gps_status_text)
        endRideButton = view.findViewById(R.id.end_ride_button)
        endRideButton.setOnClickListener { onStartStopClicked() }

        viewModel.activeRide.observe(viewLifecycleOwner) { ride -> render(ride) }
        viewModel.distanceMeters.observe(viewLifecycleOwner) { meters ->
            distanceValue.text = getString(R.string.ride_stat_km_value, meters / 1000.0)
        }
        viewModel.lastAcceptedAccuracyMeters.observe(viewLifecycleOwner) { accuracy ->
            lastAcceptedAccuracyMeters = accuracy
            if (viewModel.activeRide.value != null) updateGpsStatusText()
        }
        viewModel.lastAcceptedSpeedMetersPerSecond.observe(viewLifecycleOwner) { speed ->
            speedValue.text = if (speed != null) {
                getString(R.string.ride_stat_kmh_value, speed * 3.6)
            } else {
                getString(R.string.ride_stat_speed_unavailable)
            }
        }
        viewModel.lastCompletedRide.observe(viewLifecycleOwner) { renderLastRide(it) }

        return view
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        render(viewModel.activeRide.value)
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun isLocationServiceEnabled(): Boolean {
        val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }

    private fun onStartStopClicked() {
        if (viewModel.activeRide.value != null) {
            // Ending ends recording and saves what's there - not something a stray tap on a
            // vibrating, mounted phone should be able to do without an extra step.
            // Deliberately the app's standard DarkAlertDialog, not a Ride-scoped dialog theme:
            // every other confirmation dialog in the app (Reset to Defaults, GLES prompts, etc.)
            // already uses it, and building a second dialog theme just for this one confirmation
            // is exactly the kind of unrelated theme-system scope this pass is meant to avoid.
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.ride_stop_confirm_title)
                .setMessage(R.string.ride_stop_confirm_message)
                .setPositiveButton(R.string.ride_action_end) { _, _ -> viewModel.stopRide() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        if (!hasLocationPermission()) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else if (isLocationServiceEnabled()) {
            viewModel.startRide()
        }
        // Location services off: Start stays disabled (see render()); nothing to launch.
    }

    private fun render(ride: Ride?) {
        val isRiding = ride != null
        idleContent.visibility = if (isRiding) View.GONE else View.VISIBLE
        recordingContent.visibility = if (isRiding) View.VISIBLE else View.GONE
        recordingIndicatorRow.visibility = if (isRiding) View.VISIBLE else View.GONE

        if (isRiding) {
            startElapsedTicker(ride!!)
        } else {
            stopElapsedTicker()
            renderIdleReadiness()
        }

        updateGpsStatusText()

        RideInstrumentStyler.style(
            root = rootView,
            primaryTexts = listOfNotNull(
                locationStatusText.takeIf { !isRiding },
                distanceValue.takeIf { isRiding },
                elapsedValue.takeIf { isRiding },
                speedValue.takeIf { isRiding }
            ),
            secondaryTexts = listOfNotNull(
                readyLabel, lastRideSummary, historyLink, savedPlacesLink, recordsLink, gpsStatusText,
                distanceLabel.takeIf { isRiding }, elapsedLabel.takeIf { isRiding },
                speedLabel.takeIf { isRiding }
            ),
            primaryButtons = if (!isRiding) listOf(startStopButton) else emptyList(),
            routinePanelButtons = if (isRiding) listOf(endRideButton) else emptyList(),
            extraSurfaces = listOfNotNull(toolbar.parent as? View)
        )
    }

    /** While riding and a fix has actually been accepted, appends its real accuracy - honest
     *  because it's the same figure RideTrackingService itself just accepted the point on, not an
     *  invented "GPS lock" claim (see this class's KDoc). */
    private fun updateGpsStatusText() {
        val isRiding = viewModel.activeRide.value != null
        val accuracy = lastAcceptedAccuracyMeters
        gpsStatusText.text = when {
            !hasLocationPermission() -> getString(R.string.ride_gps_status_permission_required)
            !isLocationServiceEnabled() -> getString(R.string.ride_gps_status_location_disabled)
            isRiding && accuracy != null -> getString(R.string.ride_gps_status_with_accuracy, accuracy)
            else -> getString(R.string.ride_gps_status_available)
        }
    }

    private fun renderIdleReadiness() {
        val hasPermission = hasLocationPermission()
        val locationEnabled = isLocationServiceEnabled()
        val isReady = hasPermission && locationEnabled
        locationStatusText.text = when {
            !hasPermission -> getString(R.string.ride_status_permission_required)
            !locationEnabled -> getString(R.string.ride_status_location_disabled)
            else -> getString(R.string.ride_gps_status_available)
        }
        readyLabel.visibility = if (isReady) View.VISIBLE else View.GONE
        startStopButton.text = getString(R.string.ride_action_start)
        val canTap = !hasPermission || locationEnabled
        startStopButton.isEnabled = canTap
        // RideInstrumentStyler sets a plain (non-stateful) background/text color, which bypasses
        // MaterialButton's own disabled-state dimming - "Disabled controls must remain visually
        // distinct" (design spec), so dim explicitly rather than let it look identical to enabled.
        startStopButton.alpha = if (canTap) 1f else 0.4f
    }

    private fun renderLastRide(lastRide: Ride?) {
        val hasLastRide = lastRide != null
        lastRideLabel.visibility = if (hasLastRide) View.VISIBLE else View.GONE
        lastRideSummary.visibility = if (hasLastRide) View.VISIBLE else View.GONE
        if (lastRide != null) {
            val distanceKm = lastRide.distanceMeters / 1000.0
            val durationMinutes = (lastRide.durationMs / 60000.0).let { Math.round(it) }
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                .format(Date(lastRide.startTimestampMs))
            lastRideSummary.text = getString(
                R.string.ride_last_ride_summary, distanceKm, durationMinutes, date
            )
        }
    }

    /** Ticks every second while riding: elapsed time client-side from the ride's real recorded
     *  start timestamp (cheap, no I/O) - distance and GPS accuracy update independently, live,
     *  via RideTrackerViewModel's collection of RideTrackingService.liveState, not from this
     *  ticker. Cancelled/restarted automatically with the Fragment's STARTED state via
     *  repeatOnLifecycle, so it doesn't tick while backgrounded. */
    private fun startElapsedTicker(ride: Ride) {
        stopElapsedTicker()
        elapsedTickerJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val elapsedMs = System.currentTimeMillis() - ride.startTimestampMs
                    elapsedValue.text = formatElapsed(elapsedMs)
                    delay(1000)
                }
            }
        }
    }

    private fun stopElapsedTicker() {
        elapsedTickerJob?.cancel()
        elapsedTickerJob = null
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return getString(R.string.ride_elapsed_format, hours, minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopElapsedTicker()
    }
}
