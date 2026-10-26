#!/usr/bin/env sh
set -eu
./gradlew :app:assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
