package org.telegram.messenger.zenlygram.pro;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Catalog;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Manage;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Offer;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Plan;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.PricingPhase;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Problem;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Purchase;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Stage;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.State;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.SubscriptionState;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Summary;

/**
 * Plain-JVM checks for the checkout recovery paths a user can reach: a price lookup that fails on a
 * working Play connection (AC-P2), repeated Restore taps and Restore racing other Play work (AC-P1),
 * and whether Pro account commands must wait for Play (AC-P4). Fixture ids only.
 */
public final class GooglePlayRecoveryTests {
    private static int checks;
    private static final String ACCOUNT_A = repeat("a", 64);
    private static final int PURCHASED = 1, PENDING = 2;
    private static final int INFINITE = 1;

    public static void main(String[] args) {
        offerFailureKeepsOwnedPurchaseRecovery();
        missingPricesStayReloadableAfterRecovery();
        sessionChangeRequiresFreshBinding();
        restoreIsSingleFlight();
        playWorkInFlight();
        purchaseFlowEvents();
        storageFailures();
        System.out.println("Google Play recovery: " + checks + " checks passed");
    }

    private static Catalog catalog() {
        return new Catalog(ACCOUNT_A, Arrays.asList(new Plan("fixture.pro", "monthly"), new Plan("fixture.pro", "yearly")));
    }

    private static Summary summary(String token, int state, boolean acknowledged) {
        return Summary.of(Collections.singletonList(new Purchase(token, Collections.singletonList("fixture.pro"), state, ACCOUNT_A,
                acknowledged)), catalog());
    }

    private static Summary none() { return Summary.of(Collections.<Purchase>emptyList(), catalog()); }

    private static Offer monthly() {
        return new Offer("fixture.pro", "monthly", null, "t",
                Collections.singletonList(new PricingPhase("$4.99", 4990000, "P1M", 0, INFINITE)));
    }

    private static State ready() {
        return State.initial(true, true, true, true).offersLoaded(Collections.singletonList(monthly())).purchasesLoaded(none());
    }

    /** AC-P2: a failed price lookup must not hide a subscription the user already pays for. */
    private static void offerFailureKeepsOwnedPurchaseRecovery() {
        State loading = State.initial(true, true, true, true);
        State pricesFailed = loading.offersFailed(Problem.OFFLINE);
        check(pricesFailed.storeReady && pricesFailed.offers.isEmpty(), "price failure keeps the working Play connection");
        check(!pricesFailed.canPurchase() && !pricesFailed.canRestore(), "owned purchases still unknown: no actions yet");

        State owned = pricesFailed.purchasesLoaded(summary("owned", PURCHASED, true));
        check(owned.stage == Stage.UNAVAILABLE && owned.problem == Problem.OFFLINE, "the price problem stays visible");
        check(!owned.canPurchase(), "without a Play price nothing new can be bought");
        check(owned.canRestore() && owned.manage() == Manage.SUBSCRIPTION, "an owned subscription stays restorable and manageable");
        check(!owned.canRetry(), "an owned subscription needs no price reload, even after a price lookup failure");
        State ownedNoConnection = State.initial(true, true, true, true).purchasesLoaded(summary("owned", PURCHASED, true))
                .failed(Problem.OFFLINE);
        check(!ownedNoConnection.storeReady && ownedNoConnection.canRetry(),
                "without a Play connection Try again still reconnects, whatever is owned");

        State unverified = pricesFailed.purchasesLoaded(summary("fresh", PURCHASED, false));
        State confirmed = unverified.verifying().attached(SubscriptionState.ACTIVE);
        check(unverified.verifying().stage == Stage.VERIFYING, "an unverified own purchase is still sent for verification");
        check(confirmed.confirmed == SubscriptionState.ACTIVE && !confirmed.canPurchase() && confirmed.manage() == Manage.SUBSCRIPTION,
                "its backend confirmation is shown even though prices failed");

        State pending = pricesFailed.purchasesLoaded(summary("waiting", PENDING, false));
        check(pending.purchases.pending && !pending.canPurchase() && pending.canRestore(), "a pending payment stays explicit");

        State nothingOwned = pricesFailed.purchasesLoaded(none());
        check(nothingOwned.canRetry() && nothingOwned.canRestore() && nothingOwned.manage() == Manage.NONE && !nothingOwned.canPurchase(),
                "nothing owned and no prices: Try again and Restore, no Manage claim");
        State noOffers = loading.offersLoaded(Collections.<Offer>emptyList()).purchasesLoaded(none());
        check(noOffers.canRetry(), "an empty Play price list can also be reloaded");

        State unsupported = loading.offersFailed(Problem.BILLING_UNSUPPORTED);
        check(!unsupported.storeReady && !unsupported.canRestore() && !unsupported.canRetry() && unsupported.problem == Problem.BILLING_UNSUPPORTED,
                "billing unsupported is not a recoverable store");
        State connectionLost = loading.failed(Problem.OFFLINE);
        check(!connectionLost.storeReady && !connectionLost.canRestore() && connectionLost.canRetry(),
                "no Play connection at all: retry only (unchanged)");
        check(State.initial(true, true, true, false).offersFailed(Problem.OFFLINE).stage == Stage.LINK_REQUIRED,
                "late price failure cannot leave the link-required screen");
        check(State.initial(false, true, true, true).offersFailed(Problem.OFFLINE).stage == Stage.DISABLED,
                "direct APK stays without Play");
    }

