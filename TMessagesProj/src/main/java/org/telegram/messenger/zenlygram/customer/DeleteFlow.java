package org.telegram.messenger.zenlygram.customer;

import java.util.Map;

/**
 * The in-app account deletion's decisions (contract §2.2.5, §6.8, amendment A3; design 50 §12), pure and host-tested.
 * ZenlyCustomer persists the {@link Record} ({@code delete.v1.<tg>@<authority>} in {@code zenlygram_customer}, with
 * {@code commit()} before the first send), sends {@code POST /v1/account/delete} on the user lane with the record's key
 * (every retry reuses it) and asks this class what each answer means:
 * <ul>
 * <li><b>done</b>: a 2xx; a 401 whose reason is {@code account_erased}; a 401 {@code device_revoked} /
 * {@code session_revoked} / {@code invalid_token} after an EARLIER attempt of this key may have been processed (the
 * sent delete removed the session); a binding loss of this user while an attempt may have been processed;</li>
 * <li><b>failed</b>: a 401 when no earlier attempt may have been processed, or any other 401 code (nothing says the
 * server erased anything, and the binding is gone); 422 and every other refusal (a 4xx but 404, 409 and 429);</li>
 * <li>409 {@code idempotency_in_flight}: again after Retry-After or 30 s, then 60 s (at most two automatic retries:
 * the route allows three per hour), then UNAVAILABLE;</li>
 * <li>5xx, 404 (a server without the route), a timeout or reset after connecting, a second {@code clock_skew} or a
 * {@code nonce_replayed}: one automatic retry after 30 s, then UNAVAILABLE (the user retries with the same key);</li>
 * <li>429 or the route's running cooldown → RATE_LIMITED; no connection at all → OFFLINE (nothing was sent);</li>
 * <li>unlinked: nothing possibly processed → NOT_LINKED (the web page; the record goes), else FAILED.</li>
 * </ul>
 * "May have been processed" ({@code possibly_processed}) is set by an answer that does not prove a refusal: a 2xx, a
 * 409, a 5xx, a transport failure after the connection was established, or a reply withheld because the identity moved
 * on while the request may have arrived. A 401 / 422 / 429 / another 4xx is answered before the erasure runs (the route's
 * ThrottlePerDevice and DeviceAuth come first), so it never sets it.
 */
public final class DeleteFlow {
    /** 409 {@code idempotency_in_flight}: the first automatic retry (without a Retry-After), then the second. */
    public static final int CONFLICT_FIRST_RETRY_SECONDS = 30, CONFLICT_SECOND_RETRY_SECONDS = 60;
    public static final int MAX_CONFLICT_RETRIES = 2;
    /** 5xx / timeout: one automatic retry after this long. */
    public static final int UNAVAILABLE_RETRY_SECONDS = 30;
    public static final int MAX_UNAVAILABLE_RETRIES = 1;
    /** Longest Retry-After honoured automatically. */
    public static final int MAX_RETRY_AFTER_SECONDS = 300;

    private DeleteFlow() {}

    public enum State {
        /** A deletion runs or waits for the user's retry: nothing else is sent, recorded or linked for the account. */
        PENDING("pending"),
        /** It definitely did not erase anything (the screen names the web page); the account works as before. */
        FAILED("failed"),
        /**
         * The server erased the account and the local done sequence ran; the record goes (and the consent record with
         * it) once the user dismissed the confirmation, or at the next launch.
         */
        DONE("done");

        public final String wire;

        State(String wire) { this.wire = wire; }

        public static State of(String wire) {
            for (State state : values()) {
                if (state.wire.equals(wire)) return state;
            }
            return null;
        }
    }

    /**
     * {@code delete.v1.<tg>@<authority>}:
     * {@code {"v":1,"key":"<uuid4>","state":"pending|failed|done","possibly_processed":false,"attempts":0,"requested_at":…}}.
     */
    public static final class Record {
        public static final int FORMAT = 1;

        /** The Idempotency-Key of every attempt of this deletion (uuid4). */
        public final String key;
        public final State state;
        /** An attempt of this key may have been processed by the server. */
        public final boolean possiblyProcessed;
        /** Attempts sent so far. */
        public final int attempts;
        /** Trusted epoch seconds when the user confirmed the deletion. */
        public final long requestedAt;

