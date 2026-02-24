#!/usr/bin/env bash
set -euo pipefail

PKG="${PKG:-com.example.dhxyauto}"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH"
  exit 1
fi

echo "Clearing old logcat buffer..."
DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device" {count++} END {print count+0}')
if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "No connected adb device. Connect phone first (USB or wireless debugging)."
  exit 1
fi

adb logcat -c || true

echo "Watching logs for $PKG (Ctrl+C to stop)..."
adb logcat | grep --line-buffered -E "${PKG}|AccessibilityService|AndroidRuntime|FATAL EXCEPTION"
