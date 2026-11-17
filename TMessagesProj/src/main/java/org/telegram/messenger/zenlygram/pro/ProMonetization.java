package org.telegram.messenger.zenlygram.pro;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Pure free-first monetization policy (resources/contracts/monetization-policy.v4.json): the public
 * GET /v1/monetization envelope, its ES256 policy token verified against the pinned entitlement key
 * ring, (revision, iat) ordering, and the refresh schedule. No Android types: JSON text is decoded
 * by the caller's {@link Decoder} (ZenlyPro adapts Android's org.json) into typed values: String,
 * Long (every integer), Double (any other number), Boolean, null, List and Map.
 *
 * This build follows the ANDROID line of the policy: the owner decides free or paid for Android apart from the
 * general line that iOS and every earlier build read. It asks for that line ({@link #POLICY_PATH},
 * {@link #ACCEPT}) and accepts ONE claim shape: ver 4, exactly iss, aud, ver, platform, mode, revision,
 * trial_days (0..30, the server's own trial offer), local_grace_days (0..30, the length of the local grace,
 * ProPolicy.evaluate), iat, exp, with platform = "android". A token of the general line (the exact ver 1, 2 or 3
 * claim sets) and a ver-4 token of another platform are authentic policies of another purpose: refused, the last
 * accepted policy stays. So a general-line token cannot switch this build, whoever serves it, and this build's
 * token cannot switch a build of the general line, which refuses every shape it does not know. A ver-4 token
 * whose trial_days or local_grace_days is missing, not an integer or out of range is refused as a whole.
 *
 * Only a verified signed policy can change the mode or the local grace length; the unsigned envelope fields
 * (server_time) never do. A stored policy keeps its authenticated mode and length after its expiry; expiry
 * applies only when accepting a newly fetched token.
 */
public final class ProMonetization {
    public static final long TTL_SECONDS = 86400, SKEW_SECONDS = 300;
    public static final int MIN_TOKEN_LENGTH = 100, MAX_TOKEN_LENGTH = 8192, MAX_RESPONSE_BYTES = 16384;
    public static final long REFRESH_INTERVAL_MS = 60_000;
    /** Connect and read timeouts of the public policy request, each. */
    public static final int REQUEST_TIMEOUT_MS = 10_000;
    /** The platform whose line of the policy this build follows, and the only value of the platform claim it accepts. */
    public static final String PLATFORM = "android";
    /** The request, relative to the API base: the policy of this platform's line. */
    public static final String POLICY_PATH = "monetization?platform=" + PLATFORM;
    /**
     * The Accept header of that request: the ver-4 media type alone. The server mints ver 4 only for a request
     * that lists this exact type AND names a served platform in its query; a server that does not know ver 4 (before
     * its release, after a rollback) answers ver 1, which this build refuses, keeping the policy it holds.
     */
    public static final String ACCEPT = "application/vnd.zenlygram.monetization.v4+json";
    /** The contract's ceiling of a signed trial_days (0..30 inclusive). */
    public static final int MAX_TRIAL_DAYS = 30;
    static final String ISSUER = "zenlygram", AUDIENCE = "zenlygram-monetization";
    private static final long MAX_REVISION = 9007199254740991L;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Set<String> ENVELOPE = keys("policy_token", "server_time");
    private static final Set<String> HEADER = keys("alg", "typ", "kid");
    private static final Set<String> CLAIMS_V1 = keys("iss", "aud", "ver", "mode", "revision", "iat", "exp");
    private static final Set<String> CLAIMS_V2 = keys("iss", "aud", "ver", "mode", "revision", "trial_days", "iat", "exp");
    private static final Set<String> CLAIMS_V3 = keys("iss", "aud", "ver", "mode", "revision", "trial_days", "local_grace_days", "iat", "exp");
    private static final Set<String> CLAIMS_V4 = keys("iss", "aud", "ver", "platform", "mode", "revision", "trial_days",
            "local_grace_days", "iat", "exp");
    /** The one claim shape this build accepts, as {@link Policy#version} names it. */
    public static final int VERSION = 4;

    public enum Reason { MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, WRONG_PURPOSE, NOT_YET_VALID, EXPIRED, STALE, CONFLICT, TIMED_OUT }

    public static final class Rejected extends Exception {
        private static final long serialVersionUID = 1L;
        public final Reason reason;
        Rejected(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }
    }

    /** UTF-8 JSON object text to typed values; throws on anything that is not a JSON object. */
    public interface Decoder { Map<String, Object> object(byte[] utf8) throws Exception; }

    /** A verified signed policy. INITIAL (free, no token) until the first one is accepted. */
    public static final class Policy {
        public final ProPolicy.Mode mode;
        public final long revision, issuedAt, expiresAt;
        /** Exact signed token, persisted and reverified on load; null only for INITIAL. */
        public final String token;
        /** The claim shape the token was minted in: {@link #VERSION} for every verified policy, 0 for INITIAL. */
        public final int version;
        /** trial_days of the token (0 = no server trial offered); 0 for INITIAL. */
        public final int trialDays;
        /**
         * The local grace length in days this policy sets: the signed local_grace_days of the token (0..30,
         * 0 = no local grace), ProPolicy.DEFAULT_LOCAL_GRACE_DAYS for INITIAL. It is read from the verified claims
         * every time the token is verified (fetched, or the stored one on load), so the persisted token is its only
         * store.
         */
        public final int localGraceDays;

        /** A verified policy that offers no server trial and sets the default local grace length (host tests). */
        Policy(ProPolicy.Mode mode, long revision, long issuedAt, long expiresAt, String token) {
            this(mode, revision, issuedAt, expiresAt, token, VERSION, 0, ProPolicy.DEFAULT_LOCAL_GRACE_DAYS);
        }

        Policy(ProPolicy.Mode mode, long revision, long issuedAt, long expiresAt, String token, int version, int trialDays,
               int localGraceDays) {
            this.mode = mode;
            this.revision = revision;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.token = token;
            this.version = version;
            this.trialDays = trialDays;
            this.localGraceDays = localGraceDays;
        }
    }

    public static final Policy INITIAL = new Policy(ProPolicy.Mode.FREE, -1, 0, 0, null, 0, 0, ProPolicy.DEFAULT_LOCAL_GRACE_DAYS);

    /** Only verified paid mode may initiate a purchase, a trial, a purchase prepare or a product query. */
    public static boolean allowsNewPurchase(ProPolicy.Mode mode) { return mode == ProPolicy.Mode.PAID; }

    /**
     * Trusted time for policy freshness: the device wall clock floored at the accepted policy's signed
     * iat. Never the account clock, which unsigned account-response server_time can move.
     */
    public static long policyNow(long wallSeconds, Policy accepted) {
        return Math.max(wallSeconds, accepted.issuedAt);
    }

    /** Paid mode with a legacy entitlement (no genuine account claims): refresh it now, never use ent/until. */
    public static boolean accountRefreshDue(ProPolicy.Mode mode, ProPolicy.Claims claims) {
        return mode == ProPolicy.Mode.PAID && claims != null && !claims.account.present;
    }

    /** Reads at most {@code limit} bytes as strict UTF-8; a longer body is rejected as soon as it exceeds it. */
    public static String readBounded(InputStream stream, int limit) throws Rejected, IOException {
        return readBounded(stream, limit, Long.MAX_VALUE, () -> 0L);
    }

    /**
     * As above, and abandoned (TIMED_OUT) once {@code clock} (nanoseconds) reaches {@code deadlineNanos}:
     * a total budget checked before every read, so a slow drip of small reads cannot outlast it. A read
     * already blocked is interrupted by the caller's watchdog, not here.
     */
    public static String readBounded(InputStream stream, int limit, long deadlineNanos, LongSupplier clock) throws Rejected, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while (true) {
            if (clock.getAsLong() >= deadlineNanos) throw new Rejected(Reason.TIMED_OUT);
            count = stream.read(buffer, 0, Math.min(buffer.length, limit + 1 - out.size()));
            if (count == -1) break;
            out.write(buffer, 0, count);
            if (out.size() > limit) throw new Rejected(Reason.MALFORMED);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();
        } catch (CharacterCodingException e) { throw new Rejected(Reason.MALFORMED); }
    }

    /** The policy token of an exact {policy_token, server_time} envelope. server_time is never trusted. */
    public static String policyToken(Map<String, Object> envelope) throws Rejected {
        if (envelope == null || !envelope.keySet().equals(ENVELOPE) || !(envelope.get("server_time") instanceof String)) {
            throw new Rejected(Reason.MALFORMED);
        }
        Object token = envelope.get("policy_token");
        if (!(token instanceof String) || !wellFormed((String) token)) throw new Rejected(Reason.MALFORMED);
        return (String) token;
    }

    /** A newly fetched token: full verification including iat <= now + 300 and exp > now - 300. */
    public static Policy verifyFetched(String token, Map<String, PublicKey> keys, Decoder decoder, long now) throws Rejected {
        Policy policy = verify(token, keys, decoder);
        if (policy.issuedAt > now + SKEW_SECONDS) throw new Rejected(Reason.NOT_YET_VALID);
        if (policy.expiresAt <= now - SKEW_SECONDS) throw new Rejected(Reason.EXPIRED);
        return policy;
    }

    /** A previously accepted token: reverified with the pinned keys; its expiry does not change its mode. */
    public static Policy verifyStored(String token, Map<String, PublicKey> keys, Decoder decoder) throws Rejected {
        return verify(token, keys, decoder);
    }

    /**
     * Lexicographically newer (revision, iat) replaces current: the revision is this platform's own line, never
     * compared with a revision of the general line (such a token is never verified here). Anything older is stale.
     * An equal tuple is the same issuance again: it keeps current (idempotent), unless its mode, trial_days or
     * local_grace_days differ, which the server never issues under one (revision, iat) and is a conflict. The
     * returned object is {@code current} itself whenever nothing is adopted.
     */
    public static Policy accept(Policy current, Policy candidate) throws Rejected {
        if (candidate.revision != current.revision) {
            if (candidate.revision > current.revision) return candidate;
            throw new Rejected(Reason.STALE);
        }
        if (candidate.issuedAt != current.issuedAt) {
            if (candidate.issuedAt > current.issuedAt) return candidate;
            throw new Rejected(Reason.STALE);
        }
        if (candidate.mode != current.mode || candidate.trialDays != current.trialDays
                || candidate.localGraceDays != current.localGraceDays) {
            throw new Rejected(Reason.CONFLICT);
        }
        return current;
    }

    /**
     * Whether the one-time "Zenlygram Pro is now a subscription" notice is owed after {@code next} replaced
     * {@code previous} (iOS entitlement §5.1.2, the flip notice). Owed when an adopted free policy gives way to a
     * paid one. Never for the first policy an install adopts (previous = INITIAL: nothing to announce), kept as it
     * was by a paid to paid revision (an operator edit neither repeats nor swallows it), and dropped by a return
     * to free (nothing blocks then; a later flip announces again).
     */
    public static boolean noticeOwed(Policy previous, Policy next, boolean owed) {
        if (next.mode != ProPolicy.Mode.PAID) return false;
        if (previous.mode == ProPolicy.Mode.PAID) return owed;
        return previous.token != null;
    }

    /** One request in flight; attempts (launch, foreground, Pro screen) at least REFRESH_INTERVAL_MS apart. */
    public static final class Refresh {
        public static final Refresh IDLE = new Refresh(false, false, 0);
        public final boolean inFlight;
        private final boolean attempted;
        private final long lastStartElapsed;

        private Refresh(boolean inFlight, boolean attempted, long lastStartElapsed) {
            this.inFlight = inFlight;
            this.attempted = attempted;
            this.lastStartElapsed = lastStartElapsed;
        }

        /** The started state, or null when a request runs or the last one started under a minute ago. */
        public Refresh start(long elapsedMs) {
            if (inFlight || (attempted && elapsedMs - lastStartElapsed < REFRESH_INTERVAL_MS)) return null;
            return new Refresh(true, true, elapsedMs);
        }

        public Refresh finish() { return new Refresh(false, attempted, lastStartElapsed); }
    }

    // ---- Verification --------------------------------------------------------------------

    private static Policy verify(String token, Map<String, PublicKey> keys, Decoder decoder) throws Rejected {
        if (token == null || !wellFormed(token)) throw new Rejected(Reason.MALFORMED);
        String[] parts = token.split("\\.", -1);
        Map<String, Object> header = decode(decoder, parts[0]);
        if (!header.keySet().equals(HEADER) || !"ES256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))
                || !(header.get("kid") instanceof String)) {
            throw new Rejected(Reason.MALFORMED);
        }
        PublicKey key = keys.get(header.get("kid"));
        if (key == null) throw new Rejected(Reason.UNKNOWN_KEY);
        try {
            if (!ProCrypto.verify(key, parts[0], parts[1], base64Url(parts[2]))) throw new Rejected(Reason.BAD_SIGNATURE);
        } catch (GeneralSecurityException e) { throw new Rejected(Reason.BAD_SIGNATURE); }
        return claims(decode(decoder, parts[1]), token);
    }

    private static Policy claims(Map<String, Object> payload, String token) throws Rejected {
        // Purpose first: account entitlements and anything else signed by the same key ring are not policies.
        Set<String> names = payload.keySet();
        if (!CLAIMS_V4.containsAll(names) || !ISSUER.equals(payload.get("iss")) || !AUDIENCE.equals(payload.get("aud"))) {
            throw new Rejected(Reason.WRONG_PURPOSE);
        }
        // A policy of the general line (exactly the ver 1, 2 or 3 claim set, whatever its ver says) is not this
        // platform's policy: another purpose, like an entitlement. It is what an older server answers, too.
        if (names.equals(CLAIMS_V1) || names.equals(CLAIMS_V2) || names.equals(CLAIMS_V3)) throw new Rejected(Reason.WRONG_PURPOSE);
        // Exactly the ver-4 claim set, named by ver 4. A claim missing from it (no local_grace_days, no platform
        // beside the others) or another ver is refused: an unknown shape is never read as "unknown claims ignored".
        if (!names.equals(CLAIMS_V4) || !Long.valueOf(VERSION).equals(payload.get("ver"))) throw new Rejected(Reason.MALFORMED);
        // The line of another platform is an authentic policy of another purpose; anything that is not a string is no claim.
        Object platform = payload.get("platform");
        if (!(platform instanceof String)) throw new Rejected(Reason.MALFORMED);
        if (!PLATFORM.equals(platform)) throw new Rejected(Reason.WRONG_PURPOSE);
        ProPolicy.Mode mode;
        if ("free".equals(payload.get("mode"))) mode = ProPolicy.Mode.FREE;
        else if ("paid".equals(payload.get("mode"))) mode = ProPolicy.Mode.PAID;
        else throw new Rejected(Reason.MALFORMED);
        long revision = integer(payload.get("revision"), 0, MAX_REVISION);
        long issuedAt = integer(payload.get("iat"), 1, Long.MAX_VALUE);
        long expiresAt = integer(payload.get("exp"), 1, Long.MAX_VALUE);
        if (expiresAt <= issuedAt || expiresAt - issuedAt > TTL_SECONDS) throw new Rejected(Reason.MALFORMED);
        // JSON integers in 0..30, never a boolean, a fraction, a string or null: anything else refuses the token.
        int trialDays = (int) integer(payload.get("trial_days"), 0, MAX_TRIAL_DAYS);
        int localGraceDays = (int) integer(payload.get("local_grace_days"), 0, ProPolicy.MAX_LOCAL_GRACE_DAYS);
        return new Policy(mode, revision, issuedAt, expiresAt, token, VERSION, trialDays, localGraceDays);
    }

    private static long integer(Object value, long minimum, long maximum) throws Rejected {
        if (!(value instanceof Long)) throw new Rejected(Reason.MALFORMED);
        long result = (Long) value;
        if (result < minimum || result > maximum) throw new Rejected(Reason.MALFORMED);
        return result;
    }

    private static Map<String, Object> decode(Decoder decoder, String segment) throws Rejected {
        Map<String, Object> value;
        try { value = decoder.object(base64Url(segment)); }
        catch (Rejected rejected) { throw rejected; }
        catch (Exception e) { throw new Rejected(Reason.MALFORMED); }
        if (value == null) throw new Rejected(Reason.MALFORMED);
        return value;
    }

    private static boolean wellFormed(String token) {
        return token.length() >= MIN_TOKEN_LENGTH && token.length() <= MAX_TOKEN_LENGTH && TOKEN.matcher(token).matches();
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /** Canonical unpadded base64url (RFC 7515): no padding, no stray bits. */
    static byte[] base64Url(String value) throws Rejected {
        if (value.length() % 4 == 1) throw new Rejected(Reason.MALFORMED);
        byte[] out = new byte[value.length() * 3 / 4];
        int bits = 0, buffer = 0, index = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = ALPHABET.indexOf(value.charAt(i));
            if (digit < 0) throw new Rejected(Reason.MALFORMED);
            buffer = (buffer << 6) | digit;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[index++] = (byte) (buffer >> bits);
                buffer &= (1 << bits) - 1;
            }
        }
        if (buffer != 0) throw new Rejected(Reason.MALFORMED);
        return index == out.length ? out : Arrays.copyOf(out, index);
    }

    private static Set<String> keys(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private ProMonetization() { }
}
