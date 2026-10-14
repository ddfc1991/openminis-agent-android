#!/bin/bash
# Installs the APK on the booted emulator and drives the launch / home / VPS screens.
set -x
adb wait-for-device
adb shell getprop ro.product.cpu.abilist
# (do not disable com.google.android.googlesdksetup: on API 35 it provisions the emulator Wi-Fi; disabling it left the emulator with no network in smoke run 21)
# wait (up to 150 s) for the emulator's network (default route) before driving the app
for i in $(seq 1 50); do adb shell ip route show table all 2>/dev/null | grep -qE "^default .*dev (wlan0|eth0|radio0)" && break; sleep 3; done
adb shell ip route show table all | grep default; adb shell dumpsys connectivity 2>/dev/null | grep -m3 -E "NetworkAgentInfo|VALIDATED" | cut -c1-200
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
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
