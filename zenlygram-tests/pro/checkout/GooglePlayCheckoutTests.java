package org.telegram.messenger.zenlygram.pro;

import android.app.Activity;

import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Host regression of the REAL GooglePlayCheckout (production source, compiled unchanged) against
 * narrow fakes of its platform, ZenlyPro network/session, Billing and background-sync boundaries
 * (see fakes/). Purchase admission (audit F2): a Subscribe tap is not authorized by a cached policy
 * or by the prepare done when the screen loaded. The checkout asks the backend for a fresh
 * new-purchase google/prepare ({}), which the server refuses with 409 monetization_disabled while
 * free, and opens Play's sheet only after that answer succeeds for the same account, catalog plan
 * and offer, while the screen, identity, operation and paid policy are still current. Restore,
 * reconciliation and already-admitted purchase fulfillment are never gated by admission.
 *
 * This is bounded behavioral evidence of the controller only: no real Play provider, TLS, backend,
 * device or ZenlyPro signature/time/persistence rule is exercised here.
 */
public final class GooglePlayCheckoutTests {
    private static final int ACCOUNT = 0;
    private static final long TELEGRAM_ID = 777, OTHER_TELEGRAM_ID = 888;
    private static final String OBFUSCATED = repeat('a'), OTHER_OBFUSCATED = repeat('b');
    private static final GooglePlayModel.Plan MONTHLY_PLAN = new GooglePlayModel.Plan("zenlygram_pro", "monthly");
    private static final GooglePlayModel.Plan OTHER_PLAN = new GooglePlayModel.Plan("zenlygram_pro", "yearly");
    private static final GooglePlayModel.Offer MONTHLY = new GooglePlayModel.Offer("zenlygram_pro", "monthly", null, "offer-token-monthly",
            Collections.singletonList(new GooglePlayModel.PricingPhase("$4.99", 4_990_000L, "P1M", 0, GooglePlayModel.RECURRENCE_INFINITE)));
    private static final GooglePlayModel.Offer MONTHLY_TRIAL = new GooglePlayModel.Offer("zenlygram_pro", "monthly", "free-week", "offer-token-trial",
            Arrays.asList(new GooglePlayModel.PricingPhase("Free", 0L, "P1W", 1, 2),
                    new GooglePlayModel.PricingPhase("$4.99", 4_990_000L, "P1M", 0, GooglePlayModel.RECURRENCE_INFINITE)));
    private static final String PURCHASE_TOKEN = "purchase-token-0001";

    private static int checks, failures;

