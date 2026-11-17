package org.telegram.messenger.zenlygram.pro;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

/** bind-attest v1 client core (ProBindAttest): offer, bot check, initData, attest body/proof, outcome. Run with run-tests.sh. */
public final class ProBindAttestTests {
    private static int checks;
    private static final String CODE = "AB12CD34";
    private static final String URL = "https://10.0.2.2/app";

    public static void main(String[] args) throws Exception {
        offer();
        botCheck();
        initData();
        body();
        sharedVector();
        proof();
        outcome();
        System.out.println("Pro bind-attest: " + checks + " checks passed");
    }

    private static void offer() {
        ProBindAttest.Offer offer = ProBindAttest.Offer.of("8804541158", "ZenlyPremiumbot", "zb" + CODE, CODE);
        check(offer != null && offer.botId == 8804541158L && offer.botUsername.equals("ZenlyPremiumbot")
                && offer.startParam.equals("zbAB12CD34"), "well-formed offer is accepted as given");
        check(ProBindAttest.Offer.of(null, "ZenlyPremiumbot", "zb" + CODE, CODE) == null, "missing bot_id falls back");
        check(ProBindAttest.Offer.of("null", "ZenlyPremiumbot", "zb" + CODE, CODE) == null, "JSON null bot_id falls back");
        check(ProBindAttest.Offer.of("0", "ZenlyPremiumbot", "zb" + CODE, CODE) == null, "zero bot_id falls back");
        check(ProBindAttest.Offer.of("-5", "ZenlyPremiumbot", "zb" + CODE, CODE) == null, "negative bot_id falls back");
        check(ProBindAttest.Offer.of("88045411581234567", "ZenlyPremiumbot", "zb" + CODE, CODE) == null, "oversized bot_id falls back");
        check(ProBindAttest.Offer.of("8804541158", "@ZenlyPremiumbot", "zb" + CODE, CODE) == null, "username with @ falls back");
        check(ProBindAttest.Offer.of("8804541158", "Zen", "zb" + CODE, CODE) == null, "too short username falls back");
        check(ProBindAttest.Offer.of("8804541158", null, "zb" + CODE, CODE) == null, "missing username falls back");
        check(ProBindAttest.Offer.of("8804541158", "ZenlyPremiumbot", null, CODE) == null, "missing start_param falls back");
        check(ProBindAttest.Offer.of("8804541158", "ZenlyPremiumbot", "zbOTHER123", CODE) == null, "start_param of another bind falls back");
        check(ProBindAttest.Offer.of("8804541158", "ZenlyPremiumbot", CODE, CODE) == null, "start_param without zb prefix falls back");
        check(ProBindAttest.Offer.of("8804541158", "ZenlyPremiumbot", "zb" + CODE + " ", CODE + " ") == null, "start_param charset is enforced");
    }

    private static void botCheck() {
        ProBindAttest.Offer offer = ProBindAttest.Offer.of("8804541158", "ZenlyPremiumbot", "zb" + CODE, CODE);
        check(ProBindAttest.botMatches(offer, 8804541158L, true, true), "offered bot id, a bot with a Main Mini App, matches");
        check(!ProBindAttest.botMatches(offer, 8804541158L, true, false), "bot without a Main Mini App falls back (no start_param echo)");
        check(!ProBindAttest.botMatches(offer, 8804541159L, true, true), "username resolving to another id falls back");
        check(!ProBindAttest.botMatches(offer, 8804541158L, false, true), "non-bot account with the id falls back");
        check(!ProBindAttest.botMatches(null, 8804541158L, true, true), "no offer never matches");
    }

    private static void initData() {
        String inner = "query_id=AAH&user=%7B%22id%22%3A42%2C%22first_name%22%3A%22A%2BB%22%7D&auth_date=1730000000"
                + "&start_param=zbAB12CD34&signature=abc-_x&hash=ff00";
        String url = URL + "#tgWebAppData=" + encodeOnce(inner) + "&tgWebAppVersion=8.0&tgWebAppPlatform=android";
        check(inner.equals(ProBindAttest.initData(url)), "tgWebAppData is percent-decoded exactly once (inner form encoding kept)");
        check("a+b c".equals(ProBindAttest.percentDecode("a+b%20c")), "plus stays literal, %20 decodes");
        check("\u041f".equals(ProBindAttest.percentDecode("%D0%9F")), "multi-byte UTF-8 escape decodes");
        check("x".equals(ProBindAttest.initData(URL + "#tgWebAppVersion=8.0&tgWebAppData=x")), "parameter order does not matter");
        check(ProBindAttest.initData(URL) == null, "URL without fragment falls back");
        check(ProBindAttest.initData(URL + "?tgWebAppData=x") == null, "initData only in the query is ignored");
        check(ProBindAttest.initData(URL + "#tgWebAppVersion=8.0") == null, "fragment without tgWebAppData falls back");
        check(ProBindAttest.initData(URL + "#xtgWebAppData=x") == null, "only the exact key is accepted");
        check(ProBindAttest.initData(URL + "#tgWebAppData=x&tgWebAppData=y") == null, "duplicate tgWebAppData falls back");
        check(ProBindAttest.initData(URL + "#tgWebAppData=") == null, "empty tgWebAppData falls back");
        check(ProBindAttest.initData(URL + "#tgWebAppData=%G1") == null, "bad escape falls back");
        check(ProBindAttest.initData(URL + "#tgWebAppData=ab%4") == null, "truncated escape falls back");
        check(ProBindAttest.initData(URL + "#tgWebAppData=%FF") == null, "invalid UTF-8 falls back");
        check(ProBindAttest.initData(URL + "#tgWebAppData=a b") == null, "raw space in fragment falls back");
        check(ProBindAttest.initData(null) == null, "missing URL falls back");
    }

