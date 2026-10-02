package com.lifedashboard.tv.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.lifedashboard.tv.ui.theme.THEMES

/**
 * Fixed-viewport kiosk grid: greedily packs widget spanWeights into rows of
 * 12 (canonical order: clock, weather, calendar, packages, news). Each row
 * is a horizontal LinearLayout with layout_weight=1 inside the vertical
 * container; children are width=0 with weight=spanWeight. Nothing scrolls —
 * every visible widget fits on screen and space is filled.
 */
fun layoutWidgets(container: LinearLayout, widgets: List<DashboardWidget>) {
    val ctx = container.context
    container.removeAllViews()
    container.orientation = LinearLayout.VERTICAL
    container.setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))

    if (widgets.isEmpty()) {
        val empty = TextView(ctx).apply {
            text = "No widgets enabled — open Settings ⚙"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(THEMES.first().day.textSecondary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(empty)
        return
    }

    val canonical = listOf("clock", "weather", "calendar", "packages", "news")
    val sorted = widgets.sortedBy { canonical.indexOf(it.widgetId).takeIf { i -> i >= 0 } ?: 99 }

    val gap = ctx.dp(14)
    var row: LinearLayout? = null
    var used = 0

    fun newRow(): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply {
                // Vertical gap between rows (not after the last — handled below).
                bottomMargin = gap
            }
        }
        container.addView(r)
        return r
    }

    sorted.forEach { widget ->
        if (row == null || used + widget.spanWeight > 12) {
            row = newRow()
            used = 0
        }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, widget.spanWeight.toFloat())
        // Horizontal gap between widgets, unless this one exactly ends the row.
        if (used + widget.spanWeight < 12) lp.marginEnd = gap
        widget.layoutParams = lp
        (widget.parent as? android.view.ViewGroup)?.removeView(widget)
        row!!.addView(widget)
        used += widget.spanWeight
    }

    // Drop the bottom margin on the final row so the grid fills the viewport.
    (container.getChildAt(container.childCount - 1).layoutParams as? LinearLayout.LayoutParams)
        ?.let { it.bottomMargin = 0 }
}