    public static void main(String[] args) {
        run("subscribe asks the backend for a fresh new-purchase admission before Play opens", GooglePlayCheckoutTests::subscribeAsksForFreshAdmission);
        run("409 monetization_disabled at admission never opens Play", GooglePlayCheckoutTests::serverRefusalBlocksLaunch);
        run("admission outage or transport failure never opens Play", GooglePlayCheckoutTests::outageBlocksLaunch);
        run("a fresh matching admission opens Play once with the fresh binding", GooglePlayCheckoutTests::freshAdmissionLaunchesOnce);
        run("an admission for another binding or without the offer's plan never opens Play", GooglePlayCheckoutTests::mismatchedAdmissionNeverLaunches);
        run("the policy turning free while admission is pending never opens Play", GooglePlayCheckoutTests::policyTurnsFreeDuringAdmission);
        run("an account switch while admission is pending never opens Play", GooglePlayCheckoutTests::accountSwitchDuringAdmission);
        run("losing the Pro link while admission's answer is queued never opens Play", GooglePlayCheckoutTests::unlinkedDuringAdmission);
        run("closing the screen while admission is pending never opens Play", GooglePlayCheckoutTests::closeDuringAdmission);
        run("a reload while admission is pending never opens Play", GooglePlayCheckoutTests::reloadDuringAdmission);
        run("a double tap starts one admission and at most one Play sheet", GooglePlayCheckoutTests::doubleTapStartsOneAdmission);
        run("free at tap time asks the backend for nothing", GooglePlayCheckoutTests::freeAtTapAsksNothing);
        run("restore is never gated by purchase admission (paid and free)", GooglePlayCheckoutTests::restoreNotGated);
        run("an admitted purchase still completes after the policy turns free", GooglePlayCheckoutTests::admittedPurchaseFulfilledAfterFree);
        run("already owned at launch restores without a second admission", GooglePlayCheckoutTests::alreadyOwnedRestores);
        // Causal (expected RED before the L1/L2 source change):
        run("a tap after the Telegram account changed sends no admission and opens nothing", GooglePlayCheckoutTests::accountChangedBeforeTap);
        run("verified free during admission, then its error, recovers through the restore context only", GooglePlayCheckoutTests::freeThenAdmissionError);
        // Coverage of existing guards (expected GREEN before and after):
        run("a Play cancel arriving before the admission answer opens nothing", GooglePlayCheckoutTests::canceledBeforeAdmissionAnswer);
        run("session_changed or 401 at admission never opens Play (linked and unlinked)", GooglePlayCheckoutTests::sessionChangedAtAdmission);
        // Pro funnel (design 50 §11.4): ProFunnelSink receives exactly one outcome per purchase and restore.
        run("funnel: a purchase reports start, then success once (no trial)", GooglePlayCheckoutTests::funnelPurchaseSuccess);
        run("funnel: a free introductory phase reports trial=true", GooglePlayCheckoutTests::funnelTrialSuccess);
        run("funnel: Play's cancel after launch reports USER_CANCELED", GooglePlayCheckoutTests::funnelCanceled);
        run("funnel: an admission refusal reports the server problem and code, never a start", GooglePlayCheckoutTests::funnelAdmissionRefused);
        run("funnel: a pending Play purchase reports PENDING", GooglePlayCheckoutTests::funnelPending);
        run("funnel: an attach refusal reports the server code", GooglePlayCheckoutTests::funnelAttachRefused);
        run("funnel: restore reports restored, nothing and failed", GooglePlayCheckoutTests::funnelRestore);
        run("funnel: ALREADY_OWNED hands the purchase to one restore outcome", GooglePlayCheckoutTests::funnelAlreadyOwned);
        run("funnel: closing or reloading mid-purchase reports nothing; background attaches report nothing", GooglePlayCheckoutTests::funnelSilentCases);
        System.out.println("GooglePlayCheckoutTests: " + checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    // ---- Scenarios -------------------------------------------------------------------------

    private static void subscribeAsksForFreshAdmission() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        check(rig.newPurchasePrepares() == 2, "the tap sends one more new-purchase google/prepare {} (load + admission)");
        check(rig.billing.launches.isEmpty(), "Play's sheet is not opened before the admission answer");
        check(rig.checkout.state().stage == GooglePlayModel.Stage.PURCHASING && rig.checkout.state().holdsAccountCommands(),
                "the screen waits for admission as an in-flight purchase (account commands held)");
        check(!rig.checkout.state().canPurchase(), "nothing else can be bought while admission runs");
    }

    private static void serverRefusalBlocksLaunch() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        ZenlyPro.Prepare admission = rig.admission();
        if (admission == null) return;
        admission.callback.onError(new ZenlyPro.Error("monetization_disabled", "Pro is free right now.", 409, 0));
        check(rig.billing.launches.isEmpty(), "409 monetization_disabled: Play is never opened");
        GooglePlayModel.State state = rig.checkout.state();
        check(state.problem == GooglePlayModel.Problem.MONETIZATION_DISABLED, "the refusal is shown as purchases paused");
        check(!state.inFlight() && !state.canPurchase(), "the screen leaves the purchase and offers nothing to buy");
        check(state.canRestore(), "Restore stays available after the refusal");
        check(!state.canRetry(), "the refusal is not retried in a loop");
    }

    private static void outageBlocksLaunch() {
        List<ZenlyPro.Error> errors = Arrays.asList(
                new ZenlyPro.Error("google_unavailable", "Unavailable", 503, 0),
                new ZenlyPro.Error("monetization_unavailable", "Unavailable", 503, 0),
                new ZenlyPro.Error("service_unavailable", "Unavailable", 503, 0),
                new ZenlyPro.Error("connection_failed", "Offline", 0, 0),
                new ZenlyPro.Error("rate_limited", "Wait", 429, 30));
        for (ZenlyPro.Error error : errors) {
            Rig rig = Rig.paidReady();
            rig.checkout.purchase(rig.activity, rig.offer());
            ZenlyPro.Prepare admission = rig.admission();
            if (admission == null) return;
            admission.callback.onError(error);
            check(rig.billing.launches.isEmpty(), error.code + ": Play is never opened without an admission answer");
            check(!rig.checkout.state().inFlight(), error.code + ": the screen leaves the purchase");
            check(rig.checkout.state().problem != GooglePlayModel.Problem.NONE, error.code + ": the failure is shown");
        }
    }

    private static void freshAdmissionLaunchesOnce() {
        Rig rig = Rig.paidReady();
        GooglePlayModel.Offer offer = rig.offer();
        rig.checkout.purchase(rig.activity, offer);
        ZenlyPro.Prepare admission = rig.admission();
        if (admission == null) return;
        ZenlyPro.PlayAccount fresh = account(OBFUSCATED, MONTHLY_PLAN);
        admission.callback.onSuccess(fresh);
        check(rig.billing.launches.size() == 1, "one Play sheet opens after the admission succeeds");
        if (rig.billing.launches.size() != 1) return;
        GooglePlayBilling.Launch launch = rig.billing.launches.get(0);
        check(launch.catalog == fresh.catalog, "Play is launched with the freshly admitted catalog");
        check(launch.offer == offer, "Play is launched with the tapped offer");
        check(launch.activity == rig.activity, "Play is launched from the tapping activity");
        check(rig.checkout.state().stage == GooglePlayModel.Stage.PURCHASING, "the screen stays purchasing until Play answers");

        rig.billing.purchases = Collections.singletonList(purchase(OBFUSCATED, false));
        GooglePlaySync.deliver(GooglePlayModel.Problem.NONE, rig.billing.purchases);
        check(GooglePlaySync.attaches.size() == 1, "the completed purchase is sent for backend verification");
        if (GooglePlaySync.attaches.size() != 1) return;
        GooglePlaySync.Attach attach = GooglePlaySync.attaches.get(0);
        check(attach.binding == fresh && !attach.restore, "the attach uses the admitted binding as an automatic (non-restore) attach");
        check(rig.newPurchasePrepares() == 2, "fulfillment asks for no further admission");
    }

