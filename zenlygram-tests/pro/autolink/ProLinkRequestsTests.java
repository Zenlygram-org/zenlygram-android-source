package org.telegram.messenger.zenlygram.pro;

import java.nio.charset.StandardCharsets;

/**
 * bind/start request rules (ProLinkRequests): the exact body with and without the consent, the
 * consent-free resend (422 invalid_consent always, 409 consent_stale only for a manual link),
 * when the Pro screen may attest, and which grant travels once a start has waited on its lane
 * (consentAfterWait). Run with run-tests.sh.
 */
public final class ProLinkRequestsTests {
    private static int checks;
    private static final String DEVICE = "8d3f0c2a9e7b6d5c4b3a291807f6e5d4c3b2a1908f7e6d5c4b3a29180f7e6d5c";
    private static final String KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+synthetic/key==";
    /** A grant ConsentEvent (contract §5.7) with the shared fixture's fake id and the v1-uz vector's hash. */
    private static final String GRANT = "{\"consent_id\":\"3a1c6f0e-5b7d-4e2a-9c8b-1f2e3d4c5b6a\",\"tg_user_id\":555000111,"
            + "\"action\":\"grant\",\"scopes\":[\"link\",\"profile\",\"statistics\",\"tasks_sync\"],\"text_version\":1,"
            + "\"text_sha256\":\"229fd61b5ac4c2711e1b9635b6e5b24ab44ab5564baac8a2c1067a5b127f64d9\",\"locale\":\"uz\","
            + "\"consent_mode\":\"required\",\"age_confirmed\":true,\"recorded_at\":\"2026-11-01T09:00:00Z\","
            + "\"platform\":\"android\",\"app_version\":\"1.0.0\"}";

    public static void main(String[] args) {
        bodyWithoutConsent();
        bodyWithConsent();
        unusableConsent();
        escapingAndLimits();
        jsonChecker();
        retryRule();
        attestRule();
        consentAfterWaitRule();
        System.out.println("Pro link requests: " + checks + " checks passed");
    }

    private static String body(String os, String app, String name, String consent) {
        return new String(ProLinkRequests.bindStartBody(DEVICE, KEY, os, app, name, consent), StandardCharsets.UTF_8);
    }

    private static final String BASE = "{\"device_id\":\"" + DEVICE + "\",\"device_pubkey\":\"" + KEY + "\",\"platform\":\"android\","
            + "\"src\":\"android\",\"os_version\":\"16\",\"app_version\":\"12.1.0\",\"device_name\":\"Google Pixel 7\"";

    private static void bodyWithoutConsent() {
        String plain = body("16", "12.1.0", "Google Pixel 7", null);
        check(plain.equals(BASE + "}"), "without a consent: today's seven keys exactly, platform and src android");
        check(ProLinkRequests.isJsonObject(plain), "the body is one strict JSON object");
        check(!plain.contains("consent"), "no consent key at all when the provider gave none");
        check(body(null, null, null, null).equals("{\"device_id\":\"" + DEVICE + "\",\"device_pubkey\":\"" + KEY
                + "\",\"platform\":\"android\",\"src\":\"android\",\"os_version\":null,\"app_version\":null,\"device_name\":null}"),
                "missing optional values are JSON null (the server's fields are nullable)");
        boolean refused = false;
        try { ProLinkRequests.bindStartBody(null, KEY, "16", "1", "n", null); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "a body without the device id is a caller bug, never sent");
    }

    private static void bodyWithConsent() {
        String withConsent = body("16", "12.1.0", "Google Pixel 7", GRANT);
        check(withConsent.equals(BASE + ",\"consent\":" + GRANT + "}"), "the consent is the eighth key, embedded byte for byte");
        check(ProLinkRequests.isJsonObject(withConsent), "the body with the consent stays one strict JSON object");
        check(withConsent.indexOf(GRANT) > 0 && withConsent.indexOf(GRANT) == withConsent.lastIndexOf(GRANT),
                "the exact grant JSON appears once, unchanged (the hash fields are never re-serialized)");
        String spaced = " \n" + GRANT + "\t ";
        check(body("16", "12.1.0", "Google Pixel 7", spaced).equals(BASE + ",\"consent\":" + spaced + "}"),
                "whitespace around the object is kept verbatim (still valid JSON)");
        check(GRANT.equals(ProLinkRequests.usableConsent(GRANT)), "a grant ConsentEvent is usable as given");
    }

