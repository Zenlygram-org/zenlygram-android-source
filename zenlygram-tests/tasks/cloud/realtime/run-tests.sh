#!/bin/sh
# Host tests of the real Tasks realtime sources: TasksRealtimeWire (endpoint/metadata/auth/Pusher frames on the app's
# Gson 2.11.0), TasksRealtimeFrames (RFC 6455 codec + handshake), TasksRealtimeSocket (Pusher 7 state machine, scripted
# WebSocket + manual clock), TasksCloudRealtime (lifecycle/backoff/fencing controller, scripted service) and the
# production TasksRealtimeWebSocket against a loopback JDK TLS server (keytool certificate generated per run).
# Nothing here is device, Conscrypt, backend or real-account evidence.
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
work=$(mktemp -d "${TMPDIR:-/tmp}/zenlygram-tasks-realtime-tests.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir "$work/classes"
cloud="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/tasks/cloud"
"$java_bin/javac" --release 8 -encoding UTF-8 -cp "$gson" -d "$work/classes" \
    "$cloud"/TasksWire*.java "$cloud/TasksCloudRemote.java" \
    "$cloud/TasksRealtimeWire.java" "$cloud/TasksRealtimeFrames.java" "$cloud/TasksRealtimeSocket.java" \
    "$cloud/TasksRealtimeWebSocket.java" "$cloud/TasksCloudRealtime.java" "$cloud/TasksCloudBoards.java" \
    "$test_dir/ManualScheduler.java" "$test_dir/TasksRealtimeWireTests.java" "$test_dir/TasksRealtimeSocketTests.java" \
    "$test_dir/TasksCloudRealtimeTests.java" "$test_dir/TasksRealtimeTlsTests.java"
# CN deliberately names no host: JDK endpoint identification falls back to the CN when no DNS SAN exists, so
# CN=localhost would let the wrong-host test connect.
"$java_bin/keytool" -genkeypair -alias realtime -keyalg EC -groupname secp256r1 -dname "CN=zenlygram-realtime-loopback-test" \
    -ext "SAN=ip:127.0.0.1" -validity 2 -keystore "$work/loopback.p12" -storetype PKCS12 -storepass realtime-test >/dev/null 2>&1
cp="$work/classes:$gson"
"$java_bin/java" -cp "$cp" org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWireTests
"$java_bin/java" -cp "$cp" org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeSocketTests
"$java_bin/java" -cp "$cp" org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRealtimeTests
"$java_bin/java" -cp "$cp" org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeTlsTests "$work/loopback.p12" realtime-test
