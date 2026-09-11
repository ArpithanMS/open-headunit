package com.andrerinas.openheadunit.utils

import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.google.android.material.button.MaterialButton

/**
 * Ride Tracker's "Dark Precision Instrumentation" look - scoped to Ride Tracker/History/Detail
 * only (see the implementation brief), always applied there rather than a selectable app theme.
 * Bespoke screens call [style] once, typically from onViewCreated/onCreateView, passing whichever
 * of their own views apply.
 *
 * Text defaults to the system sans-serif (monospace is reserved for technical readouts - GPS
 * accuracy, timestamps - not general UI text), and the recording-lime accent is reserved for
 * active/recording/primary-action state, not general text color. [primaryButtons] means the
 * screen's one dominant action (e.g. Start Ride) - the one control allowed a standing accent
 * border; anything else gets the routine (structural-divider-bordered) panel treatment.
 *
 * Also bakes in a fix found the hard way on real hardware (see RIDE_ENGINE_PROGRESS.md): a
 * MaterialButton's own app:backgroundTint SRC_IN-recolors any background drawable you set unless
 * cleared first.
 */
object RideInstrumentStyler {

    fun style(
        root: View,
        primaryTexts: List<TextView> = emptyList(),
        secondaryTexts: List<TextView> = emptyList(),
        primaryButtons: List<MaterialButton> = emptyList(),
        routinePanelButtons: List<MaterialButton> = emptyList(),
        // Other opaque views (e.g. an AppBarLayout with its own ?attr/colorSurface background)
        // that sit over root and would otherwise show a grey seam instead of the ride canvas.
        extraSurfaces: List<View> = emptyList()
    ) {
        val ctx = root.context
        val canvas = ContextCompat.getColor(ctx, R.color.ride_canvas)
        root.setBackgroundColor(canvas)
        extraSurfaces.forEach { it.setBackgroundColor(canvas) }
        applyTextOnly(primary = primaryTexts, secondary = secondaryTexts)

        val accent = ContextCompat.getColor(ctx, R.color.ride_accent_recording)
        primaryButtons.forEach { button ->
            button.backgroundTintList = null
            button.background = ContextCompat.getDrawable(ctx, R.drawable.bg_ride_panel_primary_selector)
            button.setTextColor(accent)
            button.iconTint = ColorStateList.valueOf(accent)
        }
        val primaryText = ContextCompat.getColor(ctx, R.color.ride_text_primary)
        routinePanelButtons.forEach { button ->
            button.backgroundTintList = null
            button.background = ContextCompat.getDrawable(ctx, R.drawable.bg_ride_panel_selector)
            button.setTextColor(primaryText)
        }
    }

    /**
     * Text-only restyle, no root background - for views that float transparently over another
     * view's own background (e.g. Ride Detail's stat rows, floating over RouteGeometryView)
     * where painting a root background here would wrongly cover what's behind them.
     */
    fun applyTextOnly(primary: TextView, secondary: TextView) =
        applyTextOnly(listOf(primary), listOf(secondary))

    fun applyTextOnly(primary: List<TextView>, secondary: List<TextView>) {
        if (primary.isEmpty() && secondary.isEmpty()) return
        val ctx = (primary.firstOrNull() ?: secondary.first()).context
        val primaryColor = ContextCompat.getColor(ctx, R.color.ride_text_primary)
        val secondaryColor = ContextCompat.getColor(ctx, R.color.ride_text_secondary)
        primary.forEach { it.setTextColor(primaryColor) }
        secondary.forEach { it.setTextColor(secondaryColor) }
    }
}
