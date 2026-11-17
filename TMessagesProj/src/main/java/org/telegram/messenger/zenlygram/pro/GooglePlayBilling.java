package org.telegram.messenger.zenlygram.pro;

import android.app.Activity;
import android.content.Context;

import com.android.billingclient.api.AccountIdentifiers;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Billing Library 8.0.0 adapter for the Play flavor only. UI-thread confined: every public method
 * must be called on the main thread and every callback is delivered there. It never acknowledges,
 * consumes or grants: the backend verifies and acknowledges, and access comes from its signed token.
 * Purchase tokens and order ids are never logged.
 */
final class GooglePlayBilling implements PurchasesUpdatedListener, BillingClientStateListener {
    interface Result<T> { void onResult(T value, GooglePlayModel.Problem problem); }
    interface PurchaseListener { void onPurchasesUpdated(GooglePlayModel.Problem problem, List<GooglePlayModel.Purchase> purchases); }

    private static GooglePlayBilling instance;
    private static Boolean enabled;

    private final BillingClient client;
    private final List<Result<Boolean>> waiting = new ArrayList<>();
    private final Map<String, ProductDetails> details = new HashMap<>();
    private PurchaseListener purchaseListener;
    private boolean connecting;

    private GooglePlayBilling(Context context) {
        client = BillingClient.newBuilder(context)
                .setListener(this)
                // Billing 8 requires one-time pending support to build; Zenlygram sells only auto-renewing plans.
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
    }

    /**
     * Immutable per APK: true only for the Play flavor. ZENLYGRAM 13.2.1: the Play bundle's versionCode ends in 1
     * (APP_VERSION_CODE * 10 + 1) and the side-load APK's in 9; there is no per-flavor resource to read, and a debug
     * build never sells (its package is not the Play listing's). The same rule names the "play" distribution the
     * server sees (ZenlyCustomerDevice).
     */
    static boolean enabled() {
        if (enabled == null) {
            boolean value = false;
            try {
                Context context = ApplicationLoader.applicationContext;
                value = GooglePlayModel.playBuild(org.telegram.messenger.BuildConfig.DEBUG_VERSION,
                        context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode);
            } catch (Exception ignored) { /* an unreadable package means a non-Play build */ }
            enabled = value;
        }
        return enabled;
    }

    static GooglePlayBilling get() {
        if (!enabled()) throw new IllegalStateException("Google Play billing is disabled in this build");
        if (instance == null) instance = new GooglePlayBilling(ApplicationLoader.applicationContext);
        return instance;
    }

    void setPurchaseListener(PurchaseListener listener) { purchaseListener = listener; }

    void connect(Result<Boolean> callback) {
        if (client.isReady()) {
            callback.onResult(true, subscriptionsSupported());
            return;
        }
        waiting.add(callback);
        if (connecting) return;
        connecting = true;
        try {
            client.startConnection(this);
        } catch (Exception e) {
            connecting = false;
            flush(GooglePlayModel.Problem.STORE_UNAVAILABLE);
        }
    }

    @Override public void onBillingSetupFinished(BillingResult result) {
        AndroidUtilities.runOnUIThread(() -> {
            connecting = false;
            GooglePlayModel.Problem problem = problem(result.getResponseCode());
            flush(problem == GooglePlayModel.Problem.NONE ? subscriptionsSupported() : problem);
        });
    }

    @Override public void onBillingServiceDisconnected() {
        AndroidUtilities.runOnUIThread(() -> {
            connecting = false;
            flush(GooglePlayModel.Problem.OFFLINE);
        });
    }

