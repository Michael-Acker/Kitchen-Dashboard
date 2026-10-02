package com.lifedashboard.tv.test

import com.lifedashboard.tv.ui.isRefreshDue

/**
 * Tests for the refresh-cadence check behind MainActivity's loop.
 *
 * Context: on 2026-09-28 a widget-visibility toggle stranded the calendar
 * in "Loading…" because the forced refresh was skipped by the old global
 * single-flight guard. The loop now uses per-widget guards and a pure
 * cadence check; these tests pin the cadence logic (a cleared timestamp —
 * the force-refresh path — must always count as due).
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
    val min = 60 * 1000L
    val now = 1_000_000_000L

    check("never refreshed (0) is due", isRefreshDue(0L, now, 10 * min))
    check("just refreshed is not due", !isRefreshDue(now, now, 10 * min))
    check("half interval elapsed is not due", !isRefreshDue(now - 5 * min, now, 10 * min))
    check("exactly one interval elapsed is due", isRefreshDue(now - 10 * min, now, 10 * min))
    check("over one interval elapsed is due", isRefreshDue(now - 11 * min, now, 10 * min))
    check("calendar 1-min cadence: 61s elapsed is due", isRefreshDue(now - 61_000L, now, min))
    check("calendar 1-min cadence: 59s elapsed is not due", !isRefreshDue(now - 59_000L, now, min))
    // Force-refresh clears the timestamp -> must be due immediately.
    check("cleared timestamp (force refresh) is due", isRefreshDue(0L, now, 10 * min))

    if (failures > 0) {
        println("$failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("All RefreshLogic tests passed.")
}
