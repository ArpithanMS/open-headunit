package com.andrerinas.openheadunit.ride.presentation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ride.domain.RidePoint
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
 * A single ride's route on a real basemap (see [MAP_STYLE_URL]) plus its full statistics. The
 * route line is colored per-leg by that leg's speed relative to the ride's own average moving
 * speed - see [RouteSpeedSegment] - a retrospective analysis of already-recorded GPS samples, not
 * a live/fabricated telemetry signal, so it doesn't run into the "no fabricated data" rule the
 * rest of Ride Tracker is built around.
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

    private lateinit var mapView: MapView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Must run before the MapView below is inflated/created.
        MapLibre.getInstance(requireContext())
        val view = inflater.inflate(R.layout.fragment_ride_detail, container, false)

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        mapView = view.findViewById(R.id.map_view)
        mapView.onCreate(savedInstanceState)

        val dateText = view.findViewById<TextView>(R.id.ride_detail_date)
        val gpsQualityFootnote = view.findViewById<TextView>(R.id.gps_quality_footnote)
        // app_bar already carries its own fixed scrim in XML (contrast protection over the
        // route, not theme-driven), and the map itself must never get a painted background the
        // way RideInstrumentStyler.style() would apply to a root view - that would hide the
        // tiles - so only the floating text gets themed here.
        RideInstrumentStyler.applyTextOnly(primary = emptyList(), secondary = listOf(dateText))

        view.findViewById<TextView>(R.id.zoom_in_button).setOnClickListener {
            mapView.getMapAsync { it.easeCamera(CameraUpdateFactory.zoomIn()) }
        }
        view.findViewById<TextView>(R.id.zoom_out_button).setOnClickListener {
            mapView.getMapAsync { it.easeCamera(CameraUpdateFactory.zoomOut()) }
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state == null) return@observe

            state.ride?.let { ride ->
                dateText.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                    .format(Date(ride.startTimestampMs))
            }

            mapView.getMapAsync { map ->
                map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) {
                    renderRoute(map = map, points = state.acceptedRoutePoints, segments = state.speedSegments)
                }
            }

            gpsQualityFootnote.text = getString(
                R.string.ride_stat_gps_quality_value,
                state.routeStatistics.acceptedPointCount, state.routeStatistics.totalPointCount,
                state.routeStatistics.averageAccuracyMeters.roundToInt()
            )

            bindStats(view, state.routeStatistics)
        }

        return view
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
     * A single continuous line (not one tiny LineString per leg - see below) colored along its
     * length by [Expression.lineProgress] via `line-gradient`. This is deliberately NOT thousands
     * of individually-colored 2-point segments: MapLibre's GeoJSON tiling simplifies/drops
     * sub-pixel geometry per tile at low zoom, and a leg that's only a few metres long (typical
     * GPS sample spacing) is sub-pixel as soon as a multi-km ride is zoomed to fit the screen - in
     * practice this decimated the route down to nothing. A single LineString survives that
     * simplification (only its vertex density drops), which is why this is the standard technique
     * for exactly this "color a route by speed" use case.
     *
     * Color is ranked against this ride's own speed distribution (percentiles), not a ratio to the
     * mean - a stop-and-go city ride and a highway run should both use the full color range, not
     * have the city ride sit mostly in one color because its mean is dragged down by traffic.
     */
    private fun speedGradientExpression(points: List<RidePoint>, segments: List<RouteSpeedSegment>): Expression {
        val ctx = requireContext()
        val palette = SpeedPalette(
            slowest = ContextCompat.getColor(ctx, R.color.ride_speed_slowest),
            belowAverage = ContextCompat.getColor(ctx, R.color.ride_speed_below_average),
            average = ContextCompat.getColor(ctx, R.color.ride_speed_average),
            aboveAverage = ContextCompat.getColor(ctx, R.color.ride_speed_above_average),
            fast = ContextCompat.getColor(ctx, R.color.ride_speed_fast),
            fastest = ContextCompat.getColor(ctx, R.color.ride_speed_fastest),
        )

        if (segments.isEmpty()) {
            return Expression.interpolate(
                Expression.linear(), Expression.lineProgress(),
                Expression.stop(0.0, Expression.color(palette.average)), Expression.stop(1.0, Expression.color(palette.average)),
            )
        }

        val breakpoints = computeSpeedBreakpoints(segments)
        val stops = buildGradientStops(points, segments)
        val stopExpressions = stops.map {
            Expression.stop(it.progress, Expression.color(colorForSpeed(it.speedMetersPerSecond, breakpoints, palette)))
        }.toTypedArray()
        return Expression.interpolate(Expression.linear(), Expression.lineProgress(), *stopExpressions)
    }

    private data class SpeedPalette(
        val slowest: Int, val belowAverage: Int, val average: Int,
        val aboveAverage: Int, val fast: Int, val fastest: Int,
    )

    /** Nearest-rank percentiles of this ride's own leg speeds - the thresholds [colorForSpeed]
     *  bands against. Slowest ~15% / below-average / around-average / above-average / fastest ~15%
     *  (cyan 85th-95th, purple 95th-100th), matching the F1-telemetry-style scale this was asked
     *  for. */
    private data class SpeedBreakpoints(
        val p15: Double, val p40: Double, val p60: Double, val p85: Double, val p95: Double, val max: Double,
    )

    private fun computeSpeedBreakpoints(segments: List<RouteSpeedSegment>): SpeedBreakpoints {
        val sorted = segments.map { it.speedMetersPerSecond }.sorted()
        fun percentile(p: Double): Double {
            val index = (p / 100.0 * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }
        return SpeedBreakpoints(
            p15 = percentile(15.0), p40 = percentile(40.0), p60 = percentile(60.0),
            p85 = percentile(85.0), p95 = percentile(95.0), max = sorted.last(),
        )
    }

    private fun colorForSpeed(speed: Double, bp: SpeedBreakpoints, palette: SpeedPalette): Int = when {
        speed <= bp.p15 -> palette.slowest
        speed <= bp.p40 -> blendArgb(palette.slowest, palette.belowAverage, fractionBetween(speed, bp.p15, bp.p40))
        speed <= bp.p60 -> blendArgb(palette.belowAverage, palette.average, fractionBetween(speed, bp.p40, bp.p60))
        speed <= bp.p85 -> blendArgb(palette.average, palette.aboveAverage, fractionBetween(speed, bp.p60, bp.p85))
        speed <= bp.p95 -> blendArgb(palette.aboveAverage, palette.fast, fractionBetween(speed, bp.p85, bp.p95))
        else -> blendArgb(palette.fast, palette.fastest, fractionBetween(speed, bp.p95, bp.max))
    }

    private fun fractionBetween(value: Double, from: Double, to: Double): Float {
        if (to <= from) return 1f
        return ((value - from) / (to - from)).toFloat().coerceIn(0f, 1f)
    }

    private fun blendArgb(from: Int, to: Int, fraction: Float): Int = ColorUtils.blendARGB(from, to, fraction)

    private data class GradientStop(val progress: Double, val speedMetersPerSecond: Double)

    /**
     * One (progress-along-line, local speed) pair per accepted point, thinned to at most
     * [MAX_GRADIENT_STOPS] evenly-spaced points so the expression stays a reasonable size on a
     * long ride (12,000+ points) - progress-thin resolution is indistinguishable from full
     * resolution for something meant to be an "indicative" reference, not an instrument reading.
     * Stops must be strictly increasing, so a run of stationary points (identical cumulative
     * distance) collapses to a single stop rather than being individually emitted.
     */
    private fun buildGradientStops(points: List<RidePoint>, segments: List<RouteSpeedSegment>): List<GradientStop> {
        if (points.size < 2 || segments.isEmpty()) return emptyList()

        val cumulativeMeters = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulativeMeters[i] = cumulativeMeters[i - 1] + segments[i - 1].distanceMeters
        }
        val totalMeters = cumulativeMeters.last()
        if (totalMeters <= 0.0) return emptyList()

        fun speedAt(index: Int): Double = when (index) {
            0 -> segments.first().speedMetersPerSecond
            points.lastIndex -> segments.last().speedMetersPerSecond
            else -> (segments[index - 1].speedMetersPerSecond + segments[index].speedMetersPerSecond) / 2.0
        }

        val stride = ((points.size - 1).toDouble() / (MAX_GRADIENT_STOPS - 1)).coerceAtLeast(1.0)
        val thinned = mutableListOf<GradientStop>()
        var cursor = 0.0
        while (cursor <= points.lastIndex.toDouble()) {
            val index = cursor.roundToInt().coerceIn(0, points.lastIndex)
            val progress = cumulativeMeters[index] / totalMeters
            if (thinned.isEmpty() || progress > thinned.last().progress) {
                thinned.add(GradientStop(progress, speedAt(index)))
            }
            cursor += stride
        }
        val lastProgress = 1.0
        if (thinned.last().progress < lastProgress) {
            thinned.add(GradientStop(lastProgress, speedAt(points.lastIndex)))
        }
        return thinned
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
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, ROUTE_CAMERA_PADDING_PX))
    }

    private fun bindStats(root: View, stats: RouteStatistics) {
        bindStat(root, R.id.stat_distance, R.string.ride_stat_distance, formatKm(stats.distanceMeters))
        bindStat(root, R.id.stat_duration, R.string.ride_stat_duration, formatDuration(stats.durationMs))
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
        val totalMinutes = durationMs / 60_000
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
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroyView() {
        mapView.onDestroy()
        super.onDestroyView()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
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
        private const val ROUTE_CAMERA_PADDING_PX = 96
        private const val SINGLE_POINT_ZOOM = 15.0
    }
}
