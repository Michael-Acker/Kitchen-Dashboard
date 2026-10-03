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

    // --- serializeIds / parseIds: comma-joined string storage ---
    // (2026-10-02: putStringSet/getStringSet on EncryptedSharedPreferences
    // silently dropped selections on-device, so the selection persists as
    // a plain string — the same mechanism the working token storage uses.)
    check(
        "ids round-trip through the string form",
        CalendarSelection.parseIds(
            CalendarSelection.serializeIds(setOf("a@gmail.com", "family123"))
        ) == setOf("a@gmail.com", "family123")
    )
    check(
        "empty set serializes to empty string",
        CalendarSelection.serializeIds(emptySet()) == ""
    )
    check(
        "null parses to null (never picked = primary only)",
        CalendarSelection.parseIds(null) == null
    )
    check(
        "empty string parses to empty set",
        CalendarSelection.parseIds("") == emptySet<String>()
    )
    check(
        "blank entries are dropped on both ends",
        CalendarSelection.parseIds("a,,b") == setOf("a", "b") &&
            CalendarSelection.serializeIds(setOf("a", "  ")) == "a"
    )
    check(
        "entries are trimmed",
        CalendarSelection.parseIds(" a , b ") == setOf("a", "b")
    )
    check(
        "a real holiday calendar id survives the round trip",
        CalendarSelection.parseIds(
            CalendarSelection.serializeIds(setOf("en.usa#holiday@group.v.calendar.google.com"))
        ) == setOf("en.usa#holiday@group.v.calendar.google.com")
    )

    // --- toggleChecked: tap toggles, never empty, primary fallback ---
    // (2026-10-02: the picker saves on every tap — the old Save button was
    // clipped off-screen on 1080p Fire TVs, so toggles could never persist.)
    check(
        "tapping an unchecked calendar checks it",
        CalendarSelection.toggleChecked(setOf("p"), "f", "p") == setOf("p", "f")
    )
    check(
        "tapping a checked calendar unchecks it",
        CalendarSelection.toggleChecked(setOf("p", "f"), "f", "p") == setOf("p")
    )
    check(
        "unchecking the last calendar falls back to primary",
        CalendarSelection.toggleChecked(setOf("f"), "f", "p") == setOf("p")
    )
    check(
        "unchecking primary when it is the only one keeps it checked",
        CalendarSelection.toggleChecked(setOf("p"), "p", "p") == setOf("p")
    )
    check(
        "no primary id and empty result stays empty (resolves downstream)",
        CalendarSelection.toggleChecked(setOf("f"), "f", null).isEmpty()
    )
    // Regression (2026-10-02): the tap handler cleared the checked set
    // BEFORE calling toggleChecked, so only one calendar could ever stay
    // selected. The pure function composes correctly across successive
    // toggles — pin that here.
    check(
        "successive toggles accumulate",
        run {
            var checked = setOf("p")
            checked = CalendarSelection.toggleChecked(checked, "f", "p")
            checked = CalendarSelection.toggleChecked(checked, "g", "p")
            checked == setOf("p", "f", "g")
        }
    )
    check(
        "toggling one off keeps the others",
        run {
            var checked = setOf("p", "f", "g")
            checked = CalendarSelection.toggleChecked(checked, "f", "p")
            checked == setOf("p", "g")
        }
    )

    if (failures > 0) {
        println("$failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("CalendarSelectionTest: all checks passed")
}
