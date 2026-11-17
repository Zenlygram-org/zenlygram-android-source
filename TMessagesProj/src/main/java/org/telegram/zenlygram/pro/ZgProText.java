/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pro;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel;
import org.telegram.messenger.zenlygram.pro.ProErrors;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The words of the Zenlygram Pro screen and of the hub's Subscription value: the access line, the Google Play state
 * and the offers. Prices, periods and introductory phases are Google Play's own localized values; nothing here
 * computes or promises one.
 */
public final class ZgProText {

    private ZgProText() {
    }

    /**
     * One line naming the account's access. {@code until}: Unix seconds of the date the line names, or 0. "Free until
     * …" is said only where {@link ZgProRules#namesFreeUntil} allows it (here, in the notice and in the hub value): the
     * date is the end of the local grace at the signed policy's length, and a length of 0 never gets the line.
     */
    public static String status(ZgPro.Access access, long until) {
        switch (access) {
            case UNLOCKED_BUILD:
                return getString(R.string.ZG_Entitlement_Status_UnlockedBuild);
            case ACTIVE:
                return until > 0 ? formatString(R.string.ZG_Entitlement_Status_AvailableUntil, date(until))
                    : getString(R.string.ZG_Entitlement_Status_Available);
            case GRACE:
                return ZgProRules.namesFreeUntil(access, until) ? formatString(R.string.ZG_Entitlement_Status_FreeUntil, date(until))
                    : getString(R.string.ZG_Entitlement_Status_Available);
            case INACTIVE:
                return getString(R.string.ZG_Entitlement_Status_Inactive);
            case LAPSED:
                return getString(R.string.ZG_Entitlement_Status_Lapsed);
            case RECONNECT:
                return getString(R.string.ZG_Entitlement_Status_Reconnect);
            case FREE_ACCESS:
            default:
                return getString(R.string.ZG_Entitlement_Status_FreeAccess);
        }
    }

    /**
     * The paragraph under the access line, or null. Free: nothing is sold (and, for an account that holds a genuine
     * paid period, until when it runs). Paid: what the state means and that nothing the user has is affected.
     */
    public static CharSequence statusNotice(ZgPro.Access access, long until) {
        switch (access) {
            case FREE_ACCESS: {
                final String free = getString(R.string.ZG_Entitlement_Status_FreeAccess_Notice);
                return until > 0 ? free + "\n\n" + formatString(R.string.ZG_Entitlement_Status_FreeAccess_AccountUntil, date(until)) : free;
            }
            case GRACE:
                return ZgProRules.namesFreeUntil(access, until) ? formatString(R.string.ZG_PayWall_Grace_Notice, date(until)) : null;
            case INACTIVE:
                return getString(R.string.ZG_PayWall_Text) + "\n\n" + getString(R.string.ZG_PayWall_Degraded_Reassurance);
            case LAPSED:
                return getString(R.string.ZG_Entitlement_Message_SubscriptionEnded);
            case RECONNECT:
                return getString(R.string.ZG_Entitlement_Message_ReconnectToRestore);
            case ACTIVE:
            case UNLOCKED_BUILD:
            default:
                return null;
        }
    }

    /** The value of the hub's Subscription row (iOS: Free / Free until … / Pro available / Off). */
    public static CharSequence hubValue(ZgPro.Access access, long until) {
        switch (access) {
            case FREE_ACCESS:
                return getString(R.string.ZG_Settings_Pro_Status_Free);
            case GRACE:
                return ZgProRules.namesFreeUntil(access, until) ? formatString(R.string.ZG_Settings_Pro_Status_FreeUntil, date(until))
                    : getString(R.string.ZG_Settings_Pro_Status_Free);
            case INACTIVE:
            case LAPSED:
            case RECONNECT:
                return getString(R.string.ZG_Settings_Pro_Status_Inactive);
            case ACTIVE:
            case UNLOCKED_BUILD:
            default:
                return getString(R.string.ZG_Entitlement_Status_Available);
        }
    }

    /** Why "Refresh Pro access" did not go through. */
    public static String error(ZenlyPro.Error error) {
        switch (ProErrors.message(error == null ? null : error.code)) {
            case UPDATE_REQUIRED:
                return getString(R.string.ZenlyProUpdateRequired);
            case WRONG_ACCOUNT:
                return getString(R.string.ZenlyProWrongAccount);
            case WAIT:
                return getString(R.string.ZenlyProWait);
            case STORAGE:
                return getString(R.string.ZenlyProStorageError);
            case PURCHASES_PAUSED:
                return getString(R.string.ZenlyProPurchasesPaused);
            default:
                return getString(R.string.ZenlyProConnectionError);
        }
    }

    /** The Google Play section's state in one sentence, or null when there is nothing to say. */
    public static String playStatus(GooglePlayModel.State state) {
        switch (state.stage) {
            case LINK_REQUIRED:
                return getString(R.string.ZenlyGoogleLinkFirst);
            case LOADING:
                return getString(state.free ? R.string.ZenlyGoogleCheckingPurchases : R.string.ZenlyGoogleLoading);
            case PURCHASING:
                return getString(R.string.ZenlyGooglePurchasing);
            case VERIFYING:
                return getString(R.string.ZenlyGoogleVerifying);
            default:
                break;
        }
        if (state.problem != GooglePlayModel.Problem.NONE) {
            return problem(state.problem);
        }
        if (state.confirmed != null) {
            switch (state.confirmed) {
                case ACTIVE:
                    return getString(R.string.ZenlyGoogleConfirmedActive);
                case GRACE:
                    return getString(R.string.ZenlyGoogleConfirmedGrace);
                case CANCELED:
                    return getString(R.string.ZenlyGoogleConfirmedCanceled);
                case PENDING:
                    return getString(R.string.ZenlyGooglePending);
                case ON_HOLD:
                    return getString(R.string.ZenlyGoogleConfirmedHold);
                case PAUSED:
                    return getString(R.string.ZenlyGoogleConfirmedPaused);
                default:
                    return getString(R.string.ZenlyGoogleConfirmedEnded);
            }
        }
        if (state.purchases.pending) {
            return getString(R.string.ZenlyGooglePending);
        }
        if (state.purchases.owned) {
            return getString(R.string.ZenlyGoogleOwned);
        }
        return null;
    }

