package org.telegram.messenger.zenlygram.pro;

import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Catalog;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Offer;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Plan;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.PricingPhase;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Problem;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Purchase;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Stage;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.State;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Summary;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Plain-JVM tests of free-first access decisions: everyone is free until a verified owner-enabled
 * paid policy; paid access then comes only from genuine account_ent/account_until claims (legacy
 * ent/until never), surviving policy revisions; free mode never offers purchases, trials or
 * product queries, never counts down a local week and never unlocks AI. The paid-mode local grace has
 * the length of the last accepted signed policy of the Android line (its local_grace_days; 7 days while
 * none was accepted), applied as soon as that policy is accepted. Production code only.
 */
public final class ProFreeFirstTests {
    private static int checks;
    private static final long DAY = ProPolicy.DAY;
    /** The local grace while no policy was accepted, and of a policy that says 7: seven days. */
    private static final int WEEK = ProPolicy.DEFAULT_LOCAL_GRACE_DAYS;
    private static final long LOCAL_GRACE = WEEK * DAY;
    private static final long NOW = 1_800_000_000L;
    private static final String[] LOCAL = {"vault", "chatlock", "tasks", "edithistory"};
    private static final ProPolicy.Mode FREE = ProPolicy.Mode.FREE, PAID = ProPolicy.Mode.PAID;
    private static final String ACCOUNT = repeat("a", 64);
    private static final int PURCHASED = 1, INFINITE = 1;

