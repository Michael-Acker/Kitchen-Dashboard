package com.lifedashboard.tv.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The calendar widget always displays a fixed 8-day window: the previous
 * Sunday through the next Sunday. Fetching is anchored to that window —
 * not to "now" — so the whole displayed week stays populated all day,
 * including events that have already passed.
 *
 * Pure java.time logic with no Android dependencies, so it is covered by
 * the JVM unit tests (test/unit/CalendarWindowTest.kt). Both the fetch
 * (CalendarRepository) and the week strip (CalendarWidgetView) use this
 * single definition so they can never drift apart.
 */
object CalendarWindow {

    /** The Sunday that starts the 8-day strip containing [today]. */
    fun weekStart(today: LocalDate): LocalDate =
        today.minusDays((today.dayOfWeek.value % 7).toLong())

    /** The 8 day-dates shown in the strip, in order, starting on Sunday. */
    fun weekDates(today: LocalDate): List<LocalDate> {
        val start = weekStart(today)
        return (0..7).map { start.plusDays(it.toLong()) }
    }

    /**
     * Inclusive lower fetch bound: midnight at the start of the strip's
     * first Sunday.
     */
    fun fetchStart(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        weekStart(today).atStartOfDay(zone)

    /**
     * Exclusive upper fetch bound: midnight at the start of the Monday
     * after the strip's last Sunday (exactly 8 days after [fetchStart]).
     */
    fun fetchEnd(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        weekStart(today).plusDays(8).atStartOfDay(zone)
}
