package org.telegram.zenlygram.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.telegram.messenger.zenlygram.pro.ProCrypto;
import org.telegram.messenger.zenlygram.pro.ProMonetization;
import org.telegram.messenger.zenlygram.pro.ProPolicy;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cloud client's own policy verifier ({@link ProMonetization}, the copy that decides the mode and the local grace
 * length) against the tokens the production server really minted (the captures of {@link ZgPublicPolicyTest}) and the
 * keys this build pins. The captures are policies of the GENERAL line: what the server answers a request that names
 * no platform, and what a server without ver 4 answers this build. This build follows the Android line alone, so
 * each of them, although signed by a pinned key, must be refused as another purpose and change nothing.
 *
 * <p>JSON is decoded by org.json into the typed values the client's adapter produces (ZenlyPro.POLICY_JSON: every
 * integer a Long, any other number a Double), re-stated here because that adapter lives in an Android class.
 */
public class ZgEnginePolicyLiveTokenTest {

    private static final ProMonetization.Decoder DECODER = utf8 -> {
        final JSONTokener tokener = new JSONTokener(new String(utf8, StandardCharsets.UTF_8));
        final Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
            throw new IllegalArgumentException("Not one JSON object");
        }
        return typed((JSONObject) value);
    };

    private static Map<String, Object> typed(JSONObject object) throws Exception {
        final Map<String, Object> result = new LinkedHashMap<>();
        final Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            result.put(key, typedValue(object.get(key)));
        }
        return result;
    }

    private static Object typedValue(Object value) throws Exception {
        if (value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            return typed((JSONObject) value);
        }
        if (value instanceof JSONArray) {
            final JSONArray array = (JSONArray) value;
            final List<Object> items = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                items.add(typedValue(array.get(i)));
            }
            return items;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return value;
    }

    private static Map<String, PublicKey> pinnedKeys() throws Exception {
        final Map<String, PublicKey> keys = new HashMap<>();
        for (Map.Entry<String, String> entry : ZgCloudConfig.pinnedKeys().entrySet()) {
            keys.put(entry.getKey(), ProCrypto.publicKey(ZgBase64.decodeStandard(entry.getValue())));
        }
        return keys;
    }

    /** Production's real version 4 Android token (Deploy 10) is accepted by the engine's own verifier and pinned keys. */
    @Test
    public void theEngineAcceptsProductionsRealVer4Token() throws Exception {
        final Map<String, PublicKey> keys = pinnedKeys();
        final ProMonetization.Policy policy = ProMonetization.verifyFetched(ZgPublicPolicyTest.LIVE_MONETIZATION_V4, keys, DECODER, ZgPublicPolicyTest.LIVE_V4_IAT + 60);
        assertEquals(4, policy.version);
        assertEquals(ProPolicy.Mode.PAID, policy.mode);
        assertEquals(9, policy.revision);
        assertEquals(0, policy.trialDays);
        assertEquals(7, policy.localGraceDays);
        assertEquals(ZgPublicPolicyTest.LIVE_V4_IAT, policy.issuedAt);
        final ProMonetization.Policy stored = ProMonetization.verifyStored(ZgPublicPolicyTest.LIVE_MONETIZATION_V4, keys, DECODER);
        assertEquals(4, stored.version);
        assertEquals(7, stored.localGraceDays);
        assertSame(policy.getClass(), ProMonetization.accept(ProMonetization.INITIAL, policy).getClass());
    }

    @Test
    public void theServersRealGeneralLineTokensAreRefusedFetchedAndStored() throws Exception {
        final Map<String, PublicKey> keys = pinnedKeys();
        for (String token : new String[]{ZgPublicPolicyTest.LIVE_MONETIZATION_V1, ZgPublicPolicyTest.LIVE_MONETIZATION_V2}) {
            try {
                ProMonetization.verifyFetched(token, keys, DECODER, ZgPublicPolicyTest.LIVE_IAT + 60);
                fail("a policy of the general line was accepted when fetched");
            } catch (ProMonetization.Rejected rejected) {
                assertEquals(ProMonetization.Reason.WRONG_PURPOSE, rejected.reason);
            }
            try {
                ProMonetization.verifyStored(token, keys, DECODER);
                fail("a stored policy of the general line was loaded");
            } catch (ProMonetization.Rejected rejected) {
                assertEquals(ProMonetization.Reason.WRONG_PURPOSE, rejected.reason);
            }
        }
    }

    /**
     * The accepting path through the real JSON parser and the typed adapter (every integer a Long): a ver-4 policy
     * signed here with a throwaway key. If the adapter handed back another number type, every real policy would be
     * refused and every install would stay free without one test noticing.
     */
    @Test
    public void theEngineAcceptsASignedAndroidPolicyThroughTheRealJsonAdapter() throws Exception {
        final ZgPublicPolicyTest.Signer signer = new ZgPublicPolicyTest.Signer();
        final Map<String, PublicKey> keys = new HashMap<>();
        keys.put("ktest", signer.pair.getPublic());
        final String text = "{\"iss\":\"zenlygram\",\"aud\":\"zenlygram-monetization\",\"ver\":4,\"platform\":\"android\",\"mode\":\"paid\","
            + "\"revision\":12,\"trial_days\":3,\"local_grace_days\":21,\"iat\":1000,\"exp\":87400}";
        final String token = signer.signText(text);
        final ProMonetization.Policy policy = ProMonetization.verifyFetched(token, keys, DECODER, 1000L);
        assertEquals(4, policy.version);
        assertEquals(ProPolicy.Mode.PAID, policy.mode);
        assertEquals(12, policy.revision);
        assertEquals(3, policy.trialDays);
        assertEquals(21, policy.localGraceDays);
        assertEquals(1000L, policy.issuedAt);
        assertEquals(87400L, policy.expiresAt);
        assertSame(policy, ProMonetization.accept(ProMonetization.INITIAL, policy));
        // Stored: the same answer from the exact bytes.
        assertEquals(21, ProMonetization.verifyStored(token, keys, DECODER).localGraceDays);
        // The same text with a claim the parser reads as another number type, another platform, or the general line.
        assertRefused(signer.signText(text.replace("\"ver\":4", "\"ver\":4.0")), keys, ProMonetization.Reason.MALFORMED);
        assertRefused(signer.signText(text.replace("\"local_grace_days\":21", "\"local_grace_days\":21.0")), keys, ProMonetization.Reason.MALFORMED);
        assertRefused(signer.signText(text.replace("\"platform\":\"android\"", "\"platform\":\"ios\"")), keys, ProMonetization.Reason.WRONG_PURPOSE);
        assertRefused(signer.signText(text.replace("\"platform\":\"android\",", "").replace("\"ver\":4", "\"ver\":3")), keys,
            ProMonetization.Reason.WRONG_PURPOSE);
    }

    private static void assertRefused(String token, Map<String, PublicKey> keys, ProMonetization.Reason expected) {
        try {
            ProMonetization.verifyFetched(token, keys, DECODER, 1000L);
            fail("accepted a token that must be refused as " + expected);
        } catch (ProMonetization.Rejected rejected) {
            assertEquals(expected, rejected.reason);
        }
    }

    @Test
    public void withoutAnAndroidPolicyTheInstallIsFreeWithTheDefaultLength() {
        assertSame(ProPolicy.Mode.FREE, ProMonetization.INITIAL.mode);
        assertEquals(0, ProMonetization.INITIAL.version);
        assertEquals(ProPolicy.DEFAULT_LOCAL_GRACE_DAYS, ProMonetization.INITIAL.localGraceDays);
        assertEquals(7, ProMonetization.INITIAL.localGraceDays);
    }

    @Test
    public void anotherPolicysTokenIsNeverAMonetizationPolicy() throws Exception {
        try {
            ProMonetization.verifyStored(ZgPublicPolicyTest.LIVE_APP_POLICY, pinnedKeys(), DECODER);
            fail("the app update policy was read as a monetization policy");
        } catch (ProMonetization.Rejected rejected) {
            assertEquals(ProMonetization.Reason.WRONG_PURPOSE, rejected.reason);
        }
    }
}