    private static void body() {
        byte[] body = ProBindAttest.body("ab01", "user=%7B%7D&hash=1");
        check(Arrays.equals("{\"poll_token\":\"ab01\",\"init_data\":\"user=%7B%7D&hash=1\"}".getBytes(StandardCharsets.UTF_8), body),
                "attest body is exactly poll_token then init_data");
        check(new String(ProBindAttest.body("t", "a\"b\\c\n\u0001\u041f"), StandardCharsets.UTF_8)
                        .equals("{\"poll_token\":\"t\",\"init_data\":\"a\\\"b\\\\c\\u000a\\u0001\u041f\"}"),
                "quotes, backslashes and control characters are JSON-escaped; UTF-8 kept");
        int overhead = "{\"poll_token\":\"t\",\"init_data\":\"\"}".length();
        check(ProBindAttest.body("t", repeat(ProBindAttest.MAX_BODY_BYTES - overhead)) != null, "body of exactly 8 KiB is sent");
        check(ProBindAttest.body("t", repeat(ProBindAttest.MAX_BODY_BYTES - overhead + 1)) == null, "body over 8 KiB falls back");
        check(ProBindAttest.body("t", "") == null && ProBindAttest.body(null, "x") == null, "empty initData or missing token falls back");
    }

    private static void proof() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        byte[] spki = pair.getPublic().getEncoded();
        byte[] body = ProBindAttest.body("ab01", "user=%7B%7D&hash=1");
        byte[] message = ProBindAttest.proofMessage("raw-device-0001", spki, "n0nce", "1730000000", body);
        String expected = "zenlypay-device-proof-v1\nPOST\n/v1/bind/attest\nraw-device-0001\n" + hex(spki) + "\nn0nce\n1730000000\n"
                + hex(body) + "\nbind-attest";
        check(Arrays.equals(expected.getBytes(StandardCharsets.UTF_8), message),
                "proof covers path, raw device id, SPKI SHA-256, nonce, ts, exact body SHA-256 and mode; no trailing LF");
        check(ProCrypto.sha256Hex(new byte[0]).equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                "sha256Hex is lowercase hex of SHA-256");
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(message);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(expected.getBytes(StandardCharsets.UTF_8));
        check(verifier.verify(signature), "server-side reconstruction verifies the install-key DER signature");
        byte[] other = ProBindAttest.proofMessage("raw-device-0001", spki, "n0nce", "1730000000", ProBindAttest.body("ab01", "x"));
        verifier.initVerify(pair.getPublic());
        verifier.update(other);
        check(!verifier.verify(signature), "a different body invalidates the proof");
    }

    /** zenlypay tests/Fixtures/device-proof-vectors.json (bind-attest), asserted server-side by AppleOwnershipProofTest. */
    private static void sharedVector() throws Exception {
        byte[] spki = java.util.Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEq4XO8PhXlPk7EEVNz+80iA4OOq8L6UvxiSIlnWoPE19/vAbZeYTWrTRSFhLfVp9pv7+i39dKuZDMy+Sx93W6vA==");
        byte[] body = ProBindAttest.body("poll-token-fixture-0123456789abcdef",
                "user=%7B%22id%22%3A555000111%7D&auth_date=1791806400&start_param=zbABCDEFGH&signature=fixture&hash=fixture");
        check(Arrays.equals(("{\"poll_token\":\"poll-token-fixture-0123456789abcdef\",\"init_data\":\"user=%7B%22id%22%3A555000111%7D"
                + "&auth_date=1791806400&start_param=zbABCDEFGH&signature=fixture&hash=fixture\"}").getBytes(StandardCharsets.UTF_8), body),
                "shared vector: exact body bytes");
        check(ProCrypto.sha256Hex(body).equals("03680f82e67d4df7b1cac0c10f7ee4c35a5c0de8bacd51e1bca64a23a7f71cd9"), "shared vector: body SHA-256");
        String device = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        byte[] message = ProBindAttest.proofMessage(device, spki, "fixture-nonce-0123456789abcdef", "1791806400", body);
        String expected = "zenlypay-device-proof-v1\nPOST\n/v1/bind/attest\n" + device
                + "\n506d396f2bcbb23f8003b39117b1dc66b9f64dcadbde5bd562e3e2aa2384dbf9\nfixture-nonce-0123456789abcdef\n1791806400"
                + "\n03680f82e67d4df7b1cac0c10f7ee4c35a5c0de8bacd51e1bca64a23a7f71cd9\nbind-attest";
        check(Arrays.equals(expected.getBytes(StandardCharsets.UTF_8), message), "shared vector: exact proof message bytes");
    }

    private static void outcome() {
        check(ProBindAttest.confirmed(200, "confirmed"), "200 confirmed polls at once");
        check(!ProBindAttest.confirmed(200, "pending"), "200 without confirmation falls back");
        check(!ProBindAttest.confirmed(200, ""), "200 without state falls back");
        for (int status : new int[]{401, 404, 409, 410, 422, 429, 503, 500}) {
            check(!ProBindAttest.confirmed(status, "confirmed"), "status " + status + " falls back to the code");
        }
    }

    private static String encodeOnce(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return out.toString();
    }

    private static String hex(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) out.append(String.format("%02x", b & 255));
        return out.toString();
    }

    private static String repeat(int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, 'a');
        return new String(chars);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
