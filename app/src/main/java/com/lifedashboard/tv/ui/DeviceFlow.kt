package com.lifedashboard.tv.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.lifedashboard.tv.data.CalendarRepo
import com.lifedashboard.tv.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared Google TV device-authorization flow dialog, used by both the
 * calendar widget (unlinked slots) and Settings → Google Calendar.
 *
 * Shows the big user_code and verification_url, polls repo.pollForToken on
 * Dispatchers.IO, then invokes onLinked() on success. D-pad friendly:
 * the dialog is dismissible via its Cancel button.
 */
fun showDeviceFlowDialog(
    context: Context,
    palette: Palette,
    repo: CalendarRepo,
    slot: Int,
    onLinked: () -> Unit
) {
    val body = verticalStack(context).apply {
        padDp(28, 24, 28, 24)
        background = cardDrawable(context, palette, 16)
        minimumWidth = context.dp(420)
    }

    val title = bodyView(context, palette, "Link Google account $slot", 20f, bold = true).apply {
        gravity = Gravity.CENTER
    }
    val codeView = TextView(context).apply {
        text = "···"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        setTextColor(palette.textPrimary)
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        gravity = Gravity.CENTER
        letterSpacing = 0.12f
    }
    val urlView = TextView(context).apply {
        text = ""
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(palette.accent)
        gravity = Gravity.CENTER
    }
    val hint = mutedView(
        context, palette,
        "On your phone or computer, open the address above and enter the code.",
        13f
    ).apply { gravity = Gravity.CENTER }
    val statusView = bodyView(context, palette, "Starting…", 14f).apply {
        gravity = Gravity.CENTER
        setTextColor(palette.textSecondary)
    }
    listOf(title, codeView, urlView, hint, statusView).forEachIndexed { i, v ->
        if (i > 0) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = context.dp(14)
            v.layoutParams = lp
        }
        body.addView(v)
    }

    val dialog = AlertDialog.Builder(context)
        .setView(body)
        .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
        .setCancelable(true)
        .create()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    dialog.setOnDismissListener { scope.cancel() }

    scope.launch {
        val session = try {
            withContext(Dispatchers.IO) { repo.beginDeviceFlow(slot) }
        } catch (e: Exception) {
            statusView.text = "Couldn't start sign-in: ${e.message ?: "error"}"
            return@launch
        }
        codeView.text = session.userCode
        urlView.text = session.verificationUrl
        statusView.text = "Waiting for approval…"
        val result = try {
            val ok = withContext(Dispatchers.IO) {
                repo.pollForToken(slot, session.deviceCode, session.intervalSec)
            }
            if (ok) "OK" else "Not approved in time. Press Cancel and try again."
        } catch (e: Exception) {
            "Error: ${e.message ?: "unknown error"}"
        }
        if (result == "OK") {
            dialog.dismiss()
            onLinked()
        } else {
            statusView.text = result
        }
    }

    dialog.show()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
}
