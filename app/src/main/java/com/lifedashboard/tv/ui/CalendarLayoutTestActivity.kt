package com.lifedashboard.tv.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lifedashboard.tv.data.CalendarRepo
import com.lifedashboard.tv.data.CalendarWindow
import com.lifedashboard.tv.model.CalendarEvent
import com.lifedashboard.tv.ui.widgets.CalendarWidgetView
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Integration test harness for the calendar widget layout.
 *
 * Launch via adb:
 *   adb shell am start -n com.lifedashboard.tv/.ui.CalendarLayoutTestActivity \
 *     --es scenario sunday3
 *
 * Scenarios:
 *   sunday3   - Today (Sat) has 2 events; Sunday has 3 (the user's case).
 *   todayMany - Today has 10 events (daily must shrink + truncate).
 *   allMany   - Every day has 5 events (weekly must truncate with +n more).
 *   empty     - No events at all.
 *   pastWeek  - Past days of the strip have events + today has a past and
 *               a future event (verifies the whole displayed week stays
 *               populated after events pass). Launches the full dashboard
 *               with mocks like "main".
 *
 * The widget logs a CALTEST report to logcat with all measurements and
 * PASS/FAIL checks against the layout spec. The activity finishes after
 * the layout settles.
 *
 * This is test-only code; it is not referenced by the production UI.
 */
class CalendarLayoutTestActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario") ?: "sunday3"
        Log.d("CALTEST", "SCENARIO $scenario")

        CalendarWidgetView.mockEvents = buildMockEvents(scenario)

        // Fixed-size container simulating the dashboard widget card.
        // Values are in raw pixels (not dp) to match the dashboard layout.
        // 900x500 px ≈ a Fire TV widget card at 1080p.
        // The "compressed" scenario simulates the 4-widget dashboard where
        // the calendar is in the bottom-left: ~1080x440 px.
        val (cw, ch) = if (scenario == "compressed") 1080 to 440 else 900 to 500
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        // Wrap in a fullscreen root so the fixed-size container isn't
        // forced to MATCH_PARENT by the window.
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.DKGRAY)
            addView(container, FrameLayout.LayoutParams(cw, ch).apply {
                gravity = android.view.Gravity.CENTER
            })
        }
        setContentView(root)

        val dummyRepo = object : CalendarRepo {
            override fun getClientId(): String? = null
            override fun saveClientId(clientId: String) {}
            override fun getClientSecret(): String? = null
            override fun saveClientSecret(clientSecret: String) {}
            override fun isLinked(slot: Int): Boolean = false
            override fun accountName(slot: Int): String? = null
            override suspend fun beginDeviceFlow(slot: Int): CalendarRepo.DeviceFlowSession =
                throw UnsupportedOperationException()
            override suspend fun pollForToken(slot: Int, deviceCode: String, intervalSec: Long): Boolean =
                throw UnsupportedOperationException()
            override suspend fun getUpcomingEvents(): List<CalendarEvent> =
                throw UnsupportedOperationException()
            override fun unlink(slot: Int) {}
        }

        val widget = CalendarWidgetView(this, dummyRepo) {}
        container.addView(widget, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        scope.launch {
            widget.refresh()
            // Wait for the post-layout pass to run, then finish.
            // The "main"/"pastWeek" scenarios launch the full dashboard
            // with mocks instead: do not clear mocks or finish.
            // The "compressed" scenario stays open for screenshots.
            if (scenario == "main" || scenario == "pastWeek" || scenario == "compressed") {
                if (scenario == "main" || scenario == "pastWeek") {
                    container.post {
                        container.postDelayed({
                            Log.d("CALTEST", "LAUNCHING MainActivity with mocks")
                            val main = android.content.Intent(
                                this@CalendarLayoutTestActivity,
                                MainActivity::class.java
                            )
                            startActivity(main)
                        }, 800)
                    }
                } else {
                    Log.d("CALTEST", "COMPRESSED scenario staying open for screenshot")
                }
                return@launch
            }
            container.post {
                container.postDelayed({
                    Log.d("CALTEST", "DONE scenario=$scenario")
                    CalendarWidgetView.mockEvents = null
                    finish()
                }, 1500)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildMockEvents(scenario: String): List<CalendarEvent> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        fun evt(
            id: String,
            date: LocalDate,
            hour: Int,
            minute: Int,
            title: String,
            accountId: String = "1"
        ): CalendarEvent {
            val start = ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute), zone)
            return CalendarEvent(
                id = id,
                accountId = accountId,
                accountName = if (accountId == "1") "Personal" else "Work",
                title = title,
                start = start,
                end = start.plusHours(1),
                allDay = false
            )
        }

        // The 8-day strip covers previous Sunday .. next Sunday.
        val weekStart = CalendarWindow.weekStart(today)
        val sunday = weekStart.plusDays(7) // next Sunday

        return when (scenario) {
            "sunday3", "main", "compressed" -> listOf(
                evt("t1", today, 17, 30, "Reservation at Omakase @ Barracks Row"),
                evt("t2", today, 20, 15, "Avengers Endgame: Encore"),
                evt("s1", sunday, 9, 0, "Anniversary", "2"),
                evt("s2", sunday, 17, 15, "Dinner with family"),
                evt("s3", sunday, 19, 0, "Movie night"),
                evt("m1", weekStart.plusDays(1), 10, 0, "Standup"),
            )
            "todayMany" -> (1..10).map { i ->
                evt("t$i", today, 8 + i, 0, "Today event number $i with a long title")
            } + listOf(
                evt("s1", sunday, 12, 0, "Sunday lunch")
            )
            "allMany" -> (0..7).flatMap { d ->
                val date = weekStart.plusDays(d.toLong())
                (1..5).map { i ->
                    evt("d${d}e$i", date, 9 + i, 0, "Day $d event $i")
                }
            }
            "pastWeek" -> listOf(
                // Earlier days of the strip (already passed this week).
                evt("p1", weekStart, 10, 0, "Sunday brunch", "2"),
                evt("p2", weekStart, 18, 30, "Meal prep Sunday"),
                evt("p3", weekStart.plusDays(1), 9, 0, "Standup", "2"),
                evt("p4", weekStart.plusDays(2), 14, 0, "Dentist appointment"),
                // Today: one event already over, one still to come.
                evt("t0", today, 8, 0, "Morning coffee run"),
                evt("t1", today, 21, 30, "Evening movie night"),
                // A future strip day.
                evt("s1", sunday, 12, 0, "Sunday lunch")
            )
            "empty" -> emptyList()
            else -> emptyList()
        }
    }
}
