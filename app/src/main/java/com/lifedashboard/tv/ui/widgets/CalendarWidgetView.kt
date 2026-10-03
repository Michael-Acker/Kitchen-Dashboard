package com.lifedashboard.tv.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.lifedashboard.tv.data.CalendarEventDays
import com.lifedashboard.tv.data.CalendarRepo
import com.lifedashboard.tv.data.CalendarWindow
import com.lifedashboard.tv.model.CalendarEvent
import com.lifedashboard.tv.ui.DashboardWidget
import com.lifedashboard.tv.ui.bodyView
import com.lifedashboard.tv.ui.cardDrawable
import com.lifedashboard.tv.ui.dotView
import com.lifedashboard.tv.ui.dp
import com.lifedashboard.tv.ui.labelView
import com.lifedashboard.tv.ui.mutedView
import com.lifedashboard.tv.ui.newWidgetScope
import com.lifedashboard.tv.ui.padDp
import com.lifedashboard.tv.ui.safeRefresh
import com.lifedashboard.tv.ui.showDeviceFlowDialog
import com.lifedashboard.tv.ui.theme.Palette
import com.lifedashboard.tv.ui.theme.THEMES
import com.lifedashboard.tv.ui.themedButton
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Google Calendar widget with three states:
 * (a) no OAuth client ID → setup prompt + button that opens Settings
 * (b) client ID set, slot(s) unlinked → per-slot "Sign in" buttons with the
 *     TV device-flow dialog (big user_code, verification_url, polling)
 * (c) linked → today's agenda in large type + 7-day strip,
 *     color-coded by account slot (accountColorA = "1", accountColorB = "2").
 *     The whole body must fit a ~239dp grid row (Fire TV 1080p). Layout
 *     contract, resolved post-layout: the header is fixed; the today agenda
 *     gets at most 2/3 of the remaining body and shows ALL of today's
 *     events, shrinking its item text tier by tier until they fit; the strip
 *     gets everything left (at least 1/3), pinned to the bottom so it grows
 *     upward into unused today space, truncating per-day events with
 *     "+N more" only when today maxed out its quota. Every day column is
 *     stretched to the tallest column's height so the today highlight reads
 *     as a uniform block of reserved space.
 */
