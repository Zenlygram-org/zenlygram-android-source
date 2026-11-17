package org.telegram.messenger.zenlygram.pro;

/**
 * Plain-JVM checks of what a Pro error means to the user: which message they see, and whether an
 * error ends the visible linking code or polling simply continues (AC-A1). Wire codes are the
 * existing backend/DeviceAuth/transport codes; nothing here grants access.
 */
public final class ProErrorsTests {
    private static int checks;

    public static void main(String[] args) {
        messages();
        linkEndings();
        System.out.println("Pro errors: " + checks + " checks passed");
    }

    private static void messages() {
        check(ProErrors.message("trial_already_used") == ProErrors.Message.TRIAL_USED, "used trial is explained, not shown as a network error");
        check(ProErrors.message("trial_device_cap") == ProErrors.Message.TRIAL_DEVICE_LIMIT
                && ProErrors.message("trial_device_blocked") == ProErrors.Message.TRIAL_DEVICE_LIMIT, "device trial limits share one message");
        check(ProErrors.message("trial_paused") == ProErrors.Message.TRIAL_UNAVAILABLE, "paused trials are temporary");
        check(ProErrors.message("bind_paused") == ProErrors.Message.LINK_UNAVAILABLE, "paused linking is temporary");
        check(ProErrors.message("unknown_signing_key") == ProErrors.Message.UPDATE_REQUIRED, "a new signing key asks for an app update");
        check(ProErrors.message("account_mismatch") == ProErrors.Message.WRONG_ACCOUNT, "confirming with another Telegram account is named");
        check(ProErrors.message("rate_limited") == ProErrors.Message.WAIT, "throttling asks the user to wait");
        check(ProErrors.message("service_unavailable") == ProErrors.Message.CONNECTION
                && ProErrors.message("connection_failed") == ProErrors.Message.CONNECTION, "outages keep the saved-data reassurance");
        check(ProErrors.message("something_new") == ProErrors.Message.CONNECTION && ProErrors.message(null) == ProErrors.Message.CONNECTION,
                "an unknown or missing code never promises a trial or purchase");
        check(ProErrors.message("storage_unavailable") == ProErrors.Message.STORAGE,
                "a device storage failure is named as such, never as a connection problem");
        check(ProErrors.message("monetization_disabled") == ProErrors.Message.PURCHASES_PAUSED,
                "a server refusal while free says purchases are paused and Pro is free, never a network error");
    }

    private static void linkEndings() {
        check(!ProErrors.endsLink("rate_limited", 429), "a throttled poll keeps the code and waits for Retry-After");
        check(!ProErrors.endsLink("service_unavailable", 503) && !ProErrors.endsLink("connection_failed", 0),
                "an outage or lost connection keeps polling the same code");
        check(ProErrors.endsLink("link_expired", 410) && ProErrors.endsLink("not_found", 404), "a rejected code ends linking");
        check(ProErrors.endsLink("refresh_invalid", 401), "an authentication failure ends linking");
        check(ProErrors.endsLink("account_mismatch", 0) && ProErrors.endsLink("link_changed", 0) && ProErrors.endsLink("session_changed", 0),
                "another account or a newer attempt ends this code");
        check(ProErrors.endsLink("device_key_changed", 0) && ProErrors.endsLink("invalid_response", 0)
                && ProErrors.endsLink("unknown_signing_key", 0), "an unverifiable result ends linking instead of retrying forever");
        check(ProErrors.endsLink("not_configured", 0) && ProErrors.endsLink("storage_unavailable", 0),
                "a build or device that cannot store Pro ends linking");
        check(!ProErrors.endsLink(null, 0), "a missing code with no status is retried");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
