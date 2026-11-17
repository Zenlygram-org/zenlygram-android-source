package org.telegram.messenger.zenlygram.customer;

/**
 * Plain-JVM checks of the event error codes (contract §3.4, §0.3 {@code error_code}): backend codes pass, built codes
 * ({@code http_<nnn>}, {@code billing_<int>}, {@code sqlite_<int>}, {@code telegram_<rpc>}) have the documented form,
 * already-normalized codes such as {@code telegram_*} pass through, and anything else — exception text, URLs,
 * sentences, upper case — is {@code unknown}. Run with run-tests.sh.
 */
public final class ErrorCodesTests {
    private static int checks;

    public static void main(String[] args) {
        stableCodes();
        built();
        telegram();
        replies();
        System.out.println("Error codes: " + checks + " checks passed");
    }

    private static void stableCodes() {
        for (String code : new String[]{"rate_limited", "attest_unavailable", "invalid_request", "profile_unavailable", "timeout", "offline", "dns",
                "tls", "unknown", "telegram_bot_invalid", "sqlite_13", "billing_-1", "http_503", "storekit_2", "a", "0", "x.y-z_1",
                "e" + EventRegistryTests.repeat('x', 63)}) {
            check(ErrorCodes.isCode(code) && ErrorCodes.normalize(code).equals(code) && code.equals(ErrorCodes.orNull(code)), code + " passes");
        }
        for (String text : new String[]{null, "", "Rate_limited", "RATE_LIMITED", "_leading", "-leading", ".leading", "has space",
                "java.io.IOException: timeout", "https://zenlygram.org/v1", "a/b", "code:1", "cöde", "line\nbreak",
                "e" + EventRegistryTests.repeat('x', 64)}) {
            check(!ErrorCodes.isCode(text) && ErrorCodes.normalize(text).equals(ErrorCodes.UNKNOWN) && ErrorCodes.orNull(text) == null,
                    "'" + text + "' becomes unknown");
        }
        check(ErrorCodes.UNKNOWN.equals("unknown") && ErrorCodes.TIMEOUT.equals("timeout") && ErrorCodes.OFFLINE.equals("offline")
                && ErrorCodes.DNS.equals("dns") && ErrorCodes.TLS.equals("tls") && ErrorCodes.INVALID_REQUEST.equals("invalid_request"), "the fixed codes");
    }

    private static void built() {
        check(ErrorCodes.http(100).equals("http_100") && ErrorCodes.http(404).equals("http_404") && ErrorCodes.http(599).equals("http_599"),
                "http_<3 digits>");
        check(ErrorCodes.http(99).equals("unknown") && ErrorCodes.http(600).equals("unknown") && ErrorCodes.http(0).equals("unknown")
                && ErrorCodes.http(-1).equals("unknown"), "only real statuses");
        check(ErrorCodes.billing(1).equals("billing_1") && ErrorCodes.billing(-3).equals("billing_-3") && ErrorCodes.billing(12).equals("billing_12"),
                "billing_<BillingResponseCode>");
        check(ErrorCodes.sqlite(13).equals("sqlite_13") && ErrorCodes.sqlite(0).equals("sqlite_0") && ErrorCodes.sqlite(-1).equals("unknown"),
                "sqlite_<result code>");
        for (int code = -3; code <= 12; code++) check(ErrorCodes.isCode(ErrorCodes.billing(code)), "billing_" + code + " is a stable code");
    }

    private static void telegram() {
        check(ErrorCodes.telegram("FLOOD_WAIT_17").equals("telegram_flood_wait"), "FLOOD_WAIT_17 → telegram_flood_wait");
        check(ErrorCodes.telegram("PHONE_MIGRATE_2").equals("telegram_phone_migrate"), "PHONE_MIGRATE_2 → telegram_phone_migrate");
        check(ErrorCodes.telegram("BOT_INVALID").equals("telegram_bot_invalid"), "BOT_INVALID → telegram_bot_invalid");
        check(ErrorCodes.telegram("2FA_CONFIRM_WAIT_604800").equals("telegram_2fa_confirm_wait"), "a leading digit is part of the name");
        check(ErrorCodes.telegram("AUTH_KEY_UNREGISTERED").equals("telegram_auth_key_unregistered"), "no number: unchanged");
        check(ErrorCodes.telegram("A").equals("telegram_a"), "one letter");
        for (String junk : new String[]{null, "", "flood_wait", "FLOOD WAIT", "_FLOOD", "Flood", "BOT_INVALID\n", "FLOOD-WAIT",
                "X" + EventRegistryTests.repeat('Y', 60)}) {
            check(ErrorCodes.telegram(junk).equals("unknown"), "'" + junk + "' is not an RPC error name (or too long)");
        }
    }

    private static void replies() {
        check(ErrorCodes.ofReply(0, "connection_failed", true).equals("offline"), "no answer: offline");
        check(ErrorCodes.ofReply(503, "profile_unavailable", false).equals("profile_unavailable"), "the server's code");
        check(ErrorCodes.ofReply(500, null, false).equals("http_500") && ErrorCodes.ofReply(502, "Bad Gateway", false).equals("http_502"),
                "no stable code: http_<status>");
        check(ErrorCodes.ofReply(0, "rotation_pending", false).equals("rotation_pending"), "a local stable code before sending");
        check(ErrorCodes.ofReply(0, null, false).equals("unknown") && ErrorCodes.ofReply(0, "Some text", false).equals("unknown"), "else unknown");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