    private GooglePlayModel.Problem subscriptionsSupported() {
        return problem(client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS).getResponseCode());
    }

    private void flush(GooglePlayModel.Problem problem) {
        List<Result<Boolean>> callbacks = new ArrayList<>(waiting);
        waiting.clear();
        for (Result<Boolean> callback : callbacks) callback.onResult(problem == GooglePlayModel.Problem.NONE, problem);
    }

    /** Real localized Play prices; only offers matching the backend catalog survive selection. */
    void queryOffers(GooglePlayModel.Catalog catalog, Result<List<GooglePlayModel.Offer>> callback) {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (String id : catalog.productIds()) {
            products.add(QueryProductDetailsParams.Product.newBuilder().setProductId(id)
                    .setProductType(BillingClient.ProductType.SUBS).build());
        }
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build(), (result, list) ->
                AndroidUtilities.runOnUIThread(() -> {
                    GooglePlayModel.Problem problem = problem(result.getResponseCode());
                    if (problem != GooglePlayModel.Problem.NONE) {
                        callback.onResult(Collections.<GooglePlayModel.Offer>emptyList(), problem);
                        return;
                    }
                    details.clear();
                    List<GooglePlayModel.Offer> offers = new ArrayList<>();
                    List<ProductDetails> fetched = list == null ? null : list.getProductDetailsList();
                    for (ProductDetails product : fetched == null ? Collections.<ProductDetails>emptyList() : fetched) {
                        if (!catalog.allowsProduct(product.getProductId()) || product.getSubscriptionOfferDetails() == null) continue;
                        details.put(product.getProductId(), product);
                        for (ProductDetails.SubscriptionOfferDetails offer : product.getSubscriptionOfferDetails()) {
                            offers.add(offer(product.getProductId(), offer));
                        }
                    }
                    callback.onResult(GooglePlayModel.selectOffers(catalog, offers), GooglePlayModel.Problem.NONE);
                }));
    }

    private static GooglePlayModel.Offer offer(String productId, ProductDetails.SubscriptionOfferDetails offer) {
        List<GooglePlayModel.PricingPhase> phases = new ArrayList<>();
        for (ProductDetails.PricingPhase phase : offer.getPricingPhases().getPricingPhaseList()) {
            phases.add(new GooglePlayModel.PricingPhase(phase.getFormattedPrice(), phase.getPriceAmountMicros(),
                    phase.getBillingPeriod(), phase.getBillingCycleCount(), phase.getRecurrenceMode()));
        }
        return new GooglePlayModel.Offer(productId, offer.getBasePlanId(), offer.getOfferId(), offer.getOfferToken(), phases);
    }

    void queryPurchases(Result<List<GooglePlayModel.Purchase>> callback) {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                (result, list) -> AndroidUtilities.runOnUIThread(() ->
                        callback.onResult(map(list), problem(result.getResponseCode()))));
    }

    /** Returns NONE when Play's purchase sheet opened; the outcome arrives through onPurchasesUpdated. */
    GooglePlayModel.Problem launch(Activity activity, GooglePlayModel.Catalog catalog, GooglePlayModel.Offer offer) {
        ProductDetails product = details.get(offer.productId);
        if (activity == null || product == null || !catalog.allowsProduct(offer.productId)) return GooglePlayModel.Problem.STORE_UNAVAILABLE;
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product).setOfferToken(offer.offerToken).build()))
                .setObfuscatedAccountId(catalog.obfuscatedAccountId)
                .build();
        return problem(client.launchBillingFlow(activity, params).getResponseCode());
    }

    @Override public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        final GooglePlayModel.Problem problem = problem(result.getResponseCode());
        final List<GooglePlayModel.Purchase> mapped = map(purchases);
        AndroidUtilities.runOnUIThread(() -> {
            if (purchaseListener != null) purchaseListener.onPurchasesUpdated(problem, mapped);
        });
    }

    private static List<GooglePlayModel.Purchase> map(List<Purchase> purchases) {
        List<GooglePlayModel.Purchase> result = new ArrayList<>();
        if (purchases == null) return result;
        for (Purchase purchase : purchases) {
            AccountIdentifiers identifiers = purchase.getAccountIdentifiers();
            result.add(new GooglePlayModel.Purchase(purchase.getPurchaseToken(), purchase.getProducts(), purchase.getPurchaseState(),
                    identifiers == null ? null : identifiers.getObfuscatedAccountId(), purchase.isAcknowledged()));
        }
        return result;
    }

    /** The same mapping for synchronous results and the async listener (e.g. ITEM_ALREADY_OWNED from either). */
    static GooglePlayModel.Problem problem(int code) { return GooglePlayModel.Problem.fromBilling(code); }
}
