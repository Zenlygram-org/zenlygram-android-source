package org.telegram.messenger.zenlygram.customer;

/**
 * Plain-JVM checks of the route-aware answer classes (contract §2.1, §6.5, amendment A3; design 50 §7.5, §5.5): 2xx OK;
 * 401 {@code reason: account_erased} → ACCOUNT_ERASED on every route, clock_skew / nonce_replayed → BACKOFF, every other
 * 401 → UNAUTHORIZED; 403 consent_required (reason kept) / profile_account_not_linked / others; 404 → UNAVAILABLE;
 * 409 consent_stale on bind/start → CONSENT_STALE; 409 idempotency_in_flight → RETRY_SOON; 413 → TOO_LARGE; on
 * profile/consents every other 4xx → REJECTED, on the other routes → DROP; 429; 5xx and transport → BACKOFF; CustomerApi's
 * local refusals; codes kept only when stable. Run with run-tests.sh.
 */
public final class CustomerOutcomeTests {
    private static int checks;
    private static final CustomerOutcome.Route[] ROUTES = CustomerOutcome.Route.values();
    private static final String[] TERMINAL_CONSENT_CODES = {"consent_text_mismatch", "consent_version_unknown", "consent_version_retired",
            "consent_scopes_mismatch", "consent_age_required", "consent_id_mismatch"};

    public static void main(String[] args) {
        routes();
        success();
        unauthorized();
        forbidden();
        conflicts();
        otherClientErrors();
        retries();
        localRefusals();
        codes();
        System.out.println("Customer outcome: " + checks + " checks passed");
    }

    private static void routes() {
        check(CustomerOutcome.Route.CONSENTS.path.equals("profile/consents") && CustomerOutcome.Route.SYNC.path.equals("profile/sync")
                && CustomerOutcome.Route.ME.path.equals("profile/me") && CustomerOutcome.Route.EVENTS.path.equals("profile/events")
                && CustomerOutcome.Route.DELETE.path.equals("account/delete") && CustomerOutcome.Route.BIND_START.path.equals("bind/start"), "route paths");
        try {
            CustomerOutcome.of(null, 200, null, null, false);
            check(false, "a route is required");
        } catch (IllegalArgumentException expected) {
            check(true, "a route is required");
        }
    }

    private static void success() {
        for (CustomerOutcome.Route route : ROUTES) {
            for (int status : new int[]{200, 201, 204, 299}) {
                CustomerOutcome outcome = CustomerOutcome.of(route, status, null, null, false);
                check(outcome.kind == CustomerOutcome.Kind.OK && outcome.errorCode() == null && !outcome.retries() && !outcome.terminal(),
                        route + " " + status + " → OK");
            }
        }
    }

    private static void unauthorized() {
        for (CustomerOutcome.Route route : ROUTES) {
            check(kind(route, 401, "invalid_token", "account_erased") == CustomerOutcome.Kind.ACCOUNT_ERASED, route + ": 401 account_erased → erased");
            check(kind(route, 401, "device_revoked", "account_erased") == CustomerOutcome.Kind.ACCOUNT_ERASED, route + ": whatever the 401 code");
            for (String code : new String[]{"invalid_token", "expired_token", "session_revoked", "refresh_reuse", "device_revoked",
                    "device_key_mismatch", "unauthenticated", null, "Not A Code"}) {
                check(kind(route, 401, code, null) == CustomerOutcome.Kind.UNAUTHORIZED, route + ": 401 " + code + " → unauthorized");
            }
            check(kind(route, 401, "invalid_token", "something_else") == CustomerOutcome.Kind.UNAUTHORIZED, route + ": another reason is a plain 401");
            check(kind(route, 401, "clock_skew", null) == CustomerOutcome.Kind.BACKOFF, route + ": a second clock_skew backs off");
            check(kind(route, 401, "nonce_replayed", null) == CustomerOutcome.Kind.BACKOFF, route + ": nonce_replayed keeps the credential");
        }
    }

