package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The consent text manifest (contract §5.2; the vendored {@code assets/zenlygram/consent/manifest.json}), pure and
 * host-tested. {@link #fromTree} validates a parsed manifest and refuses anything malformed with null, so the consent
 * wall can only offer a text whose version, scopes, file and template hash are well formed; the current version must
 * name all three texts (uz, ru, en).
 */
public final class ConsentManifest {
    public static final String SCHEMA = "zenlygram-consent-manifest/1";
    /** The text languages, the default first (design 50 §5.8, ui-strings §1 rule 8). */
    public static final List<String> LOCALES = Collections.unmodifiableList(Arrays.asList("uz", "ru", "en"));
    public static final String DEFAULT_LOCALE = "uz";
    /** Every scope a consent may name (contract §2.4.1); v1 names all four. */
    public static final List<String> SCOPES = Collections.unmodifiableList(Arrays.asList("link", "profile", "statistics", "tasks_sync"));
    /** {@code text_version} is int 1..32767 on the wire. */
    public static final int MAX_VERSION = 32767;

    /** One text of one version. */
    public static final class Text {
        public final String locale;
        /** Path relative to the consent directory, e.g. "v1/uz.md". */
        public final String file;
        public final String templateSha256;

        Text(String locale, String file, String templateSha256) {
            this.locale = locale;
            this.file = file;
            this.templateSha256 = templateSha256;
        }
    }

    /** One text version. */
    public static final class Version {
        public final int number;
        public final List<String> scopes;
        private final Map<String, Text> texts;

        Version(int number, List<String> scopes, Map<String, Text> texts) {
            this.number = number;
            this.scopes = scopes;
            this.texts = texts;
        }

        /** The text in {@code locale}, or null. */
        public Text text(String locale) { return locale == null ? null : texts.get(locale); }

        /** The locales of this version, in {@link #LOCALES} order. */
        public List<String> locales() {
            ArrayList<String> result = new ArrayList<>();
            for (String locale : LOCALES) {
                if (texts.containsKey(locale)) result.add(locale);
            }
            return Collections.unmodifiableList(result);
        }
    }

    public final int currentVersion;
    public final int minValidVersion;
    public final List<String> placeholders;
    private final Map<Integer, Version> versions;

    private ConsentManifest(int currentVersion, int minValidVersion, List<String> placeholders, Map<Integer, Version> versions) {
        this.currentVersion = currentVersion;
        this.minValidVersion = minValidVersion;
        this.placeholders = placeholders;
        this.versions = versions;
    }

    /** The bundled current version (never null for a parsed manifest). */
    public Version current() { return versions.get(currentVersion); }

    /** Version {@code number}, or null. */
    public Version version(int number) { return versions.get(number); }

    /** Every version, ascending. */
    public List<Version> versions() { return Collections.unmodifiableList(new ArrayList<>(versions.values())); }

    /**
     * The manifest of a parsed JSON tree (Map / List / String / Integer / Long / Boolean / null), or null when it is not
     * a valid manifest: schema, versions 1..32767 with {@code min_valid_version <= current_version}, distinct
     * {@code [A-Z_]+} placeholders, per version a non-empty set of known scopes and texts with a safe relative file and a
     * hex64 template hash, and all three locales for the current version. Unknown keys (e.g. published_at) are ignored.
     */
    public static ConsentManifest fromTree(Object tree) {
        Map<String, Object> root = CustomerJson.object(tree);
        if (root == null || !SCHEMA.equals(root.get("schema"))) return null;
        Long current = CustomerJson.integral(root.get("current_version"));
        Long minValid = CustomerJson.integral(root.get("min_valid_version"));
        if (current == null || minValid == null || current < 1 || current > MAX_VERSION || minValid < 1 || minValid > current) {
            return null;
        }
        List<String> placeholders = CustomerJson.strings(root.get("placeholders"));
        if (placeholders == null || placeholders.isEmpty() || new HashSet<>(placeholders).size() != placeholders.size()) return null;
        for (String placeholder : placeholders) {
            if (!isPlaceholderName(placeholder)) return null;
        }
        Map<String, Object> versionTrees = CustomerJson.object(root.get("versions"));
        if (versionTrees == null || versionTrees.isEmpty()) return null;
        TreeMap<Integer, Version> versions = new TreeMap<>();
        for (Map.Entry<String, Object> entry : versionTrees.entrySet()) {
            int number = versionNumber(entry.getKey());
            Version version = number < 0 ? null : version(number, entry.getValue());
            if (version == null) return null;
            versions.put(number, version);
        }
        Version currentText = versions.get(current.intValue());
        if (currentText == null || currentText.texts.size() != LOCALES.size()) return null;
        return new ConsentManifest(current.intValue(), minValid.intValue(), Collections.unmodifiableList(placeholders),
                Collections.unmodifiableMap(versions));
    }

    private static Version version(int number, Object tree) {
        Map<String, Object> version = CustomerJson.object(tree);
        if (version == null) return null;
        List<String> scopes = CustomerJson.strings(version.get("scopes"));
        if (!validScopes(scopes)) return null;
        Map<String, Object> localeTrees = CustomerJson.object(version.get("locales"));
        if (localeTrees == null || localeTrees.isEmpty()) return null;
        LinkedHashMap<String, Text> texts = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : localeTrees.entrySet()) {
            Map<String, Object> text = CustomerJson.object(entry.getValue());
            String file = text == null ? null : CustomerJson.string(text.get("file"));
            String hash = text == null ? null : CustomerJson.string(text.get("template_sha256"));
            if (!LOCALES.contains(entry.getKey()) || !isSafeRelativePath(file) || !CustomerJson.isHex64(hash)) return null;
            texts.put(entry.getKey(), new Text(entry.getKey(), file, hash));
        }
        return new Version(number, Collections.unmodifiableList(scopes), Collections.unmodifiableMap(texts));
    }

    /** A non-empty list of distinct known scopes (contract §2.4.1). */
    public static boolean validScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty() || new HashSet<>(scopes).size() != scopes.size()) return false;
        for (String scope : scopes) {
            if (!SCOPES.contains(scope)) return false;
        }
        return true;
    }

    /** "1".."32767" without a sign or leading zeros, else -1. */
    private static int versionNumber(String key) {
        if (key.isEmpty() || key.length() > 5 || key.charAt(0) == '0') return -1;
        int value = 0;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + (c - '0');
        }
        return value <= MAX_VERSION ? value : -1;
    }

    private static boolean isPlaceholderName(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || c == '_')) return false;
        }
        return true;
    }

    /** "v1/uz.md"-style: [A-Za-z0-9._-] segments separated by '/', no empty, "." or ".." segment, nothing absolute. */
    static boolean isSafeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 128) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The default text language (design 50 §5.8): the app language when it is uz, ru or en (its primary subtag, so
     * "en-us" is en), else the primary subtag of the language pack's base language when that is one of them, else uz.
     */
    public static String defaultLocale(String appLanguage, String baseLanguage) {
        String language = primarySubtag(appLanguage);
        if (LOCALES.contains(language)) return language;
        language = primarySubtag(baseLanguage);
        return LOCALES.contains(language) ? language : DEFAULT_LOCALE;
    }

    private static String primarySubtag(String tag) {
        if (tag == null) return "";
        String trimmed = tag.trim().replace('_', '-');
        int dash = trimmed.indexOf('-');
        return (dash < 0 ? trimmed : trimmed.substring(0, dash)).toLowerCase(Locale.ROOT);
    }
}
