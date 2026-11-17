/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.cloud;

import org.json.JSONObject;

import org.telegram.messenger.zenlygram.pro.ProPolicy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The two signed PUBLIC policies of the Zenlygram server, verified against the pinned ring (iOS SGEntitlementPolicy +
 * SGAppUpdatePolicy, docs/entitlement.md §3.9a and §3.9c). Pure Java.
 *
 * <ul>
 *   <li>{@code GET /v1/monetization?platform=android}: aud {@code zenlygram-monetization}; the Android line of the
 *       policy, ver 4 = exactly 10 claims (the general line's nine, {@code trial_days} 0…30 and
 *       {@code local_grace_days} 0…30 among them, plus {@code platform} = {@code "android"}). A token of the general
 *       line (the exact v1, v2 or v3 claim set) and a ver-4 token of another platform are another purpose: refused.
 *       A token whose {@code trial_days} or {@code local_grace_days} is missing, not an integer or out of range is
 *       refused as a whole.</li>
 *   <li>{@code GET /v1/app-policy}: aud {@code zenlygram-app-policy}, ver 1, exactly 8 claims; {@code ios} and
 *       {@code android} are {@code null} or {@code {"min","latest"}} with min &lt;= latest.</li>
 * </ul>
 * Header: exactly {@code alg}, {@code typ}, {@code kid}. Freshness (fetched tokens only): {@code iat <= now + 300},
 * {@code exp > now - 300}, {@code 0 < exp - iat <= 86400}. A stored token is re-verified on load, never re-expired.
 */
public final class ZgPublicPolicy {

    public static final String ISSUER = "zenlygram";
    public static final String AUD_MONETIZATION = "zenlygram-monetization";
    public static final String AUD_APP_POLICY = "zenlygram-app-policy";
    public static final long SKEW_SECONDS = 300;
    public static final long MAX_LIFETIME_SECONDS = 86400;

    private static final Set<String> HEADER_KEYS = set("alg", "typ", "kid");
    private static final Set<String> MONETIZATION_V4 = set("iss", "aud", "ver", "platform", "mode", "revision", "trial_days",
        "local_grace_days", "iat", "exp");
    /** The one monetization claim shape this build accepts, and the platform whose line it follows. */
    public static final int MONETIZATION_VERSION = 4;
    public static final String PLATFORM = org.telegram.messenger.zenlygram.pro.ProMonetization.PLATFORM;
    private static final Set<String> APP_POLICY_V1 = set("iss", "aud", "ver", "revision", "ios", "android", "iat", "exp");
    private static final Set<String> PLATFORM_KEYS = set("min", "latest");
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;

    public enum Failure {
        MALFORMED,
        UNKNOWN_KID,
        BAD_SIGNATURE,
        /** Authentic, but another purpose (wrong aud / claim set / ver). */
        WRONG_PURPOSE,
        /** Right purpose, but a claim breaks the contract (types, ranges, ordering). */
        INVALID_CLAIMS,
        /** Fetched token outside the freshness window on the policy clock. */
        STALE
    }

    /** Fields every policy carries. */
    public abstract static class Signed {
        public final String compact;
        public final String kid;
        public final int ver;
        public final long revision;
        public final long iat;
        public final long exp;

        Signed(String compact, String kid, int ver, long revision, long iat, long exp) {
            this.compact = compact;
            this.kid = kid;
            this.ver = ver;
            this.revision = revision;
            this.iat = iat;
            this.exp = exp;
        }

        /** Lexicographic (revision, iat): &gt; 0 when this is newer than {@code other}. */
        public int compareOrder(Signed other) {
            if (revision != other.revision) {
                return revision > other.revision ? 1 : -1;
            }
            return Long.compare(iat, other.iat);
        }
    }

    public static final class Monetization extends Signed {
        public final boolean paid;
        /** The signed {@code trial_days} (0 = no server trial offered). */
        public final int trialDays;
        /**
         * The local grace length in days this policy sets: the signed {@code local_grace_days} (0 = no local grace).
         * Informational here: the access decision reads the length from the cloud client's own accepted token
         * ({@code ProMonetization.Policy.localGraceDays}), never from this copy.
         */
        public final int localGraceDays;

        Monetization(String compact, String kid, int ver, long revision, long iat, long exp, boolean paid, int trialDays,
                     int localGraceDays) {
            super(compact, kid, ver, revision, iat, exp);
            this.paid = paid;
            this.trialDays = trialDays;
            this.localGraceDays = localGraceDays;
        }

        public boolean offersServerTrial() {
            return paid && trialDays > 0;
        }
    }

    /** One platform's constraint. */
    public static final class Constraint {
        public final ZgAppVersion min;
        public final ZgAppVersion latest;

        Constraint(ZgAppVersion min, ZgAppVersion latest) {
            this.min = min;
            this.latest = latest;
        }

