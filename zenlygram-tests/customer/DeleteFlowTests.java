package org.telegram.messenger.zenlygram.customer;

/**
 * Plain-JVM checks of the deletion decisions (contract §2.2.5, §6.8, amendment A3; design 50 §12): a first-attempt 401
 * → failed; a timeout then 401 {@code device_revoked} → done; 409 then 401 → done; a re-signed {@code clock_skew} then
 * 200 → done; a binding loss while possibly processed → done (and not before); 401 {@code account_erased} → done at any
 * attempt; 409 retries after Retry-After or 30 s, then 60 s, at most twice; 5xx / timeout once after 30 s; 429, offline
 * and 422; unlinked; the {@code delete.v1} record (format, key kept across retries, fail-closed decoding). Run with
 * run-tests.sh.
 */
public final class DeleteFlowTests {
    private static int checks;
    private static final String KEY = "3f2a1c0e-9b8d-4e6f-a1b2-c3d4e5f60718";
    private static final String OTHER_KEY = "0b1c2d3e-4f50-4617-8899-aabbccddeeff";
    private static final long NOW = 1793527200L;

    public static void main(String[] args) {
        begin();
        firstAttempt401Fails();
        timeoutThen401IsDone();
        conflictThen401IsDone();
        clockSkewThenSuccess();
        accountErased();
        bindingLoss();
        conflictRetries();
        unavailableRetries();
        rateLimitedOfflineInvalid();
        localAndStale();
        record();
        System.out.println("Delete flow: " + checks + " checks passed");
    }

    private static void begin() {
        DeleteFlow.Decision d = DeleteFlow.begin(null, false, KEY, NOW);
        check(d.outcome == DeleteFlow.Outcome.NOT_LINKED && d.record == null, "unlinked, nothing earlier → the web page, no request, no record");
        d = DeleteFlow.begin(null, true, KEY, NOW);
        check(d.outcome == DeleteFlow.Outcome.SEND && d.record.key.equals(KEY) && d.record.state == DeleteFlow.State.PENDING
                && !d.record.possiblyProcessed && d.record.attempts == 0 && d.record.requestedAt == NOW, "linked → a new pending record, then send");
        DeleteFlow.Record pending = d.record;
        d = DeleteFlow.begin(pending, true, OTHER_KEY, NOW + 60);
        check(d.outcome == DeleteFlow.Outcome.SEND && d.record.key.equals(KEY), "Retry keeps the key");
        d = DeleteFlow.begin(pending, false, OTHER_KEY, NOW + 60);
        check(d.outcome == DeleteFlow.Outcome.NOT_LINKED && d.record == null, "a pending record that never reached the server goes when unlinked");
        DeleteFlow.Record processed = DeleteFlow.answered(pending, DeleteFlow.Answer.offline(true), 0, 1).record;
        d = DeleteFlow.begin(processed, false, OTHER_KEY, NOW + 60);
        check(d.outcome == DeleteFlow.Outcome.FAILED && d.record.state == DeleteFlow.State.FAILED, "unlinked after a possibly processed attempt → failed");
        DeleteFlow.Record failed = d.record;
        d = DeleteFlow.begin(failed, true, OTHER_KEY, NOW + 120);
        check(d.outcome == DeleteFlow.Outcome.SEND && d.record.key.equals(OTHER_KEY) && !d.record.possiblyProcessed, "after a failure: a new deletion");
        d = DeleteFlow.begin(failed, false, OTHER_KEY, NOW + 120);
        check(d.outcome == DeleteFlow.Outcome.NOT_LINKED, "failed and unlinked → the web page");
        DeleteFlow.Record done = DeleteFlow.answered(pending, answer(200, null, null), 0, 0).record;
        check(DeleteFlow.begin(done, true, OTHER_KEY, NOW).outcome == DeleteFlow.Outcome.DONE, "a done record stays done");
        try {
            DeleteFlow.begin(null, true, "not-a-uuid", NOW);
            check(false, "the key must be a uuid4");
        } catch (IllegalArgumentException expected) {
            check(true, "the key must be a uuid4");
        }
    }

