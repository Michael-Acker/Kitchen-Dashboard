package com.lifedashboard.tv.ui.theme

/**
 * A resolved color palette. All colors are ARGB ints.
 * Day themes are soft pastels; night themes stay dark and low-glare.
 */
data class Palette(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentSecondary: Int,
    /** Per-account colors for the two-person calendar. */
    val accountColorA: Int,
    val accountColorB: Int
)

data class AppTheme(
    val id: String,
    val name: String,
    val day: Palette,
    val night: Palette
)

interface ThemeProvider {
    fun allThemes(): List<AppTheme>
    fun selectedThemeIds(): Set<String>
    fun setSelectedThemeIds(ids: Set<String>)

    /** The currently applied theme: the most recently selected one. */
    fun currentTheme(): AppTheme

    /**
     * Make the given theme the current one, applied immediately.
     * Unknown ids are ignored. Does not change the selection set.
     */
    fun makeCurrentTheme(id: String)

    /**
     * Select a theme: adds it to the rotation set (most-recently-selected
     * last) and applies it immediately as the current theme.
     */
    fun selectTheme(id: String)

    /**
     * Remove a theme from the rotation set. Ignored when it would leave
     * the set empty. When the current theme is deselected, the current
     * theme falls back to the most-recently-selected remaining one.
     */
    fun deselectTheme(id: String)

    /**
     * Toggle a theme's selection: deselects when selected, selects (and
     * applies as current) when not. The theme picker's tap handler.
     */
    fun toggleTheme(id: String)

    /**
     * Resolve the day/night variant. Times are minutes since midnight local;
     * pass nulls to fall back to 07:00 / 19:00.
     */
    fun paletteFor(nowMinutes: Int, sunriseMinutes: Int?, sunsetMinutes: Int?): Palette
}
