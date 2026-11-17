package org.telegram.messenger.zenlygram.pro;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Pure origin rules of the authenticated Pro backend (contract §6.10): the one canonical spelling of
 * its API base URL, the authority, and the storage token that scopes every origin-bound local store
 * (the Pro store directory, cloud Tasks, the import mapping, consent and event bookkeeping).
 * Mirrors iOS SGEntitlementBackendOrigin, except that an explicit :443 is elided here. A refused
 * value means "no authenticated backend", never a guess. No Android types (host-tested).
 */
public final class ProOrigin {
    /** Scope identifier without an origin (iOS SGEntitlementStoreScope.unconfigured); '!' never occurs in a host. */
    public static final String UNCONFIGURED = "!unconfigured";
    private static final String SCHEME = "https://", PATH = "/v1";
    private static final String REJECTED = "Pro API must be an HTTPS /v1 endpoint";
    private static final int MAX_HOST_LENGTH = 253, MAX_LABEL_LENGTH = 63, DEFAULT_PORT = 443, MAX_PORT = 65535;

    /**
     * "" for blank input, else "https://host[:port]/v1": scheme compared case-insensitively, host
     * lower-cased, :443 elided, trailing slashes dropped. Refuses (IllegalArgumentException) anything
     * else: non-ASCII or inner whitespace, another scheme, userinfo, query, fragment, a backslash,
     * another path, an empty or trailing-dot label, a non-LDH host, a non-canonical IPv4 quad, an
     * IPv6 literal, and a port outside 1..65535 or with a leading zero.
     */
    public static String canonicalApiUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e) throw rejected();
        }
        if (!value.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) throw rejected();
        String rest = value.substring(SCHEME.length());
        if (rest.indexOf('@') >= 0 || rest.indexOf('?') >= 0 || rest.indexOf('#') >= 0 || rest.indexOf('\\') >= 0) throw rejected();
        int slash = rest.indexOf('/');
        if (slash < 0) throw rejected();
        String authority = rest.substring(0, slash), path = rest.substring(slash);
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (!path.equals(PATH)) throw rejected();
        int colon = authority.indexOf(':');
        if (colon != authority.lastIndexOf(':')) throw rejected(); // At most one ':' (no IPv6 literal).
        String host = (colon < 0 ? authority : authority.substring(0, colon)).toLowerCase(Locale.ROOT);
        if (!isHostName(host)) throw rejected();
        String port = colon < 0 ? null : authority.substring(colon + 1);
        if (port != null && !isPort(port)) throw rejected();
        if (port != null && Integer.parseInt(port) == DEFAULT_PORT) port = null;
        return SCHEME + host + (port == null ? "" : ":" + port) + PATH;
    }

    /** "host" or "host:port" of a canonical URL; "" for "". IllegalArgumentException for a non-canonical one. */
    public static String authority(String canonicalApiUrl) {
        if (canonicalApiUrl == null || canonicalApiUrl.isEmpty()) return "";
        if (!canonicalApiUrl.equals(canonicalApiUrl(canonicalApiUrl))) throw rejected();
        return canonicalApiUrl.substring(SCHEME.length(), canonicalApiUrl.length() - PATH.length());
    }

    /** Lowercase hex SHA-256 of the UTF-8 authority, or of UNCONFIGURED for "" (iOS SGEntitlementStoreScope.storageToken). */
    public static String storageToken(String authority) {
        String identifier = authority == null || authority.isEmpty() ? UNCONFIGURED : authority;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identifier.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            final char[] alphabet = "0123456789abcdef".toCharArray();
            for (byte b : digest) hex.append(alphabet[(b & 255) >>> 4]).append(alphabet[b & 15]);
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    /**
     * cloud_import_map scope key: "o-" + storageToken + "/" + principal. A bare principal (a pre-origin
     * mapping) never equals one. IllegalArgumentException for a malformed token or an empty principal.
     */
    public static String importScope(String storageToken, String principal) {
        if (!isStorageToken(storageToken) || principal == null || principal.isEmpty()) {
            throw new IllegalArgumentException("Invalid import scope");
        }
        return "o-" + storageToken + "/" + principal;
    }

    /** Exactly 64 lowercase hex characters, the form storageToken returns. */
    static boolean isStorageToken(String token) {
        if (token == null || token.length() != 64) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /**
     * LDH labels, or, when the last label starts with a digit (which resolvers read as an IPv4 literal
     * in shorthand, octal and hex forms too), exactly the canonical dotted quad, so one server cannot
     * sit under several authorities. Private and loopback quads stay accepted (a build-time choice).
     */
    private static boolean isHostName(String host) {
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH) return false;
        String[] labels = host.split("\\.", -1);
        String last = labels[labels.length - 1];
        if (!last.isEmpty() && isDigit(last.charAt(0))) {
            if (labels.length != 4) return false;
            for (String label : labels) if (!isCanonicalOctet(label)) return false;
            return true;
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH || label.charAt(0) == '-'
                    || label.charAt(label.length() - 1) == '-') return false;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!((c >= 'a' && c <= 'z') || isDigit(c) || c == '-')) return false;
            }
        }
        return true;
    }

    /** 0..255 in decimal, no sign and no leading zero. */
    private static boolean isCanonicalOctet(String label) {
        if (label.isEmpty() || label.length() > 3 || (label.length() > 1 && label.charAt(0) == '0')) return false;
        for (int i = 0; i < label.length(); i++) if (!isDigit(label.charAt(i))) return false;
        return Integer.parseInt(label) <= 255;
    }

    /** 1..65535 in decimal, no sign and no leading zero (so never 0). */
    private static boolean isPort(String port) {
        if (port.isEmpty() || port.length() > 5 || port.charAt(0) == '0') return false;
        for (int i = 0; i < port.length(); i++) if (!isDigit(port.charAt(i))) return false;
        return Integer.parseInt(port) <= MAX_PORT;
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private static IllegalArgumentException rejected() { return new IllegalArgumentException(REJECTED); }

    private ProOrigin() { }
}
