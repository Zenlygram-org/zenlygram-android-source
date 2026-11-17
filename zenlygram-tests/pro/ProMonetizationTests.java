package org.telegram.messenger.zenlygram.pro;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.telegram.messenger.zenlygram.pro.ProTestTokens.DECODER;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.b64;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.envelope;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.header;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.map;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.policy;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.policyClaims;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.general;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.generalClaims;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.policyClaimsV4;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.policyV4;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.segment;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.sign;
import static org.telegram.messenger.zenlygram.pro.ProTestTokens.signRaw;

/**
 * Plain-JVM tests of the free-first monetization policy of the Android line
 * (resources/contracts/monetization-policy.v4.json): the signed public policy envelope, the bounded body
 * read, ES256 verification against the pinned key ring, exact claim purpose and types, the one claim
 * shape this build accepts (ver 4, platform "android") and the request that asks for it, the refusal of
 * every policy of the general line and of another platform, fetch-time limits, stored reverification,
 * (revision, iat) ordering and the refresh schedule. Real ES256 signatures from keys generated per
 * run. JSON text is parsed by Android's org.json in production (ZenlyPro's adapter); here claims are
 * the typed values that adapter produces (see ProTestTokens).
 */
public final class ProMonetizationTests {
    private static int checks;
    private static final long NOW = 1_800_000_000L;
    private static final long TTL = ProMonetization.TTL_SECONDS, SKEW = ProMonetization.SKEW_SECONDS;
    private static KeyPair active, next, outsider;
    private static Map<String, PublicKey> ring;

    private interface Call { Object run() throws Exception; }

    public static void main(String[] args) throws Exception {
        active = ProTestTokens.keyPair();
        next = ProTestTokens.keyPair();
        outsider = ProTestTokens.keyPair();
        ring = new HashMap<>();
        ring.put("k2026a", active.getPublic());
        ring.put("k2026b", next.getPublic());

        initialStateIsFree();
        envelopeShape();
        boundedRead();
        signatureAndHeader();
        purposeAndClaimTypes();
        request();
        androidLineOnly();
        localGraceDaysStrict();
        trialDaysStrict();
        fetchTimeLimits();
        storedReverification();
        ordering();
        orderingOfLengths();
        freePaidFree();
        refreshSchedule();
        policyClock();
        System.out.println("Pro monetization: " + checks + " checks passed");
    }

    private static String fresh(String mode, long revision) throws Exception {
        return policy(active, "k2026a", mode, revision, NOW, NOW + TTL);
    }

    private static ProMonetization.Policy fetched(String token) throws Exception {
        return ProMonetization.verifyFetched(token, ring, DECODER, NOW);
    }

    private static String signed(Map<String, Object> claims) throws Exception {
        return sign(header("k2026a"), claims, active.getPrivate());
    }

    // ---- Initial state -------------------------------------------------------------------

    private static void initialStateIsFree() {
        check(ProMonetization.INITIAL.mode == ProPolicy.Mode.FREE, "a new install starts free");
        check(ProMonetization.INITIAL.token == null, "initial free state is not a fabricated signed policy");
        check(!ProMonetization.allowsNewPurchase(ProMonetization.INITIAL.mode), "free never initiates a purchase, trial or prepare");
        check(ProMonetization.allowsNewPurchase(ProPolicy.Mode.PAID), "only verified paid mode offers new purchases");
    }

    // ---- Envelope ------------------------------------------------------------------------

    private static void envelopeShape() throws Exception {
        String token = fresh("paid", 1);
        check(token.equals(ProMonetization.policyToken(envelope(token))), "policy_token extracted from the exact envelope");
        check(malformed(() -> ProMonetization.policyToken(map("policy_token", token))), "server_time is required");
        check(malformed(() -> ProMonetization.policyToken(map("policy_token", token, "server_time", "2026-10-20T00:00:00Z",
                "mode", "free"))), "unsigned mode next to the token is never accepted");
        check(malformed(() -> ProMonetization.policyToken(envelope(7L))), "non-string token rejected");
        check(malformed(() -> ProMonetization.policyToken(map("policy_token", token, "server_time", 7L))), "non-string server_time rejected");
        check(malformed(() -> ProMonetization.policyToken(envelope("abc.def.ghi"))), "token shorter than the contract minimum rejected");
        check(malformed(() -> ProMonetization.policyToken(envelope(repeat("a", 4000) + "." + repeat("b", 4000) + "." + repeat("c", 200)))),
                "token longer than 8192 rejected");
        check(malformed(() -> ProMonetization.policyToken(envelope(token.replace('.', '+')))), "token outside the compact base64url pattern rejected");
        check(malformed(() -> ProMonetization.policyToken(envelope(token + ".extra"))), "four-part token rejected");
        check(malformed(() -> ProMonetization.policyToken(null)), "missing body rejected");
    }