        private Record(String key, State state, boolean possiblyProcessed, int attempts, long requestedAt) {
            this.key = key;
            this.state = state;
            this.possiblyProcessed = possiblyProcessed;
            this.attempts = Math.max(0, attempts);
            this.requestedAt = Math.max(0, Math.min(requestedAt, CustomerJson.MAX_INSTANT));
        }

        /** A new pending deletion with {@code key} (a fresh uuid4) confirmed at {@code now}. */
        public static Record start(String key, long now) {
            if (!CustomerJson.isUuid4(key)) throw new IllegalArgumentException("key");
            return new Record(key, State.PENDING, false, 0, now);
        }

        /**
         * A record that could not be read: fail closed as a pending deletion whose earlier attempt may have been processed
         * (nothing more is uploaded or linked; a retry with {@code key} completes it on a 401).
         */
        public static Record unreadable(String key, long now) {
            if (!CustomerJson.isUuid4(key)) throw new IllegalArgumentException("key");
            return new Record(key, State.PENDING, true, 0, now);
        }

        Record with(State next) { return new Record(key, next, possiblyProcessed, attempts, requestedAt); }

        Record sent(boolean processed) {
            return new Record(key, state, possiblyProcessed || processed, attempts >= 1_000_000 ? attempts : attempts + 1, requestedAt);
        }

        /** Nothing is uploaded, recorded or linked for the account while this record exists in this state. */
        public boolean blocks() { return state == State.PENDING || state == State.DONE; }

        public String json() {
            return new CustomerJson.Obj().put("v", FORMAT).put("key", key).put("state", state.wire).put("possibly_processed", possiblyProcessed)
                    .put("attempts", attempts).put("requested_at", requestedAt).toString();
        }

        /** The record of a parsed stored object, or null when it is not one. */
        public static Record fromTree(Object tree) {
            Map<String, Object> object = CustomerJson.object(tree);
            if (object == null) return null;
            Long format = CustomerJson.integral(object.get("v"));
            String key = CustomerJson.string(object.get("key"));
            State state = State.of(CustomerJson.string(object.get("state")));
            Object processed = object.get("possibly_processed");
            Long attempts = CustomerJson.integral(object.get("attempts"));
            Long requestedAt = CustomerJson.integral(object.get("requested_at"));
            if (format == null || format != FORMAT || !CustomerJson.isUuid4(key) || state == null || !(processed instanceof Boolean)
                    || attempts == null || attempts < 0 || requestedAt == null || requestedAt < 0) {
                return null;
            }
            return new Record(key, state, (Boolean) processed, (int) Math.min(attempts, Integer.MAX_VALUE), requestedAt);
        }

        @Override public boolean equals(Object other) { return other instanceof Record && ((Record) other).json().equals(json()); }

        @Override public int hashCode() { return json().hashCode(); }

        @Override public String toString() { return json(); }
    }

    /** One answer of a deletion attempt, as the glue reads CustomerApi.Reply. */
    public static final class Answer {
        /** How the reply came about. */
        public enum Transport {
            /** An HTTP status was received ({@link #status}). */
            ANSWERED,
            /** No answer: DNS, TLS, connect, or a timeout / reset after connecting ({@link #possiblySent}). */
            OFFLINE,
            /** Refused before sending ({@link #error}: link_required, rate_limited, rotation_pending, …), or a second clock_skew. */
            LOCAL,
            /** Withheld: the identity moved on (logout, relink, rotation) while the call ran. */
            STALE
        }

        public final Transport transport;
        /** HTTP status; 0 when none was received. */
        public final int status;
        public final String error;
        public final String reason;
        public final int retryAfterSeconds;
        /** The request may have reached the server (CustomerApi.Reply.possiblySent). */
        public final boolean possiblySent;

        private Answer(Transport transport, int status, String error, String reason, int retryAfterSeconds, boolean possiblySent) {
            this.transport = transport;
            this.status = status;
            this.error = error;
            this.reason = reason;
            this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
            this.possiblySent = possiblySent;
        }

        public static Answer answered(int status, String error, String reason, int retryAfterSeconds) {
            return new Answer(Transport.ANSWERED, status, error, reason, retryAfterSeconds, true);
        }

