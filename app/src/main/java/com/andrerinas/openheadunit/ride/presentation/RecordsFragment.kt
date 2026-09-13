package com.andrerinas.openheadunit.ride.presentation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ride.domain.DayDistanceRecord
import com.andrerinas.openheadunit.ride.domain.MonthDistanceRecord
import com.andrerinas.openheadunit.ride.domain.RideLifetimeRecords
import com.andrerinas.openheadunit.ride.domain.RideRecordEntry
import com.andrerinas.openheadunit.utils.RideInstrumentStyler
import com.google.android.material.appbar.MaterialToolbar
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lifetime "personal records" derived from every recorded ride - see
 * [com.andrerinas.openheadunit.ride.domain.RideLifetimeRecordsCalculator]. Each row with a real
 * ride behind it (everything except the two calendar aggregates) is tappable through to that
 * ride's own Ride Detail.
 */
class RecordsFragment : Fragment() {

    private val viewModel: RecordsViewModel by viewModels()
    private lateinit var rows: RecordRows

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_records, container, false)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        val summaryText = view.findViewById<TextView>(R.id.records_summary)
        val emptyText = view.findViewById<TextView>(R.id.records_empty_text)
        val scroll = view.findViewById<View>(R.id.records_scroll)

        rows = RecordRows(view)
        RideInstrumentStyler.style(
            root = view,
            secondaryTexts = listOf(summaryText, emptyText) + rows.allLabelsAndSubtexts(),
            primaryTexts = rows.allValues(),
            extraSurfaces = listOf(toolbar),
        )

        viewModel.records.observe(viewLifecycleOwner) { records ->
            if (records == null) return@observe // still loading
            val isEmpty = records.totalRideCount == 0
            emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            scroll.visibility = if (isEmpty) View.GONE else View.VISIBLE
            if (isEmpty) return@observe

            summaryText.text = getString(
                R.string.ride_records_summary, records.totalLifetimeDistanceMeters / 1000.0, records.totalRideCount
            )
            bind(records)
        }

        return view
    }

    private fun bind(records: RideLifetimeRecords) {
        rows.longestTripDistance.bindRide(
            R.string.ride_record_longest_trip_distance, records.longestTripByDistance, ::formatKm
        )
        rows.longestTripDuration.bindRide(
            R.string.ride_record_longest_trip_duration, records.longestTripByDuration, ::formatDuration
        )
        rows.longestContinuousDuration.bindRide(
            R.string.ride_record_longest_continuous_duration, records.longestContinuousRideByDuration, ::formatDuration
        )
        rows.longestContinuousDistance.bindRide(
            R.string.ride_record_longest_continuous_distance, records.longestContinuousRideByDistance, ::formatKm
        )
        rows.highestSpeed.bindRide(
            R.string.ride_record_highest_speed, records.highestRecordedSpeed, ::formatKmh
        )
        rows.highestAvgSpeed.bindRide(
            R.string.ride_record_highest_avg_speed, records.highestAverageMovingSpeed, ::formatKmh
        )
        rows.mostInADay.bindDay(R.string.ride_record_most_in_a_day, records.mostDistanceInADay)
        rows.mostInAMonth.bindMonth(R.string.ride_record_most_in_a_month, records.mostDistanceInAMonth)
        rows.farthestFromHome.bindRide(
            R.string.ride_record_farthest_from_home, records.farthestPointFromHome, ::formatKm
        )
    }

    private fun formatKm(meters: Double): String = getString(R.string.ride_stat_km_value, meters / 1000.0)

    private fun formatKmh(metersPerSecond: Double): String =
        getString(R.string.ride_stat_kmh_value, metersPerSecond * 3.6)

    private fun formatDuration(durationMs: Double): String {
        val totalMinutes = (durationMs / 60_000.0).roundToInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            getString(R.string.ride_stat_duration_hours_minutes, hours, minutes)
        } else {
            getString(R.string.ride_stat_duration_minutes, minutes)
        }
    }

    /** One record row: title, headline value, and a "which ride/when" subtext - tap-through to
     *  that ride's own detail screen when [entry] carries a real rideId. */
    private inner class RecordRow(root: View, rowId: Int) {
        private val row: View = root.findViewById(rowId)
        val label: TextView = row.findViewById(R.id.record_label)
        val value: TextView = row.findViewById(R.id.record_value)
        val subtext: TextView = row.findViewById(R.id.record_subtext)

        fun bindRide(labelRes: Int, entry: RideRecordEntry?, formatValue: (Double) -> String) {
            label.text = getString(labelRes)
            if (entry == null) {
                value.text = "—"
                subtext.text = getString(R.string.ride_record_not_set)
                row.setOnClickListener(null)
                row.isClickable = false
                return
            }
            value.text = formatValue(entry.value)
            val dateLabel = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                .format(Date(entry.achievedAtMs))
            subtext.text = getString(R.string.ride_record_subtext_ride, dateLabel, entry.rideId)
            row.isClickable = true
            row.setOnClickListener {
                findNavController().navigate(
                    R.id.action_recordsFragment_to_rideDetailFragment,
                    bundleOf(RideDetailFragment.ARG_RIDE_ID to entry.rideId)
                )
            }
        }

        fun bindDay(labelRes: Int, record: DayDistanceRecord?) {
            label.text = getString(labelRes)
            if (record == null) {
                value.text = "—"
                subtext.text = getString(R.string.ride_record_not_set)
                return
            }
            value.text = formatKm(record.distanceMeters)
            subtext.text = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                .format(Date(record.sampleTimestampMs))
        }

        fun bindMonth(labelRes: Int, record: MonthDistanceRecord?) {
            label.text = getString(labelRes)
            if (record == null) {
                value.text = "—"
                subtext.text = getString(R.string.ride_record_not_set)
                return
            }
            value.text = formatKm(record.distanceMeters)
            val calendar = Calendar.getInstance().apply {
                set(record.year, record.month, 1)
            }
            subtext.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(calendar.time)
        }
    }

    /** Groups every row lookup in one place so onCreateView (styling) and bind() (data) don't
     *  each re-list all nine row ids separately. */
    private inner class RecordRows(root: View) {
        val longestTripDistance = RecordRow(root, R.id.record_longest_trip_distance)
        val longestTripDuration = RecordRow(root, R.id.record_longest_trip_duration)
        val longestContinuousDuration = RecordRow(root, R.id.record_longest_continuous_duration)
        val longestContinuousDistance = RecordRow(root, R.id.record_longest_continuous_distance)
        val highestSpeed = RecordRow(root, R.id.record_highest_speed)
        val highestAvgSpeed = RecordRow(root, R.id.record_highest_avg_speed)
        val mostInADay = RecordRow(root, R.id.record_most_in_a_day)
        val mostInAMonth = RecordRow(root, R.id.record_most_in_a_month)
        val farthestFromHome = RecordRow(root, R.id.record_farthest_from_home)

        private val all get() = listOf(
            longestTripDistance, longestTripDuration, longestContinuousDuration, longestContinuousDistance,
            highestSpeed, highestAvgSpeed, mostInADay, mostInAMonth, farthestFromHome,
        )

        fun allLabelsAndSubtexts(): List<TextView> = all.flatMap { listOf(it.label, it.subtext) }
        fun allValues(): List<TextView> = all.map { it.value }
    }
}
