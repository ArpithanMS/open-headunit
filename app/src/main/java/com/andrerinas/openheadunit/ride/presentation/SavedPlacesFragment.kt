package com.andrerinas.openheadunit.ride.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ride.RideComponent
import com.andrerinas.openheadunit.ride.RideEnginePreferences
import com.andrerinas.openheadunit.ride.domain.SavedPlace
import com.andrerinas.openheadunit.ride.domain.SavedPlaceRole
import com.andrerinas.openheadunit.ride.service.AutoRideMonitorService
import com.andrerinas.openheadunit.utils.RideInstrumentStyler
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Lets the user set (or clear) Home and Work - context [RideClassifier] uses to label finished
 * rides in History, never a gate on whether a ride is recorded (see [SavedPlace]'s KDoc). Manual
 * lat/lng entry works standalone; "Use Current Location" is a convenience that fills the same
 * fields with a fresh GPS fix rather than a separate input mode.
 */
class SavedPlacesFragment : Fragment() {

    private val repository by lazy { RideComponent.get(requireContext()).savedPlaceRepository }
    private val locationManager by lazy {
        requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private var pendingLocationEditor: PlaceEditor? = null
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingLocationEditor?.let { fetchCurrentLocationInto(it) }
        else pendingLocationEditor = null
    }

    private var autoDetectSwitch: Switch? = null
    private val requestAutoDetectPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            RideEnginePreferences.setAutoDetectionEnabled(requireContext(), true)
            AutoRideMonitorService.start(requireContext())
        } else {
            autoDetectSwitch?.setOnCheckedChangeListener(null)
            autoDetectSwitch?.isChecked = false
            autoDetectSwitch?.setOnCheckedChangeListener { _, isChecked -> onAutoDetectToggled(isChecked) }
            Toast.makeText(requireContext(), R.string.ride_auto_detect_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_saved_places, container, false)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        val descriptionText = view.findViewById<TextView>(R.id.saved_places_description)
        val autoDetectTitle = view.findViewById<TextView>(R.id.auto_detect_title)
        val autoDetectDescription = view.findViewById<TextView>(R.id.auto_detect_description)
        val autoDetectSwitch = view.findViewById<Switch>(R.id.auto_detect_switch)
        this.autoDetectSwitch = autoDetectSwitch

        val homeEditor = PlaceEditor(
            view.findViewById(R.id.home_place_editor), SavedPlaceRole.HOME, getString(R.string.ride_saved_place_home)
        )
        val workEditor = PlaceEditor(
            view.findViewById(R.id.work_place_editor), SavedPlaceRole.WORK, getString(R.string.ride_saved_place_work)
        )

        // Latitude/longitude/radius fields already have explicit colors in saved_place_editor.xml
        // (ride_text_primary, so typed input reads as clearly as possible) - deliberately not
        // included here, which would recolor them to the dimmer secondary tone.
        RideInstrumentStyler.style(
            root = view,
            primaryTexts = listOf(autoDetectTitle),
            secondaryTexts = listOf(
                descriptionText, autoDetectDescription,
                homeEditor.label, homeEditor.statusText, workEditor.label, workEditor.statusText,
            ),
            routinePanelButtons = listOf(
                homeEditor.useLocationButton, homeEditor.saveButton, homeEditor.clearButton,
                workEditor.useLocationButton, workEditor.saveButton, workEditor.clearButton,
            ),
            extraSurfaces = listOf(toolbar),
        )

        autoDetectSwitch.setOnCheckedChangeListener(null)
        autoDetectSwitch.isChecked = RideEnginePreferences.isAutoDetectionEnabled(requireContext())
        autoDetectSwitch.setOnCheckedChangeListener { _, isChecked -> onAutoDetectToggled(isChecked) }

        viewLifecycleOwner.lifecycleScope.launch {
            val places = repository.all().associateBy { it.role }
            homeEditor.bind(places[SavedPlaceRole.HOME])
            workEditor.bind(places[SavedPlaceRole.WORK])
        }

        return view
    }

    private fun onAutoDetectToggled(enabled: Boolean) {
        if (!enabled) {
            RideEnginePreferences.setAutoDetectionEnabled(requireContext(), false)
            AutoRideMonitorService.stop(requireContext())
            return
        }
        if (!hasLocationPermission()) {
            requestAutoDetectPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        RideEnginePreferences.setAutoDetectionEnabled(requireContext(), true)
        AutoRideMonitorService.start(requireContext())
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestCurrentLocationFor(editor: PlaceEditor) {
        if (!hasLocationPermission()) {
            pendingLocationEditor = editor
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        fetchCurrentLocationInto(editor)
    }

    /** A single fresh fix, not a subscription - unregisters itself the moment one arrives. Real
     *  GPS, not LocationHolder's possibly-stale last-known value: "current location" should mean
     *  current. */
    private fun fetchCurrentLocationInto(editor: PlaceEditor) {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(requireContext(), R.string.ride_saved_place_location_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        editor.statusText.text = getString(R.string.ride_saved_place_locating)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                if (!isAdded) return
                editor.latitudeField.setText(location.latitude.toString())
                editor.longitudeField.setText(location.longitude.toString())
                editor.refreshStatusFromFields()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                locationManager.removeUpdates(this)
            }
        }
        try {
            @Suppress("MissingPermission")
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), R.string.ride_saved_place_location_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Binds one Home or Work editor block - the two instances of saved_place_editor.xml share
     *  child view ids, so lookups are always scoped to this editor's own root (see the layout's
     *  own comment). */
    private inner class PlaceEditor(root: View, private val role: SavedPlaceRole, roleLabel: String) {
        val label: TextView = root.findViewById(R.id.place_label)
        val statusText: TextView = root.findViewById(R.id.place_status)
        val latitudeField: EditText = root.findViewById(R.id.place_latitude)
        val longitudeField: EditText = root.findViewById(R.id.place_longitude)
        private val radiusField: EditText = root.findViewById(R.id.place_radius)
        val useLocationButton: MaterialButton = root.findViewById(R.id.place_use_location_button)
        val saveButton: MaterialButton = root.findViewById(R.id.place_save_button)
        val clearButton: MaterialButton = root.findViewById(R.id.place_clear_button)

        init {
            label.text = roleLabel
            useLocationButton.setOnClickListener { requestCurrentLocationFor(this) }
            saveButton.setOnClickListener { save() }
            clearButton.setOnClickListener { clear() }
        }

        fun bind(existing: SavedPlace?) {
            if (existing != null) {
                latitudeField.setText(existing.latitude.toString())
                longitudeField.setText(existing.longitude.toString())
                radiusField.setText(existing.radiusMeters.toInt().toString())
            } else {
                radiusField.setText(DEFAULT_RADIUS_METERS.toString())
            }
            refreshStatusFromFields()
        }

        fun refreshStatusFromFields() {
            val place = parsedPlace()
            statusText.text = if (place != null) {
                getString(R.string.ride_saved_place_status, place.latitude, place.longitude, place.radiusMeters)
            } else {
                getString(R.string.ride_saved_place_not_set)
            }
        }

        private fun parsedPlace(): SavedPlace? {
            val lat = latitudeField.text.toString().toDoubleOrNull() ?: return null
            val lon = longitudeField.text.toString().toDoubleOrNull() ?: return null
            val radius = radiusField.text.toString().toFloatOrNull() ?: return null
            if (radius <= 0f) return null
            return SavedPlace(role, lat, lon, radius)
        }

        private fun save() {
            val place = parsedPlace()
            if (place == null) {
                Toast.makeText(requireContext(), R.string.ride_saved_place_invalid_input, Toast.LENGTH_SHORT).show()
                return
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repository.save(place)
                refreshStatusFromFields()
            }
        }

        private fun clear() {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.delete(role)
                latitudeField.text.clear()
                longitudeField.text.clear()
                radiusField.setText(DEFAULT_RADIUS_METERS.toString())
                refreshStatusFromFields()
            }
        }
    }

    private companion object {
        const val DEFAULT_RADIUS_METERS = 150
    }
}
