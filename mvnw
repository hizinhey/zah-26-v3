#!/bin/sh
set -eu

MAVEN_PROJECTBASEDIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"
WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"

if [ ! -r "$WRAPPER_PROPERTIES" ]; then
  echo "Missing Maven Wrapper properties: $WRAPPER_PROPERTIES" >&2
  exit 1
fi

if [ ! -r "$WRAPPER_JAR" ]; then
  WRAPPER_URL=$(sed -n 's/^wrapperUrl=//p' "$WRAPPER_PROPERTIES" | head -n 1)
  if [ -z "$WRAPPER_URL" ]; then
    echo "wrapperUrl is required in $WRAPPER_PROPERTIES" >&2
    exit 1
  fi

  mkdir -p "$(dirname "$WRAPPER_JAR")"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$WRAPPER_URL" -o "$WRAPPER_JAR.tmp"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$WRAPPER_URL" -O "$WRAPPER_JAR.tmp"
  else
    echo "Maven Wrapper needs curl or wget to download $WRAPPER_URL" >&2
    exit 1
  fi
  mv "$WRAPPER_JAR.tmp" "$WRAPPER_JAR"
fi

if [ -n "${JAVA_HOME:-}" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=java
fi
exec "$JAVACMD" -classpath "$WRAPPER_JAR" \
  -Dmaven.multiModuleProjectDirectory="$MAVEN_PROJECTBASEDIR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
