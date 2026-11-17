package org.telegram.messenger.zenlygram.tasks.cloud;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Random;

/**
 * Minimal RFC 6455 client codec for the Tasks realtime socket (no WebSocket library is a dependency of this app):
 * the opening handshake (request text, Sec-WebSocket-Accept check, no redirects/extensions/subprotocols), masked
 * client frames, and bounded server-frame reading with fragmentation reassembly and control frames. Pure Java,
 * host-tested; the socket I/O itself lives in {@link TasksRealtimeWebSocket}.
 */
final class TasksRealtimeFrames {
    static final int OP_CONTINUATION = 0x0, OP_TEXT = 0x1, OP_BINARY = 0x2, OP_CLOSE = 0x8, OP_PING = 0x9, OP_PONG = 0xA;
    static final int MAX_CONTROL_PAYLOAD = 125;
    static final int CLOSE_NORMAL = 1000, CLOSE_GOING_AWAY = 1001, CLOSE_PROTOCOL_ERROR = 1002, CLOSE_INVALID_DATA = 1007,
            CLOSE_TOO_BIG = 1009;
    /** No status code was present in a received close frame. */
    static final int NO_CODE = -1;
    static final int MAX_HANDSHAKE_BYTES = 8192;
    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private TasksRealtimeFrames() { }

    /** A peer that broke RFC 6455; {@link #closeCode} is what this client sends before dropping the connection. */
    static final class ProtocolError extends IOException {
        private static final long serialVersionUID = 1L;
        final int closeCode;

        ProtocolError(int closeCode, String reason) {
            super(reason);
            this.closeCode = closeCode;
        }
    }

    // ---- Client frames (always FIN, always masked) ---------------------------------------------

