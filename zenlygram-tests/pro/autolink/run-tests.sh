#!/bin/sh
# Host tests of the automatic link's pure core (design 50 §8, §16.1): ProLinkRequests (bind/start body,
# consent-free resend, manual attest rule, the grant a start carries after its wait on the lane), ProAutoLinkSchedule
# (eligibility, ladders, caps, clamp, a binding loss not applied yet) and ProAutoLinkMachine (steps, polls, watchdog,
# outcomes, error codes; a stale grant never links), compiled for Java 8 with the pure consent classes. It also runs
# two source-reading checks of ZenlyPro.java (and ProAutoLink.java) with the Android root (ProAutoLinkLossTests: a
# server 401 is applied before an automatic link can start; ProLinkConsentWiringTests: a start reads its consent
# again once its wait on the lane is over) and fails when an auto-link source writes to a log. Nothing here is
# device, Telegram or backend evidence.
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
pro="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro"
# Init data, poll and session tokens and consent JSON pass through these classes: none of them may log.
if grep -n -E 'FileLog|android\.util\.Log|(^|[^A-Za-z0-9_])Log\.|printStackTrace' \
        "$pro/ProLinkRequests.java" "$pro/ProAutoLink.java" "$pro/ProAutoLinkSchedule.java" "$pro/ProAutoLinkMachine.java"; then
    echo "auto-link sources must not log" >&2
    exit 1
fi
classes=$(mktemp -d "${TMPDIR:-/tmp}/zenlygram-pro-autolink-tests.XXXXXX")
trap 'rm -rf "$classes"' EXIT HUP INT TERM
# ProPolicy supplies saturatingAdd; the consent classes feed the stale-grant checks real snapshots; everything
# else is the three pure classes, their suites and the source-wiring suite (which compiles nothing it reads).
customer="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/customer"
"$java_bin/javac" --release 8 -encoding UTF-8 -d "$classes" \
    "$pro/ProPolicy.java" "$pro/ProCrypto.java" \
    "$customer/ConsentMachine.java" "$customer/ConsentSnapshot.java" "$customer/ConsentEvent.java" "$customer/ConsentText.java" \
    "$customer/ConsentManifest.java" "$customer/CustomerJson.java" "$customer/UnicodeText.java" \
    "$pro/ProLinkRequests.java" "$pro/ProAutoLinkSchedule.java" "$pro/ProAutoLinkMachine.java" \
    "$test_dir/ProLinkRequestsTests.java" "$test_dir/ProAutoLinkScheduleTests.java" "$test_dir/ProAutoLinkMachineTests.java" \
    "$test_dir/ProAutoLinkLossTests.java" "$test_dir/ProLinkConsentWiringTests.java"
for test in ProLinkRequestsTests ProAutoLinkScheduleTests ProAutoLinkMachineTests; do
    "$java_bin/java" -cp "$classes" "org.telegram.messenger.zenlygram.pro.$test"
done
# The source-wiring suites read ZenlyPro.java and ProAutoLink.java under the Android root.
"$java_bin/java" -cp "$classes" org.telegram.messenger.zenlygram.pro.ProAutoLinkLossTests "$android_dir"
"$java_bin/java" -cp "$classes" org.telegram.messenger.zenlygram.pro.ProLinkConsentWiringTests "$android_dir"
