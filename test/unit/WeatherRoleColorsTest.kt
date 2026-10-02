package com.lifedashboard.tv.test

import com.lifedashboard.tv.ui.theme.Palette
import com.lifedashboard.tv.ui.theme.WEATHER_DIVIDER_ALPHA
import com.lifedashboard.tv.ui.theme.WeatherRole
import com.lifedashboard.tv.ui.theme.weatherRoleColor

/**
 * Pins the weather widget's color-role mapping (2026-09-29).
 *
 * Context: the user reported that switching Periwinkle → Saffron left the
 * WEATHER header, day highlight, and non-white text in the old theme's
 * colors. Root cause was a reparenting crash in WeatherWidgetView.bind()
 * (fixed separately) — but the deeper risk is a role silently pointing at
 * the wrong palette color, which no build step catches. This test pins the
 * user's standing spec: every role must resolve to its specified palette
 * field, for two distinct palettes (so a test can't pass by coincidence
 * when two palette fields share a value).
 *
 * The widget's bind() delegates to weatherRoleColor(), so pinning the
 * mapping pins the binding.
 *
 * Run: test/unit/run_tests.sh (plain kotlinc + JVM, no Android needed).
 */
private var failures = 0

private fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name")
    else {
        println("FAIL: $name")
        failures++
    }
}

/** Two palettes with all-distinct fields, so cross-role mixups are caught. */
private fun paletteA() = Palette(
    background = 0xFF000001.toInt(),
    surface = 0xFF000002.toInt(),
    surfaceVariant = 0xFF000003.toInt(),
    textPrimary = 0xFF000004.toInt(),
    textSecondary = 0xFF000005.toInt(),
    accent = 0xFF000006.toInt(),
    accentSecondary = 0xFF000007.toInt(),
    accountColorA = 0xFF000008.toInt(),
    accountColorB = 0xFF000009.toInt(),
)

private fun paletteB() = Palette(
    background = 0xFF100001.toInt(),
    surface = 0xFF100002.toInt(),
    surfaceVariant = 0xFF100003.toInt(),
    textPrimary = 0xFF100004.toInt(),
    textSecondary = 0xFF100005.toInt(),
    accent = 0xFF100006.toInt(),
    accentSecondary = 0xFF100007.toInt(),
    accountColorA = 0xFF100008.toInt(),
    accountColorB = 0xFF100009.toInt(),
)

private fun expectRole(
    palette: Palette,
    label: String,
    role: WeatherRole,
    expected: Int,
) {
    val actual = weatherRoleColor(palette, role)
    check(
        "$label: $role -> expected palette field",
        actual == expected,
    )
}

fun main() {
    for ((label, p) in listOf("paletteA" to paletteA(), "paletteB" to paletteB())) {
        expectRole(p, label, WeatherRole.CARD_BACKGROUND, p.surface)
        expectRole(p, label, WeatherRole.HEADER, p.textSecondary)
        expectRole(p, label, WeatherRole.TEMPERATURE, p.textPrimary)
        expectRole(p, label, WeatherRole.CONDITION, p.textSecondary)
        expectRole(p, label, WeatherRole.STAT_LABEL, p.textSecondary)
        expectRole(p, label, WeatherRole.STAT_VALUE, p.textPrimary)
        expectRole(p, label, WeatherRole.DIVIDER, p.textSecondary)
        expectRole(p, label, WeatherRole.TODAY_HIGHLIGHT, p.surfaceVariant)
        expectRole(p, label, WeatherRole.DAY_NAME, p.textSecondary)
        expectRole(p, label, WeatherRole.FORECAST_HIGH, p.textPrimary)
        expectRole(p, label, WeatherRole.FORECAST_LOW, p.textSecondary)
        expectRole(p, label, WeatherRole.LOADING_ERROR, p.textSecondary)
    }

    // The mapping must cover every declared role (no silent gaps).
    val roles = WeatherRole.values()
    check("all ${roles.size} roles mapped", roles.size == 12)
    for (role in roles) {
        // Must not throw for any role on either palette.
        weatherRoleColor(paletteA(), role)
        weatherRoleColor(paletteB(), role)
    }
    check("every role resolves on both palettes", true)

    // Divider alpha is part of the spec.
    check("divider alpha is 0.25", WEATHER_DIVIDER_ALPHA == 0.25f)

    // Switching palettes must change every role's resolved color (no stale
    // colors retained): the Periwinkle→Saffron regression in miniature.
    val a = paletteA()
    val b = paletteB()
    val allChange = WeatherRole.values().all { role ->
        weatherRoleColor(a, role) != weatherRoleColor(b, role)
    }
    check("no role retains its color across a palette switch", allChange)

    if (failures > 0) {
        println("$failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("All WeatherRoleColors tests passed.")
}
