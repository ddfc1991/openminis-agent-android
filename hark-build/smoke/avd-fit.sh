#!/bin/bash
# [build-38.5] The user phone as it really renders: realme Neo7 Turbo with 智能分辨率 = 1080x2362 px, 405 dpi (~427 dp wide).
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
hw.lcd.width=1080
hw.lcd.height=2362
hw.lcd.density=405
skin.name=1080x2362
skin.dynamic=yes
showDeviceFrame=no
hw.initialOrientation=portrait
CFG
cat "$f"
