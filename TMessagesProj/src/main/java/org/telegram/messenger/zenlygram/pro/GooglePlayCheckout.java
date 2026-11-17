package org.telegram.messenger.zenlygram.pro;

import android.app.Activity;
import android.net.Uri;

import org.telegram.messenger.UserConfig;

import java.util.Collections;
import java.util.List;

/**
 * Lifecycle-safe Google Play checkout for one Pro screen and one Telegram identity. UI-thread
 * confined. Every async step is tagged with an operation number and the identity it started under;
 * results arriving after close, a newer operation, logout or an account switch are dropped (a
 * completed purchase is then attached by GooglePlaySync to the account it is bound to).
 */
public final class GooglePlayCheckout {
    public interface Listener { void onStateChanged(GooglePlayModel.State state); }

    private static final String MANAGE_URL = "https://play.google.com/store/account/subscriptions";

    private final int account;
    private final long telegramId;
    private final ZenlyPro pro;
    private final Listener listener;
    private final GooglePlaySync.FlowListener flow = this::onPurchaseFlow;
    private GooglePlayModel.State state;
    private ZenlyPro.PlayAccount binding;
    private int operation;
    private boolean closed;
    /**
     * Pro funnel (ProFunnelSink, design 50 §11.4): the offer of the purchase this screen runs, from the Subscribe tap
     * until its outcome is reported once (attached, failed, pending); null otherwise.
     */
    private GooglePlayModel.Offer funnelOffer;
    /** A restore (the user's, or ITEM_ALREADY_OWNED's) runs and reports its outcome once. */
    private boolean funnelRestore;

    public GooglePlayCheckout(int account, long telegramId, Listener listener) {
        this.account = account;
        this.telegramId = telegramId;
        this.pro = ZenlyPro.get(account);
        this.listener = listener;
        this.state = GooglePlayModel.State.initial(playBuild(), false, false, false);
    }

    /** Immutable per APK: only the Play flavor exposes Google Play purchase controls. */
    public static boolean playBuild() { return GooglePlayBilling.enabled(); }

    public GooglePlayModel.State state() { return state; }

    /** Screen resumed: keep an in-flight purchase/verification, otherwise reload from Play. */
    public void resume() {
        if (closed || state.inFlight()) return;
        reload();
    }

    /**
     * Reloads the catalog, real Play prices and purchases (also after linking changes). Under free
     * access it loads only the restore context ({"purpose":"restore"}) and the owned purchases: no
     * new-purchase prepare, no product/offer query, nothing purchasable.
     */
    public void reload() {
        if (closed) return;
        ZenlyPro.Snapshot snapshot = pro.snapshot();
        boolean signedIn = telegramId > 0 && UserConfig.getInstance(account).getClientUserId() == telegramId;
        boolean free = !snapshot.purchasesAvailable;
        binding = null;
        endFunnel();
        final int expected = ++operation;
        GooglePlayModel.State initial = GooglePlayModel.State.initial(playBuild(), snapshot.configured, signedIn, snapshot.linked);
        publish(free ? initial.freeAccess() : initial);
        if (state.stage != GooglePlayModel.Stage.LOADING) return;
        GooglePlaySync.register(flow);
        pro.prepareGooglePlay(free, new ZenlyPro.Callback<ZenlyPro.PlayAccount>() {
            @Override public void onSuccess(ZenlyPro.PlayAccount value) {
                if (!current(expected)) return;
                binding = value;
                loadStore(expected, value);
            }
            @Override public void onError(ZenlyPro.Error error) {
                if (current(expected)) fail(GooglePlayModel.Problem.fromServer(error.code, error.httpStatus));
            }
        });
    }

    private void loadStore(int expected, ZenlyPro.PlayAccount value) {
        GooglePlayBilling billing = GooglePlayBilling.get();
        billing.connect((ready, problem) -> {
            if (!current(expected)) return;
            if (!ready) { fail(problem); return; }
            if (state.free) {
                publish(state.offersLoaded(Collections.<GooglePlayModel.Offer>emptyList()));
                if (state.storeReady) queryPurchases(expected, false, value);
                return;
            }
            billing.queryOffers(value.catalog, (offers, offerProblem) -> {
                if (!current(expected)) return;
                // A failed price lookup on a working connection still recovers owned purchases.
                publish(offerProblem == GooglePlayModel.Problem.NONE ? state.offersLoaded(offers) : state.offersFailed(offerProblem));
                if (state.storeReady) queryPurchases(expected, false, value);
            });
        });
    }

