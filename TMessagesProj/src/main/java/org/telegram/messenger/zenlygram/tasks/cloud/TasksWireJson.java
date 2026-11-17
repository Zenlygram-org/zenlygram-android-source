package org.telegram.messenger.zenlygram.tasks.cloud;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict bounded JSON for the Tasks wire, on the existing Gson 2.11 streaming reader (STRICT
 * strictness: no comments, single quotes, unquoted names, NaN, raw control characters or invalid
 * escapes). On top of that it enforces what JsonReader does not: duplicate object keys, a nesting
 * limit, strict UTF-8 input without BOM, well-formed UTF-16 strings (no lone surrogates) and exactly
 * one top-level value. Numbers keep their literal text ({@link Num}), so integer-vs-fraction/exponent
 * is decided by the schema decoder, never by a double round trip.
 *
 * Tree values: {@code String} (JSON string), {@link Num}, {@code Boolean}, {@link #NULL},
 * {@code Map<String,Object>} (unmodifiable, document order) and {@code List<Object>} (unmodifiable).
 *
 * The writer emits the R3 canonical form: compact, raw UTF-8 (including U+2028/U+2029 and '/'),
 * only quote/backslash/\b\t\n\f\r escaped and other U+0000..U+001F as lowercase \\u00xx.
 */
final class TasksWireJson {
    static final int MAX_DEPTH = 16;
    static final Object NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    /** A JSON number literal exactly as written. */
    static final class Num {
        final String literal;
        Num(String literal) { this.literal = literal; }
        @Override public boolean equals(Object other) { return other instanceof Num && ((Num) other).literal.equals(literal); }
        @Override public int hashCode() { return literal.hashCode(); }
    }

    private TasksWireJson() { }

    /** Strict UTF-8 decoding (malformed or unmappable input rejected, never replaced). */
    static String utf8(byte[] bytes, int maxBytes) {
        if (bytes == null) throw new TasksWire.Invalid("body");
        if (bytes.length > maxBytes) throw new TasksWire.Invalid("size");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new TasksWire.Invalid("utf8");
        }
    }

    /** Parses exactly one JSON value of at most {@code maxChars} UTF-16 units. */
    static Object parse(String text, int maxChars) {
        if (text == null) throw new TasksWire.Invalid("body");
        if (text.length() > maxChars) throw new TasksWire.Invalid("size");
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') throw new TasksWire.Invalid("bom");
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setStrictness(Strictness.STRICT);
        try {
            Object value = value(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new TasksWire.Invalid("trailing");
            return value;
        } catch (TasksWire.Invalid invalid) {
            throw invalid;
        } catch (IOException | RuntimeException syntax) {
            // MalformedJsonException, IllegalStateException, NumberFormatException: never echo the input.
            throw new TasksWire.Invalid("syntax");
        }
    }

    private static Object value(JsonReader reader, int depth) throws IOException {
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT: {
                if (depth + 1 > MAX_DEPTH) throw new TasksWire.Invalid("depth");
                reader.beginObject();
                Map<String, Object> object = new LinkedHashMap<>();
                while (reader.hasNext()) {
                    String name = wellFormed(reader.nextName(), "key");
                    if (object.containsKey(name)) throw new TasksWire.Invalid("duplicate_key");
                    object.put(name, value(reader, depth + 1));
                }
                reader.endObject();
                return Collections.unmodifiableMap(object);
            }
            case BEGIN_ARRAY: {
                if (depth + 1 > MAX_DEPTH) throw new TasksWire.Invalid("depth");
                reader.beginArray();
                List<Object> array = new ArrayList<>();
                while (reader.hasNext()) array.add(value(reader, depth + 1));
                reader.endArray();
                return Collections.unmodifiableList(array);
            }
            case STRING:
                return wellFormed(reader.nextString(), "string");
            case NUMBER:
                return new Num(reader.nextString());
            case BOOLEAN:
                return reader.nextBoolean();
            case NULL:
                reader.nextNull();
                return NULL;
            default:
                throw new TasksWire.Invalid("syntax");
        }
    }

    /** Rejects lone surrogates (e.g. a JSON escape of U+D800): not a Unicode scalar sequence. */
    static String wellFormed(String value, String field) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw new TasksWire.Invalid(field);
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new TasksWire.Invalid(field);
            }
        }
        return value;
    }

    // ---- Canonical writer ------------------------------------------------------------------

    /** Ordered object for the writer; keys are written in insertion order. */
    static final class Obj {
        final LinkedHashMap<String, Object> members = new LinkedHashMap<>();
        Obj put(String key, Object value) { members.put(key, value); return this; }
    }

    /** Canonical UTF-8 bytes of a tree of String/Integer/Long/Boolean/null/List/Obj. */
    static byte[] canonicalBytes(Object value) {
        return canonical(value).getBytes(StandardCharsets.UTF_8);
    }

    static String canonical(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null || value == NULL) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Integer || value instanceof Long) {
            out.append(((Number) value).longValue());
        } else if (value instanceof Boolean) {
            out.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, item);
            }
            out.append(']');
        } else if (value instanceof Obj) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> member : ((Obj) value).members.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(out, member.getKey());
                out.append(':');
                write(out, member.getValue());
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("Unsupported canonical value");
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static void writeString(StringBuilder out, String value) {
        wellFormed(value, "string");
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\t': out.append("\\t"); break;
                case '\n': out.append("\\n"); break;
                case '\f': out.append("\\f"); break;
                case '\r': out.append("\\r"); break;
                default:
                    if (c < 0x20) out.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xf]);
                    else out.append(c);
            }
        }
        out.append('"');
    }
}