    private static void forbidden() {
        for (CustomerOutcome.Route route : ROUTES) {
            for (String reason : new String[]{"no_consent", "withdrawn", "reconsent_required"}) {
                CustomerOutcome outcome = CustomerOutcome.of(route, 403, "consent_required", reason, false);
                check(outcome.kind == CustomerOutcome.Kind.CONSENT_REQUIRED && reason.equals(outcome.reason), route + ": consent_required " + reason);
            }
            check(kind(route, 403, "profile_account_not_linked", null) == CustomerOutcome.Kind.STOP_PROFILE, route + ": not linked → stop profile");
            check(kind(route, 403, "forbidden", null) == CustomerOutcome.Kind.DROP && kind(route, 403, null, null) == CustomerOutcome.Kind.DROP,
                    route + ": another 403 drops the payload");
        }
        check(CustomerOutcome.NO_CONSENT.equals("no_consent") && CustomerOutcome.WITHDRAWN.equals("withdrawn")
                && CustomerOutcome.RECONSENT_REQUIRED.equals("reconsent_required") && CustomerOutcome.ACCOUNT_ERASED_REASON.equals("account_erased"),
                "the reason names");
    }

    private static void conflicts() {
        check(kind(CustomerOutcome.Route.BIND_START, 409, "consent_stale", null) == CustomerOutcome.Kind.CONSENT_STALE,
                "bind/start 409 consent_stale → stale (auto-link stops, a manual link retries without consent)");
        check(kind(CustomerOutcome.Route.CONSENTS, 409, "consent_stale", null) == CustomerOutcome.Kind.REJECTED, "on consents it is just another 4xx");
        check(kind(CustomerOutcome.Route.SYNC, 409, "consent_stale", null) == CustomerOutcome.Kind.DROP, "elsewhere too");
        for (CustomerOutcome.Route route : ROUTES) {
            check(kind(route, 409, "idempotency_in_flight", null) == CustomerOutcome.Kind.RETRY_SOON, route + ": 409 in flight → retry soon");
            check(kind(route, 413, "payload_too_large", null) == CustomerOutcome.Kind.TOO_LARGE, route + ": 413 → too large");
            check(kind(route, 413, null, null) == CustomerOutcome.Kind.TOO_LARGE, route + ": 413 without code");
        }
        check(kind(CustomerOutcome.Route.CONSENTS, 409, "profile_account_mismatch", null) == CustomerOutcome.Kind.REJECTED,
                "consents 409 account mismatch → rejected");
        check(kind(CustomerOutcome.Route.SYNC, 409, "profile_account_mismatch", null) == CustomerOutcome.Kind.DROP, "sync 409 → drop");
        check(kind(CustomerOutcome.Route.BIND_START, 409, "bind_conflict", null) == CustomerOutcome.Kind.DROP, "bind/start's other 409s");
    }

    private static void otherClientErrors() {
        for (String code : TERMINAL_CONSENT_CODES) {
            CustomerOutcome outcome = CustomerOutcome.of(CustomerOutcome.Route.CONSENTS, 422, code, null, false);
            check(outcome.kind == CustomerOutcome.Kind.REJECTED && outcome.terminal() && code.equals(outcome.code), "consents 422 " + code + " → rejected");
        }
        for (String code : new String[]{"unsupported_schema_version", "invalid_request", null}) {
            check(kind(CustomerOutcome.Route.CONSENTS, 422, code, null) == CustomerOutcome.Kind.REJECTED, "consents 422 " + code + " → rejected");
        }
        for (int status : new int[]{400, 405, 410, 415, 418, 451, 499}) {
            check(kind(CustomerOutcome.Route.CONSENTS, status, null, null) == CustomerOutcome.Kind.REJECTED, "consents " + status + " → rejected");
            for (CustomerOutcome.Route route : ROUTES) {
                if (route == CustomerOutcome.Route.CONSENTS) continue;
                check(kind(route, status, null, null) == CustomerOutcome.Kind.DROP, route + " " + status + " → drop");
                check(kind(route, 422, "invalid_request", null) == CustomerOutcome.Kind.DROP, route + " 422 → drop");
            }
        }
        for (CustomerOutcome.Route route : ROUTES) {
            check(kind(route, 404, "not_found", null) == CustomerOutcome.Kind.UNAVAILABLE, route + ": 404 (an older server) → unavailable");
        }
    }

