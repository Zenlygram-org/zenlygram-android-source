package org.telegram.messenger.zenlygram.tasks.cloud;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Zenlygram Tasks realtime hint channel (tasks-sync.json x-realtime, consumer contract 2ac6d7d8…, interop addendum
 * G1–G5): the two authenticated REST answers and the Laravel Reverb / Pusher protocol 7 text frames that carry ONLY
 * the content-free {@code tasks.changed} invalidation hint. Task content, permissions and revocation stay with the
 * REST snapshot / changes / mutations. Behaviour-identical to iOS {@code SGTasksRealtime}.
 * <p>
 * Pure (no Android): every value is produced by a strict validating factory or decoder; nothing here logs, and the
 * key, socket id, channel and signature never appear in {@code toString()}.
 */
public final class TasksRealtimeWire {
    public static final int SCHEMA_VERSION = 1;
    /** {@code activity_timeout} is honoured only within 10..120 s (G3). */
    public static final int MIN_ACTIVITY_TIMEOUT = 10, MAX_ACTIVITY_TIMEOUT = 120;
    /** Every WebSocket message, both directions. Pusher control frames are ~150 bytes. */
    public static final int MAX_FRAME_BYTES = 8192;
    /** GET /realtime and POST /realtime/auth answers (one small object each). */
    public static final int MAX_RESPONSE_BYTES = 16384;
    static final int MAX_APP_KEY_LENGTH = 128;
    static final String CHANNEL_PREFIX = "private-tasks.";
    /** HMAC-SHA256 (32 bytes) as base64url without padding. */
    static final int CHANNEL_TOKEN_LENGTH = 43;
    static final int SIGNATURE_HEX_LENGTH = 64;
    static final int MAX_SOCKET_ID_PART = 20;
    /**
     * The consumer contract's client.endpoint_validation literally names {@code client=zenlygram-ios}; Android sends
     * its own client name (a deliberate, documented deviation: the Pusher {@code client} parameter is informational
     * for Reverb and carries no authority). Everything else in the query is the contract's fixed value.
     */
    public static final String FIXED_QUERY = "protocol=7&client=zenlygram-android&version=1.0&flash=false";
    static final String PATH_PREFIX = "/realtime/app/";
    private static final int DEFAULT_TLS_PORT = 443;

    private TasksRealtimeWire() { }

    // ---- Endpoint ----------------------------------------------------------------------------

    /**
     * The validated WebSocket endpoint, rebuilt from the configured API origin and the validated public key only — the
     * server's {@code ws_url} string itself is never dialled.
     */
    public static final class Endpoint {
        /** {@code host[:port]} exactly as in the API base URL. */
        public final String authority;
        public final String host;
        public final int port;
        /** {@code /realtime/app/{key}?protocol=7&client=zenlygram-android&version=1.0&flash=false} */
        public final String pathAndQuery;
        /** {@code https://{authority}}: the only extra handshake header this client sends. */
        public final String origin;
        final String appKey;

        private Endpoint(String authority, String host, int port, String appKey) {
            this.authority = authority;
            this.host = host;
            this.port = port;
            this.appKey = appKey;
            this.pathAndQuery = PATH_PREFIX + appKey + "?" + FIXED_QUERY;
            this.origin = "https://" + authority;
        }

        /**
         * {@code wsUrl} must be, byte for byte, {@code wss://} + the API base URL's authority + {@code /realtime/app/}
         * + a bounded key: no other host or port, userinfo, query, fragment, trailing slash, percent escape or dot
         * segment. {@code apiBaseUrl} is the client's {@code https://{authority}/v1}. Null = refused.
         */
        public static Endpoint of(String wsUrl, String apiBaseUrl) {
            String authority = authorityOf(apiBaseUrl);
            if (authority == null || wsUrl == null) return null;
            String prefix = "wss://" + authority + PATH_PREFIX;
            if (!wsUrl.startsWith(prefix)) return null;
            String key = wsUrl.substring(prefix.length());
            if (!isValidAppKey(key)) return null;
            int colon = authority.indexOf(':');
            String host = colon < 0 ? authority : authority.substring(0, colon);
            int port = DEFAULT_TLS_PORT;
            if (colon >= 0) {
                port = port(authority.substring(colon + 1));
                if (port <= 0) return null;
            }
            if (host.isEmpty()) return null;
            return new Endpoint(authority, host, port, key);
        }

        @Override public String toString() { return "TasksRealtimeWire.Endpoint(<redacted>)"; }
    }

