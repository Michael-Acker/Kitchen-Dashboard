package com.lifedashboard.tv.ui

import android.content.Context
import android.widget.FrameLayout
import com.lifedashboard.tv.ui.theme.Palette

/**
 * Contract for kiosk dashboard widgets. All views are built programmatically
 * (no XML layouts) so theming and the dynamic grid stay simple.
 *
 * Widget ids, default visibility and grid weights (12-column grid):
 * - clock    weight 4  default ON
 * - weather  weight 8  default ON
 * - calendar weight 8  default ON
 * - packages weight 4  default ON
 * - news     weight 12 default OFF
 * Canonical order: clock, weather, calendar, packages, news.
 */
abstract class DashboardWidget(context: Context) : FrameLayout(context) {

    abstract val widgetId: String
    abstract val spanWeight: Int

    /**
     * How often this widget's data should refresh (ms).
     * MainActivity's loop ticks every minute and refreshes only widgets
     * whose interval has elapsed. Default 10 minutes.
     */
    open val refreshIntervalMs: Long = 10 * 60 * 1000L

    /**
     * Load data and rebind views. Called from a lifecycle coroutine on
     * Dispatchers.IO — implementations may do network I/O directly.
     * Must never throw: show a placeholder state on failure instead.
     */
    abstract suspend fun refresh()

    /** Apply the active theme palette to all subviews. */
    abstract fun applyTheme(p: Palette)

    /** Cancel timers/jobs. */
    open fun onDestroy() {}
}
