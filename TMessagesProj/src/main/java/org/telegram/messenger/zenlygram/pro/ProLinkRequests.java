package org.telegram.messenger.zenlygram.pro;

import java.nio.charset.StandardCharsets;

/**
 * POST /v1/bind/start rules shared by the manual (Pro screen) and the automatic (headless) link, pure
 * and host-tested: the exact request body (today's keys, platform and src "android", plus the latest
 * grant ConsentEvent as "consent" when the account's consent allows it, contract §2.2.1), when the
 * same operation is sent once more without that consent, and when the manual link may attest silently.
 * Old servers ignore "consent"; the grant is uploaded through /v1/profile/consents after every link
 * anyway. Nothing here logs.
 */
public final class ProLinkRequests {
    public static final String PLATFORM = "android";
    /** Server limits of bind/start (BindController::start). */
    static final int MAX_OS_VERSION = 32, MAX_APP_VERSION = 32, MAX_DEVICE_NAME = 128;
    /** Upper bound of the embedded consent; a grant ConsentEvent is about 400 bytes. */
    static final int MAX_CONSENT_CHARS = 4096;
    private static final int MAX_JSON_DEPTH = 8;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ProLinkRequests() { }

    /**
     * The exact UTF-8 bytes of the bind/start body. deviceId and devicePubKey are required; a null
     * osVersion, appVersion or deviceName is sent as JSON null; each is cut to the server's limit (never
     * inside a surrogate pair). consentJsonOrNull is embedded verbatim when usableConsent() accepts it and
     * left out otherwise.
     */
    public static byte[] bindStartBody(String deviceId, String devicePubKey, String osVersion, String appVersion,
                                       String deviceName, String consentJsonOrNull) {
        if (deviceId == null || devicePubKey == null) throw new IllegalArgumentException("bind/start needs the device identity");
        StringBuilder json = new StringBuilder(512 + (consentJsonOrNull == null ? 0 : consentJsonOrNull.length()));
        json.append("{\"device_id\":").append(jsonString(deviceId))
                .append(",\"device_pubkey\":").append(jsonString(devicePubKey))
                .append(",\"platform\":").append(jsonString(PLATFORM))
                .append(",\"src\":").append(jsonString(PLATFORM))
                .append(",\"os_version\":").append(jsonString(cut(osVersion, MAX_OS_VERSION)))
                .append(",\"app_version\":").append(jsonString(cut(appVersion, MAX_APP_VERSION)))
                .append(",\"device_name\":").append(jsonString(cut(deviceName, MAX_DEVICE_NAME)));
        String consent = usableConsent(consentJsonOrNull);
        if (consent != null) json.append(",\"consent\":").append(consent);
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The provider's grant when it can travel inside bind/start: one strict RFC 8259 JSON object of at
     * most MAX_CONSENT_CHARS characters (anything else would turn the whole body into invalid_request
     * and block linking), else null.
     */
    public static String usableConsent(String consentJson) {
        if (consentJson == null || consentJson.length() > MAX_CONSENT_CHARS) return null;
        return isJsonObject(consentJson) ? consentJson : null;
    }

    /**
     * The consent a bind/start carries once its operation runs on its lane: readAfter, the grant read there right before
     * the body is built, and only when readBefore, the grant read when the link started, was one too (both through
     * usableConsent). A manual link can wait on the user lane behind a logout's or an Unlink's final send and bind/revoke,
     * so a withdrawal made during that wait sends no consent, and a grant made during it is not attached: it is uploaded
     * after the link (contract §6.4). As iOS's beginBind, which reads its permission again once that wait is over and
     * attaches the grant only while both reads allow it.
     */
    public static String consentAfterWait(String readBefore, String readAfter) {
        return usableConsent(readBefore) == null ? null : usableConsent(readAfter);
    }

    /**
     * bind/start answered status/error for a body that carried a consent: send the same operation once
     * more without it. 422 invalid_consent always (the consent is uploaded after the link instead);
     * 409 consent_stale only for a manual link (the grant predates an erasure of this Telegram id; an
     * automatic attempt ends instead, contract amendment A3).
     */
    public static boolean retryWithoutConsent(int status, String error, boolean manual) {
        if (status == 422 && "invalid_consent".equals(error)) return true;
        return manual && status == 409 && "consent_stale".equals(error);
    }

    /**
     * The Pro screen attests silently (Mini App initData) only when the bind offers it and the account's
     * consent allows linking (the consent text discloses the background request); otherwise it links by code.
     */
    public static boolean useAttest(boolean hasOffer, boolean hasEffectiveGrant) {
        return hasOffer && hasEffectiveGrant;
    }

    /** At most max UTF-16 units, never ending in the high half of a surrogate pair. */
    static String cut(String value, int max) {
        if (value == null || value.length() <= max) return value;
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }

    /** A JSON string literal (JSON null for null): quotes, backslashes, controls and U+2028/U+2029 escaped. */
    static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) out.append(HEX[(c >> shift) & 15]);
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    /** One strict RFC 8259 JSON object (whitespace around it allowed), nested at most MAX_JSON_DEPTH deep. */
    static boolean isJsonObject(String text) {
        if (text == null) return false;
        Scanner scanner = new Scanner(text);
        scanner.skipSpace();
        if (scanner.peek() != '{' || !scanner.value(0)) return false;
        scanner.skipSpace();
        return scanner.atEnd();
    }

