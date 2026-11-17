package org.telegram.messenger.zenlygram.pro;

import org.telegram.messenger.zenlygram.tasks.cloud.TasksRealtimeWire;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Account-scoped Zenlygram Tasks cloud client (wire R4, x-endpoints + x-realtime): exactly six named calls on
 * the current linked binding of one Telegram account slot. No generic path, signer or credential
 * surface: ZenlyPro signs each attempt with the freshest stored credential of the SAME identity and
 * generation the call was made under (restamped at actual send on the existing Pro worker), and the
 * existing per-route 429 cooldowns and single clock-skew correction apply. Every request carries
 * X-Tasks-Wire: 4 exactly once (x-wire-negotiation; an R3 server ignores it and never echoes it), and the
 * answer's echo is surfaced on every REST result and failure (null when absent: the downgrade signal).
 * Each attempt is bounded (ProTransport.TASKS_TIMEOUT_MS total; the batch call alone
 * ProTransport.TASKS_BATCH_TIMEOUT_MS, because the server's window starts only after the whole body has
 * arrived; bounded body); at most two attempts; never a sleep or a wait. Every call from outside this package
 * carries its caller's consent {@link SendGate}, asked on the Pro worker right before each attempt is signed.
 *
 * At most one sync Tasks request (mutations/batch/snapshot/changes) and at most one realtime request (metadata/auth)
 * per account are in flight — two lanes, so a realtime reconnect never makes the sync engine see BUSY or vice versa;
 * both still run serially on the Pro worker. Callbacks always run on the UI thread, outside
 * the Pro lock, after the in-flight slot was released. Task errors are classified here and never pass
 * through generic Pro error handling: 403 tasks_account_not_linked preserves session, cached grant,
 * binding and every caller-side state; only a real 401 (of the answer, or of a batch item) applies the
 * existing session handling, and only to the exact credential that request was signed with.
 */
public final class TasksApi {
    static final String ROUTE_MUTATIONS = "tasks/mutations", ROUTE_SNAPSHOT = "tasks/snapshot", ROUTE_CHANGES = "tasks/changes";
    static final String ROUTE_REALTIME = "tasks/realtime", ROUTE_REALTIME_AUTH = "tasks/realtime/auth";
    /** POST /v1/tasks/mutations/batch (R4 only). */
    static final String ROUTE_BATCH = "tasks/mutations/batch";
    static final int MAX_MUTATION_RESPONSE_BYTES = 16384;
    /** A batch answer: at most 100 receipts or item errors, no content. */
    static final int MAX_BATCH_RESPONSE_BYTES = TasksWire.MAX_BATCH_RESPONSE_BYTES;
    static final int MAX_RETRY_AFTER_SECONDS = 3600;
    private static final int MAX_CURSOR_SCALARS = 4096;
    /** x-wire-negotiation: the X-Tasks-Wire value of every request (Request.wire). */
    static final int WIRE = TasksWire.WIRE;

    /** 503 tasks_unavailable reason batch_disabled: the batch route is switched off (answered before authentication). */
    public static final String CODE_BATCH_DISABLED = "batch_disabled";
    /** 503 tasks_unavailable reason migration_paused: a migration-origin item while migrations are paused. */
    public static final String CODE_MIGRATION_PAUSED = "migration_paused";
    /** 403 x-consent-fence (every route and batch item): a processed consent withdrawal stands. Never terminal. */
    public static final String CODE_CONSENT_REQUIRED = "tasks_consent_required";
    /**
     * STALE, local: the caller's {@link SendGate} refused the request on the Pro worker; nothing was signed or sent. Like
     * any STALE answer it goes back to the queue (TasksCloudPolicy REQUEUE), where a withdrawal's discard drops it.
     */
    public static final String CODE_CONSENT_CHANGED = "consent_changed";

    public interface Callback<T> {
        void onSuccess(T value);
        void onFailure(Failure failure);
    }

    /**
     * The caller's consent check of one request (contract §6.7: a withdrawal stops Tasks traffic at once). Asked on the
     * Pro worker immediately before each attempt is signed, so a request that waited there through a withdrawal is never
     * signed or sent: false, a throw or no gate at all refuse it as STALE {@link #CODE_CONSENT_CHANGED}. Any thread; cheap.
     */
    public interface SendGate { boolean allowed(); }

    /** This package's host proofs of the exchange only: no consent check at send. */
    static final SendGate UNGATED = () -> true;

