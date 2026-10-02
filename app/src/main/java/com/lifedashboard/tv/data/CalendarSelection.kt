package com.lifedashboard.tv.data

import java.net.URLEncoder

/**
 * One selectable calendar from Google's calendarList endpoint
 * (GET /calendar/v3/users/me/calendarList).
 *
 * Pure data + pure rules — no Android dependencies, so the selection logic
 * is pinned by JVM unit tests (test/unit/CalendarSelectionTest.kt).
 */
data class CalendarListEntry(
    val id: String,
    /** Display name, e.g. "Events", "Family", "Birthdays". */
    val summary: String,
    /** Google's per-calendar color ("#9fc6e7"); null when Google sends none. */
    val backgroundColor: String?,
    /** True for the account's primary calendar. */
    val primary: Boolean
)

/**
 * Rules for which calendars feed the dashboard (2026-10-02).
 *
 * Context: the dashboard used to fetch only each account's "primary"
 * calendar. Users with several calendars (sports schedules, family,
 * birthdays…) can now pick any subset in Settings → Google Calendar →
 * Calendars; the pick is stored per account slot and every checked
 * calendar is fetched and merged on refresh.
 */
object CalendarSelection {

    /**
     * The events endpoint accepts the literal "primary" as the primary
     * calendar's id, so the default selection needs no calendarList fetch.
     */
    const val PRIMARY_ALIAS = "primary"

    /**
     * Which calendar ids to fetch events for.
     *
     * A saved non-empty selection wins verbatim. Otherwise the account's
     * primary calendar: its real id when the calendar list is at hand,
     * else the "primary" alias the API accepts. This keeps the behavior
     * identical for users who never open the picker.
     */
    fun resolveIdsToFetch(
        savedIds: Set<String>?,
        entries: List<CalendarListEntry> = emptyList()
    ): List<String> {
        val saved = savedIds?.filter { it.isNotBlank() }?.distinct().orEmpty()
        if (saved.isNotEmpty()) return saved
        val fallback = entries.firstOrNull { it.primary }?.id
            ?: entries.firstOrNull()?.id
            ?: PRIMARY_ALIAS
        return listOf(fallback)
    }

    /**
     * Per-calendar events URL for the displayed-week window.
     *
     * Calendar ids can contain '#' and '@' (e.g.
     * "en.usa#holiday@group.v.calendar.google.com"), so the id is encoded;
     * timeMin/timeMax arrive pre-encoded from the caller and are passed
     * through untouched.
     */
    fun eventsUrl(calendarId: String, encodedTimeMin: String, encodedTimeMax: String): String {
        val id = URLEncoder.encode(calendarId, Charsets.UTF_8.name())
        return "https://www.googleapis.com/calendar/v3/calendars/$id/events" +
            "?timeMin=$encodedTimeMin&timeMax=$encodedTimeMax" +
            "&singleEvents=true&orderBy=startTime&maxResults=100"
    }
}
