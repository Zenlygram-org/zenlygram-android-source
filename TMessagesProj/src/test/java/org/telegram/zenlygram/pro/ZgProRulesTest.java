package org.telegram.zenlygram.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.messenger.zenlygram.pro.ProPolicy;
import org.telegram.zenlygram.pro.ZgPro.Access;
import org.telegram.zenlygram.pro.ZgProRules.Entitlement;

/** JVM tests of the Pro decision table (iOS SGEntitlementEvaluator + SGEntitlementEnforcement). */
public class ZgProRulesTest {

    @Test
    public void aBuildThatCannotVerifyAGrantNeverBlocks() {
        for (Entitlement entitlement : Entitlement.values()) {
            assertEquals(Access.FREE_ACCESS, ZgProRules.access(false, false, entitlement, false));
            assertEquals(Access.UNLOCKED_BUILD, ZgProRules.access(false, true, entitlement, true));
        }
        assertEquals(Access.UNLOCKED_BUILD, ZgProRules.access(false, true, null, false));
        assertTrue(Access.UNLOCKED_BUILD.active);
        assertTrue(Access.FREE_ACCESS.active);
    }

    @Test
    public void anEnforcingBuildFollowsTheSignedDecision() {
        assertEquals(Access.FREE_ACCESS, ZgProRules.access(true, false, Entitlement.FREE, false));
        // The app's own copy of the policy does not decide in an enforcing build: the client's verified one does.
        assertEquals(Access.FREE_ACCESS, ZgProRules.access(true, true, Entitlement.FREE, false));
        assertEquals(Access.ACTIVE, ZgProRules.access(true, false, Entitlement.ACTIVE, false));
        assertEquals(Access.GRACE, ZgProRules.access(true, false, Entitlement.LOCAL_TRIAL, false));
        assertEquals(Access.RECONNECT, ZgProRules.access(true, false, Entitlement.RECONNECT, false));
        assertEquals(Access.INACTIVE, ZgProRules.access(true, false, Entitlement.LINK_REQUIRED, false));
        assertTrue(Access.ACTIVE.active);
        assertTrue(Access.GRACE.active);
        assertFalse(Access.INACTIVE.active);
        assertFalse(Access.LAPSED.active);
        assertFalse(Access.RECONNECT.active);
    }

    @Test
    public void endedIsOnlyForAGrantThatRanOut() {
        assertEquals(Access.LAPSED, ZgProRules.access(true, false, Entitlement.EXPIRED, true));
        // A linked account that never paid has nothing granting: the offer, never "subscription ended".
        assertEquals(Access.INACTIVE, ZgProRules.access(true, false, Entitlement.EXPIRED, false));
        assertEquals(Access.INACTIVE, ZgProRules.access(true, false, Entitlement.LINK_REQUIRED, true));
    }

    @Test
    public void nothingConfiguredOrUnknownStaysOpen() {
        assertEquals(Access.UNLOCKED_BUILD, ZgProRules.access(true, true, Entitlement.UNCONFIGURED, false));
        assertEquals(Access.UNLOCKED_BUILD, ZgProRules.access(true, true, null, false));
        assertEquals(Entitlement.UNCONFIGURED, Entitlement.of("A_STATE_OF_A_LATER_CLIENT"));
        assertEquals(Entitlement.UNCONFIGURED, Entitlement.of(null));
        for (Entitlement entitlement : Entitlement.values()) {
            assertEquals(entitlement, Entitlement.of(entitlement.name()));
        }
    }

    @Test
    public void theVaultIsPerAccount() {
        final boolean[] grants = {false, true, false, false};
        assertFalse(ZgProRules.allowed(grants, 0, false));
        assertTrue(ZgProRules.allowed(grants, 1, false));
        assertFalse(ZgProRules.allowed(grants, 2, false));
    }

    @Test
    public void deviceGlobalCapabilitiesFollowAnySignedInAccount() {
        final boolean[] grants = {false, true, false, false};
        assertTrue(ZgProRules.allowed(grants, 0, true));
        assertTrue(ZgProRules.allowed(grants, 1, true));
        assertTrue(ZgProRules.allowed(grants, 3, true));
        assertFalse(ZgProRules.allowed(new boolean[4], 0, true));
    }

    @Test
    public void anAccountOutsideTheSlotsHasNoGrantOfItsOwn() {
        final boolean[] grants = {true, false};
        assertFalse(ZgProRules.allowed(grants, -1, false));
        assertFalse(ZgProRules.allowed(grants, 2, false));
        assertTrue(ZgProRules.allowed(grants, 2, true));
        assertFalse(ZgProRules.allowed(null, 0, true));
    }

    // ---- The local grace at the signed policy's length (local_grace_days), from the client's decision to the texts ----

    private static final long DAY = ProPolicy.DAY;
    private static final long START = 1_800_000_000L;

    /** What ZgPro.getAccess makes of the client's decision for an account without a grant, under a paid policy. */
    private static Access accessOf(ProPolicy.Decision decision, boolean lapsed) {
        return ZgProRules.access(true, false, Entitlement.of(decision.state.name()), lapsed);
    }

