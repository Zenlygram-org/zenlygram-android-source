#!/bin/sh
# Host tests of the Tasks sync owner's pure policy (TasksSyncPolicy: gate permission, drive/stop, re-grant resume,
# write state) and of the §7.5 cut-over table (TasksCutOver, with the Device archive's lines), design 30 §6.1/§7.5.
# ZenlyTasksSyncOwner (Android glue: ZenlyCustomer/ZenlyConsent/ZenlyStats, NotificationCenter, the store) is not
# executed here; its wiring and the repository's send gate are checked as source (the test's argument is the Android
# root). So are the repository's store-facts delegation and migration context (RES-CX1-2) and the app's Device archive
# banner (org.telegram.zenlygram.tasks: ZgDeviceTasksSource, ZgTasksArchive, ZgTasksText) with its
# ZG_tasks_archive_copyReturns text in values, values-ru and values-uz (RES-CX2-2).
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
work=$(mktemp -d "${TMPDIR:-/tmp}/zenlygram-tasks-sync.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
tasks="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/tasks"
cloud="$tasks/cloud"
"$java_bin/javac" --release 8 -Xlint:-options -encoding UTF-8 -d "$work" \
    "$tasks/ZenlyTaskModel.java" "$cloud/TasksCloudGate.java" "$cloud/TasksCutOver.java" "$cloud/TasksSyncPolicy.java" \
    "$test_dir/TasksSyncPolicyTests.java"
"$java_bin/java" -cp "$work" org.telegram.messenger.zenlygram.tasks.cloud.TasksSyncPolicyTests "$android_dir"