    private static void retries() {
        for (CustomerOutcome.Route route : ROUTES) {
            CustomerOutcome limited = CustomerOutcome.of(route, 429, "rate_limited", null, false);
            check(limited.kind == CustomerOutcome.Kind.RATE_LIMITED && limited.retries(), route + ": 429");
            for (int status : new int[]{500, 502, 503, 504, 599}) {
                CustomerOutcome outcome = CustomerOutcome.of(route, status, status == 503 ? "profile_unavailable" : null, null, false);
                check(outcome.kind == CustomerOutcome.Kind.BACKOFF && outcome.retries() && !outcome.terminal(), route + " " + status + " → backoff");
            }
            for (int status : new int[]{100, 101, 301, 302, 304, 600, -1}) {
                check(kind(route, status, null, null) == CustomerOutcome.Kind.BACKOFF, route + ": an unexpected " + status + " → backoff");
            }
            CustomerOutcome offline = CustomerOutcome.of(route, 0, "connection_failed", null, true);
            check(offline.kind == CustomerOutcome.Kind.BACKOFF && offline.transportFailure && offline.status == 0, route + ": offline");
            check(CustomerOutcome.of(route, 200, "invalid_response", null, true).kind == CustomerOutcome.Kind.BACKOFF,
                    route + ": an unreadable 200 is a transport failure, never OK");
            check(CustomerOutcome.of(route, 404, null, null, false).retries() && CustomerOutcome.of(route, 409, "idempotency_in_flight", null, false).retries(),
                    route + ": unavailable and in-flight retry");
        }
    }

    private static void localRefusals() {
        for (CustomerOutcome.Route route : ROUTES) {
            check(kind(route, 0, "session_changed", null) == CustomerOutcome.Kind.STALE, route + ": the identity moved on → stale (nothing counts)");
            check(kind(route, 0, "rate_limited", null) == CustomerOutcome.Kind.RATE_LIMITED, route + ": the route's cooldown runs");
            check(kind(route, 0, "payload_too_large", null) == CustomerOutcome.Kind.TOO_LARGE, route + ": above the route's bound");
            check(kind(route, 0, "invalid_request", null) == CustomerOutcome.Kind.DROP, route + ": a body refused locally");
            for (String code : new String[]{"link_required", "not_configured", "login_required", "storage_unavailable", "rotation_pending", "clock_skew",
                    null}) {
                check(kind(route, 0, code, null) == CustomerOutcome.Kind.BACKOFF, route + ": local " + code + " → backoff");
            }
        }
    }

    private static void codes() {
        CustomerOutcome outcome = CustomerOutcome.of(CustomerOutcome.Route.EVENTS, 500, "Internal Server Error!", "Some reason", false);
        check(outcome.code == null && outcome.reason == null, "free text is never kept as a code or reason");
        check(outcome.errorCode().equals("http_500"), "error.occurred code http_500");
        check(CustomerOutcome.of(CustomerOutcome.Route.EVENTS, 503, "profile_unavailable", null, false).errorCode().equals("profile_unavailable"),
                "the server's code");
        check(CustomerOutcome.of(CustomerOutcome.Route.EVENTS, 0, "connection_failed", null, true).errorCode().equals("offline"), "offline");
        check(CustomerOutcome.of(CustomerOutcome.Route.EVENTS, 0, "rotation_pending", null, false).errorCode().equals("rotation_pending"),
                "a local stable code");
        check(CustomerOutcome.of(CustomerOutcome.Route.SYNC, 403, "consent_required", "withdrawn", false).toString()
                .equals("CustomerOutcome{profile/sync,CONSENT_REQUIRED,403,consent_required}"), "toString carries no message");
        check(CustomerOutcome.of(CustomerOutcome.Route.ME, 422, "invalid_request", null, false).terminal()
                && !CustomerOutcome.of(CustomerOutcome.Route.ME, 401, "invalid_token", null, false).terminal(), "terminal answers");
    }

    // ---- helpers ----

    private static CustomerOutcome.Kind kind(CustomerOutcome.Route route, int status, String code, String reason) {
        return CustomerOutcome.of(route, status, code, reason, false).kind;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
