package com.lifedashboard.tv.test

import com.lifedashboard.tv.data.CalendarWindow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pins the calendar week window (2026-09-30).
 *
 * Context: the calendar fetched timeMin=now, so the strip de-populated
 * events after they passed. The fix anchors both the fetch and the strip
 * to a fixed 8-day window (previous Sunday .. next Sunday). This test
 * pins the window definition: weekStart/weekDates/fetchStart/fetchEnd for
 * every day of the week, the exact 8-day span, and that the widget's old
 * inline formula (now delegated to CalendarWindow.weekStart) agrees.
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

// Known dates: 2026-09-27 is a Sunday, 2026-09-28 Monday, ... 2026-10-03 Saturday.
private fun dateFor(day: DayOfWeek): LocalDate =
    LocalDate.of(2026, 9, 27).plusDays((day.value % 7).toLong())

/** The widget's old inline formula, kept here so delegation can't drift. */
private fun legacyWeekStart(today: LocalDate): LocalDate =
    today.minusDays((today.dayOfWeek.value % 7).toLong())

fun main() {
    val zone = ZoneId.of("America/New_York")

    // weekStart: every day of the week maps to the same preceding Sunday.
    val sunday = LocalDate.of(2026, 9, 27)
    for (day in DayOfWeek.values()) {
        val today = dateFor(day)
        check(
            "weekStart(${today.dayOfWeek}) == $sunday",
            CalendarWindow.weekStart(today) == sunday
        )
        check(
            "weekStart(${today.dayOfWeek}) matches legacy widget formula",
            CalendarWindow.weekStart(today) == legacyWeekStart(today)
        )
    }
    // Sunday maps to itself (no negative shift).
    check("weekStart(Sunday) is Sunday itself", CalendarWindow.weekStart(sunday) == sunday)

    // weekDates: 8 consecutive days, Sunday first, for a mid-week anchor.
    val wed = LocalDate.of(2026, 9, 30)
    val dates = CalendarWindow.weekDates(wed)
    check("weekDates has 8 days", dates.size == 8)
    check("weekDates starts on Sunday", dates.first() == sunday)
    check("weekDates ends on next Sunday", dates.last() == sunday.plusDays(7))
    check(
        "weekDates are consecutive",
        dates.zipWithNext().all { (a, b) -> b == a.plusDays(1) }
    )
    check("weekDates contains the anchor day", wed in dates)

    // Fetch bounds: midnight Sunday .. midnight Monday-after, exactly 8 days.
    val start = CalendarWindow.fetchStart(wed, zone)
    val end = CalendarWindow.fetchEnd(wed, zone)
    check(
        "fetchStart is Sunday midnight in zone",
        start.toLocalDate() == sunday &&
            start.toLocalTime().toSecondOfDay() == 0 &&
            start.zone == zone
    )
    check(
        "fetchEnd is the Monday after the strip's last Sunday",
        end.toLocalDate() == sunday.plusDays(8) &&
            end.toLocalTime().toSecondOfDay() == 0 &&
            end.zone == zone
    )
    check(
        "fetch window spans exactly 8 days",
        ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) == 8L
    )

    // The window always contains today, even late in the day (the point of
    // the fix: past events in the displayed week must be fetchable).
    for (day in DayOfWeek.values()) {
        val today = dateFor(day)
        val window = CalendarWindow.weekDates(today)
        check("displayed week contains $today", today in window)
    }

    // A Sunday anchor keeps its own week, not the previous one.
    check(
        "Sunday anchor: weekDates starts on that Sunday",
        CalendarWindow.weekDates(sunday).first() == sunday
    )

    if (failures > 0) {
        println("$failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("All CalendarWindow tests passed.")
}