    private static void mismatchedAdmissionNeverLaunches() {
        List<ZenlyPro.PlayAccount> answers = Arrays.asList(account(OTHER_OBFUSCATED, MONTHLY_PLAN), account(OBFUSCATED, OTHER_PLAN));
        for (ZenlyPro.PlayAccount answer : answers) {
            Rig rig = Rig.paidReady();
            rig.checkout.purchase(rig.activity, rig.offer());
            ZenlyPro.Prepare admission = rig.admission();
            if (admission == null) return;
            admission.callback.onSuccess(answer);
            String name = answer.catalog.obfuscatedAccountId.equals(OBFUSCATED) ? "offer plan missing" : "other binding";
            check(rig.billing.launches.isEmpty(), name + ": Play is never opened");
            check(!rig.checkout.state().canPurchase(), name + ": the stale offer is no longer purchasable");
            check(rig.checkout.state().stage != GooglePlayModel.Stage.PURCHASING, name + ": the screen leaves the purchase");
        }
    }

    private static void policyTurnsFreeDuringAdmission() {
        for (boolean notified : new boolean[]{true, false}) {
            Rig rig = Rig.paidReady();
            rig.checkout.purchase(rig.activity, rig.offer());
            ZenlyPro.Prepare admission = rig.admission();
            if (admission == null) return;
            rig.pro.purchasesAvailable = false;
            if (notified) rig.checkout.onMonetizationChanged();
            admission.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
            String name = notified ? "free notification first" : "free before its notification";
            check(rig.billing.launches.isEmpty(), name + ": Play is never opened");
            check(rig.checkout.state().free && !rig.checkout.state().canPurchase(), name + ": the screen shows free access with nothing to buy");
            check(rig.checkout.state().stage != GooglePlayModel.Stage.PURCHASING, name + ": the screen leaves the purchase");
        }
    }