    private static void unusableConsent() {
        String[] broken = {
                "", "   ", "[]", "\"grant\"", "null", "{", "{\"a\":1", "{\"a\":1}}", "{\"a\":1} {}", "{'a':1}", "{a:1}",
                "{\"a\":01}", "{\"a\":1.}", "{\"a\":.5}", "{\"a\":NaN}", "{\"a\":+1}", "{\"a\":1,}", "{\"a\" 1}", "{,}",
                "{\"a\":\"x\ny\"}", "{\"a\":\"\\x\"}", "{\"a\":\"\\u12g4\"}", "{\"a\":tru}", "{\"a\":[1,]}",
                "{\"a\":[[[[[[[[[1]]]]]]]]]}",
        };
        for (String consent : broken) {
            check(ProLinkRequests.usableConsent(consent) == null, "unusable consent is left out: " + consent.replace("\n", "\\n"));
            String sent = body("16", "12.1.0", "Google Pixel 7", consent);
            check(sent.equals(BASE + "}"), "the link still goes out, without that consent: " + consent.replace("\n", "\\n"));
        }
        StringBuilder huge = new StringBuilder("{\"pad\":\"");
        while (huge.length() < ProLinkRequests.MAX_CONSENT_CHARS) huge.append('x');
        huge.append("\"}");
        check(ProLinkRequests.usableConsent(huge.toString()) == null, "a consent above the size bound is left out");
        check(ProLinkRequests.usableConsent("{\"a\":[[[[[[[1]]]]]]]}") != null, "nesting up to the depth bound is accepted");
        check(ProLinkRequests.usableConsent("{\"a\":-0.5e+10,\"b\":[true,false,null],\"c\":{},\"d\":\"\\u00e9\\\"\\\\\\/\"}") != null,
                "every JSON value form is accepted");
    }