    private static void firstAttempt401Fails() {
        for (String code : new String[]{"device_revoked", "session_revoked", "invalid_token", "expired_token", "refresh_reuse", null}) {
            DeleteFlow.Decision d = DeleteFlow.answered(start(), answer(401, code, null), 0, 0);
            check(d.outcome == DeleteFlow.Outcome.FAILED && d.record.state == DeleteFlow.State.FAILED && !d.record.possiblyProcessed,
                    "a first-attempt 401 " + code + " → failed (nothing says the server erased anything)");
            check(d.record.attempts == 1, "the attempt is counted");
        }
    }

    private static void timeoutThen401IsDone() {
        DeleteFlow.Decision first = DeleteFlow.answered(start(), DeleteFlow.Answer.offline(true), 0, 0);
        check(first.outcome == DeleteFlow.Outcome.RETRY && first.retryInSeconds == 30 && first.record.possiblyProcessed
                && first.record.state == DeleteFlow.State.PENDING, "a timeout after connecting: possibly processed, one retry after 30 s");
        DeleteFlow.Decision second = DeleteFlow.answered(first.record, answer(401, "device_revoked", null), 0, 1);
        check(second.outcome == DeleteFlow.Outcome.DONE && second.record.state == DeleteFlow.State.DONE, "then 401 device_revoked → done");
        for (String code : new String[]{"session_revoked", "invalid_token"}) {
            check(DeleteFlow.answered(first.record, answer(401, code, null), 0, 1).outcome == DeleteFlow.Outcome.DONE, "then 401 " + code + " → done");
        }
        check(DeleteFlow.answered(first.record, answer(401, "expired_token", null), 0, 1).outcome == DeleteFlow.Outcome.FAILED,
                "any other 401 code → failed even after a possibly processed attempt");
        DeleteFlow.Decision connect = DeleteFlow.answered(start(), DeleteFlow.Answer.offline(false), 0, 0);
        check(connect.outcome == DeleteFlow.Outcome.OFFLINE && !connect.record.possiblyProcessed && connect.record.attempts == 0,
                "a connect failure: offline, nothing sent, nothing counted");
        check(DeleteFlow.answered(connect.record, answer(401, "device_revoked", null), 0, 0).outcome == DeleteFlow.Outcome.FAILED,
                "so a later 401 is still a failure");
    }

    private static void conflictThen401IsDone() {
        DeleteFlow.Decision conflict = DeleteFlow.answered(start(), answer(409, "idempotency_in_flight", null), 0, 0);
        check(conflict.outcome == DeleteFlow.Outcome.RETRY && conflict.retryInSeconds == 30 && conflict.record.possiblyProcessed,
                "409: possibly processed (another request of this key runs), retry after 30 s");
        check(DeleteFlow.isConflictRetry(answer(409, "idempotency_in_flight", null)) && !DeleteFlow.isConflictRetry(DeleteFlow.Answer.offline(true)),
                "the caller tells the 409 retry from the unavailable one");
        DeleteFlow.Decision done = DeleteFlow.answered(conflict.record, answer(401, "session_revoked", null), 1, 0);
        check(done.outcome == DeleteFlow.Outcome.DONE && done.record.attempts == 2 && done.record.key.equals(KEY), "409 then 401 → done, same key");
        check(DeleteFlow.answered(conflict.record, answer(200, null, null), 1, 0).outcome == DeleteFlow.Outcome.DONE, "409 then 200 → done");
    }

