package com.lifedashboard.tv.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** One day of the 7-day forecast strip. */
data class DayForecast(
    val date: LocalDate,
    val highF: Int,
    val lowF: Int,
    val weatherCode: Int
)

data class WeatherData(
    val tempF: Int,
    val weatherCode: Int,
    val highF: Int,
    val lowF: Int,
    val rainChancePct: Int,
    val sunrise: LocalTime?,
    val sunset: LocalTime?,
    val daily: List<DayForecast> // 7 days, starting today
)

/**
 * A calendar event pooled from one of the two linked Google accounts.
 * accountId is "1" or "2" (the in-app account slot).
 */
data class CalendarEvent(
    val id: String,
    val accountId: String,
    val accountName: String,
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean
)

/** A parcel from the Parcel Pending parcel-history table. */
data class ParcelInfo(
    val packageCode: String,
    val statusCode: Int,
    val kioskName: String,
    /** Display string as shown on the site (property timezone), e.g. "09/24/2026 4:18 PM". */
    val deliveredAt: String,
    val recipient: String?,
    // --- from the row's details cell, when the site provides one ---
    /** "Package Code" from the details dump — the code punched into the kiosk to pick up. */
    val pickupCode: String = "",
    /** "Locker Box #", e.g. "54 (Medium)". */
    val lockerBox: String = "",
    /** "Courier", e.g. "Amazon". */
    val courier: String = "",
) {
    val isPending: Boolean get() = statusCode == 1001 || statusCode == 1003
    val statusLabel: String get() = when (statusCode) {
        1001 -> "Pending"
        1002 -> "Picked Up"
        1003 -> "Oversized – Pending"
        1004 -> "Oversized – Picked Up"
        else -> "Unknown"
    }
}

data class NewsHeadline(
    val title: String,
    val source: String,
    val link: String
)
