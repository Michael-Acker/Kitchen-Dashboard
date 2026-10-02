package com.lifedashboard.tv.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.lifedashboard.tv.data.NewsRepo
import com.lifedashboard.tv.model.NewsHeadline
import com.lifedashboard.tv.ui.DashboardWidget
import com.lifedashboard.tv.ui.bodyView
import com.lifedashboard.tv.ui.cardDrawable
import com.lifedashboard.tv.ui.dp
import com.lifedashboard.tv.ui.labelView
import com.lifedashboard.tv.ui.makeFocusable
import com.lifedashboard.tv.ui.mutedView
import com.lifedashboard.tv.ui.padDp
import com.lifedashboard.tv.ui.theme.Palette
import com.lifedashboard.tv.ui.theme.THEMES
import com.lifedashboard.tv.ui.themedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Headline carousel: big title, source, "n / 5" indicator, auto-advance
 * every 10 seconds, D-pad-focusable Prev/Next buttons.
 */
class NewsWidgetView(
    context: Context,
    private val repo: NewsRepo
) : DashboardWidget(context) {

    override val widgetId: String = "news"
    override val spanWeight: Int = 12

    private var palette: Palette = THEMES.first().day
    private val handler = Handler(Looper.getMainLooper())

    private var headlines: List<NewsHeadline> = emptyList()
    private var index: Int = 0

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        padDp(24, 20, 24, 20)
    }
    private val headRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val countView: TextView = mutedView(context, palette, "", 13f)
    private val titleView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 3
    }
    private val sourceView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
    }
    private val prevButton = themedButton(context, palette, "‹").apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
    }
    private val nextButton = themedButton(context, palette, "›").apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
    }

    private val advance = object : Runnable {
        override fun run() {
            if (headlines.isNotEmpty()) {
                index = (index + 1) % headlines.size
                bind()
            }
            handler.postDelayed(this, AUTO_ADVANCE_MS)
        }
    }

    init {
        headRow.addView(
            labelView(context, palette, "TOP STORIES", 13f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        headRow.addView(countView)

        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(10), 0, context.dp(10))
        }
        mainRow.addView(prevButton)
        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(18), 0, context.dp(18), 0)
        }
        textBlock.addView(titleView)
        sourceView.setPadding(0, context.dp(10), 0, 0)
        textBlock.addView(sourceView)
        mainRow.addView(
            textBlock,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        mainRow.addView(nextButton)

        content.addView(headRow)
        content.addView(
            mainRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        prevButton.setOnClickListener { step(-1) }
        nextButton.setOnClickListener { step(1) }

        applyTheme(palette)
        titleView.text = "Loading headlines…"
        titleView.setTextColor(palette.textSecondary)
        handler.postDelayed(advance, AUTO_ADVANCE_MS)
    }

    private fun step(delta: Int) {
        if (headlines.isEmpty()) return
        index = (index + delta + headlines.size) % headlines.size
        bind()
        // Restart the auto-advance timer from the manual navigation.
        handler.removeCallbacks(advance)
        handler.postDelayed(advance, AUTO_ADVANCE_MS)
    }

    private fun bind() {
        val h = headlines.getOrNull(index) ?: return
        titleView.text = h.title
        titleView.setTextColor(palette.textPrimary)
        sourceView.text = h.source
        sourceView.setTextColor(palette.accent)
        countView.text = "${index + 1} / ${headlines.size}"
    }

    override suspend fun refresh() {
        try {
            val items = repo.getHeadlines().take(5)
            withContext(Dispatchers.Main) {
                headlines = items
                if (items.isEmpty()) {
                    titleView.text = "No headlines right now."
                    titleView.setTextColor(palette.textSecondary)
                    sourceView.text = ""
                    countView.text = ""
                } else {
                    index = index.coerceIn(items.indices)
                    bind()
                }
            }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                titleView.text = "Headlines unavailable."
                titleView.setTextColor(palette.textSecondary)
            }
        }
    }

    override fun applyTheme(p: Palette) {
        palette = p
        background = cardDrawable(context, p)
        // Focus backgrounds embed palette colors, so refresh them.
        prevButton.makeFocusable(p, p.surfaceVariant)
        nextButton.makeFocusable(p, p.surfaceVariant)
        prevButton.setTextColor(p.textPrimary)
        nextButton.setTextColor(p.textPrimary)
        countView.setTextColor(p.textSecondary)
        if (headlines.isNotEmpty()) bind()
    }

    override fun onDestroy() {
        handler.removeCallbacks(advance)
    }

    companion object {
        private const val AUTO_ADVANCE_MS = 10_000L
    }
}
