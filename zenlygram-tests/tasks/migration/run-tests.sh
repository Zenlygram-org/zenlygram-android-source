#!/bin/sh
# Host tests of the Tasks R4 automatic migration (design 30 §6.5, §7.1): TasksMigrationNormalizer against the shared
# vectors (../fixtures/tasks-r4-vectors.json, verbatim backend copy), the R4 migration plan of TasksCloudImport and the
# pure coordinator TasksAutoMigration with in-memory doubles of the local store and the cloud repository (including its
# executor-turn rule, TasksAutoMigration.awaitTurn). The Android adapters (ZenlyTasksSyncOwner,
# TasksCloudRepository.MigrationCloud) are not run here; of the store SQL (../run_tests.py) only the verification reset
# a new cloud store triggers (store_sql_tests.py, stdlib SQLite) is.
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
work=$(mktemp -d "${TMPDIR:-/tmp}/zenlygram-tasks-migration.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
tasks="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/tasks"
cloud="$tasks/cloud"
"$java_bin/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$gson" -d "$work" \
    "$tasks/ZenlyTaskModel.java" "$tasks/ZenlyTaskImport.java" \
    "$cloud"/TasksWire*.java \
    "$cloud/TasksSql.java" "$cloud/TasksCloudSchema.java" "$cloud/TasksCloudError.java" "$cloud/TasksCloudRemote.java" \
    "$cloud/TasksCloudPolicy.java" "$cloud/TasksCloudState.java" "$cloud/TasksCloudLedger.java" "$cloud/TasksCloudEngine.java" \
    "$cloud/TasksInvitationPacer.java" "$cloud/TasksAutoAcceptPolicy.java" "$cloud/TasksInviteAnswers.java" "$cloud/TasksAssigneeDisplay.java" \
    "$cloud/TasksCloudImport.java" "$cloud/TasksCloudGate.java" "$cloud/TasksAnalyticsSink.java" "$cloud/TasksCloudAutoResolve.java" \
    "$cloud/TasksCutOver.java" "$cloud/TasksMigrationNormalizer.java" "$cloud/TasksSyncPolicy.java" "$cloud/TasksAutoMigration.java" \
    "$test_dir/TasksMigrationTests.java"
"$java_bin/java" -cp "$work:$gson" org.telegram.messenger.zenlygram.tasks.cloud.TasksMigrationTests \
    "$test_dir/../fixtures/tasks-r4-vectors.json"
python3 "$test_dir/store_sql_tests.py" "$tasks/ZenlyTasksStore.java"
