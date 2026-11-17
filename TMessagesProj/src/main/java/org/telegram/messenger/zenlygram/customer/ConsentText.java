package org.telegram.messenger.zenlygram.customer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The canonical form of a consent text and its hash (contract §5.3; the only algorithm, the same as the backend's
 * ConsentCanonicalizer and iOS SGConsentCanonicalText), pure and host-tested:
 * <ol>
 * <li>strip one leading U+FEFF;</li>
 * <li>replace every {@code {{NAME}}} (NAME = [A-Z_]+, leftmost and non-overlapping like the backend's regex) whose
 * NAME is a manifest placeholder by its value, NFC-normalized and trimmed of Unicode White_Space; a missing or empty
 * value leaves {@code {{NAME}}} literally (debug builds only: a release build refuses empty values; unlike iOS, nothing
 * limits such a debug build to a local host, R-A10) and any other {@code {{…}}} is never touched; a value that still
 * holds CR, LF, TAB, "{{" or "}}" is refused;</li>
 * <li>CRLF and a lone CR become LF;</li>
 * <li>NFC-normalize the whole text;</li>
 * <li>remove trailing U+0020 / U+0009 from every line;</li>
 * <li>remove leading and trailing U+0020, U+0009, U+000A from the whole text (no final newline);</li>
 * <li>{@code text_sha256} = lowercase hex SHA-256 of the UTF-8 bytes; {@code template_sha256} = the same with no
 * values.</li>
 * </ol>
 * The client hashes exactly the canonical text it renders; the server recomputes it from its own template and operator
 * values ({@code consent_text_mismatch} when they differ).
 */
public final class ConsentText {
    public static final String ALGORITHM = "zenlygram-consent-canonical/1";

    private ConsentText() {}

    /**
     * A text as the wall shows it: version, scopes and locale from the manifest, the canonical text (what is rendered
     * and hashed) and its hash. A grant names exactly these.
     */
    public static final class Shown {
        public final int version;
        public final List<String> scopes;
        public final String locale;
        public final String canonical;
        public final String sha256;

        public Shown(int version, List<String> scopes, String locale, String canonical, String sha256) {
            if (version < 1 || version > ConsentManifest.MAX_VERSION || !ConsentManifest.validScopes(scopes)
                    || !ConsentManifest.LOCALES.contains(locale) || canonical == null || !CustomerJson.isHex64(sha256)) {
                throw new IllegalArgumentException("not a consent text");
            }
            this.version = version;
            this.scopes = Collections.unmodifiableList(new ArrayList<>(scopes));
            this.locale = locale;
            this.canonical = canonical;
            this.sha256 = sha256;
        }
    }

    /**
     * The shown text of one manifest version and locale: {@code template} filled with {@code values} for the manifest's
     * {@code placeholders}.
     *
     * @throws IllegalArgumentException for a value that holds CR, LF, TAB, "{{" or "}}" once trimmed
     */
    public static Shown shown(ConsentManifest.Version version, String locale, String template, Map<String, String> values,
            List<String> placeholders) {
        String canonical = canonical(template, values, placeholders);
        return new Shown(version.number, version.scopes, locale, canonical, sha256Hex(canonical));
    }

    /**
     * The canonical form of {@code template} filled with {@code values} (placeholder name → raw value) for
     * {@code placeholders}.
     *
     * @throws IllegalArgumentException for a value that holds CR, LF, TAB, "{{" or "}}" once trimmed
     */
    public static String canonical(String template, Map<String, String> values, List<String> placeholders) {
        String text = template.startsWith("﻿") ? template.substring(1) : template;
        text = fill(text, values == null ? Collections.<String, String>emptyMap() : values,
                placeholders == null ? Collections.<String>emptySet() : new HashSet<>(placeholders));
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = UnicodeText.nfc(text);
        return trimOuter(removeTrailingBlanks(text));
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of {@code canonical}. */
    public static String sha256Hex(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) {
                out.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** {@code template_sha256}: the hash of the canonical form without values. */
    public static String templateSha256(String template, List<String> placeholders) {
        return sha256Hex(canonical(template, Collections.<String, String>emptyMap(), placeholders));
    }

    /**
     * Strictly decoded UTF-8 (every byte order mark kept; step 1 strips exactly one), or null when {@code bytes} is not
     * well-formed UTF-8. {@code new String(bytes, UTF_8)} would silently replace malformed input.
     */
    public static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            return null;
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Step 2 with the backend's regex semantics ({@code /\{\{([A-Z_]+)\}\}/}, leftmost, non-overlapping). */
    private static String fill(String text, Map<String, String> values, Set<String> placeholders) {
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            if (text.startsWith("{{", index)) {
                int end = index + 2;
                while (end < text.length() && isPlaceholderChar(text.charAt(end))) end++;
                if (end > index + 2 && text.startsWith("}}", end)) {
                    String name = text.substring(index + 2, end);
                    String raw = placeholders.contains(name) ? values.get(name) : null;
                    out.append(raw == null || raw.isEmpty() ? text.substring(index, end + 2) : filledValue(raw, name));
                    index = end + 2;
                    continue;
                }
            }
            out.append(text.charAt(index++));
        }
        return out.toString();
    }

    private static String filledValue(String raw, String name) {
        String value = UnicodeText.trimWhiteSpace(UnicodeText.nfc(raw));
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\t') >= 0 || value.contains("{{")
                || value.contains("}}")) {
            throw new IllegalArgumentException("The value of " + name + " holds CR, LF, TAB, {{ or }}.");
        }
        return value;
    }

    private static boolean isPlaceholderChar(char c) { return (c >= 'A' && c <= 'Z') || c == '_'; }

    /** Step 5: a run of U+0020 / U+0009 directly before a LF or the end goes. */
    private static String removeTrailingBlanks(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int pendingStart = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') {
                if (pendingStart < 0) pendingStart = i;
            } else {
                if (pendingStart >= 0 && c != '\n') out.append(text, pendingStart, i);
                pendingStart = -1;
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Step 6. */
    private static String trimOuter(String text) {
        int start = 0, end = text.length();
        while (start < end && isOuter(text.charAt(start))) start++;
        while (end > start && isOuter(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private static boolean isOuter(char c) { return c == ' ' || c == '\t' || c == '\n'; }
}
