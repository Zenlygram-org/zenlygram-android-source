/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pro;

/**
 * The pure decision table of {@link ZgPro} (iOS SGEntitlementEvaluator + SGEntitlementEnforcement): no Android types,
 * so the JVM tests cover every row (ZgProRulesTest). {@link ZgPro} only reads the inputs and asks here.
 */
public final class ZgProRules {

    private ZgProRules() {
    }

    /**
     * What the cloud client decided for one account from the signed monetization policy and the account's signed
     * entitlement (org.telegram.messenger.zenlygram.pro.ZenlyPro.Status, matched by name so that this class stays
     * free of the client).
     */
    public enum Entitlement {
        /** The signed policy is free: everything is open for everyone. */
        FREE,
        /** Paid policy, a genuine running grant of this account (bought on any platform, a trial, a manual grant). */
        ACTIVE,
        /**
         * Paid policy, nothing granting, inside the local grace (iOS localGrace). The client never reports it when the
         * signed policy's local grace is 0 days.
         */
        LOCAL_TRIAL,
        /** Paid policy, the account's grant is empty or behind. */
        EXPIRED,
        /** Paid policy, the saved token cannot be trusted any more until the device checks in. */
        RECONNECT,
        /** Paid policy, this account is not linked on this device. */
        LINK_REQUIRED,
        /** No backend origin or no pinned keys: nothing can be verified. */
        UNCONFIGURED;

        /** An unknown name is {@link #UNCONFIGURED}: a state this build does not know never blocks anyone. */
        public static Entitlement of(String name) {
            if (name != null) {
                for (Entitlement value : values()) {
                    if (value.name().equals(name)) {
                        return value;
                    }
                }
            }
            return UNCONFIGURED;
        }
    }

    /**
     * The access of one account.
     *
     * @param enforcing  this build can verify signed grants (the authenticated backend is on). A build that cannot
     *                   stays open under a paid policy: nobody is left at a paywall with no way out.
     * @param paidPolicy the app's stored signed policy; read only by a build that is not enforcing (an enforcing
     *                   build follows the client's own verified policy, already inside {@code entitlement}).
     * @param lapsed     a genuine paid right of this account ran out, as opposed to an account that never paid.
     */
    public static ZgPro.Access access(boolean enforcing, boolean paidPolicy, Entitlement entitlement, boolean lapsed) {
        if (!enforcing) {
            return paidPolicy ? ZgPro.Access.UNLOCKED_BUILD : ZgPro.Access.FREE_ACCESS;
        }
        switch (entitlement == null ? Entitlement.UNCONFIGURED : entitlement) {
            case FREE:
                return ZgPro.Access.FREE_ACCESS;
            case ACTIVE:
                return ZgPro.Access.ACTIVE;
            case LOCAL_TRIAL:
                return ZgPro.Access.GRACE;
            case RECONNECT:
                return ZgPro.Access.RECONNECT;
            case EXPIRED:
                return lapsed ? ZgPro.Access.LAPSED : ZgPro.Access.INACTIVE;
            case LINK_REQUIRED:
                return ZgPro.Access.INACTIVE;
            case UNCONFIGURED:
            default:
                return ZgPro.Access.UNLOCKED_BUILD;
        }
    }

    /**
     * Whether a create is allowed (iOS SGEntitlement.has + deviceWideHas).
     *
     * @param grants       per account slot: the slot is signed in and its decision grants the capability
     * @param account      the slot whose data the create writes
     * @param deviceGlobal the capability's data lives once per install: any signed-in account's grant allows it
     */
    public static boolean allowed(boolean[] grants, int account, boolean deviceGlobal) {
        if (grants == null) {
            return false;
        }
        if (account >= 0 && account < grants.length && grants[account]) {
            return true;
        }
        if (!deviceGlobal) {
            return false;
        }
        for (boolean grant : grants) {
            if (grant) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this build sends a buyer to the website's checkout (card payment on zenlygram.org): only a build that
     * was not installed from Google Play, and only while Pro is a subscription. The Play build sells through Play
     * Billing and never shows a way to pay outside it; a free policy sells nothing anywhere.
     */
    public static boolean sellsOnWebsite(boolean paidMode, boolean playBuild) {
        return paidMode && !playBuild;
    }

    /**
     * Whether the texts may name a "Free until …" date (the access line, the paragraph under it, the hub's
     * Subscription value): only inside a running local grace, and {@code until} (Unix seconds) is then its end. With a
     * local grace of 0 days the client never reports {@link Entitlement#LOCAL_TRIAL}, so the access is never
     * {@link ZgPro.Access#GRACE} and no screen says "Free until"; the account reads as one whose grace has ended.
     */
    public static boolean namesFreeUntil(ZgPro.Access access, long until) {
        return access == ZgPro.Access.GRACE && until > 0;
    }

    /**
     * Whether the one-time "Zenlygram Pro is now a subscription" notice fits this refusal (iOS takePaidModeNotice asks
     * only for the reason noToken): only an account that never paid gets it. A lapsed or unverifiable grant has its
     * own explanation on the Pro screen.
     */
    public static boolean announcesPaidMode(ZgPro.Access access) {
        return access == ZgPro.Access.INACTIVE;
    }
}
