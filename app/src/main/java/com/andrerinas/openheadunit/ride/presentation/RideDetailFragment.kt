package com.andrerinas.openheadunit.ride.presentation

import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.Group
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ride.domain.RidePoint
import com.andrerinas.openheadunit.ride.domain.RouteSpeedColorScale
import com.andrerinas.openheadunit.ride.domain.RouteSpeedGradient
import com.andrerinas.openheadunit.ride.domain.RouteSpeedPalette
import com.andrerinas.openheadunit.ride.domain.RouteSpeedSegment
import com.andrerinas.openheadunit.ride.domain.RouteStatistics
import com.andrerinas.openheadunit.utils.RideInstrumentStyler
import com.google.android.material.appbar.MaterialToolbar
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A single ride's route plus its full statistics. [RouteGeometryView] is the guaranteed-visible
 * baseline (no network, no minSdk requirement) - a real MapLibre basemap is layered on top of it
 * as an enhancement, only on API 21+ (MapLibre's own minSdk, above this app's github-flavor
 * minSdk 16) and only once a style actually finishes loading over the network. See [isMapSupported]
 * and [setUpMap]: this app must not lose route visualization just because a device is older than
 * MapLibre supports, or because the viewer has no signal.
 *
 * The route line is colored per-leg by that leg's speed percentile within this ride's own speed
 * distribution - see [RouteSpeedColorScale] - a retrospective analysis of already-recorded GPS
 * samples, not a live/fabricated telemetry signal, so it doesn't run into the "no fabricated data"
 * rule the rest of Ride Tracker is built around.
 */
class RideDetailFragment : Fragment() {

    private val rideId: Long by lazy { requireArguments().getLong(ARG_RIDE_ID) }

