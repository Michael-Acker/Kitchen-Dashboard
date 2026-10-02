package com.lifedashboard.tv.test

import com.lifedashboard.tv.data.CalendarListEntry
import com.lifedashboard.tv.data.CalendarSelection

/**
 * Pins the multi-calendar selection rules (2026-10-02).
 *
 * Context: the dashboard used to fetch only each account's "primary"
 * calendar. Settings → Google Calendar → Calendars now lets the user pick
 * any subset; this pins which ids get fetched (saved selection wins,
 * otherwise primary) and the per-calendar events URL (ids like
 * "en.usa#holiday@group.v.calendar.google.com" must be encoded).
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

private fun entry(id: String, primary: Boolean = false) =
    CalendarListEntry(
        id = id,
        summary = "Cal $id",
        backgroundColor = "#9fc6e7",
        primary = primary
    )

fun main() {
    val primary = entry("user@gmail.com", primary = true)
    val family = entry("family123")
    val holiday = entry("en.usa#holiday@group.v.calendar.google.com")
    val list = listOf(primary, family, holiday)

    // --- resolveIdsToFetch: no saved selection → primary ---
    check(
        "null saved selection defaults to the primary alias",
        CalendarSelection.resolveIdsToFetch(null) == listOf("primary")
    )
    check(
        "empty saved selection defaults to the primary alias",
        CalendarSelection.resolveIdsToFetch(emptySet()) == listOf("primary")
    )
    check(
        "null saved + known list defaults to the primary's real id",
        CalendarSelection.resolveIdsToFetch(null, list) == listOf("user@gmail.com")
    )
    check(
        "no primary in list falls back to the first entry",
        CalendarSelection.resolveIdsToFetch(null, listOf(family, holiday)) == listOf("family123")
    )

    // --- resolveIdsToFetch: saved selection wins verbatim ---
    check(
        "saved ids win over the default",
        CalendarSelection.resolveIdsToFetch(setOf("family123"), list) == listOf("family123")
    )
    check(
        "multiple saved ids all fetched, in the saved order",
        CalendarSelection.resolveIdsToFetch(
            linkedSetOf("en.usa#holiday@group.v.calendar.google.com", "family123"),
            list
        ) == listOf("en.usa#holiday@group.v.calendar.google.com", "family123")
    )
    check(
        "blank saved ids are dropped",
        CalendarSelection.resolveIdsToFetch(setOf("  ", "family123"), list) == listOf("family123")
    )
    check(
        "all-blank saved ids fall back to the primary's real id when the list is known",
        CalendarSelection.resolveIdsToFetch(setOf("  "), list) == listOf("user@gmail.com")
    )
    check(
        "duplicate saved ids are fetched once",
        CalendarSelection.resolveIdsToFetch(setOf("a", "a")) == listOf("a")
    )

    // --- eventsUrl ---
    val url = CalendarSelection.eventsUrl(
        "en.usa#holiday@group.v.calendar.google.com",
        "2026-10-04T04%3A00%3A00-04%3A00",
        "2026-10-12T04%3A00%3A00-04%3A00"
    )
    check(
        "'#' and '@' in calendar ids are encoded",
        url.contains("/calendars/en.usa%23holiday%40group.v.calendar.google.com/events")
    )
    check("url keeps the encoded timeMin", url.contains("timeMin=2026-10-04T04%3A00%3A00-04%3A00"))
    check("url keeps the encoded timeMax", url.contains("timeMax=2026-10-12T04%3A00%3A00-04%3A00"))
    check("url expands recurring events", url.contains("singleEvents=true"))
    check("url orders by start time", url.contains("orderBy=startTime"))
    check("url caps page size", url.contains("maxResults=100"))

    val primaryUrl = CalendarSelection.eventsUrl("primary", "MIN", "MAX")
    check(
        "the primary alias passes through unencoded",
        primaryUrl.contains("/calendars/primary/events")
    )

    if (failures > 0) {
        println("$failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("CalendarSelectionTest: all checks passed")
}