        public static Answer offline(boolean possiblySent) { return new Answer(Transport.OFFLINE, 0, "connection_failed", null, 0, possiblySent); }

        /** A local verdict ({@code status} 401 for a second {@code clock_skew}, else 0). */
        public static Answer local(int status, String error, int retryAfterSeconds) {
            return new Answer(Transport.LOCAL, status, error, null, retryAfterSeconds, false);
        }

        public static Answer stale(boolean possiblySent) { return new Answer(Transport.STALE, 0, "session_changed", null, 0, possiblySent); }

        /** Whether this answer allows the server to have run the erasure. */
        boolean mayHaveProcessed() {
            switch (transport) {
                case ANSWERED:
                    return (status >= 200 && status < 300) || status == 409 || status >= 500;
                case OFFLINE:
                case STALE:
                    return possiblySent;
                default:
                    return false;
            }
        }
    }

    public enum Outcome {
        /** Send the attempt now (the record is committed first). */
        SEND,
        /** Erased: run the done sequence. */
        DONE,
        /** Definitely not erased: the record says failed; the screen names the web page. */
        FAILED,
        /** Send again after {@link Decision#retryInSeconds} (automatic). */
        RETRY,
        /** The server could not do it now: the user may retry (same key). */
        UNAVAILABLE,
        RATE_LIMITED,
        /** Nothing reached the server. */
        OFFLINE,
        /** Not linked here and nothing may have been processed: no request; the web page instead (the record goes). */
        NOT_LINKED
    }

    /** One decision: what to do, the record to store (null = remove it), the automatic retry's delay. */
    public static final class Decision {
        public final Outcome outcome;
        public final Record record;
        public final int retryInSeconds;
        /** The {@code error_code} to count for this outcome, or null. */
        public final String code;

        private Decision(Outcome outcome, Record record, int retryInSeconds, String code) {
            this.outcome = outcome;
            this.record = record;
            this.retryInSeconds = Math.max(0, retryInSeconds);
            this.code = code;
        }

        @Override public String toString() { return "DeleteFlow.Decision{" + outcome + "," + record + "," + retryInSeconds + "}"; }
    }

    /**
     * The user confirmed a deletion (or tapped Retry): {@code existing} is the stored record or null, {@code linked} whether
     * the slot is linked at the current origin, {@code newKey} a fresh uuid4 for a new deletion. A pending record keeps its
     * key; a failed one is replaced by a new deletion.
     */
    public static Decision begin(Record existing, boolean linked, String newKey, long now) {
        if (existing != null && existing.state == State.DONE) return new Decision(Outcome.DONE, existing, 0, null);
        if (existing != null && existing.state == State.PENDING) {
            if (linked) return new Decision(Outcome.SEND, existing, 0, null);
            return existing.possiblyProcessed ? new Decision(Outcome.FAILED, existing.with(State.FAILED), 0, "link_required")
                    : new Decision(Outcome.NOT_LINKED, null, 0, null);
        }
        if (!linked) return new Decision(Outcome.NOT_LINKED, null, 0, null);
        return new Decision(Outcome.SEND, Record.start(newKey, now), 0, null);
    }

