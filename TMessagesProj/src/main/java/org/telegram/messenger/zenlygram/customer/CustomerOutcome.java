package org.telegram.messenger.zenlygram.customer;

/**
 * What one customer call's answer means (contract §2.1, §6.5, amendment A3; design 50 §7.5), pure and host-tested,
 * route-aware:
 * <ul>
 * <li>2xx → {@link Kind#OK};</li>
 * <li>401 with {@code "reason": "account_erased"} (any route) → {@link Kind#ACCOUNT_ERASED} (a completed deletion: local
 * purge, consent none); {@code clock_skew} after the single re-sign and {@code nonce_replayed} → {@link Kind#BACKOFF}
 * (credential kept); any other 401 → {@link Kind#UNAUTHORIZED} (ZenlyPro already cleared exactly that credential and
 * reported the binding loss);</li>
 * <li>403 {@code consent_required} → {@link Kind#CONSENT_REQUIRED} with its {@code reason}; 403
 * {@code profile_account_not_linked} → {@link Kind#STOP_PROFILE}; any other 403 → {@link Kind#DROP};</li>
 * <li>404 → {@link Kind#UNAVAILABLE} (a server without the route, contract §7.1: retried with backoff);</li>
 * <li>409 {@code consent_stale} on {@code bind/start} → {@link Kind#CONSENT_STALE}; 409 {@code idempotency_in_flight}
 * → {@link Kind#RETRY_SOON}; 413 → {@link Kind#TOO_LARGE};</li>
 * <li>on {@code profile/consents} every other 4xx (409 {@code profile_account_mismatch}, every 422 incl. the six
 * terminal consent codes and {@code unsupported_schema_version}) → {@link Kind#REJECTED} (those entries are marked
 * rejected and never resent, contract §6.3); on the other routes every other 4xx → {@link Kind#DROP};</li>
 * <li>429 → {@link Kind#RATE_LIMITED}; 5xx (incl. 503 {@code profile_unavailable}), any unexpected status and a
 * transport failure → {@link Kind#BACKOFF}.</li>
 * </ul>
 * A refusal before sending (status 0, CustomerApi's local verdicts) maps by its code: {@code session_changed} →
 * {@link Kind#STALE} (the identity moved on: nothing of the answer counts), {@code rate_limited} (the route's running
 * install-wide cooldown) → {@link Kind#RATE_LIMITED}, {@code payload_too_large} → {@link Kind#TOO_LARGE},
 * {@code invalid_request} → {@link Kind#DROP}, anything else (not linked, rotation pending, storage) →
 * {@link Kind#BACKOFF}.
 */
public final class CustomerOutcome {
    /** The routes a customer call can take. */
    public enum Route {
        CONSENTS("profile/consents"), SYNC("profile/sync"), ME("profile/me"), EVENTS("profile/events"), DELETE("account/delete"),
        BIND_START("bind/start");

        public final String path;

        Route(String path) { this.path = path; }
    }

    public enum Kind {
        OK, UNAUTHORIZED, ACCOUNT_ERASED, CONSENT_REQUIRED, CONSENT_STALE, STOP_PROFILE, REJECTED, DROP, TOO_LARGE, RETRY_SOON,
        RATE_LIMITED, UNAVAILABLE, BACKOFF, STALE
    }

    /** {@code consent_required.reason} values (contract §2.1). */
    public static final String NO_CONSENT = "no_consent", WITHDRAWN = "withdrawn", RECONSENT_REQUIRED = "reconsent_required";
    /** 401 {@code reason} of an erased account's credentials (amendment A3). */
    public static final String ACCOUNT_ERASED_REASON = "account_erased";

    public final Route route;
    public final Kind kind;
    /** HTTP status; 0 when none was received. */
    public final int status;
    /** The answer's or the local stable error code, or null (OK, or none given). */
    public final String code;
    /** {@code reason} of a 403 {@code consent_required} or a 401, else null. */
    public final String reason;
    /** No answer arrived, or none that could be read. */
    public final boolean transportFailure;

    private CustomerOutcome(Route route, Kind kind, int status, String code, String reason, boolean transportFailure) {
        this.route = route;
        this.kind = kind;
        this.status = status;
        this.code = code;
        this.reason = reason;
        this.transportFailure = transportFailure;
    }

    /**
     * The outcome of an answer on {@code route}: its HTTP {@code status} (0 = none), its {@code errorCode} and
     * {@code reason} (each kept only when a stable code), and whether the transport failed (no answer, or one that
     * could not be read).
     */
    public static CustomerOutcome of(Route route, int status, String errorCode, String reason, boolean transportFailure) {
        if (route == null) throw new IllegalArgumentException("route");
        String code = ErrorCodes.orNull(errorCode);
        String why = ErrorCodes.orNull(reason);
        return new CustomerOutcome(route, classify(route, status, code, why, transportFailure), transportFailure ? 0 : status, code, why,
                transportFailure);
    }

    private static Kind classify(Route route, int status, String code, String reason, boolean transportFailure) {
        if (transportFailure) return Kind.BACKOFF;
        if (status == 0) {
            if ("session_changed".equals(code)) return Kind.STALE;
            if ("rate_limited".equals(code)) return Kind.RATE_LIMITED;
            if ("payload_too_large".equals(code)) return Kind.TOO_LARGE;
            if ("invalid_request".equals(code)) return Kind.DROP;
            return Kind.BACKOFF;
        }
        if (status >= 200 && status < 300) return Kind.OK;
        if (status == 401) {
            if (ACCOUNT_ERASED_REASON.equals(reason)) return Kind.ACCOUNT_ERASED;
            if ("clock_skew".equals(code) || "nonce_replayed".equals(code)) return Kind.BACKOFF;
            return Kind.UNAUTHORIZED;
        }
        if (status == 403) {
            if ("consent_required".equals(code)) return Kind.CONSENT_REQUIRED;
            if ("profile_account_not_linked".equals(code)) return Kind.STOP_PROFILE;
            return Kind.DROP;
        }
        if (status == 404) return Kind.UNAVAILABLE;
        if (status == 409 && route == Route.BIND_START && "consent_stale".equals(code)) return Kind.CONSENT_STALE;
        if (status == 409 && "idempotency_in_flight".equals(code)) return Kind.RETRY_SOON;
        if (status == 413) return Kind.TOO_LARGE;
        if (status == 429) return Kind.RATE_LIMITED;
        if (status >= 400 && status < 500) return route == Route.CONSENTS ? Kind.REJECTED : Kind.DROP;
        return Kind.BACKOFF;
    }

    /** The route is retried later with {@link CustomerBackoff} (429, 5xx, 404, offline, 409 in flight). */
    public boolean retries() {
        return kind == Kind.RATE_LIMITED || kind == Kind.RETRY_SOON || kind == Kind.UNAVAILABLE || kind == Kind.BACKOFF;
    }

    /** The payload is never sent again (dropped or rejected). */
    public boolean terminal() { return kind == Kind.DROP || kind == Kind.REJECTED; }

    /** The {@code error.occurred} code of this answer (contract §3.4); null for {@link Kind#OK}. */
    public String errorCode() {
        if (kind == Kind.OK) return null;
        return ErrorCodes.ofReply(status, code, transportFailure);
    }

    @Override public String toString() { return "CustomerOutcome{" + route.path + "," + kind + "," + status + "," + code + "}"; }
}
