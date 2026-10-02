# Kitchen Dashboard — Build, Validation & Deployment Playbook

The full checklist for making a code change and getting it onto the Fire TV.
Written 2026-09-28 so the conventions, hard-won lessons, and standing rules
are never lost to context compaction.

## 1. Standing rules (never skip these)

- **Never deploy without an explicit "ship".** A full deployment = Muse-link
  upload + fresh AFTVnews Downloader code. "Make these changes and ship when
  ready" means batch the changes, verify on the emulator, and ship. A plain
  "make these changes" means code only — stop there.
- **Don't emulator-test unrequested builds either.** Make the code changes;
  emulator verification happens when the user says "ship it".
- **Batch incremental changes** and wait for the ship word.
- **Version rule** (user's words): *"only builds that can actually launch are
  worth version number increments."* Rebuilding an unshipped package does NOT
  bump the version. Keep versionCode/versionName until a build actually
  launches somewhere.
- **Package stays `com.lifedashboard.tv`, signing cert never changes.**
  In-app updates fail signature checks if either changes. The permanent
  keystore is `~/workspace/life-dashboard-tv/keystore/lifedashboard.jks`
  (PKCS12). Never record, echo, or paste its passwords anywhere.

## 2. Code change + validation checklist

1. Edit sources under `~/workspace/life-dashboard-tv/app/src/main/java/…`.
2. **New functionality or bug fix → add lightweight unit tests** (§3).
   No exceptions; this is a standing user request.
3. Run `test/unit/run_tests.sh` — every test must pass.
4. kotlinc compile-check of the full app (build script step 1 does this;
   can also be run standalone).
5. If you added production source files, add them to
   `~/workspace/apkbuild/sources.txt`. Test files never go in sources.txt.
6. Update the versioned build script's header changelog
   (`~/workspace/apkbuild/build_prodNN.sh`).
7. Rebuild with the versioned build script (§4).
8. **At ship time:** emulator validation (§5), then deployment (§6).
9. Update `~/MEMORY.md` with the production build record (APK path, size,
   SHA-256, Catbox URL, Downloader code, what changed, verification notes).

## 3. Unit tests

- Location: `~/workspace/life-dashboard-tv/test/unit/`
- Runner: `test/unit/run_tests.sh` — plain kotlinc + JVM, no Gradle, no
  Android dependencies.
- Convention (adopted 2026-09-28, user's words): *"Any time either new
  functionality is added, or a bug is fixed, lightweight unit tests should
  be added to ensure future durability."*
- How: extract the logic into pure helpers and test those. Framework-
  dependent behavior stays on the API-25 emulator at ship time.
- Examples: `DayNightTest.kt` (13 tests, incl. the exact 4:55 PM scenario
  from the bug report), `RefreshLogicTest.kt` (8 tests, incl. the cleared-
  timestamp forced-refresh path). New pure helpers live in
  `ui/theme/DayNight.kt` and `ui/RefreshLogic.kt`.

## 4. The build pipeline

Gradle daemon can't run here (Java loopback TCP is intercepted), so builds
use the manual aapt2/kotlinc/D8 pipeline. Full toolchain notes live in
`~/AGENTS.md`; the essentials:

- Toolchain: `~/workspace/kotlin` (1.9.24), `~/workspace/jdk17`,
  SDK `~/workspace/android-sdk` (platform android-34, build-tools 34.0.0),
  R8/D8 `~/workspace/apklib/jars/r8-81324.jar`.
- Script: `~/workspace/apkbuild/build_prodNN.sh`, steps:
  1. kotlinc (app sources, `-jvm-target 17 -no-stdlib`)
  2. **aapt2 compile (app res/) → `compiled_res/app.zip`** — see the
     stale-resource incident below
  3. aapt2 link (`--min-sdk-version 23 --target-sdk-version 34`,
     `--version-code/--version-name`, `--extra-packages` with all 33
     library packages so library R classes get generated)
  4. D8 via standalone R8 (`--min-api 23`), desugar_jdk_libs-1.1.5 as a
     regular input, emoji2's repackaged.jar dexed separately
  5. zipalign `-f -p 4`, apksigner sign, verify
- **D8 input hygiene:** never glob `apklib/jars/*.jar` blindly. Exclude every
  desugar jar except desugar_jdk_libs-1.1.5, every R8 tool jar, and the stale
  lifecycle-runtime-ktx-2.6.1 extracted dir (use the case-filter loop in the
  script). New jars land in that dir over time and silently break the build
  with "defined multiple times" errors.
- **jsoup:** use the patched
  `~/workspace/apklib/jars/org.jsoup.jsoup-1.17.1-api25.jar`. Unpatched jsoup
  (1.17.1 and 1.17.2) calls `ThreadLocal.withInitial()` (API 26+) and dies
  on API 25.
- **THE STALE-RESOURCE INCIDENT (2026-09-28):** the script once linked the
  precompiled `compiled_res/*.zip` without recompiling `res/` first, silently
  shipping the old icon/banner over a corrected APK. The script now ALWAYS
  runs `aapt2 compile --dir <app>/src/main/res -o compiled_res/app.zip`
  before linking. **Never remove that step.**

## 5. Emulator validation (at ship time)

- AVD `firetv-test`, API 25 (the stick runs Fire OS 6.7.1.1 = Android 7.1).
  This VM has no KVM — boot with `-accel off` (software TCG, ~2 min cold
  boot). Without it, boot hard-fails.
- `adb install -r <apk>`, then
  `adb shell am start -n com.lifedashboard.tv/.ui.MainActivity`.
- Checks:
  - Clean launch — no flash-and-nothing, MainActivity becomes the resumed
    activity.
  - Dashboard renders in the correct day/night theme for the current time.
  - **Zero `FATAL EXCEPTION` in logcat.**
  - Screenshot to confirm visually.
  - Exercise the changed feature (toggles, new Settings rows, etc.).
  - Settings → Diagnostics: runtime event log visible; clear-log works.
  - New code present in dex if in doubt
    (`strings classes*.dex | grep <ClassName>`).
- Quirks: `sys.boot_completed` may never report `1` on this emulator —
  `adb devices` showing `device` + a working shell + successful install is
  sufficient. A "Process system isn't responding" ANR from emulator slowness
  is benign; dismiss it and continue.

## 6. Deployment

1. **Upload** the APK to Muse built-in storage:
   `/opt/hatch/bin/remote-storage upload-file --path ~/workspace/<file>`
   → a Muse link with an expiry timestamp. Note the expiry when reporting;
   anyone with the link can access the file.
2. **Hash-verify:** download the Muse link back and compare SHA-256 against
   the local APK. Must be byte-identical before proceeding.
3. **Downloader code:** create exactly one fresh 7-digit code at
   `go.aftvnews.com` pointing at the Muse URL. Standing permission: solve
   CAPTCHAs yourself (the browser's stored CAPTCHA preference covers this
   flow). Known flakiness: the "URL seems unreachable" warning page — check
   its reCAPTCHA box, then "Shorten Anyway".
4. **Verify the redirect:** `curl` the `https://aftv.news/<code>` URL and
   confirm it resolves to the Muse URL (independently, not just the
   site's preview page).
5. **Report:** versionCode/versionName, size, SHA-256, Muse URL (+ expiry),
   Downloader code, and the verification summary.
6. New code supersedes the old — old codes must not be reused.

## 7. Standing distribution decisions

- No Google Drive / Dropbox (the OAuth grant is full write access to the
  user's entire Drive with no folder-scoped option — rejected outright;
  also untested for direct APK downloads).
- No Catbox (retired 2026-09-28 — verified unnecessary; the Muse-link flow
  was the original working one). Muse links expire; the user accepts that
  tradeoff for simplicity.
- One fresh Downloader code per build. Codes are public/guessable — fine
  for an APK, never for access control.
- The "Are you still watching?" return-to-home behavior is a Fire OS
  inactivity policy (4h, no interaction), not an app bug. Device fix:
  Settings → Preferences → Data Usage Monitoring → Still Watching → OFF.
  The app's wake lock cannot override it.
