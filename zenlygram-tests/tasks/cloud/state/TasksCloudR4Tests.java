package org.telegram.messenger.zenlygram.tasks.cloud;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudStateTests.*;

/**
 * Host tests of Tasks R4 on the real TasksCloudEngine/Ledger/State/Policy/AutoResolve sources (design 30 §6.4, §7):
 * the fail-closed consent gate (its default is checked first, before anything installs a source — hence an own JVM),
 * the server consent fence and the withdrawal epoch, the batch drain (origins, skipped items, fallback, migration
 * pause, adaptive size, a 5000-create migration), the downgrade, the sealing fix against a remote modelling R4 members,
 * import provenance and the phantom settle, task.patch, every §7.3 row, the §7.4 requeue bounds, unlinked intents, the
 * §7.5 cut-over and the schema v2 upgrade. Same harness as TasksCloudStateTests: the scripted remote's answers are
 * decoded by the production strict decoders over a host SQLite file (sqlite_bridge.py, NOT Android SQLite); batch item
 * errors are classified by {@link #itemFailure}, a copy of TasksApi.classifyItem's table for the codes used here.
 */
public final class TasksCloudR4Tests {
    static final String SERVER_TIME = "2026-10-24T10:00:00Z";

    // ---- R4 wire JSON ----

    static String list4(String id, String revision, String members, String title) {
        return "{\"kind\":\"list\",\"id\":\"" + id + "\",\"revision\":\"" + revision + "\",\"members_revision\":\"" + members
                + "\",\"title\":\"" + title + "\",\"sort_order\":0,\"created_at\":\"" + T0 + "\",\"updated_at\":\"" + T0 + "\"}";
    }

    static String task4(String id, String revision, String listId, String title, String description, int priority) {
        return "{\"kind\":\"task\",\"id\":\"" + id + "\",\"revision\":\"" + revision + "\",\"owner_tg_id\":\"777\",\"viewer_role\":\"owner\","
                + "\"content\":{\"list_id\":" + q(listId) + ",\"title\":\"" + title + "\",\"description\":" + q(description)
                + ",\"due_at\":null,\"priority\":" + priority + ",\"sort_order\":0,\"annotation\":null},\"is_completed\":false,"
                + "\"completed_at\":null,\"created_at\":\"" + T0 + "\",\"updated_at\":\"" + T0 + "\",\"assignment\":null}";
    }

    @SuppressWarnings("unchecked")
    static void snapshot4(Request request, String changesCursor, String... items) {
        String json = "{\"schema_version\":1,\"items\":[" + String.join(",", items) + "],\"next_cursor\":null,\"changes_cursor\":\""
                + changesCursor + "\",\"server_time\":\"" + SERVER_TIME + "\"}";
        request.reply.success(TasksWire.decodeSnapshotResponse(json, request.limit, 4));
    }

    @SuppressWarnings("unchecked")
    static void changes4(Request request, String next, String... changes) {
        String json = "{\"schema_version\":1,\"changes\":[" + String.join(",", changes) + "],\"next_cursor\":\"" + next
                + "\",\"has_more\":false,\"server_time\":\"" + SERVER_TIME + "\"}";
        request.reply.success(TasksWire.decodeChangesResponse(json, request.limit, 4));
    }

    static String receiptJson(TasksWire.Mutation sent, String revision) {
        String kind = sent.operation.kind.wire();
        String resourceKind = kind.startsWith("list.") ? "list" : kind.startsWith("sender.") ? "block" : "task";
        return "{\"mutation_id\":\"" + sent.mutationId + "\",\"operation\":\"" + kind + "\",\"outcome\":\"committed\",\"resource_kind\":\""
                + resourceKind + "\",\"resource_id\":\"" + sent.operation.resourceId() + "\",\"resource_revision\":" + q(revision)
                + ",\"committed_at\":\"" + SERVER_TIME + "\"}";
    }

    static String ok(TasksWire.Mutation sent, String revision) {
        return "{\"mutation_id\":\"" + sent.mutationId + "\",\"status\":200,\"receipt\":" + receiptJson(sent, revision) + "}";
    }

    static String err(TasksWire.Mutation sent, int status, String code, String extras) {
        return "{\"mutation_id\":\"" + sent.mutationId + "\",\"status\":" + status + ",\"error\":{\"error\":\"" + code + "\",\"message\":\"m\""
                + extras + "}}";
    }

    static String skip(TasksWire.Mutation sent) {
        return "{\"mutation_id\":\"" + sent.mutationId + "\",\"status\":null,\"skipped\":true}";
    }

    /** TasksApi.classifyItem's table for the item codes these scenarios answer (the transport suite proves the table). */
    static TasksCloudRemote.Failure itemFailure(int status, TasksWire.ErrorEnvelope error, Integer echo) {
        TasksCloudRemote.Failure.Kind kind;
        String code = error.code;
        switch (status) {
            case 401: kind = TasksCloudRemote.Failure.Kind.SESSION; break;
            case 403: kind = "tasks_account_not_linked".equals(code) ? TasksCloudRemote.Failure.Kind.UNLINKED : TasksCloudRemote.Failure.Kind.TASK; break;
            case 429: kind = TasksCloudRemote.Failure.Kind.RATE_LIMITED; code = "rate_limited"; break;
            case 503:
                kind = TasksCloudRemote.Failure.Kind.UNAVAILABLE;
                if ("migration_paused".equals(error.wireReason)) code = "migration_paused";
                break;
            default: kind = TasksCloudRemote.Failure.Kind.TASK;
        }
        return new TasksCloudRemote.Failure(kind, code, error.reason, status, error.retryAfter, error.fields, error.effectiveAt, echo);
    }

    /** The scripted R4 answer to an open batch: results in request order, decoded strictly against the sent batch. */
    @SuppressWarnings("unchecked")
    static void answer(Request request, Integer retryAfter, String... results) {
        check("batch".equals(request.kind), "a batch request");
        String json = "{\"schema_version\":1,\"batch_id\":\"" + request.batch.batchId + "\",\"results\":[" + String.join(",", results)
                + "],\"retry_after\":" + (retryAfter == null ? "null" : retryAfter) + ",\"server_time\":\"" + SERVER_TIME + "\"}";
        TasksWire.BatchResponse response = TasksWire.decodeBatchResponse(json, request.batch, 4);
        ArrayList<TasksCloudRemote.Failure> failures = new ArrayList<>();
        for (TasksWire.BatchItemResult result : response.results) {
            failures.add(result.kind == TasksWire.BatchItemResult.Kind.ERROR ? itemFailure(result.status, result.error, 4) : null);
        }
        request.reply.success(new TasksCloudRemote.BatchReply(response, failures));
    }

    /** Every item committed, each at {@code revision}. */
    static void answerAll(Request request, String revision) {
        String[] results = new String[request.batch.items.size()];
        for (int i = 0; i < results.length; i++) results[i] = ok(request.batch.items.get(i).mutation, revision);
        answer(request, null, results);
    }

    static void failBatch(Request request, TasksCloudRemote.Failure.Kind kind, String code, int status, int retryAfter) {
        request.reply.failure(new TasksCloudRemote.Failure(kind, code, null, status, retryAfter));
    }

    static String body(TasksWire.Mutation mutation) { return new String(mutation.body(), StandardCharsets.UTF_8); }

    static String batchBody(Request request) { return new String(request.batch.body(), StandardCharsets.UTF_8); }

    /** An R4 harness (batch route) bootstrapped on a server that echoes wire 4. */
    static Harness r4(String... items) {
        Harness h = new Harness();
        h.remote.batchRoute = true;
        h.engine.start();
        h.drain();
        snapshot4(h.remote.take("snapshot"), "s0", items);
        h.drain();
        changes4(h.remote.take("changes"), "c1");
        h.drain();
        h.remote.none("bootstrap leaves nothing in flight");
        check(h.ledger.isServerWire4(), "echo: server_wire = 4");
        return h;
    }

    /** Answers every changes pull in flight (an idle wake may start one) with an empty, echoed page. */
    static void quiet(Harness h) {
        int pulls = 0;
        while (!h.remote.open().isEmpty() && "changes".equals(h.remote.open().get(0).kind)) {
            changes4(h.remote.take("changes"), "q" + (++pulls) + "-" + h.now);
            h.drain();
        }
    }

    static Request takeBatch(Harness h) {
        quiet(h);
        return h.remote.take("batch");
    }

    static void none(Harness h, String message) {
        quiet(h);
        h.remote.none(message);
    }

