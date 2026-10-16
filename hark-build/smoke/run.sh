#!/bin/bash
# Installs the APK on the booted emulator and drives the launch / home / VPS screens.
set -x
adb wait-for-device
adb shell getprop ro.product.cpu.abilist
# (do not disable com.google.android.googlesdksetup: on API 35 it provisions the emulator Wi-Fi; disabling it left the emulator with no network in smoke run 21)
# wait (up to 150 s) for the emulator's network (default route) before driving the app
for i in $(seq 1 50); do adb shell ip route show table all 2>/dev/null | grep -qE "^default .*dev (wlan0|eth0|radio0)" && break; sleep 3; done
adb shell ip route show table all | grep default; adb shell dumpsys connectivity 2>/dev/null | grep -m3 -E "NetworkAgentInfo|VALIDATED" | cut -c1-200
# [build-38.7] update over the previous version (38.6, same key): install it, open it once, then adb install -r the new APK
if [ -n "$OLD_APK" ] && [ -f "$OLD_APK" ]; then
  adb install -g "$OLD_APK" && echo "OLD_INSTALL: ok"
  adb shell monkey -p com.openminis.mines.plus -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 12
  mkdir -p smoke-out; adb exec-out screencap -p > smoke-out/zz-old-launch.png   # [38.8] before: 38.7 first screen
  adb shell am force-stop com.openminis.mines.plus
  adb shell dumpsys package com.openminis.mines.plus | grep -m2 -E "versionName|versionCode"
  if adb install -r -g "$APK" 2>&1 | tee /tmp/upg.txt | grep -q Success; then echo "INSTALL_UPGRADE: Success"; else echo "INSTALL_UPGRADE: FAILED $(cat /tmp/upg.txt)"; fi
  adb shell dumpsys package com.openminis.mines.plus | grep -m2 -E "versionName|versionCode"
  # [38.8] open the upgraded app once: still running, and (no language ever picked) it comes up in 简体中文
  mkdir -p smoke-out
  adb shell monkey -p com.openminis.mines.plus -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 15
  if adb shell pidof com.openminis.mines.plus >/dev/null; then echo "UPGRADE_LAUNCH: running"; else echo "UPGRADE_LAUNCH: not running"; fi
  adb exec-out screencap -p > smoke-out/zz-upgrade-launch.png
  adb shell uiautomator dump /sdcard/up.xml >/dev/null 2>&1; adb shell cat /sdcard/up.xml > smoke-out/zz-upgrade-launch.xml 2>/dev/null
  grep -q "欢迎使用 Mines" smoke-out/zz-upgrade-launch.xml && echo "UPGRADE_ZH: yes" || echo "UPGRADE_ZH: no ($(grep -o 'Welcome to Mines' smoke-out/zz-upgrade-launch.xml | head -1))"
  adb shell am force-stop com.openminis.mines.plus
  # the smoke then runs on a clean app (fresh state) as before
  adb shell pm clear com.openminis.mines.plus
fi
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
# [38.8] the harness drives the English onboarding labels: pin Mines to en-US through Android's per-app language
# (Mines then keeps 「跟随系统」 instead of its new 简体中文 default). The fresh install at the end checks the default.
adb shell cmd locale set-app-locales com.openminis.mines.plus --locales en-US && echo "APP_LOCALE: en-US"
for p in POST_NOTIFICATIONS RECORD_AUDIO; do adb shell pm grant com.openminis.mines.plus android.permission.$p 2>/dev/null; done
adb shell appops set com.openminis.mines.plus SYSTEM_ALERT_WINDOW allow 2>/dev/null
# a big camera-like photo for the 相册 wallpaper (pure-python PNG, 4000x3000)
python3 - <<'PY'
import zlib, struct
w, h = 4000, 3000
row = bytearray(b"\x00")
for x in range(w):
    row += bytes((30, 60, 120)) if (x // 100) % 2 else bytes((240, 230, 200))
raw = bytes(row) * h
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b"")
open("smoke-wall.jpg".replace(".jpg", ".png"), "wb").write(png)
PY
mv smoke-wall.png smoke-wall.jpg 2>/dev/null; ls -la smoke-wall.jpg
adb push smoke-wall.jpg /sdcard/Pictures/smoke-wall.png
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/smoke-wall.png
adb shell content call --uri content://media/external/file --method scan_volume --arg external_primary 2>/dev/null
python3 -m pip install -q --break-system-packages pillow 2>/dev/null || python3 -m pip install -q pillow 2>/dev/null || true   # [build-38.4] screenshot paint check
python3 hark-build/smoke/vps_smoke.py
rc=$?
# [38.8 v5] if the emulator went away during the drive, stop here instead of waiting forever for the device
if [ "$(timeout 20 adb get-state 2>/dev/null)" != "device" ]; then echo "EMULATOR_GONE: adb lost the emulator during the smoke"; exit 1; fi
# [build-38.7] fresh install: uninstall, install, launch, still running after 15 s
adb uninstall com.openminis.mines.plus >/dev/null 2>&1
if adb install -g "$APK" 2>&1 | tee /tmp/fresh.txt | grep -q Success; then echo "INSTALL_FRESH: Success"; else echo "INSTALL_FRESH: FAILED $(cat /tmp/fresh.txt)"; fi
adb shell monkey -p com.openminis.mines.plus -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 15
if adb shell pidof com.openminis.mines.plus >/dev/null; then echo "FRESH_LAUNCH: running"; else echo "FRESH_LAUNCH: not running"; fi
adb exec-out screencap -p > smoke-out/zz-fresh-install-launch.png
adb shell uiautomator dump /sdcard/fr.xml >/dev/null 2>&1; adb shell cat /sdcard/fr.xml > smoke-out/zz-fresh-install-launch.xml 2>/dev/null
grep -q "欢迎使用 Mines" smoke-out/zz-fresh-install-launch.xml && echo "FRESH_ZH: yes" || echo "FRESH_ZH: no ($(grep -o 'Welcome to Mines' smoke-out/zz-fresh-install-launch.xml | head -1))"
adb logcat -d | grep -E "FATAL EXCEPTION|ANR in com.openminis" | head -5 | sed 's/^/AFTER_FRESH: /'
for i in 1 2 3; do adb shell input swipe 540 1700 540 700 300; sleep 1; done
adb exec-out screencap -p > smoke-out/zz-fresh-install-launch-2.png
exit $rc
