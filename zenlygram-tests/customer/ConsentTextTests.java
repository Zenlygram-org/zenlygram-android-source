package org.telegram.messenger.zenlygram.customer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain-JVM checks of the consent canonical form and hash (contract §5.3, design 50 §5.1–§5.2): the contract vectors
 * V1–V3 and the per-locale vectors of the shared fixture (the v1-uz vector's operator values, so the hashes equal the
 * server's), every template_sha256 of the vendored copy, extra vectors computed with the backend's own
 * ConsentCanonicalizer (an operator name with spaces and U+02BB, White_Space trimming, the regex semantics, refused
 * values), strict UTF-8 decoding, the UnicodeText helpers, the manifest model and the default text language. Run with
 * run-tests.sh, which passes the Android repository root.
 */
public final class ConsentTextTests {
    private static int checks;
    private static final List<String> PLACEHOLDERS = Arrays.asList("OPERATOR_NAME", "OPERATOR_STIR", "RESPONSIBLE_PERSON", "CONTACT_EMAIL");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ConsentTextTests <android repository root>");
        Path root = Paths.get(args[0]);
        Path assets = root.resolve("TMessagesProj/src/main/assets/zenlygram/consent");
        Path fixtures = root.resolve("zenlygram-tests/customer/fixtures");
        ConsentManifest manifest = ConsentManifest.fromTree(MiniJson.parseFile(assets.resolve("manifest.json")));
        check(manifest != null, "the vendored manifest parses");
        check(manifest.placeholders.equals(PLACEHOLDERS), "the manifest's placeholders are the four operator values");
        vectors(fixtures, assets, manifest);
        vendoredTemplateHashes(assets, manifest);
        backendVectors(assets);
        placeholderRules();
        lineRules();
        decoding();
        shownTexts(assets, manifest);
        whiteSpace();
        unicodeHelpers();
        bcp47();
        manifestRules(assets);
        defaultLocale();
        System.out.println("Consent text: " + checks + " checks passed");
    }

    /** consent-vectors.json: V1–V3 (kind algorithm) and one vector per locale of the real v1 texts (kind locale_text). */
    private static void vectors(Path fixtures, Path assets, ConsentManifest manifest) throws Exception {
        Map<String, Object> file = MiniJson.object(MiniJson.parseFile(fixtures.resolve("consent-vectors.json")));
        check(ConsentText.ALGORITHM.equals(file.get("algorithm")), "the fixture names this algorithm");
        Set<String> algorithm = new HashSet<>(), locales = new HashSet<>();
        for (Object item : MiniJson.array(file.get("vectors"))) {
            Map<String, Object> vector = MiniJson.object(item);
            String id = (String) vector.get("id");
            Map<String, String> values = strings(vector.get("values"));
            String template;
            if ("algorithm".equals(vector.get("kind"))) {
                algorithm.add(id);
                template = (String) vector.get("template");
                String canonical = ConsentText.canonical(template, values, PLACEHOLDERS);
                check(canonical.equals(vector.get("canonical")), id + ": canonical text");
            } else {
                check("locale_text".equals(vector.get("kind")), id + ": a known vector kind");
                locales.add(id);
                String locale = (String) vector.get("locale");
                int version = ((Number) vector.get("text_version")).intValue();
                ConsentManifest.Text text = manifest.version(version).text(locale);
                check(text != null && text.file.equals(vector.get("file")), id + ": the manifest names the vector's file");
                check(text.templateSha256.equals(vector.get("template_sha256")), id + ": the manifest's template hash is the vector's");
                template = read(assets.resolve(text.file));
                ConsentText.Shown shown = ConsentText.shown(manifest.version(version), locale, template, values, manifest.placeholders);
                check(shown.sha256.equals(vector.get("text_sha256")) && shown.version == version && shown.locale.equals(locale)
                        && shown.scopes.equals(ConsentManifest.SCOPES), id + ": the shown text carries the vector's hash, version, locale and scopes");
            }
            String canonical = ConsentText.canonical(template, values, PLACEHOLDERS);
            check(ConsentText.sha256Hex(canonical).equals(vector.get("text_sha256")), id + ": text_sha256");
            check(ConsentText.templateSha256(template, PLACEHOLDERS).equals(vector.get("template_sha256")), id + ": template_sha256");
            check(UnicodeText.scalarCount(canonical) == ((Number) vector.get("canonical_length_scalars")).intValue(), id + ": scalar count");
            check(canonical.getBytes("UTF-8").length == ((Number) vector.get("canonical_utf8_bytes")).intValue(), id + ": UTF-8 length");
        }
        check(algorithm.equals(new HashSet<>(Arrays.asList("V1", "V2", "V3"))), "the contract vectors V1–V3 are all there");
        check(locales.equals(new HashSet<>(Arrays.asList("v1-uz", "v1-ru", "v1-en", "v2-uz", "v2-ru", "v2-en"))), "one vector per v1 and v2 locale");
        // The contract §5.3 table, independent of the fixture file.
        check("c7ecb28d27e873d1a917e3849202f728287e165c163bfb91964cba732a1df2a2".equals(ConsentText.sha256Hex(ConsentText.canonical(
                "Operator: {{OPERATOR_NAME}} ({{OPERATOR_STIR}})\r\nEmail: {{CONTACT_EMAIL}}  \r\n\r\n",
                map("OPERATOR_NAME", "Zenlygram MChJ", "OPERATOR_STIR", "123456789", "CONTACT_EMAIL", "privacy@zenlygram.org"), PLACEHOLDERS))),
                "contract V1");
        check("560fb9efb2279bd727779cba65fe67ae05b5a14aa25821f98903ae5e28b3f8a8".equals(
                ConsentText.templateSha256("\uFEFF# Rozilik\n\nCafe\u0301 o\u02BBzbek\t\n", PLACEHOLDERS)), "contract V2");
        check("89751cc5539c18b0e936bde8c955ae1af6e901065bdcc7643ed306409dfda67f".equals(
                ConsentText.templateSha256("Mas\u02BCul shaxs: {{RESPONSIBLE_PERSON}}\n", PLACEHOLDERS)), "contract V3");
    }

    /** Every template_sha256 of the vendored manifest equals the hash of its vendored file (design 50 §5.1 parity). */
    private static void vendoredTemplateHashes(Path assets, ConsentManifest manifest) throws Exception {
        int texts = 0;
        for (ConsentManifest.Version version : manifest.versions()) {
            for (String locale : version.locales()) {
                ConsentManifest.Text text = version.text(locale);
                byte[] bytes = Files.readAllBytes(assets.resolve(text.file));
                String template = ConsentText.decodeUtf8(bytes);
                check(template != null, text.file + " is strict UTF-8");
                check(ConsentText.templateSha256(template, manifest.placeholders).equals(text.templateSha256),
                        text.file + ": the vendored template hash matches the manifest");
                texts++;
            }
        }
        check(texts >= 3 && manifest.current().locales().equals(ConsentManifest.LOCALES), "the current version has the uz, ru and en texts");
    }

    /**
     * Hashes computed on 2026-11-01 with the backend's own ConsentCanonicalizer
     * (B/app/Modules/Profile/Services/Consent/ConsentCanonicalizer.php, PHP intl), so these inputs hash like the server.
     */
    private static void backendVectors(Path assets) throws Exception {
        expect("Operator: {{OPERATOR_NAME}} ({{OPERATOR_STIR}})\n",
                map("OPERATOR_NAME", "\u3000 O\u02BBzbekiston  Zenlygram MChJ \u00A0", "OPERATOR_STIR", "123456789"),
                "Operator: O\u02BBzbekiston  Zenlygram MChJ (123456789)",
                "434e0c8c59754c0026672631a1f89f5f4e204418c114f49f02a26da9f62e1a46", "an operator name with spaces and U+02BB");
        expect("[{{OPERATOR_NAME}}]", map("OPERATOR_NAME", " \u2003 "), "[]",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", "a White_Space-only value becomes empty");
        expect("Caf{{OPERATOR_NAME}}", map("OPERATOR_NAME", "e\u0301"), "Caf\u00E9",
                "73473dcc12b763085904a5279d048c4d5b3b008c46f1f32443b99de04aa83a14", "NFC composes across the placeholder");
        expect("{{{OPERATOR_NAME}}} {{OTHER}} {{operator_name}} {{OPERATOR_NAME}", map("OPERATOR_NAME", "X"),
                "{X} {{OTHER}} {{operator_name}} {{OPERATOR_NAME}",
                "46dd60007be33b8a374139bcb7438017a9156527bf9f8c55dd32c7f08c9597fc", "leftmost non-overlapping [A-Z_]+ placeholders only");
        expect("<{{OPERATOR_NAME}}>", map("OPERATOR_NAME", "\u0085\u200Ba\u180E\u0085"), "<\u200Ba\u180E>",
                "4743f43634e795f1096044892aca8a1f1a163f33c2d08197bea38ba356e2d686", "U+0085 is White_Space, U+200B and U+180E are not");
        expect("\uFEFF\uFEFF\n \t\na\r\r\nb \u00A0 \t\r\n\t c\t \n\n", map(), "\uFEFF\n\na\n\nb \u00A0\n\t c",
                "e283a15bf9227bf7d3c5b8dd064aaf035866bb682eeed885f51be80abe0aba5d", "one BOM, CR, trailing blanks, outer trim");
        expect("{{OPERATOR_NAME}}", map("OPERATOR_NAME", "\tab\t"), "ab",
                "fb8e20fc2e4c3f248c60c39bd652f3c1347298bb977b8b4d5903b85055620603", "tabs are trimmed before the value check");
        Map<String, String> values = map("OPERATOR_NAME", " O\u02BBzbekiston Zenlygram MChJ ", "OPERATOR_STIR", "123456789",
                "RESPONSIBLE_PERSON", "G\u02BBulom Qodirov", "CONTACT_EMAIL", "privacy@zenlygram.org");
        String[][] texts = {{"uz", "efe9244fdd5137917c6b72a28bbcec55eeda52bfdb8cc0cec9ad7ff835a432ba"},
                {"ru", "751f654409cb94f20c38a76a407ca95057c488a9251a6eab56aaa0760554b67e"},
                {"en", "da6446d54c87b3f545755017619fad320db15d0555e5c865beff2fe6294280ac"}};
        for (String[] text : texts) {
            String template = read(assets.resolve("v1/" + text[0] + ".md"));
            check(ConsentText.sha256Hex(ConsentText.canonical(template, values, PLACEHOLDERS)).equals(text[1]),
                    "v1/" + text[0] + " with a U+02BB operator name hashes like the backend");
        }
        for (String refused : new String[] {"a\tb", "a{{b", "a}}b", "a\nb", "a\rb"}) {
            try {
                ConsentText.canonical("{{OPERATOR_NAME}}", map("OPERATOR_NAME", refused), PLACEHOLDERS);
                check(false, "the backend refuses this value: " + refused.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"));
            } catch (IllegalArgumentException expected) {
                check(true, "refused value");
            }
        }
    }

    private static void placeholderRules() {
        check("A {{OPERATOR_NAME}} B".equals(ConsentText.canonical("A {{OPERATOR_NAME}} B", map(), PLACEHOLDERS)),
                "a missing value keeps the placeholder literally");
        check("A {{OPERATOR_NAME}} B".equals(ConsentText.canonical("A {{OPERATOR_NAME}} B", map("OPERATOR_NAME", ""), PLACEHOLDERS)),
                "an empty value keeps the placeholder literally");
        check("{{SOMETHING}}".equals(ConsentText.canonical("{{SOMETHING}}", map("SOMETHING", "x"), PLACEHOLDERS)),
                "a name that is not a manifest placeholder is never replaced, even with a value");
        check("x x".equals(ConsentText.canonical("{{OPERATOR_NAME}} {{OPERATOR_NAME}}", map("OPERATOR_NAME", "x"), PLACEHOLDERS)),
                "every occurrence is replaced");
        check("{{OPERATOR_NAME}}".equals(ConsentText.canonical("{{OPERATOR_NAME}}", map("OPERATOR_NAME", "x"), null)),
                "without placeholders nothing is replaced");
        check("a\u00A0b".equals(ConsentText.canonical("{{OPERATOR_NAME}}", map("OPERATOR_NAME", "\u2028a\u00A0b\u2029"), PLACEHOLDERS)),
                "inner White_Space stays, outer U+2028/U+2029 is trimmed");
    }

    private static void lineRules() {
        check("a\n\nb".equals(ConsentText.canonical("a\r\r\nb", null, PLACEHOLDERS)), "CR CRLF is two line breaks");
        check("a\nb".equals(ConsentText.canonical("a\rb", null, PLACEHOLDERS)), "a lone CR is a line break");
        check("a\n  b".equals(ConsentText.canonical("a\t \n  b \t\n\t", null, PLACEHOLDERS)), "trailing blanks go, leading blanks of inner lines stay");
        check("a".equals(ConsentText.canonical(" \t\n a", null, PLACEHOLDERS)), "leading blanks and blank lines of the text go");
        check("a\u00A0".equals(ConsentText.canonical("a\u00A0 ", null, PLACEHOLDERS)), "only U+0020/U+0009 are trailing blanks");
        check("".equals(ConsentText.canonical("\uFEFF \n\t\n", null, PLACEHOLDERS)), "an empty text");
        check("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".equals(ConsentText.sha256Hex("")), "SHA-256 of nothing");
        check("\u00E9".equals(ConsentText.canonical("e\u0301", null, PLACEHOLDERS)), "the whole text is NFC");
    }

    private static void decoding() {
        check("\uFEFFa".equals(ConsentText.decodeUtf8(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'})), "a BOM is kept by decoding");
        check(ConsentText.decodeUtf8(new byte[] {(byte) 0xC3, 0x28}) == null, "a malformed sequence is refused");
        check(ConsentText.decodeUtf8(new byte[] {(byte) 0xC0, (byte) 0xAF}) == null, "an overlong encoding is refused");
        check(ConsentText.decodeUtf8(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}) == null, "an encoded surrogate is refused");
        check("o\u02BB".equals(ConsentText.decodeUtf8(new byte[] {'o', (byte) 0xCA, (byte) 0xBB})), "U+02BB decodes");
    }

    private static void shownTexts(Path assets, ConsentManifest manifest) throws Exception {
        ConsentManifest.Version version = manifest.current();
        ConsentText.Shown shown = ConsentText.shown(version, "en", read(assets.resolve(version.text("en").file)), map(), manifest.placeholders);
        check(shown.sha256.equals(version.text("en").templateSha256), "a local build without values shows the template hash");
        check(shown.canonical.contains("{{OPERATOR_NAME}}"), "a local build keeps the placeholders literally");
        try {
            shown.scopes.add("x");
            check(false, "shown scopes are immutable");
        } catch (UnsupportedOperationException expected) {
            check(true, "shown scopes are immutable");
        }
        String hash = shown.sha256;
        refuseShown(0, ConsentManifest.SCOPES, "en", hash, "version 0");
        refuseShown(32768, ConsentManifest.SCOPES, "en", hash, "version 32768");
        refuseShown(1, Collections.<String>emptyList(), "en", hash, "no scopes");
        refuseShown(1, Arrays.asList("link", "link"), "en", hash, "repeated scopes");
        refuseShown(1, Arrays.asList("link", "ads"), "en", hash, "an unknown scope");
        refuseShown(1, ConsentManifest.SCOPES, "de", hash, "another locale");
        refuseShown(1, ConsentManifest.SCOPES, "en", hash.toUpperCase(), "an upper-case hash");
    }

    private static void refuseShown(int version, List<String> scopes, String locale, String hash, String label) {
        try {
            new ConsentText.Shown(version, scopes, locale, "text", hash);
            check(false, "a shown text refuses " + label);
        } catch (IllegalArgumentException expected) {
            check(true, label);
        }
    }

    /** UnicodeText.isWhiteSpace is exactly the PropList White_Space set the backend and iOS spell out. */
    private static void whiteSpace() {
        Set<Integer> expected = new HashSet<>();
        for (int c = 0x09; c <= 0x0D; c++) expected.add(c);
        for (int c = 0x2000; c <= 0x200A; c++) expected.add(c);
        expected.addAll(Arrays.asList(0x20, 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000));
        int found = 0;
        for (int c = 0; c <= 0x10FFFF; c++) {
            if (UnicodeText.isWhiteSpace(c) != expected.contains(c)) check(false, "White_Space membership of U+" + Integer.toHexString(c));
            if (UnicodeText.isWhiteSpace(c)) found++;
        }
        check(found == 25, "25 White_Space scalars");
        check("a\u00A0b".equals(UnicodeText.trimWhiteSpace("\u3000 \u2003a\u00A0b\u0085\u202F")), "trim takes every White_Space scalar");
        check("\u200Ba\uFEFF".equals(UnicodeText.trimWhiteSpace(" \u200Ba\uFEFF ")), "U+200B and U+FEFF are not White_Space");
        check("".equals(UnicodeText.trimWhiteSpace(" \t\n")) && "".equals(UnicodeText.trimWhiteSpace("")), "all-blank trims to empty");
        check("\uD83D\uDE00".equals(UnicodeText.trimWhiteSpace("\u2009\uD83D\uDE00\u2009")), "supplementary scalars survive trimming");
    }

    private static void unicodeHelpers() {
        check("abcdefg".equals(UnicodeText.stripControls("a\u0000b\u007Fc\u0085d\u2028e\u2029f\tg\n")), "controls and line separators go");
        check(UnicodeText.hasControlOrLineSeparator("a\u009Fb") && UnicodeText.hasControlOrLineSeparator("\u2029")
                && !UnicodeText.hasControlOrLineSeparator("a\u00A0b\u200B"), "control detection");
        check("e\u0301".equals(UnicodeText.truncateGraphemes("e\u0301e\u0301e\u0301", 3)), "a combining sequence is never split");
        check("".equals(UnicodeText.truncateGraphemes("e\u0301", 1)), "nothing when the first cluster is too long");
        check("\uD83D\uDE00".equals(UnicodeText.truncateGraphemes("\uD83D\uDE00\uD83D\uDE00", 1)), "a surrogate pair is never split");
        check("abc".equals(UnicodeText.truncateGraphemes("abc", 3)), "a short text stays");
        check("Zen\u00F3".equals(UnicodeText.nameText("  \u0000Zeno\u0301\u2028 ", 128)), "nameText: NFC, controls dropped, trimmed");
        check("AliValiyev".equals(UnicodeText.nameText("Ali\tValiyev", 128)), "nameText drops a tab");
        check(UnicodeText.nameText(" \u2003\u0007 ", 128) == null && UnicodeText.nameText(null, 128) == null, "nameText: nothing left is null");
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) longName.append('a');
        check(UnicodeText.nameText(longName.toString(), 128).length() == 128, "nameText cuts at 128 scalars");
        check("Google Pixel 7 Pro".equals(UnicodeText.printableAscii("  Google\u00A0 Pixel\t7  Pro\u00E9 ", 64)), "model: collapsed printable ASCII");
        check("only".equals(UnicodeText.printableAscii("\u00E9 only", 64)), "model: nothing before the first printable");
        check(UnicodeText.printableAscii(" \u00E9 ", 64) == null && UnicodeText.printableAscii(null, 64) == null, "model: empty is null");
        check("ab".equals(UnicodeText.printableAscii("ab cd", 3)), "model: cut and trimmed");
        check("12.1.0 (7112)".equals(UnicodeText.ver("12.1.0 (7112)")) && "1.0.0-beta+exp.5".equals(UnicodeText.ver("1.0.0-beta+exp.5")),
                "ver keeps its charset");
        check("1.0".equals(UnicodeText.ver("1.0\u00E9")) && "0".equals(UnicodeText.ver("")) && "0".equals(UnicodeText.ver(null))
                && "0".equals(UnicodeText.ver("\u00E9")), "ver drops other characters; nothing left is 0");
        check(UnicodeText.ver("123456789012345678901234567890123456789").length() == 32, "ver keeps 32 characters");
        check(UnicodeText.isVer("android") && !UnicodeText.isVer("") && !UnicodeText.isVer("1/2")
                && !UnicodeText.isVer("123456789012345678901234567890123"), "isVer");
    }

    private static void bcp47() {
        String[][] vectors = {{"uz-Latn-UZ", "uz-Latn-UZ"}, {"en_US", "en-US"}, {"EN-us", "en-US"}, {"zh-hans-cn", "zh-Hans-CN"},
                {"es-419", "es-419"}, {"sr-Latn-RS-u-nu-latn", "sr-Latn-RS"}, {"de-1996", "de"}, {"en-x-private", "en"},
                {"uz-Cyrl", "uz-Cyrl"}, {"ru-RU", "ru-RU"}, {"fil-PH", "fil-PH"}, {" en-GB ", "en-GB"}};
        for (String[] vector : vectors) check(vector[1].equals(UnicodeText.bcp47(vector[0])), "bcp47 " + vector[0]);
        for (String refused : new String[] {"und", "", "x-klingon", "i-klingon", "english", "e", "12", null}) {
            check(UnicodeText.bcp47(refused) == null, "bcp47 refuses " + refused);
        }
    }

    private static void manifestRules(Path assets) throws Exception {
        ConsentManifest manifest = ConsentManifest.fromTree(MiniJson.parseFile(assets.resolve("manifest.json")));
        check(manifest.currentVersion == 2 && manifest.minValidVersion == 1 && manifest.current().number == 2, "v2 is current, v1 still valid");
        check(manifest.current().scopes.equals(ConsentManifest.SCOPES), "v2 names all four scopes");
        check("v2/uz.md".equals(manifest.current().text("uz").file) && manifest.current().text("de") == null
                && manifest.current().text(null) == null && manifest.version(1) != null && manifest.version(3) == null, "texts by locale");
        String source = new String(Files.readAllBytes(assets.resolve("manifest.json")), "UTF-8");
        refuse(source, m -> m.put("schema", "zenlygram-consent-manifest/2"), "another schema");
        refuse(source, m -> m.put("current_version", 0), "current version 0");
        refuse(source, m -> m.put("min_valid_version", 3), "min_valid_version above current");
        refuse(source, m -> m.put("current_version", 3), "a current version without texts");
        refuse(source, m -> m.put("placeholders", Arrays.asList("OPERATOR_NAME", "OPERATOR_NAME")), "a repeated placeholder");
        refuse(source, m -> m.put("placeholders", Arrays.asList("operator_name")), "a lower-case placeholder");
        refuse(source, m -> locales(m).remove("ru"), "a current version without ru");
        refuse(source, m -> text(m, "uz").put("template_sha256", "ABC"), "a malformed hash");
        refuse(source, m -> text(m, "uz").put("file", "../uz.md"), "a parent path");
        refuse(source, m -> text(m, "uz").put("file", "/v1/uz.md"), "an absolute path");
        refuse(source, m -> text(m, "uz").put("file", "v1\\uz.md"), "a backslash path");
        refuse(source, m -> locales(m).put("de", text(m, "uz")), "an unknown locale");
        refuse(source, m -> currentVersionObject(m).put("scopes", Arrays.asList("link", "ads")), "an unknown scope");
        refuse(source, m -> {
            Map<String, Object> versions = MiniJson.object(m.get("versions"));
            versions.put("01", versions.remove("1"));
        }, "a zero-padded version key");
        check(ConsentManifest.fromTree("manifest") == null && ConsentManifest.fromTree(null) == null, "not an object");
        Map<String, Object> extra = MiniJson.object(MiniJson.parse(source));
        extra.put("comment", "ignored");
        check(ConsentManifest.fromTree(extra) != null, "unknown keys are ignored");
    }

    private interface Mutation { void apply(Map<String, Object> manifest); }

    private static void refuse(String source, Mutation mutation, String label) {
        Map<String, Object> manifest = MiniJson.object(MiniJson.parse(source));
        mutation.apply(manifest);
        check(ConsentManifest.fromTree(manifest) == null, "the manifest refuses " + label);
    }

    private static Map<String, Object> currentVersionObject(Map<String, Object> manifest) {
        Object cv = manifest.get("current_version");
        String key = String.valueOf(cv instanceof Number ? ((Number) cv).intValue() : cv);
        return MiniJson.object(MiniJson.object(manifest.get("versions")).get(key));
    }

    private static Map<String, Object> locales(Map<String, Object> manifest) { return MiniJson.object(currentVersionObject(manifest).get("locales")); }

    private static Map<String, Object> text(Map<String, Object> manifest, String locale) { return MiniJson.object(locales(manifest).get(locale)); }

    private static void defaultLocale() {
        String[][] vectors = {{"uz", null, "uz"}, {"ru", "", "ru"}, {"en", null, "en"}, {"EN", null, "en"}, {"en-us", null, "en"},
                {"ru_RU", null, "ru"}, {"de", null, "uz"}, {"pt-br", "pt", "uz"}, {"xx-custom", "ru", "ru"}, {null, null, "uz"},
                {"", "", "uz"}, {"uz-beta", null, "uz"}};
        for (String[] vector : vectors) {
            check(vector[2].equals(ConsentManifest.defaultLocale(vector[0], vector[1])), "default text language for " + vector[0] + "/" + vector[1]);
        }
    }

    private static void expect(String template, Map<String, String> values, String canonical, String sha, String label) {
        String actual = ConsentText.canonical(template, values, PLACEHOLDERS);
        check(actual.equals(canonical), label + ": canonical text");
        check(ConsentText.sha256Hex(actual).equals(sha), label + ": hash like the backend");
    }

    private static Map<String, String> strings(Object tree) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : MiniJson.object(tree).entrySet()) result.put(entry.getKey(), (String) entry.getValue());
        return result;
    }

    private static Map<String, String> map(String... pairs) {
        HashMap<String, String> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }

    private static String read(Path file) throws Exception {
        String text = ConsentText.decodeUtf8(Files.readAllBytes(file));
        check(text != null, file + " is UTF-8");
        return text;
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
