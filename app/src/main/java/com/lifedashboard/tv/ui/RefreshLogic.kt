package com.lifedashboard.tv.ui

/**
 * Pure refresh-cadence check used by MainActivity's refresh loop.
 *
 * Kept free of Android dependencies so it can be covered by a plain JVM
 * unit test (test/unit/RefreshLogicTest.kt). [lastRefreshMs] is 0 when the
 * widget has never refreshed (first launch) or its timestamp was cleared
 * for a forced refresh.
 */
fun isRefreshDue(lastRefreshMs: Long, nowMs: Long, intervalMs: Long): Boolean =
    nowMs - lastRefreshMs >= intervalMs
