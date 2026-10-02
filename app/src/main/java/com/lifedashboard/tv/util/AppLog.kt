package com.lifedashboard.tv.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-backed forensic ring log: `filesDir/app_log.txt`.
 *
 * The dashboard is a kiosk that is expected to run for days. When it is
 * found back on the Fire TV home screen, the interesting question is always
 * "what happened right before?" — and an uncaught-crash file alone cannot
 * answer it, because a system-initiated trip home (the "Are you still
 * watching?" prompt, a low-memory process kill, a power blip) writes no
 * crash file. This log records lifecycle transitions, window-focus changes,
 * memory-pressure callbacks, wake-lock state, and per-widget refresh
 * outcomes with timestamps, so the last minutes before an incident can be
 * reconstructed retroactively from Settings → Diagnostics.
 *
 * Bounded: the file is trimmed to the most recent [MAX_LINES] lines, so it
 * can never grow without bound on a device that runs for weeks. All writes
 * are best-effort and synchronized; logging must never crash the app.
 */
object AppLog {
    private const val FILE_NAME = "app_log.txt"
    private const val MAX_LINES = 2000
    private const val TRIM_EVERY_WRITES = 100
    private const val MAX_BYTES_BEFORE_TRIM = 256 * 1024L

    private val lock = Any()
    private var file: File? = null
    private var writesSinceTrim = 0

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Must be called once, early in MainActivity.onCreate, with the app context. */
    fun init(filesDir: File) {
        synchronized(lock) {
            if (file == null) file = File(filesDir, FILE_NAME)
        }
    }

    fun log(tag: String, message: String) {
        val f = synchronized(lock) { file } ?: return
        val line = "${dateFormat.format(Date())} [$tag] $message\n"
        try {
            synchronized(lock) {
                f.appendText(line)
                if (++writesSinceTrim >= TRIM_EVERY_WRITES) {
                    writesSinceTrim = 0
                    if (f.length() > MAX_BYTES_BEFORE_TRIM) trimLocked(f)
                }
            }
        } catch (_: Exception) {
            // Logging is best-effort; never let it break the dashboard.
        }
    }

    /** Keeps only the most recent [MAX_LINES] lines. Caller must hold [lock]. */
    private fun trimLocked(f: File) {
        try {
            val lines = f.readLines()
            if (lines.size > MAX_LINES) {
                f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    /** Tail of the log for the Diagnostics screen. Newest last. */
    fun readTail(maxChars: Int = 60_000): String {
        val f = synchronized(lock) { file } ?: return "Log not initialized."
        return try {
            val text = f.readText()
            if (text.length > maxChars) "…(truncated)…\n" + text.takeLast(maxChars) else text
        } catch (e: Exception) {
            "Could not read log: ${e.message}"
        }
    }

    fun clear() {
        val f = synchronized(lock) { file } ?: return
        try {
            synchronized(lock) { f.writeText("") }
        } catch (_: Exception) {
        }
    }
}
