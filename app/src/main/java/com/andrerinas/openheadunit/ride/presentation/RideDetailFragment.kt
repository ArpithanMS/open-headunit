package com.andrerinas.openheadunit.ride.presentation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ride.domain.RouteStatistics
import com.andrerinas.openheadunit.utils.RideInstrumentStyler
import com.google.android.material.appbar.MaterialToolbar
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** A single ride's route (drawn as geometry - see RouteGeometryView) plus its full statistics. */
class RideDetailFragment : Fragment() {

    private val rideId: Long by lazy { requireArguments().getLong(ARG_RIDE_ID) }

    private val viewModel: RideDetailViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RideDetailViewModel(requireActivity().application, rideId) as T
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_ride_detail, container, false)

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        val routeView = view.findViewById<RouteGeometryView>(R.id.route_geometry_view)
        val dateText = view.findViewById<TextView>(R.id.ride_detail_date)

        // routeView, not the ConstraintLayout root, is what actually fills the screen (see the
        // layout's own comment: "the route IS the background") - style it directly rather than
        // the root, which routeView otherwise fully obscures.
        RideInstrumentStyler.style(root = routeView, secondaryTexts = listOf(dateText))
        // app_bar already carries its own fixed scrim in XML (contrast protection over the
        // route, not theme-driven), so it doesn't need an extraSurfaces entry here the way the
        // opaque ?attr/colorSurface app bars on Ride Tracker/History do.

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state == null) return@observe
            routeView.setRoute(state.acceptedRoutePoints)

            state.ride?.let { ride ->
                dateText.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                    .format(Date(ride.startTimestampMs))
            }

            bindStats(view, state.routeStatistics)
        }

        return view
    }

    private fun bindStats(root: View, stats: RouteStatistics) {
        bindStat(root, R.id.stat_distance, R.string.ride_stat_distance, formatKm(stats.distanceMeters))
        bindStat(root, R.id.stat_duration, R.string.ride_stat_duration, formatDuration(stats.durationMs))
        bindStat(root, R.id.stat_moving_time, R.string.ride_stat_moving_time, formatDuration(stats.movingDurationMs))
        bindStat(root, R.id.stat_avg_speed, R.string.ride_stat_avg_speed, formatKmh(stats.averageMovingSpeedMetersPerSecond))
        bindStat(root, R.id.stat_max_speed, R.string.ride_stat_max_speed, formatKmh(stats.maxSpeedMetersPerSecond))
        bindStat(
            root, R.id.stat_gps_quality, R.string.ride_stat_gps_quality,
            getString(
                R.string.ride_stat_gps_quality_value,
                stats.acceptedPointCount, stats.totalPointCount,
                stats.averageAccuracyMeters.roundToInt()
            )
        )
    }

    private fun bindStat(root: View, rowId: Int, labelRes: Int, value: String) {
        val row = root.findViewById<View>(rowId)
        val label = row.findViewById<TextView>(R.id.stat_label)
        val statValue = row.findViewById<TextView>(R.id.stat_value)
        label.text = getString(labelRes)
        statValue.text = value
        // Text-only: these rows are transparent, floating directly over the route (see
        // fragment_ride_detail.xml) - RideInstrumentStyler.style() also paints a root background,
        // which would wrongly cover the route with an opaque box, so restyle the text directly.
        RideInstrumentStyler.applyTextOnly(primary = statValue, secondary = label)
    }

    private fun formatKm(meters: Double): String =
        getString(R.string.ride_stat_km_value, meters / 1000.0)

    private fun formatKmh(metersPerSecond: Double): String =
        getString(R.string.ride_stat_kmh_value, metersPerSecond * 3.6)

    private fun formatDuration(durationMs: Long): String {
        val totalMinutes = durationMs / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            getString(R.string.ride_stat_duration_hours_minutes, hours, minutes)
        } else {
            getString(R.string.ride_stat_duration_minutes, minutes)
        }
    }

    companion object {
        const val ARG_RIDE_ID = "rideId"
    }
}
