#!/bin/sh
set -eu
test_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
android_dir=$(CDPATH= cd -- "$test_dir/../.." && pwd)
java_bin="${JAVA_HOME:-}/bin"
if [ ! -x "$java_bin/javac" ]; then
    java_bin="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin"
fi
if [ ! -x "$java_bin/javac" ]; then
    java_bin=$(dirname -- "$(command -v javac)")
fi
classes=$(mktemp -d "${TMPDIR:-/tmp}/zenlygram-pro-tests.XXXXXX")
trap 'rm -rf "$classes"' EXIT HUP INT TERM
source_dir="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro"
"$java_bin/javac" --release 8 -encoding UTF-8 -d "$classes" "$source_dir/ProPolicy.java" "$source_dir/ProCrypto.java" \
    "$source_dir/ProMonetization.java" "$source_dir/ProActions.java" "$source_dir/ProBindAttest.java" \
    "$source_dir/ProLinkLifecycle.java" "$source_dir/GooglePlayModel.java" "$source_dir/GooglePlayAttachKeys.java" \
    "$source_dir/ProPushHint.java" "$source_dir/ProPushToken.java" "$source_dir/ProReplayPolicy.java" "$source_dir/ProAccessSchedule.java" \
    "$source_dir/ProErrors.java" "$source_dir/ProCooldowns.java" "$source_dir/GooglePlayAttachRecord.java" "$source_dir/GooglePlayAttachFlow.java" \
    "$source_dir/ProOrigin.java" \
    "$test_dir/ProCoreTests.java" "$test_dir/GooglePlayModelTests.java" "$test_dir/GooglePlayAttachKeysTests.java" \
    "$test_dir/ProPushHintTests.java" "$test_dir/ProPushTokenTests.java" "$test_dir/ProReplayPolicyTests.java" \
    "$test_dir/ProAccessScheduleTests.java" "$test_dir/GooglePlayRecoveryTests.java" "$test_dir/ProErrorsTests.java" \
    "$test_dir/ProCooldownsTests.java" "$test_dir/GooglePlayAttachRecordTests.java" "$test_dir/GooglePlayAttachFlowTests.java" \
    "$test_dir/ProTestTokens.java" "$test_dir/ProMonetizationTests.java" "$test_dir/ProFreeFirstTests.java" "$test_dir/ProBindAttestTests.java" \
    "$test_dir/ProOriginTests.java"
for test in ProCoreTests GooglePlayModelTests GooglePlayAttachKeysTests ProPushHintTests ProPushTokenTests ProReplayPolicyTests \
        ProAccessScheduleTests GooglePlayRecoveryTests ProErrorsTests ProCooldownsTests GooglePlayAttachRecordTests GooglePlayAttachFlowTests \
        ProMonetizationTests ProFreeFirstTests ProBindAttestTests; do
    "$java_bin/java" -cp "$classes" "org.telegram.messenger.zenlygram.pro.$test"
done
# The origin suite also scans the production sources for the test-only held configuration factory.
"$java_bin/java" -cp "$classes" org.telegram.messenger.zenlygram.pro.ProOriginTests "$android_dir"

# These compile the real controller/transport against test-only platform boundaries in separate JVMs.
sh "$test_dir/checkout/run-checkout-tests.sh"
sh "$test_dir/transport/run-transport-tests.sh"