    /** Recursive-descent JSON checker; never builds values. */
    private static final class Scanner {
        private final String text;
        private int at;

        Scanner(String text) { this.text = text; }

        boolean atEnd() { return at == text.length(); }

        int peek() { return at < text.length() ? text.charAt(at) : -1; }

        void skipSpace() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
                at++;
            }
        }

        boolean value(int depth) {
            if (depth > MAX_JSON_DEPTH) return false;
            int c = peek();
            if (c == '{') return object(depth + 1);
            if (c == '[') return array(depth + 1);
            if (c == '"') return string();
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            return literal("true") || literal("false") || literal("null");
        }

        private boolean object(int depth) {
            at++; // {
            skipSpace();
            if (peek() == '}') { at++; return true; }
            while (true) {
                skipSpace();
                if (peek() != '"' || !string()) return false;
                skipSpace();
                if (peek() != ':') return false;
                at++;
                skipSpace();
                if (!value(depth)) return false;
                skipSpace();
                int c = peek();
                at++;
                if (c == '}') return true;
                if (c != ',') return false;
            }
        }

        private boolean array(int depth) {
            at++; // [
            skipSpace();
            if (peek() == ']') { at++; return true; }
            while (true) {
                skipSpace();
                if (!value(depth)) return false;
                skipSpace();
                int c = peek();
                at++;
                if (c == ']') return true;
                if (c != ',') return false;
            }
        }

        private boolean string() {
            at++; // opening quote
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return true;
                if (c < 0x20) return false;
                if (c == '\\') {
                    if (at >= text.length()) return false;
                    char escaped = text.charAt(at++);
                    if (escaped == 'u') {
                        if (at + 4 > text.length()) return false;
                        for (int i = 0; i < 4; i++) if (Character.digit(text.charAt(at++), 16) < 0) return false;
                    } else if ("\"\\/bfnrt".indexOf(escaped) < 0) {
                        return false;
                    }
                }
            }
            return false;
        }

        private boolean number() {
            if (peek() == '-') at++;
            if (peek() == '0') at++;
            else if (!digits()) return false;
            if (peek() == '.') { at++; if (!digits()) return false; }
            if (peek() == 'e' || peek() == 'E') {
                at++;
                if (peek() == '+' || peek() == '-') at++;
                if (!digits()) return false;
            }
            return true;
        }

        private boolean digits() {
            int start = at;
            while (peek() >= '0' && peek() <= '9') at++;
            return at > start;
        }

        private boolean literal(String word) {
            if (!text.startsWith(word, at)) return false;
            at += word.length();
            return true;
        }
    }
}