        boolean sameAs(Constraint other) {
            if (other == null) {
                return false;
            }
            return min.compareTo(other.min) == 0 && latest.compareTo(other.latest) == 0
                && min.raw.equals(other.min.raw) && latest.raw.equals(other.latest.raw);
        }
    }

    public static final class AppPolicy extends Signed {
        public final Constraint ios;
        /** Null = no constraint for Android. */
        public final Constraint android;

        AppPolicy(String compact, String kid, int ver, long revision, long iat, long exp, Constraint ios, Constraint android) {
            super(compact, kid, ver, revision, iat, exp);
            this.ios = ios;
            this.android = android;
        }

        boolean sameValues(AppPolicy other) {
            return sameConstraint(ios, other.ios) && sameConstraint(android, other.android);
        }

        private static boolean sameConstraint(Constraint a, Constraint b) {
            return a == null ? b == null : a.sameAs(b);
        }
    }

    public static final class Parsed<T extends Signed> {
        public final T policy;
        public final Failure failure;

        Parsed(T policy, Failure failure) {
            this.policy = policy;
            this.failure = failure;
        }

        public boolean isOk() {
            return policy != null;
        }
    }

    /** What to do with a candidate against the accepted one. */
    public enum Order {
        ADOPT,
        /** Same tuple and content: keep what we have. */
        UNCHANGED,
        /** Older than the accepted one. */
        OLDER,
        /** Same tuple, different content: the accepted token stands. */
        CONFLICT
    }