    /**
     * PAY-P-L1: while a connected Play store has given no prices and nothing is owned or pending, every
     * resting state after a restore or verification must still offer Try again; otherwise the screen
     * dead-ends with no way to load prices. An owned or pending subscription needs no price reload (Restore
     * and Manage stay). Busy, frozen, unconfigured, unsupported, update-required and storage-failure states
     * keep hiding it, and a store with prices never offers it.
     */
    private static void missingPricesStayReloadableAfterRecovery() {
        State loading = State.initial(true, true, true, true);
        State pricesFailed = loading.offersFailed(Problem.OFFLINE).purchasesLoaded(none());
        State emptyPrices = loading.offersLoaded(Collections.<Offer>emptyList()).purchasesLoaded(none());
        for (State noPrices : Arrays.asList(pricesFailed, emptyPrices)) {
            State nothingToRestore = noPrices.restoring().settle(none()).failed(Problem.NOTHING_TO_RESTORE);
            check(nothingToRestore.stage == Stage.UNAVAILABLE && nothingToRestore.problem == Problem.NOTHING_TO_RESTORE
                    && nothingToRestore.canRetry(), "an empty restore without prices still offers Try again");
            for (SubscriptionState ended : Arrays.asList(SubscriptionState.EXPIRED, SubscriptionState.REVOKED)) {
                State attached = noPrices.restoring().attached(ended);
                check(attached.stage == Stage.UNAVAILABLE && !attached.canPurchase() && attached.canRetry(),
                        "an ended subscription without prices can reload them to buy again (" + ended + ")");
            }
            State active = noPrices.restoring().attached(SubscriptionState.ACTIVE);
            check(!active.canRetry() && active.manage() == Manage.SUBSCRIPTION && active.confirmed == SubscriptionState.ACTIVE,
                    "a confirmed subscription keeps Manage and its status; it needs no price reload");
            State waitingPayment = noPrices.restoring().attached(SubscriptionState.PENDING);
            check(waitingPayment.purchases.pending && !waitingPayment.canRetry(), "a pending payment needs no price reload");
            State pendingUpdate = noPrices.pending();
            check(pendingUpdate.purchases.pending && !pendingUpdate.canRetry() && pendingUpdate.canRestore()
                    && pendingUpdate.manage() == Manage.SUBSCRIPTION, "a Play pending update needs no price reload; Restore and Manage stay");
            State settledOwned = noPrices.restoring().settle(summary("owned", PURCHASED, true));
            check(settledOwned.purchases.owned && !settledOwned.canRetry() && settledOwned.canRestore()
                    && settledOwned.manage() == Manage.SUBSCRIPTION, "a restore that settles on an owned subscription needs no price reload");
            State settled = noPrices.restoring().settle(none());
            check(settled.canRetry(), "a restore that found nothing new still offers Try again");
            State rejected = noPrices.restoring().failed(Problem.PURCHASE_REJECTED);
            check(rejected.canRetry() && rejected.canRestore(), "a rejected verification without prices keeps both recoveries");

            check(!noPrices.restoring().canRetry() && !noPrices.requerying().canRetry(), "nothing is retried while verifying or re-reading");
            State updateRequired = noPrices.restoring().failed(Problem.UPDATE_REQUIRED);
            check(!updateRequired.canRetry(), "an app update is required: retrying cannot help");
            State storage = noPrices.restoring().failed(Problem.STORAGE_UNAVAILABLE);
            check(!storage.canRetry() && storage.canRestore(), "a device storage failure is not fixed by reloading prices; Restore stays");
        }
        State withPrices = ready().restoring().failed(Problem.NOTHING_TO_RESTORE);
        check(withPrices.stage == Stage.READY && !withPrices.canRetry(), "prices already loaded: no Try again");
        State unsupported = loading.offersFailed(Problem.BILLING_UNSUPPORTED);
        check(!unsupported.canRetry() && !unsupported.restoring().canRetry(), "billing unsupported never offers Try again");
        State unconfigured = State.initial(true, false, true, true);
        check(!unconfigured.canRetry(), "missing Pro configuration never offers Try again");
        check(!State.initial(true, true, true, false).canRetry() && !State.initial(false, true, true, true).canRetry()
                && !State.initial(true, true, false, true).canRetry(), "link-required, Direct and signed-out never offer Try again");
    }