    static byte[] encode(int opcode, byte[] payload, byte[] mask) {
        if (mask == null || mask.length != 4) throw new IllegalArgumentException("mask");
        int length = payload.length;
        if ((opcode & 0x8) != 0 && length > MAX_CONTROL_PAYLOAD) throw new IllegalArgumentException("control payload");
        int header = length < 126 ? 2 : length <= 0xFFFF ? 4 : 10;
        byte[] out = new byte[header + 4 + length];
        out[0] = (byte) (0x80 | opcode);
        if (length < 126) {
            out[1] = (byte) (0x80 | length);
        } else if (length <= 0xFFFF) {
            out[1] = (byte) (0x80 | 126);
            out[2] = (byte) (length >>> 8);
            out[3] = (byte) length;
        } else {
            out[1] = (byte) (0x80 | 127);
            long value = length;
            for (int i = 0; i < 8; i++) out[2 + i] = (byte) (value >>> (8 * (7 - i)));
        }
        System.arraycopy(mask, 0, out, header, 4);
        for (int i = 0; i < length; i++) out[header + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        return out;
    }

    static byte[] text(String text, Random random) {
        return encode(OP_TEXT, text.getBytes(StandardCharsets.UTF_8), mask(random));
    }

    static byte[] control(int opcode, byte[] payload, Random random) {
        return encode(opcode, payload, mask(random));
    }

    static byte[] closePayload(int code) {
        return new byte[]{(byte) (code >>> 8), (byte) code};
    }

    private static byte[] mask(Random random) {
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        return mask;
    }

    // ---- Server frames ------------------------------------------------------------------------

    static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    /**
     * One server frame: no RSV bits (no extension was negotiated), known opcode, never masked (§5.1), control frames
     * final and at most 125 bytes, a payload of at most {@code maxPayload} bytes. EOF before a complete frame throws
     * {@link EOFException}.
     */
    static Frame read(InputStream in, int maxPayload) throws IOException {
        int b0 = readByte(in), b1 = readByte(in);
        boolean fin = (b0 & 0x80) != 0;
        if ((b0 & 0x70) != 0) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "rsv");
        int opcode = b0 & 0x0F;
        boolean control = (opcode & 0x8) != 0;
        if (opcode != OP_CONTINUATION && opcode != OP_TEXT && opcode != OP_BINARY && opcode != OP_CLOSE && opcode != OP_PING && opcode != OP_PONG) {
            throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "opcode");
        }
        if ((b1 & 0x80) != 0) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "masked");
        long length = b1 & 0x7F;
        if (length == 126) {
            length = ((long) readByte(in) << 8) | readByte(in);
            if (length < 126) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "length"); // §5.2: minimal encoding
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) length = (length << 8) | readByte(in);
            if (length < 0) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "length");
            if (length <= 0xFFFF) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "length"); // §5.2: minimal encoding
        }
        if (control && (!fin || length > MAX_CONTROL_PAYLOAD)) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "control");
        if (length > maxPayload) throw new ProtocolError(CLOSE_TOO_BIG, "size");
        byte[] payload = new byte[(int) length];
        int offset = 0;
        while (offset < payload.length) {
            int read = in.read(payload, offset, payload.length - offset);
            if (read < 0) throw new EOFException();
            offset += read;
        }
        return new Frame(fin, opcode, payload);
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    /** One complete message (or control frame) after reassembly. */
    static final class Message {
        final int opcode;
        /** TEXT only. */
        final String text;
        /** PING/PONG payload (echoed by PONG). */
        final byte[] payload;
        /** CLOSE only; {@link #NO_CODE} when absent. */
        final int closeCode;

        private Message(int opcode, String text, byte[] payload, int closeCode) {
            this.opcode = opcode;
            this.text = text;
            this.payload = payload;
            this.closeCode = closeCode;
        }
    }

    /**
     * Reassembles fragmented data messages (control frames may interleave, §5.4); the whole message is bounded by
     * {@code maxMessage}. TEXT must be strict UTF-8. Not thread-safe: one per reader.
     */
    static final class Assembler {
        private final int maxMessage;
        private ByteArrayOutputStream fragments;
        private int fragmentedOpcode;

        Assembler(int maxMessage) {
            this.maxMessage = maxMessage;
        }

        /** Null while a fragmented message is incomplete. */
        Message accept(Frame frame) throws ProtocolError {
            switch (frame.opcode) {
                case OP_PING:
                case OP_PONG:
                    return new Message(frame.opcode, null, frame.payload, NO_CODE);
                case OP_CLOSE:
                    return close(frame.payload);
                case OP_TEXT:
                case OP_BINARY:
                    if (fragments != null) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "interleaved");
                    if (frame.fin) return data(frame.opcode, frame.payload);
                    fragments = new ByteArrayOutputStream();
                    fragmentedOpcode = frame.opcode;
                    append(frame.payload);
                    return null;
                case OP_CONTINUATION:
                    if (fragments == null) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "continuation");
                    append(frame.payload);
                    if (!frame.fin) return null;
                    byte[] whole = fragments.toByteArray();
                    fragments = null;
                    return data(fragmentedOpcode, whole);
                default:
                    throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "opcode");
            }
        }

        private void append(byte[] payload) throws ProtocolError {
            if (fragments.size() + payload.length > maxMessage) throw new ProtocolError(CLOSE_TOO_BIG, "size");
            fragments.write(payload, 0, payload.length);
        }

        private Message data(int opcode, byte[] payload) throws ProtocolError {
            if (payload.length > maxMessage) throw new ProtocolError(CLOSE_TOO_BIG, "size");
            if (opcode == OP_BINARY) return new Message(OP_BINARY, null, payload, NO_CODE);
            return new Message(OP_TEXT, utf8(payload), null, NO_CODE);
        }

        private static Message close(byte[] payload) throws ProtocolError {
            if (payload.length == 0) return new Message(OP_CLOSE, null, payload, NO_CODE);
            if (payload.length == 1) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "close");
            int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            // §7.4: never echo 1005/1006/1015, reserved or unassigned codes onto the wire.
            if (!isValidCloseCode(code)) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "close_code");
            if (payload.length > 2) utf8(java.util.Arrays.copyOfRange(payload, 2, payload.length));
            return new Message(OP_CLOSE, null, payload, code);
        }
    }

    /** A code a peer may send (RFC 6455 §7.4.1 + the IANA registry 1012–1014) and this client may therefore echo. */
    static boolean isValidCloseCode(int code) {
        return (code >= 1000 && code <= 1003) || (code >= 1007 && code <= 1014) || (code >= 3000 && code <= 4999);
    }

    static String utf8(byte[] bytes) throws ProtocolError {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolError(CLOSE_INVALID_DATA, "utf8");
        }
    }

    // ---- Opening handshake --------------------------------------------------------------------

    /** Base64 of 16 random bytes. */
    static String newKey(Random random) {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        return base64(nonce);
    }

    static String accept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return base64(sha1.digest((key + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The exact upgrade request: the rebuilt path, Host, the canonical Origin and nothing else — no cookie or credential. */
    static String request(TasksRealtimeWire.Endpoint endpoint, String key) {
        return "GET " + endpoint.pathAndQuery + " HTTP/1.1\r\n"
                + "Host: " + endpoint.authority + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: " + endpoint.origin + "\r\n"
                + "\r\n";
    }

    /**
     * Reads the response head byte by byte (so the first frame stays in {@code in}), bounded, and requires exactly
     * {@code HTTP/1.1 101} with Upgrade/Connection and the matching Sec-WebSocket-Accept. A redirect, any other status,
     * a negotiated extension or subprotocol is refused.
     */
    static void readResponse(InputStream in, String key) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(512);
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            if (head.size() >= MAX_HANDSHAKE_BYTES) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "handshake_size");
            head.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        if (lines.length == 0 || !lines[0].startsWith("HTTP/1.1 101")
                || (lines[0].length() > 12 && lines[0].charAt(12) != ' ')) {
            throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "status");
        }
        boolean upgrade = false, connection = false, accepted = false;
        String expected = accept(key);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "header");
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (name) {
                case "upgrade":
                    upgrade = "websocket".equalsIgnoreCase(value);
                    break;
                case "connection":
                    for (String token : value.split(",")) if ("upgrade".equalsIgnoreCase(token.trim())) connection = true;
                    break;
                case "sec-websocket-accept":
                    accepted = expected.equals(value);
                    break;
                case "sec-websocket-extensions":
                case "sec-websocket-protocol":
                    throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "negotiation");
                default:
                    break;
            }
        }
        if (!upgrade || !connection || !accepted) throw new ProtocolError(CLOSE_PROTOCOL_ERROR, "handshake");
    }

    private static final char[] BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Standard padded base64 (java.util.Base64 is API 26+; minSdk is 23). */
    static String base64(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length + 2) / 3 * 4);
        for (int i = 0; i < bytes.length; i += 3) {
            int b0 = bytes[i] & 0xFF;
            int b1 = i + 1 < bytes.length ? bytes[i + 1] & 0xFF : 0;
            int b2 = i + 2 < bytes.length ? bytes[i + 2] & 0xFF : 0;
            out.append(BASE64[b0 >>> 2]).append(BASE64[((b0 & 0x3) << 4) | (b1 >>> 4)]);
            out.append(i + 1 < bytes.length ? BASE64[((b1 & 0xF) << 2) | (b2 >>> 6)] : '=');
            out.append(i + 2 < bytes.length ? BASE64[b2 & 0x3F] : '=');
        }
        return out.toString();
    }
}
