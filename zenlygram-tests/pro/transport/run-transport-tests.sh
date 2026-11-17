#!/bin/sh
# Host behavior tests of the real ProTransport.getPublic and ProTransport.customer against a scripted
# HTTPS boundary. Separate JVMs from run-tests.sh (and from each other): the boundary installs a
# JVM-wide URLStreamHandlerFactory for "https" (no trust manager, socket factory or system property is
# changed). The org.json stub is compile-only and lives only in this runner's temporary class directory.
set -eu
test_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
android_dir=$(CDPATH= cd -- "$test_dir/../../.." && pwd)
java_bin="${JAVA_HOME:-}/bin"
if [ ! -x "$java_bin/javac" ]; then
    java_bin="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin"
fi
if [ ! -x "$java_bin/javac" ]; then
    java_bin=$(dirname -- "$(command -v javac)")
fi
classes=$(mktemp -d "${TMPDIR:-/tmp}/zenlygram-pro-transport-tests.XXXXXX")
trap 'rm -rf "$classes"' EXIT HUP INT TERM
source_dir="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro"
"$java_bin/javac" --release 8 -encoding UTF-8 -d "$classes" \
    "$source_dir/ProPolicy.java" "$source_dir/ProCrypto.java" "$source_dir/ProMonetization.java" "$source_dir/ProTransport.java" \
    "$test_dir/json-stub/org/json/JSONObject.java" "$test_dir/ScriptedHttps.java" "$test_dir/ProTransportTests.java" \
    "$test_dir/CustomerTransportTests.java"
"$java_bin/java" -cp "$classes" org.telegram.messenger.zenlygram.pro.ProTransportTests
"$java_bin/java" -cp "$classes" org.telegram.messenger.zenlygram.pro.CustomerTransportTests
