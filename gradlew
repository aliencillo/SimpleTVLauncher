#!/usr/bin/env sh
set -eu
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VER=9.1.0
BASE="$DIR/.gradle-dist"
ZIP="$BASE/gradle-$VER-bin.zip"
HOMEG="$BASE/gradle-$VER"
if [ ! -x "$HOMEG/bin/gradle" ]; then
  mkdir -p "$BASE"
  if [ ! -f "$ZIP" ]; then
    echo "Downloading Gradle $VER..."
    if command -v curl >/dev/null 2>&1; then curl -L --fail -o "$ZIP" "https://services.gradle.org/distributions/gradle-$VER-bin.zip";
    elif command -v wget >/dev/null 2>&1; then wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$VER-bin.zip";
    else echo "curl or wget is required" >&2; exit 1; fi
  fi
  unzip -q -o "$ZIP" -d "$BASE"
fi
exec "$HOMEG/bin/gradle" "$@"
