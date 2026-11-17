package org.telegram.messenger.zenlygram.pro;

import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudPolicy;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRemote;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BOUNDED API STATE-MACHINE PROOF, not ZenlyPro integration: runs the real TasksApi facade,
 * Exchange and error classifier (with the real TasksWire decoders) against a host Slot that models
 * ZenlyPro's documented boundary contract (restamp = same identity+generation with the freshest
 * token, confirm/invalidate = compare the exact signed credential). It proves the ordering decisions
 * made by production TasksApi code: at most two attempts, fresh signing per attempt, success only
 * after a validated decode AND a current-credential check, a real 401 invalidates only the exact
 * credential it was sent with, 403 tasks_account_not_linked never invalidates, strict error
 * allowlist, bounded cooldown, single flight. R4: X-Tasks-Wire: 4 on every call, the batch call
 * (route, body, Idempotency-Key, response bound, its own 20 s budget, sync lane), batch item errors
 * classified exactly like the single route (same Failure, same TasksCloudPolicy action), the
 * recognisable batch_disabled / migration_paused / tasks_consent_required, item 401s applied once
 * while the other receipts are delivered, the echo surfaced. It does NOT exercise the real ZenlyPro
 * session store, keystore signing, Android threads or any server; no reply here is a real delivery receipt.
 */
public final class TasksApiExchangeTests {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    static final String PRINCIPAL = "01arz3ndektsv4rrffq69g5fav";
    static final String MID = "11111111-1111-4111-8111-111111111111";
    static final String TASK = "33333333-3333-4333-8333-333333333333";
    static final String T = "2026-10-24T00:00:00Z";

    /** Host model of ZenlyPro's side of TasksApi.Boundary (see class comment). */
    static class Slot implements TasksApi.Boundary {
        long telegramId = 42, generation = 7;
        String token = "token-1", principal = PRINCIPAL;
        final Deque<Object> script = new ArrayDeque<>();
        final List<String> signedWith = new ArrayList<>();
        final List<Long> serverTimes = new ArrayList<>();
        final List<Integer> cooldowns = new ArrayList<>();
        final List<Runnable> queued = new ArrayList<>();
        int sends, invalidations, confirms;
        boolean queueWork, queueDelivery;
        final List<Runnable> deliveries = new ArrayList<>();
        int captures, lastMaxBytes;
        Runnable duringSend;
        final List<Integer> wires = new ArrayList<>(), timeouts = new ArrayList<>();
        final List<String> idempotencyKeys = new ArrayList<>(), invalidatedWith = new ArrayList<>();
        /** The headers ZenlyPro's prepare adds through TasksApi (Idempotency-Key, X-Tasks-Wire), per attempt. */
        final List<Map<String, String>> headers = new ArrayList<>();

        static final class Signed {
            final long telegramId, generation;
            final String token;
            Signed(long telegramId, long generation, String token) { this.telegramId = telegramId; this.generation = generation; this.token = token; }
        }

        @Override public TasksApi.Identity identity() { return new TasksApi.Identity(telegramId, token == null ? null : principal, generation); }
        @Override public Object capture(TasksApi.Identity expected) throws TasksApi.Stop {
            if (!expected.equals(identity())) throw TasksApi.stopFor("session_changed", 0);
            captures++;
            return new long[]{telegramId, generation};
        }
        @Override public void execute(Runnable work) { if (queueWork) queued.add(work); else work.run(); }
        @Override public void deliver(Runnable callback) { if (queueDelivery) deliveries.add(callback); else callback.run(); }

        @Override public Object prepare(Object ticket, TasksApi.Request<?> request) throws TasksApi.Stop {
            long[] stamp = (long[]) ticket;
            if (stamp[0] != telegramId || stamp[1] != generation) throw TasksApi.stopFor("session_changed", 0);
            if (token == null) throw TasksApi.stopFor("link_required", 0);
            if (request.principal != null && !request.principal.equals(principal)) throw TasksApi.stopFor("principal_mismatch", 0);
            signedWith.add(token);
            Map<String, String> signed = new HashMap<>();
            signed.put("Authorization", "Bearer " + token);
            if (request.idempotencyKey != null) signed.put("Idempotency-Key", request.idempotencyKey);
            TasksApi.putWireHeader(request, signed);
            headers.add(signed);
            return new Signed(telegramId, generation, token);
        }

        final List<String> routes = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        String apiBaseUrl = "https://api.example.org/v1";

        @Override public ProTransport.TasksReply send(Object attempt, TasksApi.Request<?> request) throws Exception {
            sends++;
            lastMaxBytes = request.maxBytes;
            routes.add(request.method + " " + request.route + request.query);
            bodies.add(request.body);
            wires.add(request.wire);
            timeouts.add(request.timeoutMs);
            idempotencyKeys.add(request.idempotencyKey);
            if (duringSend != null) duringSend.run();
            Object next = script.poll();
            if (next instanceof Exception) throw (Exception) next;
            if (next == null) throw new IllegalStateException("unscripted attempt");
            return (ProTransport.TasksReply) next;
        }

        @Override public String apiBaseUrl(Object attempt) { return apiBaseUrl; }

        @Override public void settle(Object attempt, TasksApi.Request<?> request, long serverTimeSeconds, int retryAfter) {
            serverTimes.add(serverTimeSeconds);
            if (retryAfter >= 0) cooldowns.add(retryAfter);
        }

        private void requireCurrent(Object attempt) throws TasksApi.Stop {
            Signed signed = (Signed) attempt;
            if (signed.telegramId != telegramId || signed.generation != generation || token == null || !token.equals(signed.token)) {
                throw TasksApi.stopFor("session_changed", 0);
            }
        }

        @Override public void confirm(Object attempt) throws TasksApi.Stop { confirms++; requireCurrent(attempt); }

        @Override public void invalidate(Object attempt, String code, String reason) throws TasksApi.Stop {
            requireCurrent(attempt);
            invalidations++;
            invalidatedWith.add(code + "/" + reason);
            token = null;
        }
    }

    static final class Captured<T> implements TasksApi.Callback<T> {
        T value;
        TasksApi.Failure failure;
        int calls;
        @Override public void onSuccess(T result) { value = result; calls++; }
        @Override public void onFailure(TasksApi.Failure result) { failure = result; calls++; }
    }

    public static void main(String[] args) {
        success();
        staleAfterResponse();
        clockSkewRetry();
        sessionFailures();
        unlinked();
        taskErrors();
        networkAndBounds();
        singleFlight();
        queries();
        identityFences();
        realtime();
        wireOnEveryCall();
        batchCall();
        batchEnvelopeFailures();
        itemsClassifyLikeTheSingleRoute();
        r4Codes();
        batchItemSession();
        sessionReasons();
        serverWire();
        if (!failures.isEmpty()) {
            System.out.println("Tasks exchange: " + failures.size() + " of " + checks + " checks FAILED");
            for (String failure : failures) System.out.println("  FAIL " + failure);
            System.exit(1);
        }
        System.out.println("Tasks exchange (bounded state-machine proof): " + checks + " checks passed");
    }

    static TasksWire.Mutation mutation() {
        return TasksWire.Mutation.seal(PRINCIPAL, MID, TasksWire.Operation.taskComplete(TASK, "7", null, true));
    }

    static String receipt() {
        return "{\"schema_version\":1,\"receipt\":{\"mutation_id\":\"" + MID + "\",\"operation\":\"task.complete\",\"outcome\":\"unchanged\","
                + "\"resource_kind\":\"task\",\"resource_id\":\"" + TASK + "\",\"resource_revision\":\"7\",\"committed_at\":\"" + T + "\"},"
                + "\"server_time\":\"" + T + "\"}";
    }

    static String error(String code, String reason) {
        return "{\"error\":\"" + code + "\",\"message\":\"generic\",\"server_time\":\"" + T + "\"" + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
    }

    static ProTransport.TasksReply reply(int status, String body) { return new ProTransport.TasksReply(status, body, 0); }

    static Captured<TasksWire.MutationResponse> mutate(Slot slot, Object... replies) {
        for (Object reply : replies) slot.script.add(reply);
        Captured<TasksWire.MutationResponse> captured = new Captured<>();
        new TasksApi(slot).mutate(slot.identity(), mutation(), captured);
        return captured;
    }

