package com.lifedashboard.tv.ui.theme

/**
 * Weather widget color-role mapping (2026-09-29).
 *
 * The user's standing spec for which palette color each weather element
 * uses. Encoded as pure logic (no Android deps) so unit tests can pin it:
 * a UI element being the wrong color doesn't fail the build, so the mapping
 * itself must be tested.
 *
 * Roles:
 * - Card background → surface
 * - WEATHER header → textSecondary
 * - Temperature → textPrimary
 * - Condition → textSecondary
 * - Stat labels → textSecondary
 * - Stat values → textPrimary
 * - Dividers → textSecondary at 0.25 alpha
 * - Today highlight → surfaceVariant
 * - Day names → textSecondary
 * - Forecast highs → textPrimary
 * - Forecast lows → textSecondary
 * - Loading/error → textSecondary
 */
enum class WeatherRole {
    CARD_BACKGROUND,
    HEADER,
    TEMPERATURE,
    CONDITION,
    STAT_LABEL,
    STAT_VALUE,
    DIVIDER,
    TODAY_HIGHLIGHT,
    DAY_NAME,
    FORECAST_HIGH,
    FORECAST_LOW,
    LOADING_ERROR,
}

/** Divider alpha for the 0.25-alpha textSecondary dividers. */
const val WEATHER_DIVIDER_ALPHA = 0.25f

/**
 * Returns the palette color for a weather role. Dividers return the base
 * textSecondary (the 0.25 alpha is applied to the view, not the color).
 */
fun weatherRoleColor(palette: Palette, role: WeatherRole): Int = when (role) {
    WeatherRole.CARD_BACKGROUND -> palette.surface
    WeatherRole.HEADER -> palette.textSecondary
    WeatherRole.TEMPERATURE -> palette.textPrimary
    WeatherRole.CONDITION -> palette.textSecondary
    WeatherRole.STAT_LABEL -> palette.textSecondary
    WeatherRole.STAT_VALUE -> palette.textPrimary
    WeatherRole.DIVIDER -> palette.textSecondary
    WeatherRole.TODAY_HIGHLIGHT -> palette.surfaceVariant
    WeatherRole.DAY_NAME -> palette.textSecondary
    WeatherRole.FORECAST_HIGH -> palette.textPrimary
    WeatherRole.FORECAST_LOW -> palette.textSecondary
    WeatherRole.LOADING_ERROR -> palette.textSecondary
}