    /**
     * A restore, verification or purchase result that fails because the account's Pro session changed
     * (GooglePlayCheckout publishes failed(SESSION_CHANGED) while the account is still linked) leaves a
     * stale Play binding. Until the user's Retry prepares a fresh one (GooglePlayCheckout.reload →
     * prepareGooglePlay → a new initial state), nothing may be bought or restored with the stale binding
     * and late store results cannot revive it. Retry is offered whether or not prices were loaded and
     * whatever is owned or pending. The model itself starts nothing (no work in flight, no account hold);
     * reloading is the user's Try again or the screen's normal resume. Manage (a plain Google Play link) stays.
     */
    private static void sessionChangeRequiresFreshBinding() {
        State loading = State.initial(true, true, true, true);
        State pricesFailed = loading.offersFailed(Problem.OFFLINE);
        State emptyPrices = loading.offersLoaded(Collections.<Offer>emptyList());
        State priced = ready();
        List<State> changedStates = new ArrayList<>();
        // Prices loaded, nothing owned: verification or our own purchase confirmation hit the session change.
        changedStates.add(priced.verifying().failed(Problem.SESSION_CHANGED));
        changedStates.add(priced.restoring().failed(Problem.SESSION_CHANGED));
        changedStates.add(priced.purchasing().requerying().failed(Problem.SESSION_CHANGED));
        for (State noPrices : Arrays.asList(priced, pricesFailed, emptyPrices)) {
            for (Summary held : Arrays.asList(summary("owned", PURCHASED, true), summary("waiting", PENDING, false), none())) {
                State known = noPrices == priced ? noPrices.requerying().purchasesLoaded(held) : noPrices.purchasesLoaded(held);
                State changed = known.restoring().failed(Problem.SESSION_CHANGED);
                if (changed != known) changedStates.add(changed);
            }
        }
        for (State changed : changedStates) {
            check(!changed.canPurchase(), "after a session change nothing is bought with the stale Play binding");
            check(!changed.canRestore() && changed.restoring() == changed && changed.verifying() == changed,
                    "after a session change nothing is restored or verified with the stale Play binding");
            check(changed.stage == Stage.UNAVAILABLE && changed.problem == Problem.SESSION_CHANGED && !changed.storeReady,
                    "the session change is shown until a fresh binding is prepared");
            check(changed.canRetry(), "Try again prepares a fresh Play binding (offered with or without prices, owned or not)");
            check(changed.offersLoaded(Collections.singletonList(monthly())) == changed && changed.purchasesLoaded(none()) == changed
                            && changed.requerying() == changed && changed.attached(SubscriptionState.ACTIVE) == changed,
                    "late results of the stale binding cannot make the screen purchasable again");
            check(!changed.holdsAccountCommands() && !changed.inFlight(),
                    "the stale state is idle: no work in flight and no hold on account commands");
            check(changed.manage() != Manage.NONE, "a Google Play subscriptions link stays reachable");
            check(changed.onPurchaseFlow(Problem.NONE) == GooglePlayModel.FlowStep.RECONCILE,
                    "an outside purchase update goes to background reconciliation, which prepares its own binding");
        }
        State owned = pricesFailed.purchasesLoaded(summary("owned", PURCHASED, true)).restoring().failed(Problem.SESSION_CHANGED);
        check(owned.manage() == Manage.SUBSCRIPTION, "a known subscription stays manageable");
        State stillHeld = pricesFailed.purchasesLoaded(summary("owned", PURCHASED, true)).restoring().failed(Problem.NOTHING_TO_RESTORE);
        check(!stillHeld.canRetry() && stillHeld.canRestore(), "other outcomes keep the owned rule and the current binding");
        check(!State.initial(true, true, true, false).canRetry(), "an unlinked account is sent back to linking, not offered Try again");
        check(!State.initial(true, true, false, true).canRetry() && !State.initial(false, true, true, true).canRetry(),
                "signed-out and Direct screens never offer Try again");
        State prepareFailed = loading.failed(Problem.SESSION_CHANGED);
        check(prepareFailed.canRetry() && !prepareFailed.canRestore() && !prepareFailed.canPurchase(),
                "a session change while preparing also offers only Try again");
    }

