package org.telegram.messenger.zenlygram.pro;

import android.app.Activity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Host fake of the Billing Library adapter: a connected Play store answering synchronously with the
 * configured offers and purchases, and recording every purchase-sheet launch. Nothing is charged.
 */
final class GooglePlayBilling {
    interface Result<T> { void onResult(T value, GooglePlayModel.Problem problem); }

    static final class Launch {
        final Activity activity;
        final GooglePlayModel.Catalog catalog;
        final GooglePlayModel.Offer offer;
        Launch(Activity activity, GooglePlayModel.Catalog catalog, GooglePlayModel.Offer offer) {
            this.activity = activity;
            this.catalog = catalog;
            this.offer = offer;
        }
    }

    private static GooglePlayBilling instance = new GooglePlayBilling();
    static boolean enabled = true;

    List<GooglePlayModel.Offer> offers = Collections.emptyList();
    List<GooglePlayModel.Purchase> purchases = Collections.emptyList();
    GooglePlayModel.Problem launchResult = GooglePlayModel.Problem.NONE;
    int offerQueries, purchaseQueries;
    final List<Launch> launches = new ArrayList<>();

    private GooglePlayBilling() { }

    static void reset() {
        instance = new GooglePlayBilling();
        enabled = true;
    }

    static boolean enabled() { return enabled; }

    static GooglePlayBilling get() {
        if (!enabled) throw new IllegalStateException("Google Play billing is disabled in this build");
        return instance;
    }

    void connect(Result<Boolean> callback) { callback.onResult(true, GooglePlayModel.Problem.NONE); }

    void queryOffers(GooglePlayModel.Catalog catalog, Result<List<GooglePlayModel.Offer>> callback) {
        offerQueries++;
        callback.onResult(offers, GooglePlayModel.Problem.NONE);
    }

    void queryPurchases(Result<List<GooglePlayModel.Purchase>> callback) {
        purchaseQueries++;
        callback.onResult(purchases, GooglePlayModel.Problem.NONE);
    }

    GooglePlayModel.Problem launch(Activity activity, GooglePlayModel.Catalog catalog, GooglePlayModel.Offer offer) {
        launches.add(new Launch(activity, catalog, offer));
        return launchResult;
    }
}
