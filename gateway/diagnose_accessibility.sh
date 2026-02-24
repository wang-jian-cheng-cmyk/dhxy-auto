#!/usr/bin/env bash
set -euo pipefail

PKG="${PKG:-com.example.dhxyauto}"
SERVICE_CLASS="${SERVICE_CLASS:-.AutomationAccessibilityService}"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH"
  exit 1
fi

echo "[1/4] Checking adb devices..."
adb devices

DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device" {count++} END {print count+0}')
if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "No connected adb device. Connect phone first (USB or wireless debugging)."
  exit 1
fi

echo
echo "[2/4] Enabled accessibility services"
adb shell settings get secure enabled_accessibility_services || true

echo
echo "[3/4] Accessibility dump (filtered)"
adb shell dumpsys accessibility | grep -nE "enabled|bound|$PKG|Accessibility" || true

echo
echo "[4/4] Last crash lines for app"
adb logcat -d | grep -nE "FATAL EXCEPTION|AndroidRuntime|$PKG|AccessibilityService" | tail -n 120 || true

echo
echo "Done."
echo "Tip: set PKG and SERVICE_CLASS env vars if package/class changed."
