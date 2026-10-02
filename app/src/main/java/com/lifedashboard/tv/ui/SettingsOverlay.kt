package com.lifedashboard.tv.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifedashboard.tv.data.CalendarRepo
import com.lifedashboard.tv.data.CalendarRepository
import com.lifedashboard.tv.data.ParcelRepo
import com.lifedashboard.tv.data.ParcelRepository
import com.lifedashboard.tv.data.UpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.lifedashboard.tv.ui.theme.Palette
import com.lifedashboard.tv.ui.theme.THEMES
import com.lifedashboard.tv.ui.theme.ThemeManager
import com.lifedashboard.tv.ui.theme.ThemeProvider

/**
 * D-pad-navigable settings. Opened via the Fire TV menu button; shows a
 * full-screen dim overlay with a menu: Themes, Widgets, Google Calendar,
 * Parcel Pending, App updates.
 *
 * Every interactive element is focusable with a visible focus state.
 * onChanged() fires after any change so the host can rebuild/re-theme.
 * The ChangeKind tells the host what actually changed so it can avoid
 * needless work (e.g. a theme switch needs no data refresh).
 */
enum class SettingsChange { THEME, WIDGETS, CALENDAR_AUTH, PARCEL_AUTH, REFRESH_DATA }

class SettingsOverlay(
    private val activity: AppCompatActivity,
    private val onChanged: (SettingsChange) -> Unit
) {
    private val ctx: Context = activity
    private val themeProvider: ThemeProvider = ThemeManager(activity)
    private val calendarRepo: CalendarRepo = CalendarRepository(activity)
    private val parcelRepo: ParcelRepo = ParcelRepository(activity)
    private val updateManager = UpdateManager(activity)
    private var updateJob: Job? = null
    private val prefs = activity.getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)

    private var palette: Palette = THEMES.first().day

    private val overlay = FrameLayout(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        visibility = View.GONE
    }

    private val panelCard = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        padDp(24, 20, 24, 20)
    }

    private var currentView = "root"
    private var firstFocusable: View? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (overlay.visibility != View.VISIBLE) return
            if (currentView != "root") showView("root") else close()
        }
    }

    init {
        val layer = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlay.setBackgroundColor(0x99000000.toInt())
        val panelLp = FrameLayout.LayoutParams(
            ctx.dp(620),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        overlay.addView(panelCard, panelLp)
        layer.addView(overlay)

        (activity.findViewById<ViewGroup>(android.R.id.content)).addView(layer)
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)

        applyTheme(palette)
    }

    fun open() {
        showView("root")
        overlay.visibility = View.VISIBLE
        backCallback.isEnabled = true
    }

    fun close() {
        overlay.visibility = View.GONE
        backCallback.isEnabled = false
    }

    fun applyTheme(p: Palette) {
        palette = p
        panelCard.background = cardDrawable(ctx, p, 20)
        if (overlay.visibility == View.VISIBLE) {
            // Perfectly smooth re-theme (2026-09-29): snapshot the panel's
            // current pixels BEFORE tearing down, rebuild underneath, then
            // dissolve the snapshot. The user never sees a blank panel, a
            // half-built list, or a scroll jump — just one palette dissolving
            // into the next.
            val snapshot = snapshotView(panelCard)
            val snapLeft = panelCard.left
            val snapTop = panelCard.top
            // Rebuilding drops D-pad focus (the theme list used to jump to the
            // top on every tap) and creates a fresh ScrollView snapped to the
            // top (which used to yank a scrolled-down list back to the top).
            // Preserve both — but as ONE posted unit, scroll first, then
            // focus. (2026-09-29: the synchronous scrollTo in build 31 was
            // silently reset by the layout pass, so the separately-posted
            // focus restore then scrolled the tapped row to the bottom edge.
            // Posting scroll-then-focus together after layout is
            // deterministic: the row is already visible when focus lands, so
            // the ScrollView never auto-scrolls.)
            val focusTag = overlay.findFocus()?.tag as? String
            val savedScrollY = findScrollView(panelCard)?.scrollY ?: 0
            showView(currentView)
            if (focusTag != null || savedScrollY != 0) {
                panelCard.post {
                    if (savedScrollY != 0) {
                        findScrollView(panelCard)?.scrollTo(0, savedScrollY)
                    }
                    if (focusTag != null) {
                        panelCard.findViewWithTag<View>(focusTag)?.requestFocus()
                    }
                }
            }
            if (snapshot != null) crossfadeSnapshot(snapshot, snapLeft, snapTop)
        }
    }

    /** Renders [v]'s current pixels to a bitmap, or null if it has no size. */
    private fun snapshotView(v: View): android.graphics.Bitmap? {
        if (v.width <= 0 || v.height <= 0) return null
        return runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(
                v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888
            )
            v.draw(android.graphics.Canvas(bmp))
            bmp
        }.getOrNull()
    }

    /**
     * Pins a pre-rebuild snapshot exactly over the panel and fades it out,
     * revealing the rebuilt content underneath. Any in-flight fade from a
     * rapid second tap is dropped first.
     */
    private fun crossfadeSnapshot(
        bmp: android.graphics.Bitmap, left: Int, top: Int
    ) {
        overlay.findViewWithTag<View>("theme_fade")?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }
        val iv = android.widget.ImageView(ctx).apply {
            tag = "theme_fade"
            setImageBitmap(bmp)
        }
        overlay.addView(iv, FrameLayout.LayoutParams(bmp.width, bmp.height).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = top
        })
        iv.animate().alpha(0f).setDuration(220).withEndAction {
            (iv.parent as? ViewGroup)?.removeView(iv)
            bmp.recycle()
        }.start()
    }

    /** Finds the first ScrollView under [root], if any. */
    private fun findScrollView(root: ViewGroup): ScrollView? {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is ScrollView) return c
            if (c is ViewGroup) findScrollView(c)?.let { return it }
        }
        return null
    }

    // ---- view plumbing ------------------------------------------------------

    private fun showView(name: String) {
        currentView = name
        firstFocusable = null
        updateJob?.cancel()
        updateJob = null
        panelCard.removeAllViews()
        val v: View = when (name) {
            "themes" -> themesView()
            "widgets" -> widgetsView()
            "calendar" -> calendarView()
            "parcel" -> parcelView()
            "updates" -> updatesView()
            "diagnostics" -> {
                // Full-screen diagnostics replaces the overlay; Back recreates.
                (activity as? MainActivity)?.showDiagnostics()
                rootView()
            }
            else -> rootView()
        }
        panelCard.addView(v)
        // Focus lands on the first focusable view. (The re-theme path in
        // applyTheme() restores focus + scroll itself, posted as one unit.)
        firstFocusable?.requestFocus()
    }

    /**
     * Panel header: optional Back button, title + subtitle, Close button.
     * [body] goes below the header. The first focusable view added (via
     * noteFirstFocusable or the back button) gets focus when shown.
     */
    private fun chrome(title: String, subtitle: String, showBack: Boolean, body: View): LinearLayout {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (showBack) {
            val back = themedButton(ctx, palette, "‹ Back").apply {
                setOnClickListener { showView("root") }
            }
            head.addView(back)
            noteFirstFocusable(back)
        }
        val titleBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (showBack) ctx.dp(14) else 0, 0, 0, 0)
        }
        titleBlock.addView(bodyView(ctx, palette, title, 20f, bold = true))
        if (subtitle.isNotEmpty()) titleBlock.addView(mutedView(ctx, palette, subtitle, 12f))
        head.addView(titleBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = themedButton(ctx, palette, "✕").apply {
            setOnClickListener { close() }
        }
        head.addView(close)
        if (!showBack) noteFirstFocusable(close)
        col.addView(head)
        col.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, ctx.dp(14)) })
        col.addView(body)
        return col
    }

    private fun noteFirstFocusable(v: View) {
        if (firstFocusable == null) firstFocusable = v
    }

    private fun rowGap(): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, ctx.dp(8))
    }

    /** Focusable menu row with title + subtitle + optional meta + chevron. */
    private fun menuRow(title: String, subtitle: String, meta: String = ""): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            padDp(16, 14, 16, 14)
            makeFocusable(palette, palette.surfaceVariant)
        }
        val textBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        textBlock.addView(bodyView(ctx, palette, title, 16f, bold = true))
        textBlock.addView(mutedView(ctx, palette, subtitle, 12f))
        row.addView(textBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (meta.isNotEmpty()) {
            row.addView(mutedView(ctx, palette, meta, 12f).apply {
                setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            })
        }
        row.addView(bodyView(ctx, palette, "›", 22f).apply { setTextColor(palette.accent) })
        return row
    }

    // ---- root menu ----------------------------------------------------------

    private fun rootView(): LinearLayout {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val entries = listOf(
            MenuEntry("themes", "Themes", "Multi-select, rotates daily",
                themeProvider.currentTheme().name),
            MenuEntry("widgets", "Widgets", "Choose what appears",
                "${WIDGET_DEFS.count { widgetVisible(it.id) }} on"),
            MenuEntry("calendar", "Google Calendar", "OAuth credentials + account linking",
                if (calendarRepo.googleConfigured()) "configured" else "not set"),
            MenuEntry("parcel", "Parcel Pending", "Delivery login",
                if (parcelRepo.hasCredentials()) "saved" else "not set"),
            MenuEntry("updates", "App updates", "Install new versions in-app",
                "v${updateManager.currentVersionName()}"),
            MenuEntry("diagnostics", "Diagnostics", "Version info + last crash log",
                "open"),
            MenuEntry("refresh_data", "Refresh widget data", "Fetch fresh data for all widgets now",
                "run")
        )
        entries.forEachIndexed { i, e ->
            val row = menuRow(e.title, e.subtitle, e.meta)
            row.setOnClickListener {
                // The refresh row is a fire-and-forget action, not a submenu:
                // trigger it and close settings so the dashboard is visible.
                if (e.key == "refresh_data") {
                    onChanged(SettingsChange.REFRESH_DATA)
                    close()
                } else showView(e.key)
            }
            if (i == 0) noteFirstFocusable(row)
            list.addView(row)
            if (i < entries.size - 1) list.addView(rowGap())
        }
        // The menu outgrew one screen (7 rows incl. Refresh widget data):
        // scroll it like the theme list so every row stays reachable.
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(440))
        }
        scroll.addView(list)
        return chrome("Settings", "Adjust this dashboard.", showBack = false, body = scroll)
    }

    private data class MenuEntry(val key: String, val title: String, val subtitle: String, val meta: String)

    // ---- themes -------------------------------------------------------------

    private fun themesView(): LinearLayout {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val selectedIds = themeProvider.selectedThemeIds()
        val currentId = themeProvider.currentTheme().id
        themeProvider.allThemes().forEachIndexed { i, theme ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                padDp(14, 10, 14, 10)
                makeFocusable(palette, palette.surfaceVariant)
                tag = "theme:${theme.id}"
            }
            // Selection checkbox: indicator only (the row handles D-pad input).
            row.addView(android.widget.CheckBox(ctx).apply {
                isFocusable = false
                isClickable = false
                isChecked = theme.id in selectedIds
                setPadding(0, 0, ctx.dp(10), 0)
            })
            val nameBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val nameRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            nameRow.addView(bodyView(ctx, palette, theme.name, 15f, bold = true))
            if (theme.id == currentId) {
                nameRow.addView(mutedView(ctx, palette, "  · current", 12f).apply {
                    setTextColor(palette.accent)
                })
            }
            nameBlock.addView(nameRow)
            val swatches = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ctx.dp(4), 0, 0)
            }
            swatches.addView(dotView(ctx, theme.day.accent, 14))
            swatches.addView(dotView(ctx, theme.night.accent, 14).apply {
                (layoutParams as LinearLayout.LayoutParams).leftMargin = ctx.dp(6)
            })
            nameBlock.addView(swatches)
            row.addView(nameBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (i == 0) noteFirstFocusable(row)
            row.setOnClickListener {
                // Tap toggles selection (2026-09-28: restored after build 31
                // made every tap a select, which removed the only way to
                // deselect — the checkboxes are indicators, not inputs).
                // Selecting also applies the theme immediately as current;
                // deselecting the current theme falls back to the most
                // recently selected remaining one (ThemeSelection keeps at
                // least one theme selected).
                themeProvider.toggleTheme(theme.id)
                onChanged(SettingsChange.THEME)
            }
            list.addView(row)
        }
        // 13 themes no longer fit on one screen: scroll the list (D-pad arrows
        // scroll once focus reaches the visible edge).
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(440))
        }
        scroll.addView(list)
        return chrome("Themes", "Tap to select — the newest pick applies now. Selections rotate daily.", showBack = true, body = scroll)
    }

    // ---- widgets ------------------------------------------------------------

    private fun widgetsView(): LinearLayout {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        WIDGET_DEFS.forEachIndexed { i, def ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                padDp(14, 10, 14, 10)
                makeFocusable(palette, palette.surfaceVariant)
            }
            val textBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            textBlock.addView(bodyView(ctx, palette, def.name, 15f, bold = true))
            textBlock.addView(mutedView(ctx, palette, def.desc, 12f))
            row.addView(textBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val toggle = Switch(ctx).apply {
                isChecked = widgetVisible(def.id)
                isFocusable = false
                isClickable = false
            }
            row.addView(toggle)
            if (i == 0) noteFirstFocusable(row)
            row.setOnClickListener {
                val next = !widgetVisible(def.id)
                prefs.edit().putBoolean(widgetKey(def.id), next).apply()
                toggle.isChecked = next
                onChanged(SettingsChange.WIDGETS)
            }
            list.addView(row)
        }
        return chrome("Widgets", "Choose what appears on the dashboard.", showBack = true, body = list)
    }

    // ---- google calendar ----------------------------------------------------

    private fun calendarView(): LinearLayout {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        list.addView(labelView(ctx, palette, "OAuth client ID", 13f))
        val clientIdField = settingsField("xxxx.apps.googleusercontent.com").apply {
            setText(calendarRepo.getClientId() ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        list.addView(clientIdField)
        list.addView(rowGap())
        list.addView(labelView(ctx, palette, "OAuth client secret", 13f))
        val clientSecretField = settingsField("From Google Cloud → Credentials → your TV client").apply {
            setText(calendarRepo.getClientSecret() ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        list.addView(clientSecretField)
        list.addView(mutedView(
            ctx, palette,
            "Google's token endpoint requires the secret for TV clients — sign-in fails without it.",
            12f
        ).apply { setPadding(0, ctx.dp(6), 0, 0) })
        val statusView = mutedView(
            ctx, palette,
            if (calendarRepo.googleConfigured()) "Client ID + secret saved." else "No credentials saved yet.",
            12f
        ).apply { setPadding(0, ctx.dp(6), 0, 0) }
        list.addView(statusView)
        val save = themedButton(ctx, palette, "Save").apply {
            setOnClickListener {
                val id = clientIdField.text.toString().trim()
                val secret = clientSecretField.text.toString().trim()
                if (id.isNotEmpty() && secret.isNotEmpty()) {
                    calendarRepo.saveClientId(id)
                    calendarRepo.saveClientSecret(secret)
                    onChanged(SettingsChange.CALENDAR_AUTH)
                    showView("calendar")
                } else {
                    statusView.text = "Enter both the client ID and the client secret."
                }
            }
        }
        list.addView(LinearLayout(ctx).apply {
            setPadding(0, ctx.dp(10), 0, 0)
            addView(save)
        })
        list.addView(rowGap())
        list.addView(rowGap())

        for (slot in 1..2) {
            val linked = runCatching { calendarRepo.isLinked(slot) }.getOrDefault(false)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                padDp(4, 8, 4, 8)
            }
            row.addView(dotView(ctx, if (slot == 1) palette.accountColorA else palette.accountColorB, 14))
            val label = bodyView(
                ctx, palette,
                "  Account $slot — " + if (linked) (calendarRepo.accountName(slot) ?: "linked") else "not linked",
                15f, bold = true
            )
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (linked) {
                row.addView(themedButton(ctx, palette, "Unlink").apply {
                    setOnClickListener {
                        calendarRepo.unlink(slot)
                        onChanged(SettingsChange.CALENDAR_AUTH)
                        showView("calendar")
                    }
                })
            } else {
                row.addView(themedButton(ctx, palette, "Sign in").apply {
                    isEnabled = calendarRepo.googleConfigured()
                    alpha = if (isEnabled) 1f else 0.4f
                    setOnClickListener {
                        showDeviceFlowDialog(ctx, palette, calendarRepo, slot) {
                            onChanged(SettingsChange.CALENDAR_AUTH)
                            showView("calendar")
                        }
                    }
                })
            }
            list.addView(row)
        }
        if (!calendarRepo.googleConfigured()) {
            list.addView(mutedView(ctx, palette, "Save a client ID and secret above to enable Sign in.", 12f).apply {
                setPadding(0, ctx.dp(8), 0, 0)
            })
        }
        return chrome("Google Calendar", "Link up to two Google accounts.", showBack = true, body = list)
    }

    // ---- app updates ----------------------------------------------------------

    private fun updatesView(): LinearLayout {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        list.addView(bodyView(
            ctx, palette,
            "Installed: v${updateManager.currentVersionName()} " +
                "(${updateManager.currentVersionCode()})",
            15f, bold = true
        ))
        list.addView(rowGap())
        list.addView(mutedView(
            ctx, palette,
            "Type your Downloader code below (or a direct APK link). The app " +
                "resolves the code, downloads the build, and installs it over " +
                "this app — your accounts and settings are kept.",
            12f
        ))
        list.addView(rowGap())

        list.addView(labelView(ctx, palette, "Downloader code or APK link", 13f))
        val urlField = settingsField("e.g. 3923958").apply {
            setText(updateManager.getUpdateSource() ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        list.addView(urlField)
        val statusView = mutedView(ctx, palette, "", 13f).apply {
            setPadding(0, ctx.dp(8), 0, 0)
        }

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ctx.dp(10), 0, 0)
        }
        val save = themedButton(ctx, palette, "Save").apply {
            setOnClickListener {
                val source = urlField.text.toString().trim()
                if (source.isNotEmpty()) {
                    updateManager.saveUpdateSource(source)
                    statusView.text = "Saved."
                } else {
                    statusView.text = "Enter your Downloader code first."
                }
            }
        }
        val check = themedButton(ctx, palette, "Check for updates")
        var installButton: View? = null
        check.setOnClickListener {
            updateJob?.cancel()
            installButton?.let { list.removeView(it) }
            installButton = null
            statusView.text = "Checking…"
            updateJob = activity.lifecycleScope.launch {
                val result = updateManager.checkForUpdate { read, total ->
                    val pct = if (total > 0) (read * 100 / total).toInt() else -1
                    activity.runOnUiThread {
                        statusView.text = if (pct >= 0) "Downloading… $pct%" else "Downloading…"
                    }
                }
                when (result) {
                    is UpdateManager.UpdateResult.UpToDate ->
                        statusView.text = "You're on the latest version."
                    is UpdateManager.UpdateResult.Available -> {
                        statusView.text =
                            "Version ${result.remoteVersionCode} downloaded."
                        installButton = themedButton(ctx, palette, "Install update").apply {
                            setOnClickListener {
                                updateManager.installApk(result.apkFile)
                            }
                        }.also { list.addView(it) }
                    }
                    is UpdateManager.UpdateResult.Failed ->
                        statusView.text = result.message
                }
            }
        }
        buttonRow.addView(save)
        val gap = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(12), 1)
        }
        buttonRow.addView(gap)
        buttonRow.addView(check)
        list.addView(buttonRow)
        list.addView(statusView)
        return chrome("App updates", "Update without reinstalling.", showBack = true, body = list)
    }

    // ---- parcel pending -----------------------------------------------------

    private fun parcelView(): LinearLayout {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val statusView = mutedView(
            ctx, palette,
            if (parcelRepo.hasCredentials()) "Login saved." else "No login saved.",
            13f
        )
        list.addView(statusView)
        list.addView(rowGap())

        list.addView(labelView(ctx, palette, "Username", 13f))
        val userField = settingsField("Username")
        // Show the saved login so it's clear the credentials are stored —
        // blank fields made it look like saving wiped them.
        userField.setText(parcelRepo.savedUsername() ?: "")
        list.addView(userField)
        list.addView(rowGap())
        list.addView(labelView(ctx, palette, "Password", 13f))
        val passField = settingsField("Password").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        list.addView(passField)
        list.addView(rowGap())

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnRow.addView(themedButton(ctx, palette, "Save").apply {
            setOnClickListener {
                val u = userField.text.toString().trim()
                val pw = passField.text.toString()
                if (u.isNotEmpty() && pw.isNotEmpty()) {
                    parcelRepo.saveCredentials(u, pw)
                    statusView.text = "Login saved."
                    // Keep the username visible as proof it's stored; only the
                    // password is cleared (never keep it on screen).
                    userField.setText(u)
                    passField.setText("")
                    onChanged(SettingsChange.PARCEL_AUTH)
                } else {
                    statusView.text = "Enter both username and password."
                }
            }
        })
        val testWrap = LinearLayout(ctx).apply { setPadding(ctx.dp(10), 0, 0, 0) }
        testWrap.addView(themedButton(ctx, palette, "Test login").apply {
            setOnClickListener {
                statusView.text = "Testing login…"
                isEnabled = false
                updateJob?.cancel()
                updateJob = activity.lifecycleScope.launch {
                    val result = parcelRepo.testLogin()
                    statusView.text = result
                    isEnabled = true
                    updateJob = null
                }
            }
        })
        btnRow.addView(testWrap)
        val dumpWrap = LinearLayout(ctx).apply { setPadding(ctx.dp(10), 0, 0, 0) }
        dumpWrap.addView(themedButton(ctx, palette, "Dump table").apply {
            setOnClickListener {
                statusView.text = "Dumping table…"
                isEnabled = false
                updateJob?.cancel()
                updateJob = activity.lifecycleScope.launch {
                    // Structural dump of the live parcel-history table for
                    // diagnosing parsing mismatches; saved where the
                    // Diagnostics screen can show it.
                    val dump = parcelRepo.debugTableDump()
                    runCatching {
                        activity.openFileOutput("parcel_debug.txt", android.content.Context.MODE_PRIVATE)
                            .use { it.write(dump.toByteArray()) }
                    }
                    statusView.text = "Table dumped — see Settings → Diagnostics."
                    isEnabled = true
                    updateJob = null
                }
            }
        })
        btnRow.addView(dumpWrap)
        val clearWrap = LinearLayout(ctx).apply { setPadding(ctx.dp(10), 0, 0, 0) }
        clearWrap.addView(themedButton(ctx, palette, "Clear").apply {
            setOnClickListener {
                parcelRepo.clearCredentials()
                userField.setText("")
                passField.setText("")
                statusView.text = "Login cleared."
                onChanged(SettingsChange.PARCEL_AUTH)
            }
        })
        btnRow.addView(clearWrap)
        list.addView(btnRow)

        return chrome("Parcel Pending", "Read-only delivery lookup.", showBack = true, body = list)
    }

    private fun settingsField(hint: String): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(palette.textSecondary)
        setTextColor(palette.textPrimary)
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = focusableBackground(ctx, palette, palette.surfaceVariant)
        padDp(12, 10, 12, 10)
    }

    // ---- widget visibility prefs --------------------------------------------

    private fun widgetKey(id: String) = "widget_visible_$id"

    private fun widgetVisible(id: String): Boolean =
        prefs.getBoolean(widgetKey(id), WIDGET_DEFS.first { it.id == id }.defaultOn)

    companion object {
        data class WidgetDef(val id: String, val name: String, val desc: String, val defaultOn: Boolean)

        val WIDGET_DEFS = listOf(
            WidgetDef("clock", "Clock", "Time and date", true),
            WidgetDef("weather", "Weather", "Current conditions + 7-day forecast", true),
            WidgetDef("calendar", "Calendar", "Google Calendar agenda", true),
            WidgetDef("packages", "Packages", "Parcel Pending deliveries", true),
            WidgetDef("news", "News", "Headline carousel", false)
        )

        fun isWidgetVisible(prefs: SharedPreferences, id: String): Boolean {
            val def = WIDGET_DEFS.firstOrNull { it.id == id } ?: return false
            return prefs.getBoolean("widget_visible_${id}", def.defaultOn)
        }
    }
}