    /** {@code host[:port]} of {@code https://{authority}/v1}, or null. */
    static String authorityOf(String apiBaseUrl) {
        if (apiBaseUrl == null || !apiBaseUrl.startsWith("https://") || !apiBaseUrl.endsWith("/v1")) return null;
        String authority = apiBaseUrl.substring("https://".length(), apiBaseUrl.length() - "/v1".length());
        if (authority.isEmpty() || authority.indexOf(':') != authority.lastIndexOf(':')) return null;
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == ':';
            if (!allowed) return null;
        }
        return authority;
    }

    private static int port(String digits) {
        if (digits.isEmpty() || digits.length() > 5) return -1;
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + (c - '0');
        }
        return value >= 1 && value <= 65535 ? value : -1;
    }

    // ---- Metadata (GET /v1/tasks/realtime) -----------------------------------------------------

    public static final class Metadata {
        /** Null = the intentional kill switch ({@code enabled:false}). A broken service is a 503, never this. */
        public final Endpoint endpoint;
        /** Validated 10..120. */
        public final int activityTimeout;
        /** Validated canonical Instant. */
        public final String serverTime;

        Metadata(Endpoint endpoint, int activityTimeout, String serverTime) {
            this.endpoint = endpoint;
            this.activityTimeout = activityTimeout;
            this.serverTime = serverTime;
        }

        public boolean isEnabled() { return endpoint != null; }

        @Override public String toString() { return "TasksRealtimeWire.Metadata(" + (isEnabled() ? "enabled" : "disabled") + ")"; }
    }

    /** Strict: exact member set, schema 1, timeout 10..120, Instant, ws_url consistent with {@code enabled}. */
    public static Metadata decodeMetadata(String json, String apiBaseUrl) {
        TasksWireDecode.Fields r = TasksWireDecode.Fields.exact(TasksWireJson.parse(json, MAX_RESPONSE_BYTES), "realtime",
                "schema_version", "enabled", "ws_url", "activity_timeout", "server_time");
        r.integer("schema_version", SCHEMA_VERSION, SCHEMA_VERSION);
        int timeout = (int) r.integer("activity_timeout", MIN_ACTIVITY_TIMEOUT, MAX_ACTIVITY_TIMEOUT);
        String serverTime = TasksWireText.instant(r.string("server_time"), "realtime.server_time");
        if (r.bool("enabled")) {
            Endpoint endpoint = Endpoint.of(r.nullableString("ws_url"), apiBaseUrl);
            if (endpoint == null) throw new TasksWire.Invalid("realtime.ws_url");
            return new Metadata(endpoint, timeout, serverTime);
        }
        if (!r.isNull("ws_url")) throw new TasksWire.Invalid("realtime.ws_url");
        return new Metadata(null, timeout, serverTime);
    }

    // ---- Socket id and subscription (POST /v1/tasks/realtime/auth) ----------------------------

    /** {@code ^[0-9]{1,20}\.[0-9]{1,20}$}, ASCII digits only. */
    public static boolean isValidSocketId(String raw) {
        if (raw == null) return false;
        int dot = raw.indexOf('.');
        if (dot < 0 || dot != raw.lastIndexOf('.')) return false;
        return digits(raw, 0, dot) && digits(raw, dot + 1, raw.length());
    }

    private static boolean digits(String raw, int from, int to) {
        int length = to - from;
        if (length < 1 || length > MAX_SOCKET_ID_PART) return false;
        for (int i = from; i < to; i++) if (raw.charAt(i) < '0' || raw.charAt(i) > '9') return false;
        return true;
    }

    /** The server's signed subscription for ONE socket id: memory only, never reused for another socket id. */
    public static final class Subscription {
        final String socketId, channel, auth, appKey;
        final String serverTime;

        private Subscription(String socketId, String channel, String auth, String appKey, String serverTime) {
            this.socketId = socketId;
            this.channel = channel;
            this.auth = auth;
            this.appKey = appKey;
            this.serverTime = serverTime;
        }

        /** Exact channel form and {@code {key}:{64 lowercase hex}} with a valid key; null = refused. */
        static Subscription of(String socketId, String channel, String auth, String serverTime) {
            if (!isValidSocketId(socketId) || !isValidChannel(channel) || auth == null) return null;
            int separator = auth.lastIndexOf(':');
            if (separator < 0) return null;
            String appKey = auth.substring(0, separator);
            if (!isValidAppKey(appKey) || !isValidAuth(auth, appKey)) return null;
            return new Subscription(socketId, channel, auth, appKey, serverTime);
        }

        public String serverTime() { return serverTime; }

        @Override public String toString() { return "TasksRealtimeWire.Subscription(<redacted>)"; }
    }

    /** Strict: exact members, schema 1, Instant, channel {@code private-tasks.<43 b64url>}, auth {@code key:hex}. */
    public static Subscription decodeSubscription(String json, String socketId) {
        if (!isValidSocketId(socketId)) throw new TasksWire.Invalid("socket_id");
        TasksWireDecode.Fields r = TasksWireDecode.Fields.exact(TasksWireJson.parse(json, MAX_RESPONSE_BYTES), "realtime_auth",
                "schema_version", "channel", "auth", "server_time");
        r.integer("schema_version", SCHEMA_VERSION, SCHEMA_VERSION);
        String serverTime = TasksWireText.instant(r.string("server_time"), "realtime_auth.server_time");
        String channel = r.string("channel");
        if (!isValidChannel(channel)) throw new TasksWire.Invalid("realtime_auth.channel");
        Subscription subscription = Subscription.of(socketId, channel, r.string("auth"), serverTime);
        if (subscription == null) throw new TasksWire.Invalid("realtime_auth.auth");
        return subscription;
    }

    /** Canonical {@code {"socket_id":"…"}}. */
    public static byte[] authRequestBody(String socketId) {
        if (!isValidSocketId(socketId)) throw new TasksWire.Invalid("socket_id");
        return TasksWireJson.canonicalBytes(new TasksWireJson.Obj().put("socket_id", socketId));
    }

    static boolean isValidAppKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_APP_KEY_LENGTH) return false;
        for (int i = 0; i < key.length(); i++) if (!isBase64UrlChar(key.charAt(i))) return false;
        return true;
    }

    private static boolean isBase64UrlChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    static boolean isValidChannel(String channel) {
        if (channel == null || !channel.startsWith(CHANNEL_PREFIX)) return false;
        String token = channel.substring(CHANNEL_PREFIX.length());
        if (token.length() != CHANNEL_TOKEN_LENGTH) return false;
        for (int i = 0; i < token.length(); i++) if (!isBase64UrlChar(token.charAt(i))) return false;
        return true;
    }

    /** {@code {key}:{64 lowercase hex}} where the key is byte-equal to {@code appKey}. */
    static boolean isValidAuth(String auth, String appKey) {
        String prefix = appKey + ":";
        if (auth == null || !auth.startsWith(prefix)) return false;
        String signature = auth.substring(prefix.length());
        if (signature.length() != SIGNATURE_HEX_LENGTH) return false;
        for (int i = 0; i < signature.length(); i++) {
            char c = signature.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    // ---- Pusher frames -----------------------------------------------------------------------

    public static final class Frame {
        public enum Kind { CONNECTION_ESTABLISHED, PING, PONG, SUBSCRIPTION_SUCCEEDED, SUBSCRIPTION_ERROR, ERROR, CHANGED, OTHER }

        public final Kind kind;
        final String socketId;
        final int activityTimeout;
        /** Null when absent or not a string. */
        final String channel;
        /** pusher:error code, or null. */
        final Integer code;

        private Frame(Kind kind, String socketId, int activityTimeout, String channel, Integer code) {
            this.kind = kind;
            this.socketId = socketId;
            this.activityTimeout = activityTimeout;
            this.channel = channel;
            this.code = code;
        }

        static Frame of(Kind kind, String channel) { return new Frame(kind, null, 0, channel, null); }

        @Override public String toString() { return "TasksRealtimeWire.Frame(" + kind + ")"; }
    }

    /** A frame that broke the protocol (size, JSON, shape). Never echoes input. */
    public static final class FrameException extends Exception {
        private static final long serialVersionUID = 1L;
        public final boolean tooLarge;

        FrameException(boolean tooLarge) {
            super(tooLarge ? "frame_too_large" : "frame_malformed", null, false, false);
            this.tooLarge = tooLarge;
        }
    }

    /**
     * One server text message: size, strict JSON, duplicate keys and depth enforced; {@code data} may be a
     * JSON-encoded string (Pusher/Reverb) or an already-decoded object (G2), parsed as structured data, never by
     * substring matching.
     */
    public static Frame parseFrame(String text) throws FrameException {
        if (text == null) throw new FrameException(false);
        if (text.length() > MAX_FRAME_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) throw new FrameException(true);
        Object root;
        try {
            root = TasksWireJson.parse(text, MAX_FRAME_BYTES);
        } catch (TasksWire.Invalid invalid) {
            throw new FrameException(false);
        }
        if (!(root instanceof Map)) throw new FrameException(false);
        Map<?, ?> members = (Map<?, ?>) root;
        Object event = members.get("event");
        Object rawChannel = members.get("channel");
        String channel = rawChannel instanceof String ? (String) rawChannel : null;
        Object data = members.get("data");
        if (!(event instanceof String)) throw new FrameException(false);
        switch ((String) event) {
            case "pusher:connection_established": {
                Object payload = payload(data);
                if (!(payload instanceof Map)) throw new FrameException(false);
                Map<?, ?> fields = (Map<?, ?>) payload;
                Object socketId = fields.get("socket_id");
                if (!(socketId instanceof String) || !isValidSocketId((String) socketId)) throw new FrameException(false);
                // G3: a REQUIRED integer within 10..120 that governs this connection; never clamped or guessed.
                int timeout = integer(fields.get("activity_timeout"), MIN_ACTIVITY_TIMEOUT, MAX_ACTIVITY_TIMEOUT);
                if (timeout < 0) throw new FrameException(false);
                return new Frame(Frame.Kind.CONNECTION_ESTABLISHED, (String) socketId, timeout, channel, null);
            }
            case "pusher:ping":
                return Frame.of(Frame.Kind.PING, channel);
            case "pusher:pong":
                return Frame.of(Frame.Kind.PONG, channel);
            case "pusher_internal:subscription_succeeded": {
                // A private channel's ack (Reverb PrivateChannel): data "{}" (or an already-decoded {}), channel required.
                Object payload = payload(data);
                if (channel == null || !(payload instanceof Map) || !((Map<?, ?>) payload).isEmpty()) throw new FrameException(false);
                return Frame.of(Frame.Kind.SUBSCRIPTION_SUCCEEDED, channel);
            }
            case "pusher:subscription_error":
                return Frame.of(Frame.Kind.SUBSCRIPTION_ERROR, channel);
            case "pusher:error": {
                Integer code = null;
                try {
                    Object payload = payload(data);
                    if (payload instanceof Map) {
                        int value = integer(((Map<?, ?>) payload).get("code"), 0, Integer.MAX_VALUE);
                        if (value >= 0) code = value;
                    }
                } catch (FrameException unreadable) {
                    // An unreadable error payload still ends the connection, with no code.
                }
                return new Frame(Frame.Kind.ERROR, null, 0, channel, code);
            }
            case "tasks.changed": {
                if (channel == null) throw new FrameException(false);
                // Exactly {"v":1} (object or JSON-encoded string); any other version, extra member or content is ignored.
                Object payload;
                try {
                    payload = payload(data);
                } catch (FrameException unreadable) {
                    return Frame.of(Frame.Kind.OTHER, channel);
                }
                if (!(payload instanceof Map)) return Frame.of(Frame.Kind.OTHER, channel);
                Map<?, ?> fields = (Map<?, ?>) payload;
                Object version = fields.get("v");
                boolean exact = fields.size() == 1 && version instanceof TasksWireJson.Num && "1".equals(((TasksWireJson.Num) version).literal);
                return Frame.of(exact ? Frame.Kind.CHANGED : Frame.Kind.OTHER, channel);
            }
            default:
                return Frame.of(Frame.Kind.OTHER, channel);
        }
    }

    /** {@code data} as a value: a string is decoded once more (bounded), an object is taken as is; null when absent/empty. */
    private static Object payload(Object data) throws FrameException {
        if (data == null || data == TasksWireJson.NULL) return null;
        if (data instanceof String) {
            String inner = (String) data;
            if (inner.isEmpty()) return null;
            try {
                return TasksWireJson.parse(inner, MAX_FRAME_BYTES);
            } catch (TasksWire.Invalid invalid) {
                throw new FrameException(false);
            }
        }
        return data;
    }

    /** A plain integer literal within [min, max], else -1. */
    private static int integer(Object value, int min, int max) {
        if (!(value instanceof TasksWireJson.Num)) return -1;
        try {
            return (int) TasksWireText.integer(((TasksWireJson.Num) value).literal, min, max, "frame");
        } catch (TasksWire.Invalid invalid) {
            return -1;
        }
    }

    public static final String PING_FRAME = "{\"event\":\"pusher:ping\",\"data\":{}}";
    public static final String PONG_FRAME = "{\"event\":\"pusher:pong\",\"data\":{}}";

    static String subscribeFrame(Subscription subscription) {
        return TasksWireJson.canonical(new TasksWireJson.Obj().put("event", "pusher:subscribe")
                .put("data", new TasksWireJson.Obj().put("auth", subscription.auth).put("channel", subscription.channel)));
    }
}
