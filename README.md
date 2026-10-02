# Kitchen Dashboard

A native Android kiosk app for the Amazon Fire TV Stick (Fire OS 6 / Android 7.1), built as an
always-on kitchen wall display.

## What it shows

- **Week-strip calendar** — two Google Calendar accounts pooled into one Sunday–Sunday strip;
  the whole displayed week stays loaded, including events that already passed today
- **Weather** — current conditions, hourly + daily forecast, sunrise/sunset (via Open-Meteo, no API key)
- **Parcel tracking** — Parcel Pending locker deliveries with per-recipient cards
- **News headlines** — BBC RSS carousel
- **Ten themes** — five day / five night palettes with automatic dimming, all hand-tuned
- **In-app updater** — checks GitHub Releases for a newer build and installs it, no remote needed
- **Stays awake** — holds a wake lock so Fire OS never sleeps or screensavers mid-display

## Tech notes

- Kotlin, `minSdk 23 / targetSdk 34`, package `com.lifedashboard.tv`
- Built with a manual **aapt2 → kotlinc → D8** pipeline (the Gradle daemon can't run in this
  environment); see `BUILD_AND_DEPLOY.md` for the full build/ship checklist
- Google sign-in uses the OAuth 2.0 device flow; the client secret is entered at runtime in
  Settings and stored in encrypted prefs — no secrets are baked into the source
- Lightweight unit tests live in `test/unit` (`run_tests.sh`); new features and bug fixes ship
  with tests

## Distribution

Production APKs ship as **GitHub Releases** on this repo. The app's updater polls the latest
release, version-compares, downloads, and installs via FileProvider. Release builds are signed
with a private keystore that is **not** in this repo (`keystore/` is gitignored) — every build
must use the same key or Android will refuse it as an update and app data will be lost.
