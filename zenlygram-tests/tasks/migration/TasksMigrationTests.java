package org.telegram.messenger.zenlygram.tasks.cloud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host tests of the Tasks R4 automatic migration (design 30 §6.5, §7.1): the normalizer against the shared vectors
 * (tasks-r4-vectors.json, a verbatim copy of the backend fixture), the R4 migration plan, and the pure coordinator step
 * TasksAutoMigration with in-memory doubles of the local store and the cloud repository. Run through run-tests.sh; no
 * Android SDK, no JUnit. The doubles model exactly the seams the production adapters implement (ZenlyTasksSyncOwner's
 * StoreLocal, TasksCloudRepository.MigrationCloud); the SQL behind them is covered by ../run_tests.py.
 */
public final class TasksMigrationTests {
    private static int checks;
    private static final String SCOPE = "o-faketoken/01jfakeprincipala";
    private static final String PRINCIPAL = "01jfakeprincipala";
    private static final long NOW_MS = 1_792_512_000_000L;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        vectors(args[0]);
        planRules();
        firstCopyAndCompletion();
        resumeAfterReloginResendsTheSameIds();
        existingMappingsSkip();
        scopeChangeSkipsAndKeepsMappings();
        scopeChangeAfterARebindVoidsAndSkips();
        failedEnqueueLeavesClaimedAndWritable();
        withdrawalRaceEnqueuesNothingAndRegrantRemigratesOnce();
        lateErasureResendOnce();
        newCloudStoreRechecksTheCopy();
        logoutKeepsTheDeviceBoardReadableUntilTheCopyIsBack();
        stepStoppedAfterMarkChecked();
        serverClockDecidesTheCopysTimes();
        startedTurnIsNeverAbandoned();
        System.out.println("Tasks migration (normalizer vectors, plan, coordinator): " + checks + " checks passed");
    }

    // ---- N1–N13 ----

    private static void vectors(String path) throws Exception {
        JsonObject root;
        try (Reader reader = new InputStreamReader(Files.newInputStream(Paths.get(path)), StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        int android = 0;
        Set<String> groups = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray("normalization")) {
            JsonObject vector = element.getAsJsonObject();
            String id = vector.get("id").getAsString();
            if (vector.has("platform") && "ios".equals(vector.get("platform").getAsString())) continue;
            android++;
            groups.add(id.replaceAll("[a-z]$", ""));
            JsonObject input = vector.getAsJsonObject("input");
            JsonObject expected = vector.getAsJsonObject("expected");
            TasksMigrationNormalizer normalizer = new TasksMigrationNormalizer(vector.get("locale").getAsString());
            long now = longOr(input, "now", 0);
            int index = (int) longOr(input, "index", 0);
            Map<String, Object> actual = new LinkedHashMap<>();
            if ("list".equals(input.get("kind").getAsString())) {
                String local = input.get("local_id").getAsString();
                boolean inbox = TasksMigrationNormalizer.isCloudInbox(local);
                actual.put("cloud_inbox", inbox);
                if (!inbox) {
                    TasksMigrationNormalizer.List list = normalizer.list(input.get("title").getAsString(), 0, index, now);
                    actual.put("title", list.title);
                    actual.put("sort_order", (long) list.sortOrder);
                }
            } else {
                ZenlyTaskModel.Task task = new ZenlyTaskModel.Task("t", "inbox", input.get("title").getAsString(),
                        stringOr(input, "description"), nullableLong(input, "due_at_ms"), (int) longOr(input, "priority", 0),
                        input.has("is_completed") && input.get("is_completed").getAsBoolean(), nullableLong(input, "completed_at_ms"),
                        longOr(input, "created_at_ms", 0), 0);
                TasksMigrationNormalizer.Task normalized = normalizer.task(task, index, now);
                TasksWire.Content content = normalized.content(null);
                actual.put("title", content.title);
                actual.put("description", content.description);
                actual.put("due_at", content.dueAt);
                actual.put("priority", (long) content.priority);
                actual.put("sort_order", (long) content.sortOrder);
                actual.put("created_at", normalized.createdAt);
                actual.put("is_completed", normalized.completed);
                actual.put("completed_at", normalized.completedAt);
                actual.put("annotation", content.annotation);
                // Every normalized row is a valid create (sealing rules included).
                check(normalized.create(TasksWire.newId(), null) != null, id + ": sealable create");
            }
            for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
                Object want = value(entry.getValue());
                Object got = actual.get(entry.getKey());
                check(want == null ? got == null : want.equals(got), id + ": " + entry.getKey() + " expected " + abbreviate(want) + " got " + abbreviate(got));
            }
        }
        check(android >= 30, "android vectors run: " + android);
        check(groups.containsAll(Arrays.asList("N1", "N2", "N3", "N4", "N5", "N6", "N7", "N8", "N9", "N11", "N13")), "every Android group covered");
        check(TasksMigrationNormalizer.deadline(999L) == null && TasksMigrationNormalizer.deadline(-1000L) == null,
                "a deadline that floors to <= 0 s is null");
        check(TasksMigrationNormalizer.localeFor("uz-UZ").equals("uz") && TasksMigrationNormalizer.localeFor("ru").equals("ru")
                && TasksMigrationNormalizer.localeFor("de").equals("en") && TasksMigrationNormalizer.localeFor(null).equals("en"), "record locale");
    }

    private static Object value(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.getAsJsonPrimitive().isBoolean()) return element.getAsBoolean();
        if (element.getAsJsonPrimitive().isNumber()) return element.getAsLong();
        return element.getAsString();
    }

    private static long longOr(JsonObject object, String key, long fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : fallback;
    }

    private static Long nullableLong(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : null;
    }

    private static String stringOr(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() > 40 ? text.substring(0, 40) + "…(" + text.length() + ")" : text;
    }

    // ---- plan ----

    private static void planRules() {
        Fixture f = new Fixture();
        TasksAutoMigration.MigrationPlan plan = TasksAutoMigration.migrationPlan(f.local.snapshot(SCOPE), new TasksMigrationNormalizer("uz"),
                Collections.<String>emptySet(), Collections.<String>emptySet(), Collections.<String>emptySet(), 0, NOW_MS, false);
        check(plan.plan.operations.size() == 4, "1 list + 3 tasks, no list for the inbox");
        TasksWire.Operation.ListCreate list = (TasksWire.Operation.ListCreate) plan.plan.operations.get(0);
        check(list.title.equals("Ish") && list.sortOrder == 0, "folder normalized (trimmed), sort index * 1024");
        Map<String, TasksWire.Operation.TaskCreate> tasks = new HashMap<>();
        for (int i = 0; i < plan.plan.operations.size(); i++) {
            if (plan.plan.operations.get(i) instanceof TasksWire.Operation.TaskCreate) {
                TasksWire.Operation.TaskCreate create = (TasksWire.Operation.TaskCreate) plan.plan.operations.get(i);
                tasks.put(create.content.title, create);
            }
        }
        check(tasks.get("Inbox item").content.listId == null, "inbox task -> Cloud Inbox (list_id null)");
        check(tasks.get("Nomsiz vazifa") != null && tasks.get("Nomsiz vazifa").content.description == null, "untitled placeholder (record locale), '' -> null");
        check(tasks.get("Second").content.listId.equals(list.listId) && tasks.get("Second").content.sortOrder == 1024, "task in its list, index 1 -> 1024");
        check(tasks.get("Second").content.priority == 4 && tasks.get("Second").content.annotation == null, "priority clamped, never an annotation");
        check(plan.plan.mappings.size() == 4 && plan.lists == 1 && plan.tasks == 3 && plan.keptLocal == 0, "mappings for every create");
        for (ZenlyTaskImport.Mapping mapping : plan.plan.mappings) {
            check(!"inbox".equals(mapping.localKey) && !f.local.incarnations.get("inbox").equals(mapping.localKey), "no mapping for the inbox folder");
        }
        check(plan.mappedStatuses.isEmpty() && plan.mappedEnqueueable == 0, "a first plan has no mapped rows");
        TasksAutoMigration.MigrationPlan mappedOnly = TasksAutoMigration.migrationPlan(f.local.snapshot(SCOPE), new TasksMigrationNormalizer("uz"),
                Collections.<String>emptySet(), Collections.<String>emptySet(), Collections.<String>emptySet(), 0, NOW_MS, true);
        check(mappedOnly.plan.operations.isEmpty(), "mappedOnly never enqueues unmapped rows");
    }

    // ---- coordinator ----

    private static void firstCopyAndCompletion() {
        Fixture f = new Fixture();
        ZenlyTaskImport.MigrationRecord record = f.step();
        check(record.state.equals("enqueued") && record.scope.equals(SCOPE) && record.locale.equals("uz"), "claimed then enqueued");
        check(record.lists == 1 && record.tasks == 3 && record.keptLocal == 0 && record.enqueuedAt != null, "counts recorded");
        check(f.cloud.outbox.size() == 4 && f.cloud.origins.equals(Collections.singleton(TasksCloudSchema.ORIGIN_MIGRATION)),
                "every create enqueued with origin migration");
        check(f.cloud.epochs.equals(Collections.singletonList(7L)), "with the captured consent epoch");
        check(f.fence.raised == 1 && f.fence.lowered == 1 && !f.fence.up, "the local step ran under the write fence");
        check(f.local.snapshotUnderFence && f.local.mappingsUnderFence, "snapshot and mappings inside the fence");
        check(f.sink.events.equals(Collections.singletonList("migration:started")), "started at the claim");
        check(cutOver(record, f, 0).equals(new TasksCutOverProbe("ARCHIVE(MIGRATING)").value), "archive(migrating) while enqueued");

        int before = f.cloud.outbox.size();
        record = f.step();
        check(record.state.equals("enqueued") && f.cloud.outbox.size() == before && f.cloud.enqueued.size() == 4, "pending rows are not enqueued twice");

        f.cloud.deliver(f.local, true);
        record = f.step();
        check(record.state.equals("completed") && record.completedAt != null && record.verifiedGeneration == 0, "completed when every row is in the cloud");
        check(f.sink.events.contains("migration:completed:1/3/0"), "completed event with counts");
        check(TasksCutOver.evaluate(TasksCloudGate.Permission.ALLOWED, TasksCutOver.Record.COMPLETED, SCOPE, SCOPE, 0) == TasksCutOver.RETIRED,
                "retired with no new rows");
        f.step();
        check(Collections.frequency(f.sink.events, "migration:completed:1/3/0") == 1, "completed emitted once");
    }

    private static void resumeAfterReloginResendsTheSameIds() {
        Fixture f = new Fixture();
        ZenlyTaskImport.MigrationRecord queued = f.step();
        Set<String> first = new HashSet<>(f.cloud.outbox.keySet());
        for (String id : first) f.local.attempt(id, false, 0); // handed to the network, outcome unknown
        f.cloud = new FakeCloud(); // logout wiped the cloud store; re-login under the same scope
        // RES-CX2-2: the purged, unbound store holds none of the queued creates (its unsent counts read {0, 0}, so a progress
        // line would read "N of N"): the archive says the Cloud copy returns instead.
        check(queued.state.equals("enqueued") && !ownerOwnCopy(queued, f, TasksCloudGate.Permission.WAITING, null),
                "re-login: the queued copy is not in the purged store");
        expectOwnerLine("returns", queued, f, TasksCloudGate.Permission.WAITING, null, null, "re-login over the purged store");
        f.cloud.shadowComplete = true;
        ZenlyTaskImport.MigrationRecord record = f.step();
        check(record.state.equals("enqueued") && f.cloud.outbox.keySet().equals(first), "re-login resends the SAME mutation ids");
        check(f.local.mappings.size() == 4, "no new mapping, never a duplicate");
        check(ownerOwnCopy(record, f, TasksCloudGate.Permission.ALLOWED, SCOPE), "the re-enqueued creates are the copy again");
        expectOwnerLine("progress", record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE, "the step re-enqueued the same ids: the progress line again");
    }

    private static void existingMappingsSkip() {
        Fixture f = new Fixture();
        f.local.mappings.put("bare-principal/task/t9", new ZenlyTaskImport.Mapping("task", "t9", TasksWire.newId(), "m-old", 0, null));
        ZenlyTaskImport.MigrationRecord record = f.step();
        check(record.state.equals("skipped") && "existing_mappings".equals(record.reason), "explicitly copied rows: skipped");
        check(f.cloud.outbox.isEmpty() && f.fence.raised == 0 && f.sink.events.isEmpty(), "nothing enqueued, no fence, no started");
    }

    private static void scopeChangeSkipsAndKeepsMappings() {
        Fixture f = new Fixture();
        f.step();
        int mappings = f.local.mappings.size();
        f.cloud.scope = "o-faketoken/01jotherprincipal";
        ZenlyTaskImport.MigrationRecord record = f.step();
        check(record.state.equals("skipped") && "scope_changed".equals(record.reason), "another server account: skipped(scope_changed)");
        check(f.local.mappings.size() == mappings, "mappings kept");
        check(TasksCutOver.evaluate(TasksCloudGate.Permission.ALLOWED, TasksCutOver.Record.SKIPPED, SCOPE, f.cloud.scope, 0) == TasksCutOver.NONE,
                "writable afterwards");
    }

    /**
     * The same after the fixed rebind (TasksCloudLedger.bindPrincipal drops migration_checked): the first step on the
     * rebound store voids the verifications once more before it records skipped(scope_changed); mappings are kept.
     */
    private static void scopeChangeAfterARebindVoidsAndSkips() {
        Fixture f = new Fixture();
        f.step();
        f.cloud.deliver(f.local, true);
        check(f.step().state.equals("completed"), "rebind: completed under the first scope");
        int mappings = f.local.mappings.size();
        f.cloud.scope = "o-faketoken/01jotherprincipal";
        f.cloud.checked = false; // the rebind cleared the store's check
        ZenlyTaskImport.MigrationRecord record = f.step();
        check(record.state.equals("skipped") && "scope_changed".equals(record.reason), "rebind: another server account -> skipped(scope_changed)");
        check(f.local.mappings.size() == mappings, "rebind: mappings kept");
        check(f.local.invalidations == 2 && f.cloud.checks == 2 && f.cloud.checked, "rebind: the rebound store is checked again");
        check(record.verifiedGeneration == -1, "rebind: the record's verification voided");
        for (long[] p : f.local.provenance.values()) check(p[2] == -1, "rebind: every attempt row voided");
        check(TasksCutOver.evaluate(TasksCloudGate.Permission.ALLOWED, TasksCutOver.Record.SKIPPED, SCOPE, f.cloud.scope, 0) == TasksCutOver.NONE,
                "rebind: writable afterwards");
    }

    private static void failedEnqueueLeavesClaimedAndWritable() {
        Fixture f = new Fixture();
        f.cloud.failEnqueue = true;
        try {
            f.step();
            check(false, "the failed step rethrows");
        } catch (TasksCloudError e) {
            check(e.code == TasksCloudError.Code.STORAGE, "storage failure");
        }
        ZenlyTaskImport.MigrationRecord record = f.local.record;
        check(record.state.equals("claimed"), "the record stays claimed");
        check(!f.fence.up && TasksSyncPolicy.writeState(f.fence.up, TasksCutOver.evaluate(TasksCloudGate.Permission.ALLOWED,
                TasksCutOver.Record.CLAIMED, SCOPE, SCOPE, 0)) == ZenlyTaskModel.WriteState.OPEN, "the board is writable (row 7, fence down)");
        check(f.sink.events.contains("migration:failed:storage") && f.sink.events.contains("error:tasks_migration:storage"), "failed + error");
        Set<String> minted = new HashSet<>();
        for (ZenlyTaskImport.Mapping mapping : f.local.mappings.values()) minted.add(mapping.mutationId);
        f.cloud.failEnqueue = false;
        record = f.step();
        check(record.state.equals("enqueued") && f.cloud.outbox.keySet().equals(minted), "the rerun enqueues the ids minted before the failure");
    }

    private static void withdrawalRaceEnqueuesNothingAndRegrantRemigratesOnce() {
        Fixture f = new Fixture();
        f.cloud.beforeEnqueue = () -> f.cloud.epoch++; // a withdrawal between the mappings and the enqueue
        try {
            f.step();
        } catch (TasksCloudError e) {
            check(e.code == TasksCloudError.Code.CONSENT_WITHDRAWN, "the enqueue refuses the changed epoch");
        }
        check(f.cloud.outbox.isEmpty() && f.local.record.state.equals("claimed"), "nothing enqueued, still claimed");
        check(!f.sink.events.contains("migration:failed:consent_withdrawn"), "a withdrawal is not a failure");
        // TasksCloudRepository.onConsentWithdrawn -> ZenlyTasksStore.releaseMigration(withdrawn).
        f.local.release("withdrawn");
        f.cloud.beforeEnqueue = null;
        check(TasksCutOver.evaluate(TasksCloudGate.Permission.FORBIDDEN, TasksCutOver.Record.RELEASED, SCOPE, SCOPE, 0) == TasksCutOver.NONE,
                "released: writable");
        ZenlyTaskImport.MigrationRecord record = f.step();
        check(record.state.equals("enqueued") && f.cloud.outbox.size() == 4, "re-grant re-migrates");
        Set<String> rows = new HashSet<>();
        for (TasksWire.Operation operation : f.cloud.outbox.values()) {
            rows.add(operation instanceof TasksWire.Operation.TaskCreate ? ((TasksWire.Operation.TaskCreate) operation).content.title
                    : "list:" + ((TasksWire.Operation.ListCreate) operation).title);
        }
        check(rows.size() == 4, "each local row exactly once: no duplicates");
        check(Collections.frequency(f.sink.events, "migration:started") == 2, "started again at the re-claim");
    }

    private static void lateErasureResendOnce() {
        for (boolean replay : new boolean[]{true, false}) {
            Fixture f = new Fixture();
            f.step();
            Set<String> ids = new HashSet<>(f.cloud.outbox.keySet());
            f.cloud.deliver(f.local, true);
            ZenlyTaskImport.MigrationRecord done = f.step();
            check(done.state.equals("completed"), "completed at generation 0");
            // A late erasure on the server: 409 resync, the shadow no longer holds the rows.
            f.cloud.shadow.clear();
            f.cloud.resyncGeneration = 1;
            check(ownerCutOver(done, f, TasksCloudGate.Permission.WAITING, SCOPE, null).reason == TasksCutOver.Reason.MIGRATING,
                    "a 409 since the completion: the archive until the step re-checks (no step runs while P is WAITING)");
            ZenlyTaskImport.MigrationRecord record = f.step();
            check(record.state.equals("enqueued"), "a completed copy re-checked after a resync returns to enqueued (board not retired)");
            check(f.cloud.outbox.keySet().equals(ids), "resent once with the reserved ids");
            for (String id : ids) check(f.local.provenance.get(id)[1] == 0 && f.local.provenance.get(id)[2] == 1, "re-verified at generation 1");
            check(TasksCutOver.evaluate(TasksCloudGate.Permission.ALLOWED, TasksCutOver.Record.ENQUEUED, SCOPE, SCOPE, 0).isReadOnly(),
                    "archive while resending");
            f.cloud.deliver(f.local, !replay);
            record = f.step();
            check(record.state.equals("completed") && record.verifiedGeneration == 1, (replay ? "replay" : "fresh execution") + " completes");
            check(ownerCutOver(record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE) == TasksCutOver.RETIRED
                    && ownerCutOver(record, f, TasksCloudGate.Permission.WAITING, SCOPE, null) == TasksCutOver.RETIRED,
                    (replay ? "replay" : "fresh execution") + ": re-checked at the store's generation 1 -> retired (ALLOWED and WAITING)");
            TasksAutoMigration.MigrationPlan plan = TasksAutoMigration.migrationPlan(f.local.snapshot(SCOPE), new TasksMigrationNormalizer("uz"),
                    f.cloud.shadow, f.cloud.outbox.keySet(), Collections.<String>emptySet(), 1, NOW_MS, true);
            TasksCloudImport.RowStatus expected = replay ? TasksCloudImport.RowStatus.REMOVED_IN_CLOUD : TasksCloudImport.RowStatus.ALREADY_IMPORTED;
            check(!plan.mappedStatuses.isEmpty() && new HashSet<>(plan.mappedStatuses).equals(Collections.singleton(expected)),
                    (replay ? "replay -> REMOVED_IN_CLOUD" : "fresh -> ALREADY_IMPORTED"));
            check(plan.plan.operations.isEmpty(), "never resent a second time");
        }
    }

    // ---- review fixes (FIX-A3) ----

    /**
     * A completed copy is verified again against a NEW cloud store (review: TasksAutoMigration.java:168). Logout wiped
     * the store and the same account signs in again; the new store reads resync generation 0 like the old one. Its first
     * step voids the old verification once: rows the store holds again confirm in place; rows it lacks (a withdrawal on
     * another device erased them with their receipts, or the user had removed them in the cloud) are resent once with
     * their reserved ids while the archive shows. Before the fix the record stayed completed: the board retired over an
     * empty cloud and nothing was uploaded again.
     */
    private static void newCloudStoreRechecksTheCopy() {
        for (String variant : new String[]{"erased", "removed", "present"}) {
            Fixture f = new Fixture();
            f.step();
            Set<String> ids = new HashSet<>(f.cloud.outbox.keySet());
            f.cloud.deliver(f.local, true);
            ZenlyTaskImport.MigrationRecord done = f.step();
            check(done.state.equals("completed") && done.verifiedGeneration == 0 && cutOver(done, f, 0) == TasksCutOver.RETIRED,
                    variant + ": retired at generation 0");
            Set<String> rows = new HashSet<>(f.cloud.shadow);
            if ("removed".equals(variant)) {
                f.cloud.shadow.clear(); // the user removed every copied row in the cloud; their receipts stay
                check(f.step().state.equals("completed") && f.cloud.outbox.isEmpty(),
                        variant + ": the same store keeps a completed copy as it is (no resync, no new store)");
            }
            f.cloud = new FakeCloud(); // logout wiped the store; the same account signs in again
            f.cloud.shadowComplete = true;
            if ("present".equals(variant)) f.cloud.shadow.addAll(rows);
            ZenlyTaskImport.MigrationRecord record = f.step();
            if ("present".equals(variant)) {
                check(record.state.equals("completed") && record.verifiedGeneration == 0 && done.completedAt.equals(record.completedAt)
                        && f.cloud.outbox.isEmpty(), variant + ": a plain re-login re-verifies in place, nothing resent");
                check(Collections.frequency(f.sink.events, "migration:completed:1/3/0") == 1, variant + ": completed emitted once");
                check(f.local.invalidations == 2 && f.cloud.checks == 1 && f.cloud.checked, variant + ": each store is checked once");
                continue;
            }
            check(record.state.equals("enqueued") && cutOver(record, f, 0).isReadOnly() && cutOver(record, f, 0) != TasksCutOver.RETIRED,
                    variant + ": back to enqueued, the archive shows (not retired)");
            check(f.cloud.outbox.keySet().equals(ids) && f.local.mappings.size() == 4, variant + ": every row resent once with its reserved id");
            for (String id : ids) {
                check(f.local.provenance.get(id)[1] == 0 && f.local.provenance.get(id)[2] == 0, variant + ": re-verified at the new store's generation");
            }
            f.cloud.deliver(f.local, "erased".equals(variant)); // an erased receipt executes anew, a surviving one replays
            record = f.step();
            check(record.state.equals("completed") && record.verifiedGeneration == 0 && cutOver(record, f, 0) == TasksCutOver.RETIRED,
                    variant + ": completed again on the new store");
            f.step();
            check(f.cloud.enqueued.size() == ids.size(), variant + ": never resent a second time");
            check(Collections.frequency(f.sink.events, "migration:completed:1/3/0") == 1, variant + ": completed emitted once");
            check(f.local.invalidations == 2 && f.cloud.checks == 1 && f.cloud.checked, variant + ": each store is checked once");
        }
    }

    /**
     * I1 after a logout purge (FIX-A7; the Android side of iOS FIX-I4, review finding SGTasksCutOver.swift:75): logout
     * wipes the account's cloud store and keeps the completed record and its mappings. Re-login with consent but no link
     * yet, then linked with the first snapshot still pending: the device board stays a readable archive (before the fix it
     * retired over the empty store). The cut-over is the owner's: computed after each step, with I1 read from the store.
     * Once the new store holds the copy again — the rows in its first complete snapshot, or the creates the step re-enqueues
     * when they are gone — and the step has checked it there (RES-CX1-2: not on the snapshot alone), it retires. The
     * archive's lines follow that I1 (RES-CX2-2): "the Cloud copy returns" while the copy is not in the store, the progress
     * line while the re-enqueued creates go, neither once retired.
     */
    private static void logoutKeepsTheDeviceBoardReadableUntilTheCopyIsBack() {
        for (String variant : new String[]{"present", "erased"}) {
            Fixture f = new Fixture();
            f.step();
            f.cloud.deliver(f.local, true);
            ZenlyTaskImport.MigrationRecord record = f.step();
            check(record.state.equals("completed") && ownerCutOver(record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE) == TasksCutOver.RETIRED,
                    variant + ": retired while the store holds the copy");
            expectOwnerLine("none", record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE, variant + ": retired, neither archive line");
            Set<String> rows = new HashSet<>(f.cloud.shadow);

            f.cloud = new FakeCloud(); // logout wiped the store: unbound, no snapshot, nothing queued
            TasksCutOver relogin = ownerCutOver(record, f, TasksCloudGate.Permission.WAITING, null, null);
            check(relogin.kind == TasksCutOver.Kind.ARCHIVE && relogin.reason == TasksCutOver.Reason.MIGRATING,
                    variant + ": re-login before the link -> archive (the device rows readable), not retired");
            // RES-CX2-2: a confirmed copy is not being copied, and this store would read "N of N": the Cloud copy returns.
            expectOwnerLine("returns", record, f, TasksCloudGate.Permission.WAITING, null, null, variant + ": re-login before the link");
            check(f.step().state.equals("completed") && f.cloud.outbox.isEmpty(), variant + ": no step without a complete snapshot");
            check(ownerCutOver(record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE).reason == TasksCutOver.Reason.MIGRATING,
                    variant + ": linked, the store bound, its snapshot pending -> still the archive");
            expectOwnerLine("returns", record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE, variant + ": linked, its snapshot pending");

            f.cloud.shadowComplete = true; // the first complete snapshot of the record's scope
            if ("present".equals(variant)) f.cloud.shadow.addAll(rows);
            // The snapshot landed; the step has not checked the copy against this new store yet (R-A3: none runs while P
            // is WAITING): still the archive for both P.
            check(ownerCutOver(record, f, TasksCloudGate.Permission.WAITING, SCOPE, null).reason == TasksCutOver.Reason.MIGRATING
                    && ownerCutOver(record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE).reason == TasksCutOver.Reason.MIGRATING,
                    variant + ": the new store's snapshot is complete, the copy not checked against it -> still the archive");
            expectOwnerLine("returns", record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE, variant + ": complete, the copy not checked yet");
            record = f.step();
            TasksCutOver afterStep = ownerCutOver(record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE);
            if ("present".equals(variant)) {
                check(record.state.equals("completed") && afterStep == TasksCutOver.RETIRED, variant + ": the rows are back in the store -> retired");
                expectOwnerLine("none", record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE, variant + ": retired, neither archive line");
                continue;
            }
            check(record.state.equals("enqueued") && f.cloud.outbox.size() == 4 && afterStep.reason == TasksCutOver.Reason.MIGRATING,
                    variant + ": the step re-enqueues the missing rows -> the archive while they go");
            expectOwnerLine("progress", record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE, variant + ": the re-enqueued creates go");
            f.cloud.deliver(f.local, true);
            record = f.step();
            check(record.state.equals("completed") && ownerCutOver(record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE) == TasksCutOver.RETIRED,
                    variant + ": confirmed again in the new store -> retired");
            expectOwnerLine("none", record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE, variant + ": retired again, neither archive line");
        }
    }

    /**
     * The step stopped between its check of a new store and the verify's record write (a throw or a process death right
     * after markChecked; iOS 7b): the store is marked, the record's verification void (-1), so the copy is not confirmed
     * against this store yet and the board stays the archive (a marker alone would retire it). The next step re-checks
     * and completes at the store's generation.
     */
    private static void stepStoppedAfterMarkChecked() {
        Fixture f = new Fixture();
        f.step();
        f.cloud.deliver(f.local, true);
        check(f.step().state.equals("completed"), "stopped step: completed at generation 0");
        Set<String> rows = new HashSet<>(f.cloud.shadow);
        f.cloud = new FakeCloud(); // logout wiped the store; the same account is linked again and its snapshot is complete
        f.cloud.shadowComplete = true;
        f.cloud.shadow.addAll(rows);
        f.local.invalidateVerification(); // exactly TasksAutoMigration.run before the verify
        f.cloud.markChecked();
        check(f.local.record.verifiedGeneration == -1 && f.cloud.checked, "stopped step: the store marked, the verification void");
        check(ownerCutOver(f.local.record, f, TasksCloudGate.Permission.WAITING, SCOPE, null).reason == TasksCutOver.Reason.MIGRATING
                && ownerCutOver(f.local.record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE).reason == TasksCutOver.Reason.MIGRATING,
                "stopped step: marked but not verified against this store -> still the archive (not retired)");
        ZenlyTaskImport.MigrationRecord record = f.step();
        check(record.state.equals("completed") && record.verifiedGeneration == 0 && f.cloud.outbox.isEmpty() && f.cloud.checks == 1,
                "stopped step: the next step completes at generation 0 without a second check or a resend");
        check(ownerCutOver(record, f, TasksCloudGate.Permission.ALLOWED, SCOPE, SCOPE) == TasksCutOver.RETIRED
                && ownerCutOver(record, f, TasksCloudGate.Permission.WAITING, SCOPE, null) == TasksCutOver.RETIRED, "stopped step: then retired");
    }

    /**
     * ZenlyTasksSyncOwner's cut-over (U = 0) with I1 read from the fixture's store (its bound scope, shadow, queued creates,
     * the step's check of it and its resync generation) and the record's verified generation.
     */
    private static TasksCutOver ownerCutOver(ZenlyTaskImport.MigrationRecord record, Fixture f, TasksCloudGate.Permission p, String boundScope,
                                             String currentScope) {
        TasksCutOver.Record state = TasksCutOver.Record.fromWire(record.state);
        return TasksCutOver.evaluate(p, state, record.scope, currentScope, 0, ownerOwnCopy(record, f, p, boundScope));
    }

    /** That cut-over's I1: the record's copy in the fixture's store, bound to {@code boundScope} (null: unbound). */
    private static boolean ownerOwnCopy(ZenlyTaskImport.MigrationRecord record, Fixture f, TasksCloudGate.Permission p, String boundScope) {
        final TasksCutOver.StoreFacts store = new TasksCutOver.StoreFacts(boundScope, f.cloud.shadowComplete, f.cloud.outbox.size(), f.cloud.checked,
                f.cloud.resyncGeneration);
        return TasksCutOver.ownCopyOnDevice(p, TasksCutOver.Record.fromWire(record.state), record.scope, record.verifiedGeneration, () -> store);
    }

    /**
     * The Device archive's line for the owner's cut-over and its I1 (RES-CX2-2, the activity's rule): "progress" (an
     * enqueued copy in this store), "returns" (the copy is not in this store: the Cloud copy returns once the account is
     * confirmed and synced), "none", or "both" (never).
     */
    private static void expectOwnerLine(String expected, ZenlyTaskImport.MigrationRecord record, Fixture f, TasksCloudGate.Permission p,
                                        String boundScope, String currentScope, String what) {
        TasksCutOver.Record state = TasksCutOver.Record.fromWire(record.state);
        boolean own = ownerOwnCopy(record, f, p, boundScope);
        TasksCutOver cutOver = TasksCutOver.evaluate(p, state, record.scope, currentScope, 0, own);
        boolean progress = TasksCutOver.archiveShowsProgress(cutOver, state, own), returns = TasksCutOver.archiveAwaitsCopy(cutOver, own);
        String line = progress && returns ? "both" : progress ? "progress" : returns ? "returns" : "none";
        check(expected.equals(line), what + " (" + state + ", " + p + ", own copy " + own + ", " + cutOver + "): " + expected + " expected, got " + line);
    }

    /**
     * created_at/completed_at are checked against the server's time (review: ZenlyTasksSyncOwner.java:345). The device
     * clock runs an hour fast; a task created and completed a minute ago (device time) is in the server's future, and
     * the server refuses a whole batch for one such item (422: every entry INVALID). Against the server estimate both
     * times are unknown (null, the server stamps its own); older times stay. A resend two hours later rebuilds identical
     * bytes, because the copy keeps the time of its first enqueue (the server hashes both fields).
     */
    private static void serverClockDecidesTheCopysTimes() {
        long server = NOW_MS - 3_600_000L, fresh = NOW_MS - 60_000L;
        Fixture f = new Fixture();
        f.local.tasks.add(new ZenlyTaskModel.Task("t-fresh", "work", "Fresh", "", null, 0, true, fresh, fresh, fresh));
        f.cloud.serverNowMillis = server;
        ZenlyTaskImport.MigrationRecord record = f.step(); // the caller's (device) clock: NOW_MS
        TasksWire.Operation.TaskCreate created = taskCreate(f.cloud.outbox.values(), "Fresh");
        check(created.createdAt == null && created.isCompleted && created.completedAt == null, "a device time in the server's future is unknown (null)");
        check(TasksWire.instant((NOW_MS - 7_000_000L) / 1000).equals(taskCreate(f.cloud.outbox.values(), "Second").createdAt),
                "a time before the server's now stays");
        check(record.claimedAt == server / 1000 && record.enqueuedAt == server / 1000, "the record's times are the server's");
        Map<String, TasksWire.Operation> first = new LinkedHashMap<>(f.cloud.outbox);
        // Logout lost the outbox; two hours later the same rows are rebuilt for their resend.
        f.cloud = new FakeCloud();
        f.cloud.shadowComplete = true;
        f.cloud.serverNowMillis = NOW_MS + 3_600_000L;
        check(f.step().state.equals("enqueued") && f.cloud.outbox.keySet().equals(first.keySet()), "the same reserved ids");
        for (Map.Entry<String, TasksWire.Operation> entry : first.entrySet()) {
            check(Arrays.equals(TasksWire.Mutation.seal(PRINCIPAL, entry.getKey(), entry.getValue()).body(),
                    TasksWire.Mutation.seal(PRINCIPAL, entry.getKey(), f.cloud.outbox.get(entry.getKey())).body()),
                    "a resend rebuilds identical bytes");
        }
        // No server estimate (0): the caller's clock decides.
        Fixture g = new Fixture();
        g.local.tasks.add(new ZenlyTaskModel.Task("t-fresh", "work", "Fresh", "", null, 0, true, fresh, fresh, fresh));
        g.cloud.serverNowMillis = 0;
        ZenlyTaskImport.MigrationRecord fallback = g.step();
        check(fallback.claimedAt == NOW_MS / 1000 && taskCreate(g.cloud.outbox.values(), "Fresh").createdAt != null,
                "without an estimate the caller's clock decides");
    }

    /**
     * The blocking cloud seam never abandons a turn that has started (review: TasksCloudRepository.java:1084). A migration
     * enqueue that outlives the wait (a large board, a slow device) still commits; the step used to report NOT_READY,
     * lower its fence on a still-claimed record and reopen the board while the creates landed with the content from
     * before an edit made then. Now the started turn is waited for; a turn still queued at the deadline is dropped and
     * never runs.
     */
    private static void startedTurnIsNeverAbandoned() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
            final AtomicBoolean committed = new AtomicBoolean();
            Thread slow = new Thread(() -> {
                try {
                    if (started.await(10, TimeUnit.SECONDS)) Thread.sleep(600); // the turn outlives the 200 ms wait
                } catch (InterruptedException ignored) {
                    // Finish anyway.
                }
                finish.countDown();
            });
            slow.setDaemon(true);
            slow.start();
            Integer value = TasksAutoMigration.awaitTurn(executor, () -> {
                started.countDown();
                finish.await();
                committed.set(true);
                return 42;
            }, 200);
            slow.join();
            check(value == 42 && committed.get(), "a started turn is waited for to its end: its outcome, never NOT_READY");

            final CountDownLatch blocker = new CountDownLatch(1);
            executor.submit(() -> {
                blocker.await();
                return null;
            });
            final AtomicBoolean ran = new AtomicBoolean();
            try {
                TasksAutoMigration.awaitTurn(executor, () -> {
                    ran.set(true);
                    return 1;
                }, 100);
                check(false, "a turn still queued at the deadline is NOT_READY");
            } catch (TasksCloudError e) {
                check(e.code == TasksCloudError.Code.NOT_READY, "dropped: NOT_READY");
            }
            blocker.countDown();
            executor.submit(() -> null).get(); // FIFO: every earlier turn has run or been skipped
            check(!ran.get(), "a dropped turn never runs");

            try {
                TasksAutoMigration.awaitTurn(executor, () -> {
                    throw new TasksCloudError(TasksCloudError.Code.STORAGE);
                }, 1_000);
                check(false, "the turn's failure is rethrown");
            } catch (ExecutionException e) {
                check(e.getCause() instanceof TasksCloudError && ((TasksCloudError) e.getCause()).code == TasksCloudError.Code.STORAGE,
                        "a failure inside the turn arrives as its ExecutionException");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static TasksWire.Operation.TaskCreate taskCreate(Iterable<TasksWire.Operation> operations, String title) {
        for (TasksWire.Operation operation : operations) {
            if (operation instanceof TasksWire.Operation.TaskCreate && ((TasksWire.Operation.TaskCreate) operation).content.title.equals(title)) {
                return (TasksWire.Operation.TaskCreate) operation;
            }
        }
        throw new AssertionError("no create titled " + title);
    }

    private static TasksCutOver cutOver(ZenlyTaskImport.MigrationRecord record, Fixture f, int unmapped) {
        return TasksCutOver.evaluate(TasksCloudGate.Permission.ALLOWED, TasksCutOver.Record.fromWire(record.state), record.scope, f.cloud.scope, unmapped);
    }

    /** TasksCutOver has no public constructor for ARCHIVE(MIGRATING): read it from the table itself. */
    private static final class TasksCutOverProbe {
        final TasksCutOver value;

        TasksCutOverProbe(String name) {
            value = TasksCutOver.evaluate(TasksCloudGate.Permission.ALLOWED, TasksCutOver.Record.ENQUEUED, null, null, 0);
            check(value.toString().equals(name), "probe " + name);
        }
    }

    // ---- doubles ----

    private static final class Fixture {
        final RecordingFence fence = new RecordingFence();
        final FakeLocal local = new FakeLocal(fence);
        final RecordingSink sink = new RecordingSink();
        FakeCloud cloud = new FakeCloud();

        Fixture() {
            cloud.shadowComplete = true;
            TasksAnalyticsSink.Holder.set(sink);
        }

        ZenlyTaskImport.MigrationRecord step() {
            TasksAnalyticsSink.Holder.set(sink);
            return TasksAutoMigration.step(0, local, cloud, fence, "uz", NOW_MS);
        }
    }

    private static final class RecordingFence implements TasksAutoMigration.Fence {
        int raised, lowered;
        boolean up;

        @Override public void raise(int account) {
            raised++;
            up = true;
        }

        @Override public void lower(int account) {
            lowered++;
            up = false;
        }
    }

    private static final class RecordingSink implements TasksAnalyticsSink {
        final List<String> events = new ArrayList<>();

        @Override public void taskCreated(int account, boolean cloud, boolean hasDue, int priority, boolean inList) { events.add("taskCreated"); }

        @Override public void taskCompleted(int account, boolean cloud, boolean reopened) { }

        @Override public void taskDeleted(int account, boolean cloud) { }

        @Override public void listCreated(int account, boolean cloud) { events.add("listCreated"); }

        @Override public void listDeleted(int account, boolean cloud, int tasks) { }

        @Override public void shared(int account, String action) { }

        @Override public void migration(int account, String phase, Integer lists, Integer tasks, Integer skipped, Long durationS, String errorCode) {
            if (MIGRATION_COMPLETED.equals(phase)) events.add("migration:completed:" + lists + "/" + tasks + "/" + skipped);
            else if (MIGRATION_FAILED.equals(phase)) events.add("migration:failed:" + errorCode);
            else events.add("migration:" + phase);
        }

        @Override public void syncIssue(int account, String kind) { }

        @Override public void error(int account, String domain, String code) { events.add("error:" + domain + ":" + code); }
    }

    /** The account's local database: inbox + "  Ish " folder, three tasks; mappings with provenance; the record. */
    static final class FakeLocal implements TasksAutoMigration.Local {
        final RecordingFence fence;
        final List<ZenlyTaskModel.Folder> folders = new ArrayList<>();
        final Map<String, String> incarnations = new LinkedHashMap<>();
        final List<ZenlyTaskModel.Task> tasks = new ArrayList<>();
        /** scope/entity/localKey -> mapping (identity only). */
        final Map<String, ZenlyTaskImport.Mapping> mappings = new LinkedHashMap<>();
        /** mutation id -> {attempted, acknowledged, verified generation}. */
        final Map<String, long[]> provenance = new HashMap<>();
        ZenlyTaskImport.MigrationRecord record;
        boolean snapshotUnderFence, mappingsUnderFence;
        int invalidations;

        FakeLocal(RecordingFence fence) {
            this.fence = fence;
            folders.add(new ZenlyTaskModel.Folder("inbox", "", 0));
            folders.add(new ZenlyTaskModel.Folder("work", "  Ish ", NOW_MS - 86_400_000L));
            incarnations.put("inbox", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
            incarnations.put("work", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
            tasks.add(new ZenlyTaskModel.Task("t-inbox", "inbox", "Inbox item", "", null, 0, false, null, NOW_MS - 3_600_000L, NOW_MS));
            tasks.add(new ZenlyTaskModel.Task("t-first", "work", "​", "", 999L, 1, true, NOW_MS - 1000, NOW_MS - 7_200_000L, NOW_MS));
            tasks.add(new ZenlyTaskModel.Task("t-second", "work", "Second", "notes", NOW_MS + 86_400_000L, 7, false, null, NOW_MS - 7_000_000L, NOW_MS));
        }

        ZenlyTaskImport.Snapshot snapshot(String scope) {
            ArrayList<ZenlyTaskImport.Mapping> rows = new ArrayList<>();
            for (Map.Entry<String, ZenlyTaskImport.Mapping> entry : mappings.entrySet()) {
                if (!entry.getKey().startsWith(scope + "/")) continue;
                ZenlyTaskImport.Mapping m = entry.getValue();
                long[] p = provenance.get(m.mutationId);
                rows.add(p == null ? m : new ZenlyTaskImport.Mapping(m.entity, m.localKey, m.cloudId, m.mutationId, m.sortOrder,
                        m.parentCloudId, p[0] != 0, p[1] != 0, p[2]));
            }
            return new ZenlyTaskImport.Snapshot(folders, incarnations, tasks, rows);
        }

        void attempt(String mutationId, boolean acknowledged, long generation) {
            if (!isMapped(mutationId)) return; // non-import ids are no-ops
            long[] p = provenance.get(mutationId);
            if (p == null) provenance.put(mutationId, p = new long[]{1, 0, generation});
            if (acknowledged) p[1] = 1;
        }

        boolean isMapped(String mutationId) {
            for (ZenlyTaskImport.Mapping m : mappings.values()) if (m.mutationId.equals(mutationId)) return true;
            return false;
        }

        void release(String reason) {
            mappings.clear();
            provenance.clear();
            if (record != null) record = record.withState("released", reason);
        }

        @Override public ZenlyTaskImport.MigrationRecord record() { return record; }

        @Override public boolean replaceRecord(ZenlyTaskImport.MigrationRecord expected, ZenlyTaskImport.MigrationRecord next) {
            if (record == null ? expected != null : !record.equals(expected)) return false;
            record = next;
            return true;
        }

        @Override public boolean hasAnyImportMapping() { return !mappings.isEmpty(); }

        @Override public ZenlyTaskImport.Snapshot importSnapshot(String scope) {
            snapshotUnderFence |= fence.up;
            return snapshot(scope);
        }

        @Override public void recordImportMappings(String scope, List<ZenlyTaskImport.Mapping> rows) {
            mappingsUnderFence |= fence.up;
            for (ZenlyTaskImport.Mapping row : rows) {
                String key = scope + "/" + row.entity + "/" + row.localKey;
                ZenlyTaskImport.Mapping existing = mappings.get(key);
                if (existing != null && !existing.sameAs(row)) throw new TasksAutoMigration.Failure("sqlite_invalid");
                if (existing == null) mappings.put(key, row);
            }
        }

        @Override public void markImportReverified(List<String> mutationIds, long generation) {
            check(fence.up, "re-verification only inside the fence");
            for (String id : mutationIds) {
                long[] p = provenance.get(id);
                if (p == null) continue;
                p[1] = 0;
                p[2] = generation;
            }
        }

        /** ZenlyTasksStore.invalidateMigrationVerification: generation -1 for the record and every attempt row. */
        @Override public void invalidateVerification() {
            invalidations++;
            if (record != null) {
                record = new ZenlyTaskImport.MigrationRecord(record.state, record.reason, record.scope, record.locale, record.claimedAt,
                        record.enqueuedAt, record.completedAt, -1, record.lists, record.tasks, record.keptLocal);
            }
            for (long[] p : provenance.values()) p[2] = -1;
        }
    }

    /**
     * The account's cloud repository: complete shadow (kind:id), outbox, consent epoch, resync generation, the server
     * time estimate (default: the device's) and the store's check marker (a new instance is a new database: unchecked).
     */
    static final class FakeCloud implements TasksAutoMigration.Cloud {
        String scope = SCOPE;
        boolean shadowComplete, failEnqueue, checked;
        long epoch = 7, resyncGeneration, serverNowMillis = NOW_MS;
        int checks;
        Runnable beforeEnqueue;
        final Set<String> shadow = new HashSet<>();
        final Map<String, TasksWire.Operation> outbox = new LinkedHashMap<>();
        final List<String> enqueued = new ArrayList<>();
        final Set<String> origins = new HashSet<>();
        final List<Long> epochs = new ArrayList<>();

        @Override public TasksAutoMigration.CloudContext context() {
            if (!shadowComplete) return null;
            return new TasksAutoMigration.CloudContext(PRINCIPAL, scope, resyncGeneration, shadow, outbox.keySet(), Collections.<String>emptySet(),
                    serverNowMillis, checked);
        }

        @Override public void markChecked() {
            checks++;
            checked = true;
        }

        @Override public long consentEpoch() { return epoch; }

        @Override public int enqueue(String principal, TasksCloudImport.Plan plan, long expectedConsentEpoch) {
            if (beforeEnqueue != null) beforeEnqueue.run();
            if (failEnqueue) throw new TasksCloudError(TasksCloudError.Code.STORAGE);
            if (expectedConsentEpoch != epoch) throw new TasksCloudError(TasksCloudError.Code.CONSENT_WITHDRAWN);
            check(PRINCIPAL.equals(principal), "the step's principal");
            origins.add(TasksCloudSchema.ORIGIN_MIGRATION);
            epochs.add(expectedConsentEpoch);
            int inserted = 0;
            for (int i = 0; i < plan.mutationIds.size(); i++) {
                if (outbox.containsKey(plan.mutationIds.get(i))) continue;
                outbox.put(plan.mutationIds.get(i), plan.operations.get(i));
                enqueued.add(plan.mutationIds.get(i));
                inserted++;
            }
            return inserted;
        }

        /** The server answers every queued create: provenance recorded; {@code executed} = the row is in the shadow. */
        void deliver(FakeLocal local, boolean executed) {
            for (Map.Entry<String, TasksWire.Operation> entry : outbox.entrySet()) {
                local.attempt(entry.getKey(), true, 0);
                if (!executed) continue;
                TasksWire.Operation operation = entry.getValue();
                shadow.add(operation instanceof TasksWire.Operation.TaskCreate ? "task:" + ((TasksWire.Operation.TaskCreate) operation).taskId
                        : "list:" + ((TasksWire.Operation.ListCreate) operation).listId);
            }
            outbox.clear();
        }
    }
}
