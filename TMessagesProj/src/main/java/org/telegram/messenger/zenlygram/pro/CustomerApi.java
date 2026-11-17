package org.telegram.messenger.zenlygram.pro;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Account-scoped customer-data client (contract §2.4 profile routes, §2.2.5 account/delete): five named
 * calls on the current linked binding of one Telegram account slot, that binding's encrypted
 * bookkeeping, the final send (logout or user Unlink) and the session forget after an erasure. No generic path, signer
 * or credential surface: ZenlyPro (its private CustomerBoundary) signs each attempt with the freshest
 * stored credential of the SAME identity and generation the call was made under (restamped at actual
 * send). The five device credentials travel only as headers; a body that is not one strict JSON object,
 * or whose top level carries a reserved credential key, is never sent. Profile routes run on the
 * customer lane, account/delete on the user lane (never queued behind an upload). Each attempt is one
 * bounded ProTransport.customer exchange; at most two attempts (the second only after a 401 clock_skew
 * carrying a valid server_time); never a sleep or a wait. A profile sync or an events batch carries the
 * caller's {@link Allowed} rule, asked on the customer worker right before each attempt is signed: a
 * call that waited there past a withdrawal, deletion, binding loss or logout is refused (NOT_ALLOWED),
 * nothing signed or sent (contract §6.7). A 429 installs the install-wide cooldown of
 * its route (the server counts per device, not per account). Only a real 401 (not clock_skew or
 * nonce_replayed) applies the existing session handling, and only to the exact credential that attempt
 * was signed with; ZenlyPro then reports the binding loss with the answer's code and reason.
 *
 * Callbacks run on the UI thread, outside the Pro lock, and only while the slot is still exactly the
 * identity the call was made for (else STALE; the one exception is the UNAUTHORIZED reply this call
 * itself caused). Nothing here writes diagnostics: bodies, tokens and answers leave this class only as
 * the typed Reply.
 */
