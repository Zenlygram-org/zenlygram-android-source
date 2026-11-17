#!/bin/sh
# Host tests of the real Tasks cloud transport sources (wire R4): TasksWire codec (with the app's own Gson
# 2.11.0 jar), the TasksApi exchange state machine (host boundary; compile-only ZenlyPro stub; the pure
# TasksCloudPolicy proves batch items map to the single route's actions) and ProTransport.tasks against a
# scripted HTTPS boundary (own JVM: installs a URLStreamHandlerFactory).
# Nothing here is device, TLS, backend or real-account evidence.
set -eu
test_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
android_dir=$(CDPATH= cd -- "$test_dir/../../../.." && pwd)
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
classes=$(mktemp -d "${TMPDIR:-/tmp}/zenlygram-tasks-transport-tests.XXXXXX")
trap 'rm -rf "$classes"' EXIT HUP INT TERM
java_root="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram"
pro="$java_root/pro"
cloud="$java_root/tasks/cloud"
"$java_bin/javac" --release 8 -encoding UTF-8 -cp "$gson" -d "$classes" \
    "$cloud/TasksWire.java" "$cloud/TasksWireJson.java" "$cloud/TasksWireText.java" "$cloud/TasksWireDecode.java" "$cloud/TasksWireEncode.java" \
    "$cloud/TasksRealtimeWire.java" "$cloud/TasksCloudRemote.java" "$cloud/TasksCloudPolicy.java" \
    "$pro/TasksApi.java" "$pro/ProTransport.java" "$pro/ProMonetization.java" "$pro/ProPolicy.java" "$pro/ProCrypto.java" \
    "$android_dir/zenlygram-tests/pro/transport/json-stub/org/json/JSONObject.java" \
    "$test_dir/pro-stub/org/telegram/messenger/zenlygram/pro/ZenlyPro.java" \
    "$test_dir/TasksWireTests.java" "$test_dir/TasksApiExchangeTests.java" "$test_dir/TasksScriptedHttps.java" "$test_dir/TasksTransportTests.java"
# Maximal astral-escaped 100-item pages are ~34 MiB each (bytes + String + tree): give the JVM explicit headroom.
"$java_bin/java" -Xmx2g -cp "$classes:$gson" org.telegram.messenger.zenlygram.tasks.cloud.TasksWireTests
"$java_bin/java" -cp "$classes:$gson" org.telegram.messenger.zenlygram.pro.TasksApiExchangeTests
"$java_bin/java" -Xmx2g -cp "$classes:$gson" org.telegram.messenger.zenlygram.pro.TasksTransportTests
