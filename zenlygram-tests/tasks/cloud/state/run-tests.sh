#!/bin/sh
# Host tests of the real cloud Tasks state sources (TasksCloudLedger/Engine/State/Import/Policy/Schema + local
# ZenlyTasksStore v2 migration SQL) with the real TasksWire codec and the app's Gson 2.11.0 jar. Tasks R4
# (TasksCloudR4Tests, own JVM: the consent gate's fail-closed default is process state): gate, consent fence and
# withdrawal epoch, batch drain, sealing fix, downgrade, provenance, patch, automatic resolution/requeue, cut-over,
# schema v2 and its 1->2 upgrade (also as literal SQL in sql_fixture_tests.py). TasksCloudPauseBindingTests (own JVM):
# a persisted 401 pause ends with the re-link after a restart (single route, batch item, realtime); a 403 one does not.
# SQL runs on host stdlib SQLite (sqlite_bridge.py, sql_fixture_tests.py), NOT Android SQLite; the remote is a
# scripted request/answer double decoded by the production strict decoders. TasksCloudRepository (Android glue:
# SQLiteOpenHelper, SharedPreferences marker, deleteDatabase wipe, main-thread delivery) is NOT executed here.
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
python3 "$test_dir/sql_fixture_tests.py"
work=$(mktemp -d "${TMPDIR:-/tmp}/zenlygram-tasks-cloud-state.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir "$work/classes" "$work/db"
tasks="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/tasks"
cloud="$tasks/cloud"
"$java_bin/javac" --release 8 -encoding UTF-8 -cp "$gson" -d "$work/classes" \
    "$tasks/ZenlyTaskModel.java" "$tasks/ZenlyTaskImport.java" \
    "$cloud"/TasksWire*.java \
    "$cloud/TasksSql.java" "$cloud/TasksCloudSchema.java" "$cloud/TasksCloudError.java" "$cloud/TasksCloudRemote.java" \
    "$cloud/TasksCloudPolicy.java" "$cloud/TasksCloudState.java" "$cloud/TasksCloudLedger.java" "$cloud/TasksCloudEngine.java" \
    "$cloud/TasksInvitationPacer.java" "$cloud/TasksAutoAcceptPolicy.java" "$cloud/TasksInviteAnswers.java" "$cloud/TasksAssigneeDisplay.java" \
    "$cloud/TasksCloudImport.java" "$cloud/TasksCloudCleanup.java" "$cloud/TasksCloudIdentityGate.java" \
    "$cloud/TasksCloudGate.java" "$cloud/TasksAnalyticsSink.java" "$cloud/TasksCloudAutoResolve.java" "$cloud/TasksCutOver.java" \
    "$cloud/TasksCloudBoards.java" \
    "$cloud/TasksMigrationNormalizer.java" "$cloud/TasksAutoMigration.java" "$test_dir/TasksBatchSplitTests.java" \
    "$test_dir/PySqlite.java" "$test_dir/TasksCloudStateTests.java" "$test_dir/TasksCloudCleanupTests.java" \
    "$test_dir/TasksCloudIdentityGateTests.java" "$test_dir/TasksCloudR4Tests.java" "$test_dir/TasksCloudPauseBindingTests.java" \
    "$test_dir/TasksCutOverParityTests.java" "$test_dir/TasksCopyDialogTests.java" "$test_dir/TasksAssignTests.java"
"$java_bin/java" -cp "$work/classes:$gson" org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudStateTests \
    "$test_dir/sqlite_bridge.py" "$work/db"
mkdir "$work/r4"
"$java_bin/java" -cp "$work/classes:$gson" org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudR4Tests \
    "$test_dir/sqlite_bridge.py" "$work/r4"
mkdir "$work/pause"
"$java_bin/java" -cp "$work/classes:$gson" org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudPauseBindingTests \
    "$test_dir/sqlite_bridge.py" "$work/pause"
# TasksBatchSplitTests (own JVM): a whole-batch 422 is resent one entry per batch, the once-per-database repair of what
# an older build marked invalid for it, and the R4 explicit copy building the automatic migration's bytes. FIX-A8b: the
# app's copy rules by the server's wire and the copy dialog's labels (Android sources read from the root), and a
# send-time consent refusal that costs no attempt on any route.
mkdir "$work/split"
"$java_bin/java" -cp "$work/classes:$gson" org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests \
    "$test_dir/sqlite_bridge.py" "$work/split" "$android_dir"
# TasksCopyDialogTests (own JVM): under R4 the copy dialog offers no folder choice and every folder is its own list (the
# re-check's bytes), a folder removed in Cloud keeps its new tasks on the device, the dialog's wording follows the rules.
mkdir "$work/copy"
"$java_bin/java" -cp "$work/classes:$gson" org.telegram.messenger.zenlygram.tasks.cloud.TasksCopyDialogTests \
    "$test_dir/sqlite_bridge.py" "$work/copy" "$android_dir"
# TasksAssignTests (own JVM): the assignee pick is the invitation (iOS wf15) — the pacer, the contact rule, the manual
# answers and the display table as pure rules; a create with an assignee as one chain, pick-time refusals, Send again
# and the exact-epoch removal, a long rate limit deferring the assignment and its automatic re-send, the legacy
# annotation, the automatic accept with its provisional row, reseals and quiet drops.
mkdir "$work/assign"
"$java_bin/java" -cp "$work/classes:$gson" org.telegram.messenger.zenlygram.tasks.cloud.TasksAssignTests \
    "$test_dir/sqlite_bridge.py" "$work/assign"
"$java_bin/java" -cp "$work/classes" org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudCleanupTests "$work/cleanup"
"$java_bin/java" -cp "$work/classes:$gson" org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudIdentityGateTests
# TasksCutOverParityTests (own JVM): a completed copy retires the device board only while the copy is in the account's
# store on this device (I1 after a logout purge, iOS parity) and the migration step has checked it against that store
# at its resync generation (RES-CX1-2: a rebind drops the check, a 409 leaves a resnapshot pending), with the store
# facts of a re-login on the real ledger (the engine's production read, TasksCloudEngine.cutOverStoreFacts).
# RES-CX2-2: the Device archive's lines follow that I1 at each stage.
mkdir "$work/parity"
"$java_bin/java" -cp "$work/classes:$gson" org.telegram.messenger.zenlygram.tasks.cloud.TasksCutOverParityTests \
    "$test_dir/sqlite_bridge.py" "$work/parity"
# TasksApiSendGateTests: the real TasksApi on a queued Pro worker never signs or sends a request whose consent gate
# refuses at send (a withdrawal while it waited). Own classes: TasksApi with the transport suite's compile-only stubs.
mkdir "$work/sendgate"
pro="$android_dir/TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro"
"$java_bin/javac" --release 8 -encoding UTF-8 -cp "$gson" -d "$work/sendgate" \
    "$cloud"/TasksWire*.java "$cloud/TasksRealtimeWire.java" "$cloud/TasksCloudRemote.java" "$cloud/TasksCloudPolicy.java" \
    "$pro/TasksApi.java" "$pro/ProTransport.java" "$pro/ProMonetization.java" "$pro/ProPolicy.java" "$pro/ProCrypto.java" \
    "$android_dir/zenlygram-tests/pro/transport/json-stub/org/json/JSONObject.java" \
    "$android_dir/zenlygram-tests/tasks/cloud/transport/pro-stub/org/telegram/messenger/zenlygram/pro/ZenlyPro.java" \
    "$test_dir/TasksApiSendGateTests.java"
"$java_bin/java" -cp "$work/sendgate:$gson" org.telegram.messenger.zenlygram.pro.TasksApiSendGateTests