    private static void accountSwitchDuringAdmission() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        ZenlyPro.Prepare admission = rig.admission();
        if (admission == null) return;
        UserConfig.setClientUserId(ACCOUNT, OTHER_TELEGRAM_ID);
        admission.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        check(rig.billing.launches.isEmpty(), "another Telegram user in the slot: Play is never opened");
    }

    /**
     * The worker answered the prepare while the session was still current; before that queued UI
     * callback runs, a later worker request's 401 (clearRefreshLocked) or unlink() removes the refresh
     * token. The Telegram identity, operation and paid policy are unchanged, so only the live link
     * state can refuse this new billing.
     */
    private static void unlinkedDuringAdmission() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        ZenlyPro.Prepare admission = rig.admission();
        if (admission == null) return;
        rig.pro.linked = false;
        admission.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        check(rig.billing.launches.isEmpty(), "unlinked before the admission answer ran: Play is never opened");
        check(rig.checkout.state().stage == GooglePlayModel.Stage.LINK_REQUIRED && !rig.checkout.state().canPurchase(),
                "the screen returns to linking with nothing to buy");
    }

    private static void closeDuringAdmission() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        ZenlyPro.Prepare admission = rig.admission();
        if (admission == null) return;
        rig.checkout.close();
        admission.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        check(rig.billing.launches.isEmpty(), "closed screen: Play is never opened");
    }

    private static void reloadDuringAdmission() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        ZenlyPro.Prepare admission = rig.admission();
        if (admission == null) return;
        rig.checkout.reload();
        admission.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        check(rig.billing.launches.isEmpty(), "superseded by a reload: Play is never opened");
    }

    private static void doubleTapStartsOneAdmission() {
        Rig rig = Rig.paidReady();
        GooglePlayModel.Offer offer = rig.offer();
        rig.checkout.purchase(rig.activity, offer);
        rig.checkout.purchase(rig.activity, offer);
        check(rig.newPurchasePrepares() == 2, "two taps send exactly one admission");
        ZenlyPro.Prepare admission = rig.admission();
        if (admission == null) return;
        admission.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        rig.checkout.purchase(rig.activity, offer);
        check(rig.billing.launches.size() == 1, "one Play sheet for the double tap");
        check(rig.newPurchasePrepares() == 2, "a tap while Play's sheet is open asks nothing");
    }

    private static void freeAtTapAsksNothing() {
        Rig rig = Rig.paidReady();
        rig.pro.purchasesAvailable = false; // Verified policy already free, notification not yet delivered.
        rig.checkout.purchase(rig.activity, rig.offer());
        check(rig.newPurchasePrepares() == 1, "no new-purchase prepare is sent while free");
        check(rig.billing.launches.isEmpty(), "Play is never opened while free");
        check(rig.checkout.state().free && !rig.checkout.state().canPurchase(), "the screen shows free access");
    }

    private static void restoreNotGated() {
        Rig paid = Rig.paidReady();
        paid.billing.purchases = Collections.singletonList(purchase(OBFUSCATED, true));
        paid.checkout.restore();
        ZenlyPro.Prepare restore = paid.lastPrepare();
        check(restore != null && restore.restore, "paid Restore fetches a fresh restore context");
        check(paid.newPurchasePrepares() == 1, "paid Restore sends no new-purchase admission");
        if (restore == null || !restore.restore) return;
        restore.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        check(GooglePlaySync.attaches.size() == 1 && GooglePlaySync.attaches.get(0).restore, "paid Restore re-sends the owned purchase");
        check(paid.billing.launches.isEmpty(), "Restore never opens a purchase sheet");

        Rig free = Rig.freeReady();
        free.billing.purchases = Collections.singletonList(purchase(OBFUSCATED, true));
        free.checkout.restore();
        ZenlyPro.Prepare freeRestore = free.lastPrepare();
        check(freeRestore != null && freeRestore.restore, "free Restore fetches a fresh restore context");
        check(free.newPurchasePrepares() == 0 && free.billing.offerQueries == 0, "free never sends a new-purchase prepare or queries offers");
        if (freeRestore == null || !freeRestore.restore) return;
        freeRestore.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        check(GooglePlaySync.attaches.size() == 1 && GooglePlaySync.attaches.get(0).restore, "free Restore re-sends the owned purchase");
        check(free.billing.launches.isEmpty(), "free Restore never opens a purchase sheet");
    }

    private static void admittedPurchaseFulfilledAfterFree() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        rig.admitIfAsked(account(OBFUSCATED, MONTHLY_PLAN));
        check(rig.billing.launches.size() == 1, "the admitted purchase opened Play");
        int prepares = rig.newPurchasePrepares();
        rig.pro.purchasesAvailable = false;
        rig.checkout.onMonetizationChanged();
        check(rig.checkout.state().free && rig.checkout.state().inFlight(), "free while Play's sheet is open keeps the purchase in flight");
        rig.billing.purchases = Collections.singletonList(purchase(OBFUSCATED, false));
        GooglePlaySync.deliver(GooglePlayModel.Problem.NONE, rig.billing.purchases);
        check(GooglePlaySync.attaches.size() == 1 && !GooglePlaySync.attaches.get(0).restore,
                "the already-paid purchase is still sent for verification");
        check(GooglePlaySync.attaches.size() == 1 && GooglePlaySync.attaches.get(0).binding.catalog.obfuscatedAccountId.equals(OBFUSCATED),
                "it attaches to the same account");
        check(rig.newPurchasePrepares() == prepares, "fulfillment asks for no admission");
        if (GooglePlaySync.attaches.size() != 1) return;
        GooglePlaySync.attaches.get(0).callback.onSuccess(GooglePlayModel.SubscriptionState.ACTIVE);
        GooglePlayModel.State state = rig.checkout.state();
        check(!state.inFlight() && state.free && !state.canPurchase(), "afterwards the free screen sells nothing");
        check(state.confirmed == GooglePlayModel.SubscriptionState.ACTIVE, "the confirmed subscription is shown");
    }

    private static void alreadyOwnedRestores() {
        Rig rig = Rig.paidReady();
        rig.billing.launchResult = GooglePlayModel.Problem.ALREADY_OWNED;
        rig.checkout.purchase(rig.activity, rig.offer());
        rig.admitIfAsked(account(OBFUSCATED, MONTHLY_PLAN));
        ZenlyPro.Prepare last = rig.lastPrepare();
        check(last != null && last.restore, "ITEM_ALREADY_OWNED restores through a fresh restore context");
        check(rig.newPurchasePrepares() == 2, "exactly the load and the one admission: no second admission for the owned item");
        check(rig.billing.launches.size() == 1, "only the one Play launch");
    }

    /**
     * L1: another Telegram user is in the slot (or signed out) while this stale screen still shows a
     * Subscribe row. The tap itself must start nothing: no new-purchase prepare queued under the new
     * identity, no purchasing state, no Play sheet.
     */
    private static void accountChangedBeforeTap() {
        for (long now : new long[]{OTHER_TELEGRAM_ID, 0}) {
            Rig rig = Rig.paidReady();
            UserConfig.setClientUserId(ACCOUNT, now);
            rig.checkout.purchase(rig.activity, rig.offer());
            String name = now == 0 ? "signed out" : "other user";
            check(rig.newPurchasePrepares() == 1, name + ": the tap queues no new-purchase prepare");
            check(rig.checkout.state().stage != GooglePlayModel.Stage.PURCHASING, name + ": the screen does not enter purchasing");
            check(rig.billing.launches.isEmpty(), name + ": Play is never opened");
        }
    }

    /**
     * L2: the verified policy turned free while the admission ran (notified or not yet), then the
     * admission fails (409 monetization_disabled or an outage). The screen must not rest blocked in
     * free + UNAVAILABLE: it reloads the free restore context ({"purpose":"restore"}) and never sends
     * another new-purchase prepare. A 409 while the snapshot is still paid keeps the existing refusal
     * (serverRefusalBlocksLaunch).
     */
    private static void freeThenAdmissionError() {
        List<ZenlyPro.Error> errors = Arrays.asList(
                new ZenlyPro.Error("monetization_disabled", "Pro is free right now.", 409, 0),
                new ZenlyPro.Error("google_unavailable", "Unavailable", 503, 0));
        for (boolean notified : new boolean[]{true, false}) {
            for (ZenlyPro.Error error : errors) {
                Rig rig = Rig.paidReady();
                rig.checkout.purchase(rig.activity, rig.offer());
                ZenlyPro.Prepare admission = rig.admission();
                if (admission == null) return;
                rig.pro.purchasesAvailable = false;
                if (notified) rig.checkout.onMonetizationChanged();
                admission.callback.onError(error);
                String name = (notified ? "notified" : "not yet notified") + " + " + error.code;
                ZenlyPro.Prepare last = rig.lastPrepare();
                check(last != null && last.restore && last != admission, name + ": a fresh restore context is requested");
                check(rig.newPurchasePrepares() == 2, name + ": no further new-purchase prepare");
                check(rig.billing.launches.isEmpty(), name + ": Play is never opened");
                if (last == null || !last.restore || last == admission) continue;
                last.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
                GooglePlayModel.State state = rig.checkout.state();
                check(state.free && state.stage == GooglePlayModel.Stage.READY && state.problem == GooglePlayModel.Problem.NONE,
                        name + ": the screen rests as free access, not blocked");
                check(!state.canPurchase() && state.canRestore(), name + ": nothing to buy, Restore available");
                check(rig.billing.offerQueries == 1, name + ": free reload queries no offers");
            }
        }
    }

    /** Play reports USER_CANCELED before the admission answer: the purchase already ended, so its late success opens nothing. */
    private static void canceledBeforeAdmissionAnswer() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        ZenlyPro.Prepare admission = rig.admission();
        if (admission == null) return;
        GooglePlaySync.deliver(GooglePlayModel.Problem.USER_CANCELED, Collections.<GooglePlayModel.Purchase>emptyList());
        check(rig.checkout.state().stage != GooglePlayModel.Stage.PURCHASING, "the cancel ends the purchasing stage");
        admission.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        check(rig.billing.launches.isEmpty(), "the late admission success opens no Play sheet");
        check(rig.checkout.state().canPurchase(), "the user may tap Subscribe again");
    }

    /** Session loss reported by the admission itself: linked keeps Try again; unlinked returns to linking. */
    private static void sessionChangedAtAdmission() {
        Rig linked = Rig.paidReady();
        linked.checkout.purchase(linked.activity, linked.offer());
        ZenlyPro.Prepare first = linked.admission();
        if (first == null) return;
        first.callback.onError(new ZenlyPro.Error("session_changed", "Changed", 0, 0));
        GooglePlayModel.State state = linked.checkout.state();
        check(linked.billing.launches.isEmpty(), "linked session_changed: Play is never opened");
        check(state.stage == GooglePlayModel.Stage.UNAVAILABLE && state.problem == GooglePlayModel.Problem.SESSION_CHANGED
                && state.canRetry() && !state.canPurchase(), "linked session_changed: Try again, nothing to buy");

        Rig unlinked = Rig.paidReady();
        unlinked.checkout.purchase(unlinked.activity, unlinked.offer());
        ZenlyPro.Prepare second = unlinked.admission();
        if (second == null) return;
        unlinked.pro.linked = false; // The 401 cleared the refresh token before its answer ran.
        second.callback.onError(new ZenlyPro.Error("unauthenticated", "Unauthenticated", 401, 0));
        check(unlinked.billing.launches.isEmpty(), "401 unlinked: Play is never opened");
        check(unlinked.checkout.state().stage == GooglePlayModel.Stage.LINK_REQUIRED, "401 unlinked: the screen returns to linking");
        check(unlinked.pro.prepares.size() == 2, "401 unlinked: linking sends no further prepare");
    }

    // ---- Pro funnel ------------------------------------------------------------------------

    private static void funnelPurchaseSuccess() {
        Rig rig = Rig.paidReady();
        check(rig.sink.calls.isEmpty(), "loading the screen reports nothing");
        rig.checkout.purchase(rig.activity, rig.offer());
        check(rig.sink.calls.isEmpty(), "no start before Play's sheet opens");
        rig.admitIfAsked(account(OBFUSCATED, MONTHLY_PLAN));
        check(rig.sink.calls.equals(Collections.singletonList("start 0 zenlygram_pro monthly")), "start once Play's sheet opened: " + rig.sink.calls);
        rig.billing.purchases = Collections.singletonList(purchase(OBFUSCATED, false));
        GooglePlaySync.deliver(GooglePlayModel.Problem.NONE, rig.billing.purchases);
        check(rig.sink.calls.size() == 1, "nothing more until the backend answers the attach");
        if (GooglePlaySync.attaches.size() != 1) return;
        GooglePlaySync.attaches.get(0).callback.onSuccess(GooglePlayModel.SubscriptionState.ACTIVE);
        check(rig.sink.calls.equals(Arrays.asList("start 0 zenlygram_pro monthly", "success 0 zenlygram_pro monthly trial=false")),
                "success after the attach: " + rig.sink.calls);
        GooglePlaySync.attaches.get(0).callback.onSuccess(GooglePlayModel.SubscriptionState.ACTIVE);
        check(rig.sink.calls.size() == 2, "a repeated answer reports no second outcome");
    }

    private static void funnelTrialSuccess() {
        Rig rig = new Rig(true, MONTHLY_TRIAL);
        rig.checkout.purchase(rig.activity, rig.offer());
        rig.admitIfAsked(account(OBFUSCATED, MONTHLY_PLAN));
        rig.billing.purchases = Collections.singletonList(purchase(OBFUSCATED, false));
        GooglePlaySync.deliver(GooglePlayModel.Problem.NONE, rig.billing.purchases);
        if (GooglePlaySync.attaches.size() != 1) { check(false, "the trial purchase was attached"); return; }
        GooglePlaySync.attaches.get(0).callback.onSuccess(GooglePlayModel.SubscriptionState.ACTIVE);
        check(rig.sink.last().equals("success 0 zenlygram_pro monthly trial=true"), "a free intro phase is a trial: " + rig.sink.calls);
    }

    private static void funnelCanceled() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        rig.admitIfAsked(account(OBFUSCATED, MONTHLY_PLAN));
        GooglePlaySync.deliver(GooglePlayModel.Problem.USER_CANCELED, Collections.<GooglePlayModel.Purchase>emptyList());
        check(rig.sink.calls.equals(Arrays.asList("start 0 zenlygram_pro monthly", "fail 0 zenlygram_pro monthly USER_CANCELED null")),
                "start then USER_CANCELED without a server code: " + rig.sink.calls);
    }

    private static void funnelAdmissionRefused() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        ZenlyPro.Prepare admission = rig.admission();
        if (admission == null) return;
        admission.callback.onError(new ZenlyPro.Error("monetization_disabled", "Pro is free right now.", 409, 0));
        check(rig.sink.calls.equals(Collections.singletonList("fail 0 zenlygram_pro monthly MONETIZATION_DISABLED monetization_disabled")),
                "the refusal is one failure with the backend code and no start: " + rig.sink.calls);

        Rig launchFailed = Rig.paidReady();
        launchFailed.billing.launchResult = GooglePlayModel.Problem.BILLING_UNSUPPORTED;
        launchFailed.checkout.purchase(launchFailed.activity, launchFailed.offer());
        launchFailed.admitIfAsked(account(OBFUSCATED, MONTHLY_PLAN));
        check(launchFailed.sink.calls.equals(Collections.singletonList("fail 0 zenlygram_pro monthly BILLING_UNSUPPORTED null")),
                "a refused launch is one failure without a start or server code: " + launchFailed.sink.calls);
    }

    private static void funnelPending() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        rig.admitIfAsked(account(OBFUSCATED, MONTHLY_PLAN));
        rig.billing.purchases = Collections.singletonList(new GooglePlayModel.Purchase(PURCHASE_TOKEN, Collections.singletonList("zenlygram_pro"),
                GooglePlayModel.PLAY_PENDING, OBFUSCATED, false));
        GooglePlaySync.deliver(GooglePlayModel.Problem.NONE, rig.billing.purchases);
        check(rig.sink.last().equals("fail 0 zenlygram_pro monthly PENDING null"), "a pending Play purchase reports PENDING: " + rig.sink.calls);
        check(GooglePlaySync.attaches.isEmpty(), "a pending purchase is not attached");
        check(GooglePlayCheckout.PENDING.equals("PENDING"), "the problem name matches ProFunnel.PENDING");
    }

    private static void funnelAttachRefused() {
        Rig rig = Rig.paidReady();
        rig.checkout.purchase(rig.activity, rig.offer());
        rig.admitIfAsked(account(OBFUSCATED, MONTHLY_PLAN));
        rig.billing.purchases = Collections.singletonList(purchase(OBFUSCATED, false));
        GooglePlaySync.deliver(GooglePlayModel.Problem.NONE, rig.billing.purchases);
        if (GooglePlaySync.attaches.size() != 1) { check(false, "the purchase was attached"); return; }
        GooglePlaySync.attaches.get(0).callback.onError(new ZenlyPro.Error("purchase_account_mismatch", "Mismatch", 409, 0));
        check(rig.sink.last().equals("fail 0 zenlygram_pro monthly ACCOUNT_MISMATCH purchase_account_mismatch"),
                "the attach refusal carries the backend code: " + rig.sink.calls);
    }

    private static void funnelRestore() {
        Rig restored = Rig.paidReady();
        restored.billing.purchases = Collections.singletonList(purchase(OBFUSCATED, true));
        restored.checkout.restore();
        ZenlyPro.Prepare context = restored.lastPrepare();
        if (context == null || !context.restore) { check(false, "restore asked for a context"); return; }
        context.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        if (GooglePlaySync.attaches.size() != 1) { check(false, "restore re-sent the purchase"); return; }
        GooglePlaySync.attaches.get(0).callback.onSuccess(GooglePlayModel.SubscriptionState.ACTIVE);
        check(restored.sink.calls.equals(Collections.singletonList("restore 0 restored null")), "restored: " + restored.sink.calls);

        Rig nothing = Rig.freeReady();
        nothing.checkout.restore();
        ZenlyPro.Prepare empty = nothing.lastPrepare();
        if (empty == null || !empty.restore) { check(false, "free restore asked for a context"); return; }
        empty.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        check(nothing.sink.calls.equals(Collections.singletonList("restore 0 nothing null")), "nothing to restore: " + nothing.sink.calls);

        Rig failed = Rig.paidReady();
        failed.checkout.restore();
        ZenlyPro.Prepare refused = failed.lastPrepare();
        if (refused == null || !refused.restore) { check(false, "restore asked for a context"); return; }
        refused.callback.onError(new ZenlyPro.Error("rate_limited", "Wait", 429, 30));
        check(failed.sink.calls.equals(Collections.singletonList("restore 0 failed rate_limited")), "failed with the code: " + failed.sink.calls);
    }

    private static void funnelAlreadyOwned() {
        Rig rig = Rig.paidReady();
        rig.billing.launchResult = GooglePlayModel.Problem.ALREADY_OWNED;
        rig.billing.purchases = Collections.singletonList(purchase(OBFUSCATED, true));
        rig.checkout.purchase(rig.activity, rig.offer());
        rig.admitIfAsked(account(OBFUSCATED, MONTHLY_PLAN));
        ZenlyPro.Prepare context = rig.lastPrepare();
        if (context == null || !context.restore) { check(false, "ALREADY_OWNED asked for a restore context"); return; }
        context.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        if (GooglePlaySync.attaches.size() != 1) { check(false, "the owned purchase was re-sent"); return; }
        GooglePlaySync.attaches.get(0).callback.onSuccess(GooglePlayModel.SubscriptionState.ACTIVE);
        check(rig.sink.calls.equals(Collections.singletonList("restore 0 restored null")),
                "no purchase start or failure, one restore outcome: " + rig.sink.calls);
    }

    private static void funnelSilentCases() {
        Rig closed = Rig.paidReady();
        closed.checkout.purchase(closed.activity, closed.offer());
        closed.admitIfAsked(account(OBFUSCATED, MONTHLY_PLAN));
        closed.checkout.close();
        GooglePlaySync.deliver(GooglePlayModel.Problem.USER_CANCELED, Collections.<GooglePlayModel.Purchase>emptyList());
        check(closed.sink.calls.equals(Collections.singletonList("start 0 zenlygram_pro monthly")), "closing reports no outcome: " + closed.sink.calls);

        Rig background = Rig.paidReady();
        background.billing.purchases = Collections.singletonList(purchase(OBFUSCATED, false));
        background.pro.pendingAttaches = Collections.singleton(GooglePlayAttachKeys.tokenHash(PURCHASE_TOKEN));
        background.checkout.reload();
        ZenlyPro.Prepare load = background.lastPrepare();
        if (load != null) load.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        check(!GooglePlaySync.attaches.isEmpty(), "the unverified purchase is attached in the background");
        for (GooglePlaySync.Attach attach : GooglePlaySync.attaches) attach.callback.onSuccess(GooglePlayModel.SubscriptionState.ACTIVE);
        check(background.sink.calls.isEmpty(), "an attach the user did not start reports nothing: " + background.sink.calls);

        ProFunnelSink.Holder.set(null);
        check(ProFunnelSink.Holder.get() == ProFunnelSink.NONE, "clearing the sink restores the no-op");
    }

    /** Records every ProFunnelSink call as one line. */
    private static final class RecordingSink implements ProFunnelSink {
        final List<String> calls = new ArrayList<>();

        String last() { return calls.isEmpty() ? "" : calls.get(calls.size() - 1); }

        @Override public void purchaseStart(int account, String productId, String basePlanId) {
            calls.add("start " + account + " " + productId + " " + basePlanId);
        }

        @Override public void purchaseSuccess(int account, String productId, String basePlanId, boolean trial) {
            calls.add("success " + account + " " + productId + " " + basePlanId + " trial=" + trial);
        }

        @Override public void purchaseFail(int account, String productId, String basePlanId, String problemName, String serverCode) {
            calls.add("fail " + account + " " + productId + " " + basePlanId + " " + problemName + " " + serverCode);
        }

        @Override public void restore(int account, String outcome, String serverCode) {
            calls.add("restore " + account + " " + outcome + " " + serverCode);
        }
    }

    // ---- Rig -------------------------------------------------------------------------------

    /** One signed-in, linked account's checkout screen over the fakes; every scenario starts from reset. */
    private static final class Rig {
        final ZenlyPro pro;
        final GooglePlayBilling billing;
        final GooglePlayCheckout checkout;
        final Activity activity = new Activity();
        final RecordingSink sink = new RecordingSink();

        private Rig(boolean paid) { this(paid, MONTHLY); }

        private Rig(boolean paid, GooglePlayModel.Offer offered) {
            ProFunnelSink.Holder.set(sink);
            ZenlyPro.reset();
            GooglePlayBilling.reset();
            GooglePlaySync.reset();
            UserConfig.reset();
            UserConfig.setClientUserId(ACCOUNT, TELEGRAM_ID);
            pro = ZenlyPro.get(ACCOUNT);
            pro.purchasesAvailable = paid;
            billing = GooglePlayBilling.get();
            billing.offers = Collections.singletonList(offered);
            checkout = new GooglePlayCheckout(ACCOUNT, TELEGRAM_ID, state -> { });
            checkout.reload();
            ZenlyPro.Prepare load = lastPrepare();
            if (load == null || load.restore == paid) throw new IllegalStateException("unexpected screen load prepare");
            load.callback.onSuccess(account(OBFUSCATED, MONTHLY_PLAN));
        }

        /** Paid screen loaded through the real reload: one offer purchasable. */
        static Rig paidReady() {
            Rig rig = new Rig(true);
            if (!rig.checkout.state().canPurchase()) throw new IllegalStateException("paid screen did not become purchasable");
            return rig;
        }

        /** Free screen loaded through the real reload: restore context only, nothing purchasable. */
        static Rig freeReady() {
            Rig rig = new Rig(false);
            if (rig.checkout.state().canPurchase() || !rig.checkout.state().canRestore()) throw new IllegalStateException("free screen state");
            return rig;
        }

        GooglePlayModel.Offer offer() { return checkout.state().purchasableOffers().get(0); }

        ZenlyPro.Prepare lastPrepare() { return pro.prepares.isEmpty() ? null : pro.prepares.get(pro.prepares.size() - 1); }

        int newPurchasePrepares() {
            int count = 0;
            for (ZenlyPro.Prepare prepare : pro.prepares) if (!prepare.restore) count++;
            return count;
        }

        /** The purchase admission: a new-purchase prepare sent after the screen load's own one. */
        ZenlyPro.Prepare admission() {
            List<ZenlyPro.Prepare> fresh = new ArrayList<>();
            for (ZenlyPro.Prepare prepare : pro.prepares) if (!prepare.restore) fresh.add(prepare);
            boolean present = fresh.size() >= 2;
            check(present, "a fresh new-purchase admission prepare was requested");
            return present ? fresh.get(fresh.size() - 1) : null;
        }

        /** Answers a pending admission if this build sends one (fulfillment scenarios hold either way). */
        void admitIfAsked(ZenlyPro.PlayAccount answer) {
            ZenlyPro.Prepare last = lastPrepare();
            if (last != null && !last.restore && newPurchasePrepares() >= 2 && billing.launches.isEmpty()) last.callback.onSuccess(answer);
        }
    }

    // ---- Fixtures and checks ---------------------------------------------------------------

    private static ZenlyPro.PlayAccount account(String obfuscated, GooglePlayModel.Plan plan) {
        return new ZenlyPro.PlayAccount(new GooglePlayModel.Catalog(obfuscated, Collections.singletonList(plan)), TELEGRAM_ID, "account-1");
    }

    private static GooglePlayModel.Purchase purchase(String obfuscated, boolean acknowledged) {
        return new GooglePlayModel.Purchase(PURCHASE_TOKEN, Collections.singletonList("zenlygram_pro"), GooglePlayModel.PLAY_PURCHASED,
                obfuscated, acknowledged);
    }

    private static String repeat(char c) {
        char[] value = new char[64];
        Arrays.fill(value, c);
        return new String(value);
    }

    private interface Scenario { void run(); }

    private static void run(String name, Scenario scenario) {
        int before = failures;
        try {
            scenario.run();
        } catch (RuntimeException | AssertionError e) {
            failures++;
            System.out.println("FAIL " + name + ": unexpected " + e);
        }
        System.out.println((failures == before ? "PASS " : "FAIL ") + name);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (condition) return;
        failures++;
        System.out.println("  failed check: " + name);
    }
}
