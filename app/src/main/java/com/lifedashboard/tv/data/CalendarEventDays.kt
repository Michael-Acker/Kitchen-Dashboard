package com.lifedashboard.tv.data

import com.lifedashboard.tv.model.CalendarEvent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure date-mapping for calendar events (2026-10-02).
 *
 * No Android dependencies, so the multi-day expansion rules are pinned by
 * JVM unit tests (test/unit/CalendarEventDaysTest.kt). The 8-day strip and
 * the today agenda both delegate here so they can never disagree about
 * which events occupy a given day.
 */
object CalendarEventDays {

    /**
     * Events occurring on [date], sorted by start.
     *
     * Multi-day events are expanded to every day they span and treated as
     * all-day (no time shown) — a "Beach week" trip shows as an all-day row
     * on each day it covers, including today. An event ending exactly at
     * midnight does not occupy its end date.
     *
     * Event times are normalized to [zone] (default: system) before the
     * day math: a 7-9pm EDT event arriving as 23:00Z-01:00Z must not leak
     * onto the next day just because UTC rolled over (2026-10-02).
     */
    fun eventsOnDate(
        date: LocalDate,
        events: List<CalendarEvent>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<CalendarEvent> {
        return events.mapNotNull { e ->
            val startDate = e.start.withZoneSameInstant(zone).toLocalDate()
            var endDate = e.end.withZoneSameInstant(zone).toLocalDate()
            if (e.end.withZoneSameInstant(zone).toLocalTime() == LocalTime.MIDNIGHT) {
                endDate = endDate.minusDays(1)
            }
            if (date < startDate || date > endDate) return@mapNotNull null
            val multiDay = startDate != endDate
            if (multiDay && !e.allDay) e.copy(allDay = true) else e
        }.sortedBy { it.start }
    }
}