    public static void main(String[] args) throws Exception {
        freeByDefault();
        freeNeverUnlocksMore();
        freeShowsOnlyGenuinePaidHistory();
        paidUsesGenuineAccountClaims();
        legacyClaimsNeverPaid();
        paidLocalFallbackAndLimits();
        paidWeekStartsAtTheFlip();
        aMarkAheadOfTheClockKeepsTheWeekOpen();
        theServerKnowsNoLocalWeek();
        localGraceLengthIsTheAcceptedPolicys();
        aNewPolicyAppliesAtOnce();
        aRefusedPolicyKeepsTheLastLength();
        lapsedIsNotNeverPaid();
        paidModeNoticeOnce();
        accountClaimParsing();
        genuineGrantAcrossPolicyRevisions();
        freeHasNoCountdown();
        screenActions();
        playFreeAccess();
        playAlreadyOpenFlip();
        playServerErrors();
        playRestoreIntent();
        System.out.println("Pro free-first: " + checks + " checks passed");
    }

    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }

    private static ProPolicy.Claims claims(long exp, long graceDays, ProPolicy.AccountGrant account, long legacyUntil, String... legacyEnt) {
        return new ProPolicy.Claims("acct", "bind", 12345L, exp - DAY, exp, legacyUntil, graceDays, set(legacyEnt), account);
    }

    private static ProPolicy.Claims genuine(long accountUntil, String... capabilities) {
        return claims(NOW + 3 * DAY, 14, ProPolicy.AccountGrant.of(set(capabilities), accountUntil), accountUntil, capabilities);
    }

    private static ProPolicy.Claims legacy(long until, String... ent) {
        return claims(NOW + 3 * DAY, 14, ProPolicy.AccountGrant.ABSENT, until, ent);
    }

    private static boolean grantsLocal(ProPolicy.Decision decision) {
        for (String capability : LOCAL) if (!decision.grants(capability)) return false;
        return true;
    }

    // ---- Free ------------------------------------------------------------------------------

    private static void freeByDefault() {
        long expiredWindow = NOW - 30 * DAY;
        ProPolicy.Decision unbound = ProPolicy.evaluate(null, NOW, expiredWindow, true, FREE, WEEK);
        check(unbound.state == ProPolicy.State.FREE && grantsLocal(unbound), "unbound install after its local week keeps every local Pro feature");
        check(grantsLocal(ProPolicy.evaluate(null, NOW, NOW - DAY, true, FREE, WEEK)), "unbound install inside its first week is free, not a trial");
        check(grantsLocal(ProPolicy.evaluate(null, NOW, 0, true, FREE, WEEK)), "missing first-launch record still free");
        check(grantsLocal(ProPolicy.evaluate(null, NOW, NOW + DAY, true, FREE, WEEK)), "clock before install date still free");
        check(grantsLocal(ProPolicy.evaluate(null, NOW, expiredWindow, false, FREE, WEEK)), "build without Pro endpoint configuration still free");
        ProPolicy.Decision expired = ProPolicy.evaluate(genuine(NOW - DAY, "vault", "tasks"), NOW, expiredWindow, true, FREE, WEEK);
        check(expired.state == ProPolicy.State.FREE && grantsLocal(expired), "expired paid subscriber is not paywalled while free");
        ProPolicy.Decision offline = ProPolicy.evaluate(claims(NOW - 30 * DAY, 0, ProPolicy.AccountGrant.ABSENT, 0), NOW, expiredWindow, true, FREE, WEEK);
        check(grantsLocal(offline), "stale offline grant past exp + grace is not paywalled while free");
        check(grantsLocal(ProPolicy.evaluate(legacy(0), NOW, expiredWindow, true, FREE, WEEK)), "legacy token without account claims still free");
    }

    private static void freeNeverUnlocksMore() {
        ProPolicy.Decision wildcard = ProPolicy.evaluate(legacy(NOW + 365 * DAY, "*"), NOW, NOW - 30 * DAY, true, FREE, WEEK);
        check(!wildcard.grants("ai"), "free never unlocks AI, even with a legacy wildcard grant");
        check(!ProPolicy.evaluate(genuine(NOW + DAY, "vault", "ai"), NOW, NOW - 30 * DAY, true, FREE, WEEK).grants("ai"),
                "free never unlocks AI from a genuine account grant either");
        check(!ProPolicy.evaluate(null, NOW, NOW - 30 * DAY, true, FREE, WEEK).grants("premium"), "free unlocks only shipped local capabilities");
    }

    /** Free status is truthful: a date appears only for a genuine, still-running paid right. */
    private static void freeShowsOnlyGenuinePaidHistory() {
        ProPolicy.Decision unbound = ProPolicy.evaluate(null, NOW, NOW - DAY, true, FREE, WEEK);
        check(unbound.until == 0 && unbound.paidUntil == 0, "free shows no trial end date or access deadline");
        check(ProPolicy.evaluate(legacy(NOW + 30 * DAY, "*"), NOW, NOW - 30 * DAY, true, FREE, WEEK).paidUntil == 0,
                "a legacy synthetic until is never presented as a paid subscription");
        ProPolicy.Decision subscriber = ProPolicy.evaluate(genuine(NOW + 30 * DAY, "vault"), NOW, NOW - 30 * DAY, true, FREE, WEEK);
        check(subscriber.paidUntil == NOW + 30 * DAY && subscriber.until == 0,
                "an existing genuine paid period stays visible while free, without a free-access deadline");
        check(ProPolicy.evaluate(genuine(NOW - 1, "vault"), NOW, NOW - 30 * DAY, true, FREE, WEEK).paidUntil == 0,
                "ended paid period is not shown as running");
    }

    // ---- Paid --------------------------------------------------------------------------------

    private static void paidUsesGenuineAccountClaims() {
        ProPolicy.Claims tasks = claims(NOW + 3 * DAY, 14, ProPolicy.AccountGrant.of(set("vault", "tasks"), NOW + 30 * DAY), NOW + 30 * DAY, "*");
        ProPolicy.Decision decision = ProPolicy.evaluate(tasks, NOW, NOW - 30 * DAY, true, PAID, WEEK);
        check(decision.state == ProPolicy.State.ACTIVE && decision.grants("tasks"), "genuine account grant is paid access");
        check(!decision.grants("chatlock"), "paid capabilities come from account_ent, never from a legacy wildcard ent");
        check(decision.paidUntil == NOW + 30 * DAY, "paid status shows the genuine account until");
        check(decision.until == NOW + 17 * DAY, "paid access deadline is the earlier of account until and exp + grace");
        check(ProPolicy.evaluate(tasks, NOW + 30 * DAY, NOW - 30 * DAY, true, PAID, WEEK).state == ProPolicy.State.EXPIRED,
                "genuine account until is final");
        ProPolicy.Claims longPaid = claims(NOW + DAY, 2, ProPolicy.AccountGrant.of(set("vault"), NOW + 365 * DAY), NOW + 365 * DAY, "vault");
        check(ProPolicy.evaluate(longPaid, NOW + 3 * DAY - 1, NOW - 30 * DAY, true, PAID, WEEK).state == ProPolicy.State.ACTIVE,
                "existing exp + grace keeps an offline paid grant");
        check(ProPolicy.evaluate(longPaid, NOW + 3 * DAY, NOW - 30 * DAY, true, PAID, WEEK).state == ProPolicy.State.RECONNECT,
                "existing exp + grace hard stop still applies to genuine grants");
        ProPolicy.Claims none = claims(NOW + 3 * DAY, 14, ProPolicy.AccountGrant.of(Collections.<String>emptySet(), 0), 0);
        check(ProPolicy.evaluate(none, NOW, NOW - 30 * DAY, true, PAID, WEEK).state == ProPolicy.State.EXPIRED,
                "account_ent [] with account_until null is genuinely no paid right");
        check(!ProMonetization.accountRefreshDue(PAID, none), "a genuine no-right answer does not loop refreshing");
    }

    private static void legacyClaimsNeverPaid() {
        ProPolicy.Claims old = legacy(NOW + 365 * DAY, "*");
        ProPolicy.Decision decision = ProPolicy.evaluate(old, NOW, NOW - 30 * DAY, true, PAID, WEEK);
        check(decision.state != ProPolicy.State.ACTIVE && !decision.grants("vault"),
                "legacy ent/until (e.g. a wildcard far horizon) is never paid access");
        check(decision.state == ProPolicy.State.RECONNECT, "legacy grant asks to refresh rather than claiming the subscription ended");
        check(ProMonetization.accountRefreshDue(PAID, old), "legacy token in paid mode requests an immediate refresh");
        check(!ProMonetization.accountRefreshDue(FREE, old), "free mode needs no account claims");
        check(!ProMonetization.accountRefreshDue(PAID, genuine(NOW + DAY, "vault")), "genuine claims need no extra refresh");
        check(!ProMonetization.accountRefreshDue(PAID, null), "an unbound install has nothing to refresh");
    }

    /** The first-week local window remains only as the existing paid-mode fallback; it consumes no server trial. */
    private static void paidLocalFallbackAndLimits() {
        long first = NOW - DAY;
        ProPolicy.Decision local = ProPolicy.evaluate(null, NOW, first, true, PAID, WEEK);
        check(local.state == ProPolicy.State.LOCAL_TRIAL && grantsLocal(local) && !local.grants("ai"), "paid mode keeps the existing local first week");
        check(ProPolicy.evaluate(null, first + LOCAL_GRACE, first, true, PAID, WEEK).state == ProPolicy.State.LINK_REQUIRED,
                "paid mode after the local week requires a genuine grant");
        check(ProPolicy.evaluate(null, NOW + 30 * DAY, first, false, PAID, WEEK).state == ProPolicy.State.UNCONFIGURED,
                "unconfigured paid build grants nothing after the week");
    }

    /**
     * The local week counts from the LATER of the first launch and the first adopted paid policy (iOS entitlement
     * §5.1.2): an install that was free for months still gets its week when the owner enables paid mode.
     */
    private static void paidWeekStartsAtTheFlip() {
        long first = NOW - 200 * DAY, flip = NOW - DAY;
        check(ProPolicy.graceStart(first, 0) == first, "never under a paid policy: the first launch");
        check(ProPolicy.graceStart(first, flip) == flip, "an old install: the week starts at the first paid policy");
        check(ProPolicy.graceStart(NOW, flip) == NOW, "an install first launched after the flip: its first launch");
        check(ProPolicy.graceStart(0, flip) == flip, "a missing first-launch record still gets the week of the flip");
        check(ProPolicy.evaluate(null, NOW, first, true, PAID, WEEK).state == ProPolicy.State.LINK_REQUIRED,
                "counted from the first launch alone the week of an old install is long over");
        ProPolicy.Decision week = ProPolicy.evaluate(null, NOW, ProPolicy.graceStart(first, flip), true, PAID, WEEK);
        check(week.state == ProPolicy.State.LOCAL_TRIAL && week.until == flip + LOCAL_GRACE && grantsLocal(week) && !week.grants("ai"),
                "counted from the flip it is open for seven days and never unlocks AI");
        check(ProPolicy.evaluate(null, flip + LOCAL_GRACE, ProPolicy.graceStart(first, flip), true, PAID, WEEK).state == ProPolicy.State.LINK_REQUIRED,
                "the week ends seven days after the flip");
        check(ProAccessSchedule.nextBoundary(null, NOW, ProPolicy.graceStart(first, flip), PAID, WEEK) == flip + LOCAL_GRACE,
                "the access timer re-evaluates at the end of that week");
        ProPolicy.Decision subscriber = ProPolicy.evaluate(genuine(NOW + 30 * DAY, "vault"), NOW, ProPolicy.graceStart(first, flip), true, PAID, WEEK);
        check(subscriber.state == ProPolicy.State.ACTIVE && !subscriber.grants("tasks"), "a genuine grant outranks the week, with its own capabilities");
        check(grantsLocal(ProPolicy.evaluate(null, NOW, ProPolicy.graceStart(first, flip), true, FREE, WEEK))
                && ProPolicy.evaluate(null, NOW, ProPolicy.graceStart(first, flip), true, FREE, WEEK).until == 0, "free mode never reads the mark");
    }

    /**
     * The first-paid mark is minted from trusted time; a validated server time may later move trusted time back
     * behind it (the clock ran ahead at the mint). The week must then stay open until time reaches the mark and run
     * its seven days from there, never be closed in between.
     */
    private static void aMarkAheadOfTheClockKeepsTheWeekOpen() {
        long first = NOW - 200 * DAY, mark = NOW + 3 * DAY;
        check(ProPolicy.graceStart(first, mark, NOW) == NOW, "a mark ahead of now starts the week at now");
        check(ProPolicy.evaluate(null, NOW, ProPolicy.graceStart(first, mark), true, PAID, WEEK).state == ProPolicy.State.LINK_REQUIRED,
                "without the clamp the week would be closed until the mark");
        ProPolicy.Decision early = ProPolicy.evaluate(null, NOW, ProPolicy.graceStart(first, mark, NOW), true, PAID, WEEK);
        check(early.state == ProPolicy.State.LOCAL_TRIAL && grantsLocal(early), "with it the week is open before the mark");
        long later = mark + DAY;
        check(ProPolicy.graceStart(first, mark, later) == mark, "once time passed the mark, the week counts from the mark");
        check(ProPolicy.evaluate(null, mark + LOCAL_GRACE - 1, ProPolicy.graceStart(first, mark, mark + LOCAL_GRACE - 1), true, PAID, WEEK).state
                == ProPolicy.State.LOCAL_TRIAL, "and is still open just before mark + 7 days");
        check(ProPolicy.evaluate(null, mark + LOCAL_GRACE, ProPolicy.graceStart(first, mark, mark + LOCAL_GRACE), true, PAID, WEEK).state
                == ProPolicy.State.LINK_REQUIRED, "and ends at mark + 7 days");
        check(ProPolicy.graceStart(first, NOW - DAY, NOW) == NOW - DAY, "a mark behind now is unchanged");
        check(ProPolicy.graceStart(first, 0, NOW) == first, "no mark: the first launch, whatever now is");
        check(ProPolicy.graceStart(NOW + DAY, NOW - DAY, NOW) == NOW + DAY, "a first launch after now keeps the old rule (no week before the install date)");
        check(ProPolicy.evaluate(null, NOW, ProPolicy.graceStart(NOW + DAY, NOW - DAY, NOW), true, PAID, WEEK).state == ProPolicy.State.LINK_REQUIRED,
                "so a clock before the install date still opens no week");
    }

    /**
     * Material created on the server (a synced Tasks board) is accepted there by a free policy or the account's own
     * genuine grant only: the local week is the device's allowance and the server knows nothing of it.
     */
    private static void theServerKnowsNoLocalWeek() {
        ProPolicy.Decision week = ProPolicy.evaluate(null, NOW, NOW - DAY, true, PAID, WEEK);
        check(week.grants("tasks") && !ProPolicy.serverGrants(week, "tasks"), "the local week grants on the device, not on the server");
        ProPolicy.Decision free = ProPolicy.evaluate(null, NOW, NOW - 30 * DAY, true, FREE, WEEK);
        check(ProPolicy.serverGrants(free, "tasks") && !ProPolicy.serverGrants(free, "ai"), "free: the server accepts the shipped capabilities");
        ProPolicy.Decision paid = ProPolicy.evaluate(genuine(NOW + 30 * DAY, "tasks"), NOW, NOW - 30 * DAY, true, PAID, WEEK);
        check(ProPolicy.serverGrants(paid, "tasks") && !ProPolicy.serverGrants(paid, "vault"), "a genuine grant: exactly what it lists");
        ProPolicy.Decision ended = ProPolicy.evaluate(genuine(NOW - DAY, "tasks"), NOW, NOW - 30 * DAY, true, PAID, WEEK);
        check(!ProPolicy.serverGrants(ended, "tasks"), "an ended grant is refused");
        ProPolicy.Decision weekOfSubscriber = ProPolicy.evaluate(genuine(NOW - DAY, "tasks"), NOW, NOW - DAY, true, PAID, WEEK);
        check(weekOfSubscriber.state == ProPolicy.State.LOCAL_TRIAL && !ProPolicy.serverGrants(weekOfSubscriber, "tasks"),
                "an ended grant inside the local week is still no grant for the server");
        check(!ProPolicy.serverGrants(null, "tasks"), "no decision, no grant");
    }

    // ---- The local grace length comes from the accepted signed policy ---------------------------

    private static KeyPair graceKey;
    private static Map<String, PublicKey> graceRing;

    private static ProMonetization.Policy fetched(String token) throws Exception {
        return ProMonetization.verifyFetched(token, graceRing, ProTestTokens.DECODER, NOW);
    }

    /** The decision exactly as ZenlyPro.decisionLocked asks for it: the accepted policy's mode and local grace length. */
    private static ProPolicy.Decision under(ProMonetization.Policy policy, ProPolicy.Claims claims, long now, long start) {
        return ProPolicy.evaluate(claims, now, start, true, policy.mode, policy.localGraceDays);
    }

    /** The deadline exactly as ZenlyPro.accessChangedLocked asks for it. */
    private static long boundary(ProMonetization.Policy policy, ProPolicy.Claims claims, long now, long start) {
        return ProAccessSchedule.nextBoundary(claims, now, start, policy.mode, policy.localGraceDays);
    }

    /**
     * L of the last accepted policy: its local_grace_days; no policy gives 7 days. The value is read from the
     * verified token alone (fetched, or the stored one on load). A policy of the general line is never accepted.
     */
    private static void localGraceLengthIsTheAcceptedPolicys() throws Exception {
        graceKey = ProTestTokens.keyPair();
        graceRing = new HashMap<>();
        graceRing.put("k2026a", graceKey.getPublic());
        long start = NOW - DAY; // The later of first launch and first adopted paid policy; never re-based below.

        check(ProMonetization.INITIAL.localGraceDays == 7, "no policy yet: 7 days (and free, so nothing counts down)");
        check(under(ProMonetization.INITIAL, null, NOW, start).state == ProPolicy.State.FREE
                && boundary(ProMonetization.INITIAL, null, NOW, start) == ProAccessSchedule.NONE, "no policy yet: free, no deadline");

        // What a server without ver 4 answers, and what the general line says to iOS: refused, the install stays free.
        for (int version = 1; version <= 3; version++) {
            boolean refused = false;
            try { ProMonetization.accept(ProMonetization.INITIAL, fetched(ProTestTokens.general(graceKey, "k2026a", version, "paid", 50, NOW, NOW + DAY))); }
            catch (ProMonetization.Rejected rejected) { refused = rejected.reason == ProMonetization.Reason.WRONG_PURPOSE; }
            check(refused, "a paid ver-" + version + " policy of the general line does not make this build paid");
        }

        ProMonetization.Policy first = ProMonetization.accept(ProMonetization.INITIAL,
                fetched(ProTestTokens.policy(graceKey, "k2026a", "paid", 1, NOW, NOW + DAY)));
        check(first.version == 4 && first.localGraceDays == 7, "the first Android policy is accepted over no policy: it says 7 days");
        ProPolicy.Decision week = under(first, null, NOW, start);
        check(week.state == ProPolicy.State.LOCAL_TRIAL && week.until == start + 7 * DAY && boundary(first, null, NOW, start) == start + 7 * DAY,
                "7 days: the grace is the seven days of before");

        ProMonetization.Policy current = first;
        long revision = 1;
        for (int days : new int[] {30, 1, 7}) {
            revision++;
            current = ProMonetization.accept(current, fetched(ProTestTokens.policyV4(graceKey, "k2026a", "paid", revision, 0, days, NOW + 2, NOW + DAY)));
            check(current.version == 4 && current.localGraceDays == days, "a policy sets " + days + " days");
            long end = start + days * DAY;
            // days = 1: start + 1 day = NOW, so the one-day grace is exactly over; the others are running.
            ProPolicy.Decision decision = under(current, null, NOW, start);
            if (days == 1) {
                check(decision.state == ProPolicy.State.LINK_REQUIRED && decision.displayedUntil() == 0 && boundary(current, null, NOW, start) == ProAccessSchedule.NONE,
                        "1 day: over exactly one day after the start");
                check(under(current, null, NOW - 1, start).state == ProPolicy.State.LOCAL_TRIAL && under(current, null, NOW - 1, start).until == end,
                        "1 day: open until its last second");
            } else {
                check(decision.state == ProPolicy.State.LOCAL_TRIAL && decision.until == end && decision.displayedUntil() == end && grantsLocal(decision),
                        days + " days: free until start + " + days + " days");
                check(boundary(current, null, NOW, start) == end, days + " days: the access timer re-evaluates at that end");
                check(under(current, null, end - 1, start).state == ProPolicy.State.LOCAL_TRIAL
                        && under(current, null, end, start).state == ProPolicy.State.LINK_REQUIRED, days + " days: closes exactly at its end");
            }
            ProMonetization.Policy stored = ProMonetization.verifyStored(current.token, graceRing, ProTestTokens.DECODER);
            check(stored.localGraceDays == days && stored.version == 4, days + " days: the stored token alone gives the length back after a restart");
        }

        // 0 days: no local grace. An install without a grant is "not active" as soon as it holds the policy.
        ProMonetization.Policy none = ProMonetization.accept(current,
                fetched(ProTestTokens.policyV4(graceKey, "k2026a", "paid", ++revision, 0, 0, NOW + 2, NOW + DAY)));
        check(none.version == 4 && none.localGraceDays == 0, "a policy sets 0 days");
        for (long now : new long[] {start, start + 1, NOW, start + 7 * DAY - 1, start + 30 * DAY}) {
            ProPolicy.Decision decision = under(none, null, now, start);
            check(decision.state == ProPolicy.State.LINK_REQUIRED && decision.until == 0 && decision.displayedUntil() == 0 && !grantsLocal(decision),
                    "0 days: not active and no free-until date at " + (now - start) + " s after the start");
            check(boundary(none, null, now, start) == ProAccessSchedule.NONE, "0 days: no deadline to wait for");
        }
        check(under(none, genuine(NOW - DAY, "vault"), NOW, start).state == ProPolicy.State.EXPIRED && ProPolicy.lapsed(genuine(NOW - DAY, "vault"), NOW),
                "0 days: an ended grant reads as ended at once, like after a grace that is over");
        ProPolicy.Decision subscriber = under(none, genuine(NOW + 20 * DAY, "vault", "tasks"), NOW, start);
        check(subscriber.state == ProPolicy.State.ACTIVE && subscriber.grants("tasks") && ProPolicy.serverGrants(subscriber, "tasks"),
                "0 days: a genuine grant is untouched");

        // The value is not zeroed in free mode; it simply has no effect there.
        ProMonetization.Policy freeWithLength = ProMonetization.accept(none,
                fetched(ProTestTokens.policyV4(graceKey, "k2026a", "free", ++revision, 0, 0, NOW + 2, NOW + DAY)));
        ProPolicy.Decision free = under(freeWithLength, null, NOW, start);
        check(freeWithLength.localGraceDays == 0 && free.state == ProPolicy.State.FREE && grantsLocal(free) && free.until == 0
                && boundary(freeWithLength, null, NOW, start) == ProAccessSchedule.NONE, "free mode with 0 days: free for everyone, no deadline");

        // Synced-board creates need a real grant at every length.
        for (int days : new int[] {1, 7, 30}) {
            ProPolicy.Decision grace = ProPolicy.evaluate(null, start, start, true, PAID, days);
            check(grace.grants("tasks") && !ProPolicy.serverGrants(grace, "tasks"), days + " days: the grace is still no grant for the server");
        }
    }

    /**
     * A newly accepted policy applies at once: the same install, the same start, re-decided under the new policy
     * (ZenlyPro.policyChangedLocked re-evaluates every slot and re-arms its timer with exactly these two calls).
     */
    private static void aNewPolicyAppliesAtOnce() throws Exception {
        long start = NOW - 3 * DAY; // Day 3 of the grace.
        ProMonetization.Policy seven = ProMonetization.accept(ProMonetization.INITIAL,
                fetched(ProTestTokens.policyV4(graceKey, "k2026a", "paid", 10, 0, 7, NOW, NOW + DAY)));
        check(under(seven, null, NOW, start).state == ProPolicy.State.LOCAL_TRIAL && under(seven, null, NOW, start).until == start + 7 * DAY
                && boundary(seven, null, NOW, start) == start + 7 * DAY, "day 3 of 7: running, free until day 7");

        ProMonetization.Policy two = ProMonetization.accept(seven, fetched(ProTestTokens.policyV4(graceKey, "k2026a", "paid", 11, 0, 2, NOW, NOW + DAY)));
        ProPolicy.Decision ended = under(two, null, NOW, start);
        check(ended.state == ProPolicy.State.LINK_REQUIRED && !grantsLocal(ended) && ended.displayedUntil() == 0
                && boundary(two, null, NOW, start) == ProAccessSchedule.NONE, "7 to 2 on day 3: the running grace ends at once");

        ProMonetization.Policy zero = ProMonetization.accept(two, fetched(ProTestTokens.policyV4(graceKey, "k2026a", "paid", 12, 0, 0, NOW, NOW + DAY)));
        check(under(zero, null, NOW, start).state == ProPolicy.State.LINK_REQUIRED && boundary(zero, null, NOW, start) == ProAccessSchedule.NONE,
                "to 0: no grace");

        ProMonetization.Policy thirty = ProMonetization.accept(zero, fetched(ProTestTokens.policyV4(graceKey, "k2026a", "paid", 13, 0, 30, NOW, NOW + DAY)));
        ProPolicy.Decision revived = under(thirty, null, NOW, start);
        check(revived.state == ProPolicy.State.LOCAL_TRIAL && grantsLocal(revived) && revived.until == start + 30 * DAY
                && boundary(thirty, null, NOW, start) == start + 30 * DAY, "0 to 30 on day 3: the ended grace is revived, from the SAME start");

        ProMonetization.Policy four = ProMonetization.accept(thirty, fetched(ProTestTokens.policyV4(graceKey, "k2026a", "paid", 14, 0, 4, NOW, NOW + DAY)));
        check(under(four, null, NOW, start).state == ProPolicy.State.LOCAL_TRIAL && under(four, null, NOW, start).until == start + 4 * DAY
                && boundary(four, null, NOW, start) == start + 4 * DAY, "30 to 4 on day 3: still running, the date and the deadline move to day 4");

        // A server rolled back to one that does not know ver 4 answers ver 1 (the general line): refused, so the
        // policy this build holds, and with it the mode and the length, stays until the next Android policy.
        ProMonetization.Policy afterRollback = four;
        boolean refusedRollback = false;
        try { afterRollback = ProMonetization.accept(four, fetched(ProTestTokens.general(graceKey, "k2026a", 1, "free", 99, NOW + 5, NOW + DAY))); }
        catch (ProMonetization.Rejected rejected) { refusedRollback = rejected.reason == ProMonetization.Reason.WRONG_PURPOSE; }
        check(refusedRollback && afterRollback == four && under(afterRollback, null, NOW, start).until == start + 4 * DAY,
                "the answer of a server without ver 4 changes nothing: still paid, still 4 days");
        ProMonetization.Policy again = ProMonetization.accept(four, fetched(ProTestTokens.policyV4(graceKey, "k2026a", "paid", 14, 0, 9, NOW + 6, NOW + DAY)));
        check(again.localGraceDays == 9 && under(again, null, NOW, start).until == start + 9 * DAY, "the next Android policy sets its length again");

        // A genuine grant outranks the grace at every length and is not moved by a change of it.
        ProPolicy.Claims subscriber = genuine(NOW + 20 * DAY, "vault");
        for (ProMonetization.Policy policy : new ProMonetization.Policy[] {seven, two, zero, thirty, four}) {
            ProPolicy.Decision decision = under(policy, subscriber, NOW, start);
            check(decision.state == ProPolicy.State.ACTIVE && decision.displayedUntil() == NOW + 20 * DAY,
                    "a genuine grant is the same under a " + policy.localGraceDays + "-day policy");
        }
    }

    /**
     * A token with a bad local_grace_days is refused as a whole (like a bad trial_days), so nothing is
     * accepted from it: the policy in effect, and with it the length, stays.
     */
    private static void aRefusedPolicyKeepsTheLastLength() throws Exception {
        long start = NOW - 3 * DAY;
        ProMonetization.Policy accepted = ProMonetization.accept(ProMonetization.INITIAL,
                fetched(ProTestTokens.policyV4(graceKey, "k2026a", "paid", 20, 0, 30, NOW, NOW + DAY)));
        Object[] bad = {31L, -1L, 7.0, "7", Boolean.TRUE, null};
        for (Object value : bad) {
            Map<String, Object> claims = ProTestTokens.policyClaimsV4("paid", 21, 0, 0, NOW + 1, NOW + DAY);
            claims.put("local_grace_days", value);
            String token = ProTestTokens.sign(ProTestTokens.header("k2026a"), claims, graceKey.getPrivate());
            ProMonetization.Policy after = accepted;
            boolean refused = false;
            try { after = ProMonetization.accept(accepted, fetched(token)); }
            catch (ProMonetization.Rejected rejected) { refused = rejected.reason == ProMonetization.Reason.MALFORMED; }
            check(refused && after == accepted, "local_grace_days " + value + ": the newer-revision token is refused");
            check(under(after, null, NOW, start).until == start + 30 * DAY, "local_grace_days " + value + ": the accepted 30 days stay");
        }
        Map<String, Object> missing = ProTestTokens.policyClaimsV4("paid", 21, 0, 0, NOW + 1, NOW + DAY);
        missing.remove("local_grace_days");
        boolean refused = false;
        try { fetched(ProTestTokens.sign(ProTestTokens.header("k2026a"), missing, graceKey.getPrivate())); }
        catch (ProMonetization.Rejected rejected) { refused = rejected.reason == ProMonetization.Reason.MALFORMED; }
        check(refused, "a token without local_grace_days is refused, never read as 7 or as 0");
    }

    /** "Subscription ended" is only for a genuine paid right that ran out, never for an account that never paid. */
    private static void lapsedIsNotNeverPaid() {
        check(!ProPolicy.lapsed(null, NOW), "an unbound install never paid");
        check(!ProPolicy.lapsed(legacy(NOW - DAY, "*"), NOW), "a legacy token proves no paid right");
        ProPolicy.Claims never = claims(NOW + 3 * DAY, 14, ProPolicy.AccountGrant.of(set(), 0), 0);
        check(!ProPolicy.lapsed(never, NOW) && ProPolicy.evaluate(never, NOW, NOW - 30 * DAY, true, PAID, WEEK).state == ProPolicy.State.EXPIRED,
                "a linked account that never paid has nothing granting, and is not lapsed");
        check(ProPolicy.lapsed(genuine(NOW - 1, "vault"), NOW) && ProPolicy.lapsed(genuine(NOW, "vault"), NOW), "a paid period behind now is lapsed");
        check(!ProPolicy.lapsed(genuine(NOW + 1, "vault"), NOW), "a running paid period is not");
    }

    /** The one-time "Pro is now a subscription" notice is owed by a free to paid flip only (iOS takePaidModeNotice). */
    private static void paidModeNoticeOnce() {
        ProMonetization.Policy free1 = new ProMonetization.Policy(FREE, 1, NOW, NOW + DAY, "t1");
        ProMonetization.Policy paid2 = new ProMonetization.Policy(PAID, 2, NOW, NOW + DAY, "t2");
        ProMonetization.Policy paid3 = new ProMonetization.Policy(PAID, 3, NOW, NOW + DAY, "t3");
        ProMonetization.Policy free4 = new ProMonetization.Policy(FREE, 4, NOW, NOW + DAY, "t4");
        ProMonetization.Policy paid5 = new ProMonetization.Policy(PAID, 5, NOW, NOW + DAY, "t5");
        check(!ProMonetization.noticeOwed(ProMonetization.INITIAL, free1, false), "nothing to announce while free");
        check(ProMonetization.noticeOwed(free1, paid2, false), "an adopted free policy gave way to a paid one");
        check(ProMonetization.noticeOwed(paid2, paid3, true), "a paid to paid revision does not swallow a notice still owed");
        check(!ProMonetization.noticeOwed(paid2, paid3, false), "nor does it repeat one already shown");
        check(!ProMonetization.noticeOwed(paid3, free4, true), "free again: nothing blocks, nothing to announce");
        check(ProMonetization.noticeOwed(free4, paid5, false), "a second free to paid flip is a new flip");
        check(!ProMonetization.noticeOwed(ProMonetization.INITIAL, paid2, false) && !ProMonetization.noticeOwed(ProMonetization.INITIAL, paid2, true),
                "the first policy an install adopts is already paid: no flip reached it");
    }

    // ---- Account claims ----------------------------------------------------------------------

    private static void accountClaimParsing() {
        String until = "2027-01-15T08:30:00Z";
        ProPolicy.AccountGrant grant = parse("account_ent", list("vault", "tasks"), "account_until", until);
        check(grant.present && grant.capabilities.equals(set("vault", "tasks")) && grant.until == 1_800_001_800L,
                "genuine account claims parse with exact RFC 3339 UTC until");
        check(!parse().present, "legacy token without account claims has no account grant");
        check(!parse("account_ent", list("vault")).present, "account_ent without account_until is not a grant");
        check(!parse("account_until", until).present, "account_until without account_ent is not a grant");
        check(!parse("account_ent", list("*"), "account_until", until).present, "wildcard never appears in account_ent");
        check(!parse("account_ent", list("vault", "vault"), "account_until", until).present, "duplicate items rejected");
        check(!parse("account_ent", list("premium"), "account_until", until).present, "unknown capability rejected");
        check(!parse("account_ent", "vault", "account_until", until).present, "non-array account_ent rejected");
        check(!parse("account_ent", Collections.singletonList(1L), "account_until", until).present, "non-string item rejected");
        check(!parse("account_ent", list("vault"), "account_until", 1_800_001_800L).present, "numeric account_until rejected");
        check(!parse("account_ent", list("vault"), "account_until", "2027-01-15T13:30:00+05:00").present, "offset timestamp rejected");
        check(!parse("account_ent", list("vault"), "account_until", "2027-01-15").present, "date-only until rejected");
        check(!parse("account_ent", null, "account_until", until).present, "null account_ent rejected");
        ProPolicy.AccountGrant none = parse("account_ent", list(), "account_until", null);
        check(none.present && none.capabilities.isEmpty() && none.until == 0, "genuine no-right claims parse as present");
        ProPolicy.AccountGrant ai = parse("account_ent", list("ai"), "account_until", until);
        check(ai.present && ai.capabilities.contains("ai"), "contract capability enum is accepted as signed");
        boolean rejected = false;
        try { ProPolicy.AccountGrant.of(set("*"), NOW); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "an account grant can never be constructed with a wildcard");
    }

    private static java.util.List<Object> list(Object... values) { return Arrays.asList(values); }

    /** An entitlement payload as ZenlyPro's org.json adapter normalizes it, with legacy ent/until always present. */
    private static ProPolicy.AccountGrant parse(Object... accountClaims) {
        Map<String, Object> payload = ProTestTokens.map("iss", "zenlygram", "sub", "acct", "ent", list("*"), "until", "2036-01-01T00:00:00Z");
        payload.putAll(ProTestTokens.map(accountClaims));
        return ProPolicy.AccountGrant.parse(payload);
    }

    /** Paid rights are account facts, independent of the policy revision that happens to be current. */
    private static void genuineGrantAcrossPolicyRevisions() throws Exception {
        KeyPair key = ProTestTokens.keyPair();
        Map<String, PublicKey> ring = new HashMap<>();
        ring.put("k2026a", key.getPublic());
        ProPolicy.Claims subscriber = genuine(NOW + 20 * DAY, "vault", "chatlock", "tasks", "edithistory");
        ProPolicy.Claims lapsed = genuine(NOW - DAY, "vault");
        long first = NOW - 30 * DAY;
        ProMonetization.Policy policy = ProMonetization.INITIAL;
        String[][] steps = {{"free", "0"}, {"paid", "1"}, {"paid", "2"}, {"free", "3"}, {"paid", "4"}};
        for (int index = 0; index < steps.length; index++) {
            String token = ProTestTokens.policy(key, "k2026a", steps[index][0], Long.parseLong(steps[index][1]), NOW + index, NOW + DAY);
            policy = ProMonetization.accept(policy, ProMonetization.verifyFetched(token, ring, ProTestTokens.DECODER, NOW));
            ProPolicy.Decision paid = ProPolicy.evaluate(subscriber, NOW + index, first, true, policy.mode, policy.localGraceDays);
            check(grantsLocal(paid), "genuine subscriber keeps access at policy step " + index);
            ProPolicy.Decision other = ProPolicy.evaluate(lapsed, NOW + index, first, true, policy.mode, policy.localGraceDays);
            boolean free = policy.mode == FREE;
            check(grantsLocal(other) == free, "lapsed account is free only while the policy is free (step " + index + ")");
        }
        check(ProPolicy.evaluate(subscriber, NOW, first, true, PAID, WEEK).paidUntil == NOW + 20 * DAY, "paid horizon unchanged by policy changes");
    }

    private static void freeHasNoCountdown() {
        long first = NOW - DAY;
        check(ProAccessSchedule.nextBoundary(null, NOW, first, FREE, WEEK) == ProAccessSchedule.NONE,
                "free schedules no first-week deadline (no automatic paywall after 7 days)");
        check(ProPolicy.evaluate(null, first + LOCAL_GRACE + 1, first, true, FREE, WEEK).state == ProPolicy.State.FREE,
                "still free after the local week elapses");
        check(ProAccessSchedule.nextBoundary(genuine(NOW + 5 * DAY, "vault"), NOW, first, FREE, WEEK) == NOW + 5 * DAY,
                "free re-evaluates only when a genuine paid period shown on screen ends");
        check(ProAccessSchedule.nextBoundary(legacy(NOW + 5 * DAY, "*"), NOW, first - 30 * DAY, PAID, WEEK) == ProAccessSchedule.NONE,
                "paid legacy grant never schedules on its synthetic until or exp");
        check(ProAccessSchedule.nextBoundary(genuine(NOW + 5 * DAY, "vault"), NOW, first - 30 * DAY, PAID, WEEK) == NOW + 5 * DAY,
                "paid schedules on the genuine account until");
    }

    // ---- Pro screen actions -------------------------------------------------------------------

    private static void screenActions() {
        ProActions unbound = ProActions.of(false, ProPolicy.State.FREE, FREE);
        check(unbound.link && !unbound.trial && !unbound.refresh && unbound.main == ProActions.Action.LINK,
                "free unbound account can still link, but no trial is offered");
        ProActions bound = ProActions.of(true, ProPolicy.State.FREE, FREE);
        check(!bound.link && !bound.trial && bound.refresh && bound.main == ProActions.Action.REFRESH,
                "free linked account can refresh/restore; no trial or upgrade action");
        ProActions expired = ProActions.of(true, ProPolicy.State.EXPIRED, PAID);
        check(expired.trial && expired.main == ProActions.Action.TRIAL && expired.refresh, "paid mode keeps the existing trial action");
        ProActions active = ProActions.of(true, ProPolicy.State.ACTIVE, PAID);
        check(!active.trial && !active.link && active.refresh, "paid active account only refreshes");
        ProActions reconnect = ProActions.of(true, ProPolicy.State.RECONNECT, PAID);
        check(reconnect.link && reconnect.main == ProActions.Action.LINK, "paid reconnect keeps linking available");
        check(!ProActions.of(false, ProPolicy.State.LINK_REQUIRED, PAID).trial, "unlinked paid account links before any trial");
    }

    // ---- Google Play ---------------------------------------------------------------------------

    private static Offer monthly() {
        return new Offer("fixture.pro", "monthly", null, "t",
                Collections.singletonList(new PricingPhase("$4.99", 4990000, "P1M", 0, INFINITE)));
    }

    private static Summary summary(Purchase... purchases) {
        return Summary.of(Arrays.asList(purchases), new Catalog(ACCOUNT, Collections.singletonList(new Plan("fixture.pro", "monthly"))));
    }

    private static void playFreeAccess() {
        State loading = State.initial(true, true, true, true).freeAccess();
        check(loading.free && loading.stage == Stage.LOADING && !loading.canPurchase(), "free checkout loads restore context only");
        State ready = loading.offersLoaded(Collections.singletonList(monthly())).purchasesLoaded(summary());
        check(ready.offers.isEmpty() && ready.purchasableOffers().isEmpty(), "free never keeps purchasable offers");
        check(ready.stage == Stage.READY && ready.problem == Problem.NONE, "free with no offers is not a 'no offers' failure");
        check(!ready.canPurchase() && ready.purchasing() == ready, "free never starts a Play purchase");
        check(ready.canRestore(), "Restore stays available while free");
        check(!ready.canRetry(), "nothing to retry for a healthy free store");
        State owned = loading.offersLoaded(Collections.<Offer>emptyList())
                .purchasesLoaded(summary(new Purchase("tok", Collections.singletonList("fixture.pro"), PURCHASED, ACCOUNT, true)));
        check(owned.manage() == GooglePlayModel.Manage.SUBSCRIPTION, "an existing Play subscription stays manageable/cancellable while free");
        check(owned.restoring().stage == Stage.VERIFYING, "free Restore re-verifies existing purchases");
        State unlinked = State.initial(true, true, true, false).freeAccess();
        check(unlinked.stage == Stage.LINK_REQUIRED && unlinked.manage() == GooglePlayModel.Manage.GENERIC,
                "free unlinked screen keeps linking and the generic subscription center");
        check(State.initial(false, true, true, true).freeAccess().stage == Stage.DISABLED, "Direct APK stays disabled");
    }

    /** A screen opened while paid must stop selling the moment the verified policy turns free. */
    private static void playAlreadyOpenFlip() {
        State paid = State.initial(true, true, true, true).offersLoaded(Collections.singletonList(monthly())).purchasesLoaded(summary());
        check(paid.canPurchase(), "paid screen offers a subscription");
        State flipped = paid.freeAccess();
        check(flipped.free && !flipped.canPurchase() && flipped.purchasing() == flipped,
                "a stale Subscribe tap after the switch to free cannot start a purchase");
        check(flipped.canRestore(), "Restore survives the switch to free");
        State inSheet = paid.purchasing().freeAccess();
        check(inSheet.inFlight() && inSheet.onPurchaseFlow(Problem.NONE) == GooglePlayModel.FlowStep.REQUERY,
                "a purchase already in Play's sheet is still attached (already-paid fulfillment)");
        State after = inSheet.requerying().purchasesLoaded(summary());
        check(after.free && !after.canPurchase() && after.offers.isEmpty(), "after that purchase the free screen sells nothing");
        check(flipped.freeAccess().free && !flipped.freeAccess().canPurchase(), "free transition is idempotent");
    }

    private static void playServerErrors() {
        check(Problem.fromServer("monetization_disabled", 409) == Problem.MONETIZATION_DISABLED, "409 monetization_disabled mapped");
        check(Problem.fromServer("nothing_to_restore", 409) == Problem.NOTHING_TO_RESTORE, "409 nothing_to_restore mapped");
        State refused = State.initial(true, true, true, true).failed(Problem.MONETIZATION_DISABLED);
        check(!refused.canPurchase() && refused.problem == Problem.MONETIZATION_DISABLED, "server refusal never leaves a purchasable screen");
        check(!refused.canRetry(), "a refused new-purchase prepare is not retried (no loop); the policy refresh recovers it");
        check(refused.canRestore(), "Restore stays reachable after a new-purchase prepare 409");
        check(refused.manage() == GooglePlayModel.Manage.GENERIC, "subscription management stays reachable after a refusal");
        State recovered = refused.freeAccess();
        check(recovered.free && !recovered.canRetry() && recovered.canRestore() && !recovered.canPurchase(),
                "paid-to-free after a refusal: free, Restore offered, no retry loop and nothing purchasable");
        State outage = State.initial(true, true, true, true).failed(Problem.STORE_UNAVAILABLE);
        check(outage.canRestore() && outage.canRetry(), "Restore stays reachable after a new-purchase prepare 503");
        check(!State.initial(true, true, true, true).failed(Problem.BILLING_UNSUPPORTED).canRestore(),
                "billing unsupported cannot query Play, so no Restore");
        check(!State.initial(true, false, true, true).canRestore(), "unconfigured build offers no Restore");
        State nothing = State.initial(true, true, true, true).freeAccess().failed(Problem.NOTHING_TO_RESTORE);
        check(!nothing.canPurchase() && nothing.manage() == GooglePlayModel.Manage.GENERIC,
                "no server binding: nothing to restore, generic management still offered");
        check(nothing.canRestore(), "Restore stays offered without a server binding, never hidden for a missing local cache");
    }

    /**
     * Restore always fetches a fresh server restore context ({"purpose":"restore"}) before verifying,
     * in either mode, never the purchase binding or catalog the screen already holds.
     */
    private static void playRestoreIntent() {
        State refused = State.initial(true, true, true, true).failed(Problem.MONETIZATION_DISABLED);
        State restoring = refused.restoring();
        check(restoring.stage == Stage.VERIFYING && !restoring.storeReady && restoring.holdsAccountCommands(),
                "Restore without a connected store is an explicit restore in progress, waiting for its fresh context");
        check(restoring.restoring() == restoring, "a repeated Restore tap starts nothing new");
        check(!restoring.canPurchase() && restoring.purchasing() == restoring, "nothing is bought while restoring");
        State ready = restoring.restoreContextReady();
        check(ready.stage == Stage.VERIFYING && ready.storeReady, "the fresh restore context keeps the restore intent");
        Purchase acknowledged = new Purchase("tok", Collections.singletonList("fixture.pro"), PURCHASED, ACCOUNT, true);
        Catalog historical = new Catalog(ACCOUNT, Collections.singletonList(new Plan("fixture.pro", "monthly")));
        check(GooglePlayModel.toAttach(Collections.singletonList(acknowledged), historical, true, Collections.<String>emptySet()).size() == 1,
                "restore re-sends acknowledged owned purchases");
        check(GooglePlayModel.toAttach(Collections.singletonList(acknowledged), historical, false, Collections.<String>emptySet()).isEmpty(),
                "a normal load does not re-send acknowledged purchases");
        State listed = ready.purchasesLoaded(summary(acknowledged));
        check(listed.stage == Stage.VERIFYING && listed.purchasesKnown, "the purchase list does not end the restore before attach");
        State done = listed.attached(GooglePlayModel.SubscriptionState.ACTIVE);
        check(!done.inFlight() && done.confirmed == GooglePlayModel.SubscriptionState.ACTIVE && done.canRestore(),
                "an attached restore settles with Restore still available");
        State failed = restoring.failed(Problem.NOTHING_TO_RESTORE);
        check(failed.stage == Stage.UNAVAILABLE && failed.canRestore() && !failed.inFlight(),
                "a restore without a server binding ends without a loop and can be tried again");
        State paid = State.initial(true, true, true, true).offersLoaded(Collections.singletonList(monthly())).purchasesLoaded(summary());
        State fresh = paid.restoring();
        check(fresh.stage == Stage.VERIFYING && fresh.restoreContextReady().stage == Stage.VERIFYING,
                "a paid-store Restore also waits in VERIFYING for its fresh restore context");
        check(fresh.restoreContextReady().purchasesLoaded(summary()).settle(null).canPurchase(),
                "after restoring nothing, the paid screen sells again");
        check(paid.restoreContextReady() == paid, "a stale restore context outside a restore changes nothing");
    }

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
