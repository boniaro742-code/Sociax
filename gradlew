#!/bin/sh
# Sociax Gradle launcher.
# Works with ANY GitHub workflow that runs "chmod +x gradlew" and "./gradlew ...".
# It uses Gradle 8.9 (the version this project needs). If Gradle 8.9 is not on the machine,
# it downloads it once. No gradle-wrapper.jar needed.
set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
GRADLE_VERSION="8.9"

# 1) A normal Gradle wrapper jar exists (e.g. you added it from Android Studio): use it.
if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi

# 2) Gradle 8.9 already installed (the Sociax workflow installs it): use it.
if command -v gradle >/dev/null 2>&1 && gradle --version 2>/dev/null | grep -q "^Gradle $GRADLE_VERSION"; then
  exec gradle "$@"
fi

# 3) Download Gradle 8.9 once, then use it.
DIST_DIR="${HOME:-/tmp}/.sociax-gradle"
GRADLE_BIN="$DIST_DIR/gradle-$GRADLE_VERSION/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  echo "Downloading Gradle $GRADLE_VERSION ..."
  mkdir -p "$DIST_DIR"
  ZIP="$DIST_DIR/gradle-$GRADLE_VERSION-bin.zip"
  URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 -o "$ZIP" "$URL"
  else
    wget -q -O "$ZIP" "$URL"
  fi
  unzip -q -o "$ZIP" -d "$DIST_DIR"
  rm -f "$ZIP"
  chmod +x "$GRADLE_BIN"
fi
exec "$GRADLE_BIN" "$@"