    private static void clockSkewThenSuccess() {
        // CustomerApi re-signs a first clock_skew itself and reports only the final answer: 200 → done.
        check(DeleteFlow.answered(start(), answer(200, null, null), 0, 0).outcome == DeleteFlow.Outcome.DONE, "clock_skew → re-sign → 200 → done");
        // A second clock_skew (the device clock is off): a local verdict, retried once, never a failure.
        DeleteFlow.Decision skew = DeleteFlow.answered(start(), DeleteFlow.Answer.local(401, "clock_skew", 0), 0, 0);
        check(skew.outcome == DeleteFlow.Outcome.RETRY && !skew.record.possiblyProcessed && skew.record.state == DeleteFlow.State.PENDING,
                "a second clock_skew → retry once, nothing processed");
        check(DeleteFlow.answered(skew.record, answer(200, null, null), 0, 1).outcome == DeleteFlow.Outcome.DONE, "then 200 → done");
        check(DeleteFlow.answered(start(), answer(401, "clock_skew", null), 0, 0).outcome == DeleteFlow.Outcome.RETRY, "an answered clock_skew too");
        DeleteFlow.Decision replayed = DeleteFlow.answered(start(), answer(401, "nonce_replayed", null), 0, 1);
        check(replayed.outcome == DeleteFlow.Outcome.UNAVAILABLE && replayed.record.state == DeleteFlow.State.PENDING,
                "nonce_replayed keeps the credential: unavailable after the automatic retry");
    }

    private static void accountErased() {
        DeleteFlow.Decision d = DeleteFlow.answered(start(), answer(401, "invalid_token", "account_erased"), 0, 0);
        check(d.outcome == DeleteFlow.Outcome.DONE && d.record.state == DeleteFlow.State.DONE, "401 account_erased on the first attempt → done");
        d = DeleteFlow.answered(start(), answer(401, "device_revoked", "account_erased"), 0, 0);
        check(d.outcome == DeleteFlow.Outcome.DONE, "whatever the 401 code");
        check(DeleteFlow.bindingLost(start(), "account_erased").outcome == DeleteFlow.Outcome.DONE,
                "a binding loss with reason account_erased completes a pending deletion even before any answer");
    }

    private static void bindingLoss() {
        check(DeleteFlow.bindingLost(start(), null) == null && DeleteFlow.bindingLost(start(), "something") == null,
                "a binding loss before any possibly processed attempt changes nothing (the 401 answer fails it)");
        DeleteFlow.Record processed = DeleteFlow.answered(start(), answer(503, "server_error", null), 0, 0).record;
        check(processed.possiblyProcessed, "SETUP: a 5xx may have been processed");
        DeleteFlow.Decision d = DeleteFlow.bindingLost(processed, null);
        check(d.outcome == DeleteFlow.Outcome.DONE && d.record.state == DeleteFlow.State.DONE && d.record.key.equals(KEY),
                "a binding loss while possibly processed → done");
        check(DeleteFlow.bindingLost(d.record, null) == null, "a done record is not completed twice");
        check(DeleteFlow.bindingLost(null, "account_erased") == null, "no record, no deletion");
        DeleteFlow.Record failed = DeleteFlow.answered(start(), answer(422, "invalid_request", null), 0, 0).record;
        check(DeleteFlow.bindingLost(failed, "account_erased") == null, "a failed record is never completed by a loss");
        // The answer arriving after the loss completed it: nothing changes.
        DeleteFlow.Decision late = DeleteFlow.answered(d.record, answer(401, "invalid_token", null), 0, 0);
        check(late.outcome == DeleteFlow.Outcome.DONE && late.record.equals(d.record), "a late answer for a done record keeps it done");
        DeleteFlow.Decision lateFailed = DeleteFlow.answered(failed, answer(200, null, null), 0, 0);
        check(lateFailed.outcome == DeleteFlow.Outcome.FAILED && lateFailed.record.equals(failed), "and a failed one failed");
    }

    private static void conflictRetries() {
        DeleteFlow.Record r = start();
        DeleteFlow.Decision d = DeleteFlow.answered(r, answer(409, "idempotency_in_flight", null, 45), 0, 0);
        check(d.outcome == DeleteFlow.Outcome.RETRY && d.retryInSeconds == 45, "the first retry honours Retry-After");
        d = DeleteFlow.answered(d.record, answer(409, "idempotency_in_flight", null), 1, 0);
        check(d.outcome == DeleteFlow.Outcome.RETRY && d.retryInSeconds == 60, "the second after 60 s");
        d = DeleteFlow.answered(d.record, answer(409, "idempotency_in_flight", null), 2, 0);
        check(d.outcome == DeleteFlow.Outcome.UNAVAILABLE && d.record.state == DeleteFlow.State.PENDING && d.record.attempts == 3,
                "at most two automatic retries, then the user's Retry (same key, still pending)");
        check(DeleteFlow.answered(r, answer(409, "idempotency_in_flight", null, 100_000), 0, 0).retryInSeconds == DeleteFlow.MAX_RETRY_AFTER_SECONDS,
                "an absurd Retry-After is capped");
        check(DeleteFlow.answered(r, answer(409, "idempotency_in_flight", null, 5), 1, 0).retryInSeconds == 60, "never sooner than the step");
    }