    static TasksCloudLedger.Entry entry(Harness h, String mutationId) {
        for (TasksCloudLedger.Entry entry : h.ledger.entries()) if (entry.mutationId.equals(mutationId)) return entry;
        return null;
    }

    static TasksCloudImport.Plan migrationPlan(int tasks) {
        ArrayList<String> ids = new ArrayList<>(), chains = new ArrayList<>(), keys = new ArrayList<>();
        ArrayList<TasksWire.Operation> operations = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            String taskId = TasksWire.newId();
            ids.add(TasksWire.newId());
            operations.add(TasksWire.Operation.taskCreate(taskId, content(null, "Imported " + i), null, false, null));
            chains.add(TasksCloudLedger.taskChain(taskId));
            keys.add(null);
        }
        return new TasksCloudImport.Plan(Collections.<ZenlyTaskImport.Mapping>emptyList(), ids, operations, chains, keys, 0, tasks, 0);
    }

    /** Records every analytics call as "method:arg". */
    static final class Sink implements TasksAnalyticsSink {
        final List<String> events = new ArrayList<>();
        @Override public void taskCreated(int account, boolean cloud, boolean hasDue, int priority, boolean inList) { events.add("taskCreated:" + cloud + ":" + inList); }
        @Override public void taskCompleted(int account, boolean cloud, boolean reopened) { events.add("taskCompleted:" + reopened); }
        @Override public void taskDeleted(int account, boolean cloud) { events.add("taskDeleted"); }
        @Override public void listCreated(int account, boolean cloud) { events.add("listCreated"); }
        @Override public void listDeleted(int account, boolean cloud, int tasks) { events.add("listDeleted:" + tasks); }
        @Override public void shared(int account, String action) { events.add("shared:" + action); }
        @Override public void migration(int account, String phase, Integer lists, Integer tasks, Integer skipped, Long durationS, String errorCode) { events.add("migration:" + phase); }
        @Override public void syncIssue(int account, String kind) { events.add("syncIssue:" + kind); }
        @Override public void error(int account, String domain, String code) { events.add("error:" + domain + ":" + code); }

        int count(String event) {
            int n = 0;
            for (String e : events) if (e.equals(event)) n++;
            return n;
        }
    }

    // ---- gate (runs first: nothing installed yet) ----

    static void gateDefaultForbiddenSendsNothing() {
        check(TasksCloudGate.permission(ACCOUNT) == TasksCloudGate.Permission.FORBIDDEN, "fail closed: FORBIDDEN until a source is installed");
        Harness h = new Harness();
        h.engine.start();
        h.drain();
        h.remote.none("before TasksCloudGate.install no Tasks request at all");
        check(h.last.status == TasksCloudState.Status.CONSENT_REQUIRED && h.engine.accountPaused(), "CONSENT_REQUIRED, realtime held");
        String local = h.engine.createTask(content(null, "Written offline"));
        h.drain();
        h.remote.none("a local edit queues, nothing is sent");
        check(h.state().task(local) != null && h.state().pending.get(0).state == TasksCloudState.PendingState.QUEUED, "the edit is in the overlay and the outbox");
        h.advance(TasksCloudEngine.POLL_MS * 5);
        h.remote.none("no poll either");

        TasksCloudGate.install(account -> TasksCloudGate.Permission.WAITING);
        h.engine.permissionChanged();
        h.drain();
        h.remote.none("WAITING sends nothing");
        check(h.last.status == TasksCloudState.Status.CONSENT_REQUIRED, "WAITING: CONSENT_REQUIRED");
        TasksCloudGate.install(account -> { throw new IllegalStateException("source failed"); });
        check(TasksCloudGate.permission(ACCOUNT) == TasksCloudGate.Permission.FORBIDDEN, "a failing source is FORBIDDEN");
        TasksCloudGate.install(account -> null);
        check(TasksCloudGate.permission(ACCOUNT) == TasksCloudGate.Permission.FORBIDDEN, "a null answer is FORBIDDEN");
        TasksCloudGate.install(account -> account == ACCOUNT + 1 ? TasksCloudGate.Permission.ALLOWED : TasksCloudGate.Permission.FORBIDDEN);
        h.engine.permissionChanged();
        h.drain();
        h.remote.none("another account's ALLOWED does not open this one");

        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        h.engine.permissionChanged();
        h.drain();
        check("snapshot".equals(h.remote.take("snapshot").kind), "ALLOWED + onPermissionChanged: the first request goes out");
        TasksCloudGate.install(null);
        check(TasksCloudGate.permission(ACCOUNT) == TasksCloudGate.Permission.FORBIDDEN, "uninstalled: fail closed again");
        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        h.sql.close();
    }

    // ---- consent fence and withdrawal ----

    static void consentFenceRequeuesWithoutAttemptAndBacksOff() {
        Sink sink = new Sink();
        TasksAnalyticsSink.Holder.set(sink);
        Harness h = r4();
        h.engine.stop(); // queue both before the engine drains
        String first = h.engine.createTask(content(null, "One"));
        String second = h.engine.createTask(content(null, "Two"));
        h.engine.start();
        h.drain();
        Request batch = h.remote.take("batch");
        check(batch.batch.items.size() == 2, "both creates in one batch");
        answer(batch, null, err(batch.batch.items.get(0).mutation, 403, "tasks_consent_required", ",\"reason\":\"withdrawn\",\"effective_at\":\"2026-10-30T08:00:00Z\""),
                skip(batch.batch.items.get(1).mutation));
        h.drain();
        TasksCloudState state = h.state();
        check(state.status == TasksCloudState.Status.CONSENT_FENCED && h.engine.accountPaused(), "CONSENT_FENCED; realtime held");
        check(state.pending.size() == 2 && state.task(first) != null && state.task(second) != null, "nothing discarded");
        for (TasksCloudLedger.Entry entry : h.ledger.entries()) {
            check(TasksCloudSchema.QUEUED.equals(entry.state) && entry.attempts == 0, "every entry queued again, no attempt counted");
        }
        check(sink.count("error:tasks_sync:tasks_consent_required") == 1, "error.occurred tasks_consent_required");
        h.engine.kick();
        h.advance(29_999);
        h.remote.none("held 30 s");
        h.advance(1);
        Request again = h.remote.take("batch");
        check(again.batch.items.size() == 2, "after 30 s the same entries go again");
        failBatch(again, TasksCloudRemote.Failure.Kind.TASK, "tasks_consent_required", 403, 0);
        h.drain();
        h.advance(59_999);
        h.remote.none("a second consecutive fence holds 60 s");
        h.advance(1);
        Request third = h.remote.take("batch");
        failBatch(third, TasksCloudRemote.Failure.Kind.TASK, "tasks_consent_required", 403, 0);
        h.drain();
        check(h.ledger.readLong(TasksCloudLedger.META_CONSENT_FENCED_UNTIL, 0) == h.now + 120_000, "doubling: 120 s");
        check(TasksCloudPolicy.consentFenceMs(480_000) == 900_000 && TasksCloudPolicy.consentFenceMs(900_000) == 900_000
                && TasksCloudPolicy.consentFenceMs(0) == 30_000, "30 s doubling, at most 900 s");
        h.engine.permissionChanged();
        h.drain();
        Request resumed = h.remote.take("batch");
        answerAll(resumed, "1");
        h.drain();
        check(h.ledger.readMeta(TasksCloudLedger.META_CONSENT_FENCE_MS) == null, "a permission change and a success end the doubling");
        for (TasksCloudLedger.Entry entry : h.ledger.entries()) check(entry.autoRequeues == 0, "a fence never counts towards auto_requeues");
        changes4(h.remote.take("changes"), "c2");
        h.drain();

        // A read answered 403 tasks_consent_required (every route): the same account-level hold.
        h.engine.refresh();
        h.drain();
        h.remote.take("changes").reply.failure(new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.TASK, "tasks_consent_required", null, 403, 0,
                null, "2026-10-30T08:00:00Z", 4));
        h.drain();
        check(h.state().status == TasksCloudState.Status.CONSENT_FENCED, "a fenced read holds the account too");
        check("2026-10-30T08:00:00Z".equals(h.ledger.readMeta(TasksCloudLedger.META_CONSENT_EFFECTIVE_AT)), "effective_at kept for the customer layer");
        h.engine.realtimeRefused(new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.TASK, "tasks_consent_required", null, 403, 0), h.remote.identity);
        check(h.engine.accountPaused(), "a fenced realtime call holds the account as well");
        TasksAnalyticsSink.Holder.set(null);
        h.sql.close();
    }

    static void withdrawalEpochRefusesInserts() {
        Harness h = r4(list4(TasksWire.newId(), "1", "1", "Work"));
        h.remote.batchRoute = true;
        String own = h.engine.createTask(content(null, "Mine"));
        h.drain();
        Request inFlight = h.remote.take("batch");
        long epoch = h.engine.consentEpoch();
        TasksCloudImport.Plan plan = migrationPlan(3);
        check(h.engine.enqueueImport(A, plan, TasksCloudSchema.ORIGIN_MIGRATION, epoch) == 3, "migration creates queued under the captured epoch");
        int[] discarded = h.engine.discardAfterConsentWithdrawal();
        check(discarded[0] == 1 && discarded[1] == 3, "every unsent entry discarded: {user 1, migration 3}");
        check(h.ledger.entries().isEmpty() && h.ledger.consentWithdrawn() && h.engine.consentEpoch() == epoch + 1,
                "withdrawn flag and the next epoch in the same transaction");
        answerAll(inFlight, "1");
        h.drain();
        check(h.ledger.entries().isEmpty(), "a late receipt of the orphaned batch changes nothing");
        TasksCloudError refused = expect(TasksCloudError.class, () -> h.engine.createTask(content(null, "After withdrawal")), "inserts refused while withdrawn");
        check(refused.code == TasksCloudError.Code.CONSENT_WITHDRAWN, "CONSENT_WITHDRAWN");
        expect(TasksCloudError.class, () -> h.engine.enqueueImport(A, migrationPlan(1)), "the explicit copy is refused too");
        h.engine.refresh();
        h.advance(TasksCloudEngine.POLL_MS * 3);
        h.remote.none("no Tasks request while withdrawn, even with the gate ALLOWED");
        check(h.state().status == TasksCloudState.Status.CONSENT_REQUIRED && h.state().lists.size() == 1, "the cache stays readable");

        long generation = h.ledger.readLong(TasksCloudLedger.META_RESYNC_GENERATION, 0);
        h.engine.resumeAfterConsentRegrant();
        h.drain();
        check(!h.ledger.consentWithdrawn() && h.ledger.readLong(TasksCloudLedger.META_RESYNC_GENERATION, 0) == generation + 1,
                "re-grant: inserts allowed, a resnapshot counted");
        check(h.remote.take("snapshot").cursor == null, "re-grant pulls a fresh snapshot");
        expect(TasksCloudError.class, () -> h.engine.enqueueImport(A, migrationPlan(1), TasksCloudSchema.ORIGIN_MIGRATION, epoch),
                "a migration step that captured the old epoch enqueues nothing");
        check(h.engine.enqueueImport(A, migrationPlan(2), TasksCloudSchema.ORIGIN_MIGRATION, epoch + 1) == 2, "the current epoch enqueues");
        check(own != null, "own create id returned");
        h.sql.close();
    }

    // ---- batch drain ----

    static void batchDrainSkippedOriginsAndItemErrors() {
        Sink sink = new Sink();
        TasksAnalyticsSink.Holder.set(sink);
        Harness h = r4();
        h.engine.stop();
        check(h.engine.enqueueImport(A, migrationPlan(2), TasksCloudSchema.ORIGIN_MIGRATION, h.engine.consentEpoch()) == 2, "two migration creates");
        String a = h.engine.createTask(content(null, "User A"));
        h.engine.start();
        h.drain();
        Request first = h.remote.take("batch");
        check(first.batch.items.size() == 1 && first.batch.items.get(0).origin == TasksWire.Origin.USER, "user entries first, homogeneous origin");
        answerAll(first, "1");
        h.drain();
        Request migration = h.remote.take("batch");
        check(migration.batch.items.size() == 2 && batchBody(migration).contains("{\"origin\":\"migration\",\"schema_version\":1,\"mutation_id\""),
                "then the migration creates, marked origin migration");
        TasksWire.Mutation skippedItem = migration.batch.items.get(1).mutation;
        answer(migration, null, ok(migration.batch.items.get(0).mutation, "1"), skip(skippedItem));
        h.drain();
        Request retried = h.remote.take("batch");
        TasksCloudLedger.Entry skipped = entry(h, skippedItem.mutationId);
        check(skipped.attempts == 1, "skipped: requeued without an attempt (only this send counts)");
        check(retried.batch.items.size() == 1 && retried.batch.items.get(0).mutation.mutationId.equals(skippedItem.mutationId)
                && Arrays.equals(retried.batch.items.get(0).mutation.body(), skippedItem.body()), "the skipped item goes again with the same id and bytes");
        answerAll(retried, "1");
        h.drain();
        changes4(h.remote.take("changes"), "c2");
        h.drain();
        // A batch of which nothing started (all skipped, no retry_after): no immediate resend, a backoff first.
        h.engine.createTask(content(null, "Skipped twice"));
        h.drain();
        Request allSkipped = h.remote.take("batch");
        answer(allSkipped, null, skip(allSkipped.batch.items.get(0).mutation));
        h.drain();
        none(h, "continuation only after progress");
        h.advance(TasksCloudPolicy.backoffMs(1));
        answerAll(takeBatch(h), "1");
        h.drain();
        quiet(h);

        h.engine.stop();
        String b = h.engine.createTask(content(null, "Invalid"));
        String c = h.engine.createTask(content(null, "Blocked"));
        String d = h.engine.createTask(content(null, "Offline"));
        h.engine.start();
        h.drain();
        Request mixed = takeBatch(h);
        check(mixed.batch.items.size() == 3, "three chain-free creates");
        answer(mixed, null, err(mixed.batch.items.get(0).mutation, 422, "tasks_invalid_request", ""),
                err(mixed.batch.items.get(1).mutation, 403, "tasks_creation_not_permitted", ""),
                err(mixed.batch.items.get(2).mutation, 409, "tasks_mutation_in_flight", ""));
        h.drain();
        check(h.pending(mixed.batch.items.get(0).mutation.mutationId).state == TasksCloudState.PendingState.INVALID
                && h.pending(mixed.batch.items.get(1).mutation.mutationId).state == TasksCloudState.PendingState.BLOCKED_CREATION
                && h.pending(mixed.batch.items.get(2).mutation.mutationId).state == TasksCloudState.PendingState.BACKOFF,
                "each item failure applied exactly as on the single route");
        check(sink.count("syncIssue:invalid") == 1, "a terminal 422 emits sync_issue invalid");
        check(sink.count("taskCreated:true:false") == 5, "task_created cloud scope for each own create, never for migration creates");
        check(a != null && b != null && c != null && d != null, "ids");
        none(h, "nothing else is due");
        TasksAnalyticsSink.Holder.set(null);
        h.sql.close();
    }

    static void fallbackUserOnlyMigrationNeverSingle() {
        Harness h = r4();
        h.engine.enqueueImport(A, migrationPlan(2), TasksCloudSchema.ORIGIN_MIGRATION, h.engine.consentEpoch());
        h.drain();
        Request batch = h.remote.take("batch");
        failBatch(batch, TasksCloudRemote.Failure.Kind.PROTOCOL, "request_rejected", 404, 0);
        h.drain();
        none(h, "404: migration entries never take the single route, they wait");
        check(h.ledger.batchRouteUnavailable(h.now), "batch_unavailable_until set");
        for (TasksCloudLedger.Entry entry : h.ledger.entries()) check(entry.attempts == 0 && TasksCloudSchema.QUEUED.equals(entry.state), "not counted");
        String user = h.engine.createTask(content(null, "User during the outage"));
        h.drain();
        Request single = h.remote.take("mutate");
        check(single.mutation.operation.resourceId().equals(user), "user entries use the single route meanwhile");
        receipt(single, "1");
        h.drain();
        none(h, "migration still waits");
        h.advance(TasksCloudPolicy.BATCH_UNAVAILABLE_MS);
        Request back = takeBatch(h);
        check(back.batch.items.size() == 2 && back.batch.items.get(0).origin == TasksWire.Origin.MIGRATION, "after 15 min the batch route again");
        failBatch(back, TasksCloudRemote.Failure.Kind.UNAVAILABLE, "batch_disabled", 503, 0);
        h.drain();
        none(h, "batch_disabled: the same fallback");
        check(h.ledger.batchRouteUnavailable(h.now), "fallback window open again");
        h.advance(TasksCloudPolicy.BATCH_UNAVAILABLE_MS);
        for (int i = 1; i <= 3; i++) {
            Request unavailable = takeBatch(h);
            failBatch(unavailable, TasksCloudRemote.Failure.Kind.UNAVAILABLE, "tasks_unavailable", 503, 0);
            h.drain();
            if (i < 3) {
                check(!h.ledger.batchRouteUnavailable(h.now), "503 #" + i + ": still the batch route");
                h.advance(TasksCloudPolicy.MAX_BACKOFF_MS);
            }
        }
        check(h.ledger.batchRouteUnavailable(h.now), "three consecutive 503: the single-route fallback");
        none(h, "and migration entries still wait");
        h.sql.close();
    }

    static void migrationPausedHoldsMigrationOnly() {
        Harness h = r4();
        h.engine.enqueueImport(A, migrationPlan(2), TasksCloudSchema.ORIGIN_MIGRATION, h.engine.consentEpoch());
        h.drain();
        Request batch = h.remote.take("batch");
        answer(batch, 3600, err(batch.batch.items.get(0).mutation, 503, "tasks_unavailable", ",\"reason\":\"migration_paused\",\"retry_after\":3600"),
                skip(batch.batch.items.get(1).mutation));
        h.drain();
        check(h.ledger.readLong(TasksCloudLedger.META_MIGRATION_PAUSED_UNTIL, 0) == h.now + 3_600_000, "migration_paused_until = now + retry_after");
        for (TasksCloudLedger.Entry entry : h.ledger.entries()) check(entry.attempts == 0, "never ran: no attempt");
        String user = h.engine.createTask(content(null, "User keeps going"));
        h.drain();
        Request userBatch = h.remote.take("batch");
        check(userBatch.batch.items.size() == 1 && userBatch.batch.items.get(0).mutation.operation.resourceId().equals(user),
                "user items continue while migrations are paused");
        answerAll(userBatch, "1");
        h.drain();
        none(h, "migration held");
        h.advance(3_600_000);
        check(takeBatch(h).batch.items.get(0).origin == TasksWire.Origin.MIGRATION, "after retry_after the migration resumes");
        h.sql.close();
    }

    static void adaptiveBatchSizeNeverStalls() {
        Harness h = r4();
        h.engine.enqueueImport(A, migrationPlan(150), TasksCloudSchema.ORIGIN_MIGRATION, h.engine.consentEpoch());
        h.drain();
        Request first = h.remote.take("batch");
        int initial = first.batch.items.size();
        check(first.batch.bodyBytes() <= TasksCloudPolicy.BATCH_INITIAL_BYTES - TasksCloudPolicy.BATCH_PROOF_RESERVE && initial > 1,
                "the first batch fits 64 KiB minus the proof reserve");
        failBatch(first, TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0, 0);
        h.drain();
        check(h.ledger.batchBudget() == TasksCloudPolicy.BATCH_INITIAL_BYTES / 2, "a timeout halves batch_byte_budget");
        for (TasksCloudLedger.Entry entry : h.ledger.entries()) check(entry.attempts == 0, "a multi-item batch failing as a whole counts no attempt");
        check(h.state().status == TasksCloudState.Status.OFFLINE, "and the account backs off");
        h.advance(TasksCloudPolicy.backoffMs(1));
        Request smaller = takeBatch(h);
        check(smaller.batch.items.size() == 1 && smaller.batch.items.get(0).mutation.mutationId.equals(first.batch.items.get(0).mutation.mutationId),
                "the same entries, the next batch is smaller (one entry once the budget is under the reserve)");
        failBatch(smaller, TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0, 0);
        h.drain();
        TasksCloudLedger.Entry alone = entry(h, smaller.batch.items.get(0).mutation.mutationId);
        check(alone.attempts == 1 && TasksCloudSchema.BACKOFF.equals(alone.state), "a one-entry batch counts its attempt (as a single send)");
        h.advance(TasksCloudPolicy.MAX_BACKOFF_MS);
        Request next = takeBatch(h);
        answerAll(next, "1");
        h.drain();
        int budget = h.ledger.batchBudget();
        answerAll(takeBatch(h), "1");
        h.drain();
        check(h.ledger.batchBudget() == budget, "two successes: unchanged");
        answerAll(takeBatch(h), "1");
        h.drain();
        check(h.ledger.batchBudget() == budget * 2, "three consecutive successes double the budget");
        int guard = 0;
        while (!h.remote.open().isEmpty() && guard++ < 200) {
            Request open = h.remote.open().get(0);
            if ("changes".equals(open.kind)) changes4(h.remote.take("changes"), "d" + guard);
            else answerAll(h.remote.take("batch"), "1");
            h.drain();
        }
        check(h.engine.unsentCounts()[1] == 0, "every entry was delivered: none stalled because of batch size");
        check(TasksCloudPolicy.shrinkBatch(1500) == TasksCloudPolicy.BATCH_MIN_BYTES && TasksCloudPolicy.growBatch(400_000) == TasksCloudPolicy.BATCH_MAX_BYTES,
                "floor one entry, cap 512 KiB");
        h.sql.close();
    }

    static void oneEntry413SendsUserAloneAndMigrationInvalid() {
        Harness h = r4();
        String user = h.engine.createTask(content(null, "Too big for a batch"));
        h.drain();
        Request batch = h.remote.take("batch");
        failBatch(batch, TasksCloudRemote.Failure.Kind.PROTOCOL, "request_rejected", 413, 0);
        h.drain();
        Request alone = h.remote.take("mutate");
        check(alone.mutation.operation.resourceId().equals(user), "a one-entry 413 of a user entry goes alone through the single route");
        receipt(alone, "1");
        h.drain();
        quiet(h);
        h.engine.enqueueImport(A, migrationPlan(1), TasksCloudSchema.ORIGIN_MIGRATION, h.engine.consentEpoch());
        h.drain();
        Request migration = takeBatch(h);
        failBatch(migration, TasksCloudRemote.Failure.Kind.PROTOCOL, "request_rejected", 413, 0);
        h.drain();
        none(h, "a migration create is never sent alone through the single route");
        check(TasksCloudSchema.INVALID.equals(entry(h, migration.batch.items.get(0).mutation.mutationId).state), "it is invalid, as on R3");
        h.sql.close();
    }

    static void migrationDrain5000() {
        Harness h = r4();
        int n = 5000;
        long started = System.nanoTime();
        check(h.engine.enqueueImport(A, migrationPlan(n), TasksCloudSchema.ORIGIN_MIGRATION, h.engine.consentEpoch()) == n, "5000 migration creates queued");
        h.drain();
        int batches = 0, items = 0, pulls = 0, maxItems = 0;
        while (!h.remote.open().isEmpty()) {
            Request open = h.remote.open().get(0);
            check(!"mutate".equals(open.kind), "migration entries never use the single route");
            if ("changes".equals(open.kind)) {
                pulls++;
                changes4(h.remote.take("changes"), "p" + pulls);
            } else {
                Request batch = h.remote.take("batch");
                batches++;
                items += batch.batch.items.size();
                maxItems = Math.max(maxItems, batch.batch.items.size());
                check(batch.batch.items.size() <= TasksWire.MAX_BATCH_ITEMS && batch.batch.bodyBytes() <= TasksWire.MAX_BATCH_BYTES, "≤ 100 items and ≤ 512 KiB");
                answerAll(batch, "1");
            }
            h.drain();
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        check(items == n && maxItems == TasksWire.MAX_BATCH_ITEMS && h.engine.unsentCounts()[1] == 0, "every create delivered in batches");
        check(pulls >= batches / TasksCloudPolicy.MAX_BATCHES_PER_CYCLE - 1, "at most 10 batches per cycle, continuation after each pull");
        check(h.ledger.decodes() <= n + 64, "each body decoded about once (" + h.ledger.decodes() + " decodes): no O(N^2) re-decode");
        System.out.println("  5000 migration creates: " + batches + " batches, " + pulls + " pulls, " + h.ledger.decodes() + " decodes, " + millis + " ms (host bridge)");
        h.sql.close();
    }

    // ---- downgrade, patch, sealing fix ----

    static void taskPatchAndDowngrade() {
        String t = TasksWire.newId(), l = TasksWire.newId();
        Harness h = r4(task4(t, "3", null, "Report", "Notes", 1), list4(l, "4", "6", "Old"));
        h.engine.stop();
        String patchId = h.engine.updateTask(t, new TasksWire.Content(null, "Report v2", "Notes", null, 3, 0, null));
        check(h.engine.updateTask(t, new TasksWire.Content(null, "Report v2", "Notes", null, 3, 0, null)) == null, "nothing changed: no entry");
        String deleteId = h.engine.deleteList(l);
        TasksWire.Operation.TaskPatch op = (TasksWire.Operation.TaskPatch) entry(h, patchId).operation;
        check(op.fields().equals(Arrays.asList(TasksWire.ContentField.TITLE, TasksWire.ContentField.PRIORITY))
                && "Report".equals(op.expect.get(TasksWire.ContentField.TITLE)) && Integer.valueOf(1).equals(op.expect.get(TasksWire.ContentField.PRIORITY)),
                "server_wire 4: a task.patch of only the changed fields, expecting the values shown");
        check("Report v2".equals(h.state().task(t).content.title) && h.state().task(t).content.priority == 3, "patch overlay applied");
        TasksWire.Operation.ListDelete fenced = (TasksWire.Operation.ListDelete) entry(h, deleteId).operation;
        check("4".equals(fenced.baseRevision) && "6".equals(fenced.baseMembersRevision), "an unrelated list.delete seals the members fence at once");
        // The server was rolled back to R3: no batch route, and a 422 for each R4-only entry on the single route.
        h.engine.start();
        h.drain();
        failBatch(h.remote.take("batch"), TasksCloudRemote.Failure.Kind.PROTOCOL, "request_rejected", 404, 0);
        h.drain();
        Request patchSent = h.remote.take("mutate");
        check(patchSent.mutation.mutationId.equals(patchId), "the patch goes through the single route");
        fail(patchSent, TasksCloudRemote.Failure.Kind.TASK, "tasks_invalid_request", null, 0);
        h.drain();
        check(!h.ledger.isServerWire4(), "the 422 on an R4-only entry clears server_wire");
        check(entry(h, patchId) == null, "re-encoded in its place under a NEW id");
        Request update = h.remote.take("mutate");
        check(update.mutation.operation instanceof TasksWire.Operation.TaskUpdate && !update.mutation.mutationId.equals(patchId)
                && body(update.mutation).contains("\"base_revision\":\"3\"") && body(update.mutation).contains("\"title\":\"Report v2\"")
                && body(update.mutation).contains("\"priority\":3") && body(update.mutation).contains("\"description\":\"Notes\""),
                "outbox order kept: the task.update of the shadow content with the set values next, base = the shadow revision");
        fail(update, TasksCloudRemote.Failure.Kind.TASK, "tasks_invalid_request", null, 0);
        h.drain();
        check(TasksCloudSchema.INVALID.equals(entry(h, update.mutation.mutationId).state), "only once: an R3 entry's 422 is invalid");
        Request deleteSent = h.remote.take("mutate");
        check(deleteSent.mutation.mutationId.equals(deleteId), "then the members-fenced list.delete");
        fail(deleteSent, TasksCloudRemote.Failure.Kind.TASK, "tasks_invalid_request", null, 0);
        h.drain();
        Request delete = h.remote.take("mutate");
        TasksWire.Operation.ListDelete plain = (TasksWire.Operation.ListDelete) delete.mutation.operation;
        check(!delete.mutation.mutationId.equals(deleteId) && "4".equals(plain.baseRevision) && plain.baseMembersRevision == null,
                "list.delete re-encoded without the members fence");
        receipt(delete, "5");
        h.drain();
        // A 2xx without the echo: an R3 server.
        Request pull = h.remote.take("changes");
        pull.reply.success(TasksWire.decodeChangesResponse("{\"schema_version\":1,\"changes\":[],\"next_cursor\":\"r3\",\"has_more\":false,\"server_time\":\""
                + SERVER_TIME + "\"}", pull.limit, null));
        h.drain();
        check(!h.ledger.isServerWire4(), "a 2xx without the echo keeps server_wire cleared");
        h.engine.discard(update.mutation.mutationId);
        String r3 = h.engine.updateTask(t, new TasksWire.Content(null, "Report v3", "Notes", null, 1, 0, null));
        check(entry(h, r3).operation instanceof TasksWire.Operation.TaskUpdate, "server_wire < 4: task.update again");
        h.sql.close();

        Harness g = r4();
        g.engine.refresh();
        g.drain();
        Request plainPull = g.remote.take("changes");
        plainPull.reply.success(TasksWire.decodeChangesResponse("{\"schema_version\":1,\"changes\":[],\"next_cursor\":\"n\",\"has_more\":false,\"server_time\":\""
                + SERVER_TIME + "\"}", plainPull.limit, null));
        g.drain();
        check(!g.ledger.isServerWire4(), "an R4 server's 2xx without the echo (rolled back) clears server_wire");
        g.sql.close();
    }

    /**
     * Sealing fix (acceptance): task created in L, then L renamed and deleted, against a remote that models R4 members —
     * L's revision covers title/order, members_revision every member change. Nothing answers 409.
     */
    static void sealingFixNoConflictAgainstR4Members() {
        String l = TasksWire.newId();
        Harness h = r4(list4(l, "1", "1", "Work"));
        String[] server = {"1", "1"}; // L: revision, members_revision
        String taskId = h.engine.createTask(content(l, "In L"));
        h.drain();
        Request create = h.remote.take("batch");
        String rename = h.engine.updateList(l, "Renamed", 0);
        String delete = h.engine.deleteList(l);
        h.drain();
        TasksCloudLedger.Entry waiting = entry(h, delete);
        check(waiting.waitsMembers && !waiting.sealed, "list.delete stored unsealed, waiting for its list's own entries");
        server[1] = "2"; // the create advanced L's members
        answerAll(create, "1");
        h.drain();
        Request renameBatch = h.remote.take("batch");
        TasksWire.Operation.ListUpdate update = (TasksWire.Operation.ListUpdate) renameBatch.batch.items.get(0).mutation.operation;
        check(update.baseRevision.equals(server[0]), "list.update sealed from the list revision (R4 does not bump it for members)");
        check(renameBatch.batch.items.size() == 1 && h.pending(delete).state == TasksCloudState.PendingState.WAITING, "the delete still waits");
        server[0] = "2";
        answerAll(renameBatch, server[0]);
        h.drain();
        Request settle = h.remote.take("changes");
        check(h.pending(delete).state == TasksCloudState.PendingState.WAITING, "an acknowledged but unsettled entry keeps the delete waiting");
        Harness restarted = h.restart();
        restarted.remote.batchRoute = true;
        restarted.ledger.recover();
        check(restarted.pending(delete).state == TasksCloudState.PendingState.WAITING, "markOrphanedSuccessors skips a members wait");
        restarted.engine.start();
        restarted.drain();
        changes4(restarted.remote.take("changes"), "c2", upsert(5, task4(taskId, "1", l, "In L", null, 0)), upsert(6, list4(l, server[0], server[1], "Renamed")));
        restarted.drain();
        Request deleteBatch = restarted.remote.take("batch");
        TasksWire.Operation.ListDelete sealed = (TasksWire.Operation.ListDelete) deleteBatch.batch.items.get(0).mutation.operation;
        check(sealed.baseRevision.equals(server[0]) && server[1].equals(sealed.baseMembersRevision),
                "sealed from the settled shadow: base_revision and base_members_revision match the server, no 409");
        check(rename != null && settle != null, "ids");
        restarted.sql.close();
    }

    // ---- provenance and phantom settle ----

    static void provenanceAndPhantomSettle() {
        final List<String> attempts = new ArrayList<>(), acknowledged = new ArrayList<>();
        final boolean[] refuse = {true};
        Harness h = r4();
        h.engine.setProvenance(new TasksCloudEngine.Provenance() {
            @Override public boolean recordAttempts(List<String> ids) { if (refuse[0]) return false; attempts.addAll(ids); return true; }
            @Override public boolean acknowledged(List<String> ids) { acknowledged.addAll(ids); return !refuse[0]; }
        });
        String taskId = h.engine.createTask(content(null, "Imported earlier"));
        h.drain();
        h.remote.none("provenance not recorded: the create is not sent yet");
        refuse[0] = false;
        h.advance(TasksCloudEngine.REQUEUE_MS);
        Request batch = h.remote.take("batch");
        String id = batch.batch.items.get(0).mutation.mutationId;
        check(attempts.equals(Collections.singletonList(id)), "the attempt is recorded before the first send");
        answerAll(batch, "1");
        h.drain();
        check(acknowledged.contains(id) && h.ledger.pendingImportAcks().isEmpty(), "on ack: import_ack_pending flushed to the local store");
        // The phantom rule: the cloud never shows the create (e.g. a durable replay of a since-deleted task).
        changes4(h.remote.take("changes"), "c2");
        h.drain();
        check(h.state().task(taskId) != null, "kept through the next cycle (a row coalesced beyond its watermark shows in the one after)");
        h.engine.refresh();
        h.drain();
        changes4(h.remote.take("changes"), "c3");
        h.drain();
        TasksCloudState state = h.state();
        check(state.task(taskId) == null && h.ledger.entries().isEmpty(), "absent after a later complete cycle: settled");
        check(state.notices.size() == 1 && state.notices.get(0).kind == TasksCloudState.Notice.Kind.REMOVED_IN_CLOUD
                && taskId.equals(state.notices.get(0).resourceId), "with a REMOVED_IN_CLOUD notice");
        h.sql.close();
    }

    // ---- §7.3 ----

    static void autoResolutionTable() {
        Sink sink = new Sink();
        TasksAnalyticsSink.Holder.set(sink);
        String t = TasksWire.newId(), u = TasksWire.newId(), v = TasksWire.newId(), l = TasksWire.newId(), m = TasksWire.newId();
        Harness h = r4(task4(t, "3", null, "Title", "Desc", 1), task4(u, "2", null, "Done?", null, 0), task4(v, "5", null, "Keep", null, 0),
                list4(l, "4", "9", "Home"), list4(m, "6", "2", "Errands"));
        // task.patch field_changed {title}: the priority part is resent automatically, the title part stays explicit.
        h.engine.stop();
        String patchId = h.engine.updateTask(t, new TasksWire.Content(null, "Mine", "Desc", null, 4, 0, null));
        String complete = h.engine.setCompleted(u, true);
        String delete = h.engine.holdDelete(v);
        h.engine.promote(delete);
        String rename = h.engine.updateList(l, "Home 2", 0);
        String listDelete = h.engine.deleteList(m);
        h.engine.start();
        h.drain();
        Request batch = h.remote.take("batch");
        check(batch.batch.items.size() == 5, "five independent entries in one batch");
        String moved = ",\"reason\":\"revision_changed\"";
        answer(batch, null,
                err(batch.batch.items.get(0).mutation, 409, "tasks_conflict", ",\"reason\":\"field_changed\",\"fields\":[\"title\"]"),
                err(batch.batch.items.get(1).mutation, 409, "tasks_conflict", moved),
                err(batch.batch.items.get(2).mutation, 409, "tasks_conflict", moved),
                err(batch.batch.items.get(3).mutation, 409, "tasks_conflict", moved),
                err(batch.batch.items.get(4).mutation, 409, "tasks_conflict", moved));
        h.drain();
        check(h.pending(patchId).state == TasksCloudState.PendingState.WAITING && h.pending(patchId).conflictFields.equals(
                Collections.singletonList(TasksWire.ContentField.TITLE)), "a resolvable conflict waits for the pull, fields kept");
        changes4(h.remote.take("changes"), "c2",
                upsert(10, task4(t, "4", null, "Theirs", "Desc", 1)),
                upsert(11, "{\"kind\":\"task\",\"id\":\"" + u + "\",\"revision\":\"3\",\"owner_tg_id\":\"777\",\"viewer_role\":\"owner\",\"content\":{\"list_id\":null,"
                        + "\"title\":\"Done?\",\"description\":null,\"due_at\":null,\"priority\":0,\"sort_order\":0,\"annotation\":null},\"is_completed\":true,"
                        + "\"completed_at\":\"" + T0 + "\",\"created_at\":\"" + T0 + "\",\"updated_at\":\"" + T0 + "\",\"assignment\":null}"),
                upsert(12, task4(v, "6", null, "Keep (edited)", null, 0)),
                upsert(13, "{\"kind\":\"list\",\"id\":\"" + l + "\",\"revision\":\"5\",\"members_revision\":\"9\",\"title\":\"Home\",\"sort_order\":7,"
                        + "\"created_at\":\"" + T0 + "\",\"updated_at\":\"" + T0 + "\"}"),
                upsert(14, list4(m, "7", "2", "Errands renamed")));
        h.drain();
        TasksCloudState state = h.state();
        check(h.pending(patchId) == null && h.pending(complete) == null && h.pending(delete) == null && h.pending(rename) == null
                && h.pending(listDelete) == null, "every conflicted entry was replaced or dropped");
        check(state.task(u).completed && state.task(v) != null, "completion already as intended → dropped; the delete dropped, task kept");
        boolean kept = false;
        for (TasksCloudState.Notice notice : state.notices) kept |= notice.kind == TasksCloudState.Notice.Kind.DELETE_KEPT && v.equals(notice.resourceId);
        check(kept, "DELETE_KEPT notice");
        Request resolved = h.remote.take("batch");
        TasksWire.Operation.TaskPatch auto = null;
        TasksWire.Operation.ListUpdate merged = null;
        TasksWire.Operation.ListDelete resealed = null;
        for (TasksWire.Batch.Item item : resolved.batch.items) {
            TasksWire.Operation op = item.mutation.operation;
            if (op instanceof TasksWire.Operation.TaskPatch) auto = (TasksWire.Operation.TaskPatch) op;
            if (op instanceof TasksWire.Operation.ListUpdate) merged = (TasksWire.Operation.ListUpdate) op;
            if (op instanceof TasksWire.Operation.ListDelete) resealed = (TasksWire.Operation.ListDelete) op;
        }
        check(auto != null && auto.fields().equals(Collections.singletonList(TasksWire.ContentField.PRIORITY))
                && Integer.valueOf(1).equals(auto.expect.get(TasksWire.ContentField.PRIORITY)), "patch: fields outside F resent, expecting the server's values");
        check(merged != null && "Home 2".equals(merged.title) && merged.sortOrder == 7 && "5".equals(merged.baseRevision),
                "list.update: disjoint three-way merge resealed on the fresh revision");
        check(resealed != null && "7".equals(resealed.baseRevision) && "2".equals(resealed.baseMembersRevision),
                "list.delete revision_changed: fresh base, the members fence it was decided under kept");
        TasksCloudState.Pending explicitPart = null;
        for (TasksCloudState.Pending pending : state.pending) if (pending.state == TasksCloudState.PendingState.CONFLICT) explicitPart = pending;
        check(explicitPart != null && explicitPart.kind == TasksWire.OperationKind.TASK_PATCH && explicitPart.reason == TasksWire.ConflictReason.FIELD_CHANGED
                && explicitPart.conflictFields.equals(Collections.singletonList(TasksWire.ContentField.TITLE)), "the title part stays explicit");
        check(sink.count("syncIssue:conflict_auto_resolved") == 5 && sink.count("syncIssue:conflict_manual") == 1, "sync_issue per outcome");
        for (TasksCloudLedger.Entry entry : h.ledger.entries()) {
            if (TasksCloudSchema.SENDING.equals(entry.state)) check(entry.autoResolutions == 1, "a replacement inherits auto_resolutions + 1");
        }
        // Again, and again: at 2 automatic resolutions the next conflict is explicit.
        String[] results = new String[resolved.batch.items.size()];
        for (int i = 0; i < results.length; i++) {
            TasksWire.Mutation sent = resolved.batch.items.get(i).mutation;
            results[i] = sent.operation instanceof TasksWire.Operation.ListDelete
                    ? err(sent, 409, "tasks_conflict", ",\"reason\":\"members_changed\"") : ok(sent, "8");
        }
        answer(resolved, null, results);
        h.drain();
        TasksCloudState.Pending members = null;
        for (TasksCloudState.Pending pending : h.state().pending) if (pending.kind == TasksWire.OperationKind.LIST_DELETE) members = pending;
        check(members != null && members.state == TasksCloudState.PendingState.CONFLICT && members.reason == TasksWire.ConflictReason.MEMBERS_CHANGED,
                "members_changed: explicit confirmation");
        TasksCloudLedger.Entry twice = null;
        for (TasksCloudLedger.Entry entry : h.ledger.entries()) if (entry.operation instanceof TasksWire.Operation.ListDelete) twice = entry;
        check(twice != null && !twice.autoResolvePending, "never marked automatic");
        check(!TasksCloudAutoResolve.resolvesAutomatically(withResolutions(twice, 2), TasksWire.ConflictReason.REVISION_CHANGED), "≤ 2 automatic resolutions per intent");
        check(TasksCloudAutoResolve.merge("a", "a", "b").equals("b") && TasksCloudAutoResolve.merge("a", "b", "a").equals("b")
                && TasksCloudAutoResolve.merge("a", "b", "b").equals("b") && TasksCloudAutoResolve.merge("a", "b", "c") == null, "three-way merge");
        TasksAnalyticsSink.Holder.set(null);
        h.sql.close();
    }

    /** The same row read back with another auto_resolutions value (a ledger row is immutable). */
    static TasksCloudLedger.Entry withResolutions(TasksCloudLedger.Entry entry, int resolutions) {
        Object[] row = {entry.seq, entry.mutationId, entry.principal, entry.kind, entry.resourceId, entry.chain, entry.listKey, entry.sealed ? 1L : 0L,
                entry.resolvedBase, entry.state, entry.sent ? 1L : 0L, entry.error, entry.reason == null ? null : entry.reason.wire(), (long) entry.attempts,
                entry.notBefore, entry.ackedRevision, entry.sourceListKey, entry.ackedResource, entry.origin, (long) entry.autoRequeues, (long) resolutions,
                null, null, entry.baseSnapshot, entry.waitsMembers ? 1L : 0L, entry.ackedCycle};
        return new TasksCloudLedger.Entry(row, entry.mutation);
    }

    // ---- §7.4 ----

    static void requeueBounds() {
        Sink sink = new Sink();
        TasksAnalyticsSink.Holder.set(sink);
        Harness h = r4();
        final boolean[] mayCreate = {false};
        h.engine.setCreationGate(() -> mayCreate[0]);
        String stalled = h.engine.createTask(content(null, "Stalls"));
        h.drain();
        for (int attempt = 1; attempt <= TasksCloudPolicy.MAX_AUTO_ATTEMPTS; attempt++) {
            if (attempt > 1) h.advance(TasksCloudPolicy.MAX_BACKOFF_MS);
            Request alone = takeBatch(h);
            failBatch(alone, TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0, 0);
            h.drain();
        }
        check(h.pending(h.ledger.entries().get(0).mutationId).state == TasksCloudState.PendingState.STALLED, "one-entry batches stall after the attempt bound");
        for (int occasion = 1; occasion <= TasksCloudPolicy.MAX_AUTO_REQUEUES + 1; occasion++) {
            h.engine.requeue(TasksCloudPolicy.Occasion.FOREGROUND);
            h.drain();
            if (occasion <= TasksCloudPolicy.MAX_AUTO_REQUEUES) {
                Request again = takeBatch(h);
                check(entry(h, again.batch.items.get(0).mutation.mutationId).attempts == 1, "requeued with attempts reset (same id)");
                for (int attempt = 1; attempt < TasksCloudPolicy.MAX_AUTO_ATTEMPTS; attempt++) {
                    failBatch(again, TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0, 0);
                    h.drain();
                    h.advance(TasksCloudPolicy.MAX_BACKOFF_MS);
                    again = takeBatch(h);
                }
                failBatch(again, TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0, 0);
                h.drain();
            } else {
                none(h, "at most 5 automatic requeues, then explicit");
            }
        }
        check(sink.count("syncIssue:stalled") == TasksCloudPolicy.MAX_AUTO_REQUEUES, "a sync_issue stalled per requeue");
        h.engine.requeue(TasksCloudPolicy.Occasion.ENTITLEMENT);
        h.drain();
        none(h, "an entitlement change does not requeue STALLED");
        h.engine.discard(h.ledger.entries().get(0).mutationId);
        h.drain();

        String blocked = h.engine.createTask(content(null, "Paid"));
        h.drain();
        Request refused = takeBatch(h);
        answer(refused, null, err(refused.batch.items.get(0).mutation, 403, "tasks_creation_not_permitted", ""));
        h.drain();
        h.engine.requeue(TasksCloudPolicy.Occasion.FOREGROUND);
        h.drain();
        none(h, "BLOCKED_CREATION stays while the local create gate refuses");
        mayCreate[0] = true;
        h.engine.requeue(TasksCloudPolicy.Occasion.ENTITLEMENT);
        h.drain();
        check(takeBatch(h).batch.items.get(0).mutation.operation.resourceId().equals(blocked), "requeued once creation is allowed");
        check(sink.count("syncIssue:blocked_creation") == 1 && stalled != null, "sync_issue blocked_creation");
        TasksAnalyticsSink.Holder.set(null);
        h.sql.close();
    }

    // ---- unlinked intents, analytics, boards ----

    static void unlinkedIntentsSealedWithTheBoundPrincipal() {
        Harness h = r4();
        h.remote.identity = new TasksCloudRemote.Identity(TG, null, 2); // unlinked, the slot still holds this user
        String taskId = h.engine.createTask(content(null, "Typed while unlinked"));
        h.drain();
        h.remote.none("no request while unlinked");
        TasksCloudLedger.Entry entry = h.ledger.entries().get(0);
        check(A.equals(entry.principal) && h.state().task(taskId) != null, "sealed with the bound principal, shown in the overlay");
        h.remote.identity = new TasksCloudRemote.Identity(TG, A, 3);
        h.engine.identityChanged();
        h.drain();
        check(h.remote.take("batch").batch.items.get(0).mutation.mutationId.equals(entry.mutationId), "sent after the relink");
        Harness fresh = new Harness();
        fresh.remote.identity = new TasksCloudRemote.Identity(TG, null, 1);
        expect(TasksCloudError.class, () -> fresh.engine.createTask(content(null, "No database binding")), "never bound: NOT_LINKED as before");
        h.remote.identity = new TasksCloudRemote.Identity(0, null, 4);
        expect(TasksCloudError.class, () -> h.engine.createTask(content(null, "Signed out")), "the slot holds nobody: NOT_LINKED");
        h.sql.close();
        fresh.sql.close();
    }

    static void analyticsOfCloudIntents() {
        Sink sink = new Sink();
        TasksAnalyticsSink.Holder.set(sink);
        String own = TasksWire.newId(), inv = TasksWire.newId(), shared = TasksWire.newId(), l = TasksWire.newId();
        Harness h = r4(list4(l, "1", "1", "L"), task4(own, "2", l, "Mine", null, 0), invitation(inv, shared, "4"));
        h.engine.createList("New", 1);
        h.engine.invite(own, "123456789");
        h.engine.setCompleted(own, true);
        h.engine.respond(inv, TasksWire.Decision.DECLINE);
        h.engine.deleteList(l);
        check(sink.events.containsAll(Arrays.asList("listCreated", "taskCompleted:false", "shared:invite_sent", "shared:invite_declined", "listDeleted:1")),
                "cloud-scope events after each local commit: " + sink.events);
        TasksAnalyticsSink.Holder.set(null);
        h.sql.close();

        TasksCloudBoards boards = new TasksCloudBoards();
        Object app = new Object(), board = new Object();
        check(boards.show(app, false) && !boards.hasVisible() && boards.hasAny(), "the app-level owner drives without realtime");
        check(boards.show(board) && boards.hasVisible(), "a resumed board wants realtime");
        check(!boards.hide(board) && !boards.hasVisible() && boards.hasAny(), "the board leaving keeps the engine for the app owner, realtime stops");
        check(boards.hide(app) && !boards.hasAny(), "the last owner stops the engine");
    }

    // ---- §7.5 ----

    static void cutOverTable() {
        TasksCloudGate.Permission allowed = TasksCloudGate.Permission.ALLOWED, waiting = TasksCloudGate.Permission.WAITING;
        String s1 = "o-a/p1", s2 = "o-a/p2";
        check(TasksCutOver.evaluate(TasksCloudGate.Permission.FORBIDDEN, TasksCutOver.Record.ENQUEUED, s1, s1, 0) == TasksCutOver.NONE, "1: forbidden → none (writable)");
        check(TasksCutOver.evaluate(null, TasksCutOver.Record.COMPLETED, s1, s1, 0) == TasksCutOver.NONE, "1: unknown permission fails closed to none");
        check(TasksCutOver.evaluate(allowed, TasksCutOver.Record.ENQUEUED, s1, s2, 0) == TasksCutOver.NONE, "2: another scope → none");
        TasksCutOver migrating = TasksCutOver.evaluate(allowed, TasksCutOver.Record.ENQUEUED, s1, s1, 3);
        check(migrating.kind == TasksCutOver.Kind.ARCHIVE && migrating.reason == TasksCutOver.Reason.MIGRATING && migrating.isReadOnly()
                && migrating.defaultsToCloud(), "3: enqueued → archive(migrating), read-only, Cloud");
        check(TasksCutOver.evaluate(waiting, TasksCutOver.Record.ENQUEUED, s1, null, 0).reason == TasksCutOver.Reason.MIGRATING, "3: also while WAITING, unbound");
        check(TasksCutOver.evaluate(allowed, TasksCutOver.Record.COMPLETED, s1, s1, 0) == TasksCutOver.RETIRED, "4: completed, nothing new → retired");
        TasksCutOver newRows = TasksCutOver.evaluate(waiting, TasksCutOver.Record.COMPLETED, s1, s1, 4);
        check(newRows.kind == TasksCutOver.Kind.ARCHIVE && newRows.reason == TasksCutOver.Reason.NEW_ROWS && newRows.newRows == 4, "5: new rows → archive(newRows 4)");
        for (TasksCutOver.Record record : Arrays.asList(null, TasksCutOver.Record.CLAIMED, TasksCutOver.Record.RELEASED, TasksCutOver.Record.SKIPPED)) {
            TasksCutOver none = TasksCutOver.evaluate(allowed, record, s1, s1, 9);
            check(none == TasksCutOver.NONE && !none.isReadOnly(), "7: " + record + " → none (writable)");
        }
        check(TasksCutOver.Record.fromWire("enqueued") == TasksCutOver.Record.ENQUEUED && TasksCutOver.Record.fromWire("bogus") == null, "record wire names");
    }

    // ---- schema v2 ----

    static final List<String> V1_DDL = Arrays.asList(
            "CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)",
            "CREATE TABLE shadow (kind TEXT NOT NULL CHECK(kind IN ('list','task','invitation','block')),id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(kind,id))",
            "CREATE TABLE staged (kind TEXT NOT NULL CHECK(kind IN ('list','task','invitation','block')),id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(kind,id))",
            "CREATE TABLE staging (singleton INTEGER PRIMARY KEY NOT NULL CHECK(singleton=1),phase TEXT NOT NULL CHECK(phase IN ('pages','catchup')),cursor TEXT,changes_cursor TEXT,page_limit INTEGER NOT NULL CHECK(page_limit BETWEEN 1 AND 100),CHECK(phase='pages' OR (cursor IS NOT NULL AND changes_cursor IS NOT NULL)),CHECK(cursor IS NOT NULL OR changes_cursor IS NULL))",
            "CREATE TABLE outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT,mutation_id TEXT NOT NULL UNIQUE,principal TEXT NOT NULL,kind TEXT NOT NULL,resource_id TEXT NOT NULL,chain TEXT NOT NULL,list_key TEXT,source_list_key TEXT,body BLOB NOT NULL,sealed INTEGER NOT NULL CHECK(sealed IN (0,1)),resolved_base TEXT,state TEXT NOT NULL CHECK(state IN ('held','queued','sending','backoff','stalled','blocked_creation','invalid','conflict','quarantined','not_found','acked')),sent INTEGER NOT NULL DEFAULT 0 CHECK(sent IN (0,1)),error TEXT,reason TEXT,attempts INTEGER NOT NULL DEFAULT 0,not_before INTEGER NOT NULL DEFAULT 0,acked_revision TEXT,acked_resource TEXT,CHECK(sent=0 OR sealed=1),CHECK(state<>'held' OR (sent=0 AND kind='task.delete')),CHECK(state<>'acked' OR acked_resource IS NOT NULL))",
            "CREATE INDEX outbox_chain ON outbox(chain,seq)",
            "CREATE TABLE notices (id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL CHECK(kind IN ('access_lost','not_found','dependency_dropped')),resource_id TEXT NOT NULL)");

    static void schemaV1OpensAtV2WithForcedResnapshot() {
        check(TasksCloudSchema.VERSION == 2, "schema version 2");
        String path = new File(dir, "v1-" + (++dbCounter) + ".db").getPath();
        PySqlite v1 = new PySqlite(bridge, path);
        String t = TasksWire.newId(), draft = TasksWire.newId(), mutationId = TasksWire.newId();
        v1.transaction(() -> {
            for (String statement : V1_DDL) v1.exec(statement);
            v1.exec("INSERT INTO meta(key,value) VALUES('incarnation','i'),('principal',?),('changes_cursor','c-old'),('shadow_complete','1')", A);
            v1.exec("INSERT INTO shadow(kind,id,json) VALUES('task',?,?)", t, task(t, "1", "Kept"));
            v1.exec("INSERT INTO staging(singleton,phase,cursor,changes_cursor,page_limit) VALUES(1,'pages','p2','s0',20)");
            v1.exec("INSERT INTO staged(kind,id,json) VALUES('task',?,?)", t, task(t, "1", "Kept"));
            v1.exec("INSERT INTO notices(kind,resource_id) VALUES('access_lost','x')");
            TasksWire.Mutation sealed = TasksWire.Mutation.seal(A, mutationId, TasksWire.Operation.taskCreate(draft, content(null, "R3 draft"), null, false, null));
            v1.exec("INSERT INTO outbox(mutation_id,principal,kind,resource_id,chain,body,sealed,state) VALUES(?,?,?,?,?,?,1,'queued')",
                    mutationId, A, "task.create", draft, "task:" + draft, sealed.body());
            return null;
        });
        expect(IllegalStateException.class, () -> v1.transaction(() -> { TasksCloudLedger.upgrade(v1, 2, 3); return null; }), "unknown versions still throw");
        expect(IllegalStateException.class, () -> v1.transaction(() -> { TasksCloudLedger.upgrade(v1, 0, 2); return null; }), "only 1 → 2");
        v1.transaction(() -> { TasksCloudLedger.upgrade(v1, 1, 2); return null; });
        check(v1.query("SELECT value FROM meta WHERE key='changes_cursor'").isEmpty() && v1.query("SELECT 1 FROM staging").isEmpty()
                && v1.query("SELECT 1 FROM staged").isEmpty(), "forced resnapshot: cursor, staging and staged pages gone");
        check(v1.query("SELECT 1 FROM shadow").size() == 1 && "1".equals(v1.query("SELECT value FROM meta WHERE key='shadow_complete'").get(0)[0]),
                "the shadow stays visible until the new snapshot reconciles");
        List<Object[]> notices = v1.query("SELECT id,kind FROM notices");
        check(notices.size() == 1 && "access_lost".equals(notices.get(0)[1]), "notices kept across the rebuild");
        v1.exec("INSERT INTO notices(kind,resource_id) VALUES('removed_in_cloud','y')");
        expect(IllegalStateException.class, () -> v1.exec("INSERT INTO outbox(mutation_id,principal,kind,resource_id,chain,body,sealed,state,origin) VALUES('m9',?,'task.create','r','task:r',X'00',1,'queued','other')", A),
                "origin CHECK on the upgraded table");
        v1.exec("INSERT INTO import_ack_pending(mutation_id) VALUES('x')");
        v1.close();

        Harness h = new Harness(path, new Remote());
        h.remote.batchRoute = true;
        TasksCloudLedger.Entry kept = h.ledger.entries().get(0);
        check(TasksCloudSchema.ORIGIN_USER.equals(kept.origin) && kept.autoRequeues == 0 && !kept.waitsMembers && kept.ackedCycle == null,
                "R3 rows read with the column defaults");
        h.engine.start();
        h.drain();
        Request snapshot = h.remote.take("snapshot");
        check(snapshot.cursor == null, "the upgraded database pulls a fresh snapshot first");
        snapshot4(snapshot, "s1", task(t, "1", "Kept"), list4(TasksWire.newId(), "1", "1", "R4 list"));
        h.drain();
        changes4(h.remote.take("changes"), "c1");
        h.drain();
        Request batch = h.remote.take("batch");
        check(batch.batch.items.get(0).mutation.mutationId.equals(mutationId), "the R3 draft drains with its original id and bytes");
        h.sql.close();
    }

    public static void main(String[] args) throws Exception {
        bridge = args[0];
        dir = new File(args[1]);
        gateDefaultForbiddenSendsNothing(); // first: nothing installed yet
        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        consentFenceRequeuesWithoutAttemptAndBacksOff();
        withdrawalEpochRefusesInserts();
        batchDrainSkippedOriginsAndItemErrors();
        fallbackUserOnlyMigrationNeverSingle();
        migrationPausedHoldsMigrationOnly();
        adaptiveBatchSizeNeverStalls();
        oneEntry413SendsUserAloneAndMigrationInvalid();
        taskPatchAndDowngrade();
        sealingFixNoConflictAgainstR4Members();
        provenanceAndPhantomSettle();
        autoResolutionTable();
        requeueBounds();
        unlinkedIntentsSealedWithTheBoundPrincipal();
        analyticsOfCloudIntents();
        cutOverTable();
        schemaV1OpensAtV2WithForcedResnapshot();
        migrationDrain5000();
        System.out.println("Tasks cloud R4 (host SQLite bridge, scripted R4 remote): " + checks + " checks passed");
    }
}