    /**
     * Owned/pending purchases decide which controls show; unverified purchases are attached now.
     * {@code bound} is the server context this operation fetched (the screen's binding, or a restore's
     * own fresh restore context); a newer operation drops the answer.
     */
    private void queryPurchases(int expected, boolean restore, ZenlyPro.PlayAccount bound) {
        GooglePlayBilling.get().queryPurchases((purchases, problem) -> {
            if (!current(expected)) return;
            if (problem != GooglePlayModel.Problem.NONE) { fail(problem); return; }
            GooglePlayModel.Summary summary = GooglePlayModel.Summary.of(purchases, bound.catalog);
            publish(state.purchasesLoaded(summary));
            List<GooglePlayModel.Purchase> eligible = GooglePlayModel.toAttach(purchases, bound.catalog, restore, pro.pendingGoogleAttaches());
            if (!eligible.isEmpty()) {
                attach(expected, purchases, restore, bound);
            } else if (restore) {
                // A pending Play purchase restores nothing yet: the backend grants nothing until it is purchased.
                reportRestore("nothing", null);
                publish(summary.pending ? state.pending() : state.settle(summary).failed(GooglePlayModel.Problem.NOTHING_TO_RESTORE));
            } else if (summary.pending) {
                // Our purchase (if one runs) came back pending from Play.
                reportPurchaseFail(PENDING, null);
            }
        });
    }

    /**
     * Subscribe: neither the cached policy nor the prepare this screen loaded with authorizes a new
     * purchase. A fresh new-purchase prepare ({}, refused by the server with 409 monetization_disabled
     * while free) must succeed first; Play's sheet opens only if that answer is still current (same
     * operation, identity, open screen, purchasing), the verified policy is still paid, the account is
     * still linked (a 401 or unlink may land after the answer was queued), and it binds the same
     * obfuscated account with the offer's plan. A tap from a closed screen or for another Telegram
     * identity starts nothing. A refusal or failure is shown, never retried; if the verified policy
     * turned free meanwhile the screen reloads its free restore context instead. Restore and
     * fulfillment of an already-open purchase do not pass through here.
     */
    public void purchase(Activity activity, GooglePlayModel.Offer offer) {
        if (!current(operation) || binding == null || offer == null || !state.canPurchase() || !state.purchasableOffers().contains(offer)) return;
        // The verified policy may have turned free since this screen rendered its offers.
        if (!pro.snapshot().purchasesAvailable) { publish(state.freeAccess()); return; }
        final int expected = ++operation; // Any queued query on this screen is superseded by the purchase.
        final ZenlyPro.PlayAccount screen = binding;
        endFunnel();
        funnelOffer = offer;
        publish(state.purchasing());
        pro.prepareGooglePlay(false, new ZenlyPro.Callback<ZenlyPro.PlayAccount>() {
            @Override public void onSuccess(ZenlyPro.PlayAccount fresh) {
                // A Play flow event may have settled or superseded this purchase meanwhile.
                if (!current(expected) || state.stage != GooglePlayModel.Stage.PURCHASING) return;
                // Turned free, lost its link, or the server now binds another account or no longer sells
                // this plan: start over (unlinked reloads straight to linking).
                ZenlyPro.Snapshot now = pro.snapshot();
                if (!now.purchasesAvailable || !now.linked || !admits(screen, fresh, offer)) {
                    reportPurchaseFail((!now.purchasesAvailable ? GooglePlayModel.Problem.MONETIZATION_DISABLED
                            : !now.linked ? GooglePlayModel.Problem.SESSION_CHANGED : GooglePlayModel.Problem.PURCHASE_REJECTED).name(), null);
                    reload();
                    return;
                }
                binding = fresh;
                GooglePlayModel.Problem problem = GooglePlayBilling.get().launch(activity, fresh.catalog, offer);
                if (problem == GooglePlayModel.Problem.NONE) {
                    ProFunnelSink.Holder.get().purchaseStart(account, offer.productId, offer.basePlanId);
                } else if (state.restoresAfter(problem)) {
                    restoreAfterOwned();
                } else {
                    reportPurchaseFail(problem.name(), null);
                    publish(state.failed(problem));
                }
            }
            @Override public void onError(ZenlyPro.Error error) {
                if (!current(expected)) return;
                // Locally verified free now: recover through the restore context, never another new-purchase prepare.
                if (!pro.snapshot().purchasesAvailable) {
                    reportPurchaseFail(GooglePlayModel.Problem.fromServer(error.code, error.httpStatus).name(), error.code);
                    reload();
                    return;
                }
                fail(GooglePlayModel.Problem.fromServer(error.code, error.httpStatus), error.code);
            }
        });
    }