    /**
     * The answer of an attempt of the pending {@code record}; {@code conflictRetries} and {@code unavailableRetries} are
     * the automatic retries of each kind already made in this run. The returned record has the attempt counted.
     */
    public static Decision answered(Record record, Answer answer, int conflictRetries, int unavailableRetries) {
        if (record == null || answer == null) throw new IllegalArgumentException("record");
        if (record.state != State.PENDING) return new Decision(record.state == State.DONE ? Outcome.DONE : Outcome.FAILED, record, 0, null);
        boolean earlier = record.possiblyProcessed;
        boolean counted = answer.transport == Answer.Transport.ANSWERED || answer.transport == Answer.Transport.STALE
                || (answer.transport == Answer.Transport.OFFLINE && answer.possiblySent);
        Record next = counted ? record.sent(answer.mayHaveProcessed()) : record;
        String code = ErrorCodes.orNull(answer.error);
        switch (answer.transport) {
            case STALE:
                return new Decision(Outcome.UNAVAILABLE, next, 0, "session_changed");
            case OFFLINE:
                if (!answer.possiblySent) return new Decision(Outcome.OFFLINE, next, 0, ErrorCodes.OFFLINE);
                return unavailable(next, unavailableRetries, ErrorCodes.TIMEOUT);
            case LOCAL:
                return local(next, answer, unavailableRetries);
            default:
                break;
        }
        int status = answer.status;
        if (status >= 200 && status < 300) return new Decision(Outcome.DONE, next.with(State.DONE), 0, null);
        if (status == 401) {
            if (CustomerOutcome.ACCOUNT_ERASED_REASON.equals(answer.reason)) return new Decision(Outcome.DONE, next.with(State.DONE), 0, null);
            if ("clock_skew".equals(answer.error) || "nonce_replayed".equals(answer.error)) {
                return unavailable(next, unavailableRetries, code);
            }
            if (earlier && isSessionGone(answer.error)) return new Decision(Outcome.DONE, next.with(State.DONE), 0, null);
            return new Decision(Outcome.FAILED, next.with(State.FAILED), 0, code != null ? code : ErrorCodes.http(401));
        }
        if (status == 409) {
            if (conflictRetries >= MAX_CONFLICT_RETRIES) return new Decision(Outcome.UNAVAILABLE, next, 0, code != null ? code : ErrorCodes.http(409));
            int wait = conflictRetries == 0 ? CONFLICT_FIRST_RETRY_SECONDS : CONFLICT_SECOND_RETRY_SECONDS;
            if (answer.retryAfterSeconds > 0) wait = Math.min(MAX_RETRY_AFTER_SECONDS, Math.max(wait, answer.retryAfterSeconds));
            return new Decision(Outcome.RETRY, next, wait, code != null ? code : ErrorCodes.http(409));
        }
        if (status == 429) return new Decision(Outcome.RATE_LIMITED, next, 0, "rate_limited");
        if (status == 404 || status >= 500) return unavailable(next, unavailableRetries, code != null ? code : ErrorCodes.http(status));
        return new Decision(Outcome.FAILED, next.with(State.FAILED), 0, code != null ? code : ErrorCodes.http(status));
    }

    /**
     * A binding loss of this user at the record's origin (design 50 §7.6), with the 401 body's {@code reason}: completes a
     * pending deletion that may have been processed, and one the server reports as {@code account_erased}.
     */
    public static Decision bindingLost(Record record, String reason) {
        if (record == null || record.state != State.PENDING) return null;
        if (record.possiblyProcessed || CustomerOutcome.ACCOUNT_ERASED_REASON.equals(reason)) {
            return new Decision(Outcome.DONE, record.with(State.DONE), 0, null);
        }
        return null;
    }

    /** The 401 codes a sent delete leaves behind (the session it removed). */
    public static boolean isSessionGone(String code) {
        return "device_revoked".equals(code) || "session_revoked".equals(code) || "invalid_token".equals(code);
    }

    private static Decision local(Record record, Answer answer, int unavailableRetries) {
        String error = answer.error;
        if ("rate_limited".equals(error)) return new Decision(Outcome.RATE_LIMITED, record, 0, "rate_limited");
        if ("link_required".equals(error)) {
            return record.possiblyProcessed ? new Decision(Outcome.FAILED, record.with(State.FAILED), 0, "link_required")
                    : new Decision(Outcome.NOT_LINKED, null, 0, null);
        }
        if ("invalid_request".equals(error) || "payload_too_large".equals(error)) {
            return new Decision(Outcome.FAILED, record.with(State.FAILED), 0, error);
        }
        // rotation_pending, storage_unavailable, not_configured, login_required, a second clock_skew: try again later.
        return unavailable(record, unavailableRetries, ErrorCodes.orNull(error));
    }

    private static Decision unavailable(Record record, int unavailableRetries, String code) {
        if (unavailableRetries < MAX_UNAVAILABLE_RETRIES) return new Decision(Outcome.RETRY, record, UNAVAILABLE_RETRY_SECONDS, code);
        return new Decision(Outcome.UNAVAILABLE, record, 0, code);
    }

    /** Whether a RETRY decision of {@link #answered} was a 409 retry (else a 5xx / timeout one). */
    public static boolean isConflictRetry(Answer answer) {
        return answer != null && answer.transport == Answer.Transport.ANSWERED && answer.status == 409;
    }
}
