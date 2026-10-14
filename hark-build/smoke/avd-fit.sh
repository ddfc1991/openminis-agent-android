#!/bin/bash
# [build-38.3] Make the smoke emulator the user's real phone: realme Neo7 Turbo, 1280x2800 px, 480 dpi (~427 dp wide).
# Runs as android-emulator-runner's pre-emulator-launch-script (after the AVD is created, before the emulator starts).
# Why not `wm size`: the default AVD boots a 320x640 panel (androidboot.qemu.skin=320x640) and WindowManager clamps a
# forced size to a bounded multiple of the physical panel, so `wm size 1080x2400` ended up as 1080x1920.
set -x
d="${ANDROID_AVD_HOME:-$HOME/.android/avd}/test.avd"
[ -d "$d" ] || d=$(dirname "$(find "$HOME" / -path '*test.avd/config.ini' 2>/dev/null | head -1)")
f="$d/config.ini"
ls -la "$d"
sed -i -E '/^(hw\.lcd\.width|hw\.lcd\.height|hw\.lcd\.density|skin\.name|skin\.path|skin\.dynamic|showDeviceFrame|hw\.initialOrientation)[ ]*=/d' "$f"
cat >> "$f" <<CFG
hw.lcd.width=1280
hw.lcd.height=2800
hw.lcd.density=480
skin.name=1280x2800
skin.dynamic=yes
showDeviceFrame=no
hw.initialOrientation=portrait
CFG
cat "$f"