    private static void escapingAndLimits() {
        String name = body("16", "12.1.0", "Q\"uote\\ Bs\u0001\n\u00e9\u2028", null);
        check(name.endsWith(",\"device_name\":\"Q\\\"uote\\\\ Bs\\u0001\\n\u00e9\\u2028\"}"),
                "quotes, backslashes and control characters are escaped; other text passes through");
        check(ProLinkRequests.isJsonObject(name), "an escaped device name keeps the body valid");
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) longName.append('n');
        String cut = body("16", "12.1.0", longName.toString(), null);
        check(cut.contains("\"device_name\":\"" + longName.substring(0, 128) + "\""), "device_name is cut to 128 characters");
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 127; i++) emoji.append('e');
        emoji.append("\uD83D\uDE00tail");
        String safe = ProLinkRequests.cut(emoji.toString(), 128);
        check(safe.length() == 127 && !Character.isHighSurrogate(safe.charAt(safe.length() - 1)),
                "the cut never leaves half of a surrogate pair");
        StringBuilder longVersion = new StringBuilder();
        for (int i = 0; i < 40; i++) longVersion.append('9');
        String versions = body(longVersion.toString(), longVersion.toString(), "n", null);
        check(versions.contains("\"os_version\":\"" + longVersion.substring(0, 32) + "\",\"app_version\":\""
                + longVersion.substring(0, 32) + "\""), "os_version and app_version are cut to the server's 32");
        check(ProLinkRequests.cut(null, 3) == null && ProLinkRequests.cut("abc", 3).equals("abc"), "short and null values are unchanged");
    }

    private static void jsonChecker() {
        check(ProLinkRequests.isJsonObject("{}") && ProLinkRequests.isJsonObject(" {\"a\" : { \"b\" : [ ] } } "),
                "objects with whitespace and nesting are recognized");
        check(!ProLinkRequests.isJsonObject(null) && !ProLinkRequests.isJsonObject("1") && !ProLinkRequests.isJsonObject("{} x"),
                "anything but a single object is refused");
    }

    private static void retryRule() {
        check(ProLinkRequests.retryWithoutConsent(422, "invalid_consent", true)
                && ProLinkRequests.retryWithoutConsent(422, "invalid_consent", false),
                "422 invalid_consent: both kinds resend once without the consent (it is uploaded after the link)");
        check(ProLinkRequests.retryWithoutConsent(409, "consent_stale", true), "409 consent_stale: a manual link resends without it");
        check(!ProLinkRequests.retryWithoutConsent(409, "consent_stale", false), "409 consent_stale ends an automatic attempt instead");
        check(!ProLinkRequests.retryWithoutConsent(422, "invalid_request", true) && !ProLinkRequests.retryWithoutConsent(422, null, true),
                "another 422 is not a consent problem");
        check(!ProLinkRequests.retryWithoutConsent(409, "bind_conflict", true) && !ProLinkRequests.retryWithoutConsent(409, null, true),
                "another 409 is not a stale consent");
        check(!ProLinkRequests.retryWithoutConsent(400, "invalid_consent", true) && !ProLinkRequests.retryWithoutConsent(200, "consent_stale", true)
                && !ProLinkRequests.retryWithoutConsent(422, "consent_stale", true) && !ProLinkRequests.retryWithoutConsent(409, "invalid_consent", true),
                "the status and the code must both match");
        check(!ProLinkRequests.retryWithoutConsent(429, "rate_limited", true) && !ProLinkRequests.retryWithoutConsent(503, "bind_paused", false),
                "throttling and pauses are never resent");
    }

    private static void attestRule() {
        check(ProLinkRequests.useAttest(true, true), "an offer and an effective grant: attest silently");
        check(!ProLinkRequests.useAttest(true, false), "no effective grant: link by code even when attest is offered");
        check(!ProLinkRequests.useAttest(false, true) && !ProLinkRequests.useAttest(false, false), "no offer: link by code");
    }

    /**
     * The grant a bind/start carries after its wait on the lane (a manual link waits on the user lane behind a logout's or
     * an Unlink's final send and bind/revoke): the grant read after the wait, only when the read before it gave one too,
     * as iOS's beginBind reads its permission again after its revoke wait.
     */
    private static void consentAfterWaitRule() {
        String regranted = GRANT.replace("3a1c6f0e-5b7d-4e2a-9c8b-1f2e3d4c5b6a", "7b2d4f6a-8c0e-4a1b-9d3c-5e7f9a1b3c5d")
                .replace("2026-11-01T09:00:00Z", "2026-11-01T09:00:07Z");
        check(!regranted.equals(GRANT) && ProLinkRequests.usableConsent(regranted) != null, "SETUP: a second, newer grant");
        check(GRANT.equals(ProLinkRequests.consentAfterWait(GRANT, GRANT)), "granted before and after the wait: that grant travels");
        check(ProLinkRequests.consentAfterWait(GRANT, null) == null,
                "withdrawn during the wait (a final send and bind/revoke ran first): the start carries no consent");
        check(ProLinkRequests.consentAfterWait(null, GRANT) == null,
                "granted only during the wait: not attached (uploaded after the link instead)");
        check(ProLinkRequests.consentAfterWait(null, null) == null, "no grant either time: no consent");
        check(regranted.equals(ProLinkRequests.consentAfterWait(GRANT, regranted)),
                "withdrawn and granted again during the wait: the grant read after the wait, never the withdrawn one");
        check(ProLinkRequests.consentAfterWait(GRANT, "{\"a\":") == null && ProLinkRequests.consentAfterWait("[]", GRANT) == null,
                "an unusable read on either side sends no consent");
        String sent = body("16", "12.1.0", "Google Pixel 7", ProLinkRequests.consentAfterWait(GRANT, null));
        check(sent.equals(BASE + "}"), "a withdrawal during the wait: the body has no consent key, the link still goes out");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
