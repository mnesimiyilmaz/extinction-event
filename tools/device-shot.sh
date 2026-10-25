#!/bin/bash
# Screenshot of the wallpaper at fixed scene times on a device or emulator running the debug build
# (the scene is held still by the DEBUG_SCRUB broadcast, then resumed).
# Usage: tools/device-shot.sh <out-dir> <t>...   e.g. tools/device-shot.sh shots 2 20 6.3 7.6 14.6
set -e
out=$1; shift
mkdir -p "$out"
adb shell input keyevent KEYCODE_WAKEUP
for t in "$@"; do
  adb shell am broadcast -p io.github.mnesimiyilmaz.extinction -a io.github.mnesimiyilmaz.extinction.DEBUG_SCRUB --ef t "$t" > /dev/null
  sleep 1.5
  adb exec-out screencap -p > "$out/t$t.png"
  echo "wrote $out/t$t.png"
done
adb shell am broadcast -p io.github.mnesimiyilmaz.extinction -a io.github.mnesimiyilmaz.extinction.DEBUG_SCRUB --ef t -1 > /dev/null