    /** The 16 KiB bound applies while reading, not after an unbounded read or from a Content-Length. */
    private static void boundedRead() throws Exception {
        check(ProMonetization.MAX_RESPONSE_BYTES == 16384, "response limit matches the contract");
        String exact = repeat("x", ProMonetization.MAX_RESPONSE_BYTES);
        check(exact.equals(ProMonetization.readBounded(new ByteArrayInputStream(exact.getBytes(StandardCharsets.UTF_8)),
                ProMonetization.MAX_RESPONSE_BYTES)), "a body of exactly the limit is read whole");
        check(malformed(() -> ProMonetization.readBounded(new ByteArrayInputStream((exact + "y").getBytes(StandardCharsets.UTF_8)),
                ProMonetization.MAX_RESPONSE_BYTES)), "one byte over the limit is rejected");
        final long[] served = {0};
        InputStream endless = new InputStream() {
            @Override public int read() { served[0]++; return '{'; }
            @Override public int read(byte[] buffer, int offset, int length) {
                Arrays.fill(buffer, offset, offset + length, (byte) '{');
                served[0] += length;
                return length;
            }
        };
        check(malformed(() -> ProMonetization.readBounded(endless, ProMonetization.MAX_RESPONSE_BYTES)), "an endless body is rejected");
        check(served[0] <= ProMonetization.MAX_RESPONSE_BYTES + 8192, "reading stops right after the limit, got " + served[0]);
        check(malformed(() -> ProMonetization.readBounded(new ByteArrayInputStream(new byte[]{(byte) 0xC3, (byte) 0x28}),
                ProMonetization.MAX_RESPONSE_BYTES)), "invalid UTF-8 is rejected, never replaced");

        // Total deadline, not a per-read timeout: a slow drip of small reads cannot hold the worker.
        final long[] clock = {0};
        final long second = 1_000_000_000L;
        InputStream drip = new InputStream() {
            @Override public int read() { clock[0] += 2 * second; return 'x'; }
            @Override public int read(byte[] buffer, int offset, int length) {
                clock[0] += 2 * second; // each byte arrives just inside any per-read timeout
                buffer[offset] = 'x';
                return 1;
            }
        };
        long deadline = 10 * second;
        check(reason(() -> ProMonetization.readBounded(drip, ProMonetization.MAX_RESPONSE_BYTES, deadline, () -> clock[0]))
                == ProMonetization.Reason.TIMED_OUT, "a slow drip past the total deadline is abandoned");
        check(clock[0] <= deadline + 2 * second, "reading stops at the first read after the deadline, at " + clock[0]);
        final long[] still = {0};
        check("{}".equals(ProMonetization.readBounded(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                ProMonetization.MAX_RESPONSE_BYTES, 10 * second, () -> still[0])), "a prompt body within the deadline is read whole");
        check(reason(() -> ProMonetization.readBounded(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                ProMonetization.MAX_RESPONSE_BYTES, -1, () -> 0L)) == ProMonetization.Reason.TIMED_OUT,
                "a deadline already passed before reading reads nothing");
    }

    // ---- Signature and header ------------------------------------------------------------