    private static void success() {
        Slot slot = new Slot();
        Captured<TasksWire.MutationResponse> result = mutate(slot, reply(200, receipt()));
        check(result.calls == 1 && result.value != null && result.value.receipt.outcome == TasksWire.Outcome.UNCHANGED, "validated receipt delivered once");
        check(slot.sends == 1 && slot.confirms == 1 && slot.invalidations == 0, "one attempt, confirmed against the current credential");
        check(slot.serverTimes.size() == 1 && slot.serverTimes.get(0) == TasksWire.epochSeconds(T), "server time fed only from the validated response");

        Slot malformed = new Slot();
        Captured<TasksWire.MutationResponse> bad = mutate(malformed, reply(200, receipt().replace("{\"schema_version\":1,",
                "{\"schema_version\":1,\"schema_version\":1,")));
        check(bad.failure != null && bad.failure.kind == TasksApi.Failure.Kind.PROTOCOL && "invalid_response".equals(bad.failure.code),
                "duplicate-key 200 is a protocol failure, never success");
        check(malformed.serverTimes.size() == 1 && malformed.serverTimes.get(0) == 0, "no server time trusted from an invalid success body");

        Slot foreign = new Slot();
        Captured<TasksWire.MutationResponse> other = mutate(foreign, reply(200, receipt().replace(MID, "22222222-2222-4222-8222-222222222222")));
        check(other.failure != null && other.failure.kind == TasksApi.Failure.Kind.PROTOCOL, "receipt of another mutation rejected");

        Slot moved = new Slot();
        moved.principal = "01arz3ndektsv4rrffq69g5fbb";
        Captured<TasksWire.MutationResponse> mismatch = mutate(moved);
        check(mismatch.failure != null && mismatch.failure.kind == TasksApi.Failure.Kind.STALE && "principal_mismatch".equals(mismatch.failure.code)
                && moved.sends == 0, "mutation sealed for another principal is never sent (no retagging)");
    }

    private static void staleAfterResponse() {
        Slot slot = new Slot();
        slot.duringSend = () -> slot.generation++; // e.g. logout/relink while the request is on the wire
        Captured<TasksWire.MutationResponse> result = mutate(slot, reply(200, receipt()));
        check(result.value == null && result.failure != null && result.failure.kind == TasksApi.Failure.Kind.STALE,
                "generation change during the attempt: success withheld");

        Slot rotated = new Slot();
        rotated.duringSend = () -> rotated.token = "token-2";
        Captured<TasksWire.MutationResponse> late = mutate(rotated, reply(401, error("invalid_token", null)));
        check(late.failure != null && late.failure.kind == TasksApi.Failure.Kind.STALE && rotated.invalidations == 0 && "token-2".equals(rotated.token),
                "a 401 for a credential rotated meanwhile never clears the new credential");

        Slot queued = new Slot();
        queued.queueWork = true;
        TasksApi api = new TasksApi(queued);
        Captured<TasksWire.MutationResponse> waiting = new Captured<>();
        queued.script.add(reply(200, receipt()));
        api.mutate(queued.identity(), mutation(), waiting);
        queued.token = "token-rotated"; // rotation completes before the queued work runs
        queued.queued.remove(0).run();
        check(waiting.value != null && queued.signedWith.size() == 1 && "token-rotated".equals(queued.signedWith.get(0)),
                "restamp at actual send signs with the rotated credential, never the queue-time one");

        Slot switched = new Slot();
        switched.queueWork = true;
        TasksApi other = new TasksApi(switched);
        Captured<TasksWire.MutationResponse> dropped = new Captured<>();
        other.mutate(switched.identity(), mutation(), dropped);
        switched.telegramId = 43;
        switched.queued.remove(0).run();
        check(dropped.failure != null && dropped.failure.kind == TasksApi.Failure.Kind.STALE && switched.sends == 0,
                "account switch before send: nothing sent");
    }

    private static void clockSkewRetry() {
        Slot slot = new Slot();
        slot.duringSend = () -> { if (slot.sends == 1) slot.token = "token-2"; };
        Captured<TasksWire.MutationResponse> result = mutate(slot, reply(401, error("clock_skew", null)), reply(200, receipt()));
        check(result.value != null && slot.sends == 2 && slot.invalidations == 0, "one clock_skew correction, then success");
        check(slot.signedWith.size() == 2 && "token-1".equals(slot.signedWith.get(0)) && "token-2".equals(slot.signedWith.get(1)),
                "the retry re-reads the current credential and re-signs");
        check(slot.serverTimes.get(0) == TasksWire.epochSeconds(T), "the skew answer's validated server_time is applied before the retry");

        Slot twice = new Slot();
        Captured<TasksWire.MutationResponse> failed = mutate(twice, reply(401, error("clock_skew", null)), reply(401, error("clock_skew", null)),
                reply(200, receipt()));
        check(failed.failure != null && "clock_skew".equals(failed.failure.code) && failed.failure.kind == TasksApi.Failure.Kind.LOCAL
                && twice.sends == 2 && twice.invalidations == 0 && twice.token != null, "at most one correction; clock skew never clears the session");

        Slot noTime = new Slot();
        Captured<TasksWire.MutationResponse> untimed = mutate(noTime, reply(401, "{\"error\":\"clock_skew\"}"));
        check(untimed.failure != null && "clock_skew".equals(untimed.failure.code) && noTime.sends == 1, "no retry without a valid server_time");
    }

    private static void sessionFailures() {
        for (String code : new String[]{"invalid_token", "device_revoked", "device_key_mismatch"}) {
            Slot slot = new Slot();
            Captured<TasksWire.MutationResponse> result = mutate(slot, reply(401, error(code, null)));
            check(result.failure != null && result.failure.kind == TasksApi.Failure.Kind.SESSION && code.equals(result.failure.code)
                    && slot.invalidations == 1 && slot.token == null, code + ": existing session handling for the exact credential");
        }
        Slot other = new Slot();
        Captured<TasksWire.MutationResponse> unknown = mutate(other, reply(401, "{\"error\":\"unauthenticated\",\"message\":\"x\"}"));
        check(unknown.failure != null && "session_invalid".equals(unknown.failure.code) && other.invalidations == 1,
                "other 401 codes keep existing semantics, code allowlisted");
        Slot unreadable = new Slot();
        Captured<TasksWire.MutationResponse> html = mutate(unreadable, reply(401, "<html>401</html>"));
        check(html.failure != null && html.failure.kind == TasksApi.Failure.Kind.PROTOCOL && unreadable.invalidations == 0 && unreadable.token != null,
                "unreadable 401 body clears nothing");
        Slot nonce = new Slot();
        Captured<TasksWire.MutationResponse> replayed = mutate(nonce, reply(401, error("nonce_replayed", null)));
        check(replayed.failure != null && replayed.failure.kind == TasksApi.Failure.Kind.OFFLINE && nonce.invalidations == 0 && nonce.sends == 1,
                "nonce_replayed: retryable, session kept, no automatic resend");
        Slot unlinkedLocal = new Slot();
        unlinkedLocal.token = null;
        Captured<TasksWire.MutationResponse> local = mutate(unlinkedLocal);
        check(local.failure != null && local.failure.kind == TasksApi.Failure.Kind.LOCAL && "link_required".equals(local.failure.code)
                && unlinkedLocal.sends == 0, "no linked session: refused locally, nothing sent");
    }

    private static void unlinked() {
        Slot slot = new Slot();
        Captured<TasksWire.MutationResponse> result = mutate(slot, reply(403, error("tasks_account_not_linked", null)));
        check(result.failure != null && result.failure.kind == TasksApi.Failure.Kind.UNLINKED && result.failure.httpStatus == 403,
                "403 tasks_account_not_linked is an account-level pause");
        check(slot.invalidations == 0 && "token-1".equals(slot.token) && slot.sends == 1 && slot.cooldowns.isEmpty(),
                "credentials preserved, no retry, no cooldown, no invalidation");
        check(slot.identity().isLinked(), "identity stays linked (binding untouched)");
        Slot loose = new Slot();
        Captured<TasksWire.MutationResponse> extra = mutate(loose, reply(403, "{\"error\":\"tasks_account_not_linked\",\"message\":\"m\",\"server_time\":\""
                + T + "\",\"tg_user_id\":\"1\"}"));
        check(extra.failure != null && extra.failure.kind == TasksApi.Failure.Kind.PROTOCOL && loose.invalidations == 0,
                "non-schema not_linked envelope is not trusted as UNLINKED, still clears nothing");
        Slot forbidden = new Slot();
        Captured<TasksWire.MutationResponse> generic = mutate(forbidden, reply(403, "{\"error\":\"forbidden\"}"));
        check(generic.failure != null && generic.failure.kind == TasksApi.Failure.Kind.PROTOCOL && "request_rejected".equals(generic.failure.code)
                && forbidden.invalidations == 0, "generic 403 is a rejected request, never a session or unlinked state");
    }

