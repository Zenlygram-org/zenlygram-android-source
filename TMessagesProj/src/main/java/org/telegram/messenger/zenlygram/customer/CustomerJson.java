package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic JSON for customer-data bodies and local records (pure, host-tested): objects keep their insertion
 * order, output is compact, and only '"', '\\', C0 controls and U+2028/U+2029 are escaped ('/' and other text stay
 * as they are), so the same value always gives the same bytes. Instants are contract §0.3 {@code instant}s
 * (UTC, "Z", second precision) by civil-date arithmetic, without java.time. There is no parser here: the Android
 * glue reads JSON with org.json and hands plain values (Map, List, String, Integer/Long, Boolean, null) to the pure
 * models, whose fromTree methods validate them with the accessors below.
 */
public final class CustomerJson {
    /** 9999-12-31T23:59:59Z, the last second an {@code instant} can spell. */
    public static final long MAX_INSTANT = 253402300799L;

    private CustomerJson() {}

    /** An ordered JSON object; {@link #put} replaces an existing key in place. */
    public static final class Obj {
        private final ArrayList<String> keys = new ArrayList<>();
        private final ArrayList<Object> values = new ArrayList<>();

        /** Adds or replaces {@code key}; a null value is written as JSON null (callers omit absent keys instead). */
        public Obj put(String key, Object value) {
            if (key == null) throw new IllegalArgumentException("null key");
            int index = keys.indexOf(key);
            if (index >= 0) {
                values.set(index, value);
            } else {
                keys.add(key);
                values.add(value);
            }
            return this;
        }

        @Override
        public String toString() { return write(this); }
    }

    /** Already serialized JSON, written verbatim (an event inside a request body). */
    public static final class Raw {
        public final String json;

        public Raw(String json) {
            if (json == null || json.isEmpty()) throw new IllegalArgumentException("empty raw JSON");
            this.json = json;
        }
    }

