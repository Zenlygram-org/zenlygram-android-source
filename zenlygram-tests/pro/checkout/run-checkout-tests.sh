#!/bin/sh
# Host regression of the real GooglePlayCheckout (production source, unchanged) against the narrow
# boundary fakes in fakes/. Isolated from ../run-tests.sh: the fakes reuse production class names
# (ZenlyPro, GooglePlayBilling, GooglePlaySync), so they must never share its classpath.
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
classes=$(mktemp -d "${TMPDIR:-/tmp}/zenlygram-checkout-tests.XXXXXX")
trap 'rm -rf "$classes"' EXIT HUP INT TERM
source_dir="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro"
fakes="$test_dir/fakes"
"$java_bin/javac" --release 8 -encoding UTF-8 -d "$classes" \
    "$source_dir/GooglePlayCheckout.java" "$source_dir/GooglePlayModel.java" "$source_dir/GooglePlayAttachKeys.java" \
    "$source_dir/ProFunnelSink.java" \
    "$fakes/android/app/Activity.java" "$fakes/android/net/Uri.java" "$fakes/org/telegram/messenger/UserConfig.java" \
    "$fakes/org/telegram/messenger/zenlygram/pro/ZenlyPro.java" "$fakes/org/telegram/messenger/zenlygram/pro/GooglePlayBilling.java" \
    "$fakes/org/telegram/messenger/zenlygram/pro/GooglePlaySync.java" \
    "$test_dir/GooglePlayCheckoutTests.java"
"$java_bin/java" -cp "$classes" org.telegram.messenger.zenlygram.pro.GooglePlayCheckoutTests
