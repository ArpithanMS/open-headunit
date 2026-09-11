package com.andrerinas.openheadunit.ride.presentation

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ride.domain.Ride
import com.andrerinas.openheadunit.ride.domain.RideClassification
import com.andrerinas.openheadunit.ride.domain.RideClassifier
import com.andrerinas.openheadunit.ride.domain.SavedPlace
import com.andrerinas.openheadunit.ride.domain.SavedPlaceRole
import com.andrerinas.openheadunit.utils.RideInstrumentStyler
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class RideHistoryAdapter(private val onRideClicked: (Ride) -> Unit) :
    RecyclerView.Adapter<RideHistoryAdapter.ViewHolder>() {

    private var rides: List<Ride> = emptyList()
    private var savedPlaces: List<SavedPlace> = emptyList()

    fun submitList(newRides: List<Ride>) {
        rides = newRides
        notifyDataSetChanged()
    }

    /** See RideHistoryFragment.onResume() - refreshed independently of [submitList] since saved
     *  places change far less often than the ride list itself. */
    fun updateSavedPlaces(places: List<SavedPlace>) {
        savedPlaces = places
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_ride, parent, false)
        return ViewHolder(view, onRideClicked)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(rides[position], savedPlaces)

    override fun getItemCount(): Int = rides.size

    class ViewHolder(itemView: View, private val onRideClicked: (Ride) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val dateText: TextView = itemView.findViewById(R.id.ride_item_date)
        private val summaryText: TextView = itemView.findViewById(R.id.ride_item_summary)
        private val classificationText: TextView = itemView.findViewById(R.id.ride_item_classification)
        private val dateFormat =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())

        fun bind(ride: Ride, savedPlaces: List<SavedPlace>) {
            val context = itemView.context
            val dateLabel = dateFormat.format(Date(ride.startTimestampMs))
            dateText.text = dateLabel
            val distanceKm = ride.distanceMeters / 1000.0
            val durationMinutes = (ride.durationMs / 60000.0).roundToInt()
            summaryText.text = context.getString(
                R.string.ride_history_item_summary, distanceKm, durationMinutes
            )

            val classification = RideClassifier.classify(ride, savedPlaces)
            val classificationLabel = classification?.let { formatClassification(context, it) }
            classificationText.text = classificationLabel
            classificationText.visibility = if (classificationLabel != null) View.VISIBLE else View.GONE

            itemView.setOnClickListener { onRideClicked(ride) }
            itemView.isFocusable = true
            // One clean spoken sentence instead of TalkBack's default child-concatenation
            // fallback (which reads the TextViews' raw text back to back, punctuation and all).
            itemView.contentDescription = buildString {
                append(context.getString(R.string.ride_history_item_description, dateLabel, distanceKm, durationMinutes))
                if (classificationLabel != null) append(", ").append(classificationLabel)
            }

            RideInstrumentStyler.applyTextOnly(
                primary = listOf(dateText), secondary = listOf(summaryText, classificationText)
            )
            itemView.setBackgroundResource(R.drawable.bg_ride_panel_selector)
        }

        private fun formatClassification(context: Context, classification: RideClassification): String? {
            val from = classification.from?.let { roleName(context, it) }
            val to = classification.to?.let { roleName(context, it) }
            return when {
                from != null && to != null -> context.getString(R.string.ride_classification_both, from, to)
                from != null -> context.getString(R.string.ride_classification_from_only, from)
                to != null -> context.getString(R.string.ride_classification_to_only, to)
                else -> null
            }
        }

        private fun roleName(context: Context, role: SavedPlaceRole): String = when (role) {
            SavedPlaceRole.HOME -> context.getString(R.string.ride_saved_place_home)
            SavedPlaceRole.WORK -> context.getString(R.string.ride_saved_place_work)
        }
    }
}
