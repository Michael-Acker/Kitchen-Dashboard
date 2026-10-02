package com.lifedashboard.tv.ui.theme

/**
 * Rigorous "every UI color is within the active theme" checking (2026-09-29).
 *
 * Motivation: a widget painted with a stale theme's colors looks almost
 * right — white text is white in every theme — so the bug only shows up as
 * a subtly wrong tint that no build step catches. This audit makes it
 * mechanical: after every theme application, every text/background color in
 * every dashboard widget must resolve to the active palette (alpha ignored)
 * or to an explicitly allowlisted value (transparent, system defaults).
 *
 * The checking itself ([check]) is pure Kotlin and unit-tested on the JVM.
 * Collecting the colors from a live view hierarchy is Android work and
 * lives in ui/ThemeAuditView.kt; MainActivity runs the full audit after
 * every applyTheme() and logs violations to the forensic log.
 */
object ThemeAudit {

    /** One themed color use found in a view hierarchy. */
    data class ColorUse(val element: String, val argb: Int)

    /** A color that falls outside the active theme. */
    data class Violation(val element: String, val argb: Int, val detail: String)

    /** RGB (alpha-stripped) values of every color slot in [p]. */
    fun paletteRgb(p: Palette): Set<Int> = setOf(
        p.background,
        p.surface,
        p.surfaceVariant,
        p.textPrimary,
        p.textSecondary,
        p.accent,
        p.accentSecondary,
        p.accountColorA,
        p.accountColorB,
    ).map { it and 0x00FFFFFF }.toSet()

    /** RGB values used by every known theme, day and night variants. */
    fun allThemesRgb(): Set<Int> =
        THEMES.flatMap { listOf(it.day, it.night) }
            .flatMap { paletteRgb(it) }
            .toSet()

    /**
     * Pure check: every entry of [colors] must land in [allowedRgb] — the
     * active palette's RGB plus explicitly allowlisted RGBs (transparent,
     * system-default text color, ...). Anything else is a violation:
     * "stale" when the RGB belongs to a different known theme (the classic
     * un-rethemed-widget bug), "unknown" otherwise.
     *
     * Returns the violations; an empty list means every color is within-theme.
     */
    fun check(
        colors: List<ColorUse>,
        allowedRgb: Set<Int>,
        allRgb: Set<Int> = allThemesRgb(),
    ): List<Violation> = colors.mapNotNull { use ->
        val rgb = use.argb and 0x00FFFFFF
        if (rgb in allowedRgb) return@mapNotNull null
        val detail = if (rgb in allRgb) {
            "stale color from a different theme"
        } else {
            "color not in the active palette"
        }
        Violation(use.element, use.argb, detail)
    }

    /** "#aarrggbb" for log lines. */
    fun hex(argb: Int): String = "#%08x".format(argb)
}
