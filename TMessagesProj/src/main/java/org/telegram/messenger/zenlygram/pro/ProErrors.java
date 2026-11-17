package org.telegram.messenger.zenlygram.pro;

/**
 * Pure meaning of a ZenlyPro.Error code for the Pro screen: which message the user sees and whether
 * the visible linking code is finished. No Android types, so it is tested on a plain JVM. Codes are
 * the existing backend, DeviceAuth and transport codes; nothing here changes access.
 */
public final class ProErrors {
    private ProErrors() { }

    public enum Message {
        TRIAL_USED, TRIAL_DEVICE_LIMIT, TRIAL_UNAVAILABLE, LINK_UNAVAILABLE, UPDATE_REQUIRED, WRONG_ACCOUNT, WAIT, STORAGE,
        PURCHASES_PAUSED, CONNECTION
    }

    /** Unknown or missing codes fall back to the connection message, which never promises access. */
    public static Message message(String code) {
        if (code == null) return Message.CONNECTION;
        switch (code) {
            case "trial_already_used": return Message.TRIAL_USED;
            case "trial_device_cap": case "trial_device_blocked": return Message.TRIAL_DEVICE_LIMIT;
            case "trial_paused": return Message.TRIAL_UNAVAILABLE;
            case "bind_paused": return Message.LINK_UNAVAILABLE;
            case "unknown_signing_key": return Message.UPDATE_REQUIRED;
            case "account_mismatch": return Message.WRONG_ACCOUNT;
            case "storage_unavailable": return Message.STORAGE;
            case "rate_limited": return Message.WAIT;
            case "monetization_disabled": return Message.PURCHASES_PAUSED;
            default: return Message.CONNECTION;
        }
    }

    /**
     * Whether a link poll error ends the current code. Client errors (except 429) and results that can
     * never verify end it; throttling, outages and lost connections keep polling with backoff.
     */
    public static boolean endsLink(String code, int httpStatus) {
        if (httpStatus >= 400 && httpStatus < 500 && httpStatus != 429) return true;
        if (code == null) return false;
        switch (code) {
            case "account_mismatch": case "link_changed": case "session_changed":
            case "device_key_changed": case "invalid_response": case "unknown_signing_key":
            case "not_configured": case "storage_unavailable": return true;
            default: return false;
        }
    }
}
