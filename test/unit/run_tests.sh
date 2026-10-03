#!/bin/bash
# Lightweight JVM unit tests for pure-logic helpers.
#
# Convention (2026-09-28): whenever new functionality is added or a bug is
# fixed, add/extend a test here. Only pure Kotlin with no Android
# dependencies belongs under test/unit — it compiles with plain kotlinc
# and runs on the JVM in seconds. Anything needing the framework goes to
# the API-25 emulator instead.
#
# Usage: ./test/unit/run_tests.sh   (from ~/workspace/life-dashboard-tv)
set -e
cd "$(dirname "$0")/../.."

export JAVA_HOME=/home/hatch/workspace/jdk17
export PATH="$JAVA_HOME/bin:/home/hatch/workspace/kotlin/bin:$PATH"
KOTLINC=kotlinc
STDLIB=/home/hatch/workspace/kotlin/lib/kotlin-stdlib.jar
OUT=/tmp/kd-unit-tests
rm -rf "$OUT"
mkdir -p "$OUT"

SRC=app/src/main/java/com/lifedashboard/tv/ui/theme

echo "=== compiling unit tests ==="
"$KOTLINC" \
  test/unit/DayNightTest.kt \
  test/unit/RefreshLogicTest.kt \
  test/unit/ThemeSelectionTest.kt \
  test/unit/ThemeAuditTest.kt \
  test/unit/ManifestTest.kt \
  test/unit/WeatherRoleColorsTest.kt \
  test/unit/CalendarWindowTest.kt \
  test/unit/CalendarSelectionTest.kt \
  test/unit/CalendarEventDaysTest.kt \
  test/unit/stubs/android/graphics/Color.kt \
  "$SRC/DayNight.kt" \
  "$SRC/Theme.kt" \
  "$SRC/Themes.kt" \
  "$SRC/ThemeSelection.kt" \
  "$SRC/ThemeAudit.kt" \
  "$SRC/WeatherRoleColors.kt" \
  app/src/main/java/com/lifedashboard/tv/ui/RefreshLogic.kt \
  app/src/main/java/com/lifedashboard/tv/data/CalendarWindow.kt \
  app/src/main/java/com/lifedashboard/tv/data/CalendarSelection.kt \
  app/src/main/java/com/lifedashboard/tv/data/CalendarEventDays.kt \
  app/src/main/java/com/lifedashboard/tv/model/Models.kt \
  -d "$OUT"

for t in DayNightTest RefreshLogicTest ThemeSelectionTest ThemeAuditTest ManifestTest WeatherRoleColorsTest CalendarWindowTest CalendarSelectionTest CalendarEventDaysTest; do
  echo "=== running $t ==="
  java -cp "$OUT:$STDLIB" "com.lifedashboard.tv.test.${t}Kt"
done
echo "=== all unit tests passed ==="