    private static void taskErrors() {
        Object[][] table = {
                {403, error("tasks_creation_not_permitted", null), TasksApi.Failure.Kind.TASK, "tasks_creation_not_permitted"},
                {404, error("tasks_not_found", null), TasksApi.Failure.Kind.TASK, "tasks_not_found"},
                {409, error("tasks_resync_required", null), TasksApi.Failure.Kind.TASK, "tasks_resync_required"},
                {409, error("tasks_mutation_in_flight", null), TasksApi.Failure.Kind.TASK, "tasks_mutation_in_flight"},
                {422, error("tasks_invalid_request", null), TasksApi.Failure.Kind.TASK, "tasks_invalid_request"},
                {422, error("tasks_mutation_mismatch", null), TasksApi.Failure.Kind.TASK, "tasks_mutation_mismatch"},
                {409, error("tasks_conflict", null), TasksApi.Failure.Kind.PROTOCOL, "invalid_response"},
                {404, error("tasks_not_found", "revision_changed"), TasksApi.Failure.Kind.PROTOCOL, "invalid_response"},
                {409, error("tasks_not_found", null), TasksApi.Failure.Kind.PROTOCOL, "invalid_response"},
                {503, error("tasks_unavailable", null), TasksApi.Failure.Kind.UNAVAILABLE, "tasks_unavailable"},
                {500, "<html>", TasksApi.Failure.Kind.UNAVAILABLE, "server_error"},
                {503, "{\"error\":\"server_error\",\"message\":\"x\"}", TasksApi.Failure.Kind.UNAVAILABLE, "server_error"},
                {413, "<html>too large</html>", TasksApi.Failure.Kind.PROTOCOL, "request_rejected"},
                {413, error("tasks_invalid_request", null), TasksApi.Failure.Kind.PROTOCOL, "request_rejected"},
                {415, "{\"error\":\"unsupported_media\"}", TasksApi.Failure.Kind.PROTOCOL, "request_rejected"},
                {503, null, TasksApi.Failure.Kind.UNAVAILABLE, "server_error"},
                {500, null, TasksApi.Failure.Kind.UNAVAILABLE, "server_error"},
                {413, null, TasksApi.Failure.Kind.PROTOCOL, "request_rejected"},
                {404, null, TasksApi.Failure.Kind.PROTOCOL, "request_rejected"},
                {403, null, TasksApi.Failure.Kind.PROTOCOL, "request_rejected"},
                {401, null, TasksApi.Failure.Kind.PROTOCOL, "invalid_response"},
                {201, receipt(), TasksApi.Failure.Kind.PROTOCOL, "invalid_response"},
        };
        for (Object[] row : table) {
            Slot slot = new Slot();
            Captured<TasksWire.MutationResponse> result = mutate(slot, reply((Integer) row[0], (String) row[1]));
            check(result.failure != null && result.failure.kind == row[2] && row[3].equals(result.failure.code) && slot.invalidations == 0
                    && slot.sends == 1 && result.value == null, row[0] + " " + row[3] + " classified strictly");
        }
        Slot conflict = new Slot();
        Captured<TasksWire.MutationResponse> reason = mutate(conflict, reply(409, error("tasks_conflict", "invitation_expired")));
        check(reason.failure != null && reason.failure.kind == TasksApi.Failure.Kind.TASK
                && reason.failure.reason == TasksWire.ConflictReason.INVITATION_EXPIRED, "tasks_conflict carries its allowlisted reason");
        TasksApi.Failure bogus = TasksApi.classify(409, TasksWire.decodeErrorEnvelope(error("tasks_conflict", "merged")), 0);
        check(bogus.kind == TasksApi.Failure.Kind.PROTOCOL && bogus.reason == null, "unknown conflict reason is a protocol failure");
    }

    private static void networkAndBounds() {
        Slot offline = new Slot();
        Captured<TasksWire.MutationResponse> io = mutate(offline, new java.io.IOException("reset"));
        check(io.failure != null && io.failure.kind == TasksApi.Failure.Kind.OFFLINE && offline.sends == 1 && offline.invalidations == 0,
                "network failure: OFFLINE (outcome unknown), no automatic resend");
        Slot timeout = new Slot();
        Captured<TasksWire.MutationResponse> slow = mutate(timeout, rejected(ProMonetization.Reason.TIMED_OUT));
        check(slow.failure != null && slow.failure.kind == TasksApi.Failure.Kind.OFFLINE, "deadline: OFFLINE");
        Slot oversized = new Slot();
        Captured<TasksWire.MutationResponse> big = mutate(oversized, rejected(ProMonetization.Reason.MALFORMED));
        check(big.failure != null && big.failure.kind == TasksApi.Failure.Kind.PROTOCOL, "oversized/invalid UTF-8 body: PROTOCOL");
        Slot limited = new Slot();
        Captured<TasksWire.MutationResponse> throttled = mutate(limited, new ProTransport.TasksReply(429, null, 99999));
        check(throttled.failure != null && throttled.failure.kind == TasksApi.Failure.Kind.RATE_LIMITED
                && throttled.failure.retryAfterSeconds == TasksApi.MAX_RETRY_AFTER_SECONDS && limited.cooldowns.size() == 1
                && limited.cooldowns.get(0) == TasksApi.MAX_RETRY_AFTER_SECONDS && limited.sends == 1, "429: bounded cooldown recorded, no retry");
        Slot zero = new Slot();
        Captured<TasksWire.MutationResponse> immediate = mutate(zero, new ProTransport.TasksReply(429, null, 0));
        check(immediate.failure != null && immediate.failure.retryAfterSeconds == 1 && zero.cooldowns.get(0) == 1, "429 without Retry-After waits at least 1 s");
        Slot cooled = new Slot() {
            @Override public Object prepare(Object ticket, TasksApi.Request<?> request) throws TasksApi.Stop {
                throw TasksApi.stopFor("rate_limited", 12);
            }
        };
        Captured<TasksWire.MutationResponse> local = mutate(cooled);
        check(local.failure != null && local.failure.kind == TasksApi.Failure.Kind.RATE_LIMITED && local.failure.retryAfterSeconds == 12
                && cooled.sends == 0, "running local cooldown: refused before any send");
        check(TasksApi.stopFor("rotation_pending", 0).failure.kind == TasksApi.Failure.Kind.LOCAL, "pending rotation refuses locally");
        check(TasksApi.stopFor("something_else", 0).failure.kind == TasksApi.Failure.Kind.STALE, "unknown Pro refusal maps to STALE");
    }

    private static Exception rejected(ProMonetization.Reason reason) {
        try {
            ProMonetization.readBounded(new java.io.ByteArrayInputStream(reason == ProMonetization.Reason.MALFORMED ? new byte[10] : new byte[0]),
                    reason == ProMonetization.Reason.MALFORMED ? 1 : 10, reason == ProMonetization.Reason.TIMED_OUT ? 0 : Long.MAX_VALUE, () -> 1L);
        } catch (Exception e) {
            return e;
        }
        throw new IllegalStateException("expected a rejection");
    }

    static final String P2 = "01arz3ndektsv4rrffq69g5fbb";
    static String emptySnapshot() {
        return "{\"schema_version\":1,\"items\":[],\"next_cursor\":null,\"changes_cursor\":\"c\",\"server_time\":\"" + T + "\"}";
    }

    /** Relink to another principal in the same Telegram account slot (ZenlyPro bumps the generation). */
    static void relink(Slot slot) { slot.principal = P2; slot.token = "token-p2"; slot.generation++; }

