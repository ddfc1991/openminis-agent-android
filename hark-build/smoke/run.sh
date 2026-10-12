#!/bin/bash
# Installs the APK on the booted emulator and drives the launch / home / VPS screens.
set -x
adb wait-for-device
adb shell getprop ro.product.cpu.abilist
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
for p in POST_NOTIFICATIONS RECORD_AUDIO; do adb shell pm grant com.openminis.mines.plus android.permission.$p 2>/dev/null; done
adb shell appops set com.openminis.mines.plus SYSTEM_ALERT_WINDOW allow 2>/dev/null
# a big camera-like photo for the 相册 wallpaper
python3 -c "
from PIL import Image, ImageDraw
im=Image.new('RGB',(4000,3000),(240,230,200)); d=ImageDraw.Draw(im)
for i in range(0,4000,200): d.rectangle([i,0,i+100,3000],fill=(30,60,120))
im.save('smoke-wall.jpg',quality=85)" || convert -size 4000x3000 gradient:white-navy smoke-wall.jpg
adb push smoke-wall.jpg /sdcard/Pictures/smoke-wall.jpg
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/smoke-wall.jpg
adb shell content call --uri content://media/external/file --method scan_volume --arg external_primary 2>/dev/null
python3 hark-build/smoke/vps_smoke.py