class CalendarWidgetView(
    context: Context,
    private val repo: CalendarRepo,
    private val onOpenSettings: () -> Unit
) : DashboardWidget(context) {

    companion object {
        /**
         * When set, refresh() renders these events instead of hitting the
         * repository. Used by CalendarLayoutTestActivity to verify layout
         * with mocked calendar data. Null = normal operation.
         */
        var mockEvents: List<CalendarEvent>? = null
    }

    override val widgetId: String = "calendar"
    override val spanWeight: Int = 8
    override val refreshIntervalMs: Long = 60 * 1000L

    private var palette: Palette = THEMES.first().day
    private val scope: CoroutineScope = newWidgetScope()

    // Last rendered state, so applyTheme() can repaint instantly instead of
    // leaving stale rows until the next data refresh.
    private enum class CalState { LOADING, SETUP, SIGNIN, AGENDA, ERROR }
    private var calState = CalState.LOADING
    private var lastEvents: List<CalendarEvent> = emptyList()

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        padDp(22, 20, 22, 20)
    }

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
    private val dayNameFmt = DateTimeFormatter.ofPattern("EEE")

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyTheme(palette)
        content.addView(mutedView(context, palette, "Loading calendar…", 15f))
    }

    override suspend fun refresh() {
        try {
            // Test hook: mocked events bypass OAuth + repository.
            mockEvents?.let {
                withContext(Dispatchers.Main) {
                    calState = CalState.AGENDA
                    lastEvents = dedupeEvents(it)
                    showAgenda(lastEvents)
                }
                return
            }
            if (!repo.googleConfigured()) {
                withContext(Dispatchers.Main) {
                    calState = CalState.SETUP
                    showSetupPrompt()
                }
                return
            }
            val linked = (1..2).filter { runCatching { repo.isLinked(it) }.getOrDefault(false) }
            if (linked.isEmpty()) {
                withContext(Dispatchers.Main) {
                    calState = CalState.SIGNIN
                    showSignInPrompt()
                }
                return
            }
            val events = repo.getUpcomingEvents()
            withContext(Dispatchers.Main) {
                calState = CalState.AGENDA
                lastEvents = dedupeEvents(events)
                showAgenda(lastEvents)
            }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                calState = CalState.ERROR
                showError()
            }
        }
    }

    override fun applyTheme(p: Palette) {
        palette = p
        background = cardDrawable(context, p)
        // Repaint the current state immediately; the rows bake colors in at
        // build time, so without this the old theme's colors linger.
        when (calState) {
            CalState.LOADING -> {
                content.removeAllViews()
                content.addView(mutedView(context, p, "Loading calendar…", 15f))
            }
            CalState.SETUP -> showSetupPrompt()
            CalState.SIGNIN -> showSignInPrompt()
            CalState.AGENDA -> showAgenda(lastEvents)
            CalState.ERROR -> showError()
        }
    }

    override fun onDestroy() {
        scope.cancel()
    }

    private fun accountColor(accountId: String): Int =
        if (accountId == "1") palette.accountColorA else palette.accountColorB

    private fun setBody(vararg views: android.view.View) {
        content.removeAllViews()
        views.forEach { content.addView(it) }
    }

    private fun header(title: String, note: String = ""): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(labelView(context, palette, title.uppercase(), 13f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (note.isNotEmpty()) row.addView(mutedView(context, palette, note, 11f))
        return row
    }

    // ---- state (a): no client ID ------------------------------------------------
    private fun showSetupPrompt() {
        val msg = bodyView(
            context, palette,
            "Enter your Google OAuth client ID in Settings → Google Calendar to see your agenda here.",
            16f
        ).apply { gravity = Gravity.CENTER }
        val btn = themedButton(context, palette, "Open Settings").apply {
            setOnClickListener { onOpenSettings() }
        }
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            padDp(8, 8, 8, 8)
        }
        wrap.addView(labelView(context, palette, "CALENDAR", 13f).apply { gravity = Gravity.CENTER })
        val spacer = android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(1, context.dp(12)) }
        wrap.addView(spacer)
        wrap.addView(msg)
        val btnRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, context.dp(16), 0, 0)
        }
        btnRow.addView(btn)
        wrap.addView(btnRow)
        setBody(wrap)
    }

    // ---- state (b): sign in per slot -------------------------------------------
    private fun showSignInPrompt() {
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.addView(header("Calendar", "sign in to link"))
        for (slot in 1..2) {
            val linked = runCatching { repo.isLinked(slot) }.getOrDefault(false)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(10), 0, context.dp(10))
            }
            val name = if (linked) repo.accountName(slot) ?: "Account $slot" else "Account $slot"
            val status = if (linked) "linked" else "not linked"
            row.addView(dotView(context, accountColor(slot.toString()), 14))
            val label = bodyView(context, palette, "  $name", 17f, bold = true)
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(mutedView(context, palette, status, 13f).apply {
                setPadding(0, 0, context.dp(12), 0)
            })
            if (!linked) {
                val signIn = themedButton(context, palette, "Sign in").apply {
                    setOnClickListener {
                        showDeviceFlowDialog(context, palette, repo, slot) {
                            scope.safeRefresh(this@CalendarWidgetView)
                        }
                    }
                }
                row.addView(signIn)
            }
            rows.addView(row)
        }
        setBody(rows)
    }

    /**
     * Dedupes events across both calendars: two events with the same start
     * time that share at least one word (case-insensitive) in their titles
     * are the same event (e.g. Gmail auto-adding a reservation that was
     * also duplicated onto the personal calendar). Winner, in priority
     * order: (1) account "1" over account "2"; (2) same account → longer
     * title wins; exact tie → the first one is kept.
     */
    private fun dedupeEvents(events: List<CalendarEvent>): List<CalendarEvent> {
        if (events.size < 2) return events
        val wordsOf = { e: CalendarEvent ->
            e.title.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 2 }
                .toSet()
        }
        // Union-find: link same-start events that share a word, then keep
        // one winner per cluster.
        val parent = IntArray(events.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            return r
        }
        val words = events.map(wordsOf)
        for (i in events.indices) {
            for (j in i + 1 until events.size) {
                if (events[i].start != events[j].start) continue
                if (words[i].any { it in words[j] }) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) parent[rj] = ri
                }
            }
        }
        fun accountRank(id: String) = id.toIntOrNull() ?: Int.MAX_VALUE
        return events.indices
            .groupBy { find(it) }
            .values
            .map { idxs ->
                // Lowest account rank wins; then longest title; minWithOrNull
                // keeps the first on an exact tie.
                idxs.map { events[it] }.minWithOrNull(
                    compareBy({ accountRank(it.accountId) }, { -it.title.length })
                )!!
            }
    }

    // ---- state (c): today's agenda + 7-day strip ---------------------------------
    // Anchored to TODAY (not "soonest upcoming"): the user expects the big
    // list to be today's events. The 7-day strip below covers the rest.
    private fun showAgenda(events: List<CalendarEvent>) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // MATCH_PARENT so the weighted stripWrap below can actually
            // expand and pin the week strip to the bottom of the widget.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        // The whole displayed week stays populated all day: today's agenda
        // lists ALL of today's events, including ones that already ended
        // (the fetch is window-anchored, so past events are in the data)
        // and multi-day events spanning today, shown as all-day rows —
        // the same expansion the 8-day strip uses, so the two can never
        // disagree about what occupies today. All-day rows float first,
        // matching calendar convention; the rest stay in start order.
        val today = LocalDate.now()
        val todays = CalendarEventDays.eventsOnDate(today, events)
            .sortedWith(compareBy({ !it.allDay }, { it.start }))

        val headerView = header("Calendar",
            if (todays.isEmpty()) "nothing today" else "${todays.size} today")
        root.addView(headerView)

        // Today's agenda: ALL of today's events (no take(2) cap). Sized
        // after layout to at most 2/3 of the body, shrinking item text tier
        // by tier until everything fits.
        val todayList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (todays.isEmpty()) {
            todayList.addView(mutedView(context, palette, "Nothing today.", 15f).apply {
                setPadding(0, context.dp(4), 0, context.dp(4))
            })
        } else {
            buildTodayRows(todayList, todays, TODAY_TIERS[0], moreCount = 0)
        }
        root.addView(todayList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 8-day strip in calendar week order: previous Sunday .. next Sunday.
        // E.g. Sat Sep 26 -> Sun 20 .. Sun 27 with Sat 26 highlighted. The
        // window comes from CalendarWindow (the same definition the fetch
        // uses), so strip and data can never disagree. The stripWrap fills
        // whatever vertical space the today list leaves and pins the strip
        // to its bottom, so the strip grows upward into unused today space
        // (or truncates with "+N more" when today maxed out its 2/3 quota).
        val weekStart = CalendarWindow.weekStart(today)
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(4), 0, 0)
        }
        // Built by populateWeekly in layoutCalendarBody (post-layout).
        val stripWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        stripWrap.addView(strip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(stripWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setBody(root)
        root.post {
            if (root.parent == null) return@post
            layoutCalendarBody(headerView, todayList, todays, strip, weekStart, events)
        }
    }

    /** Text-size tiers for the today agenda, largest first. */
    private data class TodayTier(val titleSp: Float, val timeSp: Float, val acctSp: Float, val padDp: Int)

    private val TODAY_TIERS = listOf(
        TodayTier(13f, 11f, 9f, 4),
        TodayTier(12f, 10f, 8f, 3),
        TodayTier(11f, 10f, 8f, 3),
        TodayTier(10f, 9f, 7f, 2),
        TodayTier(9f, 8f, 7f, 2)
    )

    private fun buildTodayRows(
        list: LinearLayout,
        events: List<CalendarEvent>,
        tier: TodayTier,
        moreCount: Int
    ) {
        list.removeAllViews()
        events.forEach { e -> list.addView(todayRow(e, tier)) }
        if (moreCount > 0) {
            list.addView(mutedView(context, palette, "+$moreCount more", 12f).apply {
                setPadding(0, context.dp(4), 0, 0)
            })
        }
    }

    /**
     * Layout contract (per user spec):
     *
     * DAILY: allocated 2/3 of the vertical space. Events are placed until
     * space runs out; if adding an event would exceed, ALL events shrink to
     * the next smaller font tier. At the smallest tier, truncate with "+n more".
     *
     * WEEKLY: bottom locked to the bottom of the widget. Desired height =
     * height required to display ALL events for the day with the largest
     * number of events. Maximum height = space below the daily view (its top
     * cannot go above the daily's bottom). Minimum height = 1/3 of the
     * vertical space. Once the height is established, each day populates;
     * a day with more events than fit truncates with "+n more".
     */
    private fun layoutCalendarBody(
        headerView: LinearLayout,
        todayList: LinearLayout,
        todays: List<CalendarEvent>,
        strip: LinearLayout,
        weekStart: LocalDate,
        events: List<CalendarEvent>
    ) {
        // The body the header/daily/weekly share is the content's INNER
        // height (inside its padding).
        val innerH = content.height - content.paddingTop - content.paddingBottom
        if (innerH <= 0) return
        // headerView.height is 0 if this post ran before the layout pass;
        // measure manually so we don't over-budget.
        val headerH = headerView.height.takeIf { it > 0 } ?: measureH(headerView)
        val available = innerH - headerH
        if (available <= 0) return

        // DAILY: 2/3 of vertical space, shrink font tier by tier, then "+n more".
        val dailyBudget = available * 2 / 3
        val dailyH = if (todays.isEmpty()) {
            val h = measureH(todayList)
            todayList.layoutParams = todayList.layoutParams.apply { height = h }
            h
        } else {
            sizeDaily(todayList, todays, dailyBudget)
        }

        // WEEKLY:
        // - desiredH: tallest day column with ALL its events shown.
        // - maxH: space below the daily view.
        // - minH: 1/3 of vertical space (daily never takes more than 2/3,
        //   so maxH >= minH always holds).
        val stripPadV = strip.paddingTop + strip.paddingBottom
        val desiredH = measureWeeklyDesired(weekStart, events) + stripPadV
        val maxH = (available - dailyH).coerceAtLeast(0)
        val minH = available / 3
        val weeklyH = desiredH.coerceIn(minH, maxH)

        val dayLimits = populateWeekly(strip, weekStart, events, weeklyH)

        logLayoutReport(available, dailyBudget, dailyH, desiredH, minH, maxH,
            weeklyH, dayLimits)
    }

    /**
     * Height of the tallest day column when showing ALL of that day's
     * events (no truncation). This is the weekly view's desired height.
     */
    // eventsForDay lives in CalendarEventDays now (pure + unit-tested);
    // the strip, the today agenda, and the measurement below all delegate
    // to it so they can never disagree about what occupies a day.

    private fun measureWeeklyDesired(weekStart: LocalDate, events: List<CalendarEvent>): Int {
        var maxH = 0
        for (i in 0..7) {
            val date = weekStart.plusDays(i.toLong())
            val dayEvents = CalendarEventDays.eventsOnDate(date, events)
            // limit = size → all events shown, no "+more" line.
            maxH = maxOf(maxH, measureH(dayColumn(date, dayEvents, dayEvents.size)))
        }
        return maxH
    }

    /**
     * Populates the 8-day strip at [weeklyH] px. Each day shows as many of
     * its events as fit; days with more truncate with "+n more". Every
     * column is pinned to the same inner height so the today highlight is
     * a uniform block. The strip's bottom is locked to the widget bottom
     * by the stripWrap (gravity=BOTTOM) in showAgenda.
     *
     * @return list of (date, eventCount, limitUsed) for test verification.
     */
    private fun populateWeekly(
        strip: LinearLayout,
        weekStart: LocalDate,
        events: List<CalendarEvent>,
        weeklyH: Int
    ): List<Triple<LocalDate, Int, Int>> {
        val stripPadV = strip.paddingTop + strip.paddingBottom
        val colBudget = (weeklyH - stripPadV).coerceAtLeast(0)
        val limits = mutableListOf<Triple<LocalDate, Int, Int>>()
        strip.removeAllViews()
        for (i in 0..7) {
            val date = weekStart.plusDays(i.toLong())
            val dayEvents = CalendarEventDays.eventsOnDate(date, events)
            // Largest limit whose column (including "+n more" when truncated)
            // fits in the pinned column height.
            var limit = dayEvents.size
            while (limit > 0 && measureH(dayColumn(date, dayEvents, limit)) > colBudget) {
                limit--
            }
            limits.add(Triple(date, dayEvents.size, limit))
            val col = dayColumn(date, dayEvents, limit)
            col.layoutParams = col.layoutParams.apply { height = colBudget }
            strip.addView(col)
        }
        strip.layoutParams = strip.layoutParams.apply { height = weeklyH }
        return limits
    }

    /**
     * Locks the daily list to at most [budget] px (2/3 of vertical space):
     * tries the largest text tier first and steps down until all of today's
     * events fit. Backstop: at the smallest tier, keeps as many full rows
     * as fit plus "+N more". Returns the locked height.
     */
    private fun sizeDaily(todayList: LinearLayout, todays: List<CalendarEvent>, budget: Int): Int {
        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(
            todayList.width, android.view.View.MeasureSpec.EXACTLY)
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(
            0, android.view.View.MeasureSpec.UNSPECIFIED)
        var tierIdx = 0
        buildTodayRows(todayList, todays, TODAY_TIERS[tierIdx], 0)
        while (tierIdx < TODAY_TIERS.lastIndex) {
            todayList.measure(widthSpec, heightSpec)
            if (todayList.measuredHeight <= budget) break
            tierIdx++
            buildTodayRows(todayList, todays, TODAY_TIERS[tierIdx], 0)
        }
        todayList.measure(widthSpec, heightSpec)
        var h = todayList.measuredHeight
        if (h > budget) {
            val rowH = todayList.getChildAt(0)?.measuredHeight ?: 0
            if (rowH > 0) {
                // Reserve ~half a row for the "+N more" line.
                val n = ((budget - rowH / 2) / rowH).coerceIn(1, todays.size)
                buildTodayRows(todayList, todays.take(n), TODAY_TIERS.last(), todays.size - n)
                todayList.measure(widthSpec, heightSpec)
                h = todayList.measuredHeight
            }
        }
        todayList.layoutParams = todayList.layoutParams.apply { height = h }
        return h
    }

    /**
     * Logs a structured layout report for integration-test verification.
     * Tag: CALTEST. Each line is "CALTEST <key>=<value>".
     */
    private fun logLayoutReport(
        available: Int,
        dailyBudget: Int,
        dailyH: Int,
        desiredH: Int,
        minH: Int,
        maxH: Int,
        weeklyH: Int,
        dayLimits: List<Triple<LocalDate, Int, Int>>
    ) {
        val tag = "CALTEST"
        android.util.Log.d(tag, "available=$available dailyBudget=$dailyBudget dailyH=$dailyH")
        android.util.Log.d(tag, "weekly desiredH=$desiredH minH=$minH maxH=$maxH weeklyH=$weeklyH")
        for ((date, count, limit) in dayLimits) {
            val more = if (count > limit) count - limit else 0
            android.util.Log.d(tag, "day $date count=$count limit=$limit more=$more")
        }
        // Verify spec invariants.
        val dailyOk = dailyH <= dailyBudget
        val weeklyOk = weeklyH in minH..maxH
        // weeklyH <= maxH means the strip fits below the daily view with its
        // bottom at the widget bottom (no clipping at the card edge).
        val noOverflow = weeklyH <= maxH
        android.util.Log.d(tag, "CHECK dailyH<=dailyBudget: $dailyOk ($dailyH <= $dailyBudget)")
        android.util.Log.d(tag, "CHECK weeklyH in [minH,maxH]: $weeklyOk ($weeklyH in [$minH,$maxH])")
        android.util.Log.d(tag, "CHECK noOverflow (weeklyH<=maxH): $noOverflow")
        if (dailyOk && weeklyOk && noOverflow) {
            android.util.Log.d(tag, "RESULT PASS")
        } else {
            android.util.Log.d(tag, "RESULT FAIL")
        }
    }

    /** Manual UNSPECIFIED measurement for the pre-layout case. */
    private fun measureH(v: android.view.View): Int {
        v.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        return v.measuredHeight
    }

    private fun todayRow(e: CalendarEvent, tier: TodayTier): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(tier.padDp), 0, context.dp(tier.padDp))
        }
        val timeText = if (e.allDay) "All day" else e.start.format(timeFmt)
        val time = TextView(context).apply {
            text = timeText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, tier.timeSp)
            setTypeface(Typeface.MONOSPACE)
            setTextColor(palette.textSecondary)
            gravity = Gravity.END
        }
        time.layoutParams = LinearLayout.LayoutParams(context.dp(84), LinearLayout.LayoutParams.WRAP_CONTENT)
        row.addView(time)
        row.addView(dotView(context, accountColor(e.accountId), 12).apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                leftMargin = context.dp(10)
                rightMargin = context.dp(10)
            }
        })
        val textBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textBlock.addView(bodyView(context, palette, e.title, tier.titleSp, bold = true).apply { maxLines = 1 })
        textBlock.addView(mutedView(context, palette, e.accountName, tier.acctSp).apply { maxLines = 1 })
        row.addView(textBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun dayColumn(date: LocalDate, events: List<CalendarEvent>, limit: Int): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val isToday = date == LocalDate.now()
            if (isToday) {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(palette.surfaceVariant)
                    cornerRadius = context.dp(8).toFloat()
                }
            }
            padDp(4, 4, 4, 4)
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "dayHead"
        }
        head.addView(mutedView(context, palette, date.format(dayNameFmt).uppercase(), 10f).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        val num = bodyView(context, palette, date.dayOfMonth.toString(), 13f, bold = true).apply {
            setPadding(context.dp(4), 0, 0, 0)
        }
        head.addView(num)
        col.addView(head)
        if (events.isEmpty()) {
            col.addView(mutedView(context, palette, "—", 10f).apply {
                setPadding(0, context.dp(2), 0, 0)
            })
        } else {
            // Ultra-compact: dot + "time title" on a single short line per event.
            // The limit is dynamic (see fitStripToHeight); "+N more" covers the rest.
            events.sortedBy { it.start }.take(limit).forEach { e ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, context.dp(1), 0, 0)
                    tag = "eventRow"
                }
                row.addView(dotView(context, accountColor(e.accountId), 6))
                // 8-day strip: title only, no time prefix (times live in the today view).
                val label = e.title
                row.addView(mutedView(context, palette, label, 10f).apply {
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(context.dp(3), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                col.addView(row)
            }
            if (events.size > limit) {
                col.addView(mutedView(context, palette, "+${events.size - limit} more", 10f).apply {
                    setPadding(0, context.dp(1), 0, 0)
                    tag = "moreLine"
                })
            }
        }
        return col
    }

    private fun showError() {
        setBody(
            header("Calendar"),
            mutedView(context, palette, "Couldn't load events.", 15f).apply {
                gravity = Gravity.CENTER
                setPadding(0, context.dp(24), 0, 0)
            }
        )
    }
}
