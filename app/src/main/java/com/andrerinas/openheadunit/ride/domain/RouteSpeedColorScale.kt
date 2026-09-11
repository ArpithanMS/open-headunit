package com.andrerinas.openheadunit.ride.domain

import kotlin.math.roundToInt

/** ARGB int colors for a six-stop, percentile-ranked route speed scale. Plain ints, not Android
 *  resources - keeps this file free of any Android/View dependency so it's unit-testable on a
 *  plain JVM (androidx.core.graphics.ColorUtils and android.graphics.Color are NOT usable from a
 *  plain-JVM test without Robolectric, which this project doesn't have configured). */
data class RouteSpeedPalette(
    val slowest: Int,
    val belowAverage: Int,
    val average: Int,
    val aboveAverage: Int,
    val fast: Int,
    val fastest: Int,
)

/** Nearest-rank percentiles of a ride's own leg speeds - see [RouteSpeedColorScale]. */
data class RouteSpeedBreakpoints(
    val p15: Double,
    val p40: Double,
    val p60: Double,
    val p85: Double,
    val p95: Double,
    val max: Double,
) {
    /** False when every leg was at essentially the same speed - percentile banding would
     *  otherwise paint the whole ride as "slowest" (every value sits at/below p15), which is
     *  actively misleading for a ride with no real speed variation. */
    val hasMeaningfulSpread: Boolean get() = (max - p15) > MIN_SPREAD_METERS_PER_SECOND

    private companion object {
        const val MIN_SPREAD_METERS_PER_SECOND = 0.3 // ~1 km/h
    }
}

/**
 * Colors a route by each leg's speed, ranked against THIS ride's own speed distribution
 * (percentiles) rather than a ratio to the mean - a stop-and-go city ride and a highway run both
 * use the full color range this way, instead of the city ride sitting mostly in one color because
 * traffic drags its mean down. Slowest ~15% / below-average / around-average / above-average /
 * fastest ~15% (cyan 85th-95th, purple 95th-100th) - an F1-telemetry-style scale.
 *
 * Pure - no MapLibre/Android dependency - so RideDetailFragment only has to turn its output into
 * map-drawing calls, and the percentile/color math itself is unit-testable.
 */
object RouteSpeedColorScale {

    fun breakpoints(segments: List<RouteSpeedSegment>): RouteSpeedBreakpoints? {
        if (segments.isEmpty()) return null
        val sorted = segments.map { it.speedMetersPerSecond }.sorted()
        fun percentile(p: Double): Double {
            val index = (p / 100.0 * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }
        return RouteSpeedBreakpoints(
            p15 = percentile(15.0),
            p40 = percentile(40.0),
            p60 = percentile(60.0),
            p85 = percentile(85.0),
            p95 = percentile(95.0),
            max = sorted.last(),
        )
    }

    fun colorForSpeed(speed: Double, breakpoints: RouteSpeedBreakpoints, palette: RouteSpeedPalette): Int = when {
        speed <= breakpoints.p15 -> palette.slowest
        speed <= breakpoints.p40 ->
            blendArgb(palette.slowest, palette.belowAverage, fractionBetween(speed, breakpoints.p15, breakpoints.p40))
        speed <= breakpoints.p60 ->
            blendArgb(palette.belowAverage, palette.average, fractionBetween(speed, breakpoints.p40, breakpoints.p60))
        speed <= breakpoints.p85 ->
            blendArgb(palette.average, palette.aboveAverage, fractionBetween(speed, breakpoints.p60, breakpoints.p85))
        speed <= breakpoints.p95 ->
            blendArgb(palette.aboveAverage, palette.fast, fractionBetween(speed, breakpoints.p85, breakpoints.p95))
        else ->
            blendArgb(palette.fast, palette.fastest, fractionBetween(speed, breakpoints.p95, breakpoints.max))
    }

    private fun fractionBetween(value: Double, from: Double, to: Double): Float {
        if (to <= from) return 1f
        return ((value - from) / (to - from)).toFloat().coerceIn(0f, 1f)
    }

    /** Plain-math ARGB channel blend, equivalent to androidx.core.graphics.ColorUtils.blendARGB
     *  but without the Android framework dependency (see this object's KDoc). */
    fun blendArgb(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * f).roundToInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

/** One (progress-along-route, local speed) sample used to build a MapLibre line-gradient stop -
 *  the actual Expression/MapLibre conversion happens in RideDetailFragment. */
data class RouteSpeedGradientStop(val progress: Double, val speedMetersPerSecond: Double)

object RouteSpeedGradient {

    /**
     * Thins a ride's accepted points down to at most [maxStops] (progress, local speed) pairs,
     * evenly spaced by index, always keeping the first and last. Progress-thin resolution is
     * indistinguishable from full resolution for something meant to be an "indicative" color
     * reference, not an instrument reading - this is what keeps a 12,000+ point ride's gradient
     * expression a reasonable size. Stops must be strictly increasing, so a run of stationary
     * points (identical cumulative distance) collapses to a single stop rather than being
     * individually emitted.
     */
    fun buildStops(points: List<RidePoint>, segments: List<RouteSpeedSegment>, maxStops: Int): List<RouteSpeedGradientStop> {
        if (points.size < 2 || segments.isEmpty() || maxStops < 2) return emptyList()

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

        val stride = ((points.size - 1).toDouble() / (maxStops - 1)).coerceAtLeast(1.0)
        val thinned = mutableListOf<RouteSpeedGradientStop>()
        var cursor = 0.0
        while (cursor <= points.lastIndex.toDouble()) {
            val index = cursor.roundToInt().coerceIn(0, points.lastIndex)
            val progress = cumulativeMeters[index] / totalMeters
            if (thinned.isEmpty() || progress > thinned.last().progress) {
                thinned.add(RouteSpeedGradientStop(progress, speedAt(index)))
            }
            cursor += stride
        }
        if (thinned.last().progress < 1.0) {
            thinned.add(RouteSpeedGradientStop(1.0, speedAt(points.lastIndex)))
        }
        return thinned
    }
}