public final class CustomerApi {
    static final String ROUTE_CONSENTS = "profile/consents", ROUTE_SYNC = "profile/sync", ROUTE_ME = "profile/me",
            ROUTE_EVENTS = "profile/events", ROUTE_DELETE = "account/delete";
    /** Request bounds (contract §2.4: consents and sync 16 KiB, events 256 KiB); the delete body is fixed and tiny. */
    static final int MAX_CONSENTS_BYTES = 16384, MAX_SYNC_BYTES = 16384, MAX_EVENTS_BYTES = 262144, MAX_DELETE_BYTES = 1024;
    /** Longest Retry-After passed on: the 6 h backoff ceiling. */
    static final int MAX_RETRY_AFTER_SECONDS = 21600;
    /** profile_min_interval_s until the server named one (contract §2.4.3). */
    public static final int DEFAULT_MIN_INTERVAL_SECONDS = 21600;
    /** The final send (logout or user Unlink) carries at most two profile/consents bodies (each at most ten withdrawals). */
    public static final int MAX_FINAL_BODIES = 2;
    /** Top-level body keys the server's device guards read as credentials (contract §0.1): never in a body. */
    static final Set<String> RESERVED_BODY_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "refresh_token", "device_id", "nonce", "ts", "sig")));
    static final String DELETE_BODY = "{\"schema_version\":1,\"confirm\":\"delete_account\"}";
    /** contract §0.3 error_code: a stable code, never free text. */
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    private static final Pattern UUID4 = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    /**
     * The current Telegram user of the slot and its binding at the configured origin. principal (the
     * backend account id) and bindingId (the device binding) are null while no usable linked session
     * exists (not linked, held or unconfigured). generation is a process-local fence (it changes on
     * identity, binding or configuration changes): compare it, never persist it.
     */
    public static final class Identity {
        public final long telegramUserId;
        public final String principal;
        public final String bindingId;
        public final long generation;

        Identity(long telegramUserId, String principal, String bindingId, long generation) {
            this.telegramUserId = telegramUserId;
            this.principal = principal;
            this.bindingId = principal == null ? null : bindingId;
            this.generation = generation;
        }

        public boolean isLinked() { return principal != null; }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Identity)) return false;
            Identity that = (Identity) other;
            return telegramUserId == that.telegramUserId && generation == that.generation
                    && (principal == null ? that.principal == null : principal.equals(that.principal))
                    && (bindingId == null ? that.bindingId == null : bindingId.equals(that.bindingId));
        }

        @Override public int hashCode() {
            int hash = (int) (telegramUserId * 31 + generation);
            hash = hash * 31 + (principal == null ? 0 : principal.hashCode());
            return hash * 31 + (bindingId == null ? 0 : bindingId.hashCode());
        }
    }

    public enum Kind {
        /** 200 whose body is exactly one JSON object (Reply.body), confirmed against the current credential. */
        OK,
        /** A real 401: the existing Pro handling cleared exactly the credential sent and reported the binding loss. */
        UNAUTHORIZED,
        /** 403: consent_required (see reason), profile_account_not_linked or another refusal. */
        FORBIDDEN,
        /** 404, e.g. a server without the route yet. */
        NOT_FOUND,
        /** 409: profile_account_mismatch, idempotency_in_flight, ... */
        CONFLICT,
        /** 413, or a body above the route's bound (refused locally: status 0, nothing sent). */
        TOO_LARGE,
        /** 422 and every other 4xx. */
        INVALID,
        /** 429, or the route's running install-wide cooldown; see retryAfterSeconds. */
        RATE_LIMITED,
        /** 5xx, a 401 nonce_replayed (credential kept) or an unreadable or unexpected answer: retry with backoff. */
        UNAVAILABLE,
        /** No answer (DNS, TLS, connect, timeout, reset); possiblySent tells whether the request may have arrived. */
        OFFLINE,
        /**
         * The expected identity is no longer current (account switch, logout, relink, credential rotation,
         * configuration) at call, send, completion or delivery: nothing of the answer is reported.
         */
        STALE,
        /**
         * Refused before sending (not linked, held or unconfigured, signed out, storage, rotation pending,
         * invalid argument or body), or the device clock (a second clock_skew).
         */
        LOCAL,
        /**
         * The caller's {@link Allowed} rule refused the call on the worker, right before it was signed: nothing
         * was signed or sent. Not a failure of the route: never a backoff, a receipt or an error count.
         */
        NOT_ALLOWED
    }

    /** One call's outcome; carries no credential and no server message. */
    public static final class Reply {
        /** HTTP status of the answer; 0 when none was received and for every local verdict. */
        public final int status;
        /**
         * Null exactly for OK. Otherwise the answer's error code (contract §0.1, §2.1) when it carried a
         * valid one, else a local code: rate_limited, request_rejected (4xx), server_error (5xx),
         * invalid_response (unreadable or unexpected answer), connection_failed, session_changed,
         * link_required, not_configured, login_required, storage_unavailable, rotation_pending,
         * invalid_request, payload_too_large, clock_skew or not_allowed (NOT_ALLOWED).
         */
        public final String error;
        /** The error body's reason (401 account_erased; 403 consent_required no_consent|withdrawn|reconsent_required), else null. */
        public final String reason;
        /** Retry-After of the answer (0..MAX_RETRY_AFTER_SECONDS); at least 1 for RATE_LIMITED. */
        public final int retryAfterSeconds;
        /** The bounded answer text (OK: one JSON object; server errors: their envelope with its extras); else null. */
        public final String body;
        public final Kind kind;
        /**
         * The server may have processed the request: an HTTP status was received on some attempt of this
         * call, or the transport failed after the connection was established. False for a refusal before
         * sending and for a DNS, TLS or connect failure.
         */
        public final boolean possiblySent;

        Reply(int status, String error, String reason, int retryAfterSeconds, String body, Kind kind, boolean possiblySent) {
            this.status = status;
            this.error = error;
            this.reason = reason;
            this.retryAfterSeconds = Math.max(0, Math.min(MAX_RETRY_AFTER_SECONDS, retryAfterSeconds));
            this.body = body;
            this.kind = kind;
            this.possiblySent = possiblySent;
        }

        /** This reply, marked possibly sent when an earlier step of the same call received a status. */
        Reply possibly(boolean sent) {
            return sent && !possiblySent ? new Reply(status, error, reason, retryAfterSeconds, body, kind, true) : this;
        }

        @Override public String toString() { return "CustomerApi.Reply{" + kind + "," + status + "," + error + "}"; }
    }

    /** Runs on the UI thread, outside the Pro lock, exactly once per call. */
    public interface Callback { void done(Reply reply); }

    /**
     * Whether the call may still go out. Asked on the customer worker right before each attempt is signed, so it
     * reads only published state (any thread); false, or a throw, refuses the call as NOT_ALLOWED.
     */
    public interface Allowed { boolean allowed(); }

    // ---- Public facade ----------------------------------------------------------------------

    private final Boundary boundary;

    CustomerApi(Boundary boundary) { this.boundary = boundary; }

    public static CustomerApi forAccount(int account) { return new CustomerApi(ZenlyPro.get(account).customerBoundary()); }

    /** Current identity of the slot; pass it unchanged as {@code expected} to the calls below. */
    public Identity identity() { return boundary.identity(); }

    /**
     * POST /v1/profile/consents with exactly {@code body} (one JSON object, at most 16 KiB). No rule: it delivers
     * withdrawals too.
     */
    public void postConsents(Identity expected, byte[] body, Callback done) {
        submit(expected, new Request(ROUTE_CONSENTS, "POST", copy(body), null, Lane.CUSTOMER, MAX_CONSENTS_BYTES, null), done);
    }

    /**
     * POST /v1/profile/sync with exactly {@code body} (one JSON object, at most 16 KiB), signed only while
     * {@code stillAllowed} holds on the worker (else NOT_ALLOWED).
     */
    public void postProfileSync(Identity expected, byte[] body, Allowed stillAllowed, Callback done) {
        if (stillAllowed == null) throw new IllegalArgumentException("Missing customer rule");
        submit(expected, new Request(ROUTE_SYNC, "POST", copy(body), null, Lane.CUSTOMER, MAX_SYNC_BYTES, stillAllowed), done);
    }

    /** {@link #postProfileSync(Identity, byte[], Allowed, Callback)} without a rule: the exchange host tests only. */
    void postProfileSync(Identity expected, byte[] body, Callback done) {
        submit(expected, new Request(ROUTE_SYNC, "POST", copy(body), null, Lane.CUSTOMER, MAX_SYNC_BYTES, null), done);
    }

    /** GET /v1/profile/me (no body). */
    public void getProfileMe(Identity expected, Callback done) {
        submit(expected, new Request(ROUTE_ME, "GET", null, null, Lane.CUSTOMER, 0, null), done);
    }

    /**
     * POST /v1/profile/events with exactly {@code body} (one batch envelope, at most 256 KiB), signed only while
     * {@code stillAllowed} holds on the worker (else NOT_ALLOWED).
     */
    public void postEvents(Identity expected, byte[] body, Allowed stillAllowed, Callback done) {
        if (stillAllowed == null) throw new IllegalArgumentException("Missing customer rule");
        submit(expected, new Request(ROUTE_EVENTS, "POST", copy(body), null, Lane.CUSTOMER, MAX_EVENTS_BYTES, stillAllowed), done);
    }

    /** {@link #postEvents(Identity, byte[], Allowed, Callback)} without a rule: the exchange host tests only. */
    void postEvents(Identity expected, byte[] body, Callback done) {
        submit(expected, new Request(ROUTE_EVENTS, "POST", copy(body), null, Lane.CUSTOMER, MAX_EVENTS_BYTES, null), done);
    }

    /**
     * POST /v1/account/delete {"schema_version":1,"confirm":"delete_account"} on the user lane.
     * {@code idempotencyKey} is the caller's persisted uuid4: every attempt and every retry of this
     * deletion sends it unchanged as Idempotency-Key.
     */
    public void deleteAccount(Identity expected, String idempotencyKey, Callback done) {
        if (idempotencyKey == null || !UUID4.matcher(idempotencyKey).matches()) {
            refuse(done, local(Kind.LOCAL, "invalid_request"));
            return;
        }
        submit(expected, new Request(ROUTE_DELETE, "POST", DELETE_BODY.getBytes(StandardCharsets.UTF_8), idempotencyKey,
                Lane.USER, MAX_DELETE_BYTES, null), done);
    }

    // ---- Encrypted-session bookkeeping of the same binding (no network) -------------------------

    /** profile_sync_sha256 of exactly {@code expected}'s binding; null when never synced or the identity moved on. */
    public String profileDigest(Identity expected) {
        Bookkeeping current = bookkeeping(expected);
        return current == null ? null : current.digest;
    }

    /** profile_synced_at (epoch seconds) of exactly {@code expected}'s binding; 0 when never or the identity moved on. */
    public long profileSyncedAt(Identity expected) {
        Bookkeeping current = bookkeeping(expected);
        return current == null ? 0 : current.syncedAt;
    }

    /** profile_min_interval_s of exactly {@code expected}'s binding; DEFAULT_MIN_INTERVAL_SECONDS when none. */
    public int profileMinIntervalSeconds(Identity expected) {
        Bookkeeping current = bookkeeping(expected);
        return current == null ? DEFAULT_MIN_INTERVAL_SECONDS : current.minIntervalSeconds;
    }

    /** Records a successful sync of exactly {@code expected}'s binding; false when the identity moved on, an argument is invalid or storage failed. */
    public boolean recordProfileSync(Identity expected, String digest, long syncedAtEpochS, int minIntervalS) {
        if (digest == null || !HEX64.matcher(digest).matches() || syncedAtEpochS <= 0 || minIntervalS < 0) return false;
        return update(expected, current -> new Bookkeeping(digest, syncedAtEpochS, minIntervalS, current.stopped));
    }

    /** 403 profile_account_not_linked: profile traffic of this binding stops (a relink starts a new session). */
    public boolean markProfileStopped(Identity expected) {
        return update(expected, current -> current.stopped ? current
                : new Bookkeeping(current.digest, current.syncedAt, current.minIntervalSeconds, true));
    }

    public boolean profileStopped(Identity expected) {
        Bookkeeping current = bookkeeping(expected);
        return current != null && current.stopped;
    }

    /**
     * A withdrawal, here or on another device (the server deletes the profile, contract §9.3): exactly
     * {@code expected}'s binding forgets its last sync (digest and time; min_interval_s and a stop stay), so the next
     * grant syncs the profile again before events or Tasks (contract §6.4). False when the identity moved on or storage
     * failed.
     */
    public boolean clearProfileSync(Identity expected) {
        return update(expected, current -> current.digest == null && current.syncedAt == 0 ? current
                : new Bookkeeping(null, 0, current.minIntervalSeconds, current.stopped));
    }

    /**
     * After a confirmed erasure (account/delete done, or a 401 whose reason is account_erased): deletes
     * the current origin's Pro session of {@code telegramUserId}, its bookkeeping included, without
     * bind/revoke (the server binding no longer exists). No network; reports no binding change.
     */
    public boolean forgetSessionAfterErasure(long telegramUserId) {
        return telegramUserId > 0 && boundary.forgetSession(telegramUserId);
    }

    /**
     * The final send at a logout or a user Unlink (the user's pending withdrawals, before ZenlyPro.unlink revokes the binding).
     * Synchronously, under the Pro lock, captures exactly {@code expected}'s configuration, device id
     * and refresh token, and enqueues ONE task on the user lane before returning, so it runs before the
     * bind/revoke that unlink() enqueues afterwards on the same FIFO lane. The task signs each body at
     * its own send time (own nonce) and posts it to profile/consents; every outcome is ignored (no state
     * change, no cooldown, no callback). Returns whether the task was queued: false for an identity that
     * moved on or is not linked, a held or unconfigured build, or invalid bodies (1..MAX_FINAL_BODIES
     * strict JSON objects of at most 16 KiB without a reserved key).
     */
    public boolean queueFinal(Identity expected, List<byte[]> consentBodies) {
        if (expected == null || !expected.isLinked() || consentBodies == null || consentBodies.isEmpty()
                || consentBodies.size() > MAX_FINAL_BODIES) return false;
        final List<byte[]> bodies = new ArrayList<>(consentBodies.size());
        for (byte[] body : consentBodies) {
            if (body == null || refusal(body, MAX_CONSENTS_BYTES) != null) return false;
            bodies.add(body.clone());
        }
        final Object captured;
        try { captured = boundary.captureFinal(expected); }
        catch (Stop refused) { return false; }
        try {
            boundary.execute(Lane.USER, () -> {
                for (byte[] body : bodies) {
                    try { boundary.send(boundary.signFinal(captured, body)); }
                    catch (Exception ignored) { /* Best effort before the revoke: nothing is recorded either way. */ }
                }
            });
            return true;
        } catch (RuntimeException notQueued) {
            return false;
        }
    }

    private Bookkeeping bookkeeping(Identity expected) {
        return expected == null || !expected.isLinked() ? null : boundary.bookkeeping(expected);
    }

    private boolean update(Identity expected, BookkeepingChange change) {
        return expected != null && expected.isLinked() && boundary.updateBookkeeping(expected, change);
    }

    private void submit(Identity expected, Request request, Callback done) {
        if (done == null) throw new IllegalArgumentException("Missing customer callback");
        if (expected == null || (request.body == null) != "GET".equals(request.method)) {
            refuse(done, local(Kind.LOCAL, "invalid_request"));
            return;
        }
        if (!expected.isLinked()) {
            refuse(done, local(Kind.LOCAL, "link_required"));
            return;
        }
        final Object ticket;
        try {
            // Atomically under the Pro lock: the slot is still exactly `expected`; its stamp is captured.
            ticket = boundary.capture(expected);
        } catch (Stop stale) {
            refuse(done, stale.reply);
            return;
        }
        try {
            boundary.execute(request.lane, () -> {
                Reply reply;
                try { reply = Exchange.run(boundary, ticket, request); }
                catch (RuntimeException unexpected) { reply = local(Kind.OFFLINE, "connection_failed"); }
                final Reply result = reply;
                boundary.deliver(() -> done.done(fenced(expected, result)));
            });
        } catch (RuntimeException notQueued) {
            refuse(done, local(Kind.LOCAL, "storage_unavailable"));
        }
    }

    private void refuse(Callback done, Reply reply) {
        if (done == null) throw new IllegalArgumentException("Missing customer callback");
        boundary.deliver(() -> done.done(reply));
    }

    /**
     * Last fence, on the delivering thread right before the callback: a reply reaches the caller only
     * while the slot is still exactly the identity it was requested for. The one exception is the
     * UNAUTHORIZED reply this call itself caused: same Telegram user and generation, binding cleared.
     * A withheld reply keeps possiblySent (a deletion must know whether it may have been processed).
     */
    private Reply fenced(Identity expected, Reply reply) {
        Identity now = boundary.identity();
        if (expected.equals(now)) return reply;
        if (reply.kind == Kind.UNAUTHORIZED && now.principal == null && now.telegramUserId == expected.telegramUserId
                && now.generation == expected.generation) {
            return reply;
        }
        return local(Kind.STALE, "session_changed").possibly(reply.possiblySent);
    }

    private static byte[] copy(byte[] body) { return body == null ? null : body.clone(); }

    // ---- Bounded exchange state machine (package-private, host-tested) ------------------------

    /** The lane a call runs on: CUSTOMER = ZenlyPro's customer worker, USER = its user worker (manual link, entitlement, revoke). */
    enum Lane { CUSTOMER, USER }

    /** One named customer call; constructed only by the public methods above. */
    static final class Request {
        final String route, method, idempotencyKey;
        final byte[] body;
        final Lane lane;
        final int maxBodyBytes;
        /** The caller's rule, asked before every attempt is signed; null = none. */
        final Allowed allowed;

        Request(String route, String method, byte[] body, String idempotencyKey, Lane lane, int maxBodyBytes, Allowed allowed) {
            this.route = route;
            this.method = method;
            this.body = body;
            this.idempotencyKey = idempotencyKey;
            this.lane = lane;
            this.maxBodyBytes = maxBodyBytes;
            this.allowed = allowed;
        }
    }

    /** A classified refusal from the Pro side of the boundary. */
    static final class Stop extends Exception {
        private static final long serialVersionUID = 1L;
        final transient Reply reply;

        Stop(Reply reply) {
            super(reply.error, null, false, false);
            this.reply = reply;
        }
    }

    /** The customer-data bookkeeping of one binding's encrypted Pro session; immutable. */
    static final class Bookkeeping {
        /** profile_sync_sha256: lowercase hex SHA-256 of the last synced snapshot, or null. */
        final String digest;
        /** profile_synced_at: epoch seconds of the last successful sync; 0 = never. */
        final long syncedAt;
        /** profile_min_interval_s: the server's last min_interval_s. */
        final int minIntervalSeconds;
        /** profile_stopped: a 403 profile_account_not_linked stopped this binding's profile traffic. */
        final boolean stopped;

        Bookkeeping(String digest, long syncedAt, int minIntervalSeconds, boolean stopped) {
            this.digest = digest;
            this.syncedAt = syncedAt;
            this.minIntervalSeconds = minIntervalSeconds;
            this.stopped = stopped;
        }
    }

    /** One change of a binding's bookkeeping, applied atomically under the Pro lock; null refuses it. */
    interface BookkeepingChange { Bookkeeping apply(Bookkeeping current); }

    /**
     * ZenlyPro's side (opaque ticket, attempt and capture objects; credentials never leave it). Every
     * method except execute, deliver and send runs under the Pro lock.
     */
    interface Boundary {
        Identity identity();
        /**
         * Atomically: requires the slot to be exactly {@code expected} (Telegram user, linked principal,
         * binding, generation) and captures its stamp (identity + generation + credential).
         */
        Object capture(Identity expected) throws Stop;
        /** CUSTOMER: the customer worker; USER: the user worker. */
        void execute(Lane lane, Runnable work);
        /** The UI thread. */
        void deliver(Runnable callback);
        /**
         * Restamps the ticket (same identity and generation, freshest stored credential) and refuses when
         * held, unconfigured, signed out, not linked, the binding changed, a rotation is pending (kicking
         * at most one refresh a minute) or the route's install-wide cooldown runs; then signs fresh device
         * headers (Authorization, X-Device-Id/Nonce/Ts/Sig; Idempotency-Key only for account/delete).
         */
        Object prepare(Object ticket, String route, String method, byte[] body, String idempotencyKey) throws Stop;
        /** One bounded network attempt (ProTransport.customer) of a prepared or final attempt, exactly as signed. */
        ProTransport.CustomerReply send(Object attempt) throws Exception;
        /** Validated server time (Unix seconds, 0 = none) and a 429 cooldown (retryAfter >= 0) of the attempt's route. */
        void settle(Object attempt, long serverTimeSeconds, int retryAfterSeconds);
        /** The attempt's identity, generation, binding and exact credential are still current. */
        void confirm(Object attempt) throws Stop;
        /**
         * Real 401: the existing session handling for exactly the attempt's credential, if still current,
         * reporting the binding loss with the answer's code and reason (null when it carried none).
         */
        void invalidate(Object attempt, String code, String reason) throws Stop;
        /**
         * The final send (logout or user Unlink): exactly {@code expected}'s configuration, device id and refresh token
         * (not when held or unconfigured).
         */
        Object captureFinal(Identity expected) throws Stop;
        /**
         * At send time: signs one profile/consents attempt of {@code body} with the captured credential and
         * a fresh nonce; refused once the origin changed or while the route cools down. Returns a sendable attempt.
         */
        Object signFinal(Object captured, byte[] body) throws Exception;
        /** Exactly {@code expected}'s bookkeeping while it is the slot's current linked identity, else null. */
        Bookkeeping bookkeeping(Identity expected);
        /** Applies {@code change} to exactly {@code expected}'s bookkeeping and saves it; false when it moved on, was refused or storage failed. */
        boolean updateBookkeeping(Identity expected, BookkeepingChange change);
        /** Deletes the current origin's session file of {@code telegramUserId} (no bind/revoke); false when storage failed. */
        boolean forgetSession(long telegramUserId);
    }

    static final class Exchange {
        private Exchange() { }

        /** At most two attempts (the second only after a 401 clock_skew carrying a valid server_time). */
        static Reply run(Boundary boundary, Object ticket, Request request) {
            Reply refused = refusal(request.body, request.maxBodyBytes);
            if (refused != null) return refused;
            boolean sent = false;
            for (int attempt = 0; ; attempt++) {
                // On the worker, right before anything is signed: the call may have waited here behind others, and what
                // happened meanwhile (a withdrawal, deletion, binding loss or logout) decides whether it still goes out.
                if (!allowed(request.allowed)) return local(Kind.NOT_ALLOWED, "not_allowed").possibly(sent);
                final Object signed;
                try { signed = boundary.prepare(ticket, request.route, request.method, request.body, request.idempotencyKey); }
                catch (Stop stop) { return stop.reply.possibly(sent); }

                final ProTransport.CustomerReply answer;
                try {
                    answer = boundary.send(signed);
                } catch (ProTransport.CustomerFailure failure) {
                    boolean possibly = sent || failure.connected;
                    return failure.unreadable() ? new Reply(0, "invalid_response", null, 0, null, Kind.UNAVAILABLE, possibly)
                            : new Reply(0, "connection_failed", null, 0, null, Kind.OFFLINE, possibly);
                } catch (Exception network) {
                    return new Reply(0, "connection_failed", null, 0, null, Kind.OFFLINE, sent);
                }
                sent = true;

                if (answer.status == 429) {
                    int wait = Math.max(1, Math.min(MAX_RETRY_AFTER_SECONDS, answer.retryAfter));
                    boundary.settle(signed, 0, wait);
                    return confirmed(boundary, signed, new Reply(429, "rate_limited", null, wait, null, Kind.RATE_LIMITED, true));
                }
                Envelope envelope = Envelope.of(answer.body);
                // A replayed (stored) answer carries a historical server_time: it never moves the clock.
                long serverTime = envelope == null || answer.replayed ? 0 : envelope.serverTime;
                boundary.settle(signed, serverTime, -1);
                if (answer.status == 200) {
                    Reply reply = envelope == null ? new Reply(200, "invalid_response", null, 0, null, Kind.UNAVAILABLE, true)
                            : new Reply(200, null, null, 0, answer.body, Kind.OK, true);
                    return confirmed(boundary, signed, reply);
                }
                String code = envelope == null ? null : envelope.error, reason = envelope == null ? null : envelope.reason;
                int retryAfter = answer.retryAfter > 0 ? answer.retryAfter : envelope == null ? 0 : envelope.retryAfter;
                if (answer.status == 401 && code != null) {
                    if ("clock_skew".equals(code)) {
                        // The existing single correction: settle noted the server time; re-prepare re-reads the credential and re-signs.
                        if (attempt == 0 && serverTime > 0) continue;
                        return confirmed(boundary, signed, new Reply(401, code, reason, 0, answer.body, Kind.LOCAL, true));
                    }
                    if ("nonce_replayed".equals(code)) {
                        return confirmed(boundary, signed, new Reply(401, code, reason, retryAfter, answer.body, Kind.UNAVAILABLE, true));
                    }
                    try { boundary.invalidate(signed, code, reason); }
                    catch (Stop stale) { return stale.reply.possibly(true); }
                    return new Reply(401, code, reason, 0, answer.body, Kind.UNAUTHORIZED, true);
                }
                return confirmed(boundary, signed, classify(answer.status, code, reason, retryAfter, answer.body));
            }
        }

        /** A failure or success counts only while the attempt's exact credential is current; else STALE. */
        private static Reply confirmed(Boundary boundary, Object signed, Reply reply) {
            try {
                boundary.confirm(signed);
                return reply;
            } catch (Stop stale) {
                return stale.reply.possibly(true);
            }
        }

        /** The caller's rule (none: allowed); a rule that throws refuses. */
        private static boolean allowed(Allowed rule) {
            if (rule == null) return true;
            try {
                return rule.allowed();
            } catch (RuntimeException unknown) {
                return false;
            }
        }
    }

    /**
     * Every answer except 200, 429 and a 401 with a valid code, by status. A 401 without a valid code
     * (a proxy or framework page) clears nothing; 1xx, other 2xx and 3xx (never followed) are unexpected.
     */
    static Reply classify(int status, String code, String reason, int retryAfter, String body) {
        Kind kind;
        if (status == 403) kind = Kind.FORBIDDEN;
        else if (status == 404) kind = Kind.NOT_FOUND;
        else if (status == 409) kind = Kind.CONFLICT;
        else if (status == 413) kind = Kind.TOO_LARGE;
        else if (status >= 400 && status < 500 && status != 401 && status != 429) kind = Kind.INVALID;
        else if (status >= 500 && status < 600) kind = Kind.UNAVAILABLE;
        else return new Reply(status, "invalid_response", null, retryAfter, null, Kind.UNAVAILABLE, true);
        String error = code != null ? code : status >= 500 ? "server_error" : "request_rejected";
        return new Reply(status, error, reason, retryAfter, body, kind, true);
    }

    /** Pro-side refusals (restamp, session, configuration, storage, rotation, local cooldown) mapped to the allowlist. */
    static Stop stopFor(String proCode, int retryAfter) {
        if ("rate_limited".equals(proCode)) {
            return new Stop(new Reply(0, "rate_limited", null, Math.max(1, retryAfter), null, Kind.RATE_LIMITED, false));
        }
        if ("link_required".equals(proCode) || "not_configured".equals(proCode) || "login_required".equals(proCode)
                || "storage_unavailable".equals(proCode) || "rotation_pending".equals(proCode)) {
            return new Stop(local(Kind.LOCAL, proCode));
        }
        return new Stop(local(Kind.STALE, "session_changed"));
    }

    /**
     * Why a request body is never sent, or null to send it: above the route's bound, not exactly one
     * strict JSON object (strict UTF-8, no duplicate top-level key), or a reserved credential key at its
     * top level (contract §0.1: the server's guards fall back to body credentials).
     */
    static Reply refusal(byte[] body, int maxBytes) {
        if (body == null) return null;
        if (body.length > maxBytes) return local(Kind.TOO_LARGE, "payload_too_large");
        Envelope object = Envelope.of(utf8(body));
        if (object == null) return local(Kind.LOCAL, "invalid_request");
        for (String key : RESERVED_BODY_KEYS) {
            if (object.keys.contains(key)) return local(Kind.LOCAL, "invalid_request");
        }
        return null;
    }

    /** A stable error code (contract §0.3 error_code), else null; also what ZenlyPro reports as a binding loss. */
    static String wireCode(String value) {
        return value != null && CODE.matcher(value).matches() ? value : null;
    }

    private static Reply local(Kind kind, String code) { return new Reply(0, code, null, 0, null, kind, false); }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            return null;
        }
    }

    /**
     * The parts of one strict JSON object this client reads (its top-level keys; error, reason and
     * server_time strings; a numeric retry_after); null for anything else: not exactly one object, a
     * duplicate top-level key, or RFC 8259 violations. Never the message.
     */
    static final class Envelope {
        final Set<String> keys;
        /** Valid error_code values only. */
        final String error, reason;
        /** Validated instant in Unix seconds; 0 = none. */
        final long serverTime;
        final int retryAfter;

        private Envelope(Set<String> keys, String error, String reason, long serverTime, int retryAfter) {
            this.keys = keys;
            this.error = error;
            this.reason = reason;
            this.serverTime = serverTime;
            this.retryAfter = retryAfter;
        }

        static Envelope of(String text) {
            // JsonReader would skip a leading byte order mark; RFC 8259 text has none.
            if (text == null || text.isEmpty() || text.charAt(0) == '\uFEFF') return null;
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            try {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) return null;
                reader.beginObject();
                Set<String> keys = new HashSet<>();
                String error = null, reason = null, time = null, retry = null;
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!keys.add(name)) return null; // Parsers disagree on duplicates: never trust such a body.
                    JsonToken token = reader.peek();
                    if (token == JsonToken.STRING && "error".equals(name)) error = reader.nextString();
                    else if (token == JsonToken.STRING && "reason".equals(name)) reason = reader.nextString();
                    else if (token == JsonToken.STRING && "server_time".equals(name)) time = reader.nextString();
                    else if (token == JsonToken.NUMBER && "retry_after".equals(name)) retry = reader.nextString();
                    else reader.skipValue();
                }
                reader.endObject();
                if (reader.peek() != JsonToken.END_DOCUMENT) return null;
                return new Envelope(Collections.unmodifiableSet(keys), wireCode(error), wireCode(reason), ProPolicy.utc(time), seconds(retry));
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }

        private static int seconds(String literal) {
            if (literal == null || !literal.matches("[0-9]{1,9}")) return 0;
            return Math.min(MAX_RETRY_AFTER_SECONDS, Integer.parseInt(literal));
        }
    }
}