    /** Identity fences at call/capture, send, completion and deferred UI delivery. */
    private static void identityFences() {
        // Deferred UI queue: the result was confirmed on the worker, then relink happens before the callback runs.
        Slot relinked = new Slot();
        relinked.queueDelivery = true;
        Captured<TasksWire.MutationResponse> late = mutate(relinked, reply(200, receipt()));
        check(late.calls == 0 && relinked.deliveries.size() == 1 && relinked.sends == 1, "result waits in the UI queue");
        relink(relinked);
        relinked.deliveries.remove(0).run();
        check(late.value == null && late.failure != null && late.failure.kind == TasksApi.Failure.Kind.STALE && late.calls == 1,
                "relink before UI delivery: receipt withheld, STALE (outcome unknown, same sealed id)");

        Slot loggedOut = new Slot();
        loggedOut.queueDelivery = true;
        Captured<TasksWire.SnapshotResponse> page = new Captured<>();
        loggedOut.script.add(reply(200, emptySnapshot()));
        new TasksApi(loggedOut).snapshot(loggedOut.identity(), null, 50, page);
        loggedOut.telegramId = 0;
        loggedOut.token = null;
        loggedOut.generation++;
        loggedOut.deliveries.remove(0).run();
        check(page.value == null && page.failure != null && page.failure.kind == TasksApi.Failure.Kind.STALE,
                "logout before UI delivery: snapshot page never delivered");

        Slot rotatedLater = new Slot();
        rotatedLater.queueDelivery = true;
        Captured<TasksWire.SnapshotResponse> kept = new Captured<>();
        rotatedLater.script.add(reply(200, emptySnapshot()));
        new TasksApi(rotatedLater).snapshot(rotatedLater.identity(), null, 50, kept);
        rotatedLater.token = "token-rotated-after"; // credential rotation only: identity carries no credential
        rotatedLater.deliveries.remove(0).run();
        check(kept.value != null, "credential rotation after completion keeps the same identity: page delivered");

        // Identity read for P1, relink to P2, then the call: refused before capture/send; the slot is not held.
        Slot early = new Slot();
        TasksApi.Identity p1 = early.identity();
        relink(early);
        TasksApi api = new TasksApi(early);
        Captured<TasksWire.SnapshotResponse> wrong = new Captured<>();
        api.snapshot(p1, null, 50, wrong);
        check(wrong.failure != null && wrong.failure.kind == TasksApi.Failure.Kind.STALE && early.sends == 0 && early.captures == 0,
                "P1 identity after relink to P2: STALE, nothing captured or sent");
        Captured<TasksWire.ChangesResponse> p1Changes = new Captured<>();
        api.changes(p1, "c", 50, p1Changes);
        check(p1Changes.failure != null && p1Changes.failure.kind == TasksApi.Failure.Kind.STALE && early.sends == 0, "changes fenced the same way");
        early.script.add(reply(200, emptySnapshot()));
        Captured<TasksWire.SnapshotResponse> right = new Captured<>();
        api.snapshot(early.identity(), null, 50, right);
        check(right.value != null && early.sends == 1 && early.signedWith.get(0).equals("token-p2"),
                "the refused call did not hold the in-flight slot; the current identity proceeds");
        Captured<TasksWire.MutationResponse> oldMutation = new Captured<>();
        api.mutate(early.identity(), mutation(), oldMutation);
        check(oldMutation.failure != null && "principal_mismatch".equals(oldMutation.failure.code) && early.sends == 1,
                "P1-sealed mutation under P2 identity: never sent");

        // Captured for P1, relinked before the worker sends: refused at send.
        Slot queued = new Slot();
        queued.queueWork = true;
        Captured<TasksWire.SnapshotResponse> stale = new Captured<>();
        new TasksApi(queued).snapshot(queued.identity(), null, 50, stale);
        relink(queued);
        queued.queued.remove(0).run();
        check(stale.failure != null && stale.failure.kind == TasksApi.Failure.Kind.STALE && queued.sends == 0, "relink between capture and send: nothing sent");

        // Principal moved without a generation change (defensive): prepare refuses before signing.
        Slot moved = new Slot();
        moved.queueWork = true;
        Captured<TasksWire.ChangesResponse> movedChanges = new Captured<>();
        new TasksApi(moved).changes(moved.identity(), "c", 50, movedChanges);
        moved.principal = P2;
        moved.queued.remove(0).run();
        check(movedChanges.failure != null && movedChanges.failure.kind == TasksApi.Failure.Kind.STALE && moved.sends == 0
                && moved.signedWith.isEmpty(), "principal change before send: never signed or sent");

        // Explicit identity is required.
        Slot plain = new Slot();
        Captured<TasksWire.SnapshotResponse> none = new Captured<>();
        new TasksApi(plain).snapshot(null, null, 50, none);
        check(none.failure != null && "invalid_request".equals(none.failure.code) && plain.sends == 0, "null identity refused");
        plain.token = null;
        Captured<TasksWire.SnapshotResponse> unlinked = new Captured<>();
        new TasksApi(plain).snapshot(plain.identity(), null, 50, unlinked);
        check(unlinked.failure != null && "link_required".equals(unlinked.failure.code) && plain.sends == 0, "unlinked identity refused locally");

        // The page read bound follows the requested limit.
        Slot bounded = new Slot();
        bounded.script.add(reply(200, emptySnapshot()));
        new TasksApi(bounded).snapshot(bounded.identity(), null, 7, new Captured<TasksWire.SnapshotResponse>());
        check(bounded.lastMaxBytes == TasksWire.maxPageBytes(7), "snapshot read bound is maxPageBytes(limit)");
    }

    private static void singleFlight() {
        Slot slot = new Slot();
        slot.queueWork = true;
        final TasksApi api = new TasksApi(slot);
        final Captured<TasksWire.SnapshotResponse> first = new Captured<>();
        final Captured<TasksWire.ChangesResponse> next = new Captured<>();
        Captured<TasksWire.SnapshotResponse> second = new Captured<>();
        slot.script.add(reply(200, "{\"schema_version\":1,\"items\":[],\"next_cursor\":null,\"changes_cursor\":\"c\",\"server_time\":\"" + T + "\"}"));
        slot.script.add(reply(200, "{\"schema_version\":1,\"changes\":[],\"next_cursor\":\"h\",\"has_more\":false,\"server_time\":\"" + T + "\"}"));
        final TasksApi.Identity id = slot.identity();
        api.snapshot(id, null, 50, new TasksApi.Callback<TasksWire.SnapshotResponse>() {
            @Override public void onSuccess(TasksWire.SnapshotResponse value) {
                first.onSuccess(value);
                api.changes(id, value.changesCursor, 50, next); // Paging from inside the callback.
            }
            @Override public void onFailure(TasksApi.Failure failure) { first.onFailure(failure); }
        });
        api.snapshot(id, null, 50, second);
        check(second.failure != null && second.failure.kind == TasksApi.Failure.Kind.BUSY && slot.queued.size() == 1 && slot.sends == 0,
                "a second request while one is in flight is refused immediately, nothing queued");
        slot.queued.remove(0).run();
        check(first.value != null && next.calls == 0 && slot.queued.size() == 1,
                "slot released before the callback: the next page queued from it (no synchronous wait)");
        slot.queued.remove(0).run();
        check(next.value != null && "h".equals(next.value.nextCursor) && !next.value.hasMore && slot.sends == 2, "next page delivered");
    }