    /** Compact JSON of null, Boolean, Integer, Long, String, {@link Obj}, {@link Raw} or a List of those. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    /** A JSON array of the given strings, in order. */
    public static String stringArray(Iterable<String> values) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) out.append(',');
            first = false;
            quote(out, value);
        }
        return out.append(']').toString();
    }

    /** {@code value} as a JSON string literal. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        quote(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean) {
            out.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long) {
            out.append(value.toString());
        } else if (value instanceof String) {
            quote(out, (String) value);
        } else if (value instanceof Raw) {
            out.append(((Raw) value).json);
        } else if (value instanceof Obj) {
            Obj object = (Obj) value;
            out.append('{');
            for (int i = 0; i < object.keys.size(); i++) {
                if (i > 0) out.append(',');
                quote(out, object.keys.get(i));
                out.append(':');
                write(out, object.values.get(i));
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
        } else {
            throw new IllegalArgumentException("unsupported JSON value: " + value.getClass().getName());
        }
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) out.append(HEX[(c >> shift) & 0xF]);
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    // ---- Instants (contract §0.3: ^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$, UTC) ----

    /** The {@code instant} of epoch seconds, clamped to 1970-01-01T00:00:00Z … 9999-12-31T23:59:59Z. */
    public static String instant(long epochSeconds) {
        long seconds = Math.max(0, Math.min(MAX_INSTANT, epochSeconds));
        long days = seconds / 86400;
        long secondOfDay = seconds % 86400;
        // Civil date from days since 1970-01-01 (H. Hinnant's algorithm, proleptic Gregorian).
        long z = days + 719468;
        long era = z / 146097;
        long dayOfEra = z - era * 146097;
        long yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365;
        long dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
        long mp = (5 * dayOfYear + 2) / 153;
        long day = dayOfYear - (153 * mp + 2) / 5 + 1;
        long month = mp < 10 ? mp + 3 : mp - 9;
        long year = yearOfEra + era * 400 + (month <= 2 ? 1 : 0);
        StringBuilder out = new StringBuilder(20);
        pad(out, year, 4).append('-');
        pad(out, month, 2).append('-');
        pad(out, day, 2).append('T');
        pad(out, secondOfDay / 3600, 2).append(':');
        pad(out, secondOfDay / 60 % 60, 2).append(':');
        pad(out, secondOfDay % 60, 2).append('Z');
        return out.toString();
    }

    /** Epoch seconds of an {@code instant} from 1970 on, or -1 when {@code text} is not one (or not a real date). */
    public static long parseInstant(String text) {
        if (text == null || text.length() != 20 || text.charAt(4) != '-' || text.charAt(7) != '-' || text.charAt(10) != 'T'
                || text.charAt(13) != ':' || text.charAt(16) != ':' || text.charAt(19) != 'Z') {
            return -1;
        }
        for (int i = 0; i < 19; i++) {
            char c = text.charAt(i);
            if (i != 4 && i != 7 && i != 10 && i != 13 && i != 16 && (c < '0' || c > '9')) return -1;
        }
        long year = digits(text, 0, 4), month = digits(text, 5, 2), day = digits(text, 8, 2);
        long hour = digits(text, 11, 2), minute = digits(text, 14, 2), second = digits(text, 17, 2);
        if (year < 1970 || month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month) || hour > 23 || minute > 59
                || second > 59) {
            return -1;
        }
        // Days since 1970-01-01 from a civil date (H. Hinnant's algorithm).
        long y = month <= 2 ? year - 1 : year;
        long era = y / 400;
        long yearOfEra = y - era * 400;
        long dayOfYear = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1;
        long dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
        long days = era * 146097 + dayOfEra - 719468;
        return days * 86400 + hour * 3600 + minute * 60 + second;
    }

    public static boolean isInstant(String text) { return parseInstant(text) >= 0; }

    private static long digits(String text, int start, int count) {
        long value = 0;
        for (int i = start; i < start + count; i++) value = value * 10 + (text.charAt(i) - '0');
        return value;
    }

    private static long daysInMonth(long year, long month) {
        if (month == 2) return (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28;
        return (month == 4 || month == 6 || month == 9 || month == 11) ? 30 : 31;
    }

    private static StringBuilder pad(StringBuilder out, long value, int width) {
        String digits = Long.toString(value);
        for (int i = digits.length(); i < width; i++) out.append('0');
        return out.append(digits);
    }

    // ---- Shared shapes (contract §0.3) ----

    /** {@code uuid4}: lower-case 8-4-4-4-12 hex, version 4, RFC 4122 variant. */
    public static boolean isUuid4(String text) {
        if (text == null || text.length() != 36) return false;
        for (int i = 0; i < 36; i++) {
            char c = text.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
            } else if (!isLowerHex(c)) {
                return false;
            }
        }
        char variant = text.charAt(19);
        return text.charAt(14) == '4' && (variant == '8' || variant == '9' || variant == 'a' || variant == 'b');
    }

    /** {@code hex64}: 64 lower-case hex digits. */
    public static boolean isHex64(String text) {
        if (text == null || text.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            if (!isLowerHex(text.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isLowerHex(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); }

    // ---- Plain-tree accessors (Map / List / String / Integer / Long / Boolean / null) ----

    /** The value as a JSON object, or null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) return null;
        for (Object key : ((Map<?, ?>) value).keySet()) {
            if (!(key instanceof String)) return null;
        }
        return (Map<String, Object>) value;
    }

    /** The value as a JSON array, or null. */
    public static List<?> array(Object value) { return value instanceof List ? (List<?>) value : null; }

    /** The value as a string, or null. */
    public static String string(Object value) { return value instanceof String ? (String) value : null; }

    /** The value as an integral number (Integer or Long; never a fraction), or null. */
    public static Long integral(Object value) {
        if (value instanceof Integer) return ((Integer) value).longValue();
        if (value instanceof Long) return (Long) value;
        return null;
    }

    /** The value as a list of strings, or null when it is not an array or holds anything else. */
    public static List<String> strings(Object value) {
        List<?> items = array(value);
        if (items == null) return null;
        ArrayList<String> result = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof String)) return null;
            result.add((String) item);
        }
        return result;
    }
}
