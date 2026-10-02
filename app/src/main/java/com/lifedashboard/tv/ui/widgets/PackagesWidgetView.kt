package com.lifedashboard.tv.ui.widgets

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import com.lifedashboard.tv.data.ParcelRepo
import com.lifedashboard.tv.model.ParcelInfo
import com.lifedashboard.tv.ui.DashboardWidget
import com.lifedashboard.tv.ui.bodyView
import com.lifedashboard.tv.ui.cardDrawable
import com.lifedashboard.tv.ui.dotView
import com.lifedashboard.tv.ui.dp
import com.lifedashboard.tv.ui.labelView
import com.lifedashboard.tv.ui.mutedView
import com.lifedashboard.tv.ui.padDp
import com.lifedashboard.tv.ui.theme.Palette
import com.lifedashboard.tv.ui.theme.THEMES
import com.lifedashboard.tv.ui.themedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parcel Pending packages. Prompts for Settings login when no credentials
 * are saved; otherwise shows the pending count prominently plus compact
 * per-package rows (courier + locker box, pickup code + delivered date).
 * Only parcels that have not been picked up are counted or listed.
 */
class PackagesWidgetView(
    context: Context,
    private val repo: ParcelRepo,
    private val onOpenSettings: () -> Unit
) : DashboardWidget(context) {

    override val widgetId: String = "packages"
    override val spanWeight: Int = 4
    override val refreshIntervalMs: Long = 5 * 60 * 1000L

    private var palette: Palette = THEMES.first().day

    // Last rendered state, so applyTheme() can repaint instantly instead of
    // leaving stale rows until the next data refresh (2026-09-27: package
    // dots kept the previous theme's colors after a theme switch).
    private enum class PkgState { LOADING, LOGIN, LIST, ERROR }
    private var pkgState = PkgState.LOADING
    private var lastParcels: List<ParcelInfo> = emptyList()
    private var lastError: String? = null

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        padDp(22, 20, 22, 20)
    }

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyTheme(palette)
        content.addView(mutedView(context, palette, "Loading packages…", 15f))
    }

    override suspend fun refresh() {
        try {
            if (!repo.hasCredentials()) {
                withContext(Dispatchers.Main) {
                    pkgState = PkgState.LOGIN
                    showLoginPrompt()
                }
                return
            }
            val pending = repo.getParcels().filter { it.isPending }
            withContext(Dispatchers.Main) {
                pkgState = PkgState.LIST
                lastParcels = pending
                lastError = null
                showPackages(pending)
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c // never swallow coroutine cancellation
        } catch (t: Throwable) {
            // Parcel scraping must never crash the dashboard: network, HTML
            // parsing, and even JVM Errors (e.g. from a pathological page)
            // degrade to the error state instead of killing the process.
            // (2026-09-26: a real pending package crashed the app here and
            // left it in a launch-crash loop; belt, suspenders, and rope.)
            // The failure reason is shown so a bad login or outage can be
            // told apart from a bug — it used to be swallowed entirely.
            val detail = t.message?.trim()?.take(140)
            withContext(Dispatchers.Main) {
                pkgState = PkgState.ERROR
                lastError = detail
                showError(detail)
            }
        }
    }

    override fun applyTheme(p: Palette) {
        palette = p
        background = cardDrawable(context, p)
        // Repaint the current state immediately; the rows bake colors in at
        // build time, so without this the old theme's colors linger.
        when (pkgState) {
            PkgState.LOADING -> {
                content.removeAllViews()
                content.addView(mutedView(context, p, "Loading packages…", 15f))
            }
            PkgState.LOGIN -> showLoginPrompt()
            PkgState.LIST -> showPackages(lastParcels)
            PkgState.ERROR -> showError(lastError)
        }
    }

    override fun onDestroy() {}

    private fun setBody(vararg views: android.view.View) {
        content.removeAllViews()
        views.forEach { content.addView(it) }
    }

    private fun headerRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            labelView(context, palette, "PACKAGES", 13f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        return row
    }

    private fun showLoginPrompt() {
        val msg = bodyView(
            context, palette,
            "Enter your Parcel Pending login in Settings → Parcel Pending to see deliveries here.",
            15f
        ).apply { gravity = Gravity.CENTER }
        val btn = themedButton(context, palette, "Open Settings").apply {
            setOnClickListener { onOpenSettings() }
        }
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        wrap.addView(headerRow())
        val spacer = android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, context.dp(12))
        }
        wrap.addView(spacer)
        wrap.addView(msg)
        val btnRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, context.dp(16), 0, 0)
        }
        btnRow.addView(btn)
        wrap.addView(btnRow)
        setBody(wrap)
    }

    private fun showPackages(pending: List<ParcelInfo>) {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // Small "N waiting" note in the header row (like the calendar's
        // "N today") — the old 26sp count banner was far too loud.
        val header = headerRow()
        if (pending.isNotEmpty()) {
            header.addView(mutedView(context, palette, "${pending.size} waiting", 11f))
        }
        root.addView(header)

        if (pending.isEmpty()) {
            root.addView(mutedView(context, palette, "All clear — nothing to pick up.", 14f).apply {
                setPadding(0, context.dp(12), 0, 0)
            })
        } else {
            // Compact package cards, two max, then "+N more" — the card is
            // one grid row tall, so detail dumps must never run off it.
            root.addView(android.view.View(context).apply {
                layoutParams = LinearLayout.LayoutParams(1, context.dp(4))
            })
            pending.take(2).forEach { parcel ->
                root.addView(packageCard(parcel))
            }
            if (pending.size > 2) {
                root.addView(mutedView(context, palette, "+${pending.size - 2} more", 12f).apply {
                    setPadding(0, context.dp(4), 0, 0)
                })
            }
        }
        setBody(root)
    }

    /**
     * One package as a compact three-line card:
     *   Amazon Package                        — courier (first word)
     *   Code: 17975600                        — the kiosk access code
     *   Kiosk A · Angela · Sep 25, 6:04 PM    — kiosk + recipient (first name) + arrival
     * Every line is single-line with end-ellipsis so nothing runs off.
     */
    private fun packageCard(parcel: ParcelInfo): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(6), 0, context.dp(6))
        }
        val dotColor = when (parcel.recipient?.trim()?.split(" ")?.firstOrNull()) {
            "Angela" -> palette.accountColorB // other picker swatch (dark green on Orchard night)
            else -> palette.accent // "Michael" and unknown recipients use primary
        }
        row.addView(dotView(context, dotColor, 10).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = context.dp(10)
        })

        val textBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun addLine(text: String, sizeSp: Float, bold: Boolean, muted: Boolean) {
            val v = if (muted) mutedView(context, palette, text, sizeSp)
            else bodyView(context, palette, text, sizeSp, bold)
            v.setSingleLine(true)
            v.ellipsize = TextUtils.TruncateAt.END
            textBlock.addView(v)
        }

        // Line 1: "Amazon Package" — courier first word only, no box/size.
        val head = parcel.courier.takeIf { it.isNotBlank() }
            ?.let { "${it.trim().split(" ").first()} Package" }
            .orEmpty()
        if (head.isNotBlank()) addLine(head, 13f, bold = true, muted = false)

        // Line 2: "Code: 17975600" — the kiosk access code.
        val code = parcel.pickupCode.takeIf { it.isNotBlank() }
            ?: parcel.packageCode.takeIf { it.isNotBlank() }.orEmpty()
        if (code.isNotBlank()) addLine("Code: $code", 12f, bold = false, muted = false)

        // Line 3: "Kiosk A · Angela · Sep 25, 6:04 PM" — kiosk first,
        // then recipient first name only, then arrival time.
        val metaLine = listOfNotNull(
            parcel.kioskName.takeIf { it.isNotBlank() }?.let { "Kiosk $it" },
            parcel.recipient?.takeIf { it.isNotBlank() }?.let { it.trim().split(" ").first() },
            parcel.deliveredAt.takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { parcel.statusLabel }
        addLine(metaLine, 12f, bold = false, muted = true)

        row.addView(textBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun showError(detail: String? = null) {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(headerRow())
        root.addView(mutedView(context, palette, "Couldn't load packages.", 15f).apply {
            gravity = Gravity.CENTER
            setPadding(0, context.dp(24), 0, 0)
        })
        if (!detail.isNullOrBlank()) {
            root.addView(mutedView(context, palette, detail, 11f).apply {
                gravity = Gravity.CENTER
                setPadding(context.dp(8), context.dp(8), context.dp(8), 0)
            })
        }
        setBody(root)
    }
}