    /** AC-P1: Restore is one operation at a time and never races purchase or initial recovery. */
    private static void restoreIsSingleFlight() {
        State ready = ready();
        State restoring = ready.restoring();
        check(restoring.stage == Stage.VERIFYING && restoring != ready, "Restore starts verification");
        check(restoring.restoring() == restoring, "a second Restore tap while verifying starts nothing");
        check(ready.stage == Stage.READY, "restoring never mutates the previous state");

        State loading = State.initial(true, true, true, true);
        check(loading.restoring() == loading, "Restore cannot start while Play is loading");
        State offersOnly = loading.offersLoaded(Collections.singletonList(monthly()));
        check(offersOnly.restoring() == offersOnly, "Restore cannot race the initial owned-purchase query");
        State buying = ready.purchasing();
        check(buying.restoring() == buying, "Restore cannot start during a purchase");
        State direct = State.initial(false, true, true, true);
        check(direct.restoring() == direct, "direct APK has no Restore");
        State unlinked = State.initial(true, true, true, false);
        check(unlinked.restoring() == unlinked, "unlinked account has no Restore");
        State offline = loading.failed(Problem.OFFLINE);
        check(offline.restoring() == offline, "no Play connection: Restore has nothing to query");

        State nothing = restoring.settle(none()).failed(Problem.NOTHING_TO_RESTORE);
        check(nothing.restoring().stage == Stage.VERIFYING, "after a finished restore the user may try again");
        State canceled = buying.failed(Problem.USER_CANCELED);
        check(canceled.canPurchase() && canceled.restoring().stage == Stage.VERIFYING, "a canceled purchase recovers both actions");
    }

    /** Model only: which Play stages count as a purchase or verification in flight (resume keeps them). */
    private static void playWorkInFlight() {
        State ready = ready();
        check(!ready.inFlight() && !State.initial(true, true, true, true).inFlight(), "idle or loading Play is not in flight");
        check(ready.purchasing().inFlight(), "an open purchase is in flight");
        check(ready.restoring().inFlight() && ready.verifying().inFlight(), "verification is in flight");
        check(!ready.purchasing().failed(Problem.USER_CANCELED).inFlight(), "cancel ends it");
        check(!ready.verifying().failed(Problem.OFFLINE).inFlight(), "an error ends it");
        check(!ready.verifying().attached(SubscriptionState.PENDING).inFlight(), "a pending payment ends it");
        check(!State.initial(false, true, true, true).inFlight(), "direct APK is never in flight");
    }

