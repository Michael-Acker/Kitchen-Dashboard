package com.lifedashboard.tv.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.lifedashboard.tv.ui.theme.Palette
import com.lifedashboard.tv.ui.theme.ThemeAudit

/**
 * Android half of the theme audit: walks a live view hierarchy and collects
 * every text/background color for [ThemeAudit.check]. Pure checking lives in
 * ThemeAudit (JVM-tested); this file only does view-tree reading.
 *
 * Only dashboard widgets are audited (the persistent UI). The settings
 * panel is transient and uses platform widgets (CheckBox) with system tints
 * that are intentionally not palette colors.
 */
object ThemeAuditView {

    /**
     * Collects (element, argb) for every TextView text color and every
     * readable background under [root]. [widgetLabel] prefixes element paths
     * (e.g. "weather").
     */
    fun collectColors(root: View, widgetLabel: String): List<ThemeAudit.ColorUse> {
        val out = mutableListOf<ThemeAudit.ColorUse>()
        walk(root, widgetLabel, out)
        return out
    }

    /**
     * The allowlisted RGB set: the active palette plus transparent and the
     * platform default text color (for views that intentionally carry no
     * palette color, e.g. emoji glyphs rendered by the system font).
     * Deliberately tight: a hardcoded white/black that isn't in the palette
     * is flagged, so future hardcoded colors can't hide here.
     */
    fun allowedRgb(context: Context, palette: Palette): Set<Int> {
        val rgb = ThemeAudit.paletteRgb(palette).toMutableSet()
        rgb += 0x000000 // transparent
        // Platform default text color, alpha-stripped.
        runCatching {
            rgb += TextView(context).currentTextColor and 0x00FFFFFF
        }
        return rgb
    }

    private fun walk(v: View, path: String, out: MutableList<ThemeAudit.ColorUse>) {
        val here = "$path › ${describe(v)}"
        if (v is TextView) {
            out += ThemeAudit.ColorUse("$here.text", v.currentTextColor)
        }
        backgroundArgb(v.background)?.let { bg ->
            out += ThemeAudit.ColorUse("$here.bg", bg)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                walk(v.getChildAt(i), here, out)
            }
        }
    }

    private fun describe(v: View): String {
        val name = v.javaClass.simpleName.ifEmpty { "View" }
        val extra = (v as? TextView)?.text?.toString()?.take(18)?.let { "('$it')" } ?: ""
        val tag = (v.tag as? String)?.let { " #$it" } ?: ""
        return "$name$extra$tag"
    }

    /** Best-effort background color; null when not a readable solid. */
    private fun backgroundArgb(d: Drawable?): Int? {
        return when (d) {
            is ColorDrawable -> d.color
            is GradientDrawable -> gradientColor(d)
            is StateListDrawable -> runCatching { backgroundArgb(d.current) }.getOrNull()
            else -> null
        }
    }

    private fun gradientColor(d: GradientDrawable): Int? {
        if (Build.VERSION.SDK_INT < 24) return null
        return runCatching { d.color?.defaultColor }.getOrNull()
    }
}
