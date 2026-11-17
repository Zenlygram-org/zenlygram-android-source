package org.telegram.messenger.zenlygram.tasks.cloud;

import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Scalar rules of the R3 $defs (patterns, ranges, JSON Schema code-point maxLength) and the stricter
 * client sealing rules of x-permissions-and-state.limits/visible_text (UTF-8 byte ceilings, grapheme
 * limits, a visible scalar, no boundary whitespace). Sealing rules apply only before a mutation is
 * sealed; decoded server data and restored sealed bodies are held to the schema rules, so a platform
 * ICU grapheme difference can never strand an already sealed or already committed value.
 */
final class TasksWireText {
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern POSITIVE = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern SEQUENCE = Pattern.compile("0|[1-9][0-9]{0,18}");
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern INSTANT = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z");
    /** Backend principal B.getKey(): exact bytes, never case-normalized (ULIDs are lowercase). */
    private static final Pattern PRINCIPAL = Pattern.compile("[0-9A-Za-z_-]{1,128}");
    static final long MIN_INSTANT = -62167219200L;   // 0000-01-01T00:00:00Z
    static final long MAX_INSTANT = 253402300799L;   // 9999-12-31T23:59:59Z

    private TasksWireText() { }

    static String uuid(String value, String field) {
        if (value == null || !UUID.matcher(value).matches()) throw new TasksWire.Invalid(field);
        return value;
    }

    static String nullableUuid(String value, String field) { return value == null ? null : uuid(value, field); }

    /** TelegramId and Revision: positive decimal that fits signed 64-bit. */
    static String positive(String value, String field) {
        if (value == null || !POSITIVE.matcher(value).matches() || !fitsLong(value)) throw new TasksWire.Invalid(field);
        return value;
    }

    static String sequence(String value, String field) {
        if (value == null || !SEQUENCE.matcher(value).matches() || !fitsLong(value)) throw new TasksWire.Invalid(field);
        return value;
    }

    static String principal(String value) {
        if (value == null || !PRINCIPAL.matcher(value).matches()) throw new TasksWire.Invalid("principal");
        return value;
    }

    private static boolean fitsLong(String digits) {
        try { Long.parseLong(digits); return true; }
        catch (NumberFormatException overflow) { return false; }
    }

    /** Signed decimal compare of two validated sequences (no leading zeros). */
    static int compareSequence(String a, String b) {
        return Long.compare(Long.parseLong(a), Long.parseLong(b));
    }

    /** Integer literal in [min, max]; fractions, exponents, -0 and leading zeros are rejected. */
    static long integer(String literal, long min, long max, String field) {
        if (literal == null || !INTEGER.matcher(literal).matches() || "-0".equals(literal) || literal.length() > 20) {
            throw new TasksWire.Invalid(field);
        }
        long value;
        try { value = Long.parseLong(literal); }
        catch (NumberFormatException overflow) { throw new TasksWire.Invalid(field); }
        if (value < min || value > max) throw new TasksWire.Invalid(field);
        return value;
    }

    static String cursor(String value, String field) {
        return codePoints(value, 1, 4096, field);
    }

    /** JSON Schema string length: Unicode scalars, value well-formed. */
    static String codePoints(String value, int min, int max, String field) {
        if (value == null) throw new TasksWire.Invalid(field);
        TasksWireJson.wellFormed(value, field);
        int count = value.codePointCount(0, value.length());
        if (count < min || count > max) throw new TasksWire.Invalid(field);
        return value;
    }

    // ---- Instants ---------------------------------------------------------------------------

    static String instant(String value, String field) {
        epochSeconds(value, field);
        return value;
    }

    static String nullableInstant(String value, String field) { return value == null ? null : instant(value, field); }

    static long epochSeconds(String value, String field) {
        if (value == null || !INSTANT.matcher(value).matches()) throw new TasksWire.Invalid(field);
        int year = Integer.parseInt(value.substring(0, 4));
        int month = Integer.parseInt(value.substring(5, 7));
        int day = Integer.parseInt(value.substring(8, 10));
        int hour = Integer.parseInt(value.substring(11, 13));
        int minute = Integer.parseInt(value.substring(14, 16));
        int second = Integer.parseInt(value.substring(17, 19));
        if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month) || hour > 23 || minute > 59 || second > 59) {
            throw new TasksWire.Invalid(field);
        }
        return daysFromCivil(year, month, day) * 86400L + hour * 3600L + minute * 60L + second;
    }

    static String formatInstant(long epochSeconds) {
        if (epochSeconds < MIN_INSTANT || epochSeconds > MAX_INSTANT) throw new TasksWire.Invalid("instant");
        long days = Math.floorDiv(epochSeconds, 86400L);
        long secondOfDay = Math.floorMod(epochSeconds, 86400L);
        // civil_from_days (Howard Hinnant), proleptic Gregorian.
        long z = days + 719468;
        long era = Math.floorDiv(z, 146097);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long day = doy - (153 * mp + 2) / 5 + 1;
        long month = mp < 10 ? mp + 3 : mp - 9;
        long year = yoe + era * 400 + (month <= 2 ? 1 : 0);
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d:%02dZ", year, month, day,
                secondOfDay / 3600, (secondOfDay / 60) % 60, secondOfDay % 60);
    }

    private static long daysFromCivil(long year, long month, long day) {
        year -= month <= 2 ? 1 : 0;
        long era = Math.floorDiv(year, 400);
        long yoe = year - era * 400;
        long doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    private static int daysInMonth(int year, int month) {
        switch (month) {
            case 2: return (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28;
            case 4: case 6: case 9: case 11: return 30;
            default: return 31;
        }
    }

    // ---- Client sealing rules -----------------------------------------------------------------

    /** Titles and names: byte ceiling, grapheme limit, a visible scalar, no boundary whitespace. */
    static void sealText(String value, int maxGraphemes, int maxBytes, String field) {
        sealLength(value, maxGraphemes, maxBytes, field);
        int first = value.codePointAt(0);
        int last = value.codePointBefore(value.length());
        if (boundaryWhitespace(first) || boundaryWhitespace(last)) throw new TasksWire.Invalid(field);
        boolean visible = false;
        for (int i = 0; i < value.length() && !visible; ) {
            int codePoint = value.codePointAt(i);
            visible = visibleScalar(codePoint);
            i += Character.charCount(codePoint);
        }
        if (!visible) throw new TasksWire.Invalid(field);
    }

    /** Description: byte ceiling and grapheme limit only (no trimming, no visibility rule). */
    static void sealLength(String value, int maxGraphemes, int maxBytes, String field) {
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes || graphemes(value) > maxGraphemes) {
            throw new TasksWire.Invalid(field);
        }
    }

    static int graphemes(String value) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(value);
        int count = 0;
        iterator.first();
        while (iterator.next() != BreakIterator.DONE) count++;
        return count;
    }

    /** Foundation CharacterSet.whitespacesAndNewlines: U+0009..000D, U+0085 and Zs/Zl/Zp. */
    static boolean boundaryWhitespace(int codePoint) {
        if ((codePoint >= 0x09 && codePoint <= 0x0D) || codePoint == 0x85) return true;
        int type = Character.getType(codePoint);
        return type == Character.SPACE_SEPARATOR || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    /** At least one scalar outside Cc, Cf, Mn, Mc, Me, Zs, Zl, Zp. */
    static boolean visibleScalar(int codePoint) {
        switch (Character.getType(codePoint)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.NON_SPACING_MARK:
            case Character.COMBINING_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.SPACE_SEPARATOR:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
                return false;
            default:
                return true;
        }
    }
}
