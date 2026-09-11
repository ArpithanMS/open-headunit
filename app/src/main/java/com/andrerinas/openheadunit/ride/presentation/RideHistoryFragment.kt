package com.andrerinas.openheadunit.ride.presentation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.os.bundleOf
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ride.RideComponent
import com.andrerinas.openheadunit.utils.RideInstrumentStyler
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

/** Finished rides, most recent first. See RideRepository.observeRideHistory(). */
class RideHistoryFragment : Fragment() {

    private lateinit var adapter: RideHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_ride_history, container, false)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        val recyclerView = view.findViewById<RecyclerView>(android.R.id.list)
        val emptyText = view.findViewById<TextView>(R.id.ride_history_empty_text)
        adapter = RideHistoryAdapter { ride ->
            findNavController().navigate(
                R.id.action_rideHistoryFragment_to_rideDetailFragment,
                bundleOf(RideDetailFragment.ARG_RIDE_ID to ride.id)
            )
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        RideInstrumentStyler.style(
            root = view,
            secondaryTexts = listOf(emptyText),
            extraSurfaces = listOfNotNull(toolbar.parent as? View)
        )

        val repository = RideComponent.get(requireContext()).repository
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeRideHistory().collect { rides ->
                    adapter.submitList(rides)
                    emptyText.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
                    recyclerView.visibility = if (rides.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        // A one-shot read, not a Flow - saved places change far less often than the ride list
        // itself (see SavedPlaceRepository), and this naturally picks up an edit made from
        // Settings the moment the user navigates back here.
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.updateSavedPlaces(RideComponent.get(requireContext()).savedPlaceRepository.all())
        }
    }
}
