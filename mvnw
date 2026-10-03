#!/bin/sh
# Maven wrapper — uses DevTools JDK 21 with NPCI corporate CA.
# Uses pom-local.xml for offline builds (excludes deps not in local Maven cache).
# The full pom.xml is used by Docker/CI where Maven Central is reachable.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SETTINGS="$SCRIPT_DIR/.mvn/settings.xml"

DEVTOOLS_JAVA="$HOME/DevTools/java/jdk-21/Contents/Home"
NPCI_CA="$HOME/DevTools/java/npci-ica.cer"
CUSTOM_TRUSTSTORE="/tmp/seat-reservation-cacerts"

# Use DevTools JDK 21 if available
if [ -d "$DEVTOOLS_JAVA" ]; then
  export JAVA_HOME="$DEVTOOLS_JAVA"
  if [ ! -f "$CUSTOM_TRUSTSTORE" ] && [ -f "$NPCI_CA" ]; then
    cp "$JAVA_HOME/lib/security/cacerts" "$CUSTOM_TRUSTSTORE"
    "$JAVA_HOME/bin/keytool" -import -noprompt -trustcacerts \
      -alias npci-ica -file "$NPCI_CA" \
      -keystore "$CUSTOM_TRUSTSTORE" -storepass changeit 2>/dev/null || true
  fi
  if [ -f "$CUSTOM_TRUSTSTORE" ]; then
    export MAVEN_OPTS="-Djavax.net.ssl.trustStore=$CUSTOM_TRUSTSTORE -Djavax.net.ssl.trustStorePassword=changeit"
  fi
fi

SETTINGS_ARGS=""
if [ -f "$SETTINGS" ]; then
  SETTINGS_ARGS="--settings $SETTINGS"
fi

exec mvn $SETTINGS_ARGS --offline -f pom-local.xml "$@"
