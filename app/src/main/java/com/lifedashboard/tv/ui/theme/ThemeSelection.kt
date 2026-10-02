package com.lifedashboard.tv.ui.theme

import java.time.LocalDate

/**
 * Minimal string key-value store for theme state. SharedPreferences-backed
 * in production; a plain map in unit tests (2026-09-29: this seam exists so
 * selection/rotation logic is testable on the JVM without Android).
 */
interface ThemeStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/**
 * Pure (no Android dependencies) theme selection + daily-rotation logic.
 * Backed by a [ThemeStore]; unit-tested on the JVM.
 *
 * Design (the long-standing intended behavior):
 * - Multi-select: any number of themes can be selected (checkboxes).
 * - The most-recently-selected theme applies immediately as current.
 * - Daily rotation: on the first palette resolution of a new day, the
 *   current theme advances to the next selected theme (selection order,
 *   wrapping). A single selected theme never rotates.
 * - The theme list keeps its fixed order; tapping never reorders it.
 *
 * Storage:
 * - "selected_themes_ordered": comma-separated theme ids,
 *   most-recently-selected last.
 * - "current_theme_id": the currently applied theme.
 * - "last_rotation_day": yyyy-MM-dd of the last rotation check.
 *
 * Migration: builds 14/15 stored only "current_theme_id" (single theme).
 * On first run the selection is seeded from it, so the current theme is
 * preserved and rotation simply does nothing until more themes are
 * selected. Pre-14 ordered selections are read as-is (same format).
 */
open class ThemeSelection(private val prefs: ThemeStore) : ThemeProvider {

    override fun allThemes(): List<AppTheme> = THEMES

    /**
     * The selected themes in selection order (most-recently-selected last).
     * Seeds from the current theme when no selection was ever stored.
     */
    private fun selectedOrder(): MutableList<String> {
        val known = THEMES.map { it.id }.toSet()
        val stored = prefs.get(KEY_SELECTED_ORDER)
            ?.split(",")?.map { it.trim() }?.filter { it in known }
            ?.toMutableList()
        if (!stored.isNullOrEmpty()) return stored
        // Seed: keep whatever is current (build 14/15 single-theme storage,
        // or the pre-14 ordered selection's last entry via currentTheme()).
        val seed = prefs.get(KEY_CURRENT_THEME)?.takeIf { it in known }
            ?: THEMES.first().id
        val list = mutableListOf(seed)
        prefs.set(KEY_SELECTED_ORDER, list.joinToString(","))
        return list
    }

    private fun saveOrder(order: List<String>) {
        prefs.set(KEY_SELECTED_ORDER, order.joinToString(","))
    }

    override fun selectedThemeIds(): Set<String> = selectedOrder().toSet()

    override fun setSelectedThemeIds(ids: Set<String>) {
        val known = THEMES.map { it.id }
        val ordered = known.filter { it in ids }
        if (ordered.isEmpty()) return
        saveOrder(ordered)
        // Keep the current theme inside the new selection.
        if (prefs.get(KEY_CURRENT_THEME) !in ordered) {
            prefs.set(KEY_CURRENT_THEME, ordered.last())
        }
    }

    override fun selectTheme(id: String) {
        if (THEMES.none { it.id == id }) return
        val order = selectedOrder()
        order.remove(id)
        order.add(id) // most-recently-selected last
        saveOrder(order)
        makeCurrentTheme(id)
    }

    override fun deselectTheme(id: String) {
        val order = selectedOrder()
        if (order.size <= 1 || id !in order) return // always keep at least one
        order.remove(id)
        saveOrder(order)
        if (prefs.get(KEY_CURRENT_THEME) == id) {
            // Fall back to the most-recently-selected remaining theme.
            prefs.set(KEY_CURRENT_THEME, order.last())
        }
    }

    /**
     * The theme-picker tap decision (2026-09-29): tapping a selected theme
     * deselects it, tapping an unselected one selects it (which also makes
     * it current). The checkboxes in the picker are indicators, not inputs,
     * so this is the only deselection path — it must not degenerate into
     * select-only (the build 31 regression).
     */
    override fun toggleTheme(id: String) {
        if (id in selectedThemeIds()) deselectTheme(id) else selectTheme(id)
    }

    override fun currentTheme(): AppTheme {
        rotateIfNewDay()
        val known = THEMES.map { it.id }.toSet()
        var id = prefs.get(KEY_CURRENT_THEME)
        if (id !in known) {
            id = selectedOrder().lastOrNull() ?: THEMES.first().id
            prefs.set(KEY_CURRENT_THEME, id)
        }
        return THEMES.first { it.id == id }
    }

    override fun makeCurrentTheme(id: String) {
        if (THEMES.none { it.id == id }) return
        prefs.set(KEY_CURRENT_THEME, id)
    }

    /**
     * Daily rotation: on the first call of a new calendar day, advance the
     * current theme to the next selected theme in selection order, wrapping
     * around. Called from [currentTheme], so it runs on every palette
     * resolution (app start, resume, periodic refresh) — the day check makes
     * it a no-op except once per day.
     */
    private fun rotateIfNewDay() {
        val today = LocalDate.now().toString()
        val last = prefs.get(KEY_LAST_ROTATION_DAY)
        if (last == today) return
        prefs.set(KEY_LAST_ROTATION_DAY, today)
        if (last == null) return // first run: seed the day, don't rotate yet
        val order = selectedOrder()
        if (order.size < 2) return
        val current = prefs.get(KEY_CURRENT_THEME)
        val next = order[(order.indexOf(current) + 1) % order.size]
        prefs.set(KEY_CURRENT_THEME, next)
    }

    override fun paletteFor(nowMinutes: Int, sunriseMinutes: Int?, sunsetMinutes: Int?): Palette {
        val theme = currentTheme()
        return if (isNightMinutes(nowMinutes, sunriseMinutes, sunsetMinutes)) theme.night else theme.day
    }

    companion object {
        const val PREFS_NAME = "lifedashboard_prefs"
        /** The currently applied theme id. */
        const val KEY_CURRENT_THEME = "current_theme_id"
        /** Comma-separated selected theme ids, most-recently-selected last. */
        const val KEY_SELECTED_ORDER = "selected_themes_ordered"
        /** yyyy-MM-dd of the last daily-rotation check. */
        const val KEY_LAST_ROTATION_DAY = "last_rotation_day"
        /** Legacy unordered set; no longer written. */
        const val KEY_SELECTED_THEMES = "selected_themes"
    }
}
