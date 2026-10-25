#!/bin/bash
# Builds umtv-helper.dex, the small program UnbloatMyTV runs on the TV to read app names and icons.
# Needs a JDK and the Android SDK (build-tools and a platform): ANDROID_HOME, or Homebrew's android-commandlinetools.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
JAR=$(/bin/ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
D8=$(/bin/ls -d "$SDK"/build-tools/*/d8 | sort -V | tail -1)
rm -rf build && mkdir -p build/classes
javac --release 11 -cp "$JAR" -d build/classes $(find src -name '*.java')
"$D8" --release --min-api 26 --lib "$JAR" --output build $(find build/classes -name '*.class')
mv build/classes.dex umtv-helper.dex
rm -rf build
echo "Built helper/umtv-helper.dex ($(wc -c < umtv-helper.dex | tr -d ' ') bytes)"
