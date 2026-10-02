package com.lifedashboard.tv.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.lifedashboard.tv.data.WeatherRepo
import com.lifedashboard.tv.model.WeatherData
import com.lifedashboard.tv.ui.theme.WeatherRole
import com.lifedashboard.tv.ui.theme.WEATHER_DIVIDER_ALPHA
import com.lifedashboard.tv.ui.theme.weatherRoleColor
import com.lifedashboard.tv.ui.DashboardWidget
import com.lifedashboard.tv.ui.bodyView
import com.lifedashboard.tv.ui.cardDrawable
import com.lifedashboard.tv.ui.dp
import com.lifedashboard.tv.ui.labelView
import com.lifedashboard.tv.ui.mutedView
import com.lifedashboard.tv.ui.padDp
import com.lifedashboard.tv.ui.theme.Palette
import com.lifedashboard.tv.ui.theme.THEMES
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Current conditions + 7-day strip. Exposes lastData so MainActivity can
 * re-evaluate the day/night theme from real sunrise/sunset times.
 */
class WeatherWidgetView(
    context: Context,
    private val repo: WeatherRepo
) : DashboardWidget(context) {

    override val widgetId: String = "weather"
    override val spanWeight: Int = 8

    var lastData: WeatherData? = null
        private set

    private var palette: Palette = THEMES.first().day

    // Loading/error text bakes its colors in at build time, so the state is
    // tracked and repainted on every applyTheme (same pattern as the
    // calendar widget) — otherwise a day-palette loading view lingers into
    // the night in the wrong color.
    private enum class WxState { LOADING, ERROR, DATA }
    private var wxState = WxState.LOADING

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // Compact vertical budget: the kiosk grid gives this widget half the
        // viewport height (~239dp on 1080p). Everything — header, current,
        // stats, dividers, and the 7-day strip — must fit without clipping.
        padDp(18, 10, 18, 10)
    }
    private val tempView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val glyphView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
        gravity = Gravity.CENTER
    }
    private val conditionView: TextView = mutedView(context, palette, "", 14f)
    private val statsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    private val stripRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyTheme(palette)
        showLoading()
    }

    override suspend fun refresh() {
        try {
            val data = repo.refresh()
            lastData = data
            withContext(Dispatchers.Main) {
                wxState = WxState.DATA
                bind(data)
            }
        } catch (_: Exception) {
            // A single failed poll (transient network blip, API hiccup) must
            // not blank the widget: keep the last good data on screen. Only
            // show the error state when nothing has ever loaded.
            if (lastData == null) withContext(Dispatchers.Main) {
                wxState = WxState.ERROR
                showError()
            }
        }
    }

    override fun applyTheme(p: Palette) {
        palette = p
        background = cardDrawable(context, p)
        // Repaint the current state with the new palette; the loading/error
        // views bake colors in at build time, so without this a stale
        // theme's colors linger (e.g. day-green "Loading weather…" at night).
        when (wxState) {
            WxState.LOADING -> showLoading()
            WxState.ERROR -> showError()
            WxState.DATA -> lastData?.let { bind(it) }
        }
    }

    private fun rebuild(vararg views: android.view.View) {
        content.removeAllViews()
        views.forEach { content.addView(it) }
    }

    private fun headerRow(title: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(labelView(context, palette, title.uppercase(), 13f))
        return row
    }

    private fun statCell(label: String, value: String): LinearLayout {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        cell.addView(mutedView(context, palette, label, 11f))
        cell.addView(bodyView(context, palette, value, 14f, bold = true))
        return cell
    }

    private fun divider(): android.view.View =
        android.view.View(context).apply {
            setBackgroundColor(weatherRoleColor(palette, WeatherRole.DIVIDER))
            alpha = WEATHER_DIVIDER_ALPHA
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1)
            ).apply {
                topMargin = context.dp(6)
                bottomMargin = context.dp(6)
            }
        }

    // Loading/error text matches the calendar and packages widgets: primary
    // (white at night) and left-justified, never muted/centered.
    private fun showLoading() {
        // Muted/secondary text, matching calendar + packages loading states
        // (2026-09-29: was primary/white, which broke parity).
        rebuild(mutedView(context, palette, "Loading weather…", 15f))
    }

    private fun showError() {
        rebuild(mutedView(context, palette, "Weather unavailable", 15f))
    }

    private fun bind(data: WeatherData) {
        // Detach reused views from their previous parents (2026-09-29):
        // bind() runs on every theme change, and a View can have only one
        // parent — addView on an already-attached view throws
        // IllegalStateException. MainActivity.applyTheme swallows it via
        // runCatching, which left the whole widget painted in the old
        // theme's colors (the Periwinkle→Saffron stale-weather bug).
        (tempView.parent as? android.view.ViewGroup)?.removeView(tempView)
        (conditionView.parent as? android.view.ViewGroup)?.removeView(conditionView)
        (glyphView.parent as? android.view.ViewGroup)?.removeView(glyphView)

        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tempBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        tempView.text = "${data.tempF}°"
        tempView.setTextColor(weatherRoleColor(palette, WeatherRole.TEMPERATURE))
        conditionView.text = weatherLabel(data.weatherCode)
        conditionView.setTextColor(weatherRoleColor(palette, WeatherRole.CONDITION))
        tempBlock.addView(tempView)
        tempBlock.addView(conditionView)
        glyphView.text = weatherGlyph(data.weatherCode)
        mainRow.addView(tempBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        mainRow.addView(glyphView)

        statsRow.removeAllViews()
        statsRow.addView(statCell("High / Low", "${data.highF}° / ${data.lowF}°"))
        statsRow.addView(statCell("Rain", "${data.rainChancePct}%"))
        statsRow.addView(statCell("Sunrise", data.sunrise?.format(timeFmt) ?: "—"))
        statsRow.addView(statCell("Sunset", data.sunset?.format(timeFmt) ?: "—"))

        stripRow.removeAllViews()
        val dayNameFmt = DateTimeFormatter.ofPattern("EEE")
        data.daily.take(7).forEachIndexed { i, day ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    // Keep the highlight from touching neighboring cells.
                    val m = context.dp(3)
                    setMargins(m, 0, m, 0)
                }
                // Identical internal padding on EVERY cell so the day name,
                // icon and temps sit in exactly the same place whether or not
                // the cell is highlighted; only the background differs.
                padDp(10, 6, 10, 6)
                if (i == 0) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(weatherRoleColor(palette, WeatherRole.TODAY_HIGHLIGHT))
                        cornerRadius = context.dp(10).toFloat()
                    }
                }
            }
            val name = if (i == 0) "Today" else day.date.format(dayNameFmt)
            cell.addView(mutedView(context, palette, name, 11f).apply {
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            cell.addView(TextView(context).apply {
                text = weatherGlyph(day.weatherCode)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
            })
            val temps = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            temps.addView(bodyView(context, palette, "${day.highF}°", 11f, bold = true))
            val low = mutedView(context, palette, " ${day.lowF}°", 11f)
            temps.addView(low)
            cell.addView(temps)
            stripRow.addView(cell)
        }

        rebuild(headerRow("Weather"), mainRow, divider(), statsRow, divider(), stripRow)
    }

    companion object {
        /** WMO weather-code → human label. */
        fun weatherLabel(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75, 77 -> "Snow"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Storm with hail"
            else -> "—"
        }

        /** WMO weather-code → glyph. */
        fun weatherGlyph(code: Int): String = when (code) {
            0, 1 -> "☀"
            2 -> "⛅"
            3 -> "☁"
            45, 48 -> "🌫"
            51, 53, 55, 56, 57 -> "🌦"
            61, 63, 65, 80, 81, 82 -> "🌧"
            66, 67 -> "🌧"
            71, 73, 75, 77, 85, 86 -> "❄"
            95, 96, 99 -> "⛈"
            else -> "☁"
        }
    }
}
