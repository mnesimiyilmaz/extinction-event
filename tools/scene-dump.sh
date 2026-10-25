#!/bin/bash
# Renders fixed scene moments on the connected device or emulator (SceneDump: off screen, exactly
# as the wallpaper does) and pulls them as PNGs. Compare two dumps with tools/compare-shots.py.
# Usage: tools/scene-dump.sh <out-dir>
set -e
out=$(realpath -m "$1")
mkdir -p "$out"
"$(dirname "$0")/device-test.sh" SceneDump -e dump true > /dev/null
pkg=io.github.mnesimiyilmaz.extinction
adb exec-out run-as "$pkg" tar -C files/dump -cf - . | tar -C "$out" -xf -
ls "$out"
