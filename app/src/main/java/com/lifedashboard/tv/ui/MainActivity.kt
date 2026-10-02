package com.lifedashboard.tv.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifedashboard.tv.data.CalendarRepository
import com.lifedashboard.tv.data.NewsRepository
import com.lifedashboard.tv.data.ParcelRepository
import com.lifedashboard.tv.data.WeatherRepository
import com.lifedashboard.tv.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import com.lifedashboard.tv.ui.theme.Palette
import com.lifedashboard.tv.ui.theme.ThemeAudit
import com.lifedashboard.tv.ui.theme.ThemeManager
import com.lifedashboard.tv.ui.widgets.CalendarWidgetView
import com.lifedashboard.tv.ui.widgets.ClockWidgetView
import com.lifedashboard.tv.ui.widgets.NewsWidgetView
import com.lifedashboard.tv.ui.widgets.PackagesWidgetView
import com.lifedashboard.tv.ui.widgets.WeatherWidgetView
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single-activity kiosk. Fullscreen landscape, stays awake, and runs
 * entirely hands-free: the dashboard never requires the remote.
 *
 * Stay-awake strategy (three layers):
 * - FLAG_KEEP_SCREEN_ON on the window (standard Android screen timeout).
 * - A SCREEN_BRIGHT_WAKE_LOCK held for the activity's lifetime. This is the
 *   same class of lock video players hold while playing, and it is what
 *   keeps Fire OS's own ~20-minute device sleep timer from firing — the
 *   timer only sleeps the device when no wake lock is held, which is why
 *   the stick never sleeps mid-movie. No ADB, no silent-audio hacks, no
 *   audio-focus side effects.
 * - If Fire OS ever still sleeps (unverified on-device), the fallback is a
 *   one-time `adb shell settings put secure sleep_timeout 0`.
 *
 * - Creates all widget instances once; hidden widgets keep refreshing in
 *   the background so re-enabling one shows instantly. Visibility toggles
 *   only re-layout — they never trigger data fetches.
 * - Refresh loop ticks every minute; each widget refreshes on its own
 *   cadence (calendar 1 min, packages 5 min, others 10 min) + on every onResume.
 *   Per-widget in-flight guards: one slow widget never blocks the others.
 *   The clock ticks on its own; the news carousel auto-advances.
 * - Theme is re-evaluated every minute independently of refreshes (real
 *   sunrise/sunset feed paletteFor) and applied to every widget; failures
 *   are logged, never swallowed.
 * - Hosts the SettingsOverlay gear; settings changes re-layout or
 *   force-refresh as appropriate.
 * - refresh() failures never crash: widgets guard internally and every
 *   call here is additionally wrapped defensively.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var grid: LinearLayout
    private lateinit var settings: SettingsOverlay

    private lateinit var themeManager: ThemeManager
    private lateinit var weatherRepo: WeatherRepository
    private lateinit var calendarRepo: CalendarRepository
    private lateinit var parcelRepo: ParcelRepository
    private lateinit var newsRepo: NewsRepository

    private var widgets: List<DashboardWidget> = emptyList()
    private lateinit var palette: Palette
    private var refreshJob: Job? = null

    /**
     * True once [startDashboard] has run. The welcome and crash-rescue
     * screens return from onCreate early without starting the dashboard,
     * so onResume/onDestroy must not touch dashboard state until this is set.
     * (2026-09-25: without this guard, onResume → refreshAll → applyTheme
     * crashed on the welcome screen with UninitializedPropertyAccessException.)
     */
    private var dashboardStarted = false

    /**
     * Which full-screen framework screen is showing, if any: "welcome",
     * "rescue", "diagnostics", or null for the normal dashboard. Used by
     * onBackPressed() so the remote's Back button can leave diagnostics.
     */
    private var overlayMode: String? = null

    /** Held from onCreate to onDestroy: the kiosk must never sleep. */
    private var kioskWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLog.init(applicationContext.filesDir)
        val versionLine = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "v${pi.versionName} (build ${pi.versionCode})"
        }.getOrDefault("unknown version")
        AppLog.log(
            "app",
            "process start $versionLine " +
                "(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "API ${android.os.Build.VERSION.SDK_INT})"
        )
        installCrashTrap()
        super.onCreate(savedInstanceState)
        // If the previous launch crashed, show the rescue screen instead of
        // the dashboard so the crash loop can be broken and the log reported.
        readLastCrash()?.let { showCrashRescue(it); return }
        // First launch after install: one-time welcome + diagnostics entry.
        if (isFirstRun()) { showWelcome(); return }
        startDashboard()
    }

    /**
     * Normal dashboard startup, extracted from onCreate so the crash-rescue
     * and first-run welcome screens can return early without running it.
     */
    private fun startDashboard() {
        overlayMode = null
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        acquireKioskWakeLock()

        themeManager = ThemeManager(this)
        weatherRepo = WeatherRepository(this)
        calendarRepo = CalendarRepository(this)
        parcelRepo = ParcelRepository(this)
        newsRepo = NewsRepository(this)

        root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(grid)
        setContentView(root)

        palette = currentPalette()
        root.setBackgroundColor(palette.background)

        settings = SettingsOverlay(this) { change -> onSettingsChanged(change) }

        ensureWidgets()
        applyTheme()
        relayoutWidgets()
        // Memory-pressure telemetry for the forensic log: if the system is
        // squeezing the process (or about to kill it), it shows up here.
        // registerComponentCallbacks is activity-scoped, so no manual
        // unregister is needed.
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() {
                AppLog.log("memory", "onLowMemory")
            }
            override fun onTrimMemory(level: Int) {
                AppLog.log("memory", "onTrimMemory level=$level")
            }
        })
        refreshAll()
        startRefreshLoop()
        dashboardStarted = true
        AppLog.log("app", "dashboard started, wakeLockHeld=${kioskWakeLock?.isHeld == true}")
    }

    override fun onResume() {
        super.onResume()
        AppLog.log("lifecycle", "onResume")
        if (dashboardStarted) {
            // The device may have slept past sunrise/sunset: re-check the
            // theme independently of the refresh loop.
            runCatching { maybeApplyTheme() }
            refreshAll()
        }
    }

    override fun onStart() {
        super.onStart()
        AppLog.log("lifecycle", "onStart")
    }

    override fun onPause() {
        AppLog.log("lifecycle", "onPause")
        super.onPause()
    }

    override fun onStop() {
        AppLog.log("lifecycle", "onStop")
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A system prompt (e.g. Fire OS "Are you still watching?") stealing
        // focus shows up here as hasFocus=false before onPause/onStop.
        AppLog.log("lifecycle", "onWindowFocusChanged hasFocus=$hasFocus")
    }

    /**
     * The remote's Back button leaves the diagnostics screen (returning to
     * the dashboard, or to welcome on a first run). Other overlays keep the
     * default behavior.
     */
    override fun onBackPressed() {
        if (overlayMode == "diagnostics") {
            overlayMode = null
            recreate()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        AppLog.log("lifecycle", "onDestroy isFinishing=$isFinishing")
        refreshJob?.cancel()
        widgets.forEach { runCatching { it.onDestroy() } }
        releaseKioskWakeLock()
        super.onDestroy()
    }

    // ---- launch-crash diagnostics ------------------------------------------

    /**
     * Catches any uncaught throwable, saves the stack trace to a file, then
     * lets the process die normally. On the next launch [readLastCrash]
     * finds it and [showCrashRescue] displays the rescue screen.
     *
     * The handler is process-global (static): it captures only the
     * application-scoped files dir, never this Activity, so a dead activity
     * can never be pinned by it.
     */
    private fun installCrashTrap() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val appFilesDir = applicationContext.filesDir
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val header = "Kitchen Dashboard v1.1 crashed\n" +
                    "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                    "(API ${android.os.Build.VERSION.SDK_INT})\n\n" +
                    "${throwable::class.java.name}: ${throwable.message}\n"
                val trace = android.util.Log.getStackTraceString(throwable)
                java.io.File(appFilesDir, "last_crash.txt").writeText(header + trace)
                AppLog.log("crash", "${throwable::class.java.name}: ${throwable.message}")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun readLastCrash(): String? = runCatching {
        java.io.File(filesDir, "last_crash.txt").takeIf { it.exists() }?.readText()
    }.getOrNull()

    private fun clearLastCrash() {
        runCatching { java.io.File(filesDir, "last_crash.txt").delete() }
    }

    /**
     * Rescue screen shown instead of the dashboard when the previous launch
     * crashed. Shows the saved stack trace plus actions that break a crash
     * loop without wiping the whole app: retry, or drop just the Parcel
     * Pending login (the 2026-09-26 crash was parcel-related) and restart.
     *
     * Built from plain framework views only — no repos, no themes, no
     * dashboard code — so it can never crash the way the dashboard did.
     */
    private fun showCrashRescue(trace: String) {
        overlayMode = "rescue"
        val ctx = this
        val pad = { v: android.view.View, h: Int, vPad: Int ->
            v.setPadding(h, vPad, h, vPad); v
        }
        val title = android.widget.TextView(ctx).apply {
            text = "Kitchen Dashboard ran into a problem"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
        }
        val subtitle = android.widget.TextView(ctx).apply {
            text = "The last launch crashed. The error log is below — " +
                "you can copy it, or reset the Parcel Pending login and try again."
            textSize = 16f
            setTextColor(android.graphics.Color.LTGRAY)
        }
        val logView = android.widget.TextView(ctx).apply {
            setText(trace)
            textSize = 13f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(android.graphics.Color.WHITE)
            // Not selectable: a selectable TextView grabs D-pad focus and
            // traps the remote (the Copy button covers copying). The log
            // must never be focusable or the buttons become unreachable.
            isFocusable = false
        }
        val logScroll = android.widget.ScrollView(ctx).apply {
            addView(pad(logView, 24, 24))
            setBackgroundColor(android.graphics.Color.parseColor("#1a1a1a"))
        }
        fun rescueButton(label: String, onTap: () -> Unit): android.widget.Button =
            android.widget.Button(ctx).apply {
                text = label
                textSize = 16f
                setOnClickListener { onTap() }
            }
        val copyBtn = rescueButton("Copy error log") {
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Kitchen Dashboard crash log", trace))
                subtitle.text = "Error log copied to clipboard."
            }
        }
        val parcelResetBtn = rescueButton("Reset Parcel Pending login & restart") {
            runCatching { ParcelRepository(ctx).clearCredentials() }
            clearLastCrash()
            markFirstRunDone()
            recreate()
        }
        val retryBtn = rescueButton("Dismiss & try again") {
            clearLastCrash()
            markFirstRunDone()
            recreate()
        }
        val buttons = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(copyBtn)
            addView(parcelResetBtn)
            addView(retryBtn)
        }
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            setPadding(48, 48, 48, 48)
            addView(title)
            addView(pad(subtitle, 0, 16))
            addView(logScroll, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(pad(buttons, 0, 24))
        }
        setContentView(root)
        // Give the remote a starting focus point (posted: after layout).
        copyBtn.post { copyBtn.requestFocus() }
    }

    // ---- first-run welcome ----------------------------------------------------

    private fun prefs(): android.content.SharedPreferences =
        getSharedPreferences("lifedashboard_ui", Context.MODE_PRIVATE)

    private fun isFirstRun(): Boolean =
        runCatching { !prefs().getBoolean("first_run_done", false) }.getOrDefault(true)

    private fun markFirstRunDone() {
        runCatching { prefs().edit().putBoolean("first_run_done", true).apply() }
    }

    /**
     * One-time welcome shown on the very first launch after install.
     * Non-intrusive (never shown again) and doubles as a stable entry point:
     * it runs no dashboard code, so it always opens even if the dashboard
     * itself is crash-looping. The Diagnostics button opens the debug screen.
     */
    private fun showWelcome() {
        overlayMode = "welcome"
        val ctx = this
        val title = android.widget.TextView(ctx).apply {
            text = "Kitchen Dashboard"
            textSize = 40f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }
        val subtitle = android.widget.TextView(ctx).apply {
            text = "Your kiosk display is ready.\n\n" +
                "Open Settings (gear) any time to link calendars,\n" +
                "log in to Parcel Pending, and pick a theme."
            textSize = 18f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = android.view.Gravity.CENTER
        }
        val goBtn = android.widget.Button(ctx).apply {
            text = "Continue to dashboard"
            textSize = 18f
            setOnClickListener {
                markFirstRunDone()
                recreate()
            }
        }
        val diagBtn = android.widget.Button(ctx).apply {
            text = "Diagnostics"
            textSize = 14f
            setOnClickListener { showDiagnostics() }
        }
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
            setPadding(64, 64, 64, 64)
            addView(title)
            addView(subtitle.apply { setPadding(0, 32, 0, 48) })
            addView(goBtn)
            addView(diagBtn.apply { setPadding(0, 24, 0, 0) })
        }
        setContentView(root)
        goBtn.post { goBtn.requestFocus() }
    }

    /**
     * Standalone diagnostics screen: app version, device info, and the last
     * saved crash log (if any) with copy/clear actions. Reachable from the
     * welcome screen and from Settings → Diagnostics. Framework views only.
     */
    fun showDiagnostics() {
        overlayMode = "diagnostics"
        val ctx = this
        val versionLine = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "v${pi.versionName} (build ${pi.versionCode})"
        }.getOrDefault("unknown version")
        val info = android.widget.TextView(ctx).apply {
            text = "Kitchen Dashboard $versionLine\n" +
                "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "(API ${android.os.Build.VERSION.SDK_INT})"
            textSize = 16f
            setTextColor(android.graphics.Color.LTGRAY)
        }
        val crash = readLastCrash()
        val parcelDump = runCatching {
            java.io.File(filesDir, "parcel_debug.txt").takeIf { it.exists() }?.readText()
        }.getOrNull()
        val fullLog = buildString {
            append(crash ?: "No crash recorded.")
            append("\n\n--- Runtime event log (newest last) ---\n")
            append(AppLog.readTail())
            if (parcelDump != null) {
                append("\n\n--- Parcel table dump ---\n")
                append(parcelDump)
            }
        }
        val logView = android.widget.TextView(ctx).apply {
            setText(fullLog)
            textSize = 13f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(android.graphics.Color.WHITE)
            // Not selectable: a selectable TextView grabs D-pad focus and
            // traps the remote so the buttons below become unreachable.
            isFocusable = false
        }
        val logScroll = android.widget.ScrollView(ctx).apply {
            addView(logView.apply { setPadding(24, 24, 24, 24) })
            setBackgroundColor(android.graphics.Color.parseColor("#1a1a1a"))
        }
        fun smallBtn(label: String, onTap: () -> Unit): android.widget.Button =
            android.widget.Button(ctx).apply {
                text = label
                textSize = 14f
                setOnClickListener { onTap() }
            }
        val copyBtn = smallBtn("Copy log") {
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Kitchen Dashboard diagnostics", "Kitchen Dashboard $versionLine\n\n$fullLog"))
            }
        }
        val clearBtn = smallBtn("Clear log") {
            clearLastCrash()
            runCatching { java.io.File(filesDir, "parcel_debug.txt").delete() }
            AppLog.clear()
            AppLog.log("app", "diagnostics logs cleared by user")
            logView.text = "Logs cleared."
        }
        // On-demand theme audit (2026-09-29): re-checks every widget color
        // against the active palette without changing themes. The result is
        // appended to the forensic log and shown below.
        val auditBtn = smallBtn("Audit theme colors") {
            runCatching { auditWidgetColors() }
                .onFailure { AppLog.log("theme", "manual audit error: ${it.message}") }
            logView.text = buildString {
                append(crash ?: "No crash recorded.")
                append("\n\n--- Runtime event log (newest last) ---\n")
                append(AppLog.readTail())
            }
            logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        val backBtn = smallBtn("Back") {
            overlayMode = null
            recreate()
        }
        val buttons = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(copyBtn); addView(clearBtn); addView(auditBtn); addView(backBtn)
        }
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            setPadding(48, 48, 48, 48)
            addView(info.apply { setPadding(0, 0, 0, 24) })
            addView(logScroll, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons.apply { setPadding(0, 24, 0, 0) })
        }
        setContentView(root)
        // Give the remote a starting focus point (posted: after layout).
        backBtn.post { backBtn.requestFocus() }
    }

    // ---- stay-awake -----------------------------------------------------------

    /**
     * Holds a bright wake lock for the activity's lifetime. Deprecated API,
     * but it is exactly what kiosk/"stay alive" apps use on Fire OS, and it
     * works on our API range (23–25). The lock is non-reference-counted and
     * released in onDestroy; acquisition/edge cases are swallowed so a
     * failure here can never crash the dashboard.
     */
    @Suppress("DEPRECATION")
    private fun acquireKioskWakeLock() {
        kioskWakeLock = runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "KitchenDashboard::KioskAwake"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        AppLog.log("wakelock", "acquire isHeld=${kioskWakeLock?.isHeld == true}")
    }

    private fun releaseKioskWakeLock() {
        val wasHeld = kioskWakeLock?.isHeld == true
        runCatching {
            kioskWakeLock?.let { if (it.isHeld) it.release() }
        }
        kioskWakeLock = null
        AppLog.log("wakelock", "release wasHeld=$wasHeld")
    }

    // ---- grid ---------------------------------------------------------------

    private fun widgetVisible(id: String): Boolean =
        SettingsOverlay.isWidgetVisible(
            getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE), id
        )

    /**
     * Widget instances are created ONCE and kept alive for the activity's
     * lifetime — including widgets the user has hidden. Their data keeps
     * refreshing on the normal cadence in the background, so re-enabling a
     * widget renders instantly from already-fresh data instead of showing a
     * loading screen. (2026-09-28: recreating instances on every visibility
     * toggle stranded the new instances in "Loading…" whenever the refresh
     * single-flight guard skipped the forced refresh pass.)
     */
    private fun ensureWidgets() {
        if (widgets.isNotEmpty()) return
        // Canonical order: clock, weather, calendar, packages, news.
        widgets = listOf(
            ClockWidgetView(this),
            WeatherWidgetView(this, weatherRepo),
            CalendarWidgetView(this, calendarRepo) { settings.open() },
            PackagesWidgetView(this, parcelRepo) { settings.open() },
            NewsWidgetView(this, newsRepo)
        )
    }

    /**
     * Lay out only the visible widgets. Visibility changes never touch data:
     * toggling one widget must not trigger fetches in any other widget.
     */
    private fun relayoutWidgets() {
        runCatching { layoutWidgets(grid, widgets.filter { widgetVisible(it.widgetId) }) }
    }

    // ---- theme ----------------------------------------------------------------

    private fun currentPalette(): Palette {
        val now = LocalTime.now()
        val nowMinutes = now.hour * 60 + now.minute
        val weather = widgets.filterIsInstance<WeatherWidgetView>().firstOrNull()?.lastData
        val sunriseMinutes = weather?.sunrise?.let { it.hour * 60 + it.minute }
        val sunsetMinutes = weather?.sunset?.let { it.hour * 60 + it.minute }
        return themeManager.paletteFor(nowMinutes, sunriseMinutes, sunsetMinutes)
    }

    /** The palette last applied to the widgets; null until the first apply. */
    private var lastAppliedPalette: Palette? = null

    private fun applyTheme() {
        palette = currentPalette()
        lastAppliedPalette = palette
        root.setBackgroundColor(palette.background)
        widgets.forEach { w ->
            // A widget left on a stale palette is exactly the 2026-09-28
            // stuck-dark-highlight bug: never swallow a failure silently.
            runCatching { w.applyTheme(palette) }
                .onFailure {
                    AppLog.log(
                        "theme",
                        "${w.widgetId} applyTheme FAILED: " +
                            "${it::class.java.simpleName}: ${it.message}"
                    )
                }
        }
        runCatching { settings.applyTheme(palette) }
            .onFailure { AppLog.log("theme", "settings applyTheme FAILED: ${it.message}") }
        // Theme audit (2026-09-29): every widget color must resolve to the
        // active palette. Log-only — a widget left on a stale palette is
        // exactly the class of bug this catches, and the audit itself must
        // never break theme application.
        runCatching { auditWidgetColors() }
            .onFailure { AppLog.log("theme", "audit error: ${it.message}") }
    }

    /**
     * Walks every widget's view hierarchy and checks that all text and
     * background colors fall within the just-applied palette (alpha ignored)
     * or the explicit allowlist. Violations go to the forensic log, where
     * Settings → Diagnostics surfaces them.
     */
    private fun auditWidgetColors() {
        val allowed = ThemeAuditView.allowedRgb(this, palette)
        var checked = 0
        val violations = widgets.flatMap { w ->
            val colors = ThemeAuditView.collectColors(w, w.widgetId)
            checked += colors.size
            ThemeAudit.check(colors, allowed)
        }
        if (violations.isEmpty()) {
            AppLog.log("theme", "audit: $checked widget colors all within-theme")
        } else {
            AppLog.log(
                "theme",
                "AUDIT FAIL: ${violations.size}/$checked colors outside active palette: " +
                    violations.joinToString("; ") { v ->
                        "${v.element}=${ThemeAudit.hex(v.argb)} (${v.detail})"
                    }
            )
        }
    }

    /**
     * Re-apply the theme only when the resolved palette actually changed
     * (day/night transition or daily rotation). Called every loop tick and
     * on resume — deliberately NOT coupled to refresh passes, so a slow or
     * skipped refresh can never delay the night→day transition again.
     */
    private fun maybeApplyTheme() {
        if (currentPalette() != lastAppliedPalette) {
            AppLog.log("theme", "palette changed, re-applying (day/night transition)")
            applyTheme()
        }
    }

    // ---- refresh --------------------------------------------------------------

    private fun refreshAll() {
        refreshDueWidgets(widgets.toList())
    }

    private val lastRefreshMs = mutableMapOf<String, Long>()

    /**
     * Per-widget in-flight guards (2026-09-28). The old single global guard
     * let one slow widget (e.g. a hanging calendar fetch) block every other
     * widget's cadence — and silently drop forced refreshes, stranding
     * widgets in "Loading…". Now a slow widget only delays itself; the rest
     * of the dashboard keeps refreshing around it.
     */
    private val inFlightIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun refreshDueWidgets(snapshot: List<DashboardWidget>) {
        val now = System.currentTimeMillis()
        var launched = 0
        // Snapshot: visibility toggles only re-layout, so the list is
        // stable, but copy anyway for safety.
        snapshot.forEach { w ->
            if (!isRefreshDue(lastRefreshMs[w.widgetId] ?: 0L, now, w.refreshIntervalMs)) return@forEach
            if (!inFlightIds.add(w.widgetId)) {
                AppLog.log("refresh", "${w.widgetId} skip: previous refresh still running")
                return@forEach
            }
            lastRefreshMs[w.widgetId] = now
            launched++
            lifecycleScope.launch(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                try {
                    w.refresh()
                    AppLog.log("refresh", "${w.widgetId} ok ${System.currentTimeMillis() - t0}ms")
                } catch (e: Exception) {
                    // Widgets must never throw from refresh(); belt and
                    // suspenders. Failures are now recorded in the
                    // forensic log instead of being silently swallowed.
                    AppLog.log(
                        "refresh",
                        "${w.widgetId} ERROR ${e::class.java.simpleName}: ${e.message}"
                    )
                } finally {
                    inFlightIds.remove(w.widgetId)
                }
            }
        }
        if (launched > 0) AppLog.log("refresh", "pass launched=$launched mem=${memorySnapshot()}")
    }

    /** Compact memory line for the forensic log: Java heap + system-wide. */
    private fun memorySnapshot(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val maxMb = rt.maxMemory() / 1048576
        val availMb = runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.availMem / 1048576
        }.getOrDefault(-1L)
        return "heap=${usedMb}MB/${maxMb}MB sysAvail=${availMb}MB"
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                delay(LOOP_TICK_MS)
                tick++
                // Heartbeat every 15 minutes: proves the process was alive
                // and records the memory trend even when no widget was due.
                if (tick % 15 == 0) {
                    AppLog.log("heartbeat", "tick=$tick mem=${memorySnapshot()}")
                }
                // Theme transitions ride their own check now, never blocked by refreshes.
                runCatching { maybeApplyTheme() }
                refreshDueWidgets(widgets.toList())
            }
        }
    }

    // ---- settings -------------------------------------------------------------

    // Fire TV remote hamburger (menu) button opens settings.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            settings.open()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun onSettingsChanged(change: SettingsChange) {
        when (change) {
            // Theme switch: re-theme in place. No grid rebuild, no data refresh.
            SettingsChange.THEME -> applyTheme()
            // Widget visibility: re-layout only. Instances (and their data)
            // persist, so toggling one widget never triggers data fetches in
            // any other widget, and a re-enabled widget shows instantly.
            SettingsChange.WIDGETS -> relayoutWidgets()
            // Calendar credentials changed: reload calendar data.
            SettingsChange.CALENDAR_AUTH -> forceRefresh("calendar")
            // Parcel credentials changed: reload package data.
            SettingsChange.PARCEL_AUTH -> forceRefresh("packages")
            // Manual "Refresh widget data" button in Settings.
            SettingsChange.REFRESH_DATA -> {
                AppLog.log("refresh", "manual refresh requested from Settings")
                forceRefreshAll()
            }
        }
    }

    /** Refresh specific widgets immediately, bypassing their cadence. */
    private fun forceRefresh(vararg ids: String) {
        val targets = widgets.filter { it.widgetId in ids }
        targets.forEach { lastRefreshMs.remove(it.widgetId) }
        refreshDueWidgets(targets)
    }

    private fun forceRefreshAll() {
        lastRefreshMs.clear()
        refreshDueWidgets(widgets.toList())
    }

    companion object {
        /** Loop ticks every minute; each widget refreshes on its own cadence. */
        private const val LOOP_TICK_MS = 60 * 1000L
    }
}
