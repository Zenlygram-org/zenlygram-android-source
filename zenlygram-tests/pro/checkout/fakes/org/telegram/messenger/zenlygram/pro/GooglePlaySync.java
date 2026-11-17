package org.telegram.messenger.zenlygram.pro;

import java.util.ArrayList;
import java.util.List;

/**
 * Host fake of the background Play reconciliation/attach owner: records what the real checkout hands
 * it (registration, forced reconciles, attach requests) and lets a test deliver Play purchase-flow
 * events to the registered screen.
 */
final class GooglePlaySync {
    interface FlowListener { void onPurchaseFlow(GooglePlayModel.Problem problem, List<GooglePlayModel.Purchase> purchases); }

    static final class Attach {
        final int account;
        final ZenlyPro.PlayAccount binding;
        final List<GooglePlayModel.Purchase> purchases;
        final boolean restore;
        final ZenlyPro.Callback<GooglePlayModel.SubscriptionState> callback;
        Attach(int account, ZenlyPro.PlayAccount binding, List<GooglePlayModel.Purchase> purchases, boolean restore,
               ZenlyPro.Callback<GooglePlayModel.SubscriptionState> callback) {
            this.account = account;
            this.binding = binding;
            this.purchases = purchases;
            this.restore = restore;
            this.callback = callback;
        }
    }

    static FlowListener checkout;
    static int reconciles;
    static final List<Attach> attaches = new ArrayList<>();

    private GooglePlaySync() { }

    static void reset() {
        checkout = null;
        reconciles = 0;
        attaches.clear();
    }

    static void register(FlowListener listener) { checkout = listener; }

    static void unregister(FlowListener listener) {
        if (checkout == listener) checkout = null;
    }

    static void reconcile(boolean force) { reconciles++; }

    static void attach(int account, ZenlyPro.PlayAccount binding, List<GooglePlayModel.Purchase> purchases, boolean restore,
                       ZenlyPro.Callback<GooglePlayModel.SubscriptionState> callback) {
        attaches.add(new Attach(account, binding, purchases, restore, callback));
    }

    /** Play's purchases-updated listener, as the real sync forwards it to the registered screen. */
    static void deliver(GooglePlayModel.Problem problem, List<GooglePlayModel.Purchase> purchases) {
        if (checkout != null) checkout.onPurchaseFlow(problem, purchases);
        else if (problem == GooglePlayModel.Problem.NONE) reconcile(true);
    }
}
