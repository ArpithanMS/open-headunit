package com.andrerinas.openheadunit.ride.presentation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ride.domain.Ride
import com.andrerinas.openheadunit.utils.RideInstrumentStyler
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class RideHistoryAdapter(private val onRideClicked: (Ride) -> Unit) :
    RecyclerView.Adapter<RideHistoryAdapter.ViewHolder>() {

    private var rides: List<Ride> = emptyList()

    fun submitList(newRides: List<Ride>) {
        rides = newRides
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_ride, parent, false)
        return ViewHolder(view, onRideClicked)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(rides[position])

    override fun getItemCount(): Int = rides.size

    class ViewHolder(itemView: View, private val onRideClicked: (Ride) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val dateText: TextView = itemView.findViewById(R.id.ride_item_date)
        private val summaryText: TextView = itemView.findViewById(R.id.ride_item_summary)
        private val dateFormat =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())

        fun bind(ride: Ride) {
            dateText.text = dateFormat.format(Date(ride.startTimestampMs))
            val distanceKm = ride.distanceMeters / 1000.0
            val durationMinutes = (ride.durationMs / 60000.0).roundToInt()
            summaryText.text = itemView.context.getString(
                R.string.ride_history_item_summary, distanceKm, durationMinutes
            )
            itemView.setOnClickListener { onRideClicked(ride) }

            RideInstrumentStyler.applyTextOnly(primary = dateText, secondary = summaryText)
            itemView.setBackgroundResource(R.drawable.bg_ride_panel_selector)
        }
    }
}
