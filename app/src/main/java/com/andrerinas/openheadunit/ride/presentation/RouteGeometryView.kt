package com.andrerinas.openheadunit.ride.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ride.domain.RidePoint
import kotlin.math.cos
import kotlin.math.max

/**
 * Draws a ride's accepted route as a plain line, with a start and end marker - no base map, no
 * tiles, no network dependency. This is Ride Detail's guaranteed-visible route rendering: the
 * MapLibre basemap (see RideDetailFragment) is an enhancement layered on top when the device
 * supports it (API 21+) and a style actually loads over the network - this view is what a
 * pre-Lollipop device or an offline viewer sees instead, not a degraded/broken state.
 *
 * The projection is a simple local equirectangular approximation (longitude scaled by cos of the
 * route's mean latitude, so the aspect ratio looks correct) - adequate for a single ride's small
 * geographic extent, not a claim of cartographic accuracy over long distances.
 */
class RouteGeometryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var points: List<RidePoint> = emptyList()

    // Route = the GPS/route accent (design spec color table); lime is reserved for active
    // recording and Start Ride, not a historical route's markers, so start/end are neutral -
    // "a restrained start marker; a neutral end marker" - not semantic-accent colored.
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.ride_accent_gps)
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ride_text_primary)
    }
    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ride_text_tertiary)
    }
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ContextCompat.getColor(context, R.color.ride_canvas)
    }

    private val padding = dp(24f)
    private val markerRadius = dp(7f)

    /** Route points in recording order - the accepted subset of a ride's samples. */
    fun setRoute(newPoints: List<RidePoint>) {
        points = newPoints
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        if (points.size == 1) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, markerRadius, startPaint)
            canvas.drawCircle(cx, cy, markerRadius, markerStrokePaint)
            return
        }

        // Longitude degrees are shorter than latitude degrees away from the equator; scaling by
        // cos(latitude) keeps the drawn route's proportions roughly correct instead of stretched.
        val meanLatRad = Math.toRadians(points.sumOf { it.latitude } / points.size)
        val lonScale = cos(meanLatRad)

        val projectedXs = points.map { it.longitude * lonScale }
        val projectedYs = points.map { it.latitude }
        val minX = projectedXs.min()
        val maxX = projectedXs.max()
        val minY = projectedYs.min()
        val maxY = projectedYs.max()
        // A ride that never moved (or a straight north-south/east-west line) has zero span on one
        // axis; a tiny floor avoids a divide-by-zero collapsing the whole route to one point.
        val spanX = max(maxX - minX, MIN_SPAN_DEGREES)
        val spanY = max(maxY - minY, MIN_SPAN_DEGREES)

        val availableWidth = width - 2 * padding
        val availableHeight = height - 2 * padding
        if (availableWidth <= 0 || availableHeight <= 0) return
        val scale = minOf(availableWidth / spanX, availableHeight / spanY).toFloat()

        val drawnWidth = (spanX * scale).toFloat()
        val drawnHeight = (spanY * scale).toFloat()
        val offsetX = padding + (availableWidth - drawnWidth) / 2f
        // Screen Y grows downward but latitude grows north (up), so this both flips and offsets.
        val offsetY = padding + (availableHeight - drawnHeight) / 2f

        fun screenX(longitude: Double): Float =
            offsetX + ((longitude * lonScale - minX) * scale).toFloat()

        fun screenY(latitude: Double): Float =
            offsetY + ((maxY - latitude) * scale).toFloat()

        val path = Path()
        points.forEachIndexed { index, point ->
            val x = screenX(point.longitude)
            val y = screenY(point.latitude)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, routePaint)

        val start = points.first()
        val end = points.last()
        canvas.drawCircle(screenX(start.longitude), screenY(start.latitude), markerRadius, startPaint)
        canvas.drawCircle(screenX(start.longitude), screenY(start.latitude), markerRadius, markerStrokePaint)
        canvas.drawCircle(screenX(end.longitude), screenY(end.latitude), markerRadius, endPaint)
        canvas.drawCircle(screenX(end.longitude), screenY(end.latitude), markerRadius, markerStrokePaint)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        const val MIN_SPAN_DEGREES = 0.0001
    }
}
