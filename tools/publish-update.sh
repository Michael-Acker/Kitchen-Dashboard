#!/bin/bash
# Builds a new release of Life Dashboard for in-app distribution.
#
# Usage:
#   ./tools/publish-update.sh [new-version-name]
#
# What it does:
#   1. Bumps versionCode in app/build.gradle (in-app updater keys off this).
#   2. Optionally sets versionName to the argument you pass.
#   3. Builds app/build/outputs/apk/release/app-release.apk.
#
# After it finishes, YOU:
#   1. Upload the APK to your stable "latest" URL — the same redirect your
#      go.aftvnews.com Downloader code points at is ideal, so one upload
#      serves both Downloader installs and in-app updates.
#   2. On the Fire TV: Settings -> App updates -> Check for updates -> Install.
#
# IMPORTANT: every release build must be signed with the SAME keystore
# (keystore/lifedashboard.jks), or Android will refuse to install it as an
# update and app data (OAuth tokens, settings) will be lost on reinstall.
# Create the keystore once (see the comment in app/build.gradle) and back
# up the .jks file plus its passwords somewhere safe.
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE_FILE="app/build.gradle"

CODE="$(grep -oP 'versionCode \K[0-9]+' "$GRADLE_FILE" | head -1)"
NEW_CODE=$((CODE + 1))
sed -i "s/versionCode $CODE/versionCode $NEW_CODE/" "$GRADLE_FILE"
echo "versionCode: $CODE -> $NEW_CODE"

if [ $# -ge 1 ]; then
  OLD_NAME="$(grep -oP "versionName '\K[^']+" "$GRADLE_FILE" | head -1)"
  sed -i "s/versionName '$OLD_NAME'/versionName '$1'/" "$GRADLE_FILE"
  echo "versionName: $OLD_NAME -> $1"
fi

if [ -x ./gradlew ]; then
  BUILDER=./gradlew
elif command -v gradle >/dev/null 2>&1; then
  BUILDER=gradle
else
  echo "ERROR: no Gradle found. Install Android Studio or Gradle with the Android SDK first." >&2
  exit 1
fi

$BUILDER assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
  echo
  echo "Built: $APK"
  echo "Upload this file to your file host, then point your go.aftvnews.com"
  echo "Downloader code at the new link (same as a fresh Downloader install)."
  echo "Then update from the TV — no retyping needed, the code resolves itself:"
  echo "  Settings -> App updates -> Check for updates -> Install update"
else
  echo "ERROR: expected APK not found at $APK" >&2
  exit 1
fi
