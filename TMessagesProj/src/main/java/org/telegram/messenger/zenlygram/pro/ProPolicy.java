package org.telegram.messenger.zenlygram.pro;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Pure, offline access policy. Never use this to restrict existing protected data.
 *
 * Free-first (resources/contracts/monetization-policy.json): every install is FREE until a verified
 * owner-enabled paid policy (ProMonetization) says PAID. FREE unlocks the shipped local capabilities
 * for everyone with no deadline. PAID uses only the genuine account claims (account_ent /
 * account_until) bounded by the signed exp + grace; the legacy ent/until claims (which can carry a
 * wildcard and a synthetic far horizon) never grant paid access.
 *
 * The local grace ("the local week"): under a paid policy an install that nothing else grants is free for
 * localGraceDays days from its grace start ({@link #graceStart}). The length is the caller's to pass and comes
 * from one place only, the last accepted SIGNED monetization policy (ProMonetization.Policy.localGraceDays: the
 * token's local_grace_days, {@link #DEFAULT_LOCAL_GRACE_DAYS} while no policy was accepted yet). 0 = no local
 * grace at all. It is never stored on its own and never read from anything unsigned.
 */
public final class ProPolicy {
    public static final long DAY = 86400L;
    /**
     * The local grace length, in days, while no signed policy was accepted yet: the seven days every build had
     * before the owner could set the length. (An accepted policy always says its own length.)
     */
    public static final int DEFAULT_LOCAL_GRACE_DAYS = 7;
    /** The contract's ceiling of a signed local_grace_days (0..30 inclusive). */
    public static final int MAX_LOCAL_GRACE_DAYS = 30;
    public static final long CLOCK_TOLERANCE = 2 * DAY;

    /** Installation-wide monetization mode from the verified signed policy. */
    public enum Mode { FREE, PAID }

    public enum State { FREE, ACTIVE, LOCAL_TRIAL, EXPIRED, RECONNECT, LINK_REQUIRED, UNCONFIGURED }

    /** Capabilities the backend may sign into account_ent (contract enum; never a wildcard). */
    private static final Set<String> ACCOUNT_CAPABILITIES;
    /** Shipped local Pro capabilities: free mode and the paid-mode local grace. Never AI. */
    private static final Set<String> BASE;
    static {
        Set<String> base = new HashSet<>();
        Collections.addAll(base, "vault", "chatlock", "tasks", "edithistory");
        BASE = Collections.unmodifiableSet(base);
        Set<String> account = new HashSet<>(base);
        account.add("ai");
        ACCOUNT_CAPABILITIES = Collections.unmodifiableSet(account);
    }

    /**
     * Genuine account paid/trial/manual/VIP rights signed as account_ent + account_until. ABSENT
     * means a legacy token (or malformed account claims): it never grants paid access.
     */
    public static final class AccountGrant {
        public static final AccountGrant ABSENT = new AccountGrant(false, Collections.<String>emptySet(), 0);

        public final boolean present;
        public final Set<String> capabilities;
        /** account_until in epoch seconds; 0 when null (no paid horizon). */
        public final long until;

        private AccountGrant(boolean present, Set<String> capabilities, long until) {
            this.present = present;
            this.capabilities = capabilities;
            this.until = until;
        }

        public static AccountGrant of(Set<String> capabilities, long until) {
            for (String capability : capabilities) {
                if (!ACCOUNT_CAPABILITIES.contains(capability)) throw new IllegalArgumentException("Unknown account capability");
            }
            return new AccountGrant(true, Collections.unmodifiableSet(new HashSet<>(capabilities)), Math.max(0, until));
        }

        /**
         * Reads the account claims of a verified entitlement payload (typed values: String, Long,
         * List, null). Both keys must be present with exact types, otherwise ABSENT.
         */
        public static AccountGrant parse(Map<String, Object> payload) {
            if (payload == null || !payload.containsKey("account_ent") || !payload.containsKey("account_until")) return ABSENT;
            Object entitlements = payload.get("account_ent"), until = payload.get("account_until");
            if (!(entitlements instanceof List)) return ABSENT;
            Set<String> capabilities = new HashSet<>();
            for (Object item : (List<?>) entitlements) {
                if (!(item instanceof String) || !ACCOUNT_CAPABILITIES.contains(item) || !capabilities.add((String) item)) return ABSENT;
            }
            long seconds = 0;
            if (until != null) {
                if (!(until instanceof String)) return ABSENT;
                seconds = utc((String) until);
                if (seconds <= 0) return ABSENT;
            }
            return of(capabilities, seconds);
        }
    }

    public static final class Claims {
        public final String accountId, deviceId;
        public final long telegramId, issuedAt, expiresAt, until, graceDays;
        /** Legacy ent: kept for old clients' semantics; never used for access. */
        public final Set<String> capabilities;
        public final AccountGrant account;

        public Claims(String accountId, String deviceId, long telegramId, long issuedAt, long expiresAt, long until,
                      long graceDays, Set<String> capabilities, AccountGrant account) {
            this.accountId = accountId;
            this.deviceId = deviceId;
            this.telegramId = telegramId;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.until = until;
            this.graceDays = Math.max(0L, graceDays);
            this.capabilities = Collections.unmodifiableSet(new HashSet<>(capabilities));
            this.account = account == null ? AccountGrant.ABSENT : account;
        }

        public boolean belongsTo(long telegramId, String accountId, String deviceId) {
            return telegramId > 0 && this.telegramId == telegramId
                    && this.accountId != null && this.accountId.equals(accountId)
                    && this.deviceId != null && this.deviceId.equals(deviceId);
        }

        public long hardStop() {
            long grace = graceDays > Long.MAX_VALUE / DAY ? Long.MAX_VALUE : graceDays * DAY;
            return saturatingAdd(expiresAt, grace);
        }

        /** A running genuine paid right: account_ent non-empty, now before account_until and exp + grace. */
        boolean paidAt(long now) {
            return account.present && !account.capabilities.isEmpty() && now < account.until && now < hardStop();
        }
    }

    public static final class Decision {
        public final State state;
        /** Access deadline (signed or the end of the local grace); 0 when access has none (free) or there is no access. */
        public final long until;
        /** account_until of a running genuine paid right, in either mode; 0 otherwise. Display only. */
        public final long paidUntil;
        private final Set<String> capabilities;

        private Decision(State state, long until, long paidUntil, Set<String> capabilities) {
            this.state = state;
            this.until = until;
            this.paidUntil = paidUntil;
            this.capabilities = capabilities;
        }

        public boolean grants(String capability) {
            return (state == State.FREE || state == State.ACTIVE || state == State.LOCAL_TRIAL) && capabilities.contains(capability);
        }

        /**
         * The date shown to the user ("Free until …", "Pro available until …"): the end of a running local grace,
         * or a running genuine paid period's account_until; 0 when there is none. With a local grace of 0 days there
         * is never a running grace, so nothing names a "free until" date.
         */
        public long displayedUntil() {
            return state == State.LOCAL_TRIAL ? until : paidUntil;
        }
    }

    /**
     * The access decision at {@code now}. Free mode: the shipped local capabilities for everyone, no deadline. Paid
     * mode: a running genuine grant of the account; else the local grace while it runs; else nothing, with the state
     * that says why.
     *
     * @param firstLaunch    the start of the local grace ({@link #graceStart}); 0 = no record, no grace
     * @param localGraceDays the local grace length of the last accepted signed policy
     *                       (ProMonetization.Policy.localGraceDays); 0 or less: the local grace never grants, and an
     *                       install without a genuine grant is "not active" at once under a paid policy
     */
    public static Decision evaluate(Claims claims, long now, long firstLaunch, boolean configured, Mode mode, int localGraceDays) {
        boolean paid = claims != null && claims.paidAt(now);
        long paidUntil = paid ? claims.account.until : 0;
        if (mode != Mode.PAID) return new Decision(State.FREE, 0, paidUntil, BASE);
        if (paid) return new Decision(State.ACTIVE, Math.min(claims.account.until, claims.hardStop()), paidUntil, claims.account.capabilities);
        long graceEnd = localGraceEnd(firstLaunch, localGraceDays);
        if (firstLaunch > 0 && now >= firstLaunch && now < graceEnd) {
            return new Decision(State.LOCAL_TRIAL, graceEnd, 0, BASE);
        }
        State state;
        if (!configured) state = State.UNCONFIGURED;
        else if (claims == null) state = State.LINK_REQUIRED;
        else if (!claims.account.present) state = State.RECONNECT; // Legacy token: refresh for genuine claims.
        else if (claims.account.capabilities.isEmpty() || claims.account.until <= now) state = State.EXPIRED;
        else state = State.RECONNECT;
        return new Decision(state, 0, 0, Collections.<String>emptySet());
    }

    /**
     * End of the local grace that starts at {@code start}: start + localGraceDays × 86 400 s, exclusive (the grace
     * grants while now &lt; end). 0 days (or less) is the start itself: an empty window that never grants.
     */
    public static long localGraceEnd(long start, int localGraceDays) {
        return saturatingAdd(start, Math.max(0, localGraceDays) * DAY);
    }

    /**
     * Start of the paid-mode local grace (iOS entitlement §5.1.2, D9): the LATER of the install's first
     * launch and its first adopted paid policy, so an install that was free for months still gets its
     * grace when the owner enables paid mode. firstPaid = 0 (never under a paid policy) leaves the first
     * launch. Pass the result to {@link #evaluate} and ProAccessSchedule as their firstLaunch. The start never
     * depends on the length: a policy that changes local_grace_days moves the end, never the start.
     */
    public static long graceStart(long firstLaunch, long firstPaid) {
        return firstPaid > 0 ? Math.max(firstLaunch, firstPaid) : firstLaunch;
    }

    /**
     * {@link #graceStart(long, long)} for an evaluation at {@code now}: a first-paid mark ahead of now (it was
     * minted from a clock that ran ahead and trusted time was corrected since) starts the grace at now, so the
     * grace stays open until time reaches the mark and then runs its full length. Only the paid mark is clamped: a
     * first launch after now keeps the existing rule (no grace before the install date). A length of 0 stays empty.
     */
    public static long graceStart(long firstLaunch, long firstPaid, long now) {
        if (firstPaid <= 0 || firstLaunch > now) return graceStart(firstLaunch, firstPaid);
        return Math.max(firstLaunch, Math.min(firstPaid, now));
    }

    /**
     * Whether the SERVER would accept a create of {@code capability} under this decision: free, or a genuine
     * running grant that lists it. The local grace grants on the device only, whatever its length; the server
     * knows nothing of it.
     */
    public static boolean serverGrants(Decision decision, String capability) {
        return decision != null && decision.state != State.LOCAL_TRIAL && decision.grants(capability);
    }

    /**
     * A genuine paid right that ran out: account_until is set and not ahead of now. An account that never
     * paid (account_until null) is not lapsed: its Pro screen offers, it never says "ended".
     */
    public static boolean lapsed(Claims claims, long now) {
        return claims != null && claims.account.present && claims.account.until > 0 && claims.account.until <= now;
    }

    /** Same-boot elapsedRealtime prevents clock rollback extending offline grace. */
    public static long effectiveNow(long wall, long mark, long elapsedAtMark, long elapsedNow, boolean sameBoot) {
        if (mark <= 0) return wall;
        long projected = mark;
        if (sameBoot && elapsedNow >= elapsedAtMark) {
            projected = saturatingAdd(mark, (elapsedNow - elapsedAtMark) / 1000L);
            return Math.min(Math.max(wall, projected), saturatingAdd(projected, CLOCK_TOLERANCE));
        }
        return Math.max(wall, projected);
    }

    /** Exact backend RFC 3339 UTC form yyyy-MM-ddTHH:mm:ssZ in epoch seconds, or 0. */
    public static long utc(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")) return 0;
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setLenient(false);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(value).getTime() / 1000L;
        } catch (Exception e) { return 0; }
    }

    static long saturatingAdd(long value, long increment) {
        if (increment > 0 && value > Long.MAX_VALUE - increment) return Long.MAX_VALUE;
        return value + increment;
    }

    private ProPolicy() { }
}