    private ZgPublicPolicy() {
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Verifies and parses a monetization token. {@code nowSeconds} null = a stored token (no freshness check);
     * otherwise the policy clock.
     */
    public static Parsed<Monetization> parseMonetization(String compact, ZgJws.KeyRing ring, Long nowSeconds) {
        final ZgJws.Result result = ZgJws.verify(compact, ring);
        if (!result.isOk()) {
            return new Parsed<>(null, map(result.failure));
        }
        final JSONObject header = result.verified.header;
        final JSONObject payload = result.verified.payload;
        if (!HEADER_KEYS.equals(ZgJws.keys(header))) {
            return new Parsed<>(null, Failure.MALFORMED);
        }
        if (!ISSUER.equals(payload.opt("iss")) || !AUD_MONETIZATION.equals(payload.opt("aud"))) {
            return new Parsed<>(null, Failure.WRONG_PURPOSE);
        }
        final Set<String> keys = ZgJws.keys(payload);
        // Only the Android line's shape: a policy of the general line (v1, v2, v3) is an authentic policy of another
        // purpose, and so is any other set of claims.
        final int expectedVer = MONETIZATION_VERSION;
        if (!MONETIZATION_V4.equals(keys)) {
            return new Parsed<>(null, Failure.WRONG_PURPOSE);
        }
        final Long ver = integer(payload.opt("ver"));
        if (ver == null || ver != expectedVer) {
            return new Parsed<>(null, Failure.WRONG_PURPOSE);
        }
        if (!PLATFORM.equals(payload.opt("platform"))) {
            return new Parsed<>(null, Failure.WRONG_PURPOSE);
        }
        final Object mode = payload.opt("mode");
        if (!"free".equals(mode) && !"paid".equals(mode)) {
            return new Parsed<>(null, Failure.INVALID_CLAIMS);
        }
        // JSON integers in 0…30, never a boolean, a fraction, a string or null: anything else refuses the token.
        final Long trial = integer(payload.opt("trial_days"));
        if (trial == null || trial < 0 || trial > 30) {
            return new Parsed<>(null, Failure.INVALID_CLAIMS);
        }
        final int trialDays = trial.intValue();
        final Long grace = integer(payload.opt("local_grace_days"));
        if (grace == null || grace < 0 || grace > ProPolicy.MAX_LOCAL_GRACE_DAYS) {
            return new Parsed<>(null, Failure.INVALID_CLAIMS);
        }
        final int localGraceDays = grace.intValue();
        final long[] times = times(payload, nowSeconds);
        if (times == null) {
            return new Parsed<>(null, Failure.INVALID_CLAIMS);
        }
        if (times.length == 0) {
            return new Parsed<>(null, Failure.STALE);
        }
        return new Parsed<>(new Monetization(compact, result.verified.kid, expectedVer, times[0], times[1], times[2],
            "paid".equals(mode), trialDays, localGraceDays), null);
    }

    /** Verifies and parses an app update policy token ({@code nowSeconds} as for monetization). */
    public static Parsed<AppPolicy> parseAppPolicy(String compact, ZgJws.KeyRing ring, Long nowSeconds) {
        final ZgJws.Result result = ZgJws.verify(compact, ring);
        if (!result.isOk()) {
            return new Parsed<>(null, map(result.failure));
        }
        final JSONObject header = result.verified.header;
        final JSONObject payload = result.verified.payload;
        if (!HEADER_KEYS.equals(ZgJws.keys(header))) {
            return new Parsed<>(null, Failure.MALFORMED);
        }
        if (!ISSUER.equals(payload.opt("iss")) || !AUD_APP_POLICY.equals(payload.opt("aud"))) {
            return new Parsed<>(null, Failure.WRONG_PURPOSE);
        }
        if (!APP_POLICY_V1.equals(ZgJws.keys(payload))) {
            return new Parsed<>(null, Failure.WRONG_PURPOSE);
        }
        final Long ver = integer(payload.opt("ver"));
        if (ver == null || ver != 1) {
            return new Parsed<>(null, Failure.WRONG_PURPOSE);
        }
        final Constraint[] platforms = new Constraint[2];
        final String[] names = {"ios", "android"};
        for (int i = 0; i < 2; i++) {
            final Object value = payload.opt(names[i]);
            if (value == null || value == JSONObject.NULL) {
                platforms[i] = null;
                continue;
            }
            if (!(value instanceof JSONObject)) {
                return new Parsed<>(null, Failure.INVALID_CLAIMS);
            }
            final JSONObject object = (JSONObject) value;
            if (!PLATFORM_KEYS.equals(ZgJws.keys(object))) {
                return new Parsed<>(null, Failure.INVALID_CLAIMS);
            }
            final Object min = object.opt("min");
            final Object latest = object.opt("latest");
            final ZgAppVersion minVersion = min instanceof String ? ZgAppVersion.parse((String) min) : null;
            final ZgAppVersion latestVersion = latest instanceof String ? ZgAppVersion.parse((String) latest) : null;
            if (minVersion == null || latestVersion == null || minVersion.compareTo(latestVersion) > 0) {
                return new Parsed<>(null, Failure.INVALID_CLAIMS);
            }
            platforms[i] = new Constraint(minVersion, latestVersion);
        }
        final long[] times = times(payload, nowSeconds);
        if (times == null) {
            return new Parsed<>(null, Failure.INVALID_CLAIMS);
        }
        if (times.length == 0) {
            return new Parsed<>(null, Failure.STALE);
        }
        return new Parsed<>(new AppPolicy(compact, result.verified.kid, 1, times[0], times[1], times[2],
            platforms[0], platforms[1]), null);
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Monetization ordering matrix (§3.9a). A newer (revision, iat) of the Android line is adopted.
     * An equal tuple is the same issuance: unchanged, unless the mode, {@code trial_days} or
     * {@code local_grace_days} differ, which is a conflict (every accepted policy has the one ver-4 shape).
     */
    public static Order order(Monetization candidate, Monetization current) {
        if (current == null) {
            return Order.ADOPT;
        }
        final int cmp = candidate.compareOrder(current);
        if (cmp > 0) {
            return Order.ADOPT;
        }
        if (cmp < 0) {
            return Order.OLDER;
        }
        if (candidate.paid != current.paid || candidate.trialDays != current.trialDays
            || candidate.localGraceDays != current.localGraceDays) {
            return Order.CONFLICT;
        }
        return Order.UNCHANGED;
    }

    /** App policy ordering (§3.9c). */
    public static Order order(AppPolicy candidate, AppPolicy current) {
        if (current == null) {
            return Order.ADOPT;
        }
        final int cmp = candidate.compareOrder(current);
        if (cmp > 0) {
            return Order.ADOPT;
        }
        if (cmp < 0) {
            return Order.OLDER;
        }
        return candidate.sameValues(current) ? Order.UNCHANGED : Order.CONFLICT;
    }

    /**
     * The policy clock: the wall clock floored at the accepted policy's signed iat (iOS), so a clock set back does not
     * refuse the policy we already hold.
     */
    public static long policyNow(long wallSeconds, Signed accepted) {
        return accepted == null ? wallSeconds : Math.max(wallSeconds, accepted.iat);
    }

    // ---------------------------------------------------------------------------------------------------------

    /** {revision, iat, exp}; null = invalid claims; empty = stale. */
    private static long[] times(JSONObject payload, Long now) {
        final Long revision = integer(payload.opt("revision"));
        final Long iat = integer(payload.opt("iat"));
        final Long exp = integer(payload.opt("exp"));
        if (revision == null || iat == null || exp == null || revision < 0 || revision > MAX_SAFE_INTEGER || iat < 0) {
            return null;
        }
        if (exp <= iat || exp - iat > MAX_LIFETIME_SECONDS) {
            return null;
        }
        if (now != null && (iat > now + SKEW_SECONDS || exp <= now - SKEW_SECONDS)) {
            return new long[0];
        }
        return new long[]{revision, iat, exp};
    }

    /** A JSON integer (never a float, string or boolean). */
    static Long integer(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private static Failure map(ZgJws.Failure failure) {
        switch (failure) {
            case UNKNOWN_KID:
                return Failure.UNKNOWN_KID;
            case BAD_SIGNATURE:
                return Failure.BAD_SIGNATURE;
            default:
                return Failure.MALFORMED;
        }
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
