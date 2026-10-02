package com.lifedashboard.tv.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lifedashboard.tv.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shared programmatic-view helpers for widgets and settings. */

fun Context.dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

fun View.padDp(l: Int, t: Int, r: Int, b: Int) {
    setPadding(context.dp(l), context.dp(t), context.dp(r), context.dp(b))
}

/** Rounded card background in the palette surface color. */
fun cardDrawable(context: Context, p: Palette, radiusDp: Int = 18): GradientDrawable =
    GradientDrawable().apply {
        setColor(p.surface)
        cornerRadius = context.dp(radiusDp).toFloat()
    }

/**
 * Background for a focusable row/button: plain fill normally,
 * accent outline when D-pad focused — the visible focus state.
 */
fun focusableBackground(context: Context, p: Palette, fill: Int): StateListDrawable {
    val r = context.dp(14).toFloat()
    val focused = GradientDrawable().apply {
        setColor(fill)
        setStroke(context.dp(3), p.accent)
        cornerRadius = r
    }
    val normal = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = r
    }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(), normal)
    }
}

/** Marks a view D-pad focusable with the visible focus treatment. */
fun View.makeFocusable(p: Palette, fill: Int) {
    isFocusable = true
    isClickable = true
    background = focusableBackground(context, p, fill)
}

fun labelView(context: Context, p: Palette, text: String, sizeSp: Float = 13f): TextView =
    TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(p.textSecondary)
        setTypeface(typeface, Typeface.BOLD)
    }

fun bodyView(context: Context, p: Palette, text: String, sizeSp: Float = 15f, bold: Boolean = false): TextView =
    TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(p.textPrimary)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun mutedView(context: Context, p: Palette, text: String, sizeSp: Float = 13f): TextView =
    TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(p.textSecondary)
    }

fun themedButton(context: Context, p: Palette, text: String): Button =
    Button(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(p.textPrimary)
        setTypeface(typeface, Typeface.BOLD)
        makeFocusable(p, p.surfaceVariant)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams = lp
        padDp(18, 10, 18, 10)
        minHeight = context.dp(48)
    }

/** Small colored dot (account colors, status, etc.). */
fun dotView(context: Context, color: Int, sizeDp: Int = 12): View =
    View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp)).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
    }

fun verticalStack(context: Context): LinearLayout =
    LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

fun horizontalRow(context: Context): LinearLayout =
    LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

/**
 * A per-widget coroutine scope for fire-and-forget UI work (e.g. re-refresh
 * after a device-flow dialog completes). Cancel in onDestroy().
 */
fun newWidgetScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

fun CoroutineScope.safeRefresh(widget: DashboardWidget) = launch(Dispatchers.IO) {
    try {
        widget.refresh()
    } catch (_: Exception) {
        // refresh() must never throw per contract; belt and suspenders.
    }
}