    static String problem(GooglePlayModel.Problem problem) {
        switch (problem) {
            case BILLING_UNSUPPORTED:
                return getString(R.string.ZenlyGoogleUnsupported);
            case NOT_CONFIGURED:
                return getString(R.string.ZenlyGoogleNotConfigured);
            case STORE_UNAVAILABLE:
                return getString(R.string.ZenlyGoogleStoreUnavailable);
            case OFFLINE:
                return getString(R.string.ZenlyGoogleOffline);
            case NO_OFFERS:
                return getString(R.string.ZenlyGoogleNoOffers);
            case USER_CANCELED:
                return getString(R.string.ZenlyGoogleCanceled);
            case ALREADY_OWNED:
                return getString(R.string.ZenlyGoogleOwned);
            case ACCOUNT_MISMATCH:
                return getString(R.string.ZenlyGoogleAccountMismatch);
            case PURCHASE_REJECTED:
                return getString(R.string.ZenlyGoogleRejected);
            case NOTHING_TO_RESTORE:
                return getString(R.string.ZenlyGoogleRestoreNothing);
            case SESSION_CHANGED:
                return getString(R.string.ZenlyGoogleSessionChanged);
            case STORAGE_UNAVAILABLE:
                return getString(R.string.ZenlyProStorageError);
            case RATE_LIMITED:
                return getString(R.string.ZenlyProWait);
            case MONETIZATION_DISABLED:
                return getString(R.string.ZenlyProPurchasesPaused);
            case UPDATE_REQUIRED:
                return getString(R.string.ZenlyProUpdateRequired);
            default:
                return getString(R.string.ZenlyProConnectionError);
        }
    }

    /** "Subscribe for $4.99 / month": the offer's recurring price as Google Play words it. */
    public static String offerTitle(GooglePlayModel.Offer offer) {
        return formatString(R.string.ZenlyGoogleSubscribe, recurring(offer.recurringPhase()));
    }

    /** The eligible free or introductory phases (if any), then the auto-renewal price and how to cancel. */
    public static String offerTerms(GooglePlayModel.Offer offer) {
        String intro = null;
        for (GooglePlayModel.PricingPhase phase : offer.introductoryPhases()) {
            final String part = phase.isFree()
                ? formatString(R.string.ZenlyGoogleFreeTrial, duration(phase.duration()))
                : formatString(R.string.ZenlyGoogleIntroPrice, recurring(phase), duration(phase.duration()));
            intro = intro == null ? part : formatString(R.string.ZenlyGoogleThen, intro, part);
        }
        final String renewal = formatString(R.string.ZenlyGoogleRenews, recurring(offer.recurringPhase()));
        return intro == null ? renewal : formatString(R.string.ZenlyGoogleOfferIntro, intro) + " " + renewal;
    }

    /** Google Play's own localized price with its billing period. */
    private static String recurring(GooglePlayModel.PricingPhase phase) {
        final GooglePlayModel.Period period = phase.period();
        final boolean single = period.count == 1;
        final int text;
        switch (period.unit) {
            case DAY:
                text = single ? R.string.ZenlyGooglePerDay : R.string.ZenlyGoogleEveryDays;
                break;
            case WEEK:
                text = single ? R.string.ZenlyGooglePerWeek : R.string.ZenlyGoogleEveryWeeks;
                break;
            case MONTH:
                text = single ? R.string.ZenlyGooglePerMonth : R.string.ZenlyGoogleEveryMonths;
                break;
            default:
                text = single ? R.string.ZenlyGooglePerYear : R.string.ZenlyGoogleEveryYears;
                break;
        }
        return single ? formatString(text, phase.formattedPrice) : formatString(text, phase.formattedPrice, period.count);
    }

    private static String duration(GooglePlayModel.Period period) {
        final boolean single = period.count == 1;
        switch (period.unit) {
            case DAY:
                return single ? getString(R.string.ZenlyGoogleOneDay) : formatString(R.string.ZenlyGoogleDays, period.count);
            case WEEK:
                return single ? getString(R.string.ZenlyGoogleOneWeek) : formatString(R.string.ZenlyGoogleWeeks, period.count);
            case MONTH:
                return single ? getString(R.string.ZenlyGoogleOneMonth) : formatString(R.string.ZenlyGoogleMonths, period.count);
            default:
                return single ? getString(R.string.ZenlyGoogleOneYear) : formatString(R.string.ZenlyGoogleYears, period.count);
        }
    }

    /** A date in the app's language, without the time. */
    static String date(long epochSeconds) {
        Locale locale = null;
        try {
            locale = LocaleController.getInstance().getCurrentLocale();
        } catch (Throwable ignore) {
            // The device locale below.
        }
        final DateFormat format = locale != null ? DateFormat.getDateInstance(DateFormat.MEDIUM, locale) : DateFormat.getDateInstance(DateFormat.MEDIUM);
        return format.format(new Date(epochSeconds * 1000L));
    }
}
