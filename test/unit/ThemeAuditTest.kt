package com.lifedashboard.tv.test

import com.lifedashboard.tv.ui.theme.Palette
import com.lifedashboard.tv.ui.theme.THEMES
import com.lifedashboard.tv.ui.theme.ThemeAudit

/**
 * Rigorous tests for the theme-color audit (2026-09-29).
 *
 * The user's standing rule: a widget painted the wrong color never fails
 * the build — it has to be noticed by eye. ThemeAudit is the mechanical
 * backstop: after every theme application, MainActivity checks that every
 * widget text/background color resolves to the active palette (alpha
 * ignored) or an explicit allowlist entry. These tests pin the pure
 * checking logic, including the critical "no stale colors from the old
 * theme survive" property.
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

private fun saffronDay(): Palette = THEMES.first { it.id == "saffron" }.day
private fun orchardNight(): Palette = THEMES.first { it.id == "orchard" }.night

fun main() {
    val active = saffronDay()
    val activeRgb = ThemeAudit.paletteRgb(active)
    // Allowlist mirrors ThemeAuditView.allowedRgb (transparent) minus the
    // platform default text color, which needs Android.
    val allowed = activeRgb + setOf(0x000000)

    // --- paletteRgb ------------------------------------------------------
    run {
        val rgb = ThemeAudit.paletteRgb(active)
        val slots = listOf(
            active.background, active.surface, active.surfaceVariant,
            active.textPrimary, active.textSecondary,
            active.accent, active.accentSecondary,
            active.accountColorA, active.accountColorB,
        )
        check(
            "paletteRgb covers all 9 slots",
            slots.all { (it and 0x00FFFFFF) in rgb }
        )
        check("paletteRgb strips alpha", rgb.all { it in 0..0xFFFFFF })
        check(
            "paletteRgb contains textPrimary",
            (active.textPrimary and 0x00FFFFFF) in rgb
        )
    }

    // --- clean widget -----------------------------------------------------
    run {
        // Every weather role bound to the active palette: card, header,
        // temp, condition, stat labels/values, divider, today highlight,
        // day names, highs, lows, loading text.
        val colors = listOf(
            ThemeAudit.ColorUse("weather.card.bg", active.surface),
            ThemeAudit.ColorUse("weather.header.text", active.textSecondary),
            ThemeAudit.ColorUse("weather.temp.text", active.textPrimary),
            ThemeAudit.ColorUse("weather.condition.text", active.textSecondary),
            ThemeAudit.ColorUse("weather.stat.label", active.textSecondary),
            ThemeAudit.ColorUse("weather.stat.value", active.textPrimary),
            // Divider: textSecondary at 25% alpha — alpha must be ignored.
            ThemeAudit.ColorUse("weather.divider.bg", active.textSecondary and 0x40FFFFFF),
            ThemeAudit.ColorUse("weather.today.bg", active.surfaceVariant),
            ThemeAudit.ColorUse("weather.dayname.text", active.textSecondary),
            ThemeAudit.ColorUse("weather.high.text", active.textPrimary),
            ThemeAudit.ColorUse("weather.low.text", active.textSecondary),
            ThemeAudit.ColorUse("weather.loading.text", active.textSecondary),
        )
        val violations = ThemeAudit.check(colors, allowed)
        check("fully-themed widget has no violations", violations.isEmpty())
    }

    // --- stale theme color -------------------------------------------------
    run {
        // The 2026-09-28/29 bug class: a view still carrying the previous
        // theme's color after a theme change.
        val stale = orchardNight().background
        check("test premise: stale color differs from active palette", (stale and 0x00FFFFFF) !in activeRgb)
        val colors = listOf(
            ThemeAudit.ColorUse("weather.temp.text", active.textPrimary),
            ThemeAudit.ColorUse("weather.header.text", stale),
        )
        val violations = ThemeAudit.check(colors, allowed)
        check("stale color is flagged", violations.size == 1)
        check(
            "stale violation names the element",
            violations.firstOrNull()?.element == "weather.header.text"
        )
        check(
            "stale violation is diagnosed as a different theme's color",
            violations.firstOrNull()?.detail?.contains("different theme") == true
        )
    }

    // --- unknown hardcoded color -------------------------------------------
    run {
        val colors = listOf(
            ThemeAudit.ColorUse("weather.temp.text", 0xFF123456.toInt()),
        )
        val violations = ThemeAudit.check(colors, allowed)
        check("unknown hardcoded color is flagged", violations.size == 1)
        check(
            "unknown violation is not blamed on another theme",
            violations.firstOrNull()?.detail?.contains("not in the active palette") == true
        )
    }

    // --- allowlist ----------------------------------------------------------
    run {
        val colors = listOf(
            ThemeAudit.ColorUse("weather.container.bg", 0x00000000), // transparent
            ThemeAudit.ColorUse("weather.spacer.bg", 0x00000000),
        )
        check("transparent is allowlisted", ThemeAudit.check(colors, allowed).isEmpty())
    }

    // --- alpha handling -------------------------------------------------------
    run {
        // Same RGB at a different alpha must not count as a different color.
        val halfAlpha = (active.textPrimary and 0x00FFFFFF) or 0x80000000.toInt()
        val colors = listOf(ThemeAudit.ColorUse("weather.temp.text", halfAlpha))
        check("alpha is ignored in comparisons", ThemeAudit.check(colors, allowed).isEmpty())
    }

    // --- multiple violations ----------------------------------------------------
    run {
        val colors = listOf(
            ThemeAudit.ColorUse("weather.a.text", orchardNight().surface),
            ThemeAudit.ColorUse("weather.b.text", 0xFFABCDEF.toInt()),
            ThemeAudit.ColorUse("weather.c.text", active.textPrimary),
        )
        val violations = ThemeAudit.check(colors, allowed)
        check("all violations reported, none swallowed", violations.size == 2)
        check(
            "clean elements are not flagged",
            violations.none { it.element == "weather.c.text" }
        )
    }

    // --- hex helper ---------------------------------------------------------------
    run {
        check("hex formats argb", ThemeAudit.hex(0xFFAABBCC.toInt()) == "#ffaabbcc")
    }

    if (failures > 0) {
        println("$failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("All ThemeAudit tests passed.")
}