    /**
     * Security L4 / review M4: Play's purchases-updated events carry only that flow's purchases. The
     * checkout applies one directly only to its own purchase in flight on a fully known store; while a
     * restore verifies it hands the event to background reconciliation (no second UI attach); otherwise
     * a new full purchase query decides (never a partial list).
     */
    private static void purchaseFlowEvents() {
        State loading = State.initial(true, true, true, true);
        State offersOnly = loading.offersLoaded(Collections.singletonList(monthly()));
        State ready = ready();
        State buying = ready.purchasing();
        State restoring = ready.restoring();
        State unlinked = State.initial(true, true, true, false);
        State connectionLost = loading.failed(Problem.OFFLINE);

        check(buying.onPurchaseFlow(Problem.NONE) == GooglePlayModel.FlowStep.REQUERY,
                "our own successful purchase is confirmed from the full purchase list, never from that flow's partial list");
        check(buying.onPurchaseFlow(Problem.USER_CANCELED) == GooglePlayModel.FlowStep.FAIL, "a canceled purchase is shown");
        check(buying.onPurchaseFlow(Problem.ALREADY_OWNED) == GooglePlayModel.FlowStep.RESTORE_OWNED, "already owned restores");
        check(restoring.onPurchaseFlow(Problem.NONE) == GooglePlayModel.FlowStep.RECONCILE,
                "during a restore an outside purchase update never starts a second attach on the screen");
        check(restoring.onPurchaseFlow(Problem.ALREADY_OWNED) == GooglePlayModel.FlowStep.IGNORE
                && restoring.onPurchaseFlow(Problem.OFFLINE) == GooglePlayModel.FlowStep.IGNORE, "a restore ignores stray errors");
        check(offersOnly.onPurchaseFlow(Problem.NONE) == GooglePlayModel.FlowStep.REQUERY,
                "before the full purchase query a partial update triggers a full query instead of unlocking actions");
        check(loading.onPurchaseFlow(Problem.NONE) == GooglePlayModel.FlowStep.IGNORE,
                "while prices load, the coming full query already includes the purchase");
        check(ready.onPurchaseFlow(Problem.NONE) == GooglePlayModel.FlowStep.REQUERY, "an outside completion re-reads all purchases");
        check(ready.onPurchaseFlow(Problem.OFFLINE) == GooglePlayModel.FlowStep.IGNORE, "an idle screen ignores stray errors");
        check(connectionLost.onPurchaseFlow(Problem.NONE) == GooglePlayModel.FlowStep.RECONCILE, "no store connection: reconcile in background");
        check(unlinked.onPurchaseFlow(Problem.NONE) == GooglePlayModel.FlowStep.RECONCILE,
                "an unlinked screen lets the account the purchase is bound to pick it up");
        check(State.initial(false, true, true, true).onPurchaseFlow(Problem.NONE) == GooglePlayModel.FlowStep.RECONCILE,
                "direct APK never applies a Play event on screen");

        check(loading.holdsAccountCommands() && offersOnly.holdsAccountCommands(),
                "unlink / trial / link wait while Play loads (the first query may start an automatic attach)");
        check(buying.holdsAccountCommands() && restoring.holdsAccountCommands(), "and while a purchase or verification runs");
        check(!ready.holdsAccountCommands() && !connectionLost.holdsAccountCommands() && !unlinked.holdsAccountCommands()
                && !State.initial(false, true, true, true).holdsAccountCommands(), "idle, failed, unlinked and Direct never hold them");
    }

    /** Security L6: a local storage failure is not reported as a connection problem. */
    private static void storageFailures() {
        check(Problem.fromServer("storage_unavailable", 0) == Problem.STORAGE_UNAVAILABLE, "storage failure keeps its own meaning");
        State failed = ready().verifying().failed(Problem.STORAGE_UNAVAILABLE);
        check(failed.problem == Problem.STORAGE_UNAVAILABLE && failed.canRestore(), "the operation stays recoverable");
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
