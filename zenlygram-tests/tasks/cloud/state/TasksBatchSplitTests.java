package org.telegram.messenger.zenlygram.tasks.cloud;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudR4Tests.*;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudStateTests.*;

/**
 * Host regression tests (FIX-A8) on the real TasksCloudEngine/Ledger/Import sources, same harness as TasksCloudR4Tests
 * (host SQLite bridge, scripted R4 remote decoded by the production strict decoders):
 * <ul>
 *   <li>A 422 on a multi-item batch names no item (the server validates every item while it reads the batch, so one
 *   refused item refuses the request and nothing runs): the entries go back without an attempt and are resent one entry
 *   per batch, so only the entry the server refuses alone becomes INVALID. A database an older build left with every
 *   entry of such a batch INVALID is repaired once; an entry refused again alone stays INVALID.</li>
 *   <li>The R4 explicit copy builds each create exactly as the automatic migration does (design 30 §7.1), so the
 *   migration's re-check of the shared import scope rebuilds an explicitly copied row with the bytes it was sent with;
 *   the R3 copy is unchanged.</li>
 *   <li>FIX-A8b: the app's copy (TasksCloudRepository.previewImport, Android glue read as source; args[2] is the Android
 *   root) takes the R4 copy rules once the server proved wire 4 and exactly the R3 copy on an R3-only or rolled-back
 *   server; the copy dialog labels every status a folder row can show, in uz, ru and en; a request the send gate refused
 *   (consent_changed: nothing was signed or sent) costs no attempt on any route.</li>
 * </ul>
 */
public final class TasksBatchSplitTests {
    static final String SCOPE = "o-test/" + A;
    static final long NOW_MS = 1_800_000_000_000L;

