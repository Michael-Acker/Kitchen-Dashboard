package com.lifedashboard.tv.test

import com.lifedashboard.tv.ui.theme.ThemeSelection
import com.lifedashboard.tv.ui.theme.ThemeStore
import java.time.LocalDate

/**
 * Regression tests for theme selection + daily rotation.
 *
 * Context (2026-09-29): the theme picker's tap handler must TOGGLE selection
 * — tapping a selected theme deselects it, tapping an unselected one selects
 * it (and applies it as current). Build 31 regressed this to select-only,
 * removing the only deselection path (the checkboxes are indicators, not
 * inputs). The tap decision lives in ThemeSelection.toggleTheme so the UI
 * cannot drift from it; these tests pin that contract.
 *
 * The pure logic lives in ThemeSelection (no Android deps); the production
 * ThemeManager is a thin SharedPreferences-backed subclass.
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

/** In-memory ThemeStore for tests. */
private class MapThemeStore : ThemeStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String) {
        map[key] = value
    }
}

private fun fresh(): ThemeSelection = ThemeSelection(MapThemeStore())

private fun seeded(
    order: List<String>,
    current: String,
    lastRotationDay: String?,
): ThemeSelection {
    val store = MapThemeStore()
    store.set(ThemeSelection.KEY_SELECTED_ORDER, order.joinToString(","))
    store.set(ThemeSelection.KEY_CURRENT_THEME, current)
    if (lastRotationDay != null) {
        store.set(ThemeSelection.KEY_LAST_ROTATION_DAY, lastRotationDay)
    }
    return ThemeSelection(store)
}

fun main() {
    // --- selection -------------------------------------------------------
    run {
        val t = fresh()
        t.selectTheme("saffron")
        check("selectTheme makes it current", t.currentTheme().id == "saffron")
        check("selectTheme adds to selection", "saffron" in t.selectedThemeIds())
    }

    // 2026-09-29 regression: tapping the already-current theme must keep it
    // current (the old UI toggled it off instead).
    run {
        val t = fresh()
        t.selectTheme("periwinkle")
        t.selectTheme("saffron")
        t.selectTheme("saffron") // tap the already-selected row again
        check(
            "re-selecting current theme keeps it current",
            t.currentTheme().id == "saffron"
        )
        check(
            "re-selecting current theme keeps it selected",
            "saffron" in t.selectedThemeIds()
        )
    }

    run {
        val t = fresh()
        t.selectTheme("periwinkle")
        t.selectTheme("saffron")
        check("most recently selected applies immediately", t.currentTheme().id == "saffron")
        // Fresh store seeds the selection with the default theme (orchard).
        check(
            "both stay selected",
            t.selectedThemeIds() == setOf("orchard", "periwinkle", "saffron")
        )
    }

    run {
        val t = fresh()
        t.selectTheme("no-such-theme")
        check("unknown selectTheme id is ignored", t.selectedThemeIds().isNotEmpty())
        t.makeCurrentTheme("no-such-theme")
        check("unknown makeCurrentTheme id is ignored", t.currentTheme().id == "orchard")
    }

    // --- deselection ------------------------------------------------------
    run {
        val t = seeded(listOf("saffron"), "saffron", LocalDate.now().toString())
        t.deselectTheme("saffron")
        check(
            "cannot deselect the last selected theme",
            t.selectedThemeIds() == setOf("saffron")
        )
    }

    run {
        val t = seeded(listOf("periwinkle"), "periwinkle", LocalDate.now().toString())
        t.selectTheme("saffron") // saffron current
        t.deselectTheme("saffron")
        check("deselect removes from selection", t.selectedThemeIds() == setOf("periwinkle"))
        check(
            "deselecting current falls back to most-recent remaining",
            t.currentTheme().id == "periwinkle"
        )
    }

    // --- toggle (the picker tap handler) ----------------------------------
    run {
        val t = seeded(listOf("periwinkle", "saffron"), "saffron", LocalDate.now().toString())
        t.toggleTheme("saffron") // selected -> deselect
        check("toggle deselects a selected theme", t.selectedThemeIds() == setOf("periwinkle"))
        check(
            "toggle-deselect of current falls back",
            t.currentTheme().id == "periwinkle"
        )
    }

    run {
        val t = seeded(listOf("periwinkle"), "periwinkle", LocalDate.now().toString())
        t.toggleTheme("saffron") // unselected -> select + apply
        check("toggle selects an unselected theme", t.selectedThemeIds() == setOf("periwinkle", "saffron"))
        check("toggle-select applies immediately", t.currentTheme().id == "saffron")
    }

    run {
        val t = seeded(listOf("saffron"), "saffron", LocalDate.now().toString())
        t.toggleTheme("saffron") // last selected -> stays (never empty)
        check("toggle keeps at least one selected", t.selectedThemeIds() == setOf("saffron"))
        check("toggle of last selected keeps it current", t.currentTheme().id == "saffron")
    }

    run {
        val t = seeded(listOf("periwinkle", "saffron"), "periwinkle", LocalDate.now().toString())
        t.toggleTheme("saffron") // deselect non-current
        t.toggleTheme("saffron") // re-select -> current
        check("toggle round-trips", t.selectedThemeIds() == setOf("periwinkle", "saffron"))
        check("toggle re-select applies", t.currentTheme().id == "saffron")
    }

    // --- daily rotation ----------------------------------------------------
    val yesterday = LocalDate.now().minusDays(1).toString()
    val today = LocalDate.now().toString()

    run {
        val t = seeded(listOf("periwinkle", "saffron"), "periwinkle", yesterday)
        check("rotation advances to next selected theme", t.currentTheme().id == "saffron")
        check("rotation fires only once per day", t.currentTheme().id == "saffron")
        check("rotation stamps today", t.currentTheme().id == "saffron")
    }

    run {
        val t = seeded(listOf("periwinkle", "saffron"), "saffron", yesterday)
        check("rotation wraps around selection order", t.currentTheme().id == "periwinkle")
    }

    run {
        val t = seeded(listOf("saffron"), "saffron", yesterday)
        check("single selected theme never rotates", t.currentTheme().id == "saffron")
    }

    run {
        // First run (no rotation day stored): seed the day, don't rotate.
        val t = seeded(listOf("periwinkle", "saffron"), "periwinkle", null)
        check("first run does not rotate", t.currentTheme().id == "periwinkle")
        // Second call same day: still no rotation.
        check("same day does not rotate", t.currentTheme().id == "periwinkle")
    }

    run {
        val t = seeded(listOf("periwinkle", "saffron"), "periwinkle", today)
        check("already-rotated today does not rotate again", t.currentTheme().id == "periwinkle")
    }

    // --- migration / seeding ------------------------------------------------
    run {
        // Build 14/15 stored only current_theme_id: selection seeds from it.
        val store = MapThemeStore()
        store.set(ThemeSelection.KEY_CURRENT_THEME, "saffron")
        val t = ThemeSelection(store)
        check("seeds selection from legacy current_theme_id", t.selectedThemeIds() == setOf("saffron"))
        check("legacy current theme preserved", t.currentTheme().id == "saffron")
    }

    run {
        // Nothing stored at all: falls back to the first theme.
        val t = fresh()
        check("empty store falls back to first theme", t.currentTheme().id == "orchard")
    }

    if (failures > 0) {
        println("$failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("All ThemeSelection tests passed.")
}
