package org.telegram.messenger.zenlygram.pro;

import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudPolicy;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRemote;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host regression test of the send-time consent re-check (contract §6.7: a withdrawal stops Tasks traffic at once): the
 * real TasksApi facade and Exchange against a host boundary whose Pro worker is a queue the test runs by hand. A request
 * handed to the worker before a withdrawal is never signed or sent after it: its SendGate is asked on the worker right
 * before each attempt is signed, and a refusal is STALE consent_changed, which TasksCloudPolicy requeues (never an entry
 * failure, never the server's consent fence) for the withdrawal's discard. Before the re-check the queued batch was signed
 * and sent. Not ZenlyPro, not the repository's gate itself (TasksSyncPolicy.sendAllowed: ../../sync), no server.
 */
public final class TasksApiSendGateTests {
    private static int checks;
    static final String PRINCIPAL = "01arz3ndektsv4rrffq69g5fav";
    static final String MID = "11111111-1111-4111-8111-111111111111", M2 = "44444444-4444-4444-8444-444444444444";
    static final String TASK = "33333333-3333-4333-8333-333333333333", LIST = "22222222-2222-4222-8222-222222222222";
    static final String BATCH = "99999999-9999-4999-9999-999999999999";
    static final String T = "2026-10-24T00:00:00Z";

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    /** ZenlyPro's side as a host double: the Pro worker is a queue; signing (prepare) and sending are counted. */
    static final class Slot implements TasksApi.Boundary {
        final List<Runnable> worker = new ArrayList<>();
        final List<ProTransport.TasksReply> script = new ArrayList<>();
        int prepares, sends;
        boolean inWorker;

        @Override public TasksApi.Identity identity() { return new TasksApi.Identity(42, PRINCIPAL, 7); }

        @Override public Object capture(TasksApi.Identity expected) throws TasksApi.Stop {
            if (!expected.equals(identity())) throw TasksApi.stopFor("session_changed", 0);
            return new Object();
        }

        @Override public void execute(Runnable work) { worker.add(work); }

        @Override public void deliver(Runnable callback) { callback.run(); }

        @Override public Object prepare(Object ticket, TasksApi.Request<?> request) {
            prepares++;
            return new Object();
        }

        @Override public ProTransport.TasksReply send(Object attempt, TasksApi.Request<?> request) {
            sends++;
            if (script.isEmpty()) throw new IllegalStateException("unscripted attempt");
            return script.remove(0);
        }

        @Override public String apiBaseUrl(Object attempt) { return "https://api.example.org/v1"; }

        @Override public void settle(Object attempt, TasksApi.Request<?> request, long serverTimeSeconds, int retryAfter) { }

        @Override public void confirm(Object attempt) { }

        @Override public void invalidate(Object attempt, String code, String reason) { }

        /** The Pro worker runs everything queued so far. */
        void runWorker() {
            inWorker = true;
            try {
                while (!worker.isEmpty()) worker.remove(0).run();
            } finally {
                inWorker = false;
            }
        }
    }

    static final class Captured<T> implements TasksApi.Callback<T> {
        T value;
        TasksApi.Failure failure;
        int calls;

        @Override public void onSuccess(T result) {
            value = result;
            calls++;
        }

        @Override public void onFailure(TasksApi.Failure result) {
            failure = result;
            calls++;
        }

        boolean refusedAtSend() {
            return calls == 1 && failure != null && failure.kind == TasksApi.Failure.Kind.STALE
                    && TasksApi.CODE_CONSENT_CHANGED.equals(failure.code) && failure.httpStatus == 0;
        }
    }

    public static void main(String[] args) {
        queuedBatchIsNeverSignedAfterAWithdrawal();
        everyAttemptAsksTheGateRightBeforeSigning();
        everyCallCarriesItsGate();
        aThrowingOrMissingGateRefuses();
        System.out.println("Tasks send gate (TasksApi on a queued Pro worker): " + checks + " checks passed");
    }

    /** The scenario of the review: a batch waits on the worker, the user withdraws, the worker reaches the batch. */
    private static void queuedBatchIsNeverSignedAfterAWithdrawal() {
        Slot slot = new Slot();
        TasksApi api = new TasksApi(slot);
        final AtomicBoolean consent = new AtomicBoolean(true);
        final List<Boolean> askedOnWorker = new ArrayList<>();
        TasksApi.SendGate gate = () -> {
            askedOnWorker.add(slot.inWorker);
            return consent.get();
        };
        Captured<TasksWire.BatchResponse> queued = new Captured<>();
        api.mutateBatch(slot.identity(), batch(), gate, queued);
        check(slot.worker.size() == 1 && slot.prepares == 0 && queued.calls == 0 && askedOnWorker.isEmpty(),
                "handed to the Pro worker; not signed, and the gate not asked, at the call");
        consent.set(false); // the withdrawal commits on the main thread while the batch waits behind other Pro work
        slot.runWorker();
        check(slot.prepares == 0 && slot.sends == 0, "nothing signed, nothing sent after the withdrawal");
        check(queued.refusedAtSend(), "refused as STALE consent_changed (local, no HTTP status)");
        check(askedOnWorker.equals(Arrays.asList(true)), "the gate was asked once, on the worker");
        TasksCloudRemote.Failure engineSide = new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.valueOf(queued.failure.kind.name()),
                queued.failure.code, null, queued.failure.httpStatus, queued.failure.retryAfterSeconds);
        check(TasksCloudPolicy.decide(engineSide) == TasksCloudPolicy.Action.REQUEUE && !TasksCloudPolicy.isConsentFence(engineSide),
                "the engine requeues it (a batch without counting an attempt) for the discard; it is not the server's consent fence");

        // The sync lane is free again; with consent back the next batch is signed and sent exactly once.
        consent.set(true);
        slot.script.add(new ProTransport.TasksReply(200, committed(), 0, 4));
        Captured<TasksWire.BatchResponse> later = new Captured<>();
        api.mutateBatch(slot.identity(), batch(), gate, later);
        slot.runWorker();
        check(later.calls == 1 && later.value != null && later.failure == null, "consent back: the next batch is delivered");
        check(slot.prepares == 1 && slot.sends == 1, "signed and sent once");
    }

    /** The clock-skew retry re-signs: the gate is asked again first, so a withdrawal between the attempts stops the second. */
    private static void everyAttemptAsksTheGateRightBeforeSigning() {
        Slot slot = new Slot();
        TasksApi api = new TasksApi(slot);
        final int[] asked = {0};
        TasksApi.SendGate gate = () -> ++asked[0] == 1; // consent for the first attempt only
        slot.script.add(new ProTransport.TasksReply(401, "{\"error\":\"clock_skew\",\"message\":\"generic\",\"server_time\":\"" + T + "\"}", 0));
        Captured<TasksWire.MutationResponse> result = new Captured<>();
        api.mutate(slot.identity(), mutation(), gate, result);
        slot.runWorker();
        check(asked[0] == 2, "asked before each of the two attempts");
        check(slot.prepares == 1 && slot.sends == 1, "the first attempt went out; the corrected second one was never signed");
        check(result.refusedAtSend(), "the second attempt's refusal is the result");
    }

    /** All six named calls, both lanes: none is signed while its gate refuses, and each frees its lane. */
    private static void everyCallCarriesItsGate() {
        Slot slot = new Slot();
        TasksApi api = new TasksApi(slot);
        TasksApi.SendGate withdrawn = () -> false;
        List<Captured<?>> results = new ArrayList<>();
        Captured<TasksWire.MutationResponse> mutate = new Captured<>();
        api.mutate(slot.identity(), mutation(), withdrawn, mutate);
        slot.runWorker();
        results.add(mutate);
        Captured<TasksWire.BatchResponse> batch = new Captured<>();
        api.mutateBatch(slot.identity(), batch(), withdrawn, batch);
        slot.runWorker();
        results.add(batch);
        Captured<TasksWire.SnapshotResponse> snapshot = new Captured<>();
        api.snapshot(slot.identity(), null, 50, withdrawn, snapshot);
        slot.runWorker();
        results.add(snapshot);
        Captured<TasksWire.ChangesResponse> changes = new Captured<>();
        api.changes(slot.identity(), "c", 50, withdrawn, changes);
        slot.runWorker();
        results.add(changes);
        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Metadata> metadata = new Captured<>();
        api.realtime(slot.identity(), withdrawn, metadata);
        slot.runWorker();
        results.add(metadata);
        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Subscription> auth = new Captured<>();
        api.realtimeAuth(slot.identity(), "123.456", withdrawn, auth);
        slot.runWorker();
        results.add(auth);
        String[] names = {"mutate", "mutateBatch", "snapshot", "changes", "realtime", "realtimeAuth"};
        for (int i = 0; i < names.length; i++) check(results.get(i).refusedAtSend(), names[i] + ": refused at send (no BUSY: each lane was freed)");
        check(slot.prepares == 0 && slot.sends == 0, "none of the six was signed or sent");
    }

    /** Fail closed: a gate that throws, or a call made without one, sends nothing. */
    private static void aThrowingOrMissingGateRefuses() {
        Slot slot = new Slot();
        TasksApi api = new TasksApi(slot);
        Captured<TasksWire.MutationResponse> thrown = new Captured<>();
        api.mutate(slot.identity(), mutation(), () -> { throw new IllegalStateException("customer layer not ready"); }, thrown);
        slot.runWorker();
        check(thrown.refusedAtSend(), "a throwing gate refuses");
        Captured<TasksWire.MutationResponse> missing = new Captured<>();
        api.mutate(slot.identity(), mutation(), null, missing);
        slot.runWorker();
        check(missing.refusedAtSend(), "no gate refuses");
        check(slot.prepares == 0 && slot.sends == 0, "nothing signed or sent");
    }

    // ---- wire fixtures (decoded by the production strict decoders) ----

    static TasksWire.Mutation mutation() {
        return TasksWire.Mutation.seal(PRINCIPAL, MID, TasksWire.Operation.taskComplete(TASK, "7", null, true));
    }

    /** [task.complete (user), list.create (migration)]. */
    static TasksWire.Batch batch() {
        return TasksWire.Batch.of(BATCH, Arrays.asList(new TasksWire.Batch.Item(mutation(), TasksWire.Origin.USER),
                new TasksWire.Batch.Item(TasksWire.Mutation.seal(PRINCIPAL, M2, TasksWire.Operation.listCreate(LIST, "Ishlar", 0, null)),
                        TasksWire.Origin.MIGRATION)));
    }

    /** Both items committed. */
    static String committed() {
        String complete = "{\"mutation_id\":\"" + MID + "\",\"operation\":\"task.complete\",\"outcome\":\"unchanged\",\"resource_kind\":\"task\","
                + "\"resource_id\":\"" + TASK + "\",\"resource_revision\":\"7\",\"committed_at\":\"" + T + "\"}";
        String list = "{\"mutation_id\":\"" + M2 + "\",\"operation\":\"list.create\",\"outcome\":\"committed\",\"resource_kind\":\"list\","
                + "\"resource_id\":\"" + LIST + "\",\"resource_revision\":\"1\",\"committed_at\":\"" + T + "\"}";
        return "{\"schema_version\":1,\"batch_id\":\"" + BATCH + "\",\"results\":[{\"mutation_id\":\"" + MID + "\",\"status\":200,\"receipt\":"
                + complete + "},{\"mutation_id\":\"" + M2 + "\",\"status\":200,\"receipt\":" + list + "}],\"retry_after\":null,\"server_time\":\""
                + T + "\"}";
    }
}
