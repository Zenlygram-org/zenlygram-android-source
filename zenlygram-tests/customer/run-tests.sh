#!/bin/sh
# Host tests of the pure customer-data code (design 50 §16.1): every customer/*.java whose name does not start with
# "Zenly" (those are Android glue), plus pro/ProOrigin.java, compiled with javac --release 8 together with MiniJson
# and every *Tests.java here; each suite's main runs in alphabetical order with the Android repository root as its
# argument (fixtures/ holds the backend's shared fixtures, vendored by B/tools/consent/vendor.sh). First, the no-logging
# rule: nothing in customer/, pro/CustomerApi.java, pro/ProAutoLink*.java or pro/ProLinkRequests.java may call FileLog,
# android.util.Log, Log.* or printStackTrace. No device, network, backend or account is involved.
# The string and label checks of the customer screens are a separate script: python3 strings_parity.py.
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
LC_COLLATE=C
export LC_COLLATE
customer="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/customer"
pro="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro"

# No logging in customer code (design 50 header rule; compliance C23).
logging=0
for file in "$customer"/*.java "$pro/CustomerApi.java" "$pro"/ProAutoLink*.java "$pro/ProLinkRequests.java"; do
    [ -f "$file" ] || continue
    if grep -nE 'FileLog|android\.util\.Log|printStackTrace|(^|[^A-Za-z0-9_$])Log[[:space:]]*\.[[:space:]]*[A-Za-z_]+[[:space:]]*\(' "$file" >&2; then
        echo "customer code must not log: $file" >&2
        logging=1
    fi
done
[ "$logging" -eq 0 ] || exit 1

classes=$(mktemp -d "${TMPDIR:-/tmp}/zenlygram-customer-tests.XXXXXX")
trap 'rm -rf "$classes"' EXIT HUP INT TERM
set --
for file in "$customer"/*.java; do
    case "$(basename -- "$file")" in
        Zenly*) ;;
        *) set -- "$@" "$file" ;;
    esac
done
set -- "$@" "$pro/ProOrigin.java" "$test_dir/MiniJson.java"
suites=""
for file in "$test_dir"/*Tests.java; do
    [ -f "$file" ] || continue
    set -- "$@" "$file"
    suites="$suites $(basename -- "$file" .java)"
done
"$java_bin/javac" --release 8 -encoding UTF-8 -d "$classes" "$@"
# Every suite runs, so one failing suite never hides the ones after it; the script fails when any did.
failed=""
for suite in $suites; do
    "$java_bin/java" -cp "$classes" "org.telegram.messenger.zenlygram.customer.$suite" "$android_dir" || failed="$failed $suite"
done
if [ -n "$failed" ]; then
    echo "FAILED:$failed" >&2
    exit 1
fi
