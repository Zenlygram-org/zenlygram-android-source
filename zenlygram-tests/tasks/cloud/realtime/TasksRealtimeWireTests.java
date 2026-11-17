package org.telegram.messenger.zenlygram.tasks.cloud;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Host tests of the real realtime wire sources: TasksRealtimeWire (endpoint/metadata/auth/Pusher frames, strict JSON on
 * the app's Gson) and TasksRealtimeFrames (RFC 6455 client codec + opening handshake). No network.
 */
public final class TasksRealtimeWireTests {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    static final String BASE = "https://api.example.org/v1";
    static final String KEY = "Ab0_-key";
    static final String T = "2026-10-29T10:00:00Z";
    static final String CHANNEL = "private-tasks." + repeat('A', 43);
    static final String SIG = repeat('a', 32) + repeat('0', 32);

    public static void main(String[] args) throws Exception {
        endpoint();
        metadata();
        socketIdAndSubscription();
        frames();
        codec();
        handshake();
        if (!failures.isEmpty()) {
            System.out.println("Tasks realtime wire: " + failures.size() + " of " + checks + " checks FAILED");
            for (String failure : failures) System.out.println("  FAIL " + failure);
            System.exit(1);
        }
        System.out.println("Tasks realtime wire + RFC 6455 codec: " + checks + " checks passed");
    }

    static void check(boolean condition, String name) {
        checks++;
        if (!condition) failures.add(name);
    }

    static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    // ---- endpoint ----

    static void endpoint() {
        TasksRealtimeWire.Endpoint e = TasksRealtimeWire.Endpoint.of("wss://api.example.org/realtime/app/" + KEY, BASE);
        check(e != null && "api.example.org".equals(e.host) && e.port == 443 && "https://api.example.org".equals(e.origin)
                && ("/realtime/app/" + KEY + "?protocol=7&client=zenlygram-android&version=1.0&flash=false").equals(e.pathAndQuery),
                "endpoint rebuilt from the origin and the validated key only");
        TasksRealtimeWire.Endpoint local = TasksRealtimeWire.Endpoint.of("wss://10.0.2.2/realtime/app/k", "https://10.0.2.2/v1");
        check(local != null && "10.0.2.2".equals(local.host) && "https://10.0.2.2".equals(local.origin), "local emulator origin accepted");
        TasksRealtimeWire.Endpoint port = TasksRealtimeWire.Endpoint.of("wss://h.example:8443/realtime/app/k", "https://h.example:8443/v1");
        check(port != null && port.port == 8443 && "h.example:8443".equals(port.authority), "explicit port kept from the API base");
        check(!e.toString().contains(KEY), "endpoint toString is redacted");
        String[] refused = {
                "wss://evil.example.org/realtime/app/" + KEY,          // foreign host
                "wss://api.example.org.evil/realtime/app/" + KEY,      // suffix host
                "wss://api.example.org:443/realtime/app/" + KEY,       // port the origin lacks
                "ws://api.example.org/realtime/app/" + KEY,            // not TLS
                "https://api.example.org/realtime/app/" + KEY,
                "wss://user@api.example.org/realtime/app/" + KEY,      // userinfo
                "wss://api.example.org/realtime/app/" + KEY + "?x=1",  // query
                "wss://api.example.org/realtime/app/" + KEY + "#f",    // fragment
                "wss://api.example.org/realtime/app/" + KEY + "/",     // trailing slash
                "wss://api.example.org/realtime/app/a%2Fb",            // percent escape
                "wss://api.example.org/realtime/app/../app/k",         // dot segment
                "wss://api.example.org/realtime/app/",                 // empty key
                "wss://api.example.org/realtime/app/" + repeat('k', 129),
                "wss://API.example.org/realtime/app/" + KEY,           // byte-for-byte authority
                "wss://api.example.org/app/" + KEY,                    // not the public proxy path
                "wss://api.example.org/realtime/app/kéy",
                null
        };
        for (String url : refused) check(TasksRealtimeWire.Endpoint.of(url, BASE) == null, "refused ws_url: " + url);
        check(TasksRealtimeWire.Endpoint.of("wss://api.example.org/realtime/app/" + repeat('k', 128), BASE) != null, "128-char key accepted");
        check(TasksRealtimeWire.Endpoint.of("wss://api.example.org/realtime/app/k", "https://api.example.org/v2") == null, "API base must be /v1");
        check(TasksRealtimeWire.Endpoint.of("wss://a@b/realtime/app/k", "https://a@b/v1") == null, "API base with userinfo refused");
        check(TasksRealtimeWire.Endpoint.of("wss://h:0/realtime/app/k", "https://h:0/v1") == null, "port 0 refused");
    }

    // ---- metadata ----

    static String meta(String enabled, String wsUrl, String timeout, String extra) {
        return "{\"schema_version\":1,\"enabled\":" + enabled + ",\"ws_url\":" + wsUrl + ",\"activity_timeout\":" + timeout
                + ",\"server_time\":\"" + T + "\"" + extra + "}";
    }

    static boolean invalidMeta(String json) {
        try {
            TasksRealtimeWire.decodeMetadata(json, BASE);
            return false;
        } catch (TasksWire.Invalid expected) {
            return true;
        }
    }

    static void metadata() {
        String ws = "\"wss://api.example.org/realtime/app/" + KEY + "\"";
        TasksRealtimeWire.Metadata on = TasksRealtimeWire.decodeMetadata(meta("true", ws, "30", ""), BASE);
        check(on.isEnabled() && on.activityTimeout == 30 && T.equals(on.serverTime), "enabled metadata decodes");
        TasksRealtimeWire.Metadata off = TasksRealtimeWire.decodeMetadata(meta("false", "null", "30", ""), BASE);
        check(!off.isEnabled() && off.endpoint == null, "enabled:false + ws_url:null is the kill switch");
        check(TasksRealtimeWire.decodeMetadata(meta("true", ws, "10", ""), BASE).activityTimeout == 10
                && TasksRealtimeWire.decodeMetadata(meta("true", ws, "120", ""), BASE).activityTimeout == 120, "timeout bounds 10 and 120 inclusive");
        check(invalidMeta(meta("true", ws, "9", "")), "timeout 9 refused (no clamping)");
        check(invalidMeta(meta("true", ws, "121", "")), "timeout 121 refused");
        check(invalidMeta(meta("true", ws, "30.0", "")), "fractional timeout refused");
        check(invalidMeta(meta("true", ws, "\"30\"", "")), "string timeout refused");
        check(invalidMeta(meta("true", "null", "30", "")), "enabled without ws_url refused");
        check(invalidMeta(meta("false", ws, "30", "")), "disabled with a ws_url refused");
        check(invalidMeta(meta("true", "\"wss://evil.example.org/realtime/app/k\"", "30", "")), "ws_url off the API origin refused");
        check(invalidMeta(meta("true", ws, "30", ",\"extra\":1")), "unknown member refused");
        check(invalidMeta(meta("true", ws, "30", "").replace("\"schema_version\":1", "\"schema_version\":2")), "schema_version 2 refused");
        check(invalidMeta(meta("true", ws, "30", "").replace(T, "yesterday")), "server_time must be an Instant");
        check(invalidMeta(meta("1", ws, "30", "")), "enabled must be a boolean");
        check(invalidMeta("{\"schema_version\":1,\"enabled\":true,\"ws_url\":" + ws + ",\"server_time\":\"" + T + "\"}"), "missing timeout refused");
        check(invalidMeta(meta("true", ws, "30", "") + " x"), "trailing data refused");
    }

    // ---- socket id / subscription ----

    static boolean invalidSubscription(String json, String socketId) {
        try {
            TasksRealtimeWire.decodeSubscription(json, socketId);
            return false;
        } catch (TasksWire.Invalid expected) {
            return true;
        }
    }

    static String sub(String channel, String auth) {
        return "{\"schema_version\":1,\"channel\":\"" + channel + "\",\"auth\":\"" + auth + "\",\"server_time\":\"" + T + "\"}";
    }

    static void socketIdAndSubscription() {
        check(TasksRealtimeWire.isValidSocketId("1.2") && TasksRealtimeWire.isValidSocketId("12345678901234567890.12345678901234567890"),
                "socket id: two 1..20 digit runs");
        for (String bad : new String[]{"", ".", "1.", ".1", "1.2.3", "a.1", "1.b", "123456789012345678901.1", "1.123456789012345678901",
                " 1.2", "1.2 ", "١.٢", "-1.2", "1,2", null}) {
            check(!TasksRealtimeWire.isValidSocketId(bad), "socket id refused: " + bad);
        }
        TasksRealtimeWire.Subscription s = TasksRealtimeWire.decodeSubscription(sub(CHANNEL, KEY + ":" + SIG), "1.2");
        check(s.socketId.equals("1.2") && s.channel.equals(CHANNEL) && s.appKey.equals(KEY), "subscription bound to its socket id and key");
        check(!s.toString().contains(SIG) && !s.toString().contains(CHANNEL), "subscription toString is redacted");
        check(invalidSubscription(sub(CHANNEL, KEY + ":" + SIG.toUpperCase()), "1.2"), "upper-case hex refused");
        check(invalidSubscription(sub(CHANNEL, KEY + ":" + SIG.substring(1)), "1.2"), "63-hex signature refused");
        check(invalidSubscription(sub(CHANNEL, KEY + ":" + SIG + "0"), "1.2"), "65-hex signature refused");
        check(invalidSubscription(sub(CHANNEL, ":" + SIG), "1.2"), "empty key refused");
        check(invalidSubscription(sub(CHANNEL, SIG), "1.2"), "auth without key refused");
        check(invalidSubscription(sub("private-tasks." + repeat('A', 42), KEY + ":" + SIG), "1.2"), "42-char channel token refused");
        check(invalidSubscription(sub("private-other." + repeat('A', 43), KEY + ":" + SIG), "1.2"), "foreign channel prefix refused");
        check(invalidSubscription(sub("private-tasks." + repeat('A', 42) + "=", KEY + ":" + SIG), "1.2"), "padded channel refused");
        check(invalidSubscription(sub(CHANNEL, KEY + ":" + SIG), "1.2.3"), "bad socket id refused before decoding");
        check(invalidSubscription(sub(CHANNEL, KEY + ":" + SIG).replace("}", ",\"socket_id\":\"1.2\"}"), "1.2"), "unknown member refused");
        check("{\"socket_id\":\"1.2\"}".equals(new String(TasksRealtimeWire.authRequestBody("1.2"), StandardCharsets.UTF_8)),
                "auth body is exactly {\"socket_id\"}");
        check(TasksRealtimeWire.subscribeFrame(s).equals("{\"event\":\"pusher:subscribe\",\"data\":{\"auth\":\"" + KEY + ":" + SIG
                + "\",\"channel\":\"" + CHANNEL + "\"}}"), "pusher:subscribe frame carries exactly the returned auth/channel");
    }

    // ---- Pusher frames ----

    static TasksRealtimeWire.Frame frame(String text) {
        try {
            return TasksRealtimeWire.parseFrame(text);
        } catch (TasksRealtimeWire.FrameException e) {
            return null;
        }
    }

    static boolean refusedFrame(String text) {
        return frame(text) == null;
    }

    static void frames() {
        TasksRealtimeWire.Frame established = frame("{\"event\":\"pusher:connection_established\",\"data\":\"{\\\"socket_id\\\":\\\"123.456\\\",\\\"activity_timeout\\\":30}\"}");
        check(established != null && established.kind == TasksRealtimeWire.Frame.Kind.CONNECTION_ESTABLISHED
                && "123.456".equals(established.socketId) && established.activityTimeout == 30, "connection_established with JSON-string data");
        TasksRealtimeWire.Frame object = frame("{\"event\":\"pusher:connection_established\",\"data\":{\"socket_id\":\"1.2\",\"activity_timeout\":120}}");
        check(object != null && object.activityTimeout == 120, "connection_established with object data");
        check(refusedFrame("{\"event\":\"pusher:connection_established\",\"data\":\"{\\\"socket_id\\\":\\\"1.2\\\"}\"}"), "missing activity_timeout refused (G3)");
        check(refusedFrame("{\"event\":\"pusher:connection_established\",\"data\":\"{\\\"socket_id\\\":\\\"1.2\\\",\\\"activity_timeout\\\":121}\"}"), "timeout 121 refused");
        check(refusedFrame("{\"event\":\"pusher:connection_established\",\"data\":\"{\\\"socket_id\\\":\\\"1.2\\\",\\\"activity_timeout\\\":\\\"30\\\"}\"}"), "string timeout refused");
        check(refusedFrame("{\"event\":\"pusher:connection_established\",\"data\":\"{\\\"socket_id\\\":\\\"x\\\",\\\"activity_timeout\\\":30}\"}"), "bad socket id refused");
        check(refusedFrame("{\"event\":\"pusher:connection_established\"}"), "established without data refused");

        String changedString = "{\"event\":\"tasks.changed\",\"channel\":\"" + CHANNEL + "\",\"data\":\"{\\\"v\\\":1}\"}";
        String changedObject = "{\"event\":\"tasks.changed\",\"channel\":\"" + CHANNEL + "\",\"data\":{\"v\":1}}";
        TasksRealtimeWire.Frame a = frame(changedString), b = frame(changedObject);
        check(a != null && a.kind == TasksRealtimeWire.Frame.Kind.CHANGED && CHANNEL.equals(a.channel), "tasks.changed with string data {\"v\":1}");
        check(b != null && b.kind == TasksRealtimeWire.Frame.Kind.CHANGED, "tasks.changed with already-decoded object data (G2)");
        for (String data : new String[]{"\"{\\\"v\\\":2}\"", "{\"v\":2}", "{\"v\":1,\"task\":\"x\"}", "\"{}\"", "{\"v\":\"1\"}", "{\"v\":1.0}", "\"[1]\"",
                "\"not json\"", "null"}) {
            TasksRealtimeWire.Frame other = frame("{\"event\":\"tasks.changed\",\"channel\":\"" + CHANNEL + "\",\"data\":" + data + "}");
            check(other != null && other.kind == TasksRealtimeWire.Frame.Kind.OTHER, "tasks.changed with data " + data + " is ignored, not a hint");
        }
        check(refusedFrame("{\"event\":\"tasks.changed\",\"data\":{\"v\":1}}"), "tasks.changed without channel refused");

        check(frame("{\"event\":\"pusher_internal:subscription_succeeded\",\"channel\":\"" + CHANNEL + "\",\"data\":\"{}\"}").kind
                == TasksRealtimeWire.Frame.Kind.SUBSCRIPTION_SUCCEEDED, "ack with \"{}\"");
        check(frame("{\"event\":\"pusher_internal:subscription_succeeded\",\"channel\":\"" + CHANNEL + "\",\"data\":{}}").kind
                == TasksRealtimeWire.Frame.Kind.SUBSCRIPTION_SUCCEEDED, "ack with {}");
        check(refusedFrame("{\"event\":\"pusher_internal:subscription_succeeded\",\"channel\":\"" + CHANNEL + "\",\"data\":\"{\\\"presence\\\":1}\"}"), "non-empty ack refused");
        check(refusedFrame("{\"event\":\"pusher_internal:subscription_succeeded\",\"data\":\"{}\"}"), "ack without channel refused");
        check(refusedFrame("{\"event\":\"pusher_internal:subscription_succeeded\",\"channel\":\"" + CHANNEL + "\"}"), "ack without data refused");

        check(frame("{\"event\":\"pusher:ping\"}").kind == TasksRealtimeWire.Frame.Kind.PING, "data-less ping");
        check(frame("{\"event\":\"pusher:pong\",\"data\":{}}").kind == TasksRealtimeWire.Frame.Kind.PONG, "pong");
        TasksRealtimeWire.Frame error = frame("{\"event\":\"pusher:error\",\"data\":{\"message\":\"x\",\"code\":4001}}");
        check(error.kind == TasksRealtimeWire.Frame.Kind.ERROR && error.code == 4001, "pusher:error carries its code");
        TasksRealtimeWire.Frame unreadable = frame("{\"event\":\"pusher:error\",\"data\":\"{oops\"}");
        check(unreadable != null && unreadable.kind == TasksRealtimeWire.Frame.Kind.ERROR && unreadable.code == null, "unreadable error still ends, no code");
        check(frame("{\"event\":\"some.other\",\"data\":{\"x\":[1,2]}}").kind == TasksRealtimeWire.Frame.Kind.OTHER, "unknown events ignored");

        check(refusedFrame("not json"), "malformed JSON refused");
        check(refusedFrame("[1]"), "non-object refused");
        check(refusedFrame("{\"data\":{}}"), "missing event refused");
        check(refusedFrame("{\"event\":\"pusher:ping\",\"event\":\"pusher:pong\"}"), "duplicate keys refused");
        String big = "{\"event\":\"x\",\"data\":\"" + repeat('a', TasksRealtimeWire.MAX_FRAME_BYTES) + "\"}";
        try {
            TasksRealtimeWire.parseFrame(big);
            check(false, "oversized frame refused");
        } catch (TasksRealtimeWire.FrameException e) {
            check(e.tooLarge, "oversized frame refused as too large");
        }
        String multibyte = "{\"event\":\"x\",\"data\":\"" + repeat('é', 4100) + "\"}"; // < 8192 chars, > 8192 UTF-8 bytes
        check(refusedFrame(multibyte), "the bound is UTF-8 bytes, not UTF-16 units");
    }

    // ---- RFC 6455 codec ----

    static byte[] serverFrame(boolean fin, int opcode, byte[] payload, boolean masked, int rsv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0) | rsv | opcode);
        int len = payload.length;
        int maskBit = masked ? 0x80 : 0;
        if (len < 126) {
            out.write(maskBit | len);
        } else if (len <= 0xFFFF) {
            out.write(maskBit | 126);
            out.write(len >>> 8);
            out.write(len & 0xFF);
        } else {
            out.write(maskBit | 127);
            for (int i = 7; i >= 0; i--) out.write((int) ((long) len >>> (8 * i)) & 0xFF);
        }
        if (masked) out.write(new byte[]{1, 2, 3, 4}, 0, 4);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    static int protocolError(byte[] bytes, int max) {
        try {
            TasksRealtimeFrames.read(new ByteArrayInputStream(bytes), max);
            return 0;
        } catch (TasksRealtimeFrames.ProtocolError e) {
            return e.closeCode;
        } catch (IOException e) {
            return -2;
        }
    }

    static void codec() throws Exception {
        byte[] mask = {(byte) 0x37, (byte) 0xfa, (byte) 0x21, (byte) 0x3d};
        byte[] hello = TasksRealtimeFrames.encode(TasksRealtimeFrames.OP_TEXT, "Hello".getBytes(StandardCharsets.UTF_8), mask);
        // RFC 6455 §5.7 example: a single-frame masked text message "Hello".
        byte[] rfc = {(byte) 0x81, (byte) 0x85, 0x37, (byte) 0xfa, 0x21, 0x3d, 0x7f, (byte) 0x9f, 0x4d, 0x51, 0x58};
        check(Arrays.equals(hello, rfc), "client text frame matches the RFC 6455 §5.7 masked example");
        byte[] mid = TasksRealtimeFrames.encode(TasksRealtimeFrames.OP_TEXT, new byte[126], mask);
        check(mid.length == 2 + 2 + 4 + 126 && (mid[1] & 0xFF) == (0x80 | 126) && mid[2] == 0 && mid[3] == 126, "126-byte payload uses the 16-bit length");
        byte[] large = TasksRealtimeFrames.encode(TasksRealtimeFrames.OP_BINARY, new byte[65536], mask);
        check(large.length == 2 + 8 + 4 + 65536 && (large[1] & 0xFF) == (0x80 | 127) && large[7] == 1 && large[8] == 0, "65536-byte payload uses the 64-bit length");
        check((hello[1] & 0x80) != 0 && (mid[1] & 0x80) != 0, "every client frame is masked");
        byte[] random1 = TasksRealtimeFrames.text("x", new Random(1)), random2 = TasksRealtimeFrames.text("x", new Random(2));
        check(!Arrays.equals(Arrays.copyOfRange(random1, 2, 6), Arrays.copyOfRange(random2, 2, 6)), "a fresh mask per frame");
        try {
            TasksRealtimeFrames.encode(TasksRealtimeFrames.OP_PING, new byte[126], mask);
            check(false, "control payload > 125 refused on encode");
        } catch (IllegalArgumentException expected) {
            check(true, "control payload > 125 refused on encode");
        }
        check(Arrays.equals(TasksRealtimeFrames.closePayload(1001), new byte[]{0x03, (byte) 0xE9}), "close payload is the big-endian code");

        TasksRealtimeFrames.Frame read = TasksRealtimeFrames.read(new ByteArrayInputStream(serverFrame(true, 1, "hi".getBytes(StandardCharsets.UTF_8), false, 0)), 8192);
        check(read.fin && read.opcode == 1 && "hi".equals(new String(read.payload, StandardCharsets.UTF_8)), "unmasked server text frame read");
        TasksRealtimeFrames.Frame mid16 = TasksRealtimeFrames.read(new ByteArrayInputStream(serverFrame(true, 1, new byte[300], false, 0)), 8192);
        check(mid16.payload.length == 300, "16-bit length server frame read");
        check(protocolError(serverFrame(true, 1, new byte[3], true, 0), 8192) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR, "a masked server frame is a protocol error");
        check(protocolError(serverFrame(true, 1, new byte[3], false, 0x40), 8192) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR, "RSV1 without an extension is a protocol error");
        check(protocolError(serverFrame(true, 3, new byte[0], false, 0), 8192) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR, "reserved opcode refused");
        check(protocolError(serverFrame(true, 9, new byte[126], false, 0), 8192) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR, "control frame > 125 bytes refused");
        check(protocolError(serverFrame(false, 9, new byte[1], false, 0), 8192) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR, "fragmented control frame refused");
        check(protocolError(serverFrame(true, 1, new byte[8193], false, 0), 8192) == TasksRealtimeFrames.CLOSE_TOO_BIG, "frame over 8192 bytes refused (1009)");
        check(protocolError(new byte[]{(byte) 0x81, 127, (byte) 0x80, 0, 0, 0, 0, 0, 0, 1}, 8192) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR, "negative 64-bit length refused");
        check(protocolError(new byte[]{(byte) 0x81, 126, 0, 125}, 8192) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR, "non-minimal 16-bit length (125) refused");
        check(protocolError(concat(new byte[]{(byte) 0x81, 127, 0, 0, 0, 0, 0, 0, 0x01, 0x2C}, new byte[300]), 8192) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR,
                "non-minimal 64-bit length (300) refused");
        check(protocolError(concat(new byte[]{(byte) 0x81, 126, 0, 126}, new byte[126]), 8192) == 0, "minimal 16-bit length (126) accepted");
        check(protocolError(new byte[]{(byte) 0x81, 5, 'h', 'i'}, 8192) == -2, "EOF mid-frame is a lost connection");
        check(protocolError(new byte[]{(byte) 0x81}, 8192) == -2, "EOF in the header is a lost connection");

        // Fragmentation with an interleaved ping (§5.4), then a close.
        InputStream stream = new ByteArrayInputStream(concat(
                serverFrame(false, 1, "{\"event\":".getBytes(StandardCharsets.UTF_8), false, 0),
                serverFrame(true, 9, "p".getBytes(StandardCharsets.UTF_8), false, 0),
                serverFrame(false, 0, "\"pusher:".getBytes(StandardCharsets.UTF_8), false, 0),
                serverFrame(true, 0, "ping\"}".getBytes(StandardCharsets.UTF_8), false, 0),
                serverFrame(true, 8, new byte[]{0x0F, (byte) 0xA1, 'b', 'y', 'e'}, false, 0)));
        TasksRealtimeFrames.Assembler assembler = new TasksRealtimeFrames.Assembler(8192);
        check(assembler.accept(TasksRealtimeFrames.read(stream, 8192)) == null, "first fragment buffers");
        TasksRealtimeFrames.Message ping = assembler.accept(TasksRealtimeFrames.read(stream, 8192));
        check(ping.opcode == TasksRealtimeFrames.OP_PING && Arrays.equals(ping.payload, "p".getBytes(StandardCharsets.UTF_8)), "interleaved ping delivered with its payload");
        check(assembler.accept(TasksRealtimeFrames.read(stream, 8192)) == null, "middle continuation buffers");
        TasksRealtimeFrames.Message whole = assembler.accept(TasksRealtimeFrames.read(stream, 8192));
        check(whole.opcode == TasksRealtimeFrames.OP_TEXT && "{\"event\":\"pusher:ping\"}".equals(whole.text), "fragments reassembled into one text message");
        TasksRealtimeFrames.Message close = assembler.accept(TasksRealtimeFrames.read(stream, 8192));
        check(close.opcode == TasksRealtimeFrames.OP_CLOSE && close.closeCode == 4001, "close frame code parsed (4001)");
        try {
            TasksRealtimeFrames.read(stream, 8192);
            check(false, "end of stream after close");
        } catch (EOFException expected) {
            check(true, "end of stream after close");
        }

        check(assemblerError(new TasksRealtimeFrames.Frame(true, 0, new byte[1])) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR, "continuation without a start refused");
        TasksRealtimeFrames.Assembler started = new TasksRealtimeFrames.Assembler(8192);
        started.accept(new TasksRealtimeFrames.Frame(false, 1, new byte[1]));
        check(assemblerError(started, new TasksRealtimeFrames.Frame(true, 1, new byte[1])) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR, "a new text frame mid-message refused");
        TasksRealtimeFrames.Assembler bounded = new TasksRealtimeFrames.Assembler(8192);
        bounded.accept(new TasksRealtimeFrames.Frame(false, 1, new byte[8000]));
        check(assemblerError(bounded, new TasksRealtimeFrames.Frame(true, 0, new byte[193])) == TasksRealtimeFrames.CLOSE_TOO_BIG, "reassembled message bounded to 8192 bytes");
        check(assemblerError(new TasksRealtimeFrames.Frame(true, 1, new byte[]{(byte) 0xC3, 0x28})) == TasksRealtimeFrames.CLOSE_INVALID_DATA, "invalid UTF-8 text refused (1007)");
        check(assemblerError(new TasksRealtimeFrames.Frame(true, 8, new byte[]{3})) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR, "1-byte close payload refused");
        for (int code : new int[]{999, 1004, 1005, 1006, 1015, 1016, 2999, 5000, 65535}) {
            check(assemblerError(new TasksRealtimeFrames.Frame(true, 8, TasksRealtimeFrames.closePayload(code))) == TasksRealtimeFrames.CLOSE_PROTOCOL_ERROR,
                    "peer close code " + code + " refused, never echoed");
        }
        for (int code : new int[]{1000, 1001, 1002, 1003, 1007, 1011, 1014, 3000, 4001, 4999}) {
            check(assemblerError(new TasksRealtimeFrames.Frame(true, 8, TasksRealtimeFrames.closePayload(code))) == 0, "peer close code " + code + " accepted");
        }

        TasksCloudBoards boards = new TasksCloudBoards();
        Object first = new Object(), second = new Object();
        check(boards.show(first) && boards.show(second), "every board resume starts the engine");
        check(!boards.hide(first), "a second board of the same account keeps sync/realtime running");
        check(!boards.hide(first), "a repeated pause of the same board (removal after pause) changes nothing");
        check(boards.hide(second), "the last board pausing stops them");
        check(!boards.hide(new Object()), "an unknown board stops nothing");
        TasksRealtimeFrames.Message empty = new TasksRealtimeFrames.Assembler(8192).accept(new TasksRealtimeFrames.Frame(true, 8, new byte[0]));
        check(empty.closeCode == TasksRealtimeFrames.NO_CODE, "empty close has no code");
        TasksRealtimeFrames.Message binary = new TasksRealtimeFrames.Assembler(8192).accept(new TasksRealtimeFrames.Frame(true, 2, new byte[]{1}));
        check(binary.opcode == TasksRealtimeFrames.OP_BINARY, "binary message surfaced (the Pusher socket treats it as a violation)");
    }

    static int assemblerError(TasksRealtimeFrames.Frame frame) {
        return assemblerError(new TasksRealtimeFrames.Assembler(8192), frame);
    }

    static int assemblerError(TasksRealtimeFrames.Assembler assembler, TasksRealtimeFrames.Frame frame) {
        try {
            assembler.accept(frame);
            return 0;
        } catch (TasksRealtimeFrames.ProtocolError e) {
            return e.closeCode;
        }
    }

    // ---- handshake ----

    static boolean handshakeRefused(String response, String key) {
        try {
            TasksRealtimeFrames.readResponse(new ByteArrayInputStream(response.getBytes(StandardCharsets.ISO_8859_1)), key);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    static void handshake() throws Exception {
        check("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".equals(TasksRealtimeFrames.accept("dGhlIHNhbXBsZSBub25jZQ==")), "Sec-WebSocket-Accept matches RFC 6455 §1.3");
        check("".equals(TasksRealtimeFrames.base64(new byte[0])) && "Zg==".equals(TasksRealtimeFrames.base64("f".getBytes(StandardCharsets.US_ASCII)))
                && "Zm8=".equals(TasksRealtimeFrames.base64("fo".getBytes(StandardCharsets.US_ASCII)))
                && "Zm9vYmFy".equals(TasksRealtimeFrames.base64("foobar".getBytes(StandardCharsets.US_ASCII))), "base64 RFC 4648 vectors");
        String key = TasksRealtimeFrames.newKey(new Random(7));
        check(key.length() == 24 && key.endsWith("=="), "Sec-WebSocket-Key is base64 of 16 bytes");

        TasksRealtimeWire.Endpoint endpoint = TasksRealtimeWire.Endpoint.of("wss://10.0.2.2/realtime/app/" + KEY, "https://10.0.2.2/v1");
        String request = TasksRealtimeFrames.request(endpoint, key);
        check(request.equals("GET /realtime/app/" + KEY + "?protocol=7&client=zenlygram-android&version=1.0&flash=false HTTP/1.1\r\n"
                + "Host: 10.0.2.2\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\nOrigin: https://10.0.2.2\r\n\r\n"), "upgrade request: fixed path/query, Host, Origin, nothing else");
        String lower = request.toLowerCase();
        check(!lower.contains("cookie") && !lower.contains("authorization") && !lower.contains("x-zenly"), "no cookie or credential header in the handshake");

        String accept = TasksRealtimeFrames.accept(key);
        String ok = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n";
        ByteArrayInputStream withFrame = new ByteArrayInputStream(concat(ok.getBytes(StandardCharsets.ISO_8859_1), serverFrame(true, 1, "x".getBytes(StandardCharsets.UTF_8), false, 0)));
        TasksRealtimeFrames.readResponse(withFrame, key);
        check(TasksRealtimeFrames.read(withFrame, 8192).payload[0] == 'x', "the first frame after the response head stays readable");
        check(!handshakeRefused("HTTP/1.1 101 Switching Protocols\r\nupgrade: WebSocket\r\nconnection: keep-alive, Upgrade\r\nsec-websocket-accept: " + accept + "\r\n\r\n", key),
                "header names/values case-insensitive, Connection token list");
        check(handshakeRefused("HTTP/1.1 301 Moved Permanently\r\nLocation: wss://evil/\r\n\r\n", key), "a redirect is refused, never followed");
        check(handshakeRefused("HTTP/1.1 200 OK\r\n\r\n", key), "a 200 is refused");
        check(handshakeRefused("HTTP/1.1 1010 x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n", key), "status must be exactly 101");
        check(handshakeRefused(ok.replace(accept, TasksRealtimeFrames.accept("other")), key), "a wrong Sec-WebSocket-Accept is refused");
        check(handshakeRefused(ok.replace("Upgrade: websocket\r\n", ""), key), "missing Upgrade refused");
        check(handshakeRefused(ok.replace("Connection: Upgrade", "Connection: close"), key), "Connection without upgrade refused");
        check(handshakeRefused(ok.replace("\r\n\r\n", "\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n"), key), "an unrequested extension is refused");
        check(handshakeRefused(ok.replace("\r\n\r\n", "\r\nSec-WebSocket-Protocol: chat\r\n\r\n"), key), "an unrequested subprotocol is refused");
        check(handshakeRefused("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n", key), "a truncated head is refused");
        check(handshakeRefused("HTTP/1.1 101 x\r\nX: " + repeat('a', 9000) + "\r\n\r\n", key), "a response head over 8 KiB is refused");
    }
}