    private static void unavailableRetries() {
        for (int status : new int[]{500, 502, 503, 504, 404}) {
            DeleteFlow.Decision d = DeleteFlow.answered(start(), answer(status, status == 404 ? "not_found" : "server_error", null), 0, 0);
            check(d.outcome == DeleteFlow.Outcome.RETRY && d.retryInSeconds == 30, status + ": one automatic retry after 30 s");
            check(d.record.possiblyProcessed == (status >= 500), status + ": a 5xx may have been processed, a 404 not");
            d = DeleteFlow.answered(d.record, answer(status, "server_error", null), 0, 1);
            check(d.outcome == DeleteFlow.Outcome.UNAVAILABLE && d.record.state == DeleteFlow.State.PENDING, status + ": then unavailable (Retry)");
        }
        DeleteFlow.Decision timeout = DeleteFlow.answered(start(), DeleteFlow.Answer.offline(true), 0, 1);
        check(timeout.outcome == DeleteFlow.Outcome.UNAVAILABLE && timeout.code.equals(ErrorCodes.TIMEOUT), "a second timeout → unavailable");
    }

    private static void rateLimitedOfflineInvalid() {
        DeleteFlow.Decision d = DeleteFlow.answered(start(), answer(429, "rate_limited", null, 120), 0, 0);
        check(d.outcome == DeleteFlow.Outcome.RATE_LIMITED && !d.record.possiblyProcessed && d.record.state == DeleteFlow.State.PENDING,
                "429 → rate limited (refused before the erasure), still pending");
        check(DeleteFlow.answered(start(), DeleteFlow.Answer.local(0, "rate_limited", 60), 0, 0).outcome == DeleteFlow.Outcome.RATE_LIMITED,
                "the route's running cooldown too");
        d = DeleteFlow.answered(start(), DeleteFlow.Answer.offline(false), 0, 0);
        check(d.outcome == DeleteFlow.Outcome.OFFLINE && d.code.equals(ErrorCodes.OFFLINE), "offline before sending");
        d = DeleteFlow.answered(start(), answer(422, "invalid_request", null), 0, 0);
        check(d.outcome == DeleteFlow.Outcome.FAILED && d.record.state == DeleteFlow.State.FAILED && !d.record.possiblyProcessed
                && "invalid_request".equals(d.code), "422 → failed");
        for (int status : new int[]{400, 403, 413}) {
            check(DeleteFlow.answered(start(), answer(status, null, null), 0, 0).outcome == DeleteFlow.Outcome.FAILED, status + " → failed");
        }
        check("http_403".equals(DeleteFlow.answered(start(), answer(403, null, null), 0, 0).code), "a code-less refusal counts http_<status>");
    }

    private static void localAndStale() {
        DeleteFlow.Decision d = DeleteFlow.answered(start(), DeleteFlow.Answer.local(0, "link_required", 0), 0, 0);
        check(d.outcome == DeleteFlow.Outcome.NOT_LINKED && d.record == null, "unlinked meanwhile, nothing processed → the web page");
        DeleteFlow.Record processed = DeleteFlow.answered(start(), answer(503, "server_error", null), 0, 0).record;
        d = DeleteFlow.answered(processed, DeleteFlow.Answer.local(0, "link_required", 0), 0, 1);
        check(d.outcome == DeleteFlow.Outcome.FAILED && d.record.state == DeleteFlow.State.FAILED, "unlinked after a possibly processed attempt → failed");
        for (String code : new String[]{"rotation_pending", "storage_unavailable", "not_configured", "login_required"}) {
            d = DeleteFlow.answered(start(), DeleteFlow.Answer.local(0, code, 0), 0, 0);
            check(d.outcome == DeleteFlow.Outcome.RETRY && d.record.attempts == 0, code + ": retried later, nothing counted");
        }
        check(DeleteFlow.answered(start(), DeleteFlow.Answer.local(0, "invalid_request", 0), 0, 0).outcome == DeleteFlow.Outcome.FAILED,
                "a refused request is a failure");
        d = DeleteFlow.answered(start(), DeleteFlow.Answer.stale(true), 0, 0);
        check(d.outcome == DeleteFlow.Outcome.UNAVAILABLE && d.record.possiblyProcessed && d.record.attempts == 1,
                "a withheld reply that may have arrived: possibly processed");
        d = DeleteFlow.answered(start(), DeleteFlow.Answer.stale(false), 0, 0);
        check(d.outcome == DeleteFlow.Outcome.UNAVAILABLE && !d.record.possiblyProcessed, "one that never left: not processed");
    }

