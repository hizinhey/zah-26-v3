#!/bin/sh
set -eu

if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi

echo "Maven is not installed. Install Maven 3.9.11 or add .mvn/wrapper/maven-wrapper.jar." >&2
exit 1