    static TasksCloudRemote.Failure refused422() {
        return new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.TASK, "tasks_invalid_request", null, 422, 0);
    }

    static List<String> ids(Request request) {
        ArrayList<String> ids = new ArrayList<>();
        for (TasksWire.Batch.Item item : request.batch.items) ids.add(item.mutation.mutationId);
        return ids;
    }

    static int stateCount(Harness h, String state) {
        int n = 0;
        for (TasksCloudLedger.Entry entry : h.ledger.entries()) if (state.equals(entry.state)) n++;
        return n;
    }

    // ---- A: a 422 on a multi-item batch ----

    static void wholeBatch422IsResentOneEntryPerBatch() {
        Sink sink = new Sink();
        TasksAnalyticsSink.Holder.set(sink);
        Harness h = r4();
        h.engine.stop(); // queue all three before the engine drains
        h.engine.createTask(content(null, "First"));
        h.engine.createTask(content(null, "Refused"));
        h.engine.createTask(content(null, "Third"));
        h.engine.start();
        h.drain();
        Request batch = takeBatch(h);
        check(batch.batch.items.size() == 3, "three chain-free creates in one batch");
        List<String> sent = ids(batch);
        batch.reply.failure(refused422());
        h.drain();
        check(stateCount(h, TasksCloudSchema.INVALID) == 0, "a 422 on the whole batch marks no entry invalid");
        check(TasksCloudSchema.QUEUED.equals(entry(h, sent.get(1)).state) && entry(h, sent.get(1)).attempts == 0
                && TasksCloudSchema.QUEUED.equals(entry(h, sent.get(2)).state) && entry(h, sent.get(2)).attempts == 0,
                "requeued with the same identity, the refused attempt not counted");
        check(sink.count("syncIssue:invalid") == 0, "no sync_issue invalid for a refusal that names no entry");

        Request first = takeBatch(h);
        check(ids(first).equals(Collections.singletonList(sent.get(0))) && entry(h, sent.get(0)).attempts == 1,
                "then one entry per batch, in outbox order; only this send counts");
        answerAll(first, "1");
        h.drain();
        Request second = takeBatch(h);
        check(ids(second).equals(Collections.singletonList(sent.get(1))), "the next entry alone");
        second.reply.failure(refused422());
        h.drain();
        check(TasksCloudSchema.INVALID.equals(entry(h, sent.get(1)).state), "refused alone: this entry, and only it, is invalid");
        Request third = takeBatch(h);
        check(ids(third).equals(Collections.singletonList(sent.get(2))), "the last entry alone, after the refusal");
        answerAll(third, "1");
        h.drain();
        check(sink.count("syncIssue:invalid") == 1, "one sync_issue invalid: the entry the server refused alone");
        check(stateCount(h, TasksCloudSchema.ACKED) == 2 && stateCount(h, TasksCloudSchema.INVALID) == 1, "two committed, one invalid");

        h.engine.stop();
        h.engine.createTask(content(null, "Later one"));
        h.engine.createTask(content(null, "Later two"));
        h.engine.start();
        h.drain();
        Request grown = takeBatch(h);
        check(grown.batch.items.size() == 2, "once each entry of the refused batch went alone, batches carry several entries again");
        answerAll(grown, "1");
        h.drain();
        quiet(h);
        TasksAnalyticsSink.Holder.set(null);
        h.sql.close();
    }

    /** Migration creates: the split stays on the batch route (never the single route), origin kept. */
    static void migrationBatch422SplitsOnTheBatchRoute() {
        Harness h = r4();
        check(h.engine.enqueueImport(A, migrationPlan(4), TasksCloudSchema.ORIGIN_MIGRATION, h.engine.consentEpoch()) == 4, "four migration creates");
        h.drain();
        Request batch = takeBatch(h);
        check(batch.batch.items.size() == 4 && batch.batch.items.get(0).origin == TasksWire.Origin.MIGRATION, "one migration batch");
        List<String> sent = ids(batch);
        batch.reply.failure(refused422());
        h.drain();
        check(stateCount(h, TasksCloudSchema.INVALID) == 0, "nothing invalid after the whole-batch 422");
        for (int i = 0; i < 4; i++) {
            check(h.remote.open().size() == 1 && !"mutate".equals(h.remote.open().get(0).kind), "never the single route for a migration create");
            Request alone = takeBatch(h);
            check(ids(alone).equals(Collections.singletonList(sent.get(i))) && alone.batch.items.get(0).origin == TasksWire.Origin.MIGRATION,
                    "migration create " + i + " alone, still origin migration");
            if (i == 2) alone.reply.failure(refused422());
            else answerAll(alone, "1");
            h.drain();
        }
        check(TasksCloudSchema.INVALID.equals(entry(h, sent.get(2)).state) && stateCount(h, TasksCloudSchema.INVALID) == 1,
                "only the create the server refused alone is invalid");
        check(h.engine.unsentCounts()[1] == 1, "the other migration creates were delivered");
        quiet(h);
        h.sql.close();
    }

    /**
     * The split lives in memory: after a process death the requeued entries go in one batch again, are refused and split
     * again, and still only the entry refused alone ends invalid. Stale split entries (discarded meanwhile) end it.
     */
    static void splitSurvivesARestartAndEndsWithItsEntries() {
        Harness h = r4();
        h.engine.stop();
        for (int i = 0; i < 3; i++) h.engine.createTask(content(null, "Row " + i));
        h.engine.start();
        h.drain();
        Request batch = takeBatch(h);
        List<String> sent = ids(batch);
        batch.reply.failure(refused422());
        h.drain();
        check(ids(takeBatch(h)).equals(Collections.singletonList(sent.get(0))), "split started; its first send is in flight");

        Harness restarted = h.restart(); // process death: that answer never arrives
        restarted.remote.batchRoute = true;
        restarted.engine.start();
        restarted.drain();
        Request again = takeBatch(restarted);
        check(ids(again).equals(sent), "after a restart the same three go in one batch again (same ids)");
        again.reply.failure(refused422());
        restarted.drain();
        check(stateCount(restarted, TasksCloudSchema.INVALID) == 0, "refused again as a whole: still nothing invalid");
        Request first = takeBatch(restarted);
        check(ids(first).equals(Collections.singletonList(sent.get(0))), "split again");
        restarted.engine.discard(sent.get(2)); // the user drops a split entry meanwhile
        first.reply.failure(refused422());
        restarted.drain();
        check(TasksCloudSchema.INVALID.equals(entry(restarted, sent.get(0)).state), "the entry refused alone is invalid");
        Request second = takeBatch(restarted);
        check(ids(second).equals(Collections.singletonList(sent.get(1))), "the next split entry alone");
        answerAll(second, "1");
        restarted.drain();
        restarted.engine.stop();
        restarted.engine.createTask(content(null, "New one"));
        restarted.engine.createTask(content(null, "New two"));
        restarted.engine.start();
        restarted.drain();
        check(takeBatch(restarted).batch.items.size() == 2, "a discarded split entry does not keep batches at one entry");
        restarted.sql.close();

        // Only a 422 splits: another refusal of the request itself (a 400 from an edge) fails each entry as before.
        Harness g = r4();
        g.engine.stop();
        g.engine.createTask(content(null, "x"));
        g.engine.createTask(content(null, "y"));
        g.engine.start();
        g.drain();
        Request rejected = takeBatch(g);
        failBatch(rejected, TasksCloudRemote.Failure.Kind.PROTOCOL, "request_rejected", 400, 0);
        g.drain();
        check(stateCount(g, TasksCloudSchema.INVALID) == 2, "a 400 on the batch: each entry invalid, as before");
        none(g, "and nothing is resent");
        g.sql.close();
    }

    /**
     * A database an older build left behind: a whole-batch 422 had made every entry of the batch invalid. The first start
     * of this build requeues them once (same ids and bytes); a batch refused again is split; the entry refused alone
     * stays invalid for good — no second repair after the next start. Other invalid entries are untouched.
     */
    static void olderBuildsBatchRefusalsAreRepairedOnce() {
        Harness h = r4();
        check(h.ledger.readMeta(TasksCloudLedger.META_BATCH_REFUSALS_REPAIRED) != null, "a fresh database is marked repaired at its first start");
        h.engine.stop();
        check(h.engine.enqueueImport(A, migrationPlan(1), TasksCloudSchema.ORIGIN_MIGRATION, h.engine.consentEpoch()) == 1, "one migration create");
        final String oversized = h.ledger.entries().get(0).mutationId;
        for (int i = 0; i < 3; i++) h.engine.createTask(content(null, "Clock " + i));
        h.engine.start();
        h.drain();
        Request batch = takeBatch(h);
        check(batch.batch.items.size() == 3 && batch.batch.items.get(0).origin == TasksWire.Origin.USER, "the three user creates in one batch");
        List<String> sent = ids(batch);
        Map<String, byte[]> bodies = new HashMap<>();
        for (TasksWire.Batch.Item item : batch.batch.items) bodies.put(item.mutation.mutationId, item.mutation.body());
        // What an older build stored, on a database it never repaired: the whole-batch 422 failed every entry of the batch
        // (ledger.fail INVALID with the answer's code), and a migration create a one-entry 413 had refused alone.
        h.sql.transaction(() -> {
            h.sql.update("UPDATE outbox SET state=?,error=? WHERE state=?", TasksCloudSchema.INVALID, "tasks_invalid_request", TasksCloudSchema.SENDING);
            h.sql.update("UPDATE outbox SET state=?,error=?,sent=1,attempts=1 WHERE mutation_id=?", TasksCloudSchema.INVALID, "request_rejected", oversized);
            h.sql.update("DELETE FROM meta WHERE key=?", TasksCloudLedger.META_BATCH_REFUSALS_REPAIRED);
            return null;
        });

        Harness updated = h.restart();
        updated.remote.batchRoute = true;
        check(stateCount(updated, TasksCloudSchema.INVALID) == 4, "before the update's first start: four invalid entries");
        updated.engine.start();
        updated.drain();
        check(TasksCloudSchema.INVALID.equals(entry(updated, oversized).state), "an entry invalid for another reason stays invalid");
        Request repaired = takeBatch(updated);
        check(ids(repaired).equals(sent), "the three are sent again once, in order");
        for (TasksWire.Batch.Item item : repaired.batch.items) {
            check(Arrays.equals(item.mutation.body(), bodies.get(item.mutation.mutationId)), "same id and bytes");
        }
        check(entry(updated, sent.get(1)).attempts == 1, "the refused attempt was taken back (only this send counts)");
        repaired.reply.failure(refused422()); // one of them is still refused
        updated.drain();
        for (int i = 0; i < 3; i++) {
            Request alone = takeBatch(updated);
            check(ids(alone).equals(Collections.singletonList(sent.get(i))), "split: entry " + i + " alone");
            if (i == 1) alone.reply.failure(refused422());
            else answerAll(alone, "1");
            updated.drain();
        }
        check(TasksCloudSchema.INVALID.equals(entry(updated, sent.get(1)).state) && TasksCloudSchema.ACKED.equals(entry(updated, sent.get(0)).state)
                && TasksCloudSchema.ACKED.equals(entry(updated, sent.get(2)).state), "only the entry refused alone is invalid again");
        quiet(updated);
        check(updated.ledger.repairBatchRefusals().isEmpty(), "the repair runs once per database");

        Harness later = updated.restart();
        later.remote.batchRoute = true;
        later.engine.start();
        later.drain();
        quiet(later);
        later.remote.none("after the next start the entry refused alone is not sent again: never an endless retry");
        check(TasksCloudSchema.INVALID.equals(entry(later, sent.get(1)).state), "it stays invalid (discard or keep, the user's choice)");
        later.sql.close();
    }

    // ---- B: the R4 explicit copy sends the migration's bytes ----

    static final String INBOX_INC = "inc-inbox", WORK_INC = "inc-work";

    /** Device rows: the built-in inbox, a folder with edge whitespace, and rows the R4 rules normalize. */
    static ZenlyTaskImport.Snapshot device(List<ZenlyTaskImport.Mapping> mappings, long firstEnqueueMillis) {
        LinkedHashMap<String, String> incarnations = new LinkedHashMap<>();
        incarnations.put("inbox", INBOX_INC);
        incarnations.put("work", WORK_INC);
        long old = firstEnqueueMillis - 86_400_000L;
        long fresh = firstEnqueueMillis + 3_600_000L; // created after the migration's first enqueue (a "new row")
        List<ZenlyTaskModel.Task> tasks = Arrays.asList(
                new ZenlyTaskModel.Task("t1", "inbox", "Inbox task", "", null, 0, false, null, old, old),
                new ZenlyTaskModel.Task("t2", "work", " Report ", "Notes", old + 7_200_000L, 7, true, old + 1_000L, old, old),
                new ZenlyTaskModel.Task("t3", "work", "​", "", null, 1, false, null, old, old),
                new ZenlyTaskModel.Task("t4", "work", "Fresh", "", null, 0, true, fresh, fresh, fresh));
        return new ZenlyTaskImport.Snapshot(Arrays.asList(new ZenlyTaskModel.Folder("inbox", "", 0), new ZenlyTaskModel.Folder("work", "  Ish ", old)),
                incarnations, tasks, mappings);
    }

    static TasksWire.Operation.TaskCreate create(TasksCloudImport.Plan plan, String title) {
        for (TasksWire.Operation operation : plan.operations) {
            if (operation instanceof TasksWire.Operation.TaskCreate && ((TasksWire.Operation.TaskCreate) operation).content.title.equals(title)) {
                return (TasksWire.Operation.TaskCreate) operation;
            }
        }
        throw new AssertionError("no create titled " + title);
    }

    /** mutation id → sealed body, as the server hashes it. */
    static Map<String, byte[]> sealed(TasksCloudImport.Plan plan) {
        Map<String, byte[]> bodies = new LinkedHashMap<>();
        for (int i = 0; i < plan.operations.size(); i++) {
            bodies.put(plan.mutationIds.get(i), TasksWire.Mutation.seal(A, plan.mutationIds.get(i), plan.operations.get(i)).body());
        }
        return bodies;
    }

    static void explicitCopyRebuildsWithTheMigrationsBytes() {
        long firstEnqueue = NOW_MS - 172_800_000L; // the migration's first enqueue, two days ago (server time)
        ZenlyTaskImport.MigrationRecord record = ZenlyTaskImport.MigrationRecord.claimed(SCOPE, "ru", firstEnqueue / 1000 - 5)
                .enqueued(firstEnqueue / 1000, 0, 0, 0).completed(firstEnqueue / 1000 + 60, 0);
        Set<String> none = Collections.emptySet();
        TasksCloudImport.Rules rules = TasksCloudImport.Rules.forCopy(record, SCOPE, "uz", NOW_MS);
        TasksCloudImport.ImportPreview preview = TasksCloudImport.preview(TG, A, device(Collections.<ZenlyTaskImport.Mapping>emptyList(), firstEnqueue),
                none, none, NOW_MS, rules);
        check(preview.folders.get(0).status == TasksCloudImport.RowStatus.CLOUD_INBOX, "R4: the built-in inbox is the Cloud Inbox, no choice needed");
        check(preview.folders.get(1).status == TasksCloudImport.RowStatus.NEW, "a folder with edge whitespace is normalized, not refused");
        for (TasksCloudImport.TaskRow row : preview.tasks) check(row.status == TasksCloudImport.RowStatus.NEW, "every task normalized, none refused: " + row.localTaskId);
        check(preview.importableLists == 1 && preview.importableTasks == 4, "one list and four tasks to copy");

        TasksCloudImport.Plan copy = TasksCloudImport.plan(preview, TasksCloudImport.ImportChoices.none());
        check(copy.lists == 1 && copy.tasks == 4 && copy.leftLocal == 0 && copy.mappings.size() == 5, "1 list + 4 tasks, mapped; none for the inbox");
        TasksWire.Operation.ListCreate list = (TasksWire.Operation.ListCreate) copy.operations.get(0);
        check("Ish".equals(list.title) && list.sortOrder == 0 && list.createdAt != null, "list title trimmed, index × 1024, its time kept");
        TasksWire.Operation.TaskCreate inbox = create(copy, "Inbox task");
        check(inbox.content.listId == null && inbox.content.description == null, "inbox task into the Cloud Inbox; '' sent as null");
        TasksWire.Operation.TaskCreate report = create(copy, "Report");
        check(list.listId.equals(report.content.listId) && "Notes".equals(report.content.description) && report.content.priority == 4
                && report.content.sortOrder == 0 && report.content.annotation == null && report.isCompleted && report.completedAt != null,
                "title trimmed, priority clamped, never an annotation");
        TasksWire.Operation.TaskCreate untitled = create(copy, "Задача без названия");
        check(untitled.content.sortOrder == 1024 && untitled.content.description == null, "no visible scalar: the record locale's placeholder");
        TasksWire.Operation.TaskCreate fresh = create(copy, "Fresh");
        check(fresh.createdAt == null && fresh.isCompleted && fresh.completedAt == null,
                "a time after the migration's first enqueue (+300 s) is unknown, as its re-check rebuilds it");

        // The migration's re-check of the scope, hours later: the copy's rows are absent (e.g. lost with a logout), so it
        // rebuilds each one under its reserved id. The server hashes the body: it must be the very one first sent.
        ZenlyTaskImport.Snapshot recorded = device(copy.mappings, firstEnqueue);
        TasksAutoMigration.MigrationPlan recheck = TasksAutoMigration.migrationPlan(recorded, new TasksMigrationNormalizer(record.locale),
                none, none, none, 1, TasksAutoMigration.normalizedAt(record, NOW_MS + 7_200_000L), true);
        Map<String, byte[]> sentBytes = sealed(copy), rebuilt = sealed(recheck.plan);
        check(rebuilt.keySet().equals(sentBytes.keySet()), "the re-check resends every explicitly copied row under its reserved id");
        for (Map.Entry<String, byte[]> entry : sentBytes.entrySet()) {
            check(Arrays.equals(entry.getValue(), rebuilt.get(entry.getKey())), "identical bytes: no tasks_mutation_mismatch on a resend");
        }

        // The same rows re-previewed after a re-login (mapped, not in the cloud yet): the R4 copy resends identical bytes.
        TasksCloudImport.Plan resend = TasksCloudImport.plan(TasksCloudImport.preview(TG, A, recorded, none, none, NOW_MS + 86_400_000L,
                TasksCloudImport.Rules.forCopy(record, SCOPE, "en", NOW_MS + 86_400_000L)), TasksCloudImport.ImportChoices.none());
        check(resend.mappings.isEmpty(), "no new mapping on a resend");
        for (Map.Entry<String, byte[]> entry : sealed(resend).entrySet()) {
            check(Arrays.equals(entry.getValue(), sentBytes.get(entry.getKey())), "the copy's own resend: identical bytes");
        }

        // R3 (no rules) is unchanged: it refuses what it cannot send as is and sends '' as ''. That create is what the
        // migration's re-check could never rebuild byte for byte.
        TasksCloudImport.ImportPreview r3 = TasksCloudImport.preview(TG, A, device(Collections.<ZenlyTaskImport.Mapping>emptyList(), firstEnqueue),
                none, none, NOW_MS);
        check(r3.folders.get(0).status == TasksCloudImport.RowStatus.INVALID && r3.folders.get(1).status == TasksCloudImport.RowStatus.INVALID,
                "R3: untitled inbox and edge whitespace need a choice");
        TasksCloudImport.Plan r3Copy = TasksCloudImport.plan(r3, TasksCloudImport.ImportChoices.none().withFolderInCloudInbox(INBOX_INC));
        TasksWire.Operation.TaskCreate r3Inbox = create(r3Copy, "Inbox task");
        check("".equals(r3Inbox.content.description) && r3Copy.tasks == 1, "R3 sends the local '' as it is");
        TasksAutoMigration.MigrationPlan r3Recheck = TasksAutoMigration.migrationPlan(device(r3Copy.mappings, firstEnqueue),
                new TasksMigrationNormalizer(record.locale), none, none, none, 1, firstEnqueue, true);
        check(!Arrays.equals(sealed(r3Copy).get(r3Copy.mutationIds.get(0)), sealed(r3Recheck.plan).get(r3Copy.mutationIds.get(0))),
                "R3 bytes differ from the re-check's (the mismatch the R4 copy removes)");
    }

    /** Under R4 a folder keeps its own name and goes to its own list; a choice is ignored; a stale choice is still refused. */
    static void explicitChoicesUnderR4() {
        Set<String> none = Collections.emptySet();
        TasksCloudImport.Rules rules = TasksCloudImport.Rules.forCopy(null, SCOPE, "en-GB", NOW_MS);
        TasksCloudImport.ImportPreview preview = TasksCloudImport.preview(TG, A, device(Collections.<ZenlyTaskImport.Mapping>emptyList(), NOW_MS),
                none, none, NOW_MS, rules);
        TasksCloudImport.Plan named = TasksCloudImport.plan(preview, TasksCloudImport.ImportChoices.none().withFolderTitle(WORK_INC, " Projects\t"));
        check("Ish".equals(((TasksWire.Operation.ListCreate) named.operations.get(0)).title), "a chosen list title is ignored: the folder's own, normalized");
        check("Untitled task".equals(create(named, "Untitled task").content.title), "no record: the UI language's placeholder");
        TasksCloudImport.Plan inboxed = TasksCloudImport.plan(preview, TasksCloudImport.ImportChoices.none().withFolderInCloudInbox(WORK_INC));
        TasksWire.Operation.ListCreate list = (TasksWire.Operation.ListCreate) inboxed.operations.get(0);
        check(inboxed.lists == 1 && "Ish".equals(list.title), "a Cloud Inbox choice is ignored: one list 'Ish'");
        for (String title : Arrays.asList("Report", "Untitled task", "Fresh")) {
            check(list.listId.equals(create(inboxed, title).content.listId), "its task '" + title + "' under it");
        }
        for (ZenlyTaskImport.Mapping mapping : inboxed.mappings) {
            check(ZenlyTaskImport.Mapping.FOLDER.equals(mapping.entity) ? list.listId.equals(mapping.cloudId)
                            : "t1".equals(mapping.localKey) ? mapping.parentCloudId == null : list.listId.equals(mapping.parentCloudId),
                    "the folder maps to that list, its tasks' parent is that list (the device Inbox's task: the Cloud Inbox)");
        }
        expect(TasksCloudError.class, () -> TasksCloudImport.plan(preview, TasksCloudImport.ImportChoices.none().withFolderTitle("gone", "X")),
                "a stale choice is refused under R4 too");
    }

    /** Which time and locale an explicit copy normalizes with. */
    static void copyRulesFollowTheMigrationRecord() {
        long e = NOW_MS / 1000 - 3600;
        ZenlyTaskImport.MigrationRecord claimed = ZenlyTaskImport.MigrationRecord.claimed(SCOPE, "uz", e - 10);
        ZenlyTaskImport.MigrationRecord enqueued = claimed.enqueued(e, 1, 1, 0);
        ZenlyTaskImport.MigrationRecord completed = enqueued.completed(e + 5, 0);
        TasksCloudImport.Rules noRecord = TasksCloudImport.Rules.forCopy(null, SCOPE, "ru-RU", NOW_MS);
        check("ru".equals(noRecord.normalizer.locale) && noRecord.normalizedAtMillis == NOW_MS, "no record: the UI language, the server's now");
        check("en".equals(TasksCloudImport.Rules.forCopy(null, SCOPE, "de", NOW_MS).normalizer.locale), "another UI language: en");
        for (ZenlyTaskImport.MigrationRecord record : Arrays.asList(enqueued, completed)) {
            TasksCloudImport.Rules rules = TasksCloudImport.Rules.forCopy(record, SCOPE, "en", NOW_MS);
            check("uz".equals(rules.normalizer.locale) && rules.normalizedAtMillis == e * 1000L, record.state + ": the record's locale and first enqueue");
        }
        TasksCloudImport.Rules otherScope = TasksCloudImport.Rules.forCopy(completed, "o-test/" + B, "en", NOW_MS);
        check("uz".equals(otherScope.normalizer.locale) && otherScope.normalizedAtMillis == NOW_MS, "another scope (no re-check there): now");
        check(TasksCloudImport.Rules.forCopy(claimed, SCOPE, "en", NOW_MS).normalizedAtMillis == NOW_MS, "claimed, not enqueued yet: now");
        for (String state : Arrays.asList(ZenlyTaskImport.MigrationRecord.SKIPPED, ZenlyTaskImport.MigrationRecord.RELEASED)) {
            check(TasksCloudImport.Rules.forCopy(completed.withState(state, null), SCOPE, "en", NOW_MS).normalizedAtMillis == NOW_MS,
                    state + ": never re-checked, now");
        }
    }

    // ---- FIX-A8b A: the explicit copy's rules in the app ----

    /**
     * The rules TasksCloudRepository.previewImport hands the preview (that expression is pinned as source by
     * {@link #copyWiring}), on the real ledger's wire state.
     */
    static TasksCloudImport.Rules appRules(Harness h, ZenlyTaskImport.MigrationRecord record, String language, long now) {
        return h.ledger.isServerWire4() ? TasksCloudImport.Rules.forCopy(record, SCOPE, language, now) : null;
    }

    static List<TasksCloudImport.RowStatus> statuses(TasksCloudImport.ImportPreview preview) {
        ArrayList<TasksCloudImport.RowStatus> statuses = new ArrayList<>();
        for (TasksCloudImport.FolderRow row : preview.folders) statuses.add(row.status);
        for (TasksCloudImport.TaskRow row : preview.tasks) statuses.add(row.status);
        return statuses;
    }

    /** A server that proved wire 4 gets the R4 copy; an R3-only or rolled-back one exactly the R3 copy it always got. */
    static void appCopyRulesFollowTheServersWire() {
        Set<String> none = Collections.emptySet();
        ZenlyTaskImport.Snapshot rows = device(Collections.<ZenlyTaskImport.Mapping>emptyList(), NOW_MS);
        Harness h = r4();
        TasksCloudImport.ImportPreview r4 = TasksCloudImport.preview(TG, A, rows, none, none, NOW_MS, appRules(h, null, "uz", NOW_MS));
        check(r4.rules != null && r4.usesR4Rules() && "uz".equals(r4.rules.normalizer.locale)
                        && r4.folders.get(0).status == TasksCloudImport.RowStatus.CLOUD_INBOX,
                "wire 4: the R4 copy (the inbox is the Cloud Inbox; no record: the UI language's placeholders)");
        ZenlyTaskImport.MigrationRecord record = ZenlyTaskImport.MigrationRecord.claimed(SCOPE, "ru", NOW_MS / 1000 - 600).enqueued(NOW_MS / 1000 - 300, 0, 0, 0);
        TasksCloudImport.Rules recorded = appRules(h, record, "uz", NOW_MS);
        check("ru".equals(recorded.normalizer.locale) && recorded.normalizedAtMillis == (NOW_MS / 1000 - 300) * 1000L,
                "wire 4 beside the migration's record: its locale and first enqueue");
        // Rolled back: a 2xx without the echo clears server_wire, and the copy is R3 again.
        h.engine.refresh();
        h.drain();
        Request pull = h.remote.take("changes");
        pull.reply.success(TasksWire.decodeChangesResponse("{\"schema_version\":1,\"changes\":[],\"next_cursor\":\"n\",\"has_more\":false,\"server_time\":\""
                + SERVER_TIME + "\"}", pull.limit, null));
        h.drain();
        check(appRules(h, record, "uz", NOW_MS) == null, "rolled back (no echo): the R3 copy");
        h.sql.close();

        Harness g = new Harness(); // an R3-only server: never an echo, no batch route
        g.bootstrap();
        check(!g.ledger.isServerWire4(), "an R3-only server never proves wire 4");
        TasksCloudImport.ImportPreview r3 = TasksCloudImport.preview(TG, A, rows, none, none, NOW_MS, appRules(g, record, "uz", NOW_MS));
        TasksCloudImport.ImportPreview before = TasksCloudImport.preview(TG, A, rows, none, none, NOW_MS);
        check(r3.rules == null && !r3.usesR4Rules() && statuses(r3).equals(statuses(before))
                        && r3.folders.get(0).status == TasksCloudImport.RowStatus.INVALID,
                "R3-only: the 6-argument R3 preview the app always made (the untitled inbox still needs a choice)");
        g.sql.close();
    }

    static final String MESSENGER = "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/";
    /** The copy dialog of this app (the import's org/telegram/ui/zenlygram/ZenlyCloudTasksImportDialog.java, ported). */
    static final String COPY_DIALOG = "TMessagesProj/src/main/java/org/telegram/zenlygram/tasks/ui/ZgTasksCopyDialog.java";

    static String readSource(String androidRoot, String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(androidRoot, path)), StandardCharsets.UTF_8);
    }

    /** The braces-balanced body that follows {@code signature} (these bodies hold no brace in a string or comment). */
    static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        check(at >= 0, "found: " + signature);
        int open = source.indexOf('{', at + signature.length());
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced body: " + signature);
    }

    static void inOrder(String body, String what, String... parts) {
        int from = 0;
        for (String part : parts) {
            int at = body.indexOf(part, from);
            check(at >= 0, what + ": " + part);
            from = at + part.length();
        }
    }

    /**
     * The Android glue the host cannot run (FIX-A5/A6 style): the snapshot, then the account's migration record (a
     * store failure reported as such), the UI language read on the main thread, then on the executor one server-time
     * reading for both the rules and the preview, the R4 rules only once the server proved wire 4, the snapshot's own
     * import scope. Confirm is unchanged: the plan follows its preview's rules.
     */
    static void copyWiring(String androidRoot) throws IOException {
        String repository = readSource(androidRoot, MESSENGER + "tasks/cloud/TasksCloudRepository.java");
        inOrder(methodBody(repository, "public void previewImport(final Result<TasksCloudImport.ImportPreview> done)"), "previewImport",
                "final String scope = ProOrigin.importScope(originToken, principal);",
                "store.importSnapshot(account, scope, (snapshot, failure) -> {",
                "done.complete(null, storeError(failure));",
                "store.migrationRecord(account, (record, recordFailure) -> {",
                "done.complete(null, storeError(recordFailure));",
                "final String language = LocaleController.getInstance().getCurrentLocale().getLanguage();",
                "call(done, e -> {",
                "if (!principal.equals(e.requirePrincipal())) throw new TasksCloudError(TasksCloudError.Code.PRINCIPAL_CHANGED);",
                "long now = ZenlyPro.trustedNowSeconds() * 1000L;",
                "TasksCloudImport.Rules rules = ledger.isServerWire4() ? TasksCloudImport.Rules.forCopy(record, scope, language, now) : null;",
                "return TasksCloudImport.preview(rawTelegramId, principal, snapshot, new HashSet<>(e.knownCloudIds()),",
                "new HashSet<>(e.outboxMutationIds()), now, rules);");
        check(methodBody(repository, "public void confirmImport(final TasksCloudImport.ImportPreview preview, final TasksCloudImport.ImportChoices choices,")
                .contains("return TasksCloudImport.plan(preview, choices);"), "confirm: the plan follows the rules of its preview");
        check(readSource(androidRoot, MESSENGER + "tasks/ZenlyTasksSyncOwner.java")
                        .contains("TasksMigrationNormalizer.localeFor(LocaleController.getInstance().getCurrentLocale().getLanguage())"),
                "no record yet: the copy's placeholders come from the UI language the migration step claims with");
    }

    // ---- FIX-A8b B: the copy dialog's labels ----

    static Map<String, String> cloudStrings(String androidRoot, String folder) throws IOException {
        Map<String, String> values = new HashMap<>();
        Matcher string = Pattern.compile("<string name=\"(\\w+)\">([^<]*)</string>")
                .matcher(readSource(androidRoot, "TMessagesProj/src/main/res/" + folder + "/strings_zenlygram_engine_tasks.xml"));
        while (string.find()) values.put(string.group(1), string.group(2));
        return values;
    }

    /**
     * Every status a folder row can carry has its own label (CLOUD_INBOX and REMOVED_IN_CLOUD used to fall to "Waits for a
     * choice for its folder", a task's status); both new labels exist in uz, ru and en, translated, and the Cloud Inbox
     * one names the Cloud Inbox the target picker offers.
     */
    static void dialogLabelsEveryFolderStatus(String androidRoot) throws IOException {
        String status = methodBody(readSource(androidRoot, COPY_DIALOG),
                "static String status(TasksCloudImport.RowStatus status, String invalidField)");
        for (TasksCloudImport.RowStatus value : TasksCloudImport.RowStatus.values()) {
            if (value == TasksCloudImport.RowStatus.BLOCKED_BY_FOLDER) continue; // a task's status: the default's text
            check(status.contains("case " + value.name() + ":"), "the dialog labels " + value);
        }
        check(status.contains("case CLOUD_INBOX: return text(R.string.ZenlyCloudTasksImportStatusCloudInbox);")
                && status.contains("case REMOVED_IN_CLOUD: return text(R.string.ZenlyCloudTasksImportStatusRemoved);"), "the R4 statuses' own labels");
        Map<String, Map<String, String>> locales = new LinkedHashMap<>();
        for (String folder : Arrays.asList("values", "values-uz", "values-ru")) locales.put(folder, cloudStrings(androidRoot, folder));
        for (String key : Arrays.asList("ZenlyCloudTasksImportStatusCloudInbox", "ZenlyCloudTasksImportStatusRemoved")) {
            String en = locales.get("values").get(key);
            for (Map.Entry<String, Map<String, String>> locale : locales.entrySet()) {
                String value = locale.getValue().get(key);
                check(value != null && !value.trim().isEmpty() && (locale.getKey().equals("values") || !value.equals(en)),
                        locale.getKey() + " has " + key + ", translated");
            }
            String uz = locales.get("values-uz").get(key);
            check(uz.indexOf('\'') < 0 && uz.indexOf('‘') < 0 && uz.indexOf('’') < 0, "uz " + key + ": no ASCII or quote apostrophe");
        }
        for (Map.Entry<String, Map<String, String>> locale : locales.entrySet()) {
            check(locale.getValue().get("ZenlyCloudTasksImportStatusCloudInbox").contains(locale.getValue().get("ZenlyCloudTasksInbox")),
                    locale.getKey() + ": the label names the picker's Cloud Inbox");
        }
    }

    // ---- FIX-A8b C: a send-time consent refusal costs no attempt ----

    /** TasksApi.CODE_CONSENT_CHANGED: the repository's send gate refused on the Pro worker; nothing was signed or sent. */
    static TasksCloudRemote.Failure refusedAtSend() {
        return new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.STALE, "consent_changed", null, 0, 0);
    }

    static Request takeMutate(Harness h) {
        quiet(h);
        return h.remote.take("mutate");
    }

    static int events(Sink sink, String prefix) {
        int n = 0;
        for (String event : sink.events) if (event.startsWith(prefix)) n++;
        return n;
    }

    static void consentRefusedAtSendCostsNoAttempt(String androidRoot) throws IOException {
        check(readSource(androidRoot, MESSENGER + "pro/TasksApi.java").contains("public static final String CODE_CONSENT_CHANGED = \"consent_changed\";")
                && readSource(androidRoot, MESSENGER + "tasks/cloud/TasksCloudEngine.java")
                        .contains("failure.kind == TasksCloudRemote.Failure.Kind.STALE && \"consent_changed\".equals(failure.code)"),
                "the engine knows the refusal by TasksApi.CODE_CONSENT_CHANGED (STALE, local)");
        Sink sink = new Sink();
        TasksAnalyticsSink.Holder.set(sink);
        // The single route of an R3-only server (its batch route answers 404 at once).
        Harness h = new Harness();
        h.bootstrap();
        h.engine.createTask(content(null, "Refused at send"));
        h.drain();
        Request first = h.remote.take("mutate");
        String id = first.mutation.mutationId;
        check(entry(h, id).attempts == 1, "the single route counts a send as it starts");
        first.reply.failure(refusedAtSend());
        h.drain();
        check(TasksCloudSchema.QUEUED.equals(entry(h, id).state) && entry(h, id).attempts == 0,
                "refused at send: back in the queue without an attempt, as a batch's entries");
        h.advance(TasksCloudEngine.REQUEUE_MS);
        Request second = h.remote.take("mutate");
        check(second.mutation.mutationId.equals(id) && Arrays.equals(second.mutation.body(), first.mutation.body()) && entry(h, id).attempts == 1,
                "requeued: the same id and bytes, only this send counts");
        second.reply.failure(refusedAtSend());
        h.drain();
        check(entry(h, id).attempts == 0 && events(sink, "syncIssue:") == 0 && events(sink, "error:") == 0,
                "a second refusal is not counted either; a local refusal is no sync issue and no error report");
        h.advance(TasksCloudEngine.REQUEUE_MS);
        fail(h.remote.take("mutate"), TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", null, 0);
        h.drain();
        TasksCloudLedger.Entry offline = entry(h, id);
        check(TasksCloudSchema.BACKOFF.equals(offline.state) && offline.attempts == 1 && offline.notBefore == h.now + TasksCloudPolicy.backoffMs(1),
                "a real failure after two refusals backs off as the first send it is");
        h.advance(TasksCloudPolicy.backoffMs(1));
        receipt(h.remote.take("mutate"), "1");
        h.drain();
        check(TasksCloudSchema.ACKED.equals(entry(h, id).state) && entry(h, id).attempts == 2, "delivered; the two sends that went out counted");
        h.sql.close();

        // The single route of an R4 server: a user entry a one-entry batch could not carry (413) is sent alone.
        Harness g = r4();
        g.engine.createTask(content(null, "Sent alone"));
        g.drain();
        Request big = takeBatch(g);
        String alone = ids(big).get(0);
        failBatch(big, TasksCloudRemote.Failure.Kind.PROTOCOL, "request_rejected", 413, 0);
        g.drain();
        Request single = takeMutate(g);
        check(single.mutation.mutationId.equals(alone) && entry(g, alone).attempts == 1, "a one-entry 413: sent alone through the single route");
        single.reply.failure(refusedAtSend());
        g.drain();
        check(TasksCloudSchema.QUEUED.equals(entry(g, alone).state) && entry(g, alone).attempts == 0, "refused at send on that route: not counted");
        g.advance(TasksCloudEngine.REQUEUE_MS);
        Request retried = takeBatch(g);
        check(ids(retried).equals(Collections.singletonList(alone)), "requeued: the same entry again");
        answerAll(retried, "1");
        g.drain();
        check(TasksCloudSchema.ACKED.equals(entry(g, alone).state) && entry(g, alone).attempts == 1, "delivered; only the send that went out counted");
        quiet(g);
        g.sql.close();

        // The batch route took the attempt back before this fix too; it stays so.
        Harness b = r4();
        b.engine.stop();
        b.engine.createTask(content(null, "One"));
        b.engine.createTask(content(null, "Two"));
        b.engine.start();
        b.drain();
        Request batch = takeBatch(b);
        List<String> sent = ids(batch);
        batch.reply.failure(refusedAtSend());
        b.drain();
        for (String entryId : sent) {
            check(TasksCloudSchema.QUEUED.equals(entry(b, entryId).state) && entry(b, entryId).attempts == 0, "batch route: refused at send, not counted");
        }
        b.advance(TasksCloudEngine.REQUEUE_MS);
        Request again = takeBatch(b);
        check(ids(again).equals(sent), "the same batch again");
        answerAll(again, "1");
        b.drain();
        quiet(b);
        b.sql.close();

        // Any other STALE keeps its attempt: that request may have gone out (outcome unknown).
        Harness s = new Harness();
        s.bootstrap();
        s.engine.createTask(content(null, "Raced"));
        s.drain();
        Request raced = s.remote.take("mutate");
        fail(raced, TasksCloudRemote.Failure.Kind.STALE, "session_changed", null, 0);
        s.drain();
        check(TasksCloudSchema.QUEUED.equals(entry(s, raced.mutation.mutationId).state) && entry(s, raced.mutation.mutationId).attempts == 1,
                "another STALE (outcome unknown) still counts its send");
        s.sql.close();
        TasksAnalyticsSink.Holder.set(null);
    }

    public static void main(String[] args) throws Exception {
        bridge = args[0];
        dir = new File(args[1]);
        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        wholeBatch422IsResentOneEntryPerBatch();
        migrationBatch422SplitsOnTheBatchRoute();
        splitSurvivesARestartAndEndsWithItsEntries();
        olderBuildsBatchRefusalsAreRepairedOnce();
        explicitCopyRebuildsWithTheMigrationsBytes();
        explicitChoicesUnderR4();
        copyRulesFollowTheMigrationRecord();
        appCopyRulesFollowTheServersWire();
        copyWiring(args[2]);
        dialogLabelsEveryFolderStatus(args[2]);
        consentRefusedAtSendCostsNoAttempt(args[2]);
        System.out.println("Tasks batch split, R4 copy, its app wiring and labels, consent refusal at send (host SQLite bridge, scripted R4 remote): "
                + checks + " checks passed");
    }
}
