#!/bin/bash
# Builds the debug app and its instrumented tests, installs both and runs one test class on the
# connected device or emulator, keeping the app installed (Gradle's connectedDebugAndroidTest
# uninstalls it afterwards, which drops it as the wallpaper).
# Usage: tools/device-test.sh <TestClass> [instrumentation args...]
#   tools/device-test.sh GlDeviceTest
#   tools/device-test.sh GpuBenchmark -e benchmark true      (results: adb logcat -s ExtinctionBench)
set -e
cls=$1; shift
pkg=io.github.mnesimiyilmaz.extinction
cd "$(dirname "$0")/.."
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q
# Screen off so the wallpaper does not render beside the test. Installing and instrumenting each
# kill the app process, and two wallpaper deaths within 10 s make the system revert to the default
# wallpaper, so they are spaced out.
adb shell input keyevent KEYCODE_SLEEP
adb install -r app/build/outputs/apk/debug/app-debug.apk > /dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > /dev/null
sleep 12
out=$(adb shell am instrument -w "$@" -e class "$pkg.$cls" "$pkg.test/androidx.test.runner.AndroidJUnitRunner")
echo "$out" | tail -5
echo "$out" | grep -q "^OK (" 
