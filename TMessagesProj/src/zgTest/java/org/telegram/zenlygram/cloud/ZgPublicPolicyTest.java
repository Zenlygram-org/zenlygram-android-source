package org.telegram.zenlygram.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.telegram.messenger.zenlygram.pro.ProMonetization;
import org.telegram.messenger.zenlygram.pro.ProPolicy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Collections;

/**
 * Signed public policies: live tokens captured from https://zenlygram.org/v1 on 2026-11-12 (kid k2026a, both platforms
 * null, monetization free), tampered copies of them, and synthetic tokens signed by a throwaway test key.
 */
public class ZgPublicPolicyTest {

    // GET /v1/app-policy (Accept: application/vnd.zenlygram.app-policy.v1+json), captured 2026-11-12T19:11:55Z.
    static final String LIVE_APP_POLICY = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2YSJ9.eyJpc3MiOiJ6ZW5seWdyYW0iLCJhdWQiOiJ6ZW5seWdyYW0tYXBwLXBvbGljeSIsInZlciI6MSwicmV2aXNpb24iOjAsImlvcyI6bnVsbCwiYW5kcm9pZCI6bnVsbCwiaWF0IjoxNzk0NTEwNzE1LCJleHAiOjE3OTQ1OTcxMTV9.lpYp2G5oLBv6wf3bwp0Jfpmpdyof1SJ8XotxoFziuDUHZIfV-DgdEhvzJUoKuYNS7Vk0sfrv4a64YmBaUk5M7g";
    // GET /v1/monetization (no vendor type -> v1, 7 claims).
    static final String LIVE_MONETIZATION_V1 = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2YSJ9.eyJpc3MiOiJ6ZW5seWdyYW0iLCJhdWQiOiJ6ZW5seWdyYW0tbW9uZXRpemF0aW9uIiwidmVyIjoxLCJtb2RlIjoiZnJlZSIsInJldmlzaW9uIjowLCJpYXQiOjE3OTQ1MTA3MTUsImV4cCI6MTc5NDU5NzExNX0.WOYCDLbRIEldQX7BvkOn3C50qpXDW-nzCr1bfo99nHerGK9_IH5cUox8cu5Py5AnEhrngL4vQOy40ncZP110Lw";
    // GET /v1/monetization (Accept: application/vnd.zenlygram.monetization.v2+json -> v2, 8 claims).
    static final String LIVE_MONETIZATION_V2 = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2YSJ9.eyJpc3MiOiJ6ZW5seWdyYW0iLCJhdWQiOiJ6ZW5seWdyYW0tbW9uZXRpemF0aW9uIiwidmVyIjoyLCJtb2RlIjoiZnJlZSIsInJldmlzaW9uIjowLCJ0cmlhbF9kYXlzIjowLCJpYXQiOjE3OTQ1MTA3MTUsImV4cCI6MTc5NDU5NzExNX0.D5Na0nPL1RfFJUneIQdvDsotp_FW9dBQN97OVSFy5aoHJtul5KQYrMJMurv-FKuaH2TSQpVYsP_mA7ibnzterA";
    static final long LIVE_IAT = 1794510715L;
    // GET /v1/monetization?platform=android (Accept: application/vnd.zenlygram.monetization.v4+json -> v4, 10 claims),
    // captured from production 2026-11-17T10:26:41Z (Deploy 10), before any "Android: free" flip (so mode paid, revision 9).
    static final String LIVE_MONETIZATION_V4 = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2YSJ9.eyJpc3MiOiJ6ZW5seWdyYW0iLCJhdWQiOiJ6ZW5seWdyYW0tbW9uZXRpemF0aW9uIiwidmVyIjo0LCJwbGF0Zm9ybSI6ImFuZHJvaWQiLCJtb2RlIjoicGFpZCIsInJldmlzaW9uIjo5LCJ0cmlhbF9kYXlzIjowLCJsb2NhbF9ncmFjZV9kYXlzIjo3LCJpYXQiOjE3OTQ5MTEyMDEsImV4cCI6MTc5NDk5NzYwMX0.ZihtcIJq6p61CsAN8xhT9expnooovec9T4BaxkB7-C1sVy1N040p-WEDxLb9QiPq_HNs4RvoreCjPY0x9xF_3Q";
    static final long LIVE_V4_IAT = 1794911201L;

