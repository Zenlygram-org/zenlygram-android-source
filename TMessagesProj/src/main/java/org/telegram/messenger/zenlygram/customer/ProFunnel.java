package org.telegram.messenger.zenlygram.customer;

/**
 * The Pro funnel's mappings (design 50 §11.4), pure and host-tested: where the Pro screen was opened from, why a Play
 * purchase failed (from the {@code GooglePlayModel.Problem} NAME, so the customer host runner needs no Pro sources),
 * how a restore or a trial ended. The Android side resolves the previous fragment to an {@link Origin} with
 * {@code instanceof} (release builds rename UI classes, so class names are never compared).
 */
public final class ProFunnel {
    /**
     * The problem name {@code GooglePlayCheckout} passes for a Play purchase that is pending (Play's PENDING purchase
     * state; there is no {@code Problem} constant for it).
     */
    public static final String PENDING = "PENDING";

    private ProFunnel() {}

    /** The screen the Pro screen was opened from. */
    public enum Origin {
        /** ZenlySettingsActivity or Telegram's SettingsActivity. */
        SETTINGS,
        /** ZenlyTasksActivity or ZenlyCloudTasksActivity. */
        TASKS,
        /** A screen whose feature needs Pro: ZenlyChatLockActivity, ZenlyHistoryActivity or a chat (ChatActivity). */
        FEATURE_GATE,
        /** Anything else, or unknown. */
        OTHER
    }

    /** {@code pro.screen_view.source} of the previous screen. */
    public static CustomerEvents.ProSource sourceOf(Origin previous) {
        if (previous == null) return CustomerEvents.ProSource.OTHER;
        switch (previous) {
            case SETTINGS: return CustomerEvents.ProSource.SETTINGS;
            case TASKS: return CustomerEvents.ProSource.TASKS;
            case FEATURE_GATE: return CustomerEvents.ProSource.FEATURE_GATE;
            default: return CustomerEvents.ProSource.OTHER;
        }
    }

    /** {@code pro.screen_view.mode}: paid when purchases are offered ({@code snapshot().purchasesAvailable}), else free. */
    public static CustomerEvents.ProMode mode(boolean purchasesAvailable) {
        return purchasesAvailable ? CustomerEvents.ProMode.PAID : CustomerEvents.ProMode.FREE;
    }

    /**
     * {@code pro.purchase_fail.reason} of a {@code GooglePlayModel.Problem} name: USER_CANCELED → cancelled;
     * BILLING_UNSUPPORTED, NOT_CONFIGURED, STORE_UNAVAILABLE, NO_OFFERS, UPDATE_REQUIRED → unavailable; ACCOUNT_MISMATCH,
     * PURCHASE_REJECTED, MONETIZATION_DISABLED → not_allowed; {@link #PENDING} → pending; everything else → failed.
     */
    public static CustomerEvents.FailReason reason(String problemName) {
        if (problemName == null) return CustomerEvents.FailReason.FAILED;
        switch (problemName) {
            case "USER_CANCELED":
                return CustomerEvents.FailReason.CANCELLED;
            case "BILLING_UNSUPPORTED":
            case "NOT_CONFIGURED":
            case "STORE_UNAVAILABLE":
            case "NO_OFFERS":
            case "UPDATE_REQUIRED":
                return CustomerEvents.FailReason.UNAVAILABLE;
            case "ACCOUNT_MISMATCH":
            case "PURCHASE_REJECTED":
            case "MONETIZATION_DISABLED":
                return CustomerEvents.FailReason.NOT_ALLOWED;
            case PENDING:
                return CustomerEvents.FailReason.PENDING;
            default:
                return CustomerEvents.FailReason.FAILED;
        }
    }

    /** {@code pro.restore.outcome} of the sink's outcome ({@code restored}, {@code nothing}); anything else → failed. */
    public static CustomerEvents.RestoreOutcome restoreOutcome(String outcome) {
        if (CustomerEvents.RestoreOutcome.RESTORED.wire.equals(outcome)) return CustomerEvents.RestoreOutcome.RESTORED;
        if (CustomerEvents.RestoreOutcome.NOTHING.wire.equals(outcome)) return CustomerEvents.RestoreOutcome.NOTHING;
        return CustomerEvents.RestoreOutcome.FAILED;
    }

    /**
     * {@code pro.trial_start.outcome}: started on success; refused for the server's trial refusals
     * ({@code trial_already_used}, {@code trial_device_cap}, {@code trial_device_blocked}, {@code trial_paused});
     * failed otherwise.
     */
    public static CustomerEvents.TrialOutcome trialOutcome(boolean success, String errorCode) {
        if (success) return CustomerEvents.TrialOutcome.STARTED;
        if ("trial_already_used".equals(errorCode) || "trial_device_cap".equals(errorCode) || "trial_device_blocked".equals(errorCode)
                || "trial_paused".equals(errorCode)) {
            return CustomerEvents.TrialOutcome.REFUSED;
        }
        return CustomerEvents.TrialOutcome.FAILED;
    }

    /**
     * The {@code error_code} a funnel event carries: the backend's stable code when the failure came from the server,
     * else null (omitted).
     */
    public static String serverCode(String code) { return ErrorCodes.orNull(code); }
}
