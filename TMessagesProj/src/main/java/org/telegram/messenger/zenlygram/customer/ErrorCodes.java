package org.telegram.messenger.zenlygram.customer;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The {@code error_code} values events carry (contract §3.4, §0.3; design 50 §11.1), pure and host-tested: a backend
 * stable {@code error} code, {@code http_<3 digits>}, {@code timeout}, {@code offline}, {@code dns}, {@code tls},
 * {@code billing_<int>} (Play BillingResponseCode), {@code sqlite_<int>}, {@code telegram_<RPC error lower-cased>} or
 * {@code unknown}. Every builder returns a value of the {@code error_code} pattern
 * ({@code ^[a-z0-9][a-z0-9_.-]{0,63}$}), so an exception message, a URL, an id, a name or any user text can never
 * become a code: whatever does not match is {@link #UNKNOWN}.
 */
public final class ErrorCodes {
    public static final String UNKNOWN = "unknown";
    public static final String TIMEOUT = "timeout";
    public static final String OFFLINE = "offline";
    public static final String DNS = "dns";
    public static final String TLS = "tls";
    /** The code of a dropped events batch (design 50 §11.6: 422 → error.occurred{events_upload, invalid_request}). */
    public static final String INVALID_REQUEST = "invalid_request";

    /** contract §0.3 {@code error_code}. */
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    /** A Telegram RPC error name as the API spells it (FLOOD_WAIT_17, BOT_INVALID). */
    private static final Pattern RPC_ERROR = Pattern.compile("[A-Z0-9][A-Z0-9_]*");
    private static final String TELEGRAM_PREFIX = "telegram_";

    private ErrorCodes() {}

    /** Whether {@code code} is a contract {@code error_code}. */
    public static boolean isCode(String code) { return code != null && CODE.matcher(code).matches(); }

    /**
     * {@code code} when it already is a stable code (a backend {@code error}, or one built here such as
     * {@code telegram_bot_invalid} or {@code sqlite_13}), else {@link #UNKNOWN}.
     */
    public static String normalize(String code) { return isCode(code) ? code : UNKNOWN; }

    /** {@code code} when it is a stable code, else null (an optional {@code error_code} is then omitted). */
    public static String orNull(String code) { return isCode(code) ? code : null; }

    /** {@code http_<status>} for an HTTP status 100..599, else {@link #UNKNOWN}. */
    public static String http(int status) {
        return status >= 100 && status <= 599 ? "http_" + status : UNKNOWN;
    }

    /** {@code billing_<code>} for a Play BillingResponseCode (negative codes included: {@code billing_-1}). */
    public static String billing(int responseCode) { return normalize("billing_" + responseCode); }

    /** {@code sqlite_<code>} for an SQLite result code (0..), else {@link #UNKNOWN}. */
    public static String sqlite(int resultCode) { return resultCode >= 0 ? normalize("sqlite_" + resultCode) : UNKNOWN; }

    /**
     * {@code telegram_<rpc error lower-cased>} without a trailing numeric argument ({@code FLOOD_WAIT_17} →
     * {@code telegram_flood_wait}, {@code PHONE_MIGRATE_2} → {@code telegram_phone_migrate}, {@code BOT_INVALID} →
     * {@code telegram_bot_invalid}); anything that is not an RPC error name → {@link #UNKNOWN}. The same rule as the
     * automatic link's {@code ProAutoLinkMachine.telegramErrorCode}.
     */
    public static String telegram(String rpcError) {
        if (rpcError == null || !RPC_ERROR.matcher(rpcError).matches()) return UNKNOWN;
        String name = rpcError.toLowerCase(Locale.ROOT);
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) >= '0' && name.charAt(end - 1) <= '9') end--;
        if (end > 1 && end < name.length() && name.charAt(end - 1) == '_') name = name.substring(0, end - 1);
        return normalize(TELEGRAM_PREFIX + name);
    }

    /**
     * The code of one customer call's answer: the server's stable {@code error} when it sent one, else
     * {@code http_<status>}; {@link #OFFLINE} when no answer arrived (transport failure); for a refusal before sending
     * (status 0) the local stable code, else {@link #UNKNOWN}.
     */
    public static String ofReply(int status, String error, boolean transportFailure) {
        if (transportFailure) return OFFLINE;
        if (status > 0) return isCode(error) ? error : http(status);
        return normalize(error);
    }
}