    /**
     * The current Telegram user of the slot and its cloud binding. principal (the backend account id
     * whose bytes enter the business hash) is null while no linked session exists. generation is a
     * process-local fence (it changes on identity, binding or configuration changes): compare it in
     * callbacks, never persist it; persist the principal.
     */
    public static final class Identity {
        public final long telegramUserId;
        public final String principal;
        public final long generation;

        Identity(long telegramUserId, String principal, long generation) {
            this.telegramUserId = telegramUserId;
            this.principal = principal;
            this.generation = generation;
        }

        public boolean isLinked() { return principal != null; }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Identity)) return false;
            Identity that = (Identity) other;
            return telegramUserId == that.telegramUserId && generation == that.generation
                    && (principal == null ? that.principal == null : principal.equals(that.principal));
        }

        @Override public int hashCode() {
            return (int) (telegramUserId * 31 + generation) * 31 + (principal == null ? 0 : principal.hashCode());
        }
    }

    /** Allowlisted failure; carries no server message, payload or exception. */
    public static final class Failure {
        public enum Kind {
            /** 403 tasks_account_not_linked: pause this account; everything preserved; resume after user linking. */
            UNLINKED,
            /**
             * Real 401: the exact captured refresh credential was cleared (durably, before delivery) by the existing Pro
             * handling; relink. That binding is never linked again: a later linked identity is a re-link.
             */
            SESSION,
            /**
             * The expected identity is no longer current (account switch, logout, relink, configuration or
             * credential change) at call, send, completion or UI delivery. No result is delivered for it.
             * A mutation may already have been sent and committed: its outcome is UNKNOWN; keep the same
             * sealed mutation and reconcile/resend it under the same id only while its principal is current.
             * Also the caller's send gate refusing at send ({@link #CODE_CONSENT_CHANGED}: nothing was sent).
             */
            STALE,
            /** Another Tasks request of this account is in flight. */
            BUSY,
            /** Local precondition: not linked, not configured, signed out, storage, device clock, bad argument. */
            LOCAL,
            /** Network/timeout: a mutation outcome is UNKNOWN; retry the SAME sealed mutation. */
            OFFLINE,
            /** Server 429 or a running local cooldown; see retryAfterSeconds. */
            RATE_LIMITED,
            /**
             * 5xx (tasks_unavailable or existing server_error): mutation outcome unknown; same id. Except the R4
             * codes batch_disabled and migration_paused: deliberate refusals of work that never ran (outcome
             * known, nothing committed).
             */
            UNAVAILABLE,
            /** Allowlisted Tasks error (403 creation or tasks_consent_required, 404, 409, 422). */
            TASK,
            /** Malformed/unknown-member success, non-allowlisted error, or rejected transport (413/415/other 4xx). */
            PROTOCOL
        }

        public final Kind kind;
        public final String code;
        /** Non-null exactly for tasks_conflict (R4 adds FIELD_CHANGED and MEMBERS_CHANGED). */
        public final TasksWire.ConflictReason reason;
        /** 0 for a local failure. */
        public final int httpStatus;
        public final int retryAfterSeconds;
        /** tasks_conflict field_changed: the conflicting fields in content order; empty otherwise. Unmodifiable. */
        public final List<TasksWire.ContentField> fields;
        /** tasks_consent_required: when the standing withdrawal took effect (a wire Instant); null otherwise. */
        public final String effectiveAt;
        /** The answer's X-Tasks-Wire echo; null when absent, and for a local failure. */
        public final Integer serverWire;

        Failure(Kind kind, String code, TasksWire.ConflictReason reason, int httpStatus, int retryAfterSeconds) {
            this(kind, code, reason, httpStatus, retryAfterSeconds, Collections.<TasksWire.ContentField>emptyList(), null, null);
        }

        Failure(Kind kind, String code, TasksWire.ConflictReason reason, int httpStatus, int retryAfterSeconds,
                List<TasksWire.ContentField> fields, String effectiveAt, Integer serverWire) {
            this.kind = kind;
            this.code = code;
            this.reason = reason;
            this.httpStatus = httpStatus;
            this.retryAfterSeconds = Math.max(0, Math.min(MAX_RETRY_AFTER_SECONDS, retryAfterSeconds));
            this.fields = fields;
            this.effectiveAt = effectiveAt;
            this.serverWire = serverWire;
        }

        Failure withServerWire(Integer value) {
            return new Failure(kind, code, reason, httpStatus, retryAfterSeconds, fields, effectiveAt, value);
        }

        @Override public String toString() { return "TasksApi.Failure{" + kind + "," + code + "," + httpStatus + "}"; }
    }

    // ---- Public facade ----------------------------------------------------------------------

    private static final Map<Integer, TasksApi> INSTANCES = new HashMap<>();

    private final Boundary boundary;
    private boolean inFlight, realtimeInFlight;

    TasksApi(Boundary boundary) { this.boundary = boundary; }

    public static TasksApi forAccount(int account) {
        ZenlyPro pro = ZenlyPro.get(account); // Validates the slot.
        synchronized (INSTANCES) {
            TasksApi api = INSTANCES.get(account);
            if (api == null) {
                api = new TasksApi(pro.tasksBoundary());
                INSTANCES.put(account, api);
            }
            return api;
        }
    }

    /** Current identity of the slot; pass it unchanged as {@code expected} to the calls below. */
    public Identity identity() { return boundary.identity(); }

    /**
     * POST /v1/tasks/mutations with the exact sealed body; Idempotency-Key = mutation id. {@code expected}
     * must be the current linked identity and its principal the mutation's principal. {@code gate}: the
     * caller's consent at send ({@link SendGate}); every call below takes one.
     */
    public void mutate(Identity expected, TasksWire.Mutation mutation, SendGate gate, Callback<TasksWire.MutationResponse> callback) {
        if (mutation == null) { reject(callback, "invalid_request"); return; }
        if (expected != null && expected.principal != null && !expected.principal.equals(mutation.principal)) {
            reject(callback, "principal_mismatch");
            return;
        }
        submit(expected, new Request<TasksWire.MutationResponse>(ROUTE_MUTATIONS, "POST", "", mutation.body(), mutation.mutationId, expected,
                MAX_MUTATION_RESPONSE_BYTES, (text, base, wire) -> TasksWire.decodeMutationResponse(text, mutation, wire),
                value -> value.serverTime, gate), callback);
    }

    /**
     * POST /v1/tasks/mutations/batch (R4) with the exact batch body; Idempotency-Key = batch id; the sync lane;
     * its own ProTransport.TASKS_BATCH_TIMEOUT_MS budget. {@code expected} must be the current linked identity and
     * its principal the batch's. Every result answers its own item (checked); {@link #classifyItem} turns an error
     * item into exactly the Failure the single route would have given for the same answer. An item 401 of the
     * DeviceAuth family applies the existing session handling once, like an envelope 401, and the batch is still
     * delivered so the other items' receipts are never lost. A failure of the whole call (the envelope) leaves
     * every item's outcome as that failure says (OFFLINE / UNAVAILABLE / malformed: unknown, resend the same
     * mutations; 413: send less; 404 on an R3 server; 503 batch_disabled: nothing ran).
     */
    public void mutateBatch(Identity expected, TasksWire.Batch batch, SendGate gate, Callback<TasksWire.BatchResponse> callback) {
        if (batch == null) { reject(callback, "invalid_request"); return; }
        if (expected != null && expected.principal != null && !expected.principal.equals(batch.principal)) {
            reject(callback, "principal_mismatch");
            return;
        }
        submit(expected, new Request<TasksWire.BatchResponse>(ROUTE_BATCH, "POST", "", batch.body(), batch.batchId, expected,
                MAX_BATCH_RESPONSE_BYTES, (text, base, wire) -> TasksWire.decodeBatchResponse(text, batch, wire),
                value -> value.serverTime, TasksApi::sessionItem, gate), callback);
    }

    /** GET /v1/tasks/snapshot for {@code expected}; cursor null for the first page; keep limit fixed throughout one snapshot. */
    public void snapshot(Identity expected, String cursor, int limit, SendGate gate, Callback<TasksWire.SnapshotResponse> callback) {
        String query = query(cursor, limit, false);
        if (query == null) { reject(callback, "invalid_request"); return; }
        submit(expected, new Request<TasksWire.SnapshotResponse>(ROUTE_SNAPSHOT, "GET", query, null, null, expected, TasksWire.maxPageBytes(limit),
                (text, base, wire) -> TasksWire.decodeSnapshotResponse(text, limit, wire), value -> value.serverTime, gate), callback);
    }

    /** GET /v1/tasks/changes for {@code expected}; cursor required. */
    public void changes(Identity expected, String cursor, int limit, SendGate gate, Callback<TasksWire.ChangesResponse> callback) {
        String query = query(cursor, limit, true);
        if (query == null) { reject(callback, "invalid_request"); return; }
        submit(expected, new Request<TasksWire.ChangesResponse>(ROUTE_CHANGES, "GET", query, null, null, expected, TasksWire.maxPageBytes(limit),
                (text, base, wire) -> TasksWire.decodeChangesResponse(text, limit, wire), value -> value.serverTime, gate), callback);
    }

    /**
     * GET /v1/tasks/realtime for {@code expected} (x-realtime metadata): the ws_url is validated against the API base
     * URL this very attempt was sent to; {@code enabled:false} is the kill switch, never a failure.
     */
    public void realtime(Identity expected, SendGate gate, Callback<TasksRealtimeWire.Metadata> callback) {
        submit(expected, new Request<TasksRealtimeWire.Metadata>(ROUTE_REALTIME, "GET", "", null, null, expected,
                TasksRealtimeWire.MAX_RESPONSE_BYTES, (text, base, wire) -> TasksRealtimeWire.decodeMetadata(text, base),
                value -> value.serverTime, gate), callback);
    }

    /** POST /v1/tasks/realtime/auth {"socket_id"} for {@code expected}: a fresh signed request per socket id. */
    public void realtimeAuth(Identity expected, String socketId, SendGate gate, Callback<TasksRealtimeWire.Subscription> callback) {
        if (!TasksRealtimeWire.isValidSocketId(socketId)) { reject(callback, "invalid_request"); return; }
        submit(expected, new Request<TasksRealtimeWire.Subscription>(ROUTE_REALTIME_AUTH, "POST", "", TasksRealtimeWire.authRequestBody(socketId),
                null, expected, TasksRealtimeWire.MAX_RESPONSE_BYTES, (text, base, wire) -> TasksRealtimeWire.decodeSubscription(text, socketId),
                TasksRealtimeWire.Subscription::serverTime, gate), callback);
    }

    // The same six calls without a send gate: this package's host proofs of the exchange only, never app traffic.

    void mutate(Identity expected, TasksWire.Mutation mutation, Callback<TasksWire.MutationResponse> callback) {
        mutate(expected, mutation, UNGATED, callback);
    }

    void mutateBatch(Identity expected, TasksWire.Batch batch, Callback<TasksWire.BatchResponse> callback) {
        mutateBatch(expected, batch, UNGATED, callback);
    }

    void snapshot(Identity expected, String cursor, int limit, Callback<TasksWire.SnapshotResponse> callback) {
        snapshot(expected, cursor, limit, UNGATED, callback);
    }

    void changes(Identity expected, String cursor, int limit, Callback<TasksWire.ChangesResponse> callback) {
        changes(expected, cursor, limit, UNGATED, callback);
    }

    void realtime(Identity expected, Callback<TasksRealtimeWire.Metadata> callback) {
        realtime(expected, UNGATED, callback);
    }

    void realtimeAuth(Identity expected, String socketId, Callback<TasksRealtimeWire.Subscription> callback) {
        realtimeAuth(expected, socketId, UNGATED, callback);
    }

    /** The first batch item answered with a real 401 (the DeviceAuth family), else null. */
    private static TasksWire.ErrorEnvelope sessionItem(TasksWire.BatchResponse response) {
        for (TasksWire.BatchItemResult result : response.results) {
            if (result.kind == TasksWire.BatchItemResult.Kind.ERROR && result.status == 401
                    && classifyItem(result.status, result.error).kind == Failure.Kind.SESSION) {
                return result.error;
            }
        }
        return null;
    }

    private <T> void reject(Callback<T> callback, String code) {
        if (callback == null) throw new IllegalArgumentException("Missing Tasks callback");
        Failure failure = local("principal_mismatch".equals(code) ? Failure.Kind.STALE : Failure.Kind.LOCAL, code);
        boundary.deliver(() -> callback.onFailure(failure));
    }

    private <T> void submit(Identity expected, Request<T> request, Callback<T> callback) {
        if (callback == null) throw new IllegalArgumentException("Missing Tasks callback");
        if (expected == null) { reject(callback, "invalid_request"); return; }
        if (expected.principal == null) { reject(callback, "link_required"); return; }
        Object ticket = null;
        Failure refused = null;
        synchronized (this) {
            if (request.realtime ? realtimeInFlight : inFlight) {
                refused = local(Failure.Kind.BUSY, "request_in_flight");
            } else {
                try {
                    // Atomically under the Pro lock: the slot is still exactly `expected`; its stamp is captured.
                    ticket = boundary.capture(expected);
                    if (request.realtime) realtimeInFlight = true;
                    else inFlight = true;
                } catch (Stop stale) {
                    refused = stale.failure;
                }
            }
        }
        if (refused != null) {
            final Failure failure = refused;
            boundary.deliver(() -> callback.onFailure(failure));
            return;
        }
        final Object captured = ticket;
        try {
            boundary.execute(() -> {
                Result<T> result;
                try {
                    result = Exchange.run(boundary, captured, request);
                } catch (RuntimeException unexpected) {
                    result = Result.failed(local(Failure.Kind.OFFLINE, "connection_failed"));
                } finally {
                    release(request);
                }
                final Result<T> done = result;
                boundary.deliver(() -> fenced(expected, done).dispatch(callback));
            });
        } catch (RuntimeException notQueued) {
            release(request);
            Failure failure = local(Failure.Kind.LOCAL, "storage_unavailable");
            boundary.deliver(() -> callback.onFailure(failure));
        }
    }

    /**
     * Last fence, on the delivering thread right before the callback: a result reaches the caller only
     * while the slot is still exactly the identity it was requested for. The one exception is the
     * session loss this request itself caused (a SESSION failure, or a batch whose item 401 cleared the
     * session): same Telegram user and generation, binding cleared.
     */
    private <T> Result<T> fenced(Identity expected, Result<T> done) {
        Identity now = boundary.identity();
        if (expected.equals(now)) return done;
        if (done.sessionCleared && now.principal == null
                && now.telegramUserId == expected.telegramUserId && now.generation == expected.generation) {
            return done;
        }
        return Result.failed(local(Failure.Kind.STALE, "session_changed"));
    }

    private synchronized void release(Request<?> request) {
        if (request.realtime) realtimeInFlight = false;
        else inFlight = false;
    }

    /**
     * x-wire-negotiation, for ZenlyPro's signing step: X-Tasks-Wire = request.wire, exactly once (one value
     * per header name) on every Tasks request of this R4 client.
     */
    static void putWireHeader(Request<?> request, Map<String, String> headers) {
        if (request.wire == WIRE) headers.put(ProTransport.TASKS_WIRE_HEADER, Integer.toString(request.wire));
    }

    /** "?cursor=…&limit=N" (cursor percent-encoded as strict UTF-8), or null for an invalid argument. */
    static String query(String cursor, int limit, boolean cursorRequired) {
        if (limit < TasksWire.MIN_LIMIT || limit > TasksWire.MAX_LIMIT) return null;
        if (cursor == null) return cursorRequired ? null : "?limit=" + limit;
        int scalars = cursor.codePointCount(0, cursor.length());
        if (scalars < 1 || scalars > MAX_CURSOR_SCALARS) return null;
        String encoded = percentEncode(cursor);
        return encoded == null ? null : "?cursor=" + encoded + "&limit=" + limit;
    }

    private static String percentEncode(String value) {
        ByteBuffer bytes;
        try {
            bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException lone) {
            return null;
        }
        StringBuilder out = new StringBuilder(bytes.remaining() * 3);
        while (bytes.hasRemaining()) {
            int b = bytes.get() & 0xff;
            boolean unreserved = (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '.' || b == '_' || b == '~';
            if (unreserved) out.append((char) b);
            else out.append('%').append(Character.toUpperCase(Character.forDigit(b >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
        }
        return out.toString();
    }

    // ---- Bounded exchange state machine (package-private, host-tested) ------------------------

    /**
     * {@code apiBaseUrl}: the configured {@code https://{authority}/v1} the attempt was actually sent to;
     * {@code serverWire}: the answer's X-Tasks-Wire echo (null when absent).
     */
    interface Decoder<T> { T decode(String text, String apiBaseUrl, Integer serverWire); }

    interface ServerTime<T> { String of(T value); }

    /** A delivered value's own session loss (the batch: an item 401 of the DeviceAuth family), else null. */
    interface SessionItem<T> { TasksWire.ErrorEnvelope of(T value); }

    /** One named Tasks call for one expected identity; constructed only by the public methods above. */
    static final class Request<T> {
        final String route, method, query, idempotencyKey;
        /** The identity the caller requested this for; its principal must be the binding signed at every attempt. */
        final Identity expected;
        final String principal;
        final byte[] body;
        final int maxBytes;
        /** x-wire-negotiation: TasksWire.WIRE on every request; ZenlyPro signs it in as X-Tasks-Wire exactly once. */
        final int wire;
        /** One attempt's total budget: ProTransport.TASKS_BATCH_TIMEOUT_MS for the batch call, TASKS_TIMEOUT_MS otherwise. */
        final int timeoutMs;
        final Decoder<T> decoder;
        final ServerTime<T> serverTime;
        /** Batch only; null otherwise. */
        final SessionItem<T> sessionItem;
        /** Metadata/auth: the realtime in-flight lane, never the sync engine's. */
        final boolean realtime;
        /** The caller's consent, asked on the Pro worker right before each attempt is signed (null refuses). */
        final SendGate gate;

        Request(String route, String method, String query, byte[] body, String idempotencyKey, Identity expected, int maxBytes,
                Decoder<T> decoder, ServerTime<T> serverTime, SendGate gate) {
            this(route, method, query, body, idempotencyKey, expected, maxBytes, decoder, serverTime, null, gate);
        }

        Request(String route, String method, String query, byte[] body, String idempotencyKey, Identity expected, int maxBytes,
                Decoder<T> decoder, ServerTime<T> serverTime, SessionItem<T> sessionItem, SendGate gate) {
            this.route = route;
            this.method = method;
            this.query = query;
            this.body = body;
            this.idempotencyKey = idempotencyKey;
            this.expected = expected;
            this.principal = expected == null ? null : expected.principal;
            this.maxBytes = maxBytes;
            this.wire = WIRE;
            this.timeoutMs = ROUTE_BATCH.equals(route) ? ProTransport.TASKS_BATCH_TIMEOUT_MS : ProTransport.TASKS_TIMEOUT_MS;
            this.decoder = decoder;
            this.serverTime = serverTime;
            this.sessionItem = sessionItem;
            this.realtime = ROUTE_REALTIME.equals(route) || ROUTE_REALTIME_AUTH.equals(route);
            this.gate = gate;
        }
    }

    /** A classified refusal from the Pro side of the boundary. */
    static final class Stop extends Exception {
        private static final long serialVersionUID = 1L;
        final transient Failure failure;
        Stop(Failure failure) {
            super(failure.code, null, false, false);
            this.failure = failure;
        }
    }

    /**
     * ZenlyPro's side (opaque ticket/attempt objects; credentials never leave it). Every method except
     * send/execute/deliver runs under the Pro lock.
     */
    interface Boundary {
        Identity identity();
        /**
         * Atomically under the Pro lock: requires the slot to be exactly {@code expected} (Telegram user,
         * linked principal, generation) and captures its stamp (identity + generation + credential).
         */
        Object capture(Identity expected) throws Stop;
        /** The existing Pro worker. */
        void execute(Runnable work);
        /** The UI thread. */
        void deliver(Runnable callback);
        /**
         * Restamps the ticket (same identity and generation, freshest stored credential), requires a
         * linked session whose principal is request.principal, requires the route's cooldown to be over,
         * and signs fresh device headers (+ Idempotency-Key, + X-Tasks-Wire: request.wire).
         */
        Object prepare(Object ticket, Request<?> request) throws Stop;
        /** One bounded network attempt (ProTransport.tasks within request.timeoutMs) with exactly the prepared headers. */
        ProTransport.TasksReply send(Object attempt, Request<?> request) throws Exception;
        /** The API base URL ({@code https://{authority}/v1}) the attempt was signed and sent for. */
        String apiBaseUrl(Object attempt);
        /** Validated server time (Unix seconds, 0 = none) and a 429 cooldown (retryAfter >= 0) of the attempt's route. */
        void settle(Object attempt, Request<?> request, long serverTimeSeconds, int retryAfter);
        /** The attempt's identity, generation, principal and exact credential are still current. */
        void confirm(Object attempt) throws Stop;
        /**
         * Real 401: existing session handling for exactly the attempt's credential and principal, if still current,
         * reporting the binding loss with the answer's (or the batch item's) code and reason (null when it named none).
         */
        void invalidate(Object attempt, String code, String reason) throws Stop;
    }

    static final class Result<T> {
        final T value;
        final Failure failure;
        /** This very request applied the existing session handling (a real 401 of its answer or of a batch item). */
        final boolean sessionCleared;
        private Result(T value, Failure failure, boolean sessionCleared) {
            this.value = value;
            this.failure = failure;
            this.sessionCleared = sessionCleared;
        }
        static <T> Result<T> ok(T value) { return new Result<>(value, null, false); }
        static <T> Result<T> ok(T value, boolean sessionCleared) { return new Result<>(value, null, sessionCleared); }
        static <T> Result<T> failed(Failure failure) { return new Result<>(null, failure, false); }
        static <T> Result<T> failed(Failure failure, boolean sessionCleared) { return new Result<>(null, failure, sessionCleared); }
        void dispatch(Callback<T> callback) {
            if (failure == null) callback.onSuccess(value);
            else callback.onFailure(failure);
        }
    }

    static final class Exchange {
        private Exchange() { }

        /**
         * At most two attempts (the second only after a 401 clock_skew carrying a valid server_time), each one signed only
         * after the request's send gate allowed it here, on the worker.
         */
        static <T> Result<T> run(Boundary boundary, Object ticket, Request<T> request) {
            for (int attempt = 0; attempt < 2; attempt++) {
                // The caller's consent, asked again right before signing: a withdrawal while this request waited on the
                // worker refuses it here, with nothing signed or sent.
                if (!permits(request.gate)) return Result.failed(local(Failure.Kind.STALE, CODE_CONSENT_CHANGED));
                final Object signed;
                try { signed = boundary.prepare(ticket, request); }
                catch (Stop stop) { return Result.failed(stop.failure); }

                final ProTransport.TasksReply reply;
                try {
                    reply = boundary.send(signed, request);
                } catch (ProMonetization.Rejected rejected) {
                    return Result.failed(rejected.reason == ProMonetization.Reason.TIMED_OUT
                            ? local(Failure.Kind.OFFLINE, "connection_failed") : protocol(0));
                } catch (Exception network) {
                    return Result.failed(local(Failure.Kind.OFFLINE, "connection_failed"));
                }

                if (reply.status == 200) {
                    T value;
                    try {
                        if (reply.body == null) throw new IllegalArgumentException("empty");
                        value = request.decoder.decode(reply.body, boundary.apiBaseUrl(signed), reply.serverWire);
                    } catch (RuntimeException invalid) {
                        boundary.settle(signed, request, 0, -1);
                        return Result.failed(protocol(200).withServerWire(reply.serverWire));
                    }
                    // Never a route cooldown from a batch's own retry_after: it names one item's bucket (or a
                    // migration pause), which the caller schedules per origin.
                    boundary.settle(signed, request, seconds(request.serverTime.of(value)), -1);
                    TasksWire.ErrorEnvelope lost = request.sessionItem == null ? null : request.sessionItem.of(value);
                    try {
                        // A batch item 401 is applied once, like an envelope 401; the other items' receipts still count.
                        if (lost != null) boundary.invalidate(signed, lost.code, lost.wireReason);
                        else boundary.confirm(signed);
                    } catch (Stop stale) {
                        return Result.failed(stale.failure);
                    }
                    return Result.ok(value, lost != null);
                }

                TasksWire.ErrorEnvelope envelope = reply.body == null ? null : TasksWire.decodeErrorEnvelope(reply.body);
                Failure failure = classify(reply.status, envelope, reply.retryAfter).withServerWire(reply.serverWire);
                boundary.settle(signed, request, envelope == null ? 0 : seconds(envelope.serverTime),
                        reply.status == 429 ? failure.retryAfterSeconds : -1);
                if (attempt == 0 && reply.status == 401 && envelope != null && "clock_skew".equals(envelope.code)
                        && envelope.serverTime != null) {
                    continue; // The existing single correction: re-prepare re-reads the credential and re-signs.
                }
                boolean session = failure.kind == Failure.Kind.SESSION; // Only with a readable envelope.
                try {
                    if (session) boundary.invalidate(signed, envelope.code, envelope.wireReason);
                    else boundary.confirm(signed);
                } catch (Stop stale) {
                    return Result.failed(stale.failure);
                }
                return Result.failed(failure, session);
            }
            return Result.failed(new Failure(Failure.Kind.LOCAL, "clock_skew", null, 401, 0));
        }

        private static long seconds(String instant) {
            if (instant == null) return 0;
            try { return TasksWire.epochSeconds(instant); }
            catch (TasksWire.Invalid invalid) { return 0; }
        }

        /** Fail closed: no gate, false or a throw refuse. */
        private static boolean permits(SendGate gate) {
            if (gate == null) return false;
            try {
                return gate.allowed();
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /**
     * Strict allowlist of x-error-codes: a Tasks code counts only with its own status and an exact
     * $defs.Error envelope ($defs.ErrorRules: tasks_conflict with a known reason, tasks_consent_required with
     * withdrawn and effective_at, tasks_unavailable with batch_disabled / migration_paused or none, no reason
     * otherwise). Existing auth envelopes keep their meaning by status: any 401 except
     * clock_skew/nonce_replayed is a session failure (as in ZenlyPro.successfulLocked); an unreadable 401
     * body never clears anything. R4 answers keep the R3 Kind an R3 client gives them and say what they
     * are in code (and fields / effectiveAt): batch_disabled and migration_paused UNAVAILABLE (refused, never
     * ran), tasks_consent_required TASK (never terminal), field_changed / members_changed TASK tasks_conflict.
     */
    static Failure classify(int status, TasksWire.ErrorEnvelope envelope, int retryAfter) {
        String code = envelope == null ? null : envelope.code;
        boolean strict = envelope != null && envelope.strict;
        if (status == 429) return new Failure(Failure.Kind.RATE_LIMITED, "rate_limited", null, 429, Math.max(1, retryAfter));
        if (status == 401) {
            if (envelope == null) return protocol(401);
            if ("clock_skew".equals(code)) return new Failure(Failure.Kind.LOCAL, "clock_skew", null, 401, 0);
            if ("nonce_replayed".equals(code)) return new Failure(Failure.Kind.OFFLINE, "nonce_replayed", null, 401, 0);
            boolean known = "invalid_token".equals(code) || "device_revoked".equals(code) || "device_key_mismatch".equals(code);
            return new Failure(Failure.Kind.SESSION, known ? code : "session_invalid", null, 401, 0);
        }
        if (status >= 500) {
            boolean tasks = status == 503 && strict && "tasks_unavailable".equals(code);
            String refused = tasks ? envelope.wireReason : null;
            if (CODE_BATCH_DISABLED.equals(refused) || CODE_MIGRATION_PAUSED.equals(refused)) {
                return new Failure(Failure.Kind.UNAVAILABLE, refused, null, 503, retryAfter);
            }
            return new Failure(Failure.Kind.UNAVAILABLE, tasks ? "tasks_unavailable" : "server_error", null, status, retryAfter);
        }
        // Status first: an oversized request whatever its body names (the batch route's 413 is tasks_invalid_request)
        // is a bounded transport refusal; the sender answers it by sending less.
        if (status == 413) return new Failure(Failure.Kind.PROTOCOL, "request_rejected", null, 413, 0);
        if (strict) {
            if (status == 403 && "tasks_account_not_linked".equals(code)) return new Failure(Failure.Kind.UNLINKED, code, null, 403, 0);
            if (status == 403 && CODE_CONSENT_REQUIRED.equals(code)) {
                return new Failure(Failure.Kind.TASK, code, null, 403, retryAfter, Collections.<TasksWire.ContentField>emptyList(),
                        envelope.effectiveAt, null);
            }
            if ((status == 403 && "tasks_creation_not_permitted".equals(code))
                    || (status == 404 && "tasks_not_found".equals(code))
                    || (status == 409 && ("tasks_resync_required".equals(code) || "tasks_mutation_in_flight".equals(code)))
                    || (status == 422 && ("tasks_invalid_request".equals(code) || "tasks_mutation_mismatch".equals(code)))) {
                return new Failure(Failure.Kind.TASK, code, null, status, retryAfter);
            }
            if (status == 409 && "tasks_conflict".equals(code)) {
                return new Failure(Failure.Kind.TASK, code, envelope.reason, 409, retryAfter, envelope.fields, null, null);
            }
        }
        if (status >= 400 && status < 500 && (code == null || !code.startsWith("tasks_"))) {
            return new Failure(Failure.Kind.PROTOCOL, "request_rejected", null, status, 0);
        }
        return protocol(status);
    }

    /**
     * One batch item's error (a {@link TasksWire.BatchItemResult} of kind ERROR) through the very table of the
     * single route, so an item failure is the Failure the same answer to a single mutation would be, and
     * TasksCloudPolicy maps it to the same action. The item error is strict by construction; its retry_after
     * (or the batch's) stands in for Retry-After. Its serverWire is the batch's (on the BatchResponse).
     */
    public static Failure classifyItem(int status, TasksWire.ErrorEnvelope error) {
        if (error == null) return protocol(status);
        return classify(status, error, error.retryAfter);
    }

    /** Pro-side refusals (restamp, session, configuration, storage, local cooldown) mapped to the allowlist. */
    static Stop stopFor(String proCode, int retryAfter) {
        if ("link_required".equals(proCode) || "not_configured".equals(proCode) || "login_required".equals(proCode)
                || "storage_unavailable".equals(proCode) || "rotation_pending".equals(proCode)) {
            return new Stop(local(Failure.Kind.LOCAL, proCode));
        }
        if ("rate_limited".equals(proCode)) return new Stop(new Failure(Failure.Kind.RATE_LIMITED, "rate_limited", null, 429, Math.max(1, retryAfter)));
        if ("principal_mismatch".equals(proCode)) return new Stop(local(Failure.Kind.STALE, "principal_mismatch"));
        return new Stop(local(Failure.Kind.STALE, "session_changed"));
    }

    private static Failure local(Failure.Kind kind, String code) { return new Failure(kind, code, null, 0, 0); }

    private static Failure protocol(int status) { return new Failure(Failure.Kind.PROTOCOL, "invalid_response", null, status, 0); }
}
