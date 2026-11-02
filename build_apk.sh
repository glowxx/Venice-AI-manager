#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "=== Building Venice AI Launcher APK ==="
mkdir -p /home/pi/.gradle-docker

docker run --rm \
    -v "$DIR":/project \
    -v /home/pi/.gradle-docker:/root/.gradle \
    venice-builder \
    ./gradlew assembleRelease --no-daemon --stacktrace

if [ -f "$DIR/app/build/outputs/apk/release/app-release.apk" ]; then
    cp "$DIR/app/build/outputs/apk/release/app-release.apk" "$DIR/VeniceLauncher.apk"
    echo "=== APK Successfully Built ==="
    ls -lh "$DIR/VeniceLauncher.apk"
else
    echo "ERROR: Output APK not found!"
    exit 1
fi
