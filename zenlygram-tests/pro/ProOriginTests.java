package org.telegram.messenger.zenlygram.pro;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Plain-JVM checks of the canonical Pro origin (contract §6.10, design 50 §3.1): every equivalent
 * spelling is one canonical https /v1 origin (lower-case host, :443 elided), everything else is
 * refused, the storage tokens equal the shared iOS derivation, import scopes are origin-bound, no
 * production source but ZenlyPro.java names the test-only held configuration, and ZenlyPro's origin
 * accessors and trusted clock stay lock-free. Run with run-tests.sh, which passes the Android
 * repository root as the first argument.
 */
public final class ProOriginTests {
    private static int checks;
    private static final String ZENLY_PRO = "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro/ZenlyPro.java";
    private static final String MESSAGE = "Pro API must be an HTTPS /v1 endpoint";
    private static final String ORG = "6e0e6d39ba09d62eba37c6d82bbb78cd254b4ffc237e268c4124acea5dd835a4";
    private static final String LOCAL_STACK = "f9292b96ff7d66abe0820bbe9bd022cb0abd37bd673d7b462e1bba84245a9f66";
    private static final String LOOPBACK_8443 = "37d265174de89804c26f2d37573fc7a9a3852a21e02c524941ba99a472071026";
    private static final String UNCONFIGURED = "40726705e2abb242b6b2aba71d6b6a0c73206dfb2fdd3398ed6170c82f76a005";
    private static final String HELD_FACTORY = "authHeldForTesting";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ProOriginTests <android repository root>");
        canonical();
        rejected();
        authority();
        storageTokens();
        importScope();
        heldFactoryIsTestOnly(new File(args[0]));
        accessorsAreLockFree(new File(args[0]));
        System.out.println("Pro origin: " + checks + " checks passed");
    }

    private static void canonical() {
        for (String spelling : new String[] {"https://zenlygram.org/v1", " HTTPS://ZenlyGram.ORG:443/v1/ ", "https://ZENLYGRAM.org/v1//",
                "Https://zenlygram.org:443/v1", "\thttps://zenlygram.org/v1\n", "hTTpS://zenlygram.ORG/v1///"}) {
            check("https://zenlygram.org/v1".equals(ProOrigin.canonicalApiUrl(spelling)),
                    "one canonical lower-case origin, default port elided: [" + spelling + "]");
        }
        check("https://127.0.0.1:8443/v1".equals(ProOrigin.canonicalApiUrl("https://127.0.0.1:8443/v1")), "a non-default port is part of the origin");
        check("https://10.0.2.2/v1".equals(ProOrigin.canonicalApiUrl("https://10.0.2.2/v1")), "the local stack's emulator host is a canonical dotted quad");
        check("https://10.0.2.2/v1".equals(ProOrigin.canonicalApiUrl("https://10.0.2.2:443/v1")), ":443 is elided for a dotted quad too");
        check("https://zenlygram.org:8443/v1".equals(ProOrigin.canonicalApiUrl("https://ZenlyGram.org:8443/v1/")), "another port is kept as written");
        check("https://zenlygram.org:65535/v1".equals(ProOrigin.canonicalApiUrl("https://zenlygram.org:65535/v1"))
                && "https://zenlygram.org:1/v1".equals(ProOrigin.canonicalApiUrl("https://zenlygram.org:1/v1")), "ports 1 and 65535 are in range");
        check("https://0.0.0.0/v1".equals(ProOrigin.canonicalApiUrl("https://0.0.0.0/v1"))
                && "https://255.255.255.255/v1".equals(ProOrigin.canonicalApiUrl("https://255.255.255.255/v1")), "octets 0 and 255 are canonical");
        check("https://1example.org/v1".equals(ProOrigin.canonicalApiUrl("https://1example.org/v1"))
                && "https://a-b.c-d.org/v1".equals(ProOrigin.canonicalApiUrl("https://a-b.c-d.org/v1")),
                "LDH labels may start with a digit or hold inner hyphens when the last label is a name");
        check("https://localhost/v1".equals(ProOrigin.canonicalApiUrl("https://LOCALHOST/v1")), "a single-label name is a host");
        String label63 = repeat('a', 63);
        check(("https://" + label63 + ".org/v1").equals(ProOrigin.canonicalApiUrl("https://" + label63 + ".org/v1")), "a 63-character label is accepted");
        String host253 = repeat('a', 63) + "." + repeat('b', 63) + "." + repeat('c', 63) + "." + repeat('d', 61);
        check(host253.length() == 253 && ("https://" + host253 + "/v1").equals(ProOrigin.canonicalApiUrl("https://" + host253 + "/v1")),
                "a 253-character host is accepted");
        check("".equals(ProOrigin.canonicalApiUrl("")) && "".equals(ProOrigin.canonicalApiUrl("  ")) && "".equals(ProOrigin.canonicalApiUrl(null))
                && "".equals(ProOrigin.canonicalApiUrl("\t\n")), "blank input stays unconfigured");
        String canonical = ProOrigin.canonicalApiUrl(" HTTPS://ZenlyGram.ORG:443/v1/ ");
        check(canonical.equals(ProOrigin.canonicalApiUrl(canonical)), "canonicalization is idempotent");
    }

    private static void rejected() {
        String label64 = repeat('a', 64);
        String host254 = repeat('a', 63) + "." + repeat('b', 63) + "." + repeat('c', 63) + "." + repeat('d', 62);
        List<String> refused = new ArrayList<>(Arrays.asList(
                // Design 50 §3.1 vectors (and the staged instrumentation list).
                "http://zenlygram.org/v1", "https://user@zenlygram.org/v1", "https://zenlygram.org/v1?x=1", "https://zenlygram.org/v1#x",
                "https://zenlygram.org/v2", "https://zenlygram.org/v1/tasks", "https://zenlygram.org", "https:zenlygram.org/v1",
                "https://zenlygram.org./v1", "https://zenlygram.org:0/v1", "https://zénlygram.org/v1", "https://01.2.3.4/v1",
                "https://256.1.1.1/v1", "https://-a.org/v1",
                // Further rules of the same algorithm.
                "https://zenlygram.org:65536/v1", "https://zenlygram.org:08443/v1", "https://zenlygram.org:/v1", "https://zenlygram.org:+443/v1",
                "https://zenlygram.org:443:443/v1", "https://[::1]/v1", "https://::1/v1", "https://zenly_gram.org/v1", "https://zenlygram-.org/v1",
                "https://a..org/v1", "https://.org/v1", "https:///v1", "https://1.2.3/v1", "https://1.2.3.4.5/v1", "https://1.2.3.04/v1",
                "https://example.123/v1", "https://zenlygram%2eorg/v1", "https://zenlygram.org/V1", "https://zenlygram.org//v1",
                "https://zenlygram.org/v1/../v1", "https://zenlygram.org/", "https://zenlygram.org\\@evil.org/v1", "https://zenlygram.org/v1\\",
                "https://zenlygram.org/v1 x", "https://zenly gram.org/v1", "https://zenlygram.org/v1 ", "https://zenlygram.org․org/v1",
                "ftp://zenlygram.org/v1", "https//zenlygram.org/v1", "zenlygram.org/v1", "https://" + label64 + ".org/v1",
                "https://" + host254 + "/v1", "https://zenlygram.org/v1;x", "https://zenlygram.org:8443:/v1"));
        for (String endpoint : refused) {
            try {
                String result = ProOrigin.canonicalApiUrl(endpoint);
                check(false, "must be refused, never normalized into another origin: [" + endpoint + "] -> " + result);
            } catch (IllegalArgumentException expected) {
                check(MESSAGE.equals(expected.getMessage()), "the refusal keeps the Config message: [" + endpoint + "]");
            }
        }
    }

    private static void authority() {
        check("zenlygram.org".equals(ProOrigin.authority("https://zenlygram.org/v1")), "authority of the canonical .org origin");
        check("127.0.0.1:8443".equals(ProOrigin.authority("https://127.0.0.1:8443/v1")), "a kept port is part of the authority");
        check("10.0.2.2".equals(ProOrigin.authority(ProOrigin.canonicalApiUrl("https://10.0.2.2:443/v1"))), "the elided :443 is not in the authority");
        check("".equals(ProOrigin.authority("")) && "".equals(ProOrigin.authority(null)), "unconfigured has the empty authority");
        for (String spelling : new String[] {"https://ZenlyGram.org/v1", "https://zenlygram.org:443/v1", "https://zenlygram.org/v1/", " https://zenlygram.org/v1",
                "http://zenlygram.org/v1"}) {
            try {
                ProOrigin.authority(spelling);
                check(false, "authority needs the canonical form: [" + spelling + "]");
            } catch (IllegalArgumentException expected) { check(true, "non-canonical authority input refused"); }
        }
    }

    private static void storageTokens() {
        check(ORG.equals(ProOrigin.storageToken("zenlygram.org")), "zenlygram.org token");
        check(LOCAL_STACK.equals(ProOrigin.storageToken("10.0.2.2")), "10.0.2.2 (local stack) token");
        check(LOOPBACK_8443.equals(ProOrigin.storageToken("127.0.0.1:8443")), "127.0.0.1:8443 token");
        check(UNCONFIGURED.equals(ProOrigin.storageToken(ProOrigin.UNCONFIGURED)), "!unconfigured token");
        check(UNCONFIGURED.equals(ProOrigin.storageToken("")) && UNCONFIGURED.equals(ProOrigin.storageToken(null)),
                "no origin is the !unconfigured scope, which no host can collide with");
        check("!unconfigured".equals(ProOrigin.UNCONFIGURED), "the iOS unconfigured identifier");
        check(ORG.equals(ProOrigin.storageToken(ProOrigin.authority(ProOrigin.canonicalApiUrl(" HTTPS://ZenlyGram.ORG:443/v1/ ")))),
                "every equivalent spelling lands in the one .org store");
        check(!ProOrigin.storageToken("zenlygram.org:8443").equals(ORG), "another port is another store");
        for (String token : new String[] {ORG, LOCAL_STACK, LOOPBACK_8443, UNCONFIGURED}) {
            check(ProOrigin.isStorageToken(token), "a derived token is 64 lowercase hex: " + token);
        }
        check(!ProOrigin.isStorageToken(null) && !ProOrigin.isStorageToken("") && !ProOrigin.isStorageToken(ORG.substring(1))
                && !ProOrigin.isStorageToken(ORG + "0") && !ProOrigin.isStorageToken(ORG.toUpperCase(java.util.Locale.ROOT))
                && !ProOrigin.isStorageToken("../" + ORG.substring(3)) && !ProOrigin.isStorageToken(ORG.substring(1) + "g"),
                "anything else is not a storage token");
    }

    private static void importScope() {
        check(("o-" + ORG + "/acc-1").equals(ProOrigin.importScope(ORG, "acc-1")), "import scope = o-<token>/<principal>");
        check(!ProOrigin.importScope(ORG, "acc-1").equals(ProOrigin.importScope(LOCAL_STACK, "acc-1")), "the same principal at two origins is two scopes");
        check(!ProOrigin.importScope(ORG, "acc-1").equals("acc-1"), "a bare (pre-origin) principal never equals a scope");
        String[][] invalid = {{null, "acc-1"}, {"", "acc-1"}, {ORG.toUpperCase(java.util.Locale.ROOT), "acc-1"}, {ORG.substring(1), "acc-1"},
                {ORG, ""}, {ORG, null}};
        for (String[] pair : invalid) {
            try {
                ProOrigin.importScope(pair[0], pair[1]);
                check(false, "malformed import scope input refused: " + Arrays.toString(pair));
            } catch (IllegalArgumentException expected) { check(true, "malformed import scope input refused"); }
        }
    }

    /** The test-only held factory: production sources name it only where it is defined (ZenlyPro.java). */
    private static void heldFactoryIsTestOnly(File android) throws IOException {
        File definition = new File(android, ZENLY_PRO);
        check(definition.isFile(), "SETUP: ZenlyPro.java found under " + android);
        List<String> naming = new ArrayList<>();
        int scanned = 0;
        for (String root : new String[] {"TMessagesProj/src/main/java", "TMessagesProj_App/src/main/java"}) {
            File directory = new File(android, root);
            check(directory.isDirectory(), "SETUP: production source root exists: " + root);
            List<Path> files;
            try (Stream<Path> walk = Files.walk(directory.toPath())) {
                files = walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
            }
            for (Path file : files) {
                scanned++;
                // ISO-8859-1 maps every byte to one char, so the ASCII name is found in any encoding without decoding errors.
                if (new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(HELD_FACTORY)) {
                    naming.add(android.toPath().relativize(file).toString());
                }
            }
        }
        check(scanned > 1000, "SETUP: the whole production source tree was scanned (" + scanned + " files)");
        check(naming.equals(Collections.singletonList(android.toPath().relativize(definition.toPath()).toString())),
                "only ZenlyPro.java names the test-only held configuration; found " + naming);
    }

    /**
     * Structural guard (design 50 §2.2, §3.2): the origin accessors and the trusted clock read volatile
     * fields only, so other components may call them on any thread and inside their own locks (the
     * Tasks repository LOCK); none of them, nor what they call, takes ZenlyPro's LOCK.
     */
    private static void accessorsAreLockFree(File android) throws IOException {
        String source = new String(Files.readAllBytes(new File(android, ZENLY_PRO).toPath()), StandardCharsets.UTF_8);
        check(source.contains("private static volatile Config config;"), "the configuration field is volatile");
        check(source.contains("private static volatile long serverOffsetSeconds;"), "the server offset mirror is volatile");
        for (String signature : new String[] {"public static String storageToken()", "public static String configuredStorageToken()",
                "public static String authority()", "public static String appVersionName()", "public static long trustedNowSeconds()",
                "private static Config currentConfig()", "private static Config resourceConfig()"}) {
            String body = body(source, signature);
            check(!body.contains("LOCK") && !body.contains("synchronized") && !body.contains("Locked("), "lock-free: " + signature);
        }
    }

    /** The brace-balanced body following the one occurrence of signature. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        check(start >= 0 && source.indexOf(signature, start + 1) < 0, "SETUP: ZenlyPro.java declares exactly one " + signature);
        int open = source.indexOf('{', start), depth = 0;
        for (int i = open; i >= 0 && i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError("SETUP: no balanced body for " + signature);
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