    /** The fresh admission binds the screen's obfuscated account and still configures the offer's plan. */
    private static boolean admits(ZenlyPro.PlayAccount screen, ZenlyPro.PlayAccount fresh, GooglePlayModel.Offer offer) {
        if (fresh == null || !fresh.catalog.obfuscatedAccountId.equals(screen.catalog.obfuscatedAccountId)) return false;
        for (GooglePlayModel.Plan plan : fresh.catalog.plans) {
            if (plan.matches(offer.productId, offer.basePlanId)) return true;
        }
        return false;
    }

    /**
     * The user's Restore: re-sends every purchase bound to this account, acknowledged ones included, so
     * the backend re-issues the signed entitlement. Ignored unless the model allows it (never while
     * loading, purchasing or verifying), so repeated taps start one operation. It works without a
     * connected store too (e.g. after a refused new-purchase prepare).
     */
    public void restore() {
        if (closed) return;
        GooglePlayModel.State next = state.restoring();
        if (next != state) startRestore(next);
    }

    /**
     * The verified monetization policy changed while this screen is open. A purchase already in Play's
     * sheet or a verification keeps running (and is still attached); free stops offering at once.
     * Otherwise the screen reloads for the new mode, so a refusal left over from the other mode clears
     * without any retry.
     */
    public void onMonetizationChanged() {
        if (closed) return;
        boolean free = !pro.snapshot().purchasesAvailable;
        if (free == state.free) return;
        if (!state.inFlight()) reload();
        else if (free) publish(state.freeAccess());
    }

    /** Play reported ITEM_ALREADY_OWNED for the purchase in flight: restore it instead. */
    private void restoreAfterOwned() {
        if (closed) return;
        startRestore(state.verifying());
    }

    /**
     * Every restore, in either mode, fetches a fresh server restore context ({"purpose":"restore"}), never
     * the purchase binding or catalog this screen holds, then reads Play's purchases with restore=true.
     * A new operation number, so results of any earlier query on this screen are dropped; the restore
     * stays VERIFYING (its intent) until its own answers arrive.
     */
    private void startRestore(GooglePlayModel.State verifying) {
        if (verifying.stage != GooglePlayModel.Stage.VERIFYING) return;
        final int expected = ++operation;
        // ITEM_ALREADY_OWNED hands a running purchase to this restore, whose outcome is reported instead.
        endFunnel();
        funnelRestore = true;
        publish(verifying);
        GooglePlaySync.register(flow);
        pro.prepareGooglePlay(true, new ZenlyPro.Callback<ZenlyPro.PlayAccount>() {
            @Override public void onSuccess(ZenlyPro.PlayAccount context) {
                if (!current(expected)) return;
                GooglePlayBilling billing = GooglePlayBilling.get();
                billing.connect((ready, problem) -> {
                    if (!current(expected)) return;
                    if (!ready) { fail(problem); return; }
                    publish(state.restoreContextReady());
                    queryPurchases(expected, true, context);
                });
            }
            @Override public void onError(ZenlyPro.Error error) {
                if (current(expected)) fail(GooglePlayModel.Problem.fromServer(error.code, error.httpStatus), error.code);
            }
        });
    }

    /** Play's subscription center; focused on this account's product only when one is known. */
    public Uri manageUri(String packageName) {
        String product = state.manage() == GooglePlayModel.Manage.SUBSCRIPTION ? state.purchases.ownedProductId : null;
        if (product == null || packageName == null) return Uri.parse(MANAGE_URL);
        return Uri.parse(MANAGE_URL).buildUpon().appendQueryParameter("sku", product)
                .appendQueryParameter("package", packageName).build();
    }

    public void close() {
        closed = true;
        operation++;
        binding = null;
        endFunnel();
        GooglePlaySync.unregister(flow);
    }

    /** Dispatches a Play purchases-updated event (that flow's purchases only) by GooglePlayModel.State.onPurchaseFlow. */
    private void onPurchaseFlow(GooglePlayModel.Problem problem, List<GooglePlayModel.Purchase> purchases) {
        if (!current(operation) || binding == null) {
            // Another identity or an unfinished load: let the bound account pick it up.
            if (problem == GooglePlayModel.Problem.NONE) GooglePlaySync.reconcile(true);
            return;
        }
        switch (state.onPurchaseFlow(problem)) {
            // ITEM_ALREADY_OWNED usually arrives here (async), not from launchBillingFlow's return value.
            case RESTORE_OWNED: restoreAfterOwned(); break;
            case FAIL:
                reportPurchaseFail(problem.name(), null);
                publish(state.failed(problem));
                break;
            case REQUERY: requery(purchases); break;
            case RECONCILE: GooglePlaySync.reconcile(true); break; // never a second attach on this screen
            default: break;
        }
    }

