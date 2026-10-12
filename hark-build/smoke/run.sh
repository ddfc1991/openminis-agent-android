#!/bin/bash
# Installs the APK on the booted emulator and drives the VPS screens.
set -x
adb wait-for-device
adb shell getprop ro.product.cpu.abilist
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
for p in POST_NOTIFICATIONS RECORD_AUDIO; do adb shell pm grant com.openminis.mines.plus android.permission.$p 2>/dev/null; done
adb shell appops set com.openminis.mines.plus SYSTEM_ALERT_WINDOW allow 2>/dev/null
python3 hark-build/smoke/vps_smoke.py
