package org.telegram.messenger.zenlygram.pro;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Telegram-attested bind ("bind-attest" v1.1), Android-independent and host-tested: the bind/start
 * offer, the bot identity check, initData extraction from the Mini App URL Telegram returns to
 * messages.requestMainWebView, and the exact request body and device-proof bytes of POST
 * /v1/bind/attest. Every rejection here means "use the existing code/bot fallback", never an error
 * shown to the user. Nothing in this class logs.
 */
public final class ProBindAttest {
    public static final String ROUTE = "bind/attest";
    static final String PROOF_PATH = "/v1/bind/attest";
    static final String PROOF_MODE = "bind-attest";
    /** Server limit on the whole JSON body. */
    static final int MAX_BODY_BYTES = 8192;

    /**
     * The validated `attest` object of a bind/start answer; null-free and immutable. Its `url` is not
     * used: the Main Mini App URL is the bot's own (BotFather), never passed by the client.
     */
    public static final class Offer {
        public final long botId;
        public final String botUsername, startParam;

        private Offer(long botId, String botUsername, String startParam) {
            this.botId = botId;
            this.botUsername = botUsername;
            this.startParam = startParam;
        }

        /**
         * The offer of the bind whose code is {@code code}, or null when any field is missing or malformed
         * (the client then links by code). start_param must be exactly "zb" + that code.
         */
        public static Offer of(String botId, String botUsername, String startParam, String code) {
            if (botId == null || botUsername == null || startParam == null || code == null) return null;
            if (!botId.matches("[1-9][0-9]{0,15}") || !botUsername.matches("[A-Za-z][A-Za-z0-9_]{3,31}")
                    || !startParam.matches("[A-Za-z0-9_-]{1,64}") || !startParam.equals("zb" + code)) {
                return null;
            }
            return new Offer(Long.parseLong(botId), botUsername, startParam);
        }
    }

    /**
     * The freshly resolved @bot_username is exactly the offered bot id, a bot, and has a Main Mini App
     * (the only launch that echoes start_param into initData); otherwise link by code.
     */
    public static boolean botMatches(Offer offer, long resolvedUserId, boolean isBot, boolean hasMainApp) {
        return offer != null && isBot && hasMainApp && resolvedUserId == offer.botId;
    }

    /**
     * Telegram.WebApp.initData from the URL of messages.requestMainWebView: the single
     * {@code tgWebAppData} parameter of the fragment, percent-decoded exactly once (it stays an
     * application/x-www-form-urlencoded string). Null when absent, repeated, malformed or empty.
     */
    public static String initData(String webAppUrl) {
        if (webAppUrl == null) return null;
        int hash = webAppUrl.indexOf('#');
        if (hash < 0) return null;
        String found = null;
        for (String part : webAppUrl.substring(hash + 1).split("&", -1)) {
            int equals = part.indexOf('=');
            if (equals < 0 || !part.substring(0, equals).equals("tgWebAppData")) continue;
            if (found != null) return null;
            found = part.substring(equals + 1);
        }
        if (found == null) return null;
        String decoded = percentDecode(found);
        return decoded == null || decoded.isEmpty() ? null : decoded;
    }

    /** Strict RFC 3986 percent-decoding into UTF-8; '+' stays literal. Null on a bad escape or invalid UTF-8. */
    static String percentDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '%') {
                if (c > 0x7e || c < 0x21) return null; // A URL fragment is printable ASCII.
                bytes.write(c);
                continue;
            }
            if (i + 2 >= value.length()) return null;
            int high = Character.digit(value.charAt(i + 1), 16), low = Character.digit(value.charAt(i + 2), 16);
            if (high < 0 || low < 0) return null;
            bytes.write((high << 4) | low);
            i += 2;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * The exact UTF-8 bytes of {@code {"poll_token":…,"init_data":…}} that are both sent and covered
     * by the device proof, or null when they would exceed the server limit.
     */
    static byte[] body(String pollToken, String initData) {
        if (pollToken == null || initData == null || initData.isEmpty()) return null;
        String json = "{\"poll_token\":" + jsonString(pollToken) + ",\"init_data\":" + jsonString(initData) + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return bytes.length > MAX_BODY_BYTES ? null : bytes;
    }

    /** The nine-line device-proof message of POST /v1/bind/attest over the exact {@code body} bytes. */
    static byte[] proofMessage(String rawDeviceId, byte[] spki, String nonce, String timestamp, byte[] body)
            throws GeneralSecurityException {
        return ProCrypto.ownershipProofMessage(PROOF_PATH, rawDeviceId, ProCrypto.sha256Hex(spki), nonce, timestamp,
                ProCrypto.sha256Hex(body), PROOF_MODE);
    }

    /** Only 200 with state "confirmed" attached this Telegram user; anything else falls back to the code. */
    static boolean confirmed(int status, String state) {
        return status == 200 && "confirmed".equals(state);
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private ProBindAttest() { }
}