    /**
     * Our own purchase finished, or an outside update arrived: the screen waits (not purchasable) while
     * the full purchase list is read, and that list alone decides attach / pending / ownership. A
     * purchase bound to another Telegram account (switched while Play's sheet was open) is never moved
     * here; background reconciliation hands it to the account it is bound to.
     */
    private void requery(List<GooglePlayModel.Purchase> purchases) {
        GooglePlayModel.State waiting = state.requerying();
        if (waiting != state) publish(waiting);
        queryPurchases(++operation, false, binding);
        if (GooglePlayModel.Summary.of(purchases, binding.catalog).otherAccount) GooglePlaySync.reconcile(true);
    }

    private void attach(int expected, List<GooglePlayModel.Purchase> purchases, boolean restore, ZenlyPro.PlayAccount bound) {
        publish(state.verifying());
        GooglePlaySync.attach(account, bound, purchases, restore, new ZenlyPro.Callback<GooglePlayModel.SubscriptionState>() {
            @Override public void onSuccess(GooglePlayModel.SubscriptionState value) {
                if (!current(expected)) return;
                reportAttached(value);
                publish(value == null ? state.settle(null) : state.attached(value));
            }
            @Override public void onError(ZenlyPro.Error error) {
                if (current(expected)) fail(GooglePlayModel.Problem.fromServer(error.code, error.httpStatus), error.code);
            }
        });
    }

    private void fail(GooglePlayModel.Problem problem) { fail(problem, null); }

    /**
     * A lost session (e.g. 401 cleared the refresh token) returns the screen to linking. {@code serverCode} = the
     * backend's error code when the failure came from the server (the funnel's {@code error_code}), else null.
     */
    private void fail(GooglePlayModel.Problem problem, String serverCode) {
        reportPurchaseFail(problem == null || problem == GooglePlayModel.Problem.NONE ? GooglePlayModel.Problem.FAILED.name() : problem.name(), serverCode);
        reportRestore("failed", serverCode);
        if (problem == GooglePlayModel.Problem.SESSION_CHANGED && !pro.snapshot().linked) {
            reload();
            return;
        }
        publish(state.failed(problem));
    }

    // ---- Pro funnel (design 50 §11.4): each purchase and restore reports exactly one outcome ----

    /** The {@code problemName} ProFunnelSink.purchaseFail takes for a pending Play purchase (ProFunnel.PENDING). */
    static final String PENDING = "PENDING";

    /** Forgets a running purchase or restore without reporting it (superseded by a reload, a new tap or close). */
    private void endFunnel() {
        funnelOffer = null;
        funnelRestore = false;
    }

    private void reportPurchaseFail(String problemName, String serverCode) {
        GooglePlayModel.Offer offer = funnelOffer;
        if (offer == null) return;
        funnelOffer = null;
        ProFunnelSink.Holder.get().purchaseFail(account, offer.productId, offer.basePlanId, problemName, serverCode);
    }

    private void reportRestore(String outcome, String serverCode) {
        if (!funnelRestore) return;
        funnelRestore = false;
        ProFunnelSink.Holder.get().restore(account, outcome, serverCode);
    }

    /**
     * The backend answered an attach: a running purchase succeeded when it now grants access (trial = the offer
     * began with a free introductory phase), else it is pending or failed; a running restore restored access or nothing.
     */
    private void reportAttached(GooglePlayModel.SubscriptionState value) {
        boolean grants = value != null && value != GooglePlayModel.SubscriptionState.PENDING
                && value != GooglePlayModel.SubscriptionState.EXPIRED && value != GooglePlayModel.SubscriptionState.REVOKED;
        GooglePlayModel.Offer offer = funnelOffer;
        if (offer != null) {
            if (grants) {
                funnelOffer = null;
                ProFunnelSink.Holder.get().purchaseSuccess(account, offer.productId, offer.basePlanId, freeTrial(offer));
            } else {
                reportPurchaseFail(value == GooglePlayModel.SubscriptionState.PENDING ? PENDING : GooglePlayModel.Problem.FAILED.name(), null);
            }
        }
        reportRestore(grants ? "restored" : "nothing", null);
    }

    private static boolean freeTrial(GooglePlayModel.Offer offer) {
        for (GooglePlayModel.PricingPhase phase : offer.introductoryPhases()) {
            if (phase != null && phase.isFree()) return true;
        }
        return false;
    }

    private boolean current(int expected) {
        return !closed && expected == operation && telegramId > 0
                && UserConfig.getInstance(account).getClientUserId() == telegramId;
    }

    private void publish(GooglePlayModel.State next) {
        state = next;
        if (!closed) listener.onStateChanged(next);
    }
}