    private static void signatureAndHeader() throws Exception {
        ProMonetization.Policy paid = fetched(fresh("paid", 4));
        check(paid.mode == ProPolicy.Mode.PAID && paid.revision == 4 && paid.issuedAt == NOW && paid.expiresAt == NOW + TTL,
                "valid pinned ES256 policy verifies with its signed claims");
        check(fetched(policy(next, "k2026b", "free", 4, NOW, NOW + TTL)).mode == ProPolicy.Mode.FREE, "every pinned kid verifies");
        check(reason(() -> fetched(policy(outsider, "k2026c", "paid", 4, NOW, NOW + TTL))) == ProMonetization.Reason.UNKNOWN_KEY,
                "unpinned kid rejected (no key download)");
        check(reason(() -> fetched(policy(outsider, "k2026a", "paid", 4, NOW, NOW + TTL))) == ProMonetization.Reason.BAD_SIGNATURE,
                "foreign key under a pinned kid rejected");
        check(reason(() -> fetched(policy(next, "k2026a", "paid", 4, NOW, NOW + TTL))) == ProMonetization.Reason.BAD_SIGNATURE,
                "a pinned key under another kid is not interchangeable");
        String token = fresh("free", 4);
        String[] parts = token.split("\\.");
        String swapped = parts[0] + "." + segment(policyClaims("paid", 4, NOW, NOW + TTL)) + "." + parts[2];
        check(reason(() -> fetched(swapped)) == ProMonetization.Reason.BAD_SIGNATURE, "mode flipped after signing rejected");
        check(reason(() -> fetched(parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 2))) != null,
                "truncated signature rejected");
        Map<String, Object> claims = policyClaims("paid", 4, NOW, NOW + TTL);
        check(malformed(() -> fetched(sign(map("alg", "ES256", "kid", "k2026a"), claims, active.getPrivate()))), "missing typ rejected");
        check(malformed(() -> fetched(sign(map("typ", "JWT", "alg", "ES384", "kid", "k2026a"), claims, active.getPrivate()))),
                "any algorithm other than ES256 rejected");
        check(malformed(() -> fetched(sign(map("typ", "JWT", "alg", "none", "kid", "k2026a"), claims, active.getPrivate()))),
                "alg none rejected");
        check(malformed(() -> fetched(sign(map("typ", "JWT", "alg", "ES256", "kid", "k2026a", "crit", Collections.singletonList("b64")),
                claims, active.getPrivate()))), "unknown header parameters rejected");
        check(malformed(() -> fetched(sign(map("typ", "JWT", "alg", "ES256", "kid", 7L), claims, active.getPrivate()))),
                "non-string kid rejected");
        check(malformed(() -> fetched(signRaw(segment(header("k2026a")), b64("{\"iss\":".getBytes(StandardCharsets.UTF_8)),
                active.getPrivate()))), "validly signed but unparseable payload rejected");
        check(malformed(() -> fetched(parts[0] + "=." + parts[1] + "." + parts[2])), "padded or non-canonical base64url rejected");
    }

    // ---- Purpose and exact claim types -----------------------------------------------------

    private static void purposeAndClaimTypes() throws Exception {
        Map<String, Object> entitlement = map("iss", "zenlygram", "sub", "acct", "tgid", 12345L, "dev", "bind", "tier", "pro",
                "ent", Collections.singletonList("*"), "until", "2036-01-01T00:00:00Z", "grace_days", 14L, "iat", NOW,
                "exp", NOW + TTL, "jti", "j", "ver", 1L);
        check(reason(() -> fetched(signed(entitlement))) == ProMonetization.Reason.WRONG_PURPOSE,
                "an account entitlement token is never a monetization policy");
        check(purpose(with("aud", "zenlygram-entitlement")), "another audience rejected");
        check(purpose(with("iss", "other")), "another issuer rejected");
        check(purpose(with("account_ent", Collections.singletonList("vault"))), "account-shaped claim inside a policy rejected");
        check(purpose(with("sub", "acct")), "subject inside a policy rejected");
        check(malformed(with("ver", 5L)), "another ver under the ver-4 claim set rejected");
        check(malformed(with("ver", 4.0)), "ver 4.0 is not integer 4");
        check(malformed(with("ver", "4")), "string ver rejected");
        check(malformed(with("mode", "PAID")), "mode is case-exact");
        check(malformed(with("mode", "trial")), "unknown mode rejected");
        check(malformed(with("mode", Boolean.TRUE)), "non-string mode rejected");
        check(malformed(with("revision", -1L)), "negative revision rejected");
        check(malformed(with("revision", 9007199254740992L)), "revision above 2^53-1 rejected");
        check(reason(() -> fetched(signed(with("revision", 9007199254740991L)))) == null, "revision 2^53-1 accepted");
        check(malformed(with("revision", 3.0)), "fractional revision rejected");
        check(malformed(with("revision", "3")), "string revision rejected");
        check(malformed(with("revision", null)), "null revision rejected");
        check(malformed(without("revision")), "missing revision rejected");
        check(malformed(without("exp")), "missing exp rejected");
        check(malformed(policyClaims("paid", 1, 0, TTL)), "iat must be at least 1");
        check(malformed(policyClaims("paid", 1, NOW, NOW)), "exp must be after iat");
        check(malformed(policyClaims("paid", 1, NOW, NOW + TTL + 1)), "lifetime above 24 h rejected");
        check(malformed(with("iat", Long.toString(NOW))), "string iat rejected");
    }

    // ---- The Android line: ver 4, platform "android", and nothing else ----------------------------

    /** The exact request of the specification: the platform in the query, the ver-4 media type alone in Accept. */
    private static void request() {
        check("android".equals(ProMonetization.PLATFORM), "this build's platform is android, lower case, as it declares when it links");
        check("monetization?platform=android".equals(ProMonetization.POLICY_PATH), "the request names the platform in its query");
        check("application/vnd.zenlygram.monetization.v4+json".equals(ProMonetization.ACCEPT), "Accept lists the ver-4 media type alone");
        check(ProMonetization.VERSION == 4, "the one shape is ver 4");
    }

    private static ProMonetization.Policy v4(String mode, long revision, long trialDays, long graceDays) throws Exception {
        return fetched(policyV4(active, "k2026a", mode, revision, trialDays, graceDays, NOW, NOW + TTL));
    }

    private static void androidLineOnly() throws Exception {
        check(ProMonetization.INITIAL.version == 0 && ProMonetization.INITIAL.trialDays == 0
                && ProMonetization.INITIAL.localGraceDays == ProPolicy.DEFAULT_LOCAL_GRACE_DAYS && ProPolicy.DEFAULT_LOCAL_GRACE_DAYS == 7,
                "no policy: no shape, and the local grace is the default 7 days");
        for (int days : new int[] {0, 1, 7, 30}) {
            ProMonetization.Policy policy = v4("paid", 4, 0, days);
            check(policy.mode == ProPolicy.Mode.PAID && policy.revision == 4 && policy.issuedAt == NOW && policy.expiresAt == NOW + TTL,
                    "ver 4 accepted with its signed claims (local_grace_days " + days + ")");
            check(policy.version == 4 && policy.localGraceDays == days && policy.trialDays == 0, "ver 4: local_grace_days " + days + " is the length");
        }
        ProMonetization.Policy both = v4("paid", 4, 30, 30);
        check(both.trialDays == 30 && both.localGraceDays == 30, "ver 4 carries both numbers, each at its ceiling");
        ProMonetization.Policy free = v4("free", 4, 0, 12);
        check(free.mode == ProPolicy.Mode.FREE && free.localGraceDays == 12, "ver 4 in free mode keeps the configured value (it is not zeroed)");
        check(fetched(policyV4(next, "k2026b", "paid", 4, 0, 3, NOW, NOW + TTL)).localGraceDays == 3, "ver 4 under every pinned kid");
        check(reason(() -> fetched(policyV4(outsider, "k2026a", "paid", 4, 0, 0, NOW, NOW + TTL))) == ProMonetization.Reason.BAD_SIGNATURE,
                "a ver-4 token of a foreign key is rejected: the length is only ever read from a verified token");
        String token = policyV4(active, "k2026a", "paid", 4, 0, 30, NOW, NOW + TTL);
        String[] parts = token.split("\\.");
        String shortened = parts[0] + "." + segment(policyClaimsV4("paid", 4, 0, 0, NOW, NOW + TTL)) + "." + parts[2];
        check(reason(() -> fetched(shortened)) == ProMonetization.Reason.BAD_SIGNATURE, "local_grace_days changed after signing rejected");

        // The general line (what iOS and every earlier build read, what a server without ver 4 answers): authentic,
        // signed by a pinned key, and never this build's policy, whether fetched or found in the store.
        for (int version = 1; version <= 3; version++) {
            final String other = general(active, "k2026a", version, "free", 9, NOW, NOW + TTL);
            check(reason(() -> fetched(other)) == ProMonetization.Reason.WRONG_PURPOSE, "a ver-" + version + " policy of the general line is refused when fetched");
            check(reason(() -> ProMonetization.verifyStored(other, ring, DECODER)) == ProMonetization.Reason.WRONG_PURPOSE,
                    "a stored ver-" + version + " policy of the general line is no policy of this build");
            Map<String, Object> renamed = generalClaims(version, "free", 9, NOW, NOW + TTL);
            renamed.put("ver", 4L);
            check(purpose(renamed), "the ver-" + version + " claim set is the general line whatever its ver says");
        }
        final ProMonetization.Policy held = v4("paid", 4, 0, 7);
        check(reason(() -> ProMonetization.accept(held, fetched(general(active, "k2026a", 3, "free", 99, NOW + 10, NOW + TTL)))) == ProMonetization.Reason.WRONG_PURPOSE,
                "a general-line policy with a higher revision cannot switch this build to free");

        // The line of another platform, and a platform that is no string.
        check(purpose(with("platform", "ios")), "the line of another platform is another purpose");
        check(purpose(with("platform", "Android")) && purpose(with("platform", "ANDROID")) && purpose(with("platform", "android ")),
                "the platform is compared exactly");
        check(purpose(with("platform", "")), "an empty platform is not android");
        check(malformed(with("platform", 7L)) && malformed(with("platform", null)) && malformed(with("platform", Boolean.TRUE))
                && malformed(with("platform", Collections.singletonList("android"))), "a platform that is not a string rejected");
        check(purpose(without("platform")), "ver 4 without its platform is the general line's ver-3 claim set: refused");

        // ver must name the claim set, and the set is exact.
        check(malformed(with("ver", 3L)) && malformed(with("ver", 2L)) && malformed(with("ver", 1L)), "the ver-4 claim set under an earlier ver rejected");
        check(malformed(without("local_grace_days")), "ver 4 without local_grace_days is no known claim set");
        check(malformed(without("trial_days")), "ver 4 without trial_days is no known claim set");
        check(malformed(without("mode")) && malformed(without("iat")), "every claim of the set is required");
        check(purpose(with("account_ent", Collections.singletonList("vault"))), "account-shaped claim inside a ver-4 policy rejected");
        check(purpose(with("aud", "zenlygram-entitlement")), "ver 4 under another audience rejected");
        check(purpose(with("local_grace_seconds", 0L)), "an unknown claim next to the ver-4 set rejected");
        check(purpose(with("platforms", Collections.singletonList("android"))), "a list of platforms next to the set rejected");
        check(malformed(with("mode", "trial")) && malformed(with("revision", -1L)) && malformed(with("iat", 0L)),
                "the claim rules hold for ver 4");
    }

    /** A token whose local_grace_days is missing, not an integer or outside 0..30 is invalid as a whole. */
    private static void localGraceDaysStrict() throws Exception {
        check(reason(() -> fetched(signed(with("local_grace_days", 0L)))) == null, "0 accepted (no local grace)");
        check(reason(() -> fetched(signed(with("local_grace_days", 30L)))) == null, "30 accepted (the ceiling)");
        check(malformed(with("local_grace_days", 31L)), "31 rejected");
        check(malformed(with("local_grace_days", -1L)), "-1 rejected");
        check(malformed(with("local_grace_days", 365L)), "365 rejected");
        check(malformed(with("local_grace_days", Long.MAX_VALUE)), "a huge value rejected, never truncated into range");
        check(malformed(with("local_grace_days", 7.0)), "7.0 is not an integer");
        check(malformed(with("local_grace_days", 7.5)), "a fraction rejected");
        check(malformed(with("local_grace_days", "7")), "a string rejected");
        check(malformed(with("local_grace_days", Boolean.TRUE)), "a boolean rejected");
        check(malformed(with("local_grace_days", null)), "null rejected");
        check(malformed(with("local_grace_days", Collections.singletonList(7L))), "an array rejected");
        // The same strictness for a stored token: a bad value is no policy at all.
        check(malformed(() -> ProMonetization.verifyStored(signed(with("local_grace_days", 31L)), ring, DECODER)),
                "a stored token with a bad local_grace_days is refused too");
    }

    /** trial_days keeps its rule: an integer in 0..30, else the whole token is refused. */
    private static void trialDaysStrict() throws Exception {
        check(reason(() -> fetched(signed(with("trial_days", 30L)))) == null && reason(() -> fetched(signed(with("trial_days", 0L)))) == null,
                "trial_days 0 and 30 accepted");
        for (Object bad : new Object[] {31L, -1L, 7.0, "7", Boolean.FALSE, null}) {
            check(malformed(with("trial_days", bad)), "trial_days " + bad + " rejected");
        }
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> claims = policyClaims("paid", 1, NOW, NOW + TTL);
        claims.put(key, value);
        return claims;
    }

    private static Map<String, Object> without(String key) {
        Map<String, Object> claims = policyClaims("paid", 1, NOW, NOW + TTL);
        claims.remove(key);
        return claims;
    }

    private static boolean purpose(Map<String, Object> claims) throws Exception {
        return reason(() -> fetched(signed(claims))) == ProMonetization.Reason.WRONG_PURPOSE;
    }

    private static boolean malformed(Map<String, Object> claims) throws Exception {
        return malformed(() -> fetched(signed(claims)));
    }

    // ---- Fetch-time limits -----------------------------------------------------------------

    private static void fetchTimeLimits() throws Exception {
        check(reason(() -> fetched(policy(active, "k2026a", "paid", 1, NOW + SKEW, NOW + SKEW + 60))) == null,
                "issued up to 300 s ahead of trusted time accepted");
        check(reason(() -> fetched(policy(active, "k2026a", "paid", 1, NOW + SKEW + 1, NOW + SKEW + 60)))
                == ProMonetization.Reason.NOT_YET_VALID, "issued more than 300 s in the future rejected");
        check(reason(() -> fetched(policy(active, "k2026a", "paid", 1, NOW - TTL, NOW - SKEW + 1))) == null,
                "expired less than 300 s ago accepted");
        check(reason(() -> fetched(policy(active, "k2026a", "paid", 1, NOW - TTL, NOW - SKEW))) == ProMonetization.Reason.EXPIRED,
                "expired 300 s ago rejected when newly fetched");
        check(reason(() -> ProMonetization.verifyFetched(fresh("paid", 1), ring, DECODER, NOW + TTL + SKEW))
                == ProMonetization.Reason.EXPIRED, "the same token fetched a day later is stale input, not a new policy");
    }

    // ---- Stored reverification -------------------------------------------------------------

    private static void storedReverification() throws Exception {
        long old = NOW - 30 * ProPolicy.DAY;
        String token = policy(active, "k2026a", "paid", 7, old, old + TTL);
        ProMonetization.Policy stored = ProMonetization.verifyStored(token, ring, DECODER);
        check(stored.mode == ProPolicy.Mode.PAID && stored.revision == 7,
                "a previously accepted policy keeps its authenticated mode after its expiry");
        check(token.equals(stored.token), "the stored policy keeps its exact signed token for persistence");
        String[] parts = token.split("\\.");
        check(reason(() -> ProMonetization.verifyStored(parts[0] + "." + segment(policyClaims("free", 7, old, old + TTL)) + "." + parts[2],
                ring, DECODER)) == ProMonetization.Reason.BAD_SIGNATURE, "a tampered stored policy is reverified and rejected");
        Map<String, PublicKey> rotated = new HashMap<>();
        rotated.put("k2026b", next.getPublic());
        check(reason(() -> ProMonetization.verifyStored(token, rotated, DECODER)) == ProMonetization.Reason.UNKNOWN_KEY,
                "a stored policy whose key is no longer pinned is not trusted");
        check(malformed(() -> ProMonetization.verifyStored(policy(active, "k2026a", "paid", 7, NOW, NOW + TTL + 1), ring, DECODER)),
                "lifetime rule still applies to stored policies");
        check(reason(() -> ProMonetization.verifyStored(signed(map("iss", "zenlygram")), ring, DECODER))
                == ProMonetization.Reason.WRONG_PURPOSE, "stored token still needs the exact claim set");
        // The stored token is the only store of the local grace length: reverified, never re-expired, it gives the
        // same number back.
        String grace = policyV4(active, "k2026a", "paid", 7, 4, 21, old, old + TTL);
        ProMonetization.Policy storedGrace = ProMonetization.verifyStored(grace, ring, DECODER);
        check(storedGrace.version == 4 && storedGrace.localGraceDays == 21 && storedGrace.trialDays == 4 && grace.equals(storedGrace.token),
                "a stored policy keeps its local_grace_days after its expiry");
        check(ProMonetization.verifyStored(policyV4(active, "k2026a", "paid", 7, 0, 0, old, old + TTL), ring, DECODER).localGraceDays == 0,
                "a stored 0 stays 0 (never read as the default)");
        check(stored.localGraceDays == 7, "a stored 7 is 7");
        check(reason(() -> ProMonetization.verifyStored(grace, rotated, DECODER)) == ProMonetization.Reason.UNKNOWN_KEY,
                "a stored policy whose key is no longer pinned gives no length");
        check(malformed(() -> ProMonetization.verifyStored("not-a-token", ring, DECODER)), "corrupt stored value rejected");
    }

    // ---- Ordering ----------------------------------------------------------------------------

    private static void ordering() throws Exception {
        ProMonetization.Policy current = fetched(policy(active, "k2026a", "paid", 2, NOW, NOW + TTL));
        check(ProMonetization.accept(ProMonetization.INITIAL, fetched(fresh("free", 0))).revision == 0,
                "absent server setting {free, 0} is accepted over the initial state");
        check(ProMonetization.accept(current, fetched(policy(active, "k2026a", "free", 3, NOW - 50, NOW + 1000))).mode
                == ProPolicy.Mode.FREE, "a higher revision wins even with an older issuance time");
        check(ProMonetization.accept(current, fetched(policy(active, "k2026a", "free", 2, NOW + 1, NOW + TTL))).mode
                == ProPolicy.Mode.FREE, "same revision: the later signed issuance (emergency change) wins");
        // A valid 24 h token (exp = iat + TTL), so the ordering rule, not the lifetime rule, rejects it.
        check(reason(() -> ProMonetization.accept(current, fetched(policy(active, "k2026a", "free", 2, NOW - 1, NOW - 1 + TTL))))
                == ProMonetization.Reason.STALE, "same revision issued earlier rejected");
        check(reason(() -> ProMonetization.accept(current, fetched(policy(active, "k2026a", "free", 1, NOW + 100, NOW + TTL))))
                == ProMonetization.Reason.STALE, "lower revision rejected even if issued later");
        ProMonetization.Policy same = fetched(policy(active, "k2026a", "paid", 2, NOW, NOW + 600));
        check(ProMonetization.accept(current, same) == current, "equal tuple with the same mode is idempotent");
        check(reason(() -> ProMonetization.accept(current, fetched(policy(active, "k2026a", "free", 2, NOW, NOW + TTL))))
                == ProMonetization.Reason.CONFLICT, "equal tuple with a conflicting mode rejected");
    }

    /**
     * (revision, iat) alone orders policies; two tokens of one issuance must agree on the mode, trial_days and
     * local_grace_days.
     */
    private static void orderingOfLengths() throws Exception {
        ProMonetization.Policy three = fetched(policyV4(active, "k2026a", "paid", 2, 5, 3, NOW, NOW + TTL));
        check(ProMonetization.accept(ProMonetization.INITIAL, three) == three, "a policy is accepted over the initial state");
        check(ProMonetization.accept(three, fetched(policyV4(active, "k2026a", "paid", 2, 5, 3, NOW, NOW + 600))) == three,
                "equal tuple, same numbers: idempotent");
        check(reason(() -> ProMonetization.accept(three, fetched(policyV4(active, "k2026a", "paid", 2, 5, 4, NOW, NOW + TTL))))
                == ProMonetization.Reason.CONFLICT, "equal tuple, another local_grace_days: conflicting issuance, the accepted one stands");
        check(reason(() -> ProMonetization.accept(three, fetched(policyV4(active, "k2026a", "paid", 2, 6, 3, NOW, NOW + TTL))))
                == ProMonetization.Reason.CONFLICT, "equal tuple, another trial_days: conflicting issuance");
        check(reason(() -> ProMonetization.accept(three, fetched(policyV4(active, "k2026a", "free", 2, 5, 3, NOW, NOW + TTL))))
                == ProMonetization.Reason.CONFLICT, "equal tuple with another mode conflicts");

        // A newer tuple wins and brings its own numbers.
        ProMonetization.Policy longer = fetched(policyV4(active, "k2026a", "paid", 3, 5, 30, NOW - 50, NOW + 1000));
        check(ProMonetization.accept(three, longer) == longer && longer.localGraceDays == 30, "a higher revision brings its own length");
        ProMonetization.Policy shorter = fetched(policyV4(active, "k2026a", "paid", 3, 5, 0, NOW + 1, NOW + TTL));
        check(ProMonetization.accept(longer, shorter) == shorter && shorter.localGraceDays == 0, "same revision, later issuance: its length wins");

        // A replay can never bring an older length back.
        check(reason(() -> ProMonetization.accept(shorter, longer)) == ProMonetization.Reason.STALE, "an older issuance (a longer grace) is stale");
        check(reason(() -> ProMonetization.accept(shorter, three)) == ProMonetization.Reason.STALE, "a lower revision is stale");
    }

    private static void freePaidFree() throws Exception {
        ProMonetization.Policy state = ProMonetization.INITIAL;
        state = ProMonetization.accept(state, fetched(fresh("free", 0)));
        check(state.mode == ProPolicy.Mode.FREE, "free while the owner has not enabled paid");
        ProMonetization.Policy paid = fetched(policy(active, "k2026a", "paid", 1, NOW + 10, NOW + TTL));
        state = ProMonetization.accept(state, paid);
        check(state.mode == ProPolicy.Mode.PAID && ProMonetization.allowsNewPurchase(state.mode), "deliberate owner enable reveals paid");
        state = ProMonetization.accept(state, fetched(policy(active, "k2026a", "free", 2, NOW + 20, NOW + TTL)));
        check(state.mode == ProPolicy.Mode.FREE && !ProMonetization.allowsNewPurchase(state.mode), "owner can return everyone to free");
        final ProMonetization.Policy settled = state;
        check(reason(() -> ProMonetization.accept(settled, paid)) == ProMonetization.Reason.STALE,
                "a replayed older paid policy cannot re-enable paid");
        check(ProMonetization.accept(settled, ProMonetization.verifyStored(settled.token, ring, DECODER)) == settled,
                "reloading the persisted policy is idempotent");
    }

    // ---- Policy clock --------------------------------------------------------------------------

    /**
     * Policy freshness uses the device wall clock floored at the accepted signed iat, never the
     * account clock (which unsigned account-response server_time can move).
     */
    private static void policyClock() throws Exception {
        check(ProMonetization.policyNow(NOW, ProMonetization.INITIAL) == NOW, "initial state uses the wall clock");
        ProMonetization.Policy accepted = fetched(policy(active, "k2026a", "free", 3, NOW, NOW + TTL));
        check(ProMonetization.policyNow(NOW + 5000, accepted) == NOW + 5000, "wall clock ahead of the accepted iat is used as is");
        check(ProMonetization.policyNow(NOW - 10 * TTL, accepted) == NOW,
                "a wall clock rolled back before the accepted signed iat is floored at that iat");
        String expiredBeforeAccepted = policy(active, "k2026a", "paid", 4, NOW - 2 * TTL, NOW - TTL);
        check(reason(() -> ProMonetization.verifyFetched(expiredBeforeAccepted, ring, DECODER,
                ProMonetization.policyNow(NOW - 2 * TTL, accepted))) == ProMonetization.Reason.EXPIRED,
                "rolling the clock back cannot make a newer-revision but long-expired paid policy fresh");
        check(reason(() -> ProMonetization.verifyFetched(policy(active, "k2026a", "paid", 4, NOW + 10, NOW + TTL), ring, DECODER,
                ProMonetization.policyNow(NOW - 10 * TTL, accepted))) == null,
                "a genuinely fresh newer policy still verifies after a clock rollback");
    }

    // ---- Refresh -------------------------------------------------------------------------------

    private static void refreshSchedule() {
        ProMonetization.Refresh started = ProMonetization.Refresh.IDLE.start(5_000);
        check(started != null && started.inFlight, "first launch refresh starts immediately");
        check(started.start(500_000) == null, "one request in flight at a time");
        ProMonetization.Refresh done = started.finish();
        check(!done.inFlight, "finish releases the in-flight slot");
        check(done.start(5_000 + ProMonetization.REFRESH_INTERVAL_MS - 1) == null, "attempts are at least 60 s apart");
        check(done.start(5_000 + ProMonetization.REFRESH_INTERVAL_MS) != null, "foreground or Pro screen may refresh after 60 s");
        check(done.start(1_000) == null, "an elapsed clock behind the last attempt never bypasses the cooldown");
        check(ProMonetization.REFRESH_INTERVAL_MS == 60_000, "cooldown matches the contract");
        check(ProMonetization.REQUEST_TIMEOUT_MS > 0 && ProMonetization.REQUEST_TIMEOUT_MS <= 15_000, "request timeout is bounded");
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static ProMonetization.Reason reason(Call call) throws Exception {
        try { call.run(); return null; }
        catch (ProMonetization.Rejected rejected) { return rejected.reason; }
    }

    private static boolean malformed(Call call) throws Exception { return reason(call) == ProMonetization.Reason.MALFORMED; }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