    private static ProPolicy.Decision paid(long now, int localGraceDays) {
        return ProPolicy.evaluate(null, now, START, true, ProPolicy.Mode.PAID, localGraceDays);
    }

    @Test
    public void freeUntilIsNamedOnlyInsideARunningLocalGrace() {
        assertTrue(ZgProRules.namesFreeUntil(Access.GRACE, START + 7 * DAY));
        assertFalse(ZgProRules.namesFreeUntil(Access.GRACE, 0));
        for (Access access : Access.values()) {
            if (access != Access.GRACE) {
                assertFalse(access.name(), ZgProRules.namesFreeUntil(access, START + 7 * DAY));
                assertFalse(access.name(), ZgProRules.namesFreeUntil(access, 0));
            }
        }
    }

    @Test
    public void theFreeUntilDateFollowsTheLengthOfTheSignedPolicy() {
        for (int days : new int[]{1, 7, 30}) {
            final long end = START + days * DAY;
            for (long now : new long[]{START, START + 1, end - 1}) {
                final ProPolicy.Decision decision = paid(now, days);
                assertEquals(Access.GRACE, accessOf(decision, false));
                assertEquals(end, decision.displayedUntil());
                assertTrue(ZgProRules.namesFreeUntil(accessOf(decision, false), decision.displayedUntil()));
            }
            // Over: the offer, no date, exactly at the end.
            final ProPolicy.Decision over = paid(end, days);
            assertEquals(Access.INACTIVE, accessOf(over, false));
            assertEquals(0, over.displayedUntil());
            assertFalse(ZgProRules.namesFreeUntil(accessOf(over, false), over.displayedUntil()));
        }
    }

    @Test
    public void aLocalGraceOfZeroDaysNeverSaysFreeUntil() {
        for (long now : new long[]{START, START + 1, START + DAY, START + 7 * DAY - 1, START + 7 * DAY, START + 30 * DAY}) {
            final ProPolicy.Decision decision = paid(now, 0);
            final Access access = accessOf(decision, false);
            assertEquals(Access.INACTIVE, access);
            assertFalse(access.active);
            assertEquals(0, decision.displayedUntil());
            assertFalse(ZgProRules.namesFreeUntil(access, decision.displayedUntil()));
            // The gates and the Pro screen see exactly what an install whose 7-day grace has ended sees.
            final ProPolicy.Decision ended = paid(START + 7 * DAY + (now - START), 7);
            assertEquals(accessOf(ended, false), access);
            assertEquals(ended.state, decision.state);
            assertEquals(ended.displayedUntil(), decision.displayedUntil());
            for (String capability : new String[]{"vault", "chatlock", "tasks", "edithistory"}) {
                assertFalse(capability, decision.grants(capability));
                assertFalse(capability, ProPolicy.serverGrants(decision, capability));
            }
            // And the one-time "Pro is now a subscription" notice fits it, as for any account that never paid.
            assertTrue(ZgProRules.announcesPaidMode(access));
        }
    }

    @Test
    public void aChangedLengthChangesTheAccessOfARunningGraceBothWays() {
        final long day3 = START + 3 * DAY;
        assertEquals(Access.GRACE, accessOf(paid(day3, 7), false));
        assertEquals(Access.INACTIVE, accessOf(paid(day3, 2), false)); // shorter: the running grace is over at once
        assertEquals(Access.INACTIVE, accessOf(paid(day3, 0), false));
        assertEquals(Access.GRACE, accessOf(paid(day3, 30), false));
        assertEquals(START + 30 * DAY, paid(day3, 30).displayedUntil());
        final long day10 = START + 10 * DAY;
        assertEquals(Access.INACTIVE, accessOf(paid(day10, 7), false));
        assertEquals(Access.GRACE, accessOf(paid(day10, 30), false)); // longer: an ended grace is open again
        assertTrue(ZgProRules.namesFreeUntil(accessOf(paid(day10, 30), false), paid(day10, 30).displayedUntil()));
    }

    @Test
    public void onlyASideLoadBuildSendsABuyerToTheWebsite() {
        // The side-load build has no Play Billing: while Pro is a subscription it opens the website's checkout.
        assertTrue(ZgProRules.sellsOnWebsite(true, false));
        // The Google Play build sells through Play Billing only and never shows a way to pay outside it.
        assertFalse(ZgProRules.sellsOnWebsite(true, true));
        // A free policy sells nothing anywhere.
        assertFalse(ZgProRules.sellsOnWebsite(false, false));
        assertFalse(ZgProRules.sellsOnWebsite(false, true));
    }

    @Test
    public void thePaidModeNoticeIsForAnAccountThatNeverPaid() {
        assertTrue(ZgProRules.announcesPaidMode(Access.INACTIVE));
        for (Access access : Access.values()) {
            if (access != Access.INACTIVE) {
                assertFalse(access.name(), ZgProRules.announcesPaidMode(access));
            }
        }
    }
}