    private static final ZgJws.KeyRing RING = ZgCloudConfig.pinnedRing();

    // ------------------------------------------------------------------------------------------------ live tokens

    @Test
    public void liveAppPolicyVerifiesWithPinnedKeyAndBothPlatformsNull() {
        final ZgPublicPolicy.Parsed<ZgPublicPolicy.AppPolicy> parsed = ZgPublicPolicy.parseAppPolicy(LIVE_APP_POLICY, RING, LIVE_IAT + 60);
        assertTrue(String.valueOf(parsed.failure), parsed.isOk());
        assertEquals("k2026a", parsed.policy.kid);
        assertEquals(0, parsed.policy.revision);
        assertNull(parsed.policy.ios);
        assertNull(parsed.policy.android);
        assertEquals(ZgAppUpdateDecision.Kind.NONE, ZgAppUpdateDecision.decide(parsed.policy, "13.2.1", LIVE_IAT).kind);
    }

    /**
     * What production answers a request that names no platform, and what a server without ver 4 answers this build:
     * real tokens of the general line, signed by the pinned key. They are policies of another purpose here.
     */
    @Test
    public void theGeneralLinesRealTokensAreNotThisBuildsPolicy() {
        for (String token : new String[]{LIVE_MONETIZATION_V1, LIVE_MONETIZATION_V2}) {
            final ZgPublicPolicy.Parsed<ZgPublicPolicy.Monetization> fetched = ZgPublicPolicy.parseMonetization(token, RING, LIVE_IAT);
            assertFalse(fetched.isOk());
            assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, fetched.failure);
            // A stored one (what a build of the general line left behind) is no policy either.
            assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, ZgPublicPolicy.parseMonetization(token, RING, null).failure);
        }
    }

    /** Production's real version 4 Android token (Deploy 10) verifies against the pinned keys and reads as paid. */
    @Test
    public void liveMonetizationV4AndroidVerifies() {
        final ZgPublicPolicy.Parsed<ZgPublicPolicy.Monetization> parsed = ZgPublicPolicy.parseMonetization(LIVE_MONETIZATION_V4, RING, LIVE_V4_IAT + 60);
        assertTrue(String.valueOf(parsed.failure), parsed.isOk());
        assertEquals(4, parsed.policy.ver);
        assertTrue(parsed.policy.paid);
        assertEquals(9, parsed.policy.revision);
        assertEquals(0, parsed.policy.trialDays);
        assertEquals(7, parsed.policy.localGraceDays);
        assertEquals(LIVE_V4_IAT, parsed.policy.iat);
        // Stored (no freshness check): the same answer.
        assertTrue(ZgPublicPolicy.parseMonetization(LIVE_MONETIZATION_V4, RING, null).isOk());
        // The general-line tokens from the same server are refused as another purpose by the Android build.
        assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, ZgPublicPolicy.parseMonetization(LIVE_MONETIZATION_V1, RING, LIVE_V4_IAT + 60).failure);
        assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, ZgPublicPolicy.parseMonetization(LIVE_MONETIZATION_V2, RING, LIVE_V4_IAT + 60).failure);
    }

    @Test
    public void storedTokenIsNeverReExpired() {
        assertTrue(ZgPublicPolicy.parseAppPolicy(LIVE_APP_POLICY, RING, null).isOk());
        assertEquals(ZgPublicPolicy.Failure.STALE, ZgPublicPolicy.parseAppPolicy(LIVE_APP_POLICY, RING, LIVE_IAT + 86400 + 301).failure);
        assertEquals(ZgPublicPolicy.Failure.STALE, ZgPublicPolicy.parseAppPolicy(LIVE_APP_POLICY, RING, LIVE_IAT - 301).failure);
    }

    @Test
    public void tamperedPayloadFailsSignature() {
        final String[] parts = LIVE_APP_POLICY.split("\\.");
        final String forged = ZgBase64.encodeUrl(("{\"iss\":\"zenlygram\",\"aud\":\"zenlygram-app-policy\",\"ver\":1,\"revision\":0,"
            + "\"ios\":null,\"android\":{\"min\":\"99.0\",\"latest\":\"99.0\"},\"iat\":1794510715,\"exp\":1794597115}").getBytes(StandardCharsets.UTF_8));
        assertEquals(ZgPublicPolicy.Failure.BAD_SIGNATURE,
            ZgPublicPolicy.parseAppPolicy(parts[0] + "." + forged + "." + parts[2], RING, LIVE_IAT).failure);
    }

    @Test
    public void tamperedSignatureFails() {
        final String[] parts = LIVE_MONETIZATION_V1.split("\\.");
        final byte[] sig = ZgBase64.decodeUrl(parts[2]);
        sig[10] ^= 0x01;
        assertEquals(ZgPublicPolicy.Failure.BAD_SIGNATURE,
            ZgPublicPolicy.parseMonetization(parts[0] + "." + parts[1] + "." + ZgBase64.encodeUrl(sig), RING, LIVE_IAT).failure);
        // A signature of the wrong length is a bad signature too.
        assertEquals(ZgPublicPolicy.Failure.BAD_SIGNATURE,
            ZgPublicPolicy.parseMonetization(parts[0] + "." + parts[1] + "." + ZgBase64.encodeUrl(new byte[63]), RING, LIVE_IAT).failure);
    }

    @Test
    public void wrongHeaderAlgorithmOrKidIsRefused() {
        final String[] parts = LIVE_APP_POLICY.split("\\.");
        final String none = ZgBase64.encodeUrl("{\"typ\":\"JWT\",\"alg\":\"none\",\"kid\":\"k2026a\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ZgPublicPolicy.Failure.MALFORMED, ZgPublicPolicy.parseAppPolicy(none + "." + parts[1] + "." + parts[2], RING, LIVE_IAT).failure);
        final String otherKid = ZgBase64.encodeUrl("{\"typ\":\"JWT\",\"alg\":\"ES256\",\"kid\":\"k2099z\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ZgPublicPolicy.Failure.UNKNOWN_KID, ZgPublicPolicy.parseAppPolicy(otherKid + "." + parts[1] + "." + parts[2], RING, LIVE_IAT).failure);
        assertEquals(ZgPublicPolicy.Failure.MALFORMED, ZgPublicPolicy.parseAppPolicy(parts[0] + "." + parts[1], RING, LIVE_IAT).failure);
        assertEquals(ZgPublicPolicy.Failure.MALFORMED, ZgPublicPolicy.parseAppPolicy(LIVE_APP_POLICY + ".x", RING, LIVE_IAT).failure);
    }

    @Test
    public void policiesRefuseEachOthersAudience() {
        assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, ZgPublicPolicy.parseAppPolicy(LIVE_MONETIZATION_V1, RING, LIVE_IAT).failure);
        assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, ZgPublicPolicy.parseMonetization(LIVE_APP_POLICY, RING, LIVE_IAT).failure);
    }

    // ------------------------------------------------------------------------------------------------ synthetic

    @Test
    public void androidConstraintDrivesTheDecision() throws Exception {
        final Signer s = new Signer();
        final ZgPublicPolicy.AppPolicy policy = s.appPolicy(3, "{\"min\":\"13.2\",\"latest\":\"13.4.0\"}");
        assertEquals(ZgAppUpdateDecision.Kind.BLOCK, ZgAppUpdateDecision.decide(policy, "13.1.9", 1000).kind);
        final ZgAppUpdateDecision remind = ZgAppUpdateDecision.decide(policy, "13.2.1", 1000);
        assertEquals(ZgAppUpdateDecision.Kind.REMIND, remind.kind);
        assertEquals("13.4.0", remind.latest);
        assertEquals(ZgAppUpdateDecision.Kind.NONE, ZgAppUpdateDecision.decide(policy, "13.4", 1000).kind);
        assertEquals(ZgAppUpdateDecision.Kind.NONE, ZgAppUpdateDecision.decide(policy, "13.1-debug", 1000).kind);
        // The safety valve: a block whose token expired more than 30 days ago no longer blocks.
        final long late = policy.exp + ZgAppUpdateDecision.BLOCK_SAFETY_VALVE_SECONDS + 1;
        assertEquals(ZgAppUpdateDecision.Kind.REMIND, ZgAppUpdateDecision.decide(policy, "13.1.9", late).kind);
    }

    @Test
    public void appPolicyClaimRules() throws Exception {
        final Signer s = new Signer();
        assertEquals(ZgPublicPolicy.Failure.INVALID_CLAIMS, s.parseApp(s.appPayload(1, "{\"min\":\"14.0\",\"latest\":\"13.0\"}")).failure);
        assertEquals(ZgPublicPolicy.Failure.INVALID_CLAIMS, s.parseApp(s.appPayload(1, "{\"min\":\"13\",\"latest\":\"13.0\"}")).failure);
        assertEquals(ZgPublicPolicy.Failure.INVALID_CLAIMS, s.parseApp(s.appPayload(1, "{\"min\":\"13.0\",\"latest\":\"13.0\",\"x\":1}")).failure);
        final JSONObject extra = s.appPayload(1, "null");
        extra.put("sub", "x");
        assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, s.parseApp(extra).failure);
        final JSONObject long_ = s.appPayload(1, "null");
        long_.put("exp", long_.getLong("iat") + 86401);
        assertEquals(ZgPublicPolicy.Failure.INVALID_CLAIMS, s.parseApp(long_).failure);
    }

    @Test
    public void appPolicyOrdering() throws Exception {
        final Signer s = new Signer();
        final ZgPublicPolicy.AppPolicy r1 = s.appPolicy(1, "null");
        final ZgPublicPolicy.AppPolicy r2 = s.appPolicy(2, "null");
        final ZgPublicPolicy.AppPolicy r1other = s.appPolicy(1, "{\"min\":\"1.0\",\"latest\":\"2.0\"}");
        assertEquals(ZgPublicPolicy.Order.ADOPT, ZgPublicPolicy.order(r2, r1));
        assertEquals(ZgPublicPolicy.Order.OLDER, ZgPublicPolicy.order(r1, r2));
        assertEquals(ZgPublicPolicy.Order.UNCHANGED, ZgPublicPolicy.order(s.appPolicy(1, "null"), r1));
        assertEquals(ZgPublicPolicy.Order.CONFLICT, ZgPublicPolicy.order(r1other, r1));
    }

    @Test
    public void monetizationClaimRulesAndPaid() throws Exception {
        final Signer s = new Signer();
        final JSONObject paid = s.monetizationPayloadV4(5, "paid", 7, 7);
        final ZgPublicPolicy.Parsed<ZgPublicPolicy.Monetization> parsed = s.parseMonetization(paid);
        assertTrue(String.valueOf(parsed.failure), parsed.isOk());
        assertTrue(parsed.policy.paid);
        assertTrue(parsed.policy.offersServerTrial());
        assertEquals(4, parsed.policy.ver);
        assertEquals(ZgPublicPolicy.Failure.INVALID_CLAIMS, s.parseMonetization(s.monetizationPayloadV4(5, "paid", 31, 7)).failure);
        assertEquals(ZgPublicPolicy.Failure.INVALID_CLAIMS, s.parseMonetization(s.monetizationPayloadV4(5, "maybe", 0, 7)).failure);
        final JSONObject wrongVer = s.monetizationPayloadV4(5, "free", 0, 7);
        wrongVer.put("ver", 3);
        assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, s.parseMonetization(wrongVer).failure);
    }

    // ------------------------------------------------------------------------------------------------ the Android line (ver 4)

    @Test
    public void theRequestAsksForTheAndroidLine() {
        assertEquals("application/vnd.zenlygram.monetization.v4+json", ZgCloudConfig.ACCEPT_MONETIZATION);
        // The cloud client's own fetch of the same policy sends the same header to the same address.
        assertEquals(ZgCloudConfig.ACCEPT_MONETIZATION, ProMonetization.ACCEPT);
        assertEquals("https://zenlygram.org/v1/monetization?platform=android", ZgCloudConfig.MONETIZATION_URL);
        assertEquals(ZgCloudConfig.BASE_URL + "/" + ProMonetization.POLICY_PATH, ZgCloudConfig.MONETIZATION_URL);
        assertEquals("android", ZgPublicPolicy.PLATFORM);
        assertEquals(4, ZgPublicPolicy.MONETIZATION_VERSION);
    }

    @Test
    public void monetizationCarriesTheLocalGraceLength() throws Exception {
        final Signer s = new Signer();
        for (int days : new int[]{0, 1, 7, 30}) {
            final ZgPublicPolicy.Parsed<ZgPublicPolicy.Monetization> parsed = s.parseMonetization(s.monetizationPayloadV4(5, "paid", 0, days));
            assertTrue(String.valueOf(parsed.failure), parsed.isOk());
            assertEquals(4, parsed.policy.ver);
            assertEquals(days, parsed.policy.localGraceDays);
            assertTrue(parsed.policy.paid);
            assertFalse(parsed.policy.offersServerTrial());
        }
        final ZgPublicPolicy.Monetization both = s.parseMonetization(s.monetizationPayloadV4(5, "paid", 30, 30)).policy;
        assertEquals(30, both.trialDays);
        assertEquals(30, both.localGraceDays);
        assertTrue(both.offersServerTrial());
        // Free mode keeps the configured value (it is not zeroed; it has no effect there).
        final ZgPublicPolicy.Monetization free = s.parseMonetization(s.monetizationPayloadV4(5, "free", 0, 12)).policy;
        assertFalse(free.paid);
        assertEquals(12, free.localGraceDays);
        // A stored token (no freshness check) gives the same length back.
        final String compact = s.sign(s.monetizationPayloadV4(5, "paid", 0, 21));
        assertEquals(21, ZgPublicPolicy.parseMonetization(compact, s.ring, null).policy.localGraceDays);
    }

    @Test
    public void onlyTheAndroidLineIsAPolicyOfThisBuild() throws Exception {
        final Signer s = new Signer();
        // The general line in each of its shapes, validly signed: another purpose, fetched or stored.
        for (int version = 1; version <= 3; version++) {
            final JSONObject general = s.generalPayload(version, 9, "free");
            assertEquals("ver " + version, ZgPublicPolicy.Failure.WRONG_PURPOSE, s.parseMonetization(general).failure);
            assertEquals("ver " + version, ZgPublicPolicy.Failure.WRONG_PURPOSE, ZgPublicPolicy.parseMonetization(s.sign(general), s.ring, null).failure);
            // The claim set is what tells the lines apart, not the number in ver.
            general.put("ver", 4);
            assertEquals("ver " + version + " as 4", ZgPublicPolicy.Failure.WRONG_PURPOSE, s.parseMonetization(general).failure);
        }
        // The line of another platform, a platform in another spelling, no string, none.
        for (Object platform : new Object[]{"ios", "Android", "ANDROID", "android ", "", 7, true, JSONObject.NULL}) {
            final JSONObject payload = s.monetizationPayloadV4(5, "paid", 0, 7);
            payload.put("platform", platform);
            assertEquals(String.valueOf(platform), ZgPublicPolicy.Failure.WRONG_PURPOSE, s.parseMonetization(payload).failure);
        }
        final JSONObject noPlatform = s.monetizationPayloadV4(5, "paid", 0, 7);
        noPlatform.remove("platform");
        assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, s.parseMonetization(noPlatform).failure);
        // An unknown claim next to the set.
        final JSONObject extra = s.monetizationPayloadV4(5, "paid", 0, 7);
        extra.put("platforms", "android");
        assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, s.parseMonetization(extra).failure);
    }

    @Test
    public void monetizationWithABadLocalGraceDaysIsRefusedAsAWhole() throws Exception {
        final Signer s = new Signer();
        for (Object bad : new Object[]{31, -1, 365, "7", 7.5, true, JSONObject.NULL}) {
            final JSONObject payload = s.monetizationPayloadV4(5, "paid", 0, 7);
            payload.put("local_grace_days", bad);
            assertEquals(String.valueOf(bad), ZgPublicPolicy.Failure.INVALID_CLAIMS, s.parseMonetization(payload).failure);
        }
        // 7.0 is a fraction on the wire, not an integer (signed as raw text: a JSON builder would print it as 7).
        final String fraction = "{\"iss\":\"zenlygram\",\"aud\":\"zenlygram-monetization\",\"ver\":4,\"platform\":\"android\",\"mode\":\"paid\","
            + "\"revision\":5,\"trial_days\":0,\"local_grace_days\":7.0,\"iat\":1000,\"exp\":87400}";
        assertEquals(ZgPublicPolicy.Failure.INVALID_CLAIMS, ZgPublicPolicy.parseMonetization(s.signText(fraction), s.ring, 1000L).failure);
        assertTrue(ZgPublicPolicy.parseMonetization(s.signText(fraction.replace("7.0", "7")), s.ring, 1000L).isOk());
        // Missing: the ten claims without one of them are no known shape.
        final JSONObject missing = s.monetizationPayloadV4(5, "paid", 0, 7);
        missing.remove("local_grace_days");
        assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, s.parseMonetization(missing).failure);
        final JSONObject noTrial = s.monetizationPayloadV4(5, "paid", 0, 7);
        noTrial.remove("trial_days");
        assertEquals(ZgPublicPolicy.Failure.WRONG_PURPOSE, s.parseMonetization(noTrial).failure);
        // trial_days keeps its rule.
        final JSONObject badTrial = s.monetizationPayloadV4(5, "paid", 0, 7);
        badTrial.put("trial_days", 31);
        assertEquals(ZgPublicPolicy.Failure.INVALID_CLAIMS, s.parseMonetization(badTrial).failure);
        // A bad stored token is no policy either.
        final JSONObject stored = s.monetizationPayloadV4(5, "paid", 0, 7);
        stored.put("local_grace_days", 31);
        assertFalse(ZgPublicPolicy.parseMonetization(s.sign(stored), s.ring, null).isOk());
    }

    @Test
    public void monetizationOrdering() throws Exception {
        final Signer s = new Signer();
        final ZgPublicPolicy.Monetization held = s.parseMonetization(s.monetizationPayloadV4(5, "paid", 7, 3)).policy;
        // The same issuance (equal revision and iat) again: unchanged; with another number or mode: a conflict.
        assertEquals(ZgPublicPolicy.Order.UNCHANGED, ZgPublicPolicy.order(s.parseMonetization(s.monetizationPayloadV4(5, "paid", 7, 3)).policy, held));
        assertEquals(ZgPublicPolicy.Order.CONFLICT, ZgPublicPolicy.order(s.parseMonetization(s.monetizationPayloadV4(5, "paid", 7, 4)).policy, held));
        assertEquals(ZgPublicPolicy.Order.CONFLICT, ZgPublicPolicy.order(s.parseMonetization(s.monetizationPayloadV4(5, "paid", 8, 3)).policy, held));
        assertEquals(ZgPublicPolicy.Order.CONFLICT, ZgPublicPolicy.order(s.parseMonetization(s.monetizationPayloadV4(5, "free", 7, 3)).policy, held));
        // (revision, iat) orders.
        assertEquals(ZgPublicPolicy.Order.ADOPT, ZgPublicPolicy.order(s.parseMonetization(s.monetizationPayloadV4(6, "paid", 7, 0)).policy, held));
        assertEquals(ZgPublicPolicy.Order.ADOPT, ZgPublicPolicy.order(s.parseMonetization(s.monetizationPayloadV4(6, "free", 0, 7)).policy, held));
        assertEquals(ZgPublicPolicy.Order.OLDER, ZgPublicPolicy.order(s.parseMonetization(s.monetizationPayloadV4(4, "paid", 7, 30)).policy, held));
        assertEquals(ZgPublicPolicy.Order.ADOPT, ZgPublicPolicy.order(held, null));
    }

    @Test
    public void versionComparison() {
        assertEquals(0, ZgAppVersion.parse("12.9").compareTo(ZgAppVersion.parse("12.9.0")));
        assertTrue(ZgAppVersion.parse("12.9.2").compareTo(ZgAppVersion.parse("12.10.0")) < 0);
        assertNull(ZgAppVersion.parse("12"));
        assertNull(ZgAppVersion.parse("1.2.3.4.5"));
        assertNull(ZgAppVersion.parse("13.2.1-beta"));
        assertNotNull(ZgAppVersion.parse("13.2.1.7"));
    }

    @Test
    public void derRoundTrip() {
        final byte[] raw = new byte[64];
        raw[0] = (byte) 0x80;
        raw[63] = 1;
        assertEquals(java.util.Arrays.toString(raw), java.util.Arrays.toString(ZgJws.derToRaw(ZgJws.rawToDer(raw))));
    }

    /** Signs synthetic tokens with a throwaway P-256 key under kid "ktest". */
    static final class Signer {
        final KeyPair pair;
        final ZgJws.KeyRing ring;

        Signer() throws Exception {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            pair = generator.generateKeyPair();
            ring = new ZgJws.KeyRing(Collections.singletonMap("ktest", ZgBase64.encodeStandard(pair.getPublic().getEncoded())));
        }

        String sign(JSONObject payload) throws Exception {
            return signText(payload.toString());
        }

        /** Signs exactly this payload text (for a number form a JSON builder would not print, like 7.0). */
        String signText(String payloadJson) throws Exception {
            final String header = ZgBase64.encodeUrl("{\"alg\":\"ES256\",\"typ\":\"JWT\",\"kid\":\"ktest\"}".getBytes(StandardCharsets.UTF_8));
            final String body = ZgBase64.encodeUrl(payloadJson.getBytes(StandardCharsets.UTF_8));
            final Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(pair.getPrivate());
            signature.update((header + "." + body).getBytes(StandardCharsets.US_ASCII));
            return header + "." + body + "." + ZgBase64.encodeUrl(ZgJws.derToRaw(signature.sign()));
        }

        JSONObject appPayload(long revision, String android) throws Exception {
            return new JSONObject("{\"iss\":\"zenlygram\",\"aud\":\"zenlygram-app-policy\",\"ver\":1,\"revision\":" + revision
                + ",\"ios\":null,\"android\":" + android + ",\"iat\":1000,\"exp\":87400}");
        }

        /** The ver-4 claim set of the Android line. */
        JSONObject monetizationPayloadV4(long revision, String mode, int trialDays, int localGraceDays) throws Exception {
            return new JSONObject("{\"iss\":\"zenlygram\",\"aud\":\"zenlygram-monetization\",\"ver\":4,\"platform\":\"android\",\"revision\":"
                + revision + ",\"mode\":\"" + mode + "\",\"trial_days\":" + trialDays + ",\"local_grace_days\":" + localGraceDays
                + ",\"iat\":1000,\"exp\":87400}");
        }

        /** A policy of the general line: the exact ver 1, ver 2 (trial_days 0) or ver 3 (local_grace_days 7 too) claim set. */
        JSONObject generalPayload(int version, long revision, String mode) throws Exception {
            final JSONObject o = new JSONObject("{\"iss\":\"zenlygram\",\"aud\":\"zenlygram-monetization\",\"ver\":" + version + ",\"revision\":"
                + revision + ",\"mode\":\"" + mode + "\",\"iat\":1000,\"exp\":87400}");
            if (version >= 2) {
                o.put("trial_days", 0);
            }
            if (version >= 3) {
                o.put("local_grace_days", 7);
            }
            return o;
        }

        ZgPublicPolicy.Parsed<ZgPublicPolicy.AppPolicy> parseApp(JSONObject payload) throws Exception {
            return ZgPublicPolicy.parseAppPolicy(sign(payload), ring, 1000L);
        }

        ZgPublicPolicy.AppPolicy appPolicy(long revision, String android) throws Exception {
            final ZgPublicPolicy.Parsed<ZgPublicPolicy.AppPolicy> parsed = parseApp(appPayload(revision, android));
            assertTrue(String.valueOf(parsed.failure), parsed.isOk());
            return parsed.policy;
        }

        ZgPublicPolicy.Parsed<ZgPublicPolicy.Monetization> parseMonetization(JSONObject payload) throws Exception {
            return ZgPublicPolicy.parseMonetization(sign(payload), ring, 1000L);
        }
    }
}
