package org.telegram.messenger.zenlygram.tasks.cloud;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host behaviour tests of the real TasksCloudLedger/Engine/State/Import/Policy sources with the real Transport
 * TasksWire codec (app Gson) over a real on-disk SQLite file (host stdlib SQLite via sqlite_bridge.py, NOT Android
 * SQLite). The remote is a scripted request/answer double: every answer is decoded by the production strict decoders;
 * nothing here is backend, device or real-account evidence. TasksCloudRepository (Android glue) is not executed.
 */
public final class TasksCloudStateTests {
    static final long TG = 777L;
    /** The engine's account slot; main() installs the consent gate TasksCloudGate as ALLOWED for every account. */
    static final int ACCOUNT = 3;
    static final String A = "01jaaaaaaaaaaaaaaaaaaaaaaa", B = "01jbbbbbbbbbbbbbbbbbbbbbbb";
    static final String T0 = "2026-10-01T00:00:00Z";
    static String bridge;
    static File dir;
    static int checks, dbCounter;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    static <T extends Throwable> T expect(Class<T> type, Runnable body, String message) {
        try {
            body.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                checks++;
                return type.cast(thrown);
            }
            throw new AssertionError(message + ": unexpected " + thrown, thrown);
        }
        throw new AssertionError(message + ": nothing thrown");
    }

    // ---- harness ----

    static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void drain() { while (!queue.isEmpty()) queue.poll().run(); }
    }

    static final class Timer {
        final Runnable runnable;
        final long due;
        Timer(Runnable runnable, long due) { this.runnable = runnable; this.due = due; }
    }

    static final class Request {
        final String kind, cursor;
        final int limit;
        final TasksWire.Mutation mutation;
        /** kind "batch" only. */
        final TasksWire.Batch batch;
        @SuppressWarnings("rawtypes") final TasksCloudRemote.Reply reply;
        boolean answered;

        @SuppressWarnings("rawtypes")
        Request(String kind, String cursor, int limit, TasksWire.Mutation mutation, TasksCloudRemote.Reply reply) {
            this(kind, cursor, limit, mutation, reply, null);
        }

        @SuppressWarnings("rawtypes")
        Request(String kind, String cursor, int limit, TasksWire.Mutation mutation, TasksCloudRemote.Reply reply, TasksWire.Batch batch) {
            this.kind = kind;
            this.cursor = cursor;
            this.limit = limit;
            this.mutation = mutation;
            this.reply = reply;
            this.batch = batch;
        }
    }

    static final class Remote implements TasksCloudRemote {
        Identity identity = new Identity(TG, A, 1);
        final ArrayList<Request> requests = new ArrayList<>();
        /** false: an R3 server — no batch route, every batch answers 404 at once and user entries take the single route. */
        boolean batchRoute;
        int refusedBatches;

        @Override public Identity identity() { return identity; }
        @Override public void mutate(Identity expected, TasksWire.Mutation mutation, Reply<TasksWire.MutationResponse> reply) { add(expected, new Request("mutate", null, 0, mutation, reply)); }
        @Override public void mutateBatch(Identity expected, TasksWire.Batch batch, Reply<BatchReply> reply) {
            check(batch.principal.equals(expected.principal), "batch sealed for the captured principal");
            if (!batchRoute) {
                check(expected == identity && open().isEmpty(), "an R3 server's 404 answers the exact identity, one request at a time");
                refusedBatches++;
                reply.failure(new Failure(Failure.Kind.PROTOCOL, "request_rejected", null, 404, 0));
                return;
            }
            add(expected, new Request("batch", null, 0, null, reply, batch));
        }
        @Override public void snapshot(Identity expected, String cursor, int limit, Reply<TasksWire.SnapshotResponse> reply) { add(expected, new Request("snapshot", cursor, limit, null, reply)); }
        @Override public void changes(Identity expected, String cursor, int limit, Reply<TasksWire.ChangesResponse> reply) { add(expected, new Request("changes", cursor, limit, null, reply)); }

        private void add(Identity expected, Request request) {
            check(expected == identity, "every request carries the exact identity captured for its step");
            check(request.mutation == null || request.mutation.principal.equals(expected.principal), "mutation sealed for the captured principal");
            add(request);
        }

        private void add(Request request) {
            check(open().isEmpty(), "at most one Tasks request in flight");
            requests.add(request);
        }

        List<Request> open() {
            ArrayList<Request> result = new ArrayList<>();
            for (Request request : requests) if (!request.answered) result.add(request);
            return result;
        }

        Request take(String kind) {
            List<Request> open = open();
            check(open.size() == 1, "expected one open request for " + kind + ", got " + open.size());
            Request request = open.get(0);
            check(request.kind.equals(kind), "expected " + kind + " request, got " + request.kind);
            request.answered = true;
            return request;
        }

        void none(String message) { check(open().isEmpty(), message); }
    }

    static final class Harness {
        final String path;
        final Remote remote;
        final QueueExecutor executor = new QueueExecutor();
        final ArrayList<Timer> timers = new ArrayList<>();
        long now = 1_800_000_000_000L;
        PySqlite sql;
        TasksCloudLedger ledger;
        TasksCloudEngine engine;
        TasksCloudState last;

        Harness() { this(new File(dir, "cloud-" + (++dbCounter) + ".db").getPath(), new Remote()); }

        Harness(String path, Remote remote) {
            this.path = path;
            this.remote = remote;
            boolean fresh = !new File(path).exists();
            sql = new PySqlite(bridge, path);
            if (fresh) TasksCloudLedger.create(sql, TasksWire.newId());
            ledger = new TasksCloudLedger(sql);
            engine = new TasksCloudEngine(ACCOUNT, TG, ledger, remote, executor, (runnable, delay) -> timers.add(new Timer(runnable, now + delay)),
                    () -> now, state -> last = state);
        }

        /** Process death: no reply ever arrives; the SQLite file stays. */
        Harness restart() {
            sql.close();
            return new Harness(path, new Remote());
        }

        void drain() { executor.drain(); }

        void advance(long ms) {
            now += ms;
            ArrayList<Timer> due = new ArrayList<>();
            for (Timer timer : timers) if (timer.due <= now) due.add(timer);
            timers.removeAll(due);
            for (Timer timer : due) timer.runnable.run();
            drain();
        }

        TasksCloudState state() { return engine.state(); }

        void bootstrap(String... items) {
            engine.start();
            drain();
            snapshot(remote.take("snapshot"), null, "s0", items);
            drain();
            changes(remote.take("changes"), "c1", false);
            drain();
            remote.none("bootstrap leaves nothing in flight");
        }

        TasksCloudState.Pending pending(String mutationId) {
            for (TasksCloudState.Pending pending : state().pending) if (pending.mutationId.equals(mutationId)) return pending;
            return null;
        }
    }

    // ---- wire JSON (decoded by the production strict decoders) ----

    static String q(String value) { return value == null ? "null" : "\"" + value + "\""; }

    static String task(String id, String revision, String title) {
        return taskJson(id, revision, "owner", "777", null, title, false, null);
    }

    static String taskJson(String id, String revision, String role, String owner, String listId, String title, boolean completed, String assignment) {
        return "{\"kind\":\"task\",\"id\":\"" + id + "\",\"revision\":\"" + revision + "\",\"owner_tg_id\":\"" + owner
                + "\",\"viewer_role\":\"" + role + "\",\"content\":{\"list_id\":" + q(listId) + ",\"title\":\"" + title
                + "\",\"description\":null,\"due_at\":null,\"priority\":0,\"sort_order\":0,\"annotation\":null},\"is_completed\":"
                + completed + ",\"completed_at\":" + (completed ? q(T0) : "null") + ",\"created_at\":\"" + T0 + "\",\"updated_at\":\"" + T0
                + "\",\"assignment\":" + (assignment == null ? "null" : assignment) + "}";
    }

    static String assigned(String id, String revision, String invitationId) {
        return taskJson(id, revision, "assignee", "555", null, "Shared", false,
                "{\"invitation_id\":\"" + invitationId + "\",\"recipient_tg_id\":\"777\",\"state\":\"accepted\",\"expires_at\":\"2026-10-08T00:00:00Z\"}");
    }

    static String list(String id, String revision, String title) {
        return "{\"kind\":\"list\",\"id\":\"" + id + "\",\"revision\":\"" + revision + "\",\"title\":\"" + title
                + "\",\"sort_order\":0,\"created_at\":\"" + T0 + "\",\"updated_at\":\"" + T0 + "\"}";
    }

    static String invitation(String id, String taskId, String taskRevision) {
        return "{\"kind\":\"invitation\",\"id\":\"" + id + "\",\"task_id\":\"" + taskId + "\",\"task_revision\":\"" + taskRevision
                + "\",\"owner_tg_id\":\"555\",\"title\":\"Join\",\"due_at\":null,\"expires_at\":\"2026-10-08T00:00:00Z\"}";
    }

    static String upsert(int position, String item) { return "{\"position\":\"" + position + "\",\"action\":\"upsert\",\"item\":" + item + "}"; }

    static String remove(int position, String kind, String id) {
        return "{\"position\":\"" + position + "\",\"action\":\"remove\",\"kind\":\"" + kind + "\",\"id\":\"" + id + "\"}";
    }

    @SuppressWarnings("unchecked")
    static void snapshot(Request request, String next, String changesCursor, String... items) {
        String json = "{\"schema_version\":1,\"items\":[" + String.join(",", items) + "],\"next_cursor\":" + q(next)
                + ",\"changes_cursor\":\"" + changesCursor + "\",\"server_time\":\"2026-10-24T10:00:00Z\"}";
        request.reply.success(TasksWire.decodeSnapshotResponse(json, request.limit));
    }

    @SuppressWarnings("unchecked")
    static void changes(Request request, String next, boolean hasMore, String... changes) {
        String json = "{\"schema_version\":1,\"changes\":[" + String.join(",", changes) + "],\"next_cursor\":\"" + next
                + "\",\"has_more\":" + hasMore + ",\"server_time\":\"2026-10-24T10:00:00Z\"}";
        request.reply.success(TasksWire.decodeChangesResponse(json, request.limit));
    }

    @SuppressWarnings("unchecked")
    static void receipt(Request request, String revision) {
        TasksWire.Mutation sent = request.mutation;
        String kind = sent.operation.kind.wire();
        String resourceKind = kind.startsWith("list.") ? "list" : kind.startsWith("sender.") ? "block" : "task";
        String resourceId = kind.equals("sender.block") ? "555" : sent.operation.resourceId();
        String json = "{\"schema_version\":1,\"receipt\":{\"mutation_id\":\"" + sent.mutationId + "\",\"operation\":\"" + kind
                + "\",\"outcome\":\"committed\",\"resource_kind\":\"" + resourceKind + "\",\"resource_id\":\"" + resourceId
                + "\",\"resource_revision\":" + q(revision) + ",\"committed_at\":\"2026-10-24T10:00:01Z\"},\"server_time\":\"2026-10-24T10:00:01Z\"}";
        request.reply.success(TasksWire.decodeMutationResponse(json, sent));
    }

    static void fail(Request request, TasksCloudRemote.Failure.Kind kind, String code, TasksWire.ConflictReason reason, int retryAfter) {
        request.reply.failure(new TasksCloudRemote.Failure(kind, code, reason, 0, retryAfter));
    }

    static TasksWire.Content content(String listId, String title) {
        return new TasksWire.Content(listId, title, null, null, 0, 0, null);
    }

    static String body(Request request) { return new String(request.mutation.body(), StandardCharsets.UTF_8); }

    // ---- scenarios ----

    static void stagedSnapshotPaginationAndCatchup() {
        Harness h = new Harness();
        String t1 = TasksWire.newId(), t2 = TasksWire.newId(), t3 = TasksWire.newId(), l1 = TasksWire.newId();
        h.engine.start();
        h.drain();
        Request first = h.remote.take("snapshot");
        check(first.cursor == null && first.limit == TasksCloudEngine.PAGE_LIMIT, "first page: no cursor, fixed limit");
        snapshot(first, "p2", "s0", task(t1, "1", "One"), list(l1, "1", "Work"));
        h.drain();
        check(!h.state().shadowComplete && h.state().tasks.isEmpty(), "pages stage; shadow untouched until reconciliation");
        Request second = h.remote.take("snapshot");
        check("p2".equals(second.cursor) && second.limit == first.limit, "next page cursor, same limit");
        snapshot(second, null, "s-other", task(t2, "1", "Two"));
        h.drain();
        Request catchup = h.remote.take("changes");
        check("s0".equals(catchup.cursor), "post-S catch-up starts at the FIRST page's changes cursor");
        changes(catchup, "h1", true, upsert(10, task(t3, "1", "Three")), remove(11, "task", t1));
        h.drain();
        check(!h.state().shadowComplete, "catch-up not exhausted: still staged");
        changes(h.remote.take("changes"), "h2", false);
        h.drain();
        TasksCloudState state = h.state();
        check(state.shadowComplete && state.task(t1) == null && state.task(t2) != null && state.task(t3) != null
                && state.list(l1) != null, "complete snapshot + post-S delta replaces shadow atomically");
        check("h2".equals(h.ledger.readMeta(TasksCloudLedger.META_CHANGES_CURSOR)), "exhausted cursor persisted");
        h.remote.none("no redundant changes cycle after reconciliation");
        h.sql.close();
    }

    static void incompleteSnapshotKeepsShadowAndDraftsAndResumes() {
        Harness h = new Harness();
        String t1 = TasksWire.newId(), t9 = TasksWire.newId(), t10 = TasksWire.newId();
        h.bootstrap(task(t1, "1", "Old"));
        String created = h.engine.createTask(content(null, "Offline draft"));
        h.drain();
        fail(h.remote.take("mutate"), TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", null, 0);
        h.drain();
        h.engine.refresh();
        h.drain();
        fail(h.remote.take("changes"), TasksCloudRemote.Failure.Kind.TASK, "tasks_resync_required", null, 0);
        h.drain();
        snapshot(h.remote.take("snapshot"), "p2", "s5", task(t9, "1", "New"));
        h.drain();
        fail(h.remote.take("snapshot"), TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", null, 0);
        h.drain();
        TasksCloudState state = h.state();
        check(state.task(t1) != null && state.task(t9) == null, "failed pagination never replaces or clears the shadow");
        check(state.task(created) != null && state.pending.size() == 1, "failed pagination keeps the local draft and outbox");
        check(state.status == TasksCloudState.Status.OFFLINE, "offline surfaced");

        Harness restarted = h.restart();
        restarted.engine.start();
        restarted.drain();
        Request resumed = restarted.remote.take("snapshot");
        check("p2".equals(resumed.cursor), "restart resumes the persisted staged page cursor");
        snapshot(resumed, null, "s9", task(t10, "1", "Ten"));
        restarted.drain();
        Request catchup = restarted.remote.take("changes");
        check("s5".equals(catchup.cursor), "catch-up from the staged snapshot's S");
        changes(catchup, "c9", false);
        restarted.drain();
        state = restarted.state();
        check(state.task(t1) == null && state.task(t9) != null && state.task(t10) != null, "reconciled complete snapshot");
        check(state.task(created) != null, "pending local creation is not deleted for being absent from the snapshot");
        restarted.sql.close();
    }

    static void restartReplaysSameIdAndBytesAndReceiptPrunesAfterSync() {
        Harness h = new Harness();
        h.bootstrap();
        String taskId = h.engine.createTask(content(null, "Ship \u2028 it"));
        h.drain();
        Request first = h.remote.take("mutate");
        byte[] bytes = first.mutation.body();
        Harness restarted = h.restart();
        restarted.engine.start();
        restarted.drain();
        Request resent = restarted.remote.take("mutate");
        check(resent.mutation.mutationId.equals(first.mutation.mutationId), "same mutation id after restart");
        check(Arrays.equals(resent.mutation.body(), bytes), "byte-identical sealed body after restart");
        receipt(resent, "1");
        restarted.drain();
        TasksCloudState state = restarted.state();
        check(state.pending.isEmpty() && state.task(taskId) != null && state.task(taskId).server == null,
                "acknowledged create stays visible as overlay until synced");
        Request sync = restarted.remote.take("changes");
        changes(sync, "c2", false, upsert(5, task(taskId, "1", "Ship \u2028 it")));
        restarted.drain();
        state = restarted.state();
        check(state.task(taskId) != null && state.task(taskId).server != null, "server row replaces overlay");
        check(restarted.ledger.entries().isEmpty(), "acknowledged entry pruned after the next complete cycle");
        restarted.sql.close();
    }

    static void lateReplyAfterFenceIsInertAndFencedTransactionRollsBack() {
        Harness h = new Harness();
        h.bootstrap();
        h.engine.createTask(content(null, "Logout race"));
        h.drain();
        Request inFlight = h.remote.take("mutate");
        h.engine.close();
        h.sql.fenced = true;
        receipt(inFlight, "1");
        h.drain();
        h.remote.none("closed engine issues nothing");
        expect(TasksCloudError.class, () -> h.engine.createTask(content(null, "After logout")), "intents refused after fence");
        h.sql.close();
        PySqlite inspect = new PySqlite(bridge, h.path);
        List<Object[]> rows = inspect.query("SELECT state,sent FROM outbox");
        check(rows.size() == 1 && TasksCloudSchema.SENDING.equals(rows.get(0)[0]), "late receipt did not commit anything");
        inspect.close();

        Harness g = new Harness();
        List<TasksWire.Operation> creates = Arrays.<TasksWire.Operation>asList(
                TasksWire.Operation.taskCreate(TasksWire.newId(), content(null, "First"), null, false, null),
                TasksWire.Operation.taskCreate(TasksWire.newId(), content(null, "Second"), null, false, null));
        g.sql.fenceAfter = 3; // first create fully written inside the transaction, then logout lands
        expect(TasksSql.ClosedException.class, () -> g.ledger.enqueueAll(A, Arrays.asList(TasksWire.newId(), TasksWire.newId()), creates,
                Arrays.asList("task:" + creates.get(0).resourceId(), "task:" + creates.get(1).resourceId()),
                Arrays.<String>asList(null, null)), "fenced mid-transaction");
        g.sql.fenced = false;
        check(g.ledger.entries().isEmpty(), "fenced transaction rolled back completely");
        g.sql.close();
    }

    static void chainedBaseRevisionResolvedBeforeFirstSend() {
        Harness h = new Harness();
        String t = TasksWire.newId();
        h.bootstrap(task(t, "3", "Base"));
        h.engine.updateTask(t, content(null, "Edited"));
        h.drain();
        Request update = h.remote.take("mutate");
        check(body(update).contains("\"base_revision\":\"3\""), "first edit sealed on the visible revision");
        String complete = h.engine.setCompleted(t, true);
        h.drain();
        check(h.pending(complete).state == TasksCloudState.PendingState.WAITING, "dependent edit waits for its predecessor");
        receipt(update, "4");
        h.drain();
        Request next = h.remote.take("mutate");
        check(next.mutation.mutationId.equals(complete) && body(next).contains("\"base_revision\":\"4\""),
                "provisional successor sealed with the predecessor's receipt revision");
        h.sql.close();
    }

    static void conflictKeepsDraftAndReappliesOnlyExplicitly() {
        Harness h = new Harness();
        String t = TasksWire.newId();
        h.bootstrap(task(t, "3", "Server"));
        String mutationId = h.engine.updateTask(t, content(null, "Mine"));
        h.drain();
        fail(h.remote.take("mutate"), TasksCloudRemote.Failure.Kind.TASK, "tasks_conflict", TasksWire.ConflictReason.REVISION_CHANGED, 0);
        h.drain();
        TasksCloudState.Pending conflict = h.pending(mutationId);
        check(conflict.state == TasksCloudState.PendingState.CONFLICT && conflict.reason == TasksWire.ConflictReason.REVISION_CHANGED,
                "409 conflict is explicit with its reason");
        check("Server".equals(h.state().task(t).content.title) && conflict.draft instanceof TasksWire.Operation.TaskUpdate,
                "current row shown; user's draft kept");
        changes(h.remote.take("changes"), "c2", false, upsert(3, task(t, "5", "Remote")));
        h.drain();
        h.remote.none("no automatic resubmission");
        String fresh = h.engine.resolveConflict(mutationId, true);
        h.drain();
        Request reapplied = h.remote.take("mutate");
        check(!fresh.equals(mutationId) && reapplied.mutation.mutationId.equals(fresh), "reapply uses a NEW mutation id");
        check(body(reapplied).contains("\"base_revision\":\"5\"") && body(reapplied).contains("\"title\":\"Mine\""),
                "reapply: fresh revision, same user intent");
        check(h.pending(mutationId) == null, "conflicted entry replaced atomically");
        h.sql.close();
    }

    static void creationRefusalBlocksUntilExplicitRetryWithSameBytes() {
        Harness h = new Harness();
        h.bootstrap();
        h.engine.createTask(content(null, "Paid"));
        h.drain();
        Request refused = h.remote.take("mutate");
        fail(refused, TasksCloudRemote.Failure.Kind.TASK, "tasks_creation_not_permitted", null, 0);
        h.drain();
        check(h.pending(refused.mutation.mutationId).state == TasksCloudState.PendingState.BLOCKED_CREATION, "403 creation: blocked draft");
        h.advance(TasksCloudPolicy.MAX_BACKOFF_MS);
        check(h.remote.open().isEmpty() || "changes".equals(h.remote.open().get(0).kind), "no automatic resend of a refused create");
        if (!h.remote.open().isEmpty()) {
            changes(h.remote.take("changes"), "c2", false);
            h.drain();
        }
        h.engine.retry(refused.mutation.mutationId);
        h.drain();
        Request retried = h.remote.take("mutate");
        check(retried.mutation.mutationId.equals(refused.mutation.mutationId) && Arrays.equals(retried.mutation.body(), refused.mutation.body()),
                "explicit retry reuses the unchanged id and bytes");
        h.sql.close();
    }

    static void unlinkedPausesWithEverythingPreserved() {
        Harness h = new Harness();
        String t = TasksWire.newId();
        h.bootstrap(task(t, "1", "Kept"));
        h.engine.createTask(content(null, "Paused"));
        h.drain();
        Request first = h.remote.take("mutate");
        fail(first, TasksCloudRemote.Failure.Kind.UNLINKED, "tasks_account_not_linked", null, 0);
        h.drain();
        TasksCloudState state = h.state();
        check(state.status == TasksCloudState.Status.UNLINKED_PAUSED, "account-level pause");
        check(state.task(t) != null && "c1".equals(h.ledger.readMeta(TasksCloudLedger.META_CHANGES_CURSOR)), "shadow and cursor intact");
        check(h.pending(first.mutation.mutationId).state == TasksCloudState.PendingState.QUEUED && h.pending(first.mutation.mutationId).sent,
                "sealed outbox entry intact (sent, same id)");
        h.advance(TasksCloudEngine.POLL_MS * 3);
        h.engine.kick();
        h.drain();
        h.remote.none("no automatic retry/backoff while unlinked");

        Harness restarted = h.restart();
        restarted.engine.start();
        restarted.drain();
        restarted.remote.none("pause survives restart");
        check(restarted.state().status == TasksCloudState.Status.UNLINKED_PAUSED, "paused after restart");
        restarted.engine.refresh();
        restarted.drain();
        Request resumed = restarted.remote.take("mutate");
        check(Arrays.equals(resumed.mutation.body(), first.mutation.body()), "explicit recovery resumes the same business id/bytes");
        restarted.sql.close();

        Harness g = new Harness();
        g.bootstrap();
        g.engine.createTask(content(null, "Relink"));
        g.drain();
        fail(g.remote.take("mutate"), TasksCloudRemote.Failure.Kind.UNLINKED, "tasks_account_not_linked", null, 0);
        g.drain();
        g.remote.identity = new TasksCloudRemote.Identity(TG, A, 2); // user completed Telegram linking in this process
        g.engine.kick();
        g.drain();
        check("mutate".equals(g.remote.take("mutate").kind), "observed re-link resumes without wiping");
        g.sql.close();
    }

    static void offlineBackoffIsBoundedAndRateLimitRespected() {
        Harness h = new Harness();
        h.bootstrap();
        h.engine.createTask(content(null, "Retry"));
        h.drain();
        Request first = h.remote.take("mutate");
        String id = first.mutation.mutationId;
        fail(first, TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", null, 0);
        h.drain();
        check(h.pending(id).state == TasksCloudState.PendingState.BACKOFF, "unknown outcome: backoff");
        h.engine.kick();
        h.drain();
        h.remote.none("not before the backoff deadline");
        for (int attempt = 2; attempt <= TasksCloudPolicy.MAX_AUTO_ATTEMPTS; attempt++) {
            h.advance(TasksCloudPolicy.MAX_BACKOFF_MS);
            Request again = h.remote.take("mutate");
            check(again.mutation.mutationId.equals(id) && Arrays.equals(again.mutation.body(), first.mutation.body()), "same identity on retry " + attempt);
            fail(again, TasksCloudRemote.Failure.Kind.UNAVAILABLE, "tasks_unavailable", null, 0);
            h.drain();
        }
        check(h.pending(id).state == TasksCloudState.PendingState.STALLED, "bounded: stalls after max attempts");
        h.advance(TasksCloudPolicy.MAX_BACKOFF_MS);
        if (!h.remote.open().isEmpty()) {
            check("changes".equals(h.remote.open().get(0).kind), "only polling continues");
            changes(h.remote.take("changes"), "c2", false);
            h.drain();
        }
        h.engine.retry(id);
        h.drain();
        Request limited = h.remote.take("mutate");
        fail(limited, TasksCloudRemote.Failure.Kind.RATE_LIMITED, "rate_limited", null, 30);
        h.drain();
        h.advance(29_000);
        check(h.remote.open().isEmpty() || !"mutate".equals(h.remote.open().get(0).kind), "Retry-After respected");
        h.sql.close();
    }

    static void heldDeleteUndoPromotionAndRestart() {
        Harness h = new Harness();
        String t = TasksWire.newId(), u = TasksWire.newId();
        h.bootstrap(task(t, "7", "Hold me"), task(u, "2", "Other"));
        String hold = h.engine.holdDelete(t);
        h.drain();
        check(h.state().task(t) == null && h.pending(hold).state == TasksCloudState.PendingState.HELD, "held delete hides the task");
        h.remote.none("held deletes are never sent");
        check(h.engine.undo(hold) && h.state().task(t) != null && h.pending(hold) == null, "Undo restores atomically");
        String second = h.engine.holdDelete(t);
        h.engine.promote(second);
        h.drain();
        Request delete = h.remote.take("mutate");
        check(delete.mutation.mutationId.equals(second) && body(delete).contains("\"base_revision\":\"7\""), "promotion seals hold-time base");
        h.engine.promote(second);
        check(!h.engine.undo(second), "no Undo after promotion; promotion idempotent");
        receipt(delete, "8");
        h.drain();
        changes(h.remote.take("changes"), "c2", false, remove(4, "task", t));
        h.drain();

        String surviving = h.engine.holdDelete(u);
        h.drain();
        Harness restarted = h.restart();
        restarted.engine.start();
        restarted.drain();
        Request promoted = restarted.remote.take("mutate");
        check(promoted.mutation.mutationId.equals(surviving), "restart promotes a surviving hold (UI handle gone)");
        receipt(promoted, "3");
        restarted.drain();
        changes(restarted.remote.take("changes"), "c3", false, remove(6, "task", u));
        restarted.drain();

        restarted.engine.stop();
        String local = restarted.engine.createTask(content(null, "Never sent"));
        String localHold = restarted.engine.holdDelete(local);
        restarted.engine.promote(localHold);
        check(restarted.ledger.entries().isEmpty(), "never-sent create + delete cancel both locally");
        String keep = restarted.engine.createTask(content(null, "Background"));
        String bgHold = restarted.engine.holdDelete(keep);
        restarted.engine.stop();
        check(restarted.pending(bgHold) == null, "stop() (app background) promotes live holds exactly once");
        restarted.sql.close();
    }

    static void confirmedSharedAccessLossRemovesReadableCache() {
        Harness h = new Harness();
        String s = TasksWire.newId(), inv = TasksWire.newId();
        h.bootstrap(assigned(s, "2", inv));
        check(!h.state().task(s).owner && inv.equals(h.state().task(s).liveInvitationId), "assignee view");
        h.engine.setCompleted(s, true);
        h.drain();
        Request complete = h.remote.take("mutate");
        check(body(complete).contains("\"invitation_id\":\"" + inv + "\""), "assignee completion carries the current epoch");
        fail(complete, TasksCloudRemote.Failure.Kind.TASK, "tasks_not_found", null, 0);
        h.drain();
        TasksCloudState state = h.state();
        check(state.task(s) == null && state.pending.isEmpty(), "404 on shared task: cache and commands removed");
        check(state.notices.size() == 1 && state.notices.get(0).kind == TasksCloudState.Notice.Kind.ACCESS_LOST
                && s.equals(state.notices.get(0).resourceId), "minimal access-lost notice only");

        Harness g = new Harness();
        String s2 = TasksWire.newId(), inv2 = TasksWire.newId();
        g.bootstrap(assigned(s2, "2", inv2));
        g.engine.refresh();
        g.drain();
        Request sync = g.remote.take("changes");
        g.engine.setCompleted(s2, true); // queued behind the in-flight sync
        changes(sync, "c2", false, remove(9, "task", s2));
        g.drain();
        g.remote.none("unauthorized command never sent");
        check(g.state().task(s2) == null && g.ledger.entries().isEmpty() && g.state().notices.size() == 1, "remote removal = access loss");
        h.sql.close();
        g.sql.close();
    }

    static void principalChangeNeverRetagsOutbox() {
        Harness h = new Harness();
        String t = TasksWire.newId(), tb = TasksWire.newId();
        h.bootstrap(task(t, "1", "Account A"));
        h.engine.refresh();
        h.drain();
        Request sync = h.remote.take("changes");
        String draft = h.engine.createTask(content(null, "A's draft"));
        h.remote.identity = new TasksCloudRemote.Identity(TG, B, 5);
        changes(sync, "c2", false);
        h.drain();
        Request snap = h.remote.take("snapshot");
        check(snap.cursor == null, "new principal: fresh snapshot, A's cursor unusable");
        check(h.state().task(t) == null, "A's cached cloud data removed on binding change");
        check(h.pending(h.ledger.entries().get(0).mutationId).state == TasksCloudState.PendingState.WRONG_PRINCIPAL,
                "A's draft kept but marked, never retagged");
        snapshot(snap, null, "sb", task(tb, "1", "Account B"));
        h.drain();
        changes(h.remote.take("changes"), "cb", false);
        h.drain();
        h.remote.none("A's mutation is never sent to B");
        check(h.state().task(tb) != null && h.state().task(draft) == null, "B's view has no A overlay");
        h.engine.discard(h.ledger.entries().get(0).mutationId);
        check(h.ledger.entries().isEmpty(), "explicit discard");
        h.sql.close();
    }

    static void terminalErrorsAreDistinct() {
        Harness h = new Harness();
        String t = TasksWire.newId();
        h.bootstrap(task(t, "1", "Own"));
        String update = h.engine.updateTask(t, content(null, "Gone?"));
        h.drain();
        fail(h.remote.take("mutate"), TasksCloudRemote.Failure.Kind.TASK, "tasks_not_found", null, 0);
        h.drain();
        check(h.pending(update).state == TasksCloudState.PendingState.NOT_FOUND && "Own".equals(h.state().task(t).content.title),
                "404 own: overlay dropped, draft kept for explicit copy");
        check(h.state().notices.get(0).kind == TasksCloudState.Notice.Kind.NOT_FOUND, "not-found notice");
        changes(h.remote.take("changes"), "c2", false);
        h.drain();
        h.engine.discard(update);

        String mismatch = h.engine.createTask(content(null, "Mismatch"));
        h.drain();
        Request m = h.remote.take("mutate");
        fail(m, TasksCloudRemote.Failure.Kind.TASK, "tasks_mutation_mismatch", null, 0);
        h.drain();
        check(h.pending(m.mutation.mutationId).state == TasksCloudState.PendingState.QUARANTINED, "422 mismatch quarantined");
        expect(TasksCloudError.class, () -> h.engine.retry(m.mutation.mutationId), "quarantine never auto-retried or re-id'd");
        String other = h.engine.createTask(content(null, "Independent"));
        h.drain();
        Request independent = h.remote.take("mutate");
        check(h.state().task(other) != null && independent.kind.equals("mutate"), "independent chains continue");
        fail(independent, TasksCloudRemote.Failure.Kind.TASK, "tasks_invalid_request", null, 0);
        h.drain();
        check(h.pending(independent.mutation.mutationId).state == TasksCloudState.PendingState.INVALID, "422 invalid: terminal repair");
        check(mismatch != null, "mismatch create id returned");
        h.sql.close();
    }

    /** Review MED: a receipt overlay survives a cycle whose coalesced row moved beyond H. */
    static void receiptOverlaySurvivesRowCoalescedBeyondH() {
        Harness h = new Harness();
        String t = TasksWire.newId();
        h.bootstrap(task(t, "3", "Open"));
        String created = h.engine.createTask(content(null, "Just made"));
        h.drain();
        Request create = h.remote.take("mutate");
        h.engine.setCompleted(t, true); // queued behind the in-flight create (different chain)
        receipt(create, "1");
        h.drain();
        receipt(h.remote.take("mutate"), "4");
        h.drain();
        changes(h.remote.take("changes"), "c2", false); // both rows coalesced past H: nothing in this cycle
        h.drain();
        TasksCloudState state = h.state();
        check(state.task(created) != null && state.task(t).completed, "create and completion stay visible after an empty cycle");
        check(h.ledger.entries().size() == 2, "receipts retained until the shadow shows them");
        h.engine.refresh();
        h.drain();
        changes(h.remote.take("changes"), "c3", false, upsert(20, taskJson(t, "4", "owner", "777", null, "Open", true, null)),
                upsert(21, task(created, "2", "Just made")));
        h.drain();
        state = h.state();
        check(h.ledger.entries().isEmpty() && state.task(t).completed && state.task(created).server != null,
                "settled once the shadow revision reaches the receipt");

        String gone = h.engine.createTask(content(null, "Deleted elsewhere"));
        h.drain();
        receipt(h.remote.take("mutate"), "1");
        h.drain();
        changes(h.remote.take("changes"), "c4", false);
        h.drain();
        for (int cycle = 5; cycle < 9; cycle++) { // the row may move beyond H on EVERY cycle
            check(h.state().task(gone) != null && h.ledger.entries().size() == 1, "repeated coalescence: receipt overlay kept (cycle " + cycle + ")");
            h.engine.refresh();
            h.drain();
            changes(h.remote.take("changes"), "c" + cycle, false);
            h.drain();
        }
        h.engine.refresh();
        h.drain();
        changes(h.remote.take("changes"), "c9", false, remove(30, "task", gone));
        h.drain();
        check(h.state().task(gone) == null && h.ledger.entries().isEmpty(), "settled only by the actual removal event");

        String inv = TasksWire.newId(), invited = TasksWire.newId();
        h.engine.refresh();
        h.drain();
        changes(h.remote.take("changes"), "c10", false, upsert(31, invitation(inv, invited, "2")));
        h.drain();
        h.engine.respond(inv, TasksWire.Decision.DECLINE);
        h.drain();
        receipt(h.remote.take("mutate"), "3");
        h.drain();
        changes(h.remote.take("changes"), "c11", false);
        h.drain();
        check(h.ledger.entries().size() == 1 && h.state().invitation(inv) == null, "decline receipt kept while its invitation is still in the shadow");
        h.engine.refresh();
        h.drain();
        changes(h.remote.take("changes"), "c12", false, remove(32, "invitation", inv));
        h.drain();
        check(h.ledger.entries().isEmpty(), "decline settled by its invitation's removal (no TaskItem ever appears)");
        h.sql.close();
    }

    /** Discarding a failed move must not let a list.delete authored on its optimistic view cascade the task. */
    static void discardedMoveTurnsDependentDeleteIntoExplicitConflict() {
        Harness h = new Harness();
        String a = TasksWire.newId(), b = TasksWire.newId(), t = TasksWire.newId();
        h.bootstrap(list(a, "1", "A"), list(b, "1", "B"), taskJson(t, "1", "owner", "777", a, "Moving", false, null));
        String move = h.engine.updateTask(t, content(b, "Moving"));
        h.drain();
        fail(h.remote.take("mutate"), TasksCloudRemote.Failure.Kind.TASK, "tasks_invalid_request", null, 0);
        h.drain();
        String delete = h.engine.deleteList(a);
        h.drain();
        h.remote.none("delete(A) blocked behind the failed move");
        h.engine.discard(move);
        h.drain();
        check(h.pending(delete).state == TasksCloudState.PendingState.CONFLICT && a.equals(h.state().task(t).content.listId),
                "after discard the task is back in A and delete(A) needs explicit re-confirmation");
        boolean noticed = false;
        for (TasksCloudState.Notice notice : h.state().notices) noticed |= notice.kind == TasksCloudState.Notice.Kind.DEPENDENCY_DROPPED;
        check(noticed, "dependency-dropped notice");
        h.remote.none("no silent cascade delete");
        h.sql.close();
    }

    /** Review HIGH edge: a reapplied move must depend on the list the task is in NOW, not the stale source. */
    static void reappliedMoveRecapturesCurrentSourceList() {
        Harness h = new Harness();
        String a = TasksWire.newId(), b = TasksWire.newId(), c = TasksWire.newId(), t = TasksWire.newId();
        h.bootstrap(list(a, "1", "A"), list(b, "1", "B"), list(c, "1", "C"), taskJson(t, "1", "owner", "777", a, "Moving", false, null));
        String move = h.engine.updateTask(t, content(b, "Moving"));
        h.drain();
        fail(h.remote.take("mutate"), TasksCloudRemote.Failure.Kind.TASK, "tasks_conflict", TasksWire.ConflictReason.REVISION_CHANGED, 0);
        h.drain();
        changes(h.remote.take("changes"), "c2", false, upsert(40, taskJson(t, "5", "owner", "777", c, "Moving", false, null)));
        h.drain();
        String again = h.engine.resolveConflict(move, true);
        h.drain();
        Request reapplied = h.remote.take("mutate");
        check(reapplied.mutation.mutationId.equals(again) && body(reapplied).contains("\"base_revision\":\"5\""), "reapply on the fresh revision");
        fail(reapplied, TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", null, 0);
        h.drain();
        String delete = h.engine.deleteList(c);
        h.drain();
        h.remote.none("delete(C) waits: the reapplied move now leaves C");
        check(h.pending(delete).state == TasksCloudState.PendingState.WAITING, "dependency recaptured from the current row");
        h.sql.close();
    }

    /**
     * Security LOW: cold repository whose gate fenced linked P1, Pro unlinked before the fresh engine ever bound.
     * The persisted P1 cache and outbox must be shown (NOT_LINKED) under the proven persisted principal, with no
     * request; a P2 gate must still refuse that cache.
     */
    static void coldUnlinkedEngineShowsPersistedPrincipalCache() {
        Harness h = new Harness();
        String t = TasksWire.newId();
        h.bootstrap(task(t, "1", "Cached"));
        String draft = h.engine.createTask(content(null, "Draft"));
        h.drain();
        fail(h.remote.take("mutate"), TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", null, 0);
        h.drain();

        Harness cold = h.restart();
        TasksCloudIdentityGate gate = new TasksCloudIdentityGate();
        gate.admit(new TasksCloudIdentityGate.Identity(A, 1), TasksCloudState.empty(TasksCloudState.Status.LOADING, TG)); // subscribe saw linked P1
        cold.remote.identity = new TasksCloudRemote.Identity(TG, null, 2); // unlinked before the engine bound
        cold.engine.start();
        cold.drain();
        cold.remote.none("unlinked: no Tasks request");
        TasksCloudState state = cold.last;
        check(state != null && state.status == TasksCloudState.Status.NOT_LINKED, "NOT_LINKED status");
        check(state.task(t) != null && state.task(draft) != null && state.pending.size() == 1, "persisted cache and outbox visible");
        check(A.equals(state.principal), "presentation principal is the proven persisted principal, not null");
        check(gate.admit(new TasksCloudIdentityGate.Identity(null, 2), state).state == state, "P1 gate shows the cache instead of LOADING forever");
        TasksCloudIdentityGate other = new TasksCloudIdentityGate();
        other.admit(new TasksCloudIdentityGate.Identity(B, 3), TasksCloudState.empty(TasksCloudState.Status.LOADING, TG));
        check(other.admit(new TasksCloudIdentityGate.Identity(B, 3), state).state.contentFree(), "a P2 gate still refuses P1's cache (never relabelled)");
        cold.sql.close();
    }

    /** Pro rebind to another principal: old view gone at once, the old binding's in-flight reply is dropped. */
    static void principalRebindIsAppliedImmediatelyAndFencesOldReplies() {
        Harness h = new Harness();
        String t = TasksWire.newId(), late = TasksWire.newId();
        h.bootstrap(task(t, "1", "A's task"));
        h.engine.refresh();
        h.drain();
        Request old = h.remote.take("changes");
        h.remote.identity = new TasksCloudRemote.Identity(TG, B, 9);
        h.engine.identityChanged();
        h.drain();
        check(h.last != null && B.equals(h.last.principal) && h.last.task(t) == null, "published view is B's, without A's cache");
        Request snap = h.remote.take("snapshot");
        check(snap.cursor == null, "B starts a fresh snapshot immediately");
        changes(old, "c-old", false, upsert(50, task(late, "1", "Late A")));
        h.drain();
        check(h.state().task(late) == null && B.equals(h.state().principal), "late reply of the old binding cannot recreate A's data");
        expect(TasksCloudError.class, () -> h.engine.updateTask(t, content(null, "Edit A")), "A's row is not editable under B");
        h.sql.close();
    }

    /** Review HIGH: a move A->B with unknown outcome must block delete(A), or the server cascade-deletes the task. */
    static void moveBlocksDeleteOfSourceListUntilReceipt() {
        Harness h = new Harness();
        String a = TasksWire.newId(), b = TasksWire.newId(), t = TasksWire.newId();
        h.bootstrap(list(a, "1", "A"), list(b, "1", "B"), taskJson(t, "1", "owner", "777", a, "Moving", false, null));
        String move = h.engine.updateTask(t, content(b, "Moving"));
        h.drain();
        fail(h.remote.take("mutate"), TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", null, 0);
        h.drain();
        check(b.equals(h.state().task(t).content.listId), "optimistic view shows the task in B");
        String delete = h.engine.deleteList(a);
        h.drain();
        h.remote.none("delete(A) is not sent while the move's outcome is unknown");
        check(h.pending(delete).state == TasksCloudState.PendingState.WAITING, "delete(A) waits on the move from A");
        h.advance(TasksCloudPolicy.MAX_BACKOFF_MS);
        Request retried = h.remote.take("mutate");
        check(retried.mutation.mutationId.equals(move), "move resent first (same id)");
        receipt(retried, "2");
        h.drain();
        // R4 sealing fix: an R3 server advanced A's revision with the move, so delete(A) seals from the settled shadow.
        changes(h.remote.take("changes"), "c2", false, upsert(10, taskJson(t, "2", "owner", "777", b, "Moving", false, null)),
                upsert(11, list(a, "2", "A")));
        h.drain();
        Request next = h.remote.take("mutate");
        check(next.mutation.mutationId.equals(delete) && body(next).contains("\"base_revision\":\"2\""),
                "delete(A) only after the move's receipt (settled), sealed on A's current revision");
        h.sql.close();
    }

    static void listCreatePrecedesDependentTaskAndRefusalBlocksIt() {
        Harness h = new Harness();
        h.bootstrap();
        String listId = h.engine.createList("Work", 0);
        h.drain();
        Request listCreate = h.remote.take("mutate");
        String taskId = h.engine.createTask(content(listId, "In list"));
        h.drain();
        check(h.state().task(taskId).pending.state == TasksCloudState.PendingState.WAITING, "task waits for its list.create");
        fail(listCreate, TasksCloudRemote.Failure.Kind.TASK, "tasks_creation_not_permitted", null, 0);
        h.drain();
        h.remote.none("dependent task is blocked locally, never re-filed");
        check(listId.equals(h.state().task(taskId).content.listId), "task keeps its list (no silent re-file to Cloud Inbox)");
        h.engine.retry(listCreate.mutation.mutationId);
        h.drain();
        receipt(h.remote.take("mutate"), "1");
        h.drain();
        Request dependent = h.remote.take("mutate");
        check(body(dependent).contains("\"list_id\":\"" + listId + "\""), "dependent create sent after acknowledgement");
        h.sql.close();
    }

    static void invitationFlows() {
        Harness h = new Harness();
        String own = TasksWire.newId(), shared = TasksWire.newId(), inv = TasksWire.newId();
        h.bootstrap(task(own, "2", "Mine"), invitation(inv, shared, "4"));
        String invitationId = h.engine.invite(own, "123456789012");
        h.drain();
        Request request = h.remote.take("mutate");
        check(body(request).contains("\"recipient_tg_id\":\"123456789012\"") && invitationId.equals(h.state().task(own).liveInvitationId),
                "explicit invitation with a new epoch");
        receipt(request, "3");
        h.drain();
        changes(h.remote.take("changes"), "c2", false);
        h.drain();
        h.engine.respond(inv, TasksWire.Decision.ACCEPT);
        h.drain();
        Request accept = h.remote.take("mutate");
        check(body(accept).contains("\"base_revision\":\"4\"") && h.state().invitation(inv) == null,
                "accept uses the invitation's task revision; preview hidden while pending");
        receipt(accept, "5");
        h.drain();
        changes(h.remote.take("changes"), "c3", false, remove(7, "invitation", inv), upsert(8, assigned(shared, "5", inv)));
        h.drain();
        check(h.state().task(shared) != null && !h.state().task(shared).owner, "accepted task visible read-only");
        h.engine.respond(inv, TasksWire.Decision.LEAVE);
        h.drain();
        check(h.state().task(shared) == null, "Leave hides immediately (confirmed by the UI first)");
        h.sql.close();
    }

    static void schemaInvariants() {
        Harness h = new Harness();
        PySqlite sql = h.sql;
        String insert = "INSERT INTO outbox(mutation_id,principal,kind,resource_id,chain,body,sealed,state,sent) VALUES(?,?,?,?,?,?,?,?,?)";
        byte[] bytes = {1};
        expect(IllegalStateException.class, () -> sql.exec(insert, "m1", A, "task.update", "x", "task:x", bytes, 0L, "sending", 1L), "sent requires sealed");
        expect(IllegalStateException.class, () -> sql.exec(insert, "m2", A, "task.update", "x", "task:x", bytes, 1L, "held", 0L), "held only task.delete");
        expect(IllegalStateException.class, () -> sql.exec(insert, "m3", A, "task.create", "x", "task:x", bytes, 1L, "acked", 1L), "acked needs cycle");
        sql.exec(insert, "m4", A, "task.create", "x", "task:x", bytes, 1L, "queued", 0L);
        expect(IllegalStateException.class, () -> sql.exec(insert, "m4", A, "task.create", "y", "task:y", bytes, 1L, "queued", 0L), "unique mutation id");
        expect(IllegalStateException.class, () -> sql.exec("INSERT INTO staging(singleton,phase,cursor,changes_cursor,page_limit) VALUES(2,'pages',NULL,NULL,50)"), "staging singleton");
        expect(IllegalStateException.class, () -> sql.exec("INSERT INTO staging(singleton,phase,cursor,changes_cursor,page_limit) VALUES(1,'catchup',NULL,NULL,50)"), "catch-up needs cursors");
        h.sql.close();
    }

    /** ProOrigin.storageToken of zenlygram.org and of 10.0.2.2 (the local stack), as in design 50 §3.1. */
    static final String ORG_TOKEN = "6e0e6d39ba09d62eba37c6d82bbb78cd254b4ffc237e268c4124acea5dd835a4",
            LOCAL_TOKEN = "f9292b96ff7d66abe0820bbe9bd022cb0abd37bd673d7b462e1bba84245a9f66";

    static void originScopedFileNames() {
        String org = TasksCloudSchema.fileName(ORG_TOKEN, TG);
        check(org.equals("zenly_tasks_cloud.o-" + ORG_TOKEN + ".777.db"), "v2 name: origin token, then raw Telegram id");
        check(!org.equals(TasksCloudSchema.fileName(LOCAL_TOKEN, TG)), "two origins never share a database");
        check(!org.startsWith(TasksCloudSchema.LEGACY_PREFIX) && !org.startsWith("zenly_tasks_" + TG),
                "never the legacy name nor the local-only database");
        TasksCloudSchema.Owner owner = TasksCloudSchema.ownerOf(org);
        check(owner != null && ORG_TOKEN.equals(owner.token) && owner.rawTelegramId == TG, "owner round trip");
        owner = TasksCloudSchema.ownerOf(TasksCloudSchema.fileName(LOCAL_TOKEN, Long.MAX_VALUE));
        check(owner != null && LOCAL_TOKEN.equals(owner.token) && owner.rawTelegramId == Long.MAX_VALUE, "largest raw id");
        for (String name : Arrays.asList(null, "", "zenly_tasks_cloud_777.db", "zenly_tasks_777.db",
                "zenly_tasks_cloud.o-" + ORG_TOKEN + ".0777.db", "zenly_tasks_cloud.o-" + ORG_TOKEN + ".0.db",
                "zenly_tasks_cloud.o-" + ORG_TOKEN + "..db", "zenly_tasks_cloud.o-" + ORG_TOKEN + ".db",
                "zenly_tasks_cloud.o-" + ORG_TOKEN + ".-777.db", "zenly_tasks_cloud.o-" + ORG_TOKEN + ".7a7.db",
                "zenly_tasks_cloud.o-" + ORG_TOKEN + ".9223372036854775808.db", "zenly_tasks_cloud.o-" + ORG_TOKEN + ".777.db-wal",
                "zenly_tasks_cloud.o-" + ORG_TOKEN.toUpperCase(Locale.ROOT) + ".777.db",
                "zenly_tasks_cloud.o-" + ORG_TOKEN.substring(1) + ".777.db", "zenly_tasks_cloud.o-" + ORG_TOKEN + "0.777.db",
                "zenly_tasks_cloud.o-" + ORG_TOKEN + "_777.db", "zenly_tasks_cloud.o-" + ORG_TOKEN + ".777.sqlite")) {
            check(TasksCloudSchema.ownerOf(name) == null, "not an exact v2 cloud database name: " + name);
        }
        for (String token : Arrays.asList(null, "", ORG_TOKEN.substring(1), ORG_TOKEN + "0",
                ORG_TOKEN.toUpperCase(Locale.ROOT), "!unconfigured", ORG_TOKEN.substring(1) + "g")) {
            expect(IllegalArgumentException.class, () -> TasksCloudSchema.fileName(token, TG), "malformed origin token refused");
        }
        expect(IllegalArgumentException.class, () -> TasksCloudSchema.fileName(ORG_TOKEN, 0), "raw id 0 refused");
        expect(IllegalArgumentException.class, () -> TasksCloudSchema.fileName(ORG_TOKEN, -TG), "negative raw id refused");
    }

    /**
     * Source guard (design 50 §3.7): no production path names a pre-origin cloud database or marker file. Outside
     * comments, the only "zenly_tasks_cloud" literals of the app sources are the v2 file prefix, the v2 marker file and
     * the LEGACY_PREFIX definition, and no production code but that definition refers to that constant
     * (TasksCloudSchema.LEGACY_PREFIX).
     *
     * <p>What "refers to it" means: the bare name where the schema's constant is in scope (the schema's own package,
     * or a file that imports it statically), and the qualified name anywhere. Another class may have a constant of the
     * same name for its own storage (the app's consent migration has one for its old preference keys): that is not a
     * reference to the Cloud Tasks one, and if its value named a pre-origin cloud file the literal scan below would
     * still refuse it.
     */
    static void legacyStorageIsNeverNamedInProduction() throws Exception {
        File android = new File(bridge).getAbsoluteFile();
        for (int up = 0; up < 5; up++) android = android.getParentFile(); // state, cloud, tasks, zenlygram-tests, android
        ArrayList<File> sources = new ArrayList<>();
        for (String root : Arrays.asList("TMessagesProj/src/main/java", "Zenlygram/src/main/java")) collectJava(new File(android, root), sources);
        check(sources.size() > 100, "production sources found under " + android);
        Pattern literal = Pattern.compile("\"(zenly_tasks_cloud[^\"]*)\"");
        Pattern schemaPackage = Pattern.compile("(?m)^package\\s+org\\.telegram\\.messenger\\.zenlygram\\.tasks\\.cloud\\s*;");
        Pattern schemaStaticImport = Pattern.compile(
                "import\\s+static\\s+org\\.telegram\\.messenger\\.zenlygram\\.tasks\\.cloud\\.TasksCloudSchema\\.(\\*|LEGACY_PREFIX)\\s*;");
        HashSet<String> seen = new HashSet<>();
        boolean schemaInScope = false;
        for (File source : sources) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            if (!text.contains("zenly_tasks_cloud") && !text.contains("LEGACY_PREFIX")) continue;
            boolean schema = source.getName().equals("TasksCloudSchema.java");
            boolean inScope = schemaPackage.matcher(text).find() || schemaStaticImport.matcher(text).find();
            if (schema && inScope) schemaInScope = true;
            for (String line : text.split("\n")) {
                String code = line.trim();
                if (code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")) continue;
                boolean legacyDefinition = schema && code.contains("LEGACY_PREFIX =");
                boolean refers = code.contains("TasksCloudSchema.LEGACY_PREFIX") || inScope && code.contains("LEGACY_PREFIX");
                if (refers && !legacyDefinition) {
                    throw new AssertionError("production refers to LEGACY_PREFIX: " + source.getName() + ": " + code);
                }
                Matcher matcher = literal.matcher(code);
                while (matcher.find()) {
                    String value = matcher.group(1);
                    if (!value.equals(TasksCloudSchema.FILE_PREFIX) && !value.equals("zenly_tasks_cloud.v2")
                            && !(legacyDefinition && value.equals(TasksCloudSchema.LEGACY_PREFIX))) {
                        throw new AssertionError("production names pre-origin cloud storage: " + source.getName() + ": " + code);
                    }
                    seen.add(value);
                }
            }
        }
        check(seen.equals(new HashSet<>(Arrays.asList(TasksCloudSchema.FILE_PREFIX, "zenly_tasks_cloud.v2", TasksCloudSchema.LEGACY_PREFIX))),
                "the scan saw the v2 prefix, the v2 marker file and the legacy constant: " + seen);
        check(schemaInScope, "the scan knows the schema's own package, where the bare name is its constant");
    }

    static void collectJava(File file, List<File> into) {
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectJava(child, into);
            else if (child.getName().endsWith(".java")) into.add(child);
        }
    }

    static void policyTable() {
        check(TasksCloudPolicy.classify(new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.UNLINKED, "tasks_account_not_linked", null, 403, 0))
                == TasksCloudPolicy.Action.PAUSE_UNLINKED, "unlinked is an account pause, not access loss");
        check(TasksCloudPolicy.classify(new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.TASK, "tasks_mutation_in_flight", null, 409, 0))
                == TasksCloudPolicy.Action.BACKOFF, "in-flight: same id backoff");
        check(TasksCloudPolicy.classify(new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.LOCAL, "rotation_pending", null, 0, 0))
                == TasksCloudPolicy.Action.BACKOFF, "rotation pending: retry later, same id");
        check(TasksCloudPolicy.classify(new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.PROTOCOL, "request_rejected", null, 413, 0))
                == TasksCloudPolicy.Action.INVALID, "413 is not a receipt");
        check(TasksCloudPolicy.backoffMs(1) == TasksCloudPolicy.BASE_BACKOFF_MS && TasksCloudPolicy.backoffMs(40) == TasksCloudPolicy.MAX_BACKOFF_MS, "bounded backoff");
    }

    // ---- import ----

    static ZenlyTaskImport.Snapshot localSnapshot(List<ZenlyTaskImport.Mapping> mappings) {
        LinkedHashMap<String, String> incarnations = new LinkedHashMap<>();
        incarnations.put("inbox", "inc-inbox");
        incarnations.put("work", "inc-work");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 4097; i++) longText.append('x');
        List<ZenlyTaskModel.Task> tasks = Arrays.asList(
                new ZenlyTaskModel.Task("t1", "inbox", "Inbox task", "", null, 0, false, null, 1_700_000_000_999L, 1_700_000_000_999L),
                new ZenlyTaskModel.Task("t2", "work", "Report", "Notes", 1_700_000_000_999L, 2, true, 1_700_000_100_500L, 1_700_000_000_999L, 1_700_000_100_500L),
                new ZenlyTaskModel.Task("t3", "work", "Long", longText.toString(), null, 0, false, null, 1_700_000_001_000L, 1_700_000_001_000L),
                new ZenlyTaskModel.Task("t4", "work", "Far", "", 2_147_483_648_000L, 0, false, null, 1_700_000_002_000L, 1_700_000_002_000L),
                new ZenlyTaskModel.Task("t5", "work", " padded", "", null, 0, false, null, 1_700_000_003_000L, 1_700_000_003_000L));
        return new ZenlyTaskImport.Snapshot(Arrays.asList(new ZenlyTaskModel.Folder("inbox", "", 0), new ZenlyTaskModel.Folder("work", "Work", 1_700_000_000_000L)),
                incarnations, tasks, mappings);
    }

    static TasksCloudImport.TaskRow row(TasksCloudImport.ImportPreview preview, String id) {
        for (TasksCloudImport.TaskRow row : preview.tasks) if (row.localTaskId.equals(id)) return row;
        throw new AssertionError("row " + id);
    }

    static void staleAfterSendIsUnknownAndReplaysSameMutation() {
        Harness h = new Harness();
        h.bootstrap();
        h.engine.createTask(content(null, "Raced"));
        h.drain();
        Request first = h.remote.take("mutate");
        fail(first, TasksCloudRemote.Failure.Kind.STALE, "session_changed", null, 0);
        h.drain();
        TasksCloudState.Pending pending = h.pending(first.mutation.mutationId);
        check(pending != null && pending.sent && pending.state == TasksCloudState.PendingState.QUEUED, "STALE after send = outcome unknown; sealed entry kept");
        h.remote.identity = new TasksCloudRemote.Identity(TG, A, 2); // rotated credential, same principal
        h.advance(TasksCloudEngine.REQUEUE_MS);
        Request replay = h.remote.take("mutate");
        check(replay.mutation.mutationId.equals(first.mutation.mutationId) && Arrays.equals(replay.mutation.body(), first.mutation.body()),
                "replayed with the same id/bytes under the freshly captured identity");
        h.sql.close();
    }

    static void importPreviewPlanAndMappingReuse() {
        long now = 1_800_000_000_000L;
        HashSet<String> none = new HashSet<>();
        TasksCloudImport.ImportPreview preview = TasksCloudImport.preview(TG, A, localSnapshot(Collections.<ZenlyTaskImport.Mapping>emptyList()), none, none, now);
        check(preview.telegramUserId == TG && A.equals(preview.principal), "preview names the destination account");
        check(preview.folders.get(0).inbox && preview.folders.get(0).status == TasksCloudImport.RowStatus.INVALID
                && "title".equals(preview.folders.get(0).invalidField), "untitled local Inbox needs an explicit choice, no fabricated title");
        check(preview.folders.get(1).status == TasksCloudImport.RowStatus.NEW, "real folder imports as a real list");
        check(row(preview, "t1").status == TasksCloudImport.RowStatus.BLOCKED_BY_FOLDER, "task under invalid folder blocked, not re-filed");
        check(row(preview, "t2").status == TasksCloudImport.RowStatus.NEW, "valid task");
        check(row(preview, "t3").status == TasksCloudImport.RowStatus.INVALID && row(preview, "t3").invalidField.contains("description"),
                "4097-grapheme description refused, never truncated");
        check(row(preview, "t4").status == TasksCloudImport.RowStatus.INVALID && row(preview, "t4").invalidField.contains("due_at"),
                "deadline beyond cloud range refused");
        check(row(preview, "t5").status == TasksCloudImport.RowStatus.INVALID, "boundary whitespace refused, not trimmed");

        TasksCloudImport.Plan plan = TasksCloudImport.plan(preview, TasksCloudImport.ImportChoices.none());
        check(plan.lists == 1 && plan.tasks == 1 && plan.leftLocal == 4, "only eligible rows queued; others stay local");
        TasksWire.Operation.ListCreate work = (TasksWire.Operation.ListCreate) plan.operations.get(0);
        TasksWire.Operation.TaskCreate report = (TasksWire.Operation.TaskCreate) plan.operations.get(1);
        check("Work".equals(work.title) && "2023-11-14T22:13:20Z".equals(work.createdAt), "folder title/created_at preserved (ms->s)");
        check(work.listId.equals(report.content.listId) && ("list:" + work.listId).equals(plan.listKeys.get(1)), "task targets its mapped list");
        check(report.content.dueAt == 1_700_000_000L && "2023-11-14T22:13:20Z".equals(report.createdAt)
                && "2023-11-14T22:15:00Z".equals(report.completedAt) && report.isCompleted && "Notes".equals(report.content.description),
                "explicit millisecond->second conversion of due/created/completed");
        check(plan.mappings.size() == 2 && "inc-work".equals(plan.mappings.get(0).localKey) && "t2".equals(plan.mappings.get(1).localKey),
                "mapping keyed by folder incarnation and task id");

        TasksCloudImport.Plan inbox = TasksCloudImport.plan(preview, TasksCloudImport.ImportChoices.none().withFolderInCloudInbox("inc-inbox"));
        TasksWire.Operation.TaskCreate inboxTask = null;
        for (TasksWire.Operation op : inbox.operations) {
            if (op instanceof TasksWire.Operation.TaskCreate && "Inbox task".equals(((TasksWire.Operation.TaskCreate) op).content.title)) {
                inboxTask = (TasksWire.Operation.TaskCreate) op;
            }
        }
        check(inboxTask != null && inboxTask.content.listId == null && inbox.lists == 1, "explicit Cloud Inbox choice: no hidden list.create");
        TasksCloudImport.Plan titled = TasksCloudImport.plan(preview, TasksCloudImport.ImportChoices.none().withFolderTitle("inc-inbox", "Inbox"));
        TasksWire.Operation.ListCreate titledInbox = (TasksWire.Operation.ListCreate) titled.operations.get(0);
        check(titled.lists == 2 && "Inbox".equals(titledInbox.title) && titledInbox.createdAt == null,
                "user-typed title; unknown local creation time sends null, never 1970");
        expect(TasksCloudError.class, () -> TasksCloudImport.plan(preview, TasksCloudImport.ImportChoices.none().withFolderTitle("missing", "X")),
                "stale choice refused");

        HashSet<String> known = new HashSet<>(Collections.singletonList("list:" + work.listId));
        TasksCloudImport.ImportPreview again = TasksCloudImport.preview(TG, A, localSnapshot(plan.mappings), known, none, now);
        check(again.folders.get(1).status == TasksCloudImport.RowStatus.ALREADY_IMPORTED && row(again, "t2").status == TasksCloudImport.RowStatus.RESEND,
                "re-login: synced list recognised, unconfirmed task resends");
        TasksCloudImport.Plan resend = TasksCloudImport.plan(again, TasksCloudImport.ImportChoices.none());
        TasksWire.Operation.TaskCreate resent = (TasksWire.Operation.TaskCreate) resend.operations.get(0);
        check(resend.mutationIds.get(0).equals(plan.mutationIds.get(1)) && resent.taskId.equals(report.taskId)
                && work.listId.equals(resent.content.listId) && resend.mappings.isEmpty(),
                "uncertain receipt resolves the SAME mutation id/cloud id; no duplicate mapping");
        HashSet<String> outbox = new HashSet<>(Collections.singletonList(plan.mutationIds.get(1)));
        check(row(TasksCloudImport.preview(TG, A, localSnapshot(plan.mappings), known, outbox, now), "t2").status == TasksCloudImport.RowStatus.PENDING,
                "queued import is not planned twice");
        check(TasksCloudImport.preview(TG, B, localSnapshot(Collections.<ZenlyTaskImport.Mapping>emptyList()), none, none, now).principal.equals(B),
                "another principal gets its own mapping scope");

        check(TasksCloudImport.dueSecondsFromLocalMillis(null) == null, "no deadline stays null");
        check(TasksCloudImport.dueSecondsFromLocalMillis(2_147_483_647_999L) == 2_147_483_647L, "upper bound floors into range");
        expect(TasksWire.Invalid.class, () -> TasksCloudImport.dueSecondsFromLocalMillis(2_147_483_648_000L), "beyond MAX_DEADLINE");
        expect(TasksWire.Invalid.class, () -> TasksCloudImport.dueSecondsFromLocalMillis(0L), "local deadlines are > 0");

        Harness h = new Harness();
        h.bootstrap();
        check(h.engine.enqueueImport(A, plan) == 2, "consented import queued");
        h.drain();
        check(h.remote.take("mutate").mutation.operation instanceof TasksWire.Operation.ListCreate, "list create first");
        check(h.engine.enqueueImport(A, plan) == 0, "re-confirm is idempotent");
        expect(TasksCloudError.class, () -> h.engine.enqueueImport(B, plan), "no automatic second-account claim");
        h.sql.close();
    }

    /** A realtime hint is the existing changes pull now (not the 60 s poll); one arriving mid-cycle forces one more. */
    static void realtimeHintPullsNowAndSurvivesTheCycleInFlight() {
        Harness h = new Harness();
        h.bootstrap();
        h.engine.requestSync();
        h.drain();
        Request first = h.remote.take("changes");
        check("c1".equals(first.cursor), "hint: immediate changes pull from the stored cursor, no 60 s wait");
        h.engine.requestSync(); // the server committed again while the first answer was on its way
        h.drain();
        changes(first, "c2", false);
        h.drain();
        Request second = h.remote.take("changes");
        check("c2".equals(second.cursor), "a hint during the in-flight cycle is not swallowed by its (older) answer");
        changes(second, "c3", false);
        h.drain();
        h.remote.none("no extra cycle once every hint is covered");
        h.engine.stop();
        h.drain();
        h.engine.requestSync();
        h.drain();
        h.remote.none("no hint pull while the board is stopped");
        h.engine.start();
        h.drain();
        check("c3".equals(h.remote.take("changes").cursor), "board start (foreground) catches up with its own changes pull, realtime or not");
        h.sql.close();
    }

    static void realtimeHintNeverJumpsBackoffRetryAfterOrPause() {
        Harness h = new Harness();
        h.bootstrap();
        h.engine.requestSync();
        h.drain();
        fail(h.remote.take("changes"), TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", null, 0);
        h.drain();
        h.engine.requestSync();
        h.drain();
        h.remote.none("a hint waits for the failure backoff");
        h.advance(TasksCloudPolicy.backoffMs(1));
        Request retried = h.remote.take("changes");
        changes(retried, "c2", false);
        h.drain();

        h.engine.requestSync();
        h.drain();
        fail(h.remote.take("changes"), TasksCloudRemote.Failure.Kind.RATE_LIMITED, "rate_limited", null, 30);
        h.drain();
        check(h.engine.readsGateMs() == 30_000, "a 429 on a read closes the shared reads gate (realtime metadata/auth wait too)");
        h.engine.requestSync();
        h.drain();
        h.advance(29_000);
        h.remote.none("Retry-After outranks any hint");
        h.advance(1_000);
        changes(h.remote.take("changes"), "c3", false);
        h.drain();

        h.engine.realtimeRefused(new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.RATE_LIMITED, "rate_limited", null, 429, 90), h.remote.identity);
        check(h.engine.readsGateMs() == 90_000, "a realtime 429 closes the same reads gate");
        h.engine.requestSync();
        h.drain();
        h.remote.none("a realtime 429 holds back a hint pull");
        h.engine.stop();
        h.engine.start();
        h.drain();
        h.remote.none("a realtime 429 holds back the foreground catch-up pull");
        h.engine.refresh();
        h.drain();
        h.remote.none("a realtime 429 holds back even an explicit Check for changes");
        h.advance(TasksCloudEngine.POLL_MS);
        h.remote.none("a realtime 429 holds back the 60 s idle poll");
        h.advance(89_999 - TasksCloudEngine.POLL_MS);
        h.remote.none("nothing on the reads budget before the realtime Retry-After ends");
        h.advance(1);
        changes(h.remote.take("changes"), "c4", false);
        h.drain();
        h.remote.none("exactly one pull once the shared gate opens");
        h.engine.realtimeRefused(new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.UNLINKED, "tasks_account_not_linked", null, 403, 0), h.remote.identity);
        h.drain();
        check(h.engine.accountPaused() && h.last.status == TasksCloudState.Status.UNLINKED_PAUSED,
                "a realtime 403 tasks_account_not_linked records the same durable pause a pull would");
        h.engine.requestSync();
        h.drain();
        h.advance(TasksCloudEngine.POLL_MS * 3);
        h.remote.none("a hint never resumes a paused account; only an explicit refresh / relink does");
        h.sql.close();
    }

    public static void main(String[] args) throws Exception {
        bridge = args[0];
        dir = new File(args[1]);
        // Fail closed by default (TasksCloudR4Tests proves it); these R3-era scenarios run with traffic allowed.
        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        stagedSnapshotPaginationAndCatchup();
        incompleteSnapshotKeepsShadowAndDraftsAndResumes();
        restartReplaysSameIdAndBytesAndReceiptPrunesAfterSync();
        lateReplyAfterFenceIsInertAndFencedTransactionRollsBack();
        chainedBaseRevisionResolvedBeforeFirstSend();
        conflictKeepsDraftAndReappliesOnlyExplicitly();
        creationRefusalBlocksUntilExplicitRetryWithSameBytes();
        unlinkedPausesWithEverythingPreserved();
        offlineBackoffIsBoundedAndRateLimitRespected();
        heldDeleteUndoPromotionAndRestart();
        confirmedSharedAccessLossRemovesReadableCache();
        principalChangeNeverRetagsOutbox();
        terminalErrorsAreDistinct();
        listCreatePrecedesDependentTaskAndRefusalBlocksIt();
        moveBlocksDeleteOfSourceListUntilReceipt();
        receiptOverlaySurvivesRowCoalescedBeyondH();
        discardedMoveTurnsDependentDeleteIntoExplicitConflict();
        reappliedMoveRecapturesCurrentSourceList();
        principalRebindIsAppliedImmediatelyAndFencesOldReplies();
        coldUnlinkedEngineShowsPersistedPrincipalCache();
        invitationFlows();
        schemaInvariants();
        originScopedFileNames();
        legacyStorageIsNeverNamedInProduction();
        policyTable();
        staleAfterSendIsUnknownAndReplaysSameMutation();
        importPreviewPlanAndMappingReuse();
        realtimeHintPullsNowAndSurvivesTheCycleInFlight();
        realtimeHintNeverJumpsBackoffRetryAfterOrPause();
        System.out.println("Tasks cloud state (host SQLite bridge, scripted remote): " + checks + " checks passed");
    }
}
