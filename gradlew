#!/bin/sh
# Minimal Gradle wrapper
GRADLE_HOME="${HOME}/.gradle"
GRADLE_VERSION="7.5"
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

if [ -f "${GRADLE_HOME}/wrapper/dists/gradle-${GRADLE_VERSION}-bin/*/gradle-${GRADLE_VERSION}/bin/gradle" ]; then
  GRADLE_BIN=$(ls ${GRADLE_HOME}/wrapper/dists/gradle-${GRADLE_VERSION}-bin/*/gradle-${GRADLE_VERSION}/bin/gradle 2>/dev/null | head -1)
  exec "${GRADLE_BIN}" "$@"
fi

# Try system gradle
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

echo "Gradle not found"
exit 1
