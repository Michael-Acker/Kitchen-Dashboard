package com.lifedashboard.tv.test

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Launcher-visibility regression tests (2026-09-29).
 *
 * Context: the Fire TV launcher tile was blank/missing across builds 29
 * and 30. Build 30's root cause: android:icon/android:banner were set on
 * <application> in the source manifest but missing from the hand-maintained
 * merged manifest at ~/workspace/apkbuild/AndroidManifest.xml — and aapt
 * only guarantees the Leanback tile when the *launchable activity* itself
 * carries them. These tests assert, for both the source manifest and the
 * merged manifest that actually ships, that the Leanback-launchable
 * MainActivity declares android:icon and android:banner.
 *
 * Build 32 root cause (2026-09-29): even with the manifest correct, Fire OS
 * ignores APK icons stored under res/mipmap-* for sideloaded apps and shows
 * a blank tile (AFTVnews documented fix). The icon must live under
 * res/drawable-* and the manifest must reference @drawable/ic_launcher.
 * These tests pin that too, including that the drawable PNGs exist.
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

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

private fun parseManifest(path: String): Element {
    val file = File(path)
    check("manifest exists: $path", file.exists())
    val db = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
    return db.parse(file).documentElement
}

/** The activity carrying the LEANBACK_LAUNCHER category. */
private fun leanbackActivity(root: Element): Element? {
    val activities = root.getElementsByTagName("activity")
    for (i in 0 until activities.length) {
        val a = activities.item(i) as Element
        val cats = a.getElementsByTagName("category")
        for (j in 0 until cats.length) {
            val c = cats.item(j) as Element
            if (c.getAttributeNS(ANDROID_NS, "name") == "android.intent.category.LEANBACK_LAUNCHER") {
                return a
            }
        }
    }
    return null
}

private fun checkManifest(label: String, path: String) {
    val root = parseManifest(path)
    val activity = leanbackActivity(root)
    check("$label: has a LEANBACK_LAUNCHER activity", activity != null)
    if (activity == null) return
    val name = activity.getAttributeNS(ANDROID_NS, "name")
    check("$label: leanback activity is MainActivity ($name)", name.endsWith("MainActivity"))
    val icon = activity.getAttributeNS(ANDROID_NS, "icon")
    val banner = activity.getAttributeNS(ANDROID_NS, "banner")
    check("$label: MainActivity declares android:icon ($icon)", icon.isNotEmpty())
    check("$label: MainActivity declares android:banner ($banner)", banner.isNotEmpty())
    check("$label: icon points at the launcher drawable", icon == "@drawable/ic_launcher")
    check("$label: banner points at the app banner", banner == "@drawable/app_banner")
}

fun main() {
    checkManifest("source manifest", "app/src/main/AndroidManifest.xml")
    // The hand-maintained merged manifest that actually ships (sibling of
    // the project dir; run_tests.sh cds to the project root).
    checkManifest("merged manifest", "../apkbuild/AndroidManifest.xml")

    // Fire OS sideloaded-app icon fix (build 32): the launcher icon must
    // exist under res/drawable-*, not just res/mipmap-*.
    val densities = listOf("hdpi", "mdpi", "xhdpi", "xxhdpi", "xxxhdpi")
    for (d in densities) {
        val f = File("app/src/main/res/drawable-$d/ic_launcher.png")
        check("drawable-$d/ic_launcher.png exists", f.exists() && f.length() > 0)
    }
    check(
        "no manifest references @mipmap/ic_launcher",
        !File("app/src/main/AndroidManifest.xml").readText().contains("@mipmap/ic_launcher") &&
            !File("../apkbuild/AndroidManifest.xml").readText().contains("@mipmap/ic_launcher")
    )

    if (failures > 0) {
        println("$failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("All Manifest tests passed.")
}