    static final String KEY = "local-app_Key9";
    static final String CHANNEL = "private-tasks.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    static final String SIG = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    static String metadata(String wsUrl) {
        return "{\"schema_version\":1,\"enabled\":true,\"ws_url\":\"" + wsUrl + "\",\"activity_timeout\":30,\"server_time\":\"" + T + "\"}";
    }

    /** The two realtime calls: exact routes/bodies, ws_url checked against the attempt's API base, own in-flight lane. */
    private static void realtime() {
        Slot slot = new Slot();
        TasksApi api = new TasksApi(slot);
        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Metadata> meta = new Captured<>();
        slot.script.add(reply(200, metadata("wss://api.example.org/realtime/app/" + KEY)));
        api.realtime(slot.identity(), meta);
        check(meta.value != null && meta.value.isEnabled() && meta.value.activityTimeout == 30
                && "api.example.org".equals(meta.value.endpoint.host) && meta.value.endpoint.port == 443
                && "https://api.example.org".equals(meta.value.endpoint.origin)
                && ("/realtime/app/" + KEY + "?protocol=7&client=zenlygram-android&version=1.0&flash=false").equals(meta.value.endpoint.pathAndQuery),
                "realtime metadata: endpoint rebuilt from the API origin + validated key, fixed Android query");
        check("GET tasks/realtime".equals(slot.routes.get(0)) && slot.bodies.get(0) == null && slot.signedWith.size() == 1,
                "realtime metadata is one signed GET /v1/tasks/realtime, no query or body");

        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Metadata> foreign = new Captured<>();
        slot.script.add(reply(200, metadata("wss://evil.example.org/realtime/app/" + KEY)));
        api.realtime(slot.identity(), foreign);
        check(foreign.failure != null && foreign.failure.kind == TasksApi.Failure.Kind.PROTOCOL,
                "a ws_url on another authority than the configured API base is refused (PROTOCOL), never opened");

        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Metadata> moved = new Captured<>();
        slot.apiBaseUrl = "https://api.example.org:8443/v1";
        slot.script.add(reply(200, metadata("wss://api.example.org/realtime/app/" + KEY)));
        api.realtime(slot.identity(), moved);
        check(moved.failure != null && moved.failure.kind == TasksApi.Failure.Kind.PROTOCOL,
                "the port is part of the authority: a ws_url without the API base's port is refused");
        slot.apiBaseUrl = "https://api.example.org/v1";

        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Metadata> unavailable = new Captured<>();
        slot.script.add(new ProTransport.TasksReply(503, "{\"error\":\"tasks_unavailable\",\"message\":\"x\",\"server_time\":\"" + T + "\"}", 7));
        api.realtime(slot.identity(), unavailable);
        check(unavailable.failure != null && unavailable.failure.kind == TasksApi.Failure.Kind.UNAVAILABLE
                && unavailable.failure.retryAfterSeconds == 7, "realtime 503 is UNAVAILABLE with its Retry-After (backoff, not disabled)");

        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Subscription> auth = new Captured<>();
        slot.script.add(reply(200, "{\"schema_version\":1,\"channel\":\"" + CHANNEL + "\",\"auth\":\"" + KEY + ":" + SIG + "\",\"server_time\":\"" + T + "\"}"));
        int before = slot.sends;
        api.realtimeAuth(slot.identity(), "123.456", auth);
        check(auth.value != null && slot.sends == before + 1 && "POST tasks/realtime/auth".equals(slot.routes.get(slot.routes.size() - 1))
                && "{\"socket_id\":\"123.456\"}".equals(new String(slot.bodies.get(slot.bodies.size() - 1), java.nio.charset.StandardCharsets.UTF_8)),
                "realtime auth: one signed POST with exactly {\"socket_id\"}");

        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Subscription> badId = new Captured<>();
        before = slot.sends;
        api.realtimeAuth(slot.identity(), "123.456.7", badId);
        check(badId.failure != null && badId.failure.kind == TasksApi.Failure.Kind.LOCAL && slot.sends == before,
                "a malformed socket id is refused locally, nothing sent");

        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Subscription> upper = new Captured<>();
        slot.script.add(reply(200, "{\"schema_version\":1,\"channel\":\"" + CHANNEL + "\",\"auth\":\"" + KEY + ":" + SIG.toUpperCase() + "\",\"server_time\":\"" + T + "\"}"));
        api.realtimeAuth(slot.identity(), "1.2", upper);
        check(upper.failure != null && upper.failure.kind == TasksApi.Failure.Kind.PROTOCOL, "an upper-case signature is a protocol failure");

        // Lanes: a realtime call in flight never makes the sync engine see BUSY, and vice versa.
        Slot lanes = new Slot();
        lanes.queueWork = true;
        TasksApi laned = new TasksApi(lanes);
        Captured<TasksWire.SnapshotResponse> page = new Captured<>();
        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Metadata> rt = new Captured<>();
        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Metadata> rt2 = new Captured<>();
        laned.snapshot(lanes.identity(), null, 50, page);
        laned.realtime(lanes.identity(), rt);
        laned.realtime(lanes.identity(), rt2);
        check(page.calls == 0 && rt.calls == 0 && lanes.queued.size() == 2 && rt2.failure != null && rt2.failure.kind == TasksApi.Failure.Kind.BUSY,
                "sync and realtime lanes are independent; a second realtime call is BUSY");
    }

    // ---- R4 (tasks-sync.json revision 4) -------------------------------------------------------

    static final String BATCH = "99999999-9999-4999-9999-999999999999";
    static final String M2 = "44444444-4444-4444-8444-444444444444";
    static final String LIST = "22222222-2222-4222-8222-222222222222";

    static TasksWire.Mutation listCreate() {
        return TasksWire.Mutation.seal(PRINCIPAL, M2, TasksWire.Operation.listCreate(LIST, "Ishlar", 0, null));
    }

    /** [task.complete (user), list.create (migration)]. */
    static TasksWire.Batch batch() {
        return TasksWire.Batch.of(BATCH, Arrays.asList(new TasksWire.Batch.Item(mutation(), TasksWire.Origin.USER),
                new TasksWire.Batch.Item(listCreate(), TasksWire.Origin.MIGRATION)));
    }

    static String listReceipt() {
        return "{\"mutation_id\":\"" + M2 + "\",\"operation\":\"list.create\",\"outcome\":\"committed\",\"resource_kind\":\"list\","
                + "\"resource_id\":\"" + LIST + "\",\"resource_revision\":\"1\",\"committed_at\":\"" + T + "\"}";
    }

    static String completeReceipt() {
        return "{\"mutation_id\":\"" + MID + "\",\"operation\":\"task.complete\",\"outcome\":\"unchanged\",\"resource_kind\":\"task\","
                + "\"resource_id\":\"" + TASK + "\",\"resource_revision\":\"7\",\"committed_at\":\"" + T + "\"}";
    }

    static String answer(String retryAfter, String first, String second) {
        return "{\"schema_version\":1,\"batch_id\":\"" + BATCH + "\",\"results\":[" + first + "," + second + "],\"retry_after\":" + retryAfter
                + ",\"server_time\":\"" + T + "\"}";
    }

    static String ok(String mutationId, String receipt) { return "{\"mutation_id\":\"" + mutationId + "\",\"status\":200,\"receipt\":" + receipt + "}"; }

    static String failed(String mutationId, int status, String error) {
        return "{\"mutation_id\":\"" + mutationId + "\",\"status\":" + status + ",\"error\":" + error + "}";
    }

    static String skipped(String mutationId) { return "{\"mutation_id\":\"" + mutationId + "\",\"status\":null,\"skipped\":true}"; }

    static String committed() { return answer("null", ok(MID, completeReceipt()), ok(M2, listReceipt())); }

    static ProTransport.TasksReply echoed(int status, String body) { return new ProTransport.TasksReply(status, body, 0, 4); }

    static Captured<TasksWire.BatchResponse> mutateBatch(Slot slot, Object... replies) {
        for (Object reply : replies) slot.script.add(reply);
        Captured<TasksWire.BatchResponse> captured = new Captured<>();
        new TasksApi(slot).mutateBatch(slot.identity(), batch(), captured);
        return captured;
    }

    static String rtSubscription() {
        return "{\"schema_version\":1,\"channel\":\"" + CHANNEL + "\",\"auth\":\"" + KEY + ":" + SIG + "\",\"server_time\":\"" + T + "\"}";
    }

    /** x-wire-negotiation: every one of the six calls is a wire-4 request, and the header goes on exactly once. */
    private static void wireOnEveryCall() {
        Slot slot = new Slot();
        TasksApi api = new TasksApi(slot);
        slot.script.add(reply(200, receipt()));
        api.mutate(slot.identity(), mutation(), new Captured<TasksWire.MutationResponse>());
        slot.script.add(reply(200, emptySnapshot()));
        api.snapshot(slot.identity(), null, 50, new Captured<TasksWire.SnapshotResponse>());
        slot.script.add(reply(200, "{\"schema_version\":1,\"changes\":[],\"next_cursor\":\"h\",\"has_more\":false,\"server_time\":\"" + T + "\"}"));
        api.changes(slot.identity(), "c", 50, new Captured<TasksWire.ChangesResponse>());
        slot.script.add(reply(200, metadata("wss://api.example.org/realtime/app/" + KEY)));
        api.realtime(slot.identity(), new Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Metadata>());
        slot.script.add(reply(200, rtSubscription()));
        api.realtimeAuth(slot.identity(), "1.2", new Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Subscription>());
        slot.script.add(reply(200, committed()));
        api.mutateBatch(slot.identity(), batch(), new Captured<TasksWire.BatchResponse>());
        check(slot.sends == 6 && slot.wires.equals(Arrays.asList(4, 4, 4, 4, 4, 4)), "all six calls are X-Tasks-Wire: 4 requests");
        boolean once = slot.headers.size() == 6;
        for (Map<String, String> sent : slot.headers) {
            int wire = 0;
            for (String name : sent.keySet()) if (name.equalsIgnoreCase("X-Tasks-Wire")) wire++;
            once &= wire == 1 && "4".equals(sent.get("X-Tasks-Wire"));
        }
        check(once, "the signing step adds X-Tasks-Wire: 4 exactly once to every attempt");
        check(slot.timeouts.equals(Arrays.asList(5000, 5000, 5000, 5000, 5000, 20000)) && ProTransport.TASKS_TIMEOUT_MS == 5000
                && ProTransport.TASKS_BATCH_TIMEOUT_MS == 20000, "the batch call alone has the 20 s budget; the R3 routes keep 5 s");
        check(slot.idempotencyKeys.equals(Arrays.asList(MID, null, null, null, null, BATCH)), "Idempotency-Key: the mutation id, the batch id, nothing else");

        Slot skew = new Slot();
        Captured<TasksWire.BatchResponse> retried = mutateBatch(skew, reply(401, error("clock_skew", null)), reply(200, committed()));
        check(retried.value != null && skew.sends == 2 && skew.wires.equals(Arrays.asList(4, 4)) && skew.headers.size() == 2
                && "4".equals(skew.headers.get(1).get("X-Tasks-Wire")), "the clock_skew re-sign carries the header again");
    }

    private static void batchCall() {
        Slot slot = new Slot();
        TasksWire.Batch sent = batch();
        Captured<TasksWire.BatchResponse> result = mutateBatch(slot, echoed(200, committed()));
        check(result.calls == 1 && result.value != null && result.value.results.size() == 2
                && result.value.results.get(0).receipt.operation == TasksWire.OperationKind.TASK_COMPLETE
                && result.value.results.get(1).receipt.operation == TasksWire.OperationKind.LIST_CREATE, "both receipts delivered, request order");
        check("POST tasks/mutations/batch".equals(slot.routes.get(0)) && Arrays.equals(slot.bodies.get(0), sent.body())
                && BATCH.equals(slot.idempotencyKeys.get(0)) && slot.lastMaxBytes == 262144 && slot.timeouts.get(0) == 20000,
                "one signed POST /v1/tasks/mutations/batch: exact batch body, Idempotency-Key = batch id, 262144-byte answer bound");
        check(slot.confirms == 1 && slot.invalidations == 0 && slot.serverTimes.get(0) == TasksWire.epochSeconds(T) && slot.cooldowns.isEmpty(),
                "confirmed against the current credential, server time noted, no cooldown");
        check(result.value.serverWire == 4, "the X-Tasks-Wire echo is surfaced on the batch result");
        Slot bare = new Slot();
        check(mutateBatch(bare, reply(200, committed())).value.serverWire == null, "no echo: serverWire null (the downgrade signal)");

        Slot paused = new Slot();
        String pausedItem = failed(M2, 503, "{\"error\":\"tasks_unavailable\",\"message\":\"m\",\"reason\":\"migration_paused\",\"retry_after\":3600}");
        Captured<TasksWire.BatchResponse> pause = mutateBatch(paused, reply(200, answer("3600", ok(MID, completeReceipt()), pausedItem)));
        check(pause.value != null && pause.value.retryAfter == 3600 && paused.cooldowns.isEmpty(),
                "a batch's own retry_after never cools the batch route down (user items keep working)");

        Slot moved = new Slot();
        moved.principal = P2;
        moved.token = "token-p2";
        Captured<TasksWire.BatchResponse> mismatch = new Captured<>();
        new TasksApi(moved).mutateBatch(moved.identity(), batch(), mismatch);
        check(mismatch.failure != null && "principal_mismatch".equals(mismatch.failure.code) && mismatch.failure.kind == TasksApi.Failure.Kind.STALE
                && moved.sends == 0, "a batch sealed for another principal is never sent");
        Slot none = new Slot();
        Captured<TasksWire.BatchResponse> missing = new Captured<>();
        new TasksApi(none).mutateBatch(none.identity(), null, missing);
        check(missing.failure != null && "invalid_request".equals(missing.failure.code) && none.sends == 0, "no batch: refused locally");

        Slot lanes = new Slot();
        lanes.queueWork = true;
        TasksApi api = new TasksApi(lanes);
        Captured<TasksWire.MutationResponse> single = new Captured<>();
        Captured<TasksWire.BatchResponse> busy = new Captured<>();
        Captured<org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire.Metadata> rt = new Captured<>();
        api.mutate(lanes.identity(), mutation(), single);
        api.mutateBatch(lanes.identity(), batch(), busy);
        api.realtime(lanes.identity(), rt);
        check(busy.failure != null && busy.failure.kind == TasksApi.Failure.Kind.BUSY && lanes.queued.size() == 2 && rt.calls == 0,
                "the batch shares the sync lane (BUSY behind a mutation); realtime keeps its own lane");
    }

    private static void batchEnvelopeFailures() {
        Object[][] table = {
                // The envelope 413 is tasks_invalid_request (contract TR4-C0): status first, a refusal answered by sending less.
                {413, error("tasks_invalid_request", null), TasksApi.Failure.Kind.PROTOCOL, "request_rejected"},
                {404, "{\"message\":\"Not Found\"}", TasksApi.Failure.Kind.PROTOCOL, "request_rejected"},
                {503, error("tasks_unavailable", "batch_disabled"), TasksApi.Failure.Kind.UNAVAILABLE, "batch_disabled"},
                {503, error("tasks_unavailable", null), TasksApi.Failure.Kind.UNAVAILABLE, "tasks_unavailable"},
                {422, error("tasks_invalid_request", null), TasksApi.Failure.Kind.TASK, "tasks_invalid_request"},
                {403, error("tasks_account_not_linked", null), TasksApi.Failure.Kind.UNLINKED, "tasks_account_not_linked"},
                {415, error("tasks_invalid_request", null), TasksApi.Failure.Kind.PROTOCOL, "invalid_response"},
                {200, "{\"schema_version\":1}", TasksApi.Failure.Kind.PROTOCOL, "invalid_response"},
                {200, committed().replace(BATCH, "88888888-8888-4888-8888-888888888888"), TasksApi.Failure.Kind.PROTOCOL, "invalid_response"},
        };
        for (Object[] row : table) {
            Slot slot = new Slot();
            Captured<TasksWire.BatchResponse> result = mutateBatch(slot, reply((Integer) row[0], (String) row[1]));
            check(result.failure != null && result.failure.kind == row[2] && row[3].equals(result.failure.code)
                    && (row[0].equals(200) || result.failure.httpStatus == (Integer) row[0]) && slot.invalidations == 0 && slot.sends == 1
                    && slot.cooldowns.isEmpty(), "batch " + row[0] + " " + row[3]);
        }
        Slot throttled = new Slot();
        Captured<TasksWire.BatchResponse> limited = mutateBatch(throttled, new ProTransport.TasksReply(429, null, 30));
        check(limited.failure != null && limited.failure.kind == TasksApi.Failure.Kind.RATE_LIMITED && throttled.cooldowns.equals(Collections.singletonList(30)),
                "an envelope 429 (pre-auth throttle) cools the batch route down");
        Slot slow = new Slot();
        Captured<TasksWire.BatchResponse> timeout = mutateBatch(slow, rejected(ProMonetization.Reason.TIMED_OUT));
        check(timeout.failure != null && timeout.failure.kind == TasksApi.Failure.Kind.OFFLINE, "a batch timeout is OFFLINE: every item's outcome unknown");
    }

    /** The adapter TasksCloudRepository applies before TasksCloudPolicy. */
    static TasksCloudRemote.Failure adapt(TasksApi.Failure failure) {
        return new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.valueOf(failure.kind.name()), failure.code, failure.reason,
                failure.httpStatus, failure.retryAfterSeconds);
    }

    /** Every item status and code: the item's Failure is the single route's for the same answer, and so is the policy action. */
    private static void itemsClassifyLikeTheSingleRoute() {
        String consent = ",\"reason\":\"withdrawn\",\"effective_at\":\"2026-10-30T08:00:00Z\"";
        Object[][] table = {
                // status, code, extras, retry_after (0 = none), expected action
                {401, "invalid_token", "", 0, TasksCloudPolicy.Action.PAUSE_SESSION},
                {401, "device_revoked", "", 0, TasksCloudPolicy.Action.PAUSE_SESSION},
                {403, "tasks_account_not_linked", "", 0, TasksCloudPolicy.Action.PAUSE_UNLINKED},
                {403, "tasks_creation_not_permitted", "", 0, TasksCloudPolicy.Action.BLOCK_CREATION},
                {403, "tasks_consent_required", consent, 0, TasksCloudPolicy.Action.BACKOFF},
                {404, "tasks_not_found", "", 0, TasksCloudPolicy.Action.NOT_FOUND},
                {409, "tasks_conflict", ",\"reason\":\"revision_changed\"", 0, TasksCloudPolicy.Action.CONFLICT},
                {409, "tasks_conflict", ",\"reason\":\"field_changed\",\"fields\":[\"title\",\"sort_order\"]", 0, TasksCloudPolicy.Action.CONFLICT},
                {409, "tasks_conflict", ",\"reason\":\"members_changed\"", 0, TasksCloudPolicy.Action.CONFLICT},
                {409, "tasks_conflict", ",\"reason\":\"invitation_expired\"", 0, TasksCloudPolicy.Action.CONFLICT},
                {409, "tasks_resync_required", "", 0, TasksCloudPolicy.Action.RESYNC},
                {409, "tasks_mutation_in_flight", "", 0, TasksCloudPolicy.Action.BACKOFF},
                {422, "tasks_invalid_request", "", 0, TasksCloudPolicy.Action.INVALID},
                {422, "tasks_mutation_mismatch", "", 0, TasksCloudPolicy.Action.QUARANTINE},
                {429, "tasks_rate_limited", "", 7, TasksCloudPolicy.Action.RETRY_AFTER},
                {503, "tasks_unavailable", "", 1, TasksCloudPolicy.Action.BACKOFF},
                {503, "tasks_unavailable", ",\"reason\":\"migration_paused\"", 3600, TasksCloudPolicy.Action.BACKOFF},
        };
        for (Object[] row : table) {
            int status = (Integer) row[0], retry = (Integer) row[3];
            String code = (String) row[1], extras = (String) row[2];
            TasksApi.Failure single = TasksApi.classify(status, TasksWire.decodeErrorEnvelope("{\"error\":\"" + code + "\",\"message\":\"m\","
                    + "\"server_time\":\"" + T + "\"" + extras + "}"), retry);
            String item = failed(M2, status, "{\"error\":\"" + code + "\",\"message\":\"m\"" + extras + (retry > 0 ? ",\"retry_after\":" + retry : "") + "}");
            TasksWire.BatchResponse response = TasksWire.decodeBatchResponse(answer(retry > 0 ? String.valueOf(retry) : "null",
                    ok(MID, completeReceipt()), item), batch(), null);
            TasksWire.BatchItemResult result = response.results.get(1);
            TasksApi.Failure fromItem = TasksApi.classifyItem(result.status, result.error);
            String name = status + " " + code + extras;
            check(result.kind == TasksWire.BatchItemResult.Kind.ERROR && fromItem.kind == single.kind && fromItem.code.equals(single.code)
                    && fromItem.reason == single.reason && fromItem.httpStatus == single.httpStatus
                    && fromItem.retryAfterSeconds == single.retryAfterSeconds && fromItem.fields.equals(single.fields)
                    && (fromItem.effectiveAt == null ? single.effectiveAt == null : fromItem.effectiveAt.equals(single.effectiveAt)),
                    "item " + name + ": the single route's Failure");
            check(TasksCloudPolicy.classify(adapt(fromItem)) == row[4] && TasksCloudPolicy.classify(adapt(single)) == row[4],
                    "item " + name + ": the single route's policy action " + row[4]);
        }
        TasksWire.BatchResponse limited = TasksWire.decodeBatchResponse(answer("9", ok(MID, completeReceipt()),
                failed(M2, 429, "{\"error\":\"tasks_rate_limited\",\"message\":\"m\"}")), batch(), null);
        check(TasksApi.classifyItem(429, limited.results.get(1).error).retryAfterSeconds == 9, "a 429 item without retry_after waits the batch's");
        check(TasksApi.classifyItem(409, null).kind == TasksApi.Failure.Kind.PROTOCOL, "no item error: protocol");
    }

    /** batch_disabled, migration_paused and tasks_consent_required are recognisable at envelope and item level. */
    private static void r4Codes() {
        Slot disabled = new Slot();
        Captured<TasksWire.BatchResponse> off = mutateBatch(disabled, reply(503, error("tasks_unavailable", "batch_disabled")));
        check(off.failure != null && TasksApi.CODE_BATCH_DISABLED.equals(off.failure.code) && off.failure.kind == TasksApi.Failure.Kind.UNAVAILABLE
                && off.failure.httpStatus == 503 && disabled.cooldowns.isEmpty(), "503 batch_disabled: UNAVAILABLE batch_disabled (never ran)");
        Slot loose = new Slot();
        Captured<TasksWire.BatchResponse> unknown = mutateBatch(loose, reply(503, "{\"error\":\"tasks_unavailable\",\"reason\":\"batch_disabled\"}"));
        check(unknown.failure != null && "server_error".equals(unknown.failure.code), "a non-schema 503 is never read as batch_disabled");

        TasksWire.BatchResponse paused = TasksWire.decodeBatchResponse(answer("3600", ok(MID, completeReceipt()),
                failed(M2, 503, "{\"error\":\"tasks_unavailable\",\"message\":\"m\",\"reason\":\"migration_paused\",\"retry_after\":3600}")), batch(), null);
        TasksApi.Failure pause = TasksApi.classifyItem(503, paused.results.get(1).error);
        check(TasksApi.CODE_MIGRATION_PAUSED.equals(pause.code) && pause.kind == TasksApi.Failure.Kind.UNAVAILABLE && pause.retryAfterSeconds == 3600,
                "item 503 migration_paused: UNAVAILABLE migration_paused with retry_after 3600");

        String fence = "{\"error\":\"tasks_consent_required\",\"message\":\"m\",\"server_time\":\"" + T + "\",\"reason\":\"withdrawn\","
                + "\"effective_at\":\"2026-10-30T08:00:00Z\"}";
        Slot snapshot = new Slot();
        snapshot.script.add(reply(403, fence));
        Captured<TasksWire.SnapshotResponse> page = new Captured<>();
        new TasksApi(snapshot).snapshot(snapshot.identity(), null, 50, page);
        check(page.failure != null && TasksApi.CODE_CONSENT_REQUIRED.equals(page.failure.code) && page.failure.kind == TasksApi.Failure.Kind.TASK
                && "2026-10-30T08:00:00Z".equals(page.failure.effectiveAt) && page.failure.httpStatus == 403,
                "403 tasks_consent_required on a read: recognisable with effective_at");
        check(snapshot.invalidations == 0 && snapshot.identity().isLinked() && snapshot.cooldowns.isEmpty(),
                "the consent fence never touches the session, binding or cooldowns");
        Slot single = new Slot();
        Captured<TasksWire.MutationResponse> mutation = mutate(single, reply(403, fence));
        TasksCloudPolicy.Action action = TasksCloudPolicy.classify(adapt(mutation.failure));
        check(TasksApi.CODE_CONSENT_REQUIRED.equals(mutation.failure.code) && action != TasksCloudPolicy.Action.INVALID
                && action != TasksCloudPolicy.Action.QUARANTINE && action != TasksCloudPolicy.Action.BLOCK_CREATION,
                "tasks_consent_required is never terminal for an entry");
        Slot noInstant = new Slot();
        Captured<TasksWire.MutationResponse> partial = mutate(noInstant, reply(403, fence.replace(",\"effective_at\":\"2026-10-30T08:00:00Z\"", "")));
        check(partial.failure.kind == TasksApi.Failure.Kind.PROTOCOL && "invalid_response".equals(partial.failure.code)
                && TasksCloudPolicy.classify(adapt(partial.failure)) == TasksCloudPolicy.Action.BACKOFF,
                "a consent body breaking its rules is a protocol failure (still not terminal)");

        Slot conflict = new Slot();
        Captured<TasksWire.MutationResponse> field = mutate(conflict, reply(409, "{\"error\":\"tasks_conflict\",\"message\":\"m\",\"server_time\":\"" + T
                + "\",\"reason\":\"field_changed\",\"fields\":[\"title\",\"due_at\"]}"));
        check(field.failure.kind == TasksApi.Failure.Kind.TASK && field.failure.reason == TasksWire.ConflictReason.FIELD_CHANGED
                && field.failure.fields.equals(Arrays.asList(TasksWire.ContentField.TITLE, TasksWire.ContentField.DUE_AT)),
                "409 field_changed carries its fields");
        Slot fenced = new Slot();
        Captured<TasksWire.MutationResponse> members = mutate(fenced, reply(409, error("tasks_conflict", "members_changed")));
        check(members.failure.kind == TasksApi.Failure.Kind.TASK && members.failure.reason == TasksWire.ConflictReason.MEMBERS_CHANGED
                && members.failure.fields.isEmpty(), "409 members_changed is a conflict with its reason");
    }

    /** An item 401 (identity fence inside the item) applies the session handling once; the other receipts still arrive. */
    private static void batchItemSession() {
        Slot slot = new Slot();
        Captured<TasksWire.BatchResponse> result = mutateBatch(slot, reply(200, answer("null", ok(MID, completeReceipt()),
                failed(M2, 401, "{\"error\":\"invalid_token\",\"message\":\"m\"}"))));
        check(result.value != null && result.value.results.get(0).receipt != null && result.value.results.get(1).status == 401,
                "the batch is delivered: the committed receipt is not lost");
        check(slot.invalidations == 1 && slot.invalidatedWith.equals(Collections.singletonList("invalid_token/null")) && slot.token == null
                && !slot.identity().isLinked(), "the item 401 applied the session handling for the exact credential, with its code");
        check(TasksApi.classifyItem(401, result.value.results.get(1).error).kind == TasksApi.Failure.Kind.SESSION, "and the item itself is SESSION");

        Slot twice = new Slot();
        TasksWire.Batch three = TasksWire.Batch.of(BATCH, Arrays.asList(new TasksWire.Batch.Item(mutation(), TasksWire.Origin.USER),
                new TasksWire.Batch.Item(listCreate(), TasksWire.Origin.USER)));
        twice.script.add(reply(200, answer("null", failed(MID, 401, "{\"error\":\"device_revoked\",\"message\":\"m\"}"),
                failed(M2, 401, "{\"error\":\"device_revoked\",\"message\":\"m\"}"))));
        Captured<TasksWire.BatchResponse> both = new Captured<>();
        new TasksApi(twice).mutateBatch(twice.identity(), three, both);
        check(both.value != null && twice.invalidations == 1, "applied once per batch");

        Slot rotated = new Slot();
        rotated.duringSend = () -> rotated.token = "token-2";
        Captured<TasksWire.BatchResponse> stale = mutateBatch(rotated, reply(200, answer("null", ok(MID, completeReceipt()),
                failed(M2, 401, "{\"error\":\"invalid_token\",\"message\":\"m\"}"))));
        check(stale.failure != null && stale.failure.kind == TasksApi.Failure.Kind.STALE && rotated.invalidations == 0 && "token-2".equals(rotated.token),
                "an item 401 for a credential rotated meanwhile never clears the new one (batch withheld: same ids later)");

        Slot relinked = new Slot();
        relinked.queueDelivery = true;
        Captured<TasksWire.BatchResponse> late = mutateBatch(relinked, reply(200, answer("null", ok(MID, completeReceipt()),
                failed(M2, 401, "{\"error\":\"invalid_token\",\"message\":\"m\"}"))));
        relink(relinked);
        relinked.deliveries.remove(0).run();
        check(late.value == null && late.failure != null && late.failure.kind == TasksApi.Failure.Kind.STALE,
                "a relink before delivery still withholds it (only this request's own session loss passes the fence)");

        Slot skew = new Slot();
        Captured<TasksWire.BatchResponse> nonSession = mutateBatch(skew, reply(200, answer("null", ok(MID, completeReceipt()),
                failed(M2, 401, "{\"error\":\"nonce_replayed\",\"message\":\"m\"}"))));
        check(nonSession.value != null && skew.invalidations == 0 && skew.confirms == 1, "a non-session item 401 clears nothing");
    }

    /** Boundary.invalidate gets the answer's code and reason (AND-13 pass-through; no stashed body). */
    private static void sessionReasons() {
        Slot erased = new Slot();
        Captured<TasksWire.MutationResponse> result = mutate(erased, reply(401, "{\"error\":\"device_revoked\",\"message\":\"m\",\"reason\":\"account_erased\"}"));
        check(result.failure.kind == TasksApi.Failure.Kind.SESSION && erased.invalidatedWith.equals(Collections.singletonList("device_revoked/account_erased")),
                "401 account_erased: the binding loss reports code and reason");
        Slot other = new Slot();
        Captured<TasksWire.MutationResponse> unknown = mutate(other, reply(401, "{\"error\":\"unauthenticated\",\"message\":\"x\"}"));
        check("session_invalid".equals(unknown.failure.code) && other.invalidatedWith.equals(Collections.singletonList("unauthenticated/null")),
                "an unlisted 401 code is still reported as sent (ZenlyPro sanitizes it)");
    }

    /** The X-Tasks-Wire echo on every REST result and failure; null when absent. */
    private static void serverWire() {
        Slot slot = new Slot();
        check(mutate(slot, echoed(200, receipt())).value.serverWire == 4, "mutation result echo");
        check(mutate(new Slot(), reply(200, receipt())).value.serverWire == null, "mutation result without echo");
        Slot pages = new Slot();
        pages.script.add(echoed(200, emptySnapshot()));
        Captured<TasksWire.SnapshotResponse> snapshot = new Captured<>();
        new TasksApi(pages).snapshot(pages.identity(), null, 50, snapshot);
        pages.script.add(echoed(200, "{\"schema_version\":1,\"changes\":[],\"next_cursor\":\"h\",\"has_more\":false,\"server_time\":\"" + T + "\"}"));
        Captured<TasksWire.ChangesResponse> changes = new Captured<>();
        new TasksApi(pages).changes(pages.identity(), "c", 50, changes);
        check(snapshot.value.serverWire == 4 && changes.value.serverWire == 4, "snapshot and changes echo");
        check(mutate(new Slot(), echoed(404, error("tasks_not_found", null))).failure.serverWire == 4, "a failure carries the echo");
        check(mutate(new Slot(), reply(404, error("tasks_not_found", null))).failure.serverWire == null, "a failure without echo: null");
        check(mutate(new Slot(), echoed(200, "{}")).failure.serverWire == 4, "a malformed 200 still reports its echo");
        check(mutate(new Slot(), new java.io.IOException("reset")).failure.serverWire == null, "no answer: null");
        Slot ignored = new Slot();
        Captured<TasksWire.MutationResponse> wrong = mutate(ignored, new ProTransport.TasksReply(200, receipt(), 0, 3));
        check(wrong.value != null && wrong.value.serverWire == 3, "the echo is reported as received; the caller compares it with 4");
    }

    private static void queries() {
        check("?limit=50".equals(TasksApi.query(null, 50, false)), "first snapshot page sends only the limit");
        check(TasksApi.query(null, 50, true) == null, "changes require a cursor");
        check(TasksApi.query("c", 0, false) == null && TasksApi.query("c", 101, false) == null, "limit outside 1..100 refused");
        check("?cursor=a%2Bb%2F%3D%20%D1%8F~-._&limit=1".equals(TasksApi.query("a+b/= \u044f~-._", 1, false)), "cursor strictly percent-encoded");
        check(TasksApi.query("", 1, false) == null && TasksApi.query("x\ud800", 1, false) == null, "empty or ill-formed cursor refused");
        Slot slot = new Slot();
        Captured<TasksWire.ChangesResponse> refused = new Captured<>();
        new TasksApi(slot).changes(slot.identity(), null, 50, refused);
        check(refused.failure != null && refused.failure.kind == TasksApi.Failure.Kind.LOCAL && "invalid_request".equals(refused.failure.code)
                && slot.sends == 0, "invalid arguments never reach the network");
        Slot page = new Slot();
        page.script.add(reply(200, "{\"schema_version\":1,\"items\":[],\"next_cursor\":null,\"changes_cursor\":\"c\",\"server_time\":\"" + T + "\"}"));
        Captured<TasksWire.SnapshotResponse> snap = new Captured<>();
        new TasksApi(page).snapshot(page.identity(), "cursor", 1, snap);
        check(snap.value != null && snap.value.items.isEmpty() && snap.value.nextCursor == null, "empty snapshot page decodes");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) failures.add(name);
    }
}