    private static void record() {
        DeleteFlow.Record r = start();
        check(("{\"v\":1,\"key\":\"" + KEY + "\",\"state\":\"pending\",\"possibly_processed\":false,\"attempts\":0,\"requested_at\":" + NOW + "}")
                .equals(r.json()), "the documented JSON: " + r.json());
        DeleteFlow.Record sent = DeleteFlow.answered(r, DeleteFlow.Answer.offline(true), 0, 0).record;
        for (DeleteFlow.Record value : new DeleteFlow.Record[]{r, sent, DeleteFlow.answered(sent, answer(200, null, null), 0, 1).record,
                DeleteFlow.answered(r, answer(422, null, null), 0, 0).record}) {
            check(value.equals(DeleteFlow.Record.fromTree(MiniJson.parse(value.json()))), "round trip " + value.json());
        }
        check(r.blocks() && DeleteFlow.answered(r, answer(200, null, null), 0, 0).record.blocks()
                && !DeleteFlow.answered(r, answer(422, null, null), 0, 0).record.blocks(), "pending and done block, failed does not");
        for (String bad : new String[]{"{}", "[]", "{\"v\":1,\"key\":\"x\",\"state\":\"pending\",\"possibly_processed\":false,\"attempts\":0,\"requested_at\":1}",
                "{\"v\":2,\"key\":\"" + KEY + "\",\"state\":\"pending\",\"possibly_processed\":false,\"attempts\":0,\"requested_at\":1}",
                "{\"v\":1,\"key\":\"" + KEY + "\",\"state\":\"gone\",\"possibly_processed\":false,\"attempts\":0,\"requested_at\":1}",
                "{\"v\":1,\"key\":\"" + KEY + "\",\"state\":\"pending\",\"possibly_processed\":\"no\",\"attempts\":0,\"requested_at\":1}",
                "{\"v\":1,\"key\":\"" + KEY + "\",\"state\":\"pending\",\"possibly_processed\":false,\"attempts\":-1,\"requested_at\":1}"}) {
            check(DeleteFlow.Record.fromTree(MiniJson.parse(bad)) == null, "not a record: " + bad);
        }
        DeleteFlow.Record unreadable = DeleteFlow.Record.unreadable(OTHER_KEY, NOW);
        check(unreadable.state == DeleteFlow.State.PENDING && unreadable.possiblyProcessed && unreadable.blocks(),
                "an unreadable record fails closed: pending, possibly processed");
        check(DeleteFlow.answered(unreadable, answer(401, "invalid_token", null), 0, 0).outcome == DeleteFlow.Outcome.DONE,
                "so a 401 on its retry completes it");
        check(DeleteFlow.State.of("pending") == DeleteFlow.State.PENDING && DeleteFlow.State.of("x") == null, "state names");
    }

    // ---- helpers ----

    private static DeleteFlow.Record start() { return DeleteFlow.Record.start(KEY, NOW); }

    private static DeleteFlow.Answer answer(int status, String error, String reason) { return answer(status, error, reason, 0); }

    private static DeleteFlow.Answer answer(int status, String error, String reason, int retryAfter) {
        return DeleteFlow.Answer.answered(status, error, reason, retryAfter);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
