package com.lifedashboard.tv.ui.theme

/**
 * Pure day/night decision used by ThemeManager.paletteFor.
 *
 * Kept free of Android dependencies so it can be covered by a plain JVM
 * unit test (test/unit/DayNightTest.kt). The night→day transition is what
 * repaints the weather widget's "Today" highlight; the 2026-09-28 bug
 * (highlight stuck dark while the rest of the dashboard was in day mode)
 * came from the theme transition being coupled to refresh passes, so this
 * decision logic gets a regression test.
 *
 * All values are minutes-since-midnight in the device's local timezone.
 * Sunrise/sunset fall back to 7:00 AM / 7:00 PM when the weather widget
 * has no data yet to provide real sun times.
 */
fun isNightMinutes(nowMinutes: Int, sunriseMinutes: Int?, sunsetMinutes: Int?): Boolean {
    val sunrise = sunriseMinutes ?: 7 * 60
    val sunset = sunsetMinutes ?: 19 * 60
    return nowMinutes < sunrise || nowMinutes > sunset
}