    private val viewModel: RideDetailViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RideDetailViewModel(requireActivity().application, rideId) as T
        }
    }

    private lateinit var routeGeometryView: RouteGeometryView
    private var mapView: MapView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_ride_detail, container, false)

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        routeGeometryView = view.findViewById(R.id.route_geometry_view)

        val dateText = view.findViewById<TextView>(R.id.ride_detail_date)
        val gpsQualityFootnote = view.findViewById<TextView>(R.id.gps_quality_footnote)
        val foundContentGroup = view.findViewById<Group>(R.id.ride_found_content_group)
        val notFoundMessage = view.findViewById<TextView>(R.id.ride_not_found_message)
        // app_bar already carries its own fixed scrim in XML (contrast protection over the
        // route, not theme-driven), and the map itself must never get a painted background the
        // way RideInstrumentStyler.style() would apply to a root view - that would hide the
        // tiles - so only the floating text gets themed here.
        RideInstrumentStyler.applyTextOnly(primary = emptyList(), secondary = listOf(dateText, notFoundMessage))

        if (isMapSupported()) {
            setUpMap(view, savedInstanceState)
        } else {
            // route_geometry_view above is already a complete rendering on its own - there is
            // nothing broken to recover from here, just no basemap layer to add on this device.
            view.findViewById<View>(R.id.map_zoom_controls).visibility = View.GONE
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                null -> Unit // still loading
                is RideDetailViewModel.UiState.NotFound -> {
                    foundContentGroup.visibility = View.GONE
                    notFoundMessage.visibility = View.VISIBLE
                    routeGeometryView.setRoute(emptyList())
                }
                is RideDetailViewModel.UiState.Found -> {
                    foundContentGroup.visibility = View.VISIBLE
                    notFoundMessage.visibility = View.GONE

                    dateText.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                        .format(Date(state.ride.startTimestampMs))

                    routeGeometryView.setRoute(state.acceptedRoutePoints)

                    mapView?.getMapAsync { map ->
                        map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) {
                            renderRoute(map = map, points = state.acceptedRoutePoints, segments = state.speedSegments)
                            // The enhanced map now shows real tiles and the same route - the
                            // always-on Canvas baseline has done its job of being visible instantly.
                            routeGeometryView.visibility = View.GONE
                        }
                    }

                    gpsQualityFootnote.text = getString(
                        R.string.ride_stat_gps_quality_value,
                        state.routeStatistics.acceptedPointCount, state.routeStatistics.totalPointCount,
                        state.routeStatistics.averageAccuracyMeters.roundToInt()
                    )

                    bindStats(view, state.ride.durationMs, state.routeStatistics)
                }
            }
        }

        return view
    }

    private fun isMapSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    private fun setUpMap(root: View, savedInstanceState: Bundle?) {
        // Application context, not the Fragment/Activity one - MapLibre.getInstance() is a
        // process-wide singleton that otherwise ends up holding a long-lived reference to
        // whichever Activity happened to create it first.
        MapLibre.getInstance(requireContext().applicationContext)

        val newMapView = MapView(requireContext())
        newMapView.onCreate(savedInstanceState)
        root.findViewById<FrameLayout>(R.id.map_container).addView(
            newMapView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        mapView = newMapView

        root.findViewById<TextView>(R.id.zoom_in_button).setOnClickListener {
            newMapView.getMapAsync { it.easeCamera(CameraUpdateFactory.zoomIn()) }
        }
        root.findViewById<TextView>(R.id.zoom_out_button).setOnClickListener {
            newMapView.getMapAsync { it.easeCamera(CameraUpdateFactory.zoomOut()) }
        }
    }

    private fun renderRoute(map: MapLibreMap, points: List<RidePoint>, segments: List<RouteSpeedSegment>) {
        val style = map.style ?: return
        if (points.isEmpty()) return

        if (points.size >= 2) {
            val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
            val existingRouteSource = style.getSource(SOURCE_ID_ROUTE) as? GeoJsonSource
            if (existingRouteSource != null) {
                existingRouteSource.setGeoJson(line)
            } else {
                // lineMetrics is what makes line-progress (and therefore a gradient) available at
                // all - without it MapLibre has no per-vertex position along the line to key off.
                style.addSource(GeoJsonSource(SOURCE_ID_ROUTE, line, GeoJsonOptions().withLineMetrics(true)))
                style.addLayer(
                    LineLayer(LAYER_ID_ROUTE, SOURCE_ID_ROUTE).withProperties(
                        PropertyFactory.lineGradient(speedGradientExpression(points, segments)),
                        PropertyFactory.lineWidth(ROUTE_LINE_WIDTH),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                )
            }
        }

        addOrUpdateMarker(style, SOURCE_ID_MARKER_START, LAYER_ID_MARKER_START, points.first(), R.color.ride_text_primary)
        addOrUpdateMarker(style, SOURCE_ID_MARKER_END, LAYER_ID_MARKER_END, points.last(), R.color.ride_text_tertiary)

        fitCameraToRoute(map, points)
    }

    /**
     * A single continuous line (not one tiny LineString per leg) colored along its length by
     * [Expression.lineProgress] via `line-gradient`. This is deliberately NOT thousands of
     * individually-colored 2-point segments: MapLibre's GeoJSON tiling simplifies/drops sub-pixel
     * geometry per tile at low zoom, and a leg that's only a few metres long (typical GPS sample
     * spacing) is sub-pixel as soon as a multi-km ride is zoomed to fit the screen - in practice
     * this decimated the route down to nothing. A single LineString survives that simplification
     * (only its vertex density drops), which is why this is the standard technique for exactly
     * this "color a route by speed" use case.
     *
     * All the percentile/color math itself lives in [RouteSpeedColorScale] (no MapLibre
     * dependency there, so it's unit-testable) - this just turns its output into map draw calls.
     */
    private fun speedGradientExpression(points: List<RidePoint>, segments: List<RouteSpeedSegment>): Expression {
        val ctx = requireContext()
        val palette = RouteSpeedPalette(
            slowest = ContextCompat.getColor(ctx, R.color.ride_speed_slowest),
            belowAverage = ContextCompat.getColor(ctx, R.color.ride_speed_below_average),
            average = ContextCompat.getColor(ctx, R.color.ride_speed_average),
            aboveAverage = ContextCompat.getColor(ctx, R.color.ride_speed_above_average),
            fast = ContextCompat.getColor(ctx, R.color.ride_speed_fast),
            fastest = ContextCompat.getColor(ctx, R.color.ride_speed_fastest),
        )

        val breakpoints = RouteSpeedColorScale.breakpoints(segments)
        if (breakpoints == null || !breakpoints.hasMeaningfulSpread) {
            // No segments, or every leg was essentially the same speed - a flat, neutral line is
            // correct either way. Percentile banding would otherwise paint a uniform-speed ride as
            // uniformly "slowest", since every value would sit at/below p15.
            return Expression.interpolate(
                Expression.linear(), Expression.lineProgress(),
                Expression.stop(0.0, Expression.color(palette.average)), Expression.stop(1.0, Expression.color(palette.average)),
            )
        }

        val stops = RouteSpeedGradient.buildStops(points, segments, MAX_GRADIENT_STOPS)
        val stopExpressions = stops.map {
            Expression.stop(it.progress, Expression.color(RouteSpeedColorScale.colorForSpeed(it.speedMetersPerSecond, breakpoints, palette)))
        }.toTypedArray()
        return Expression.interpolate(Expression.linear(), Expression.lineProgress(), *stopExpressions)
    }

    private fun addOrUpdateMarker(style: Style, sourceId: String, layerId: String, point: RidePoint, colorRes: Int) {
        val feature = Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
        val existing = style.getSource(sourceId) as? GeoJsonSource
        if (existing != null) {
            existing.setGeoJson(feature)
            return
        }
        style.addSource(GeoJsonSource(sourceId, feature))
        style.addLayer(
            CircleLayer(layerId, sourceId).withProperties(
                PropertyFactory.circleRadius(MARKER_RADIUS),
                PropertyFactory.circleColor(ContextCompat.getColor(requireContext(), colorRes)),
                PropertyFactory.circleStrokeWidth(MARKER_STROKE_WIDTH),
                PropertyFactory.circleStrokeColor(ContextCompat.getColor(requireContext(), R.color.ride_canvas)),
            )
        )
    }

    private fun fitCameraToRoute(map: MapLibreMap, points: List<RidePoint>) {
        if (points.size == 1) {
            val single = points.first()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(single.latitude, single.longitude), SINGLE_POINT_ZOOM))
            return
        }
        val bounds = LatLngBounds.Builder().apply {
            points.forEach { include(LatLng(it.latitude, it.longitude)) }
        }.build()
        val paddingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, ROUTE_CAMERA_PADDING_DP, resources.displayMetrics
        ).toInt()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
    }

    private fun bindStats(root: View, rideDurationMs: Long, stats: RouteStatistics) {
        bindStat(root, R.id.stat_distance, R.string.ride_stat_distance, formatKm(stats.distanceMeters))
        // Ride.durationMs (start tap to stop/reconciliation), not RouteStatistics.durationMs
        // (first accepted GPS fix to last) - the latter silently excludes GPS-acquisition time
        // and would disagree with History's list and the live elapsed clock shown while riding.
        bindStat(root, R.id.stat_duration, R.string.ride_stat_duration, formatDuration(rideDurationMs))
        bindStat(root, R.id.stat_moving_time, R.string.ride_stat_moving_time, formatDuration(stats.movingDurationMs))
        bindStat(root, R.id.stat_avg_speed, R.string.ride_stat_avg_speed, formatKmh(stats.averageMovingSpeedMetersPerSecond))
        bindStat(root, R.id.stat_max_speed, R.string.ride_stat_max_speed, formatKmh(stats.maxSpeedMetersPerSecond))
    }

    private fun bindStat(root: View, rowId: Int, labelRes: Int, value: String) {
        val row = root.findViewById<View>(rowId)
        val label = row.findViewById<TextView>(R.id.stat_label)
        val statValue = row.findViewById<TextView>(R.id.stat_value)
        label.text = getString(labelRes)
        statValue.text = value
        // Text-only: these rows are transparent, floating directly over the map (see
        // fragment_ride_detail.xml) - RideInstrumentStyler.style() also paints a root background,
        // which would wrongly cover the map with an opaque box, so restyle the text directly.
        RideInstrumentStyler.applyTextOnly(primary = statValue, secondary = label)
    }

    private fun formatKm(meters: Double): String =
        getString(R.string.ride_stat_km_value, meters / 1000.0)

    private fun formatKmh(metersPerSecond: Double): String =
        getString(R.string.ride_stat_kmh_value, metersPerSecond * 3.6)

    private fun formatDuration(durationMs: Long): String {
        // Rounds rather than floors, to match RideHistoryAdapter's "X min" formatting - otherwise
        // the same ride can read 208 min in History (rounded) and "3h 28m" = 208 min here too, but
        // the two came from floor vs round and would silently drift apart by a minute elsewhere.
        val totalMinutes = (durationMs / 60_000.0).roundToInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            getString(R.string.ride_stat_duration_hours_minutes, hours, minutes)
        } else {
            getString(R.string.ride_stat_duration_minutes, minutes)
        }
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView?.onStop()
        super.onStop()
    }

    override fun onDestroyView() {
        mapView?.onDestroy()
        mapView = null
        super.onDestroyView()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    companion object {
        const val ARG_RIDE_ID = "rideId"

        // OpenFreeMap's Positron style: muted/light basemap, deliberately not a dark style - it's
        // what makes the speed-colored route (and the app's own dark UI chrome) stand out clearly
        // rather than competing with a busy/colorful basemap. No API key needed.
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

        private const val SOURCE_ID_ROUTE = "ride-route"
        private const val LAYER_ID_ROUTE = "ride-route-line"
        private const val SOURCE_ID_MARKER_START = "ride-marker-start"
        private const val LAYER_ID_MARKER_START = "ride-marker-start-circle"
        private const val SOURCE_ID_MARKER_END = "ride-marker-end"
        private const val LAYER_ID_MARKER_END = "ride-marker-end-circle"

        // Caps the gradient expression's stop count regardless of ride length - indicative
        // resolution, not one stop per raw GPS fix (see buildGradientStops).
        private const val MAX_GRADIENT_STOPS = 256

        private const val ROUTE_LINE_WIDTH = 4.5f
        private const val MARKER_RADIUS = 6f
        private const val MARKER_STROKE_WIDTH = 2f
        private const val ROUTE_CAMERA_PADDING_DP = 32f
        private const val SINGLE_POINT_ZOOM = 15.0
    }
}
