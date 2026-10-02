package com.lifedashboard.tv.testharness

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.lifedashboard.tv.data.CalendarListEntry
import com.lifedashboard.tv.data.CalendarRepo
import com.lifedashboard.tv.model.CalendarEvent
import com.lifedashboard.tv.ui.dp
import com.lifedashboard.tv.ui.theme.THEMES
import com.lifedashboard.tv.ui.widgets.CalendarWidgetView
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Emulator-only test harness (never shipped): renders CalendarWidgetView with
 * canned events inside the exact grid geometry MainActivity uses (24dp
 * padding, 3 equal-weight rows, calendar at spanWeight 8 of row 2), so we can
 * screenshot-verify that the 7-day strip fits without clipping.
 */
class FakeCalendarRepo : CalendarRepo {
    override fun getClientId(): String = "fake"
    override fun saveClientId(clientId: String) {}
    override fun getClientSecret(): String = "fake"
    override fun saveClientSecret(clientSecret: String) {}
    override fun isLinked(slot: Int): Boolean = true
    override fun accountName(slot: Int): String? =
        if (slot == 1) "michaelacker11@gmail.com" else "Account 2"
    override suspend fun beginDeviceFlow(slot: Int): CalendarRepo.DeviceFlowSession =
        throw UnsupportedOperationException()
    override suspend fun pollForToken(slot: Int, deviceCode: String, intervalSec: Long): Boolean = false

    override suspend fun getCalendarList(slot: Int): List<CalendarListEntry> =
        emptyList()

    override fun getSelectedCalendarIds(slot: Int): Set<String>? = null

    override fun saveSelectedCalendarIds(slot: Int, ids: Set<String>) {}

    override suspend fun getUpcomingEvents(): List<CalendarEvent> {
        val today = LocalDate.now()
        val zone = ZonedDateTime.now().zone
        fun ev(dayOffset: Long, hour: Int, min: Int, title: String, slot: Int = 1): CalendarEvent {
            val start = today.plusDays(dayOffset).atTime(hour, min).atZone(zone)
            return CalendarEvent(
                id = "id-$dayOffset-$hour$min",
                accountId = slot.toString(),
                accountName = accountName(slot)!!,
                title = title,
                start = start,
                end = start.plusHours(2),
                allDay = false
            )
        }
        return listOf(
            ev(0, 9, 0, "Morning standup"),
            ev(0, 18, 30, "Dinner with Sam"),
            ev(1, 17, 30, "Reservation at Omakase @ Barracks Row"),
            ev(1, 20, 15, "Endgame"),
            ev(2, 10, 0, "Dentist"),
            ev(3, 12, 0, "Lunch with Priya", 2),
            ev(3, 13, 0, "Dentist follow-up", 2),
            ev(3, 14, 0, "Gym", 2),
            ev(3, 15, 0, "Call mom", 2),
            ev(5, 8, 0, "Flight out", 2)
        )
    }

    override fun unlink(slot: Int) {}
}

class CalStripTestActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        setContentView(
            container,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Row 1 spacer (clock + weather live here in the real grid).
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { bottomMargin = dp(14) }
        })

        // Row 2: calendar widget at spanWeight 8 of 12, packages placeholder 4.
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { bottomMargin = dp(14) }
        val cal = CalendarWidgetView(this, FakeCalendarRepo()) {}
        cal.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 8f
        ).apply { marginEnd = dp(14) }
        row2.addView(cal)
        row2.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 4f
            )
        })
        container.addView(row2)

        // Row 3 spacer (news lives here in the real grid).
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })

        cal.applyTheme(THEMES.first().night)
        scope.launch(Dispatchers.IO) {
            runCatching { cal.refresh() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
