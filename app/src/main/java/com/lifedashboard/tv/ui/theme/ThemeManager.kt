package com.lifedashboard.tv.ui.theme

import android.content.Context

/**
 * Production ThemeProvider: [ThemeSelection] logic backed by
 * SharedPreferences. The pure logic lives in ThemeSelection (unit-tested on
 * the JVM); this class is just the Android entry point.
 */
class ThemeManager(context: Context) : ThemeSelection(SharedPrefsThemeStore(context)) {
    companion object {
        /** SharedPreferences file; also holds widget visibility + update prefs. */
        const val PREFS_NAME = ThemeSelection.PREFS_NAME
    }
}

/** Production [ThemeStore] backed by SharedPreferences. */
private class SharedPrefsThemeStore(context: Context) : ThemeStore {
    private val sp =
        context.getSharedPreferences(ThemeSelection.PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(key: String): String? = sp.getString(key, null)
    override fun set(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }
}
