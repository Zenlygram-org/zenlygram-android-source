package org.telegram.messenger.zenlygram.pro;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sole owner of sending Play purchase tokens to the backend. UI-thread confined.
 *
 * Play keeps unacknowledged purchases queryable and refunds them after three days, so Play itself is
 * the durable retry queue: every foreground re-queries it and attaches purchased, unacknowledged
 * tokens to whichever linked Telegram account the backend bound them to (by obfuscated account id).
 * Nothing is stored locally and nothing is granted locally. ZenlyPro serializes requests on one
 * worker and the backend handles concurrent attach of one token, so overlapping attaches are harmless.
 */
public final class GooglePlaySync {
    /** The visible checkout screen receives Play purchase-flow results while it is registered. */
    interface FlowListener { void onPurchaseFlow(GooglePlayModel.Problem problem, List<GooglePlayModel.Purchase> purchases); }

    private static final long FOREGROUND_INTERVAL_MS = 60000;
    private static FlowListener checkout;
    private static long lastForegroundElapsed = -FOREGROUND_INTERVAL_MS;
    private static boolean reconciling, installed;

    private GooglePlaySync() { }

    /** Called from ZenlyPro.refreshIfNeeded() on app foreground; a no-op in the direct APK. */
    static void onForeground() {
        if (!GooglePlayBilling.enabled()) return;
        AndroidUtilities.runOnUIThread(() -> reconcile(false));
    }

    static void register(FlowListener listener) {
        install();
        checkout = listener;
    }

    static void unregister(FlowListener listener) {
        if (checkout == listener) checkout = null;
    }

    private static void install() {
        if (installed) return;
        installed = true;
        GooglePlayBilling.get().setPurchaseListener(GooglePlaySync::onPurchasesUpdated);
    }

    private static void onPurchasesUpdated(GooglePlayModel.Problem problem, List<GooglePlayModel.Purchase> purchases) {
        if (checkout != null) {
            checkout.onPurchaseFlow(problem, purchases);
            return;
        }
        // Out-of-app completion (e.g. a pending payment cleared) with no Pro screen open.
        if (problem == GooglePlayModel.Problem.NONE) reconcile(true);
    }

    /**
     * Re-checks Play for purchases still awaiting the backend, across all linked accounts: unverified
     * ones, and acknowledged ones whose attach some linked binding never resolved (a lost answer after
     * the backend committed); those are re-sent with their pending key so the backend replays them.
     */
    static void reconcile(boolean force) {
        long elapsed = SystemClock.elapsedRealtime();
        if (reconciling || (!force && elapsed - lastForegroundElapsed < FOREGROUND_INTERVAL_MS)) return;
        List<Integer> accounts = linkedAccounts();
        if (accounts.isEmpty()) return;
        Set<String> pending = new HashSet<>();
        for (int account : accounts) pending.addAll(ZenlyPro.get(account).pendingGoogleAttaches());
        lastForegroundElapsed = elapsed;
        reconciling = true;
        install();
        GooglePlayBilling billing = GooglePlayBilling.get();
        billing.connect((ready, problem) -> {
            if (!ready) { reconciling = false; return; }
            billing.queryPurchases((purchases, queryProblem) -> {
                List<GooglePlayModel.Purchase> waiting = new ArrayList<>();
                if (queryProblem == GooglePlayModel.Problem.NONE) {
                    for (GooglePlayModel.Purchase purchase : purchases) {
                        if (GooglePlayModel.awaitsBackend(purchase, pending)) waiting.add(purchase);
                    }
                }
                tryAccounts(accounts, 0, waiting);
            });
        });
    }

