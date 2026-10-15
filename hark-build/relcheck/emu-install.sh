#!/bin/bash
# Install tests on the emulator. Never fails the step; results in emu-out/result.txt
mkdir -p emu-out; R=emu-out/result.txt; : > $R
PKG=com.openminis.mines.plus
log(){ echo "$*" | tee -a $R; }
run(){ local name="$1"; shift; local out; out=$("$@" 2>&1); local rc=$?; log "[$name] rc=$rc :: $(echo "$out" | tr '\n' ' ' | tail -c 600)"; }
adb wait-for-device
log "device: sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r') release=$(adb shell getprop ro.build.version.release | tr -d '\r') abis=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r') pagesize=$(adb shell getconf PAGE_SIZE | tr -d '\r')"
D=apks/mines-38.5-downloaded.apk; O=apks/mines-38.5-orig.apk; OLD=apks/mines-38.4.apk
run "A adb install 38.4 fresh" adb install "$OLD"
run "B adb install -r 38.5-signed(downloaded) over 38.4 (update path, like the phone)" adb install -r "$D"
run "B2 versionCode now" adb shell "dumpsys package $PKG | grep -m2 -E 'versionCode|versionName'"
run "C uninstall" adb uninstall $PKG
run "D adb install 38.5-signed fresh" adb install "$D"
run "E uninstall" adb uninstall $PKG
run "F adb install 38.5-orig (v2 only) fresh" adb install "$O"
run "G uninstall" adb uninstall $PKG
run "H push signed to /data/local/tmp" adb push "$D" /data/local/tmp/m.apk
run "I pm install /data/local/tmp (fresh)" adb shell pm install /data/local/tmp/m.apk
run "J pm install -r again" adb shell pm install -r /data/local/tmp/m.apk
run "K uninstall" adb uninstall $PKG
run "L push orig to /data/local/tmp" adb push "$O" /data/local/tmp/o.apk
run "M pm install orig from /data/local/tmp" adb shell pm install /data/local/tmp/o.apk
run "N uninstall" adb uninstall $PKG
# streamed session install (what installer apps do via PackageInstaller sessions)
SZ=$(stat -c %s "$D")
run "O pm install -S (stdin session) signed" sh -c "cat '$D' | adb shell pm install -S $SZ"
run "P launch" adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 8
run "Q pidof" adb shell pidof $PKG
adb logcat -d | grep -iE "PackageManager|PackageInstaller|INSTALL_|ApkSignatureVerifier|NO_CERTIFICATES|FATAL" | tail -80 > emu-out/logcat-install.txt
log "done"
cat $R
exit 0
