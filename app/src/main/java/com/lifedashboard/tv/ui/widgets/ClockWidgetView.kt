package com.lifedashboard.tv.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.lifedashboard.tv.ui.DashboardWidget
import com.lifedashboard.tv.ui.cardDrawable
import com.lifedashboard.tv.ui.dp
import com.lifedashboard.tv.ui.padDp
import com.lifedashboard.tv.ui.theme.Palette
import com.lifedashboard.tv.ui.theme.THEMES
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compact live clock. Ticks every second on its own Handler — no network,
 * no remote needed.
 */
class ClockWidgetView(context: Context) : DashboardWidget(context) {

    override val widgetId: String = "clock"
    override val spanWeight: Int = 4

    private val handler = Handler(Looper.getMainLooper())

    private val timeView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val periodView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTypeface(typeface, Typeface.BOLD)
    }
    private val dateView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        gravity = Gravity.CENTER
    }

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    init {
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            padDp(16, 16, 16, 16)
        }
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        timeRow.addView(timeView)
        periodView.setPadding(context.dp(8), 0, 0, context.dp(10))
        timeRow.addView(periodView)
        inner.addView(timeRow)
        inner.addView(dateView)
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyTheme(THEMES.first().day)
        updateClock()
        handler.post(tick)
    }

    private fun updateClock() {
        val now = LocalTime.now()
        timeView.text = now.format(DateTimeFormatter.ofPattern("h:mm"))
        periodView.text = now.format(DateTimeFormatter.ofPattern("a"))
        dateView.text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
    }

    override suspend fun refresh() {
        // Clock ticks on its own; refresh just syncs immediately.
        withContext(Dispatchers.Main) { updateClock() }
    }

    override fun applyTheme(p: Palette) {
        background = cardDrawable(context, p)
        timeView.setTextColor(p.textPrimary)
        periodView.setTextColor(p.textSecondary)
        dateView.setTextColor(p.textSecondary)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
    }
}
