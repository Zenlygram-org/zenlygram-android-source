package org.telegram.messenger.zenlygram.customer;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Text helpers shared by the consent hash and the customer payloads (pure, host-tested). Everything works on Unicode
 * scalars (code points), never on Java's notion of whitespace: the consent algorithm trims Unicode White_Space,
 * spelled out exactly as the backend's ConsentCanonicalizer and iOS do (PropList.txt; compliance C-3).
 */
public final class UnicodeText {
    private UnicodeText() {}

    /** Unicode White_Space: U+0009–000D, 0020, 0085, 00A0, 1680, 2000–200A, 2028, 2029, 202F, 205F, 3000. */
    public static boolean isWhiteSpace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000D) || codePoint == 0x0020 || codePoint == 0x0085 || codePoint == 0x00A0
                || codePoint == 0x1680 || (codePoint >= 0x2000 && codePoint <= 0x200A) || codePoint == 0x2028 || codePoint == 0x2029
                || codePoint == 0x202F || codePoint == 0x205F || codePoint == 0x3000;
    }

    /** {@code text} without leading and trailing Unicode White_Space (not {@link String#trim()}). */
    public static String trimWhiteSpace(String text) {
        int start = 0, end = text.length();
        while (start < end) {
            int codePoint = text.codePointAt(start);
            if (!isWhiteSpace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = text.codePointBefore(end);
            if (!isWhiteSpace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return text.substring(start, end);
    }

    /** NFC (canonical composition). */
    public static String nfc(String text) { return Normalizer.normalize(text, Normalizer.Form.NFC); }

    /** C0 (U+0000–001F), DEL and C1 (U+007F–009F) controls. */
    public static boolean isControl(int codePoint) { return codePoint <= 0x1F || (codePoint >= 0x7F && codePoint <= 0x9F); }

    /** A control or U+2028 / U+2029. */
    public static boolean isControlOrLineSeparator(int codePoint) {
        return isControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029;
    }

    /** Whether {@code text} holds a control or U+2028 / U+2029. */
    public static boolean hasControlOrLineSeparator(String text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isControlOrLineSeparator(codePoint)) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    /** {@code text} without controls and U+2028 / U+2029. */
    public static String stripControls(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!isControlOrLineSeparator(codePoint)) out.appendCodePoint(codePoint);
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }

    /** The number of Unicode scalars (an unpaired surrogate counts as one). */
    public static int scalarCount(String text) { return text.codePointCount(0, text.length()); }

    /** The longest prefix of whole grapheme clusters with at most {@code maxScalars} scalars. */
    public static String truncateGraphemes(String text, int maxScalars) {
        if (scalarCount(text) <= maxScalars) return text;
        BreakIterator boundaries = BreakIterator.getCharacterInstance(Locale.ROOT);
        boundaries.setText(text);
        int end = 0;
        for (int boundary = boundaries.next(); boundary != BreakIterator.DONE; boundary = boundaries.next()) {
            if (text.codePointCount(0, boundary) > maxScalars) break;
            end = boundary;
        }
        return text.substring(0, end);
    }

    /**
     * A display name as the customer payloads send it ({@code name_text}, design 50 §9.1): NFC, controls and
     * U+2028/U+2029 dropped, White_Space trimmed, cut at a grapheme boundary to at most {@code maxScalars} scalars;
     * null when nothing is left.
     */
    public static String nameText(String raw, int maxScalars) {
        if (raw == null) return null;
        String text = truncateGraphemes(trimWhiteSpace(stripControls(nfc(raw))), maxScalars);
        text = trimWhiteSpace(text);
        return text.isEmpty() ? null : text;
    }

    /**
     * Printable ASCII (U+0020–007E) of {@code raw}: White_Space runs become one space, other characters are dropped,
     * the result is trimmed and cut to {@code maxLength}; null when nothing is left (the {@code model} rule).
     */
    public static String printableAscii(String raw, int maxLength) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder(raw.length());
        boolean space = false;
        for (int i = 0; i < raw.length(); ) {
            int codePoint = raw.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isWhiteSpace(codePoint)) {
                space = out.length() > 0;
            } else if (codePoint > 0x20 && codePoint < 0x7F) {
                if (space) out.append(' ');
                space = false;
                out.appendCodePoint(codePoint);
            }
        }
        String text = out.length() > maxLength ? out.substring(0, maxLength) : out.toString();
        text = text.trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * The contract {@code ver} form ({@code ^[0-9A-Za-z._+() -]{1,32}$}) of a raw version: other characters dropped,
     * at most 32 kept, "0" when nothing is left (the same clipping as iOS).
     */
    public static String ver(String raw) {
        StringBuilder out = new StringBuilder();
        if (raw != null) {
            for (int i = 0; i < raw.length() && out.length() < 32; i++) {
                char c = raw.charAt(i);
                if (isVerChar(c)) out.append(c);
            }
        }
        return out.length() == 0 ? "0" : out.toString();
    }

    /** Whether {@code text} is a contract {@code ver}. */
    public static boolean isVer(String text) {
        if (text == null || text.isEmpty() || text.length() > 32) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!isVerChar(text.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isVerChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '.' || c == '_' || c == '+'
                || c == '(' || c == ')' || c == ' ' || c == '-';
    }

    /**
     * The contract {@code bcp47} form (language[-Script][-REGION]) of a language tag such as
     * {@code Locale.toLanguageTag()}: '_' read as '-', the language lower-cased, a script title-cased, a region
     * upper-cased (or three digits); variants, extensions and private use dropped. Null when there is no 2–3 letter
     * language (also for "und").
     */
    public static String bcp47(String raw) {
        if (raw == null) return null;
        String[] parts = raw.trim().replace('_', '-').split("-", -1);
        String language = parts[0];
        if (!isLetters(language, 2, 3)) return null;
        language = language.toLowerCase(Locale.ROOT);
        if ("und".equals(language)) return null;
        StringBuilder out = new StringBuilder(language);
        int index = 1;
        if (index < parts.length && isLetters(parts[index], 4, 4)) {
            String script = parts[index++];
            out.append('-').append(Character.toUpperCase(script.charAt(0))).append(script.substring(1).toLowerCase(Locale.ROOT));
        }
        if (index < parts.length) {
            String region = parts[index];
            if (isLetters(region, 2, 2)) {
                out.append('-').append(region.toUpperCase(Locale.ROOT));
            } else if (region.length() == 3 && isDigits(region)) {
                out.append('-').append(region);
            }
        }
        return out.toString();
    }

    private static boolean isLetters(String text, int min, int max) {
        if (text.length() < min || text.length() > max) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return false;
        }
        return true;
    }

    private static boolean isDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
