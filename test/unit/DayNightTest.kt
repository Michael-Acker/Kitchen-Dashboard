package com.lifedashboard.tv.test

import com.lifedashboard.tv.ui.theme.isNightMinutes

/**
 * Regression tests for the day/night decision behind ThemeManager.
 *
 * Context: on 2026-09-28 the weather widget's "Today" highlight was stuck
 * dark (night colors) while the rest of the dashboard was in day mode —
 * the night→day transition had failed. The transition itself is now
 * decoupled from refresh passes (MainActivity.maybeApplyTheme); these
 * tests pin the underlying day/night boundary logic.
 *
 * Run: test/unit/run_tests.sh (plain kotlinc + JVM, no Android needed).
 */
private var failures = 0

private fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name")
    else {
        println("FAIL: $name")
        failures++
    }
}

fun main() {
    // Fallback 7:00 AM / 7:00 PM when the weather widget has no sun data.
    check("midnight is night", isNightMinutes(0, null, null))
    check("6:59 AM is night", isNightMinutes(6 * 60 + 59, null, null))
    check("exact sunrise 7:00 AM is day", !isNightMinutes(7 * 60, null, null))
    check("noon is day", !isNightMinutes(12 * 60, null, null))
    check("6:59 PM is day", !isNightMinutes(18 * 60 + 59, null, null))
    check("exact sunset 7:00 PM is day", !isNightMinutes(19 * 60, null, null))
    check("7:01 PM is night", isNightMinutes(19 * 60 + 1, null, null))
    check("11:59 PM is night", isNightMinutes(23 * 60 + 59, null, null))

    // Real sun times from the 2026-09-28 report (sunrise 7:01 AM, sunset 6:55 PM):
    // the user saw the stuck highlight at 4:55 PM, which must be day.
    val sr = 7 * 60 + 1
    val ss = 18 * 60 + 55
    check("4:55 PM with real sun times is day", !isNightMinutes(16 * 60 + 55, sr, ss))
    check("6:00 AM with real sun times is night", isNightMinutes(6 * 60, sr, ss))
    check("8:00 PM with real sun times is night", isNightMinutes(20 * 60, sr, ss))
    check("exact real sunrise is day", !isNightMinutes(sr, sr, ss))
    check("exact real sunset is day", !isNightMinutes(ss, sr, ss))

    if (failures > 0) {
        println("$failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("All DayNight tests passed.")
}
