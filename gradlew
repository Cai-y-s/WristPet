#!/usr/bin/env sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
VERSION=8.14.2
GRADLE_HOME_LOCAL="$ROOT/.gradle/gradle-$VERSION"
if [ ! -x "$GRADLE_HOME_LOCAL/bin/gradle" ]; then
  echo "Gradle $VERSION is not present. Download the Gradle distribution from:" >&2
  echo "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" >&2
  echo "Unzip it to $ROOT/.gradle/gradle-$VERSION and retry." >&2
  exit 1
fi
exec "$GRADLE_HOME_LOCAL/bin/gradle" "$@"