    /**
     * Each account's restore-purpose prepare reveals its existing obfuscated id (never creating a
     * binding, in either mode); a purchase attaches only to its own account.
     */
    private static void tryAccounts(List<Integer> accounts, int index, List<GooglePlayModel.Purchase> waiting) {
        if (index >= accounts.size() || waiting.isEmpty()) { reconciling = false; return; }
        final int account = accounts.get(index);
        ZenlyPro.get(account).prepareGooglePlay(true, new ZenlyPro.Callback<ZenlyPro.PlayAccount>() {
            @Override public void onSuccess(ZenlyPro.PlayAccount binding) {
                List<GooglePlayModel.Purchase> others = new ArrayList<>();
                for (GooglePlayModel.Purchase purchase : waiting) {
                    if (GooglePlayModel.classify(purchase, binding.catalog) == GooglePlayModel.Disposition.OTHER_ACCOUNT) others.add(purchase);
                }
                attach(account, binding, waiting, false, new ZenlyPro.Callback<GooglePlayModel.SubscriptionState>() {
                    @Override public void onSuccess(GooglePlayModel.SubscriptionState value) { tryAccounts(accounts, index + 1, others); }
                    @Override public void onError(ZenlyPro.Error error) { tryAccounts(accounts, index + 1, others); }
                });
            }
            @Override public void onError(ZenlyPro.Error error) { tryAccounts(accounts, index + 1, waiting); }
        });
    }

    /**
     * Attaches, one by one, the purchases this account may attach (restore adds already-verified ones;
     * an unresolved operation of this binding re-sends its acknowledged purchase). restore=true is a
     * deliberate new operation (fresh Idempotency-Keys); otherwise this is an automatic retry that
     * reuses each token's persisted pending key. Succeeds with the most favorable backend state, with
     * null when nothing was eligible, or fails with the last error when every eligible purchase failed.
     */
    static void attach(int account, ZenlyPro.PlayAccount binding, List<GooglePlayModel.Purchase> purchases, boolean restore,
                       ZenlyPro.Callback<GooglePlayModel.SubscriptionState> callback) {
        ZenlyPro pro = ZenlyPro.get(account);
        List<GooglePlayModel.Purchase> eligible = GooglePlayModel.toAttach(purchases, binding.catalog, restore, pro.pendingGoogleAttaches());
        attachNext(pro, binding, eligible, restore, 0, new ArrayList<GooglePlayModel.SubscriptionState>(), null, callback);
    }

    private static void attachNext(ZenlyPro pro, ZenlyPro.PlayAccount binding, List<GooglePlayModel.Purchase> queue, boolean newOperation,
                                   int index, List<GooglePlayModel.SubscriptionState> states, ZenlyPro.Error lastError,
                                   ZenlyPro.Callback<GooglePlayModel.SubscriptionState> callback) {
        if (index >= queue.size()) {
            if (states.isEmpty() && lastError != null) callback.onError(lastError);
            else callback.onSuccess(GooglePlayModel.best(states));
            return;
        }
        pro.attachGooglePlay(binding, queue.get(index).token, newOperation, new ZenlyPro.Callback<GooglePlayModel.SubscriptionState>() {
            @Override public void onSuccess(GooglePlayModel.SubscriptionState value) {
                List<GooglePlayModel.SubscriptionState> next = new ArrayList<>(states);
                next.add(value);
                attachNext(pro, binding, queue, newOperation, index + 1, next, lastError, callback);
            }
            @Override public void onError(ZenlyPro.Error error) {
                attachNext(pro, binding, queue, newOperation, index + 1, states, error, callback);
            }
        });
    }

    /** Selected account first; only signed-in accounts with a linked Pro session can attach. */
    private static List<Integer> linkedAccounts() {
        List<Integer> accounts = new ArrayList<>();
        int selected = UserConfig.selectedAccount;
        if (linked(selected)) accounts.add(selected);
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (account != selected && linked(account)) accounts.add(account);
        }
        return Collections.unmodifiableList(accounts);
    }

    private static boolean linked(int account) {
        try {
            return UserConfig.getInstance(account).isClientActivated() && ZenlyPro.get(account).snapshot().linked;
        } catch (Exception e) {
            return false;
        }
    }
}
