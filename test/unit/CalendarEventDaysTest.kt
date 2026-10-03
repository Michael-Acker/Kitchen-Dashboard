package com.lifedashboard.tv.test

import com.lifedashboard.tv.data.CalendarEventDays
import com.lifedashboard.tv.model.CalendarEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pins the multi-day expansion rules (2026-10-02).
 *
 * Context: the 8-day strip expanded multi-day events to every day they
 * span (as all-day), but the today agenda only listed events STARTING
 * today — a "Beach week" trip was invisible in the agenda on its middle
 * days. Both views now delegate to CalendarEventDays.eventsOnDate, so
 * they can never disagree about what occupies a day.
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

private val zone = ZoneId.of("America/New_York")
// 2026-10-02 is a Friday.
private val fri = LocalDate.of(2026, 10, 2)

private fun ev(
    title: String,
    start: ZonedDateTime,
    end: ZonedDateTime,
    allDay: Boolean = false
) = CalendarEvent(
    id = title,
    accountId = "1",
    accountName = "Account 1",
    title = title,
    start = start,
    end = end,
    allDay = allDay
)

private fun at(day: LocalDate, hour: Int, min: Int = 0): ZonedDateTime =
    day.atTime(hour, min).atZone(zone)

fun main() {
    val sat = fri.plusDays(1)
    val sun = fri.plusDays(2)
    val thu = fri.minusDays(1)

    val timedToday = ev("Standup", at(fri, 9), at(fri, 10))
    val timedOtherDay = ev("Dentist", at(sat, 10), at(sat, 11))
    val multiDay = ev("Beach week", at(thu, 9), at(sun, 17))
    val allDaySingle = ev("Holiday", at(fri, 0), at(sat, 0), allDay = true)
    val endsMidnight = ev("Late shift", at(fri, 22), at(sat, 0))
    val alreadyAllDayMulti = ev("Conference", at(thu, 0), at(sat, 0), allDay = true)
    val events = listOf(timedToday, timedOtherDay, multiDay, allDaySingle, endsMidnight, alreadyAllDayMulti)

    val fridays = CalendarEventDays.eventsOnDate(fri, events).map { it.title }.toSet()

    check("timed event today is included", "Standup" in fridays)
    check("timed event on another day is excluded", "Dentist" !in fridays)
    check("multi-day event spanning today is included", "Beach week" in fridays)
    check(
        "multi-day event is marked all-day",
        CalendarEventDays.eventsOnDate(fri, events)
            .first { it.title == "Beach week" }.allDay
    )
    check("single-day all-day event is included unchanged", "Holiday" in fridays)
    check(
        "event ending exactly at midnight does not occupy its end date",
        "Late shift" in fridays &&
            "Late shift" !in CalendarEventDays.eventsOnDate(sat, events).map { it.title }.toSet()
    )
    check(
        "already all-day multi-day event stays all-day",
        CalendarEventDays.eventsOnDate(fri, events)
            .first { it.title == "Conference" }.allDay
    )
    check(
        "multi-day event appears on every spanned day",
        "Beach week" in CalendarEventDays.eventsOnDate(thu, events).map { it.title } &&
            "Beach week" in CalendarEventDays.eventsOnDate(sat, events).map { it.title } &&
            "Beach week" in CalendarEventDays.eventsOnDate(sun, events).map { it.title }
    )
    check(
        "results are sorted by start time",
        CalendarEventDays.eventsOnDate(fri, events)
            .map { it.start } == CalendarEventDays.eventsOnDate(fri, events)
            .map { it.start }.sorted()
    )
    check(
        "a day with no events yields an empty list",
        CalendarEventDays.eventsOnDate(fri.plusDays(9), events).isEmpty()
    )

    // Regression (2026-10-02): a Sat 7-9pm EDT event arriving from Google
    // as 23:00Z-01:00Z was treated as spanning two UTC dates and shown on
    // Sunday too. Day math must use the display zone, not the event's zone.
    val ny = ZoneId.of("America/New_York")
    val dukeUtc = ev(
        "Duke University M",
        ZonedDateTime.parse("2026-10-03T23:00:00Z"),
        ZonedDateTime.parse("2026-10-04T01:00:00Z")
    )
    check(
        "UTC evening event appears only on its local day",
        "Duke University M" in CalendarEventDays.eventsOnDate(fri.plusDays(1), listOf(dukeUtc), ny)
            .map { it.title } &&
            "Duke University M" !in CalendarEventDays.eventsOnDate(fri.plusDays(2), listOf(dukeUtc), ny)
            .map { it.title }
    )
    check(
        "UTC evening event is not marked all-day",
        !CalendarEventDays.eventsOnDate(fri.plusDays(1), listOf(dukeUtc), ny)
            .first { it.title == "Duke University M" }.allDay
    )

    if (failures > 0) {
        println("$failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("CalendarEventDaysTest: all checks passed")
}
