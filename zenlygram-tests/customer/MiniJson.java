package org.telegram.messenger.zenlygram.customer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only strict JSON (RFC 8259) for the customer host suites, independent of the production writer so the suites
 * never check CustomerJson with itself. {@link #parse} gives the plain values the production fromTree methods take
 * (LinkedHashMap in document order, ArrayList, String, Integer or Long for integers, Double, Boolean, null) and refuses
 * duplicate keys, trailing data, raw controls in strings, lone surrogate escapes and leading zeros. {@link #write}
 * prints those values compactly in the production writer's escaping, for exact-bytes comparisons.
 */
final class MiniJson {
    private final String text;
    private int index;

    private MiniJson(String text) { this.text = text; }

    static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        parser.space();
        Object value = parser.value();
        parser.space();
        if (parser.index != text.length()) throw parser.error("trailing data");
        return value;
    }

    static Object parseFile(Path file) throws IOException { return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("not a JSON object: " + value);
        return (Map<String, Object>) value;
    }

    static List<?> array(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("not a JSON array: " + value);
        return (List<?>) value;
    }

    /** Compact JSON of plain values (keys in map order; '/' and non-ASCII unescaped). */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                string(out, (String) entry.getKey());
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, item);
            }
            out.append(']');
        } else if (value instanceof String) {
            string(out, (String) value);
        } else {
            out.append(value);
        }
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c == '\b') {
                out.append("\\b");
            } else if (c == '\f') {
                out.append("\\f");
            } else if (c < 0x20 || c == ' ' || c == ' ') {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }

    private Object value() {
        if (index >= text.length()) throw error("unexpected end");
        char c = text.charAt(index);
        switch (c) {
            case '{': return objectValue();
            case '[': return arrayValue();
            case '"': return stringValue();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected character");
        }
    }

    private Map<String, Object> objectValue() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        index++;
        space();
        if (peek('}')) {
            index++;
            return map;
        }
        while (true) {
            space();
            if (!peek('"')) throw error("expected a key");
            String key = stringValue();
            space();
            expect(':');
            space();
            if (map.containsKey(key)) throw error("duplicate key " + key);
            map.put(key, value());
            space();
            if (peek(',')) {
                index++;
                continue;
            }
            expect('}');
            return map;
        }
    }

    private List<Object> arrayValue() {
        ArrayList<Object> list = new ArrayList<>();
        index++;
        space();
        if (peek(']')) {
            index++;
            return list;
        }
        while (true) {
            space();
            list.add(value());
            space();
            if (peek(',')) {
                index++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String stringValue() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (index >= text.length()) throw error("unterminated string");
            char c = text.charAt(index++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw error("raw control in a string");
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (index >= text.length()) throw error("unterminated escape");
            char e = text.charAt(index++);
            switch (e) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u': {
                    char unit = hex4();
                    if (Character.isHighSurrogate(unit)) {
                        if (!text.startsWith("\\u", index)) throw error("lone high surrogate");
                        index += 2;
                        char low = hex4();
                        if (!Character.isLowSurrogate(low)) throw error("lone high surrogate");
                        out.append(unit).append(low);
                    } else if (Character.isLowSurrogate(unit)) {
                        throw error("lone low surrogate");
                    } else {
                        out.append(unit);
                    }
                    break;
                }
                default:
                    throw error("bad escape");
            }
        }
    }

    private char hex4() {
        if (index + 4 > text.length()) throw error("short unicode escape");
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(index++), 16);
            if (digit < 0) throw error("bad unicode escape");
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private Object number() {
        int start = index;
        if (peek('-')) index++;
        if (peek('0')) {
            index++;
        } else {
            if (!digit()) throw error("bad number");
            while (digit()) { }
        }
        boolean fraction = false;
        if (peek('.')) {
            fraction = true;
            index++;
            if (!digit()) throw error("bad fraction");
            while (digit()) { }
        }
        if (peek('e') || peek('E')) {
            fraction = true;
            index++;
            if (peek('+') || peek('-')) index++;
            if (!digit()) throw error("bad exponent");
            while (digit()) { }
        }
        String literal = text.substring(start, index);
        if (fraction) return Double.valueOf(literal);
        long value = Long.parseLong(literal);
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (Object) Integer.valueOf((int) value) : (Object) Long.valueOf(value);
    }

    private boolean digit() {
        if (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
            index++;
            return true;
        }
        return false;
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, index)) throw error("bad literal");
        index += word.length();
        return value;
    }

    private void space() {
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            index++;
        }
    }

    private boolean peek(char c) { return index < text.length() && text.charAt(index) == c; }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
        index++;
    }

    private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at " + index); }
}
