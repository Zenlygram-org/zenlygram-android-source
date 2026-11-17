#!/bin/sh
# Host tests of the real CustomerApi exchange state machine against a recording host Boundary (the
# compile-only ZenlyPro stub only satisfies CustomerApi.forAccount), compiled with the real ProTransport,
# ProMonetization, ProPolicy and ProCrypto and the app's own Gson 2.11.0 jar. Nothing here is device,
# TLS, backend, keystore or real-account evidence.
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
gson="${GSON_JAR:-$android_dir/.gradle-user-home/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0/527175ca6d81050b53bdd4c457a6d6e017626b0e/gson-2.11.0.jar}"
if [ ! -f "$gson" ]; then
    gson=$(find "$HOME/.gradle" -name gson-2.11.0.jar 2>/dev/null | head -n 1)
fi
if [ -z "$gson" ] || [ ! -f "$gson" ]; then
    echo "gson-2.11.0.jar not found (set GSON_JAR)" >&2
    exit 1
fi
classes=$(mktemp -d "${TMPDIR:-/tmp}/zenlygram-customer-api-tests.XXXXXX")
trap 'rm -rf "$classes"' EXIT HUP INT TERM
pro="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro"
"$java_bin/javac" --release 8 -encoding UTF-8 -cp "$gson" -d "$classes" \
    "$pro/CustomerApi.java" "$pro/ProTransport.java" "$pro/ProMonetization.java" "$pro/ProPolicy.java" "$pro/ProCrypto.java" \
    "$android_dir/zenlygram-tests/pro/transport/json-stub/org/json/JSONObject.java" \
    "$test_dir/pro-stub/org/telegram/messenger/zenlygram/pro/ZenlyPro.java" \
    "$test_dir/CustomerApiExchangeTests.java"
"$java_bin/java" -cp "$classes:$gson" org.telegram.messenger.zenlygram.pro.CustomerApiExchangeTests
