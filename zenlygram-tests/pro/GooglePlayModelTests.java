package org.telegram.messenger.zenlygram.pro;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Catalog;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel.Disposition;
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
 * Plain-JVM behavior tests for the Google Play rail (run with run-tests.sh). Product ids here are
 * test fixtures only; the real catalog is backend configuration and is never compiled into the app.
 */
public final class GooglePlayModelTests {
    private static int checks;
    private static final String ACCOUNT_A = repeat("a", 64);
    private static final String ACCOUNT_B = repeat("b", 64);
    private static final int PURCHASED = 1, PENDING = 2, UNSPECIFIED = 0;
    private static final int INFINITE = 1, FINITE = 2;
    /** No unresolved attach operation on this binding. */
    private static final java.util.Set<String> NO_PENDING = Collections.<String>emptySet();

    public static void main(String[] args) {
        ownedPurchasesKnownBeforeActions();
        requeryingIsNotPurchasable();
        catalogValidation();
        identityAndLifecycleClassification();
        restoreAttachment();
        offerSelection();
        periodsAndStates();
        serverErrors();
        directFlavorDisabled();
        checkoutTransitions();
        billingCodes();
        alreadyOwnedAndForeignSubscription();
        manageStaysReachable();
        introDurationBounds();
        System.out.println("Google Play model: " + checks + " checks passed");
    }

    private static Catalog catalog(String account) {
        return new Catalog(account, Arrays.asList(new Plan("fixture.pro", "monthly"), new Plan("fixture.pro", "yearly")));
    }

    private static Purchase purchase(String token, int state, String account, boolean acknowledged) {
        return new Purchase(token, Collections.singletonList("fixture.pro"), state, account, acknowledged);
    }

    private static Summary none() {
        return Summary.of(Collections.<Purchase>emptyList(), catalog(ACCOUNT_A));
    }

    private static void catalogValidation() {
        Catalog catalog = catalog(ACCOUNT_A);
        check(catalog.productIds().equals(Collections.singletonList("fixture.pro")), "product ids are distinct, in order");
        check(catalog.allowsProduct("fixture.pro") && !catalog.allowsProduct("other"), "only configured products allowed");
        check(rejects(() -> new Catalog(ACCOUNT_A.toUpperCase(), catalog.plans)), "uppercase obfuscated id rejected");
        check(rejects(() -> new Catalog(ACCOUNT_A.substring(1), catalog.plans)), "63-char obfuscated id rejected");
        check(rejects(() -> new Catalog(null, catalog.plans)), "missing obfuscated id rejected");
        check(rejects(() -> new Catalog(ACCOUNT_A, Collections.<Plan>emptyList())), "empty catalog rejected (minItems 1)");
        check(rejects(() -> new Catalog(ACCOUNT_A, Arrays.asList(new Plan("p", "m"), new Plan("p", "m")))), "duplicate plan rejected");
        check(rejects(() -> new Plan("", "monthly")), "empty product id rejected");
        check(rejects(() -> new Plan("fixture.pro", "month ly")), "whitespace in base plan rejected");
        check(rejects(() -> new Plan("Fixture", "monthly")), "non-Play product id rejected");
        check(catalog.plans != null && rejects(() -> catalog.plans.add(new Plan("x", "y"))), "catalog plans are immutable");
    }

    private static void identityAndLifecycleClassification() {
        Catalog mine = catalog(ACCOUNT_A);
        check(GooglePlayModel.classify(purchase("tok-1", PURCHASED, ACCOUNT_A, false), mine) == Disposition.ATTACH,
                "own purchased unacknowledged purchase is sent for verification");
        check(GooglePlayModel.classify(purchase("tok-1", PURCHASED, ACCOUNT_A, true), mine) == Disposition.OWNED,
                "own acknowledged purchase is already verified");
        check(GooglePlayModel.classify(purchase("tok-1", PENDING, ACCOUNT_A, false), mine) == Disposition.PENDING,
                "pending purchase is never sent as purchased");
        check(GooglePlayModel.classify(purchase("tok-1", PURCHASED, ACCOUNT_B, false), mine) == Disposition.OTHER_ACCOUNT,
                "purchase bound to another account is rejected");
        check(GooglePlayModel.classify(purchase("tok-1", PURCHASED, null, false), mine) == Disposition.OTHER_ACCOUNT,
                "purchase without account binding is never silently claimed");
        check(GooglePlayModel.classify(new Purchase("tok-1", Collections.singletonList("other"), PURCHASED, ACCOUNT_A, false), mine)
                == Disposition.UNSUPPORTED, "product outside backend catalog is unsupported");
        check(GooglePlayModel.classify(purchase("", PURCHASED, ACCOUNT_A, false), mine) == Disposition.INVALID, "empty token invalid");
        check(GooglePlayModel.classify(purchase("tok 1", PURCHASED, ACCOUNT_A, false), mine) == Disposition.INVALID, "whitespace token invalid");
        check(GooglePlayModel.classify(purchase(repeat("t", 4097), PURCHASED, ACCOUNT_A, false), mine) == Disposition.INVALID,
                "token over contract maximum invalid");
        check(GooglePlayModel.classify(purchase(repeat("t", 4096), PURCHASED, ACCOUNT_A, false), mine) == Disposition.ATTACH,
                "token at contract maximum accepted");
        check(GooglePlayModel.classify(purchase("tok-1", UNSPECIFIED, ACCOUNT_A, false), mine) == Disposition.INVALID,
                "unspecified Play state is ignored");
        check(GooglePlayModel.classify(new Purchase("tok-1", null, PURCHASED, ACCOUNT_A, false), mine) == Disposition.INVALID,
                "purchase without products invalid");

        // Account switch: one Play purchase, two Telegram accounts. Only its bound account attaches it.
        List<Purchase> play = Collections.singletonList(purchase("tok-a", PURCHASED, ACCOUNT_A, false));
        check(GooglePlayModel.toAttach(play, catalog(ACCOUNT_B), true, NO_PENDING).isEmpty(), "switched account never attaches the other's purchase");
        check(GooglePlayModel.toAttach(play, catalog(ACCOUNT_A), false, NO_PENDING).size() == 1, "bound account attaches its purchase");
        check(GooglePlayModel.needsVerification(play.get(0)), "purchased unacknowledged needs verification");
        check(!GooglePlayModel.needsVerification(purchase("tok-a", PENDING, ACCOUNT_A, false)), "pending needs no verification yet");
        check(!GooglePlayModel.needsVerification(purchase("tok-a", PURCHASED, ACCOUNT_A, true)), "acknowledged needs no retry");
    }

    private static void restoreAttachment() {
        Catalog mine = catalog(ACCOUNT_A);
        List<Purchase> purchases = Arrays.asList(
                purchase("owned", PURCHASED, ACCOUNT_A, true),
                purchase("fresh", PURCHASED, ACCOUNT_A, false),
                purchase("fresh", PURCHASED, ACCOUNT_A, false),
                purchase("waiting", PENDING, ACCOUNT_A, false),
                purchase("theirs", PURCHASED, ACCOUNT_B, false));
        List<Purchase> foreground = GooglePlayModel.toAttach(purchases, mine, false, NO_PENDING);
        check(foreground.size() == 1 && foreground.get(0).token.equals("fresh"), "foreground retry sends only unverified own purchases, once");
        List<Purchase> restored = GooglePlayModel.toAttach(purchases, mine, true, NO_PENDING);
        check(restored.size() == 2 && restored.get(0).token.equals("owned") && restored.get(1).token.equals("fresh"),
                "restore re-sends verified and unverified own purchases, never pending or foreign");
        Summary summary = Summary.of(purchases, mine);
        check(summary.owned && summary.pending && summary.otherAccount && summary.unverified, "summary reports every disposition");
        check("fixture.pro".equals(summary.ownedProductId), "summary keeps owned product for Play management");
        Summary none = Summary.of(Collections.singletonList(purchase("theirs", PURCHASED, ACCOUNT_B, true)), mine);
        check(!none.owned && none.otherAccount && none.ownedProductId == null, "foreign purchase is not owned here");
    }

    private static PricingPhase phase(String price, long micros, String period, int cycles, int recurrence) {
        return new PricingPhase(price, micros, period, cycles, recurrence);
    }

    private static Offer offer(String basePlan, String offerId, String token, PricingPhase... phases) {
        return new Offer("fixture.pro", basePlan, offerId, token, Arrays.asList(phases));
    }

    private static void offerSelection() {
        Catalog catalog = catalog(ACCOUNT_A);
        PricingPhase monthly = phase("$4.99", 4990000, "P1M", 0, INFINITE);
        PricingPhase yearly = phase("$39.99", 39990000, "P1Y", 0, INFINITE);
        Offer base = offer("monthly", null, "base-token", monthly);
        Offer trial = offer("monthly", "trial", "trial-token", phase("Free", 0, "P1W", 1, FINITE), monthly);
        Offer intro = offer("monthly", "intro", "intro-token", phase("$0.99", 990000, "P1M", 3, FINITE), monthly);
        Offer yearlyBase = offer("yearly", null, "year-token", yearly);
        Offer unknownPlan = offer("weekly", null, "week-token", phase("$1.99", 1990000, "P1W", 0, INFINITE));
        Offer prepaid = offer("yearly", "prepaid", "prepaid-token", phase("$1", 1000000, "P1Y", 1, 3));
        Offer noToken = offer("yearly", "free", "", phase("Free", 0, "P1Y", 0, INFINITE));
        Offer zeroRenewal = offer("yearly", "zero", "zero-token", phase("Free", 0, "P1Y", 0, INFINITE));
        Offer badPeriod = offer("yearly", "odd", "odd-token", phase("$1", 1000000, "P1Y2M", 0, INFINITE));

        List<Offer> selected = GooglePlayModel.selectOffers(catalog,
                Arrays.asList(yearlyBase, intro, base, trial, unknownPlan, prepaid, noToken, zeroRenewal, badPeriod, null));
        check(selected.size() == 2, "one offer per configured base plan");
        check(selected.get(0) == trial, "eligible free trial wins for monthly; catalog order kept");
        check(selected.get(1) == yearlyBase, "malformed, prepaid and zero-renewal offers ignored");
        check(GooglePlayModel.selectOffers(catalog, Arrays.asList(base, intro)).get(0) == intro, "lower introductory price wins");
        Offer samePrice = offer("monthly", "loyal", "loyal-token", monthly);
        check(GooglePlayModel.selectOffers(catalog, Arrays.asList(base, samePrice)).get(0) == samePrice, "promotional offer wins a tie");
        check(GooglePlayModel.selectOffers(catalog, Collections.singletonList(unknownPlan)).isEmpty(),
                "offers outside the backend catalog are never shown");
        check(trial.recurringPhase() == monthly && trial.introductoryPhases().size() == 1, "renewal and intro phases split");
        check(GooglePlayModel.selectOffers(catalog, Collections.<Offer>emptyList()).isEmpty(), "no Play offers means none invented");
    }

    private static void periodsAndStates() {
        GooglePlayModel.Period month = GooglePlayModel.Period.parse("P3M");
        check(month != null && month.count == 3 && month.unit == GooglePlayModel.Unit.MONTH, "ISO month period parsed");
        check(GooglePlayModel.Period.parse("P1W").unit == GooglePlayModel.Unit.WEEK, "week period parsed");
        check(GooglePlayModel.Period.parse("P0D") == null && GooglePlayModel.Period.parse("1M") == null
                && GooglePlayModel.Period.parse("P1Y2M") == null && GooglePlayModel.Period.parse(null) == null, "invalid periods rejected");
        GooglePlayModel.Period duration = phase("$0.99", 990000, "P1M", 3, FINITE).duration();
        check(duration.count == 3 && duration.unit == GooglePlayModel.Unit.MONTH, "intro duration is period times cycles");

        check(SubscriptionState.fromWire("on_hold") == SubscriptionState.ON_HOLD, "wire state parsed");
        check(SubscriptionState.fromWire("ACTIVE") == null && SubscriptionState.fromWire("granted") == null
                && SubscriptionState.fromWire(null) == null, "unknown or noncanonical states rejected");
        check(GooglePlayModel.best(Arrays.asList(SubscriptionState.EXPIRED, SubscriptionState.CANCELED, SubscriptionState.PENDING))
                == SubscriptionState.CANCELED, "best restored state chosen");
        check(GooglePlayModel.best(Collections.<SubscriptionState>emptyList()) == null, "no states means no result");
    }

    private static void serverErrors() {
        check(Problem.fromServer("purchase_account_mismatch", 409) == Problem.ACCOUNT_MISMATCH, "409 ownership mismatch mapped");
        check(Problem.fromServer("invalid_purchase", 422) == Problem.PURCHASE_REJECTED, "invalid purchase mapped");
        check(Problem.fromServer("unsupported_product", 422) == Problem.PURCHASE_REJECTED, "unsupported product mapped");
        check(Problem.fromServer("google_not_configured", 503) == Problem.NOT_CONFIGURED, "missing Google config is unavailable");
        check(Problem.fromServer("google_unavailable", 503) == Problem.STORE_UNAVAILABLE, "Google outage mapped");
        check(Problem.fromServer("connection_failed", 0) == Problem.OFFLINE, "offline mapped");
        check(Problem.fromServer("refresh_invalid", 401) == Problem.SESSION_CHANGED, "DeviceAuth 401 returns to session handling");
        check(Problem.fromServer("anything", 429) == Problem.RATE_LIMITED, "throttle mapped");
        check(Problem.fromServer("idempotency_in_flight", 409) == Problem.RATE_LIMITED, "concurrent attach waits");
        check(Problem.fromServer("server_error", 500) == Problem.FAILED, "generic failure mapped");
    }

    private static void directFlavorDisabled() {
        check(GooglePlayModel.playBuild(false, 73011) && GooglePlayModel.playBuild(false, 1), "the bundle flavor (versionCode ending in 1) is the Play build");
        check(!GooglePlayModel.playBuild(false, 73019) && !GooglePlayModel.playBuild(false, 73010), "the side-load APK (ending in 9) is not");
        check(!GooglePlayModel.playBuild(true, 73011), "a debug build never sells");
        check(!GooglePlayModel.playBuild(false, 0) && !GooglePlayModel.playBuild(false, -9), "an unreadable versionCode is not the Play build");
        State direct = State.initial(false, true, true, true);
        check(direct.stage == Stage.DISABLED, "direct APK starts disabled");
        Offer offer = offer("monthly", null, "t", phase("$4.99", 4990000, "P1M", 0, INFINITE));
        State after = direct.offersLoaded(Collections.singletonList(offer)).purchasing().verifying()
                .attached(SubscriptionState.ACTIVE).failed(Problem.OFFLINE).pending().settle(null);
        check(after.stage == Stage.DISABLED && after.offers.isEmpty(), "no event enables Play checkout in the direct APK");
        check(!after.canPurchase() && !after.canRestore() && after.manage() == GooglePlayModel.Manage.NONE && !after.canRetry(),
                "direct APK has no Play actions");
        check(after.confirmed == null, "direct APK never records a Play result");
    }

    private static void checkoutTransitions() {
        check(State.initial(true, true, false, true).stage == Stage.SIGN_IN_REQUIRED, "signed-out Telegram cannot buy");
        State unlinked = State.initial(true, true, true, false);
        check(unlinked.stage == Stage.LINK_REQUIRED, "purchase requires linked account first");
        check(unlinked.offersLoaded(Collections.<Offer>emptyList()).stage == Stage.LINK_REQUIRED, "unlinked state ignores late results");
        State unconfigured = State.initial(true, false, true, true);
        check(unconfigured.stage == Stage.UNAVAILABLE && unconfigured.problem == Problem.NOT_CONFIGURED
                && !unconfigured.canPurchase() && !unconfigured.canRetry(), "missing Pro config shows unavailable state");

        State loading = State.initial(true, true, true, true);
        check(loading.stage == Stage.LOADING && !loading.canPurchase() && !loading.canRestore(), "loading has no actions");
        State offline = loading.failed(Problem.OFFLINE);
        check(offline.stage == Stage.UNAVAILABLE && offline.canRetry() && !offline.canPurchase(), "offline load offers retry only");
        State unsupported = loading.failed(Problem.BILLING_UNSUPPORTED);
        check(!unsupported.canRetry() && !unsupported.canRestore(), "unsupported billing has no purchase/restore/retry");
        State notConfigured = loading.failed(Problem.NOT_CONFIGURED);
        check(notConfigured.stage == Stage.UNAVAILABLE && notConfigured.offersLoaded(Collections.<Offer>emptyList()) == notConfigured,
                "backend Google config missing stays unavailable");
        Offer monthly = offer("monthly", null, "t", phase("$4.99", 4990000, "P1M", 0, INFINITE));
        State noOffers = loading.offersLoaded(Collections.<Offer>emptyList()).purchasesLoaded(none());
        check(noOffers.stage == Stage.UNAVAILABLE && noOffers.problem == Problem.NO_OFFERS && noOffers.canRestore()
                && !noOffers.canPurchase(), "no Play offers: nothing to buy, restore stays available");
        State ready = loading.offersLoaded(Collections.singletonList(monthly)).purchasesLoaded(none());
        check(ready.stage == Stage.READY && ready.canPurchase() && ready.canRestore() && ready.manage() == GooglePlayModel.Manage.NONE,
                "offers enable purchase; a queried store without a Google subscription offers no Manage");
        check(loading.stage == Stage.LOADING, "transitions never mutate the previous state");

        State buying = ready.purchasing();
        check(buying.stage == Stage.PURCHASING && !buying.canPurchase() && buying.purchasing() == buying, "no double purchase");
        State canceled = buying.failed(Problem.USER_CANCELED);
        check(canceled.stage == Stage.READY && canceled.problem == Problem.USER_CANCELED && canceled.canPurchase(),
                "cancellation returns to offers");
        State pending = buying.pending();
        check(pending.stage == Stage.READY && pending.purchases.pending && !pending.canPurchase()
                && pending.manage() == GooglePlayModel.Manage.SUBSCRIPTION
                && pending.confirmed == null, "pending blocks a second purchase and grants nothing");
        State verifying = buying.verifying();
        check(verifying.stage == Stage.VERIFYING && !verifying.canPurchase(), "verification blocks actions");
        State active = verifying.attached(SubscriptionState.ACTIVE);
        check(active.stage == Stage.READY && active.purchases.owned && active.confirmed == SubscriptionState.ACTIVE
                && !active.canPurchase() && active.manage() == GooglePlayModel.Manage.SUBSCRIPTION, "verified purchase shows management");
        State backendPending = verifying.attached(SubscriptionState.PENDING);
        check(backendPending.purchases.pending && !backendPending.purchases.owned, "backend pending is not owned");
        State expired = verifying.attached(SubscriptionState.EXPIRED);
        check(!expired.purchases.owned && expired.canPurchase(), "expired subscription can be bought again");
        State rejected = verifying.failed(Problem.ACCOUNT_MISMATCH);
        check(rejected.stage == Stage.READY && rejected.problem == Problem.ACCOUNT_MISMATCH && rejected.confirmed == null,
                "rejected attach records no result");
        State owned = ready.purchasesLoaded(Summary.of(Collections.singletonList(purchase("x", PURCHASED, ACCOUNT_A, true)), catalog(ACCOUNT_A)));
        check(!owned.canPurchase() && owned.manage() == GooglePlayModel.Manage.SUBSCRIPTION,
                "Play-owned subscription hides purchase and shows management");
        State unverified = ready.purchasesLoaded(Summary.of(Collections.singletonList(purchase("x", PURCHASED, ACCOUNT_A, false)), catalog(ACCOUNT_A)));
        check(!unverified.canPurchase(), "unverified purchase blocks buying twice");
        State nothing = verifying.settle(null).failed(Problem.NOTHING_TO_RESTORE);
        check(nothing.stage == Stage.READY && nothing.problem == Problem.NOTHING_TO_RESTORE, "empty restore reported");
        check(State.initial(true, true, true, true).verifying().stage == Stage.LOADING, "cannot verify before store is ready");
        List<Offer> source = new ArrayList<>(Collections.singletonList(monthly));
        State copied = loading.offersLoaded(source);
        source.clear();
        check(copied.offers.size() == 1, "state keeps its own offer list");
    }

    /** Billing 8.0.0 response codes (verified with javap) map identically for sync and async results. */
    private static void billingCodes() {
        check(Problem.fromBilling(0) == Problem.NONE, "OK");
        check(Problem.fromBilling(1) == Problem.USER_CANCELED, "USER_CANCELED");
        check(Problem.fromBilling(7) == Problem.ALREADY_OWNED, "ITEM_ALREADY_OWNED is not reported as store outage");
        check(Problem.fromBilling(3) == Problem.BILLING_UNSUPPORTED && Problem.fromBilling(-2) == Problem.BILLING_UNSUPPORTED,
                "BILLING_UNAVAILABLE / FEATURE_NOT_SUPPORTED");
        check(Problem.fromBilling(2) == Problem.OFFLINE && Problem.fromBilling(-1) == Problem.OFFLINE
                && Problem.fromBilling(-3) == Problem.OFFLINE && Problem.fromBilling(12) == Problem.OFFLINE, "transient Play failures");
        check(Problem.fromBilling(4) == Problem.NO_OFFERS, "ITEM_UNAVAILABLE");
        check(Problem.fromBilling(5) == Problem.STORE_UNAVAILABLE && Problem.fromBilling(6) == Problem.STORE_UNAVAILABLE
                && Problem.fromBilling(8) == Problem.STORE_UNAVAILABLE, "developer/generic errors");
    }

    private static void alreadyOwnedAndForeignSubscription() {
        Offer monthly = offer("monthly", null, "t", phase("$4.99", 4990000, "P1M", 0, INFINITE));
        State ready = State.initial(true, true, true, true).offersLoaded(Collections.singletonList(monthly)).purchasesLoaded(none());
        State buying = ready.purchasing();
        check(buying.restoresAfter(Problem.ALREADY_OWNED), "already-owned during a purchase triggers restore");
        check(!buying.restoresAfter(Problem.USER_CANCELED) && !ready.restoresAfter(Problem.ALREADY_OWNED),
                "restore only for already-owned while purchasing");
        State foreign = ready.purchasesLoaded(Summary.of(
                Collections.singletonList(purchase("theirs", PURCHASED, ACCOUNT_B, true)), catalog(ACCOUNT_A)));
        check(!foreign.canPurchase() && foreign.purchasableOffers().isEmpty()
                && foreign.manage() == GooglePlayModel.Manage.SUBSCRIPTION,
                "the only product, held for another Telegram account, cannot be bought again and stays manageable");

        // Multi-product catalog: only the product actually held for another account is withheld.
        Catalog two = new Catalog(ACCOUNT_A, Arrays.asList(new Plan("fixture.pro", "monthly"), new Plan("fixture.plus", "monthly")));
        Offer plus = new Offer("fixture.plus", "monthly", null, "plus-token",
                Collections.singletonList(phase("$9.99", 9990000, "P1M", 0, INFINITE)));
        State both = State.initial(true, true, true, true).offersLoaded(Arrays.asList(monthly, plus))
                .purchasesLoaded(Summary.of(Collections.singletonList(purchase("theirs", PURCHASED, ACCOUNT_B, true)), two));
        check(both.canPurchase() && both.purchasableOffers().equals(Collections.singletonList(plus)),
                "another product in a multi-product catalog stays purchasable");
        check(both.purchases.otherAccountProducts.equals(Collections.singleton("fixture.pro")), "held product recorded exactly");
    }

    private static void manageStaysReachable() {
        Offer monthly = offer("monthly", null, "t", phase("$4.99", 4990000, "P1M", 0, INFINITE));
        State ready = State.initial(true, true, true, true).offersLoaded(Collections.singletonList(monthly)).purchasesLoaded(none());
        State active = ready.verifying().attached(SubscriptionState.ACTIVE);
        check(active.failed(Problem.OFFLINE).manage() == GooglePlayModel.Manage.SUBSCRIPTION,
                "known subscription stays manageable after a later offline failure");
        check(State.initial(true, true, true, true).failed(Problem.OFFLINE).manage() == GooglePlayModel.Manage.GENERIC,
                "store never queried: a generic Google Play link, never a claim of a Play subscription");
        check(State.initial(true, true, true, true).failed(Problem.BILLING_UNSUPPORTED).manage() == GooglePlayModel.Manage.GENERIC,
                "billing unsupported still offers the generic link");
        check(State.initial(true, true, true, false).manage() == GooglePlayModel.Manage.GENERIC,
                "unlinked (e.g. session lost after 401): generic Google Play link stays reachable");
        check(ready.manage() == GooglePlayModel.Manage.NONE,
                "store queried with no Google subscription (e.g. a trial): no Manage control at all");
        check(State.initial(true, true, true, true).manage() == GooglePlayModel.Manage.NONE, "nothing while loading");
        check(State.initial(false, true, true, true).manage() == GooglePlayModel.Manage.NONE
                && State.initial(true, true, false, true).manage() == GooglePlayModel.Manage.NONE, "direct APK and signed-out show none");
    }

    /**
     * AC-P1. Play prices arrive before Play reports this account's purchases. Until both are known the
     * screen must not offer Subscribe (a second subscription) nor Restore (an attach racing the
     * initial recovery query); a failed purchase query must not fall back to a purchasable screen.
     */
    private static void ownedPurchasesKnownBeforeActions() {
        Offer monthly = offer("monthly", null, "t", phase("$4.99", 4990000, "P1M", 0, INFINITE));
        State offersOnly = State.initial(true, true, true, true).offersLoaded(Collections.singletonList(monthly));
        check(!offersOnly.canPurchase() && !offersOnly.canRestore(), "prices alone never enable Subscribe or Restore");
        check(offersOnly.purchasing() == offersOnly, "a tap on a stale Subscribe control cannot start a purchase yet");
        check(offersOnly.manage() == GooglePlayModel.Manage.NONE, "no Manage claim while purchases are unknown");
        // Security review L4: a Play purchase-flow update carries only that flow's purchases; it never
        // stands in for the full purchase query, so it cannot unlock Subscribe or Restore.
        State partialSettle = offersOnly.settle(none());
        check(!partialSettle.purchasesKnown && !partialSettle.canPurchase() && !partialSettle.canRestore(),
                "a partial purchase update never marks all purchases known");
        State partialPending = offersOnly.pending();
        check(!partialPending.purchasesKnown && !partialPending.canRestore(), "a pending update during loading keeps loading");

        State owned = offersOnly.purchasesLoaded(Summary.of(
                Collections.singletonList(purchase("x", PURCHASED, ACCOUNT_A, true)), catalog(ACCOUNT_A)));
        check(owned.stage == Stage.READY && !owned.canPurchase() && owned.canRestore()
                && owned.manage() == GooglePlayModel.Manage.SUBSCRIPTION, "an owned subscription is never offered for purchase again");
        State pending = offersOnly.purchasesLoaded(Summary.of(
                Collections.singletonList(purchase("p", PENDING, ACCOUNT_A, false)), catalog(ACCOUNT_A)));
        check(!pending.canPurchase() && pending.purchases.pending, "a pending payment blocks a second purchase");
        State fresh = offersOnly.purchasesLoaded(none());
        check(fresh.stage == Stage.READY && fresh.problem == Problem.NONE && fresh.canPurchase() && fresh.canRestore(),
                "nothing owned: Subscribe and Restore become available together");

        State queryFailed = offersOnly.failed(Problem.OFFLINE);
        check(queryFailed.stage == Stage.UNAVAILABLE && queryFailed.problem == Problem.OFFLINE && !queryFailed.canPurchase(),
                "owned-purchase query failure never falls back to a purchasable screen");
        check(queryFailed.canRestore() && queryFailed.canRetry() && queryFailed.manage() == GooglePlayModel.Manage.GENERIC,
                "owned-purchase query failure: Restore and Try again recover; a neutral Google Play link stays reachable");
    }

    /**
     * Review N1/N3: a full re-query (after an outside purchase update or our own successful purchase)
     * keeps the screen non-purchasable until Play's full purchase list returns; the list then decides.
     */
    private static void requeryingIsNotPurchasable() {
        Offer monthly = offer("monthly", null, "t", phase("$4.99", 4990000, "P1M", 0, INFINITE));
        State ready = State.initial(true, true, true, true).offersLoaded(Collections.singletonList(monthly)).purchasesLoaded(none());
        State requery = ready.requerying();
        check(requery.stage == Stage.LOADING && !requery.purchasesKnown, "a re-query waits for the full purchase list");
        check(!requery.canPurchase() && !requery.canRestore() && requery.holdsAccountCommands(),
                "no purchase, restore or account command while purchases are re-read");
        check(requery.purchasing() == requery && requery.restoring() == requery, "a tap on a stale Subscribe or Restore row starts nothing");
        check(ready.stage == Stage.READY, "requerying never mutates the previous state");

        State afterOwnPurchase = ready.purchasing().requerying();
        check(afterOwnPurchase.stage == Stage.LOADING && !afterOwnPurchase.canPurchase(),
                "after our own purchase the screen waits for the full list instead of trusting that flow's partial list");
        State bought = afterOwnPurchase.purchasesLoaded(Summary.of(
                Collections.singletonList(purchase("mine", PURCHASED, ACCOUNT_A, false)), catalog(ACCOUNT_A)));
        check(bought.stage == Stage.READY && bought.purchases.unverified && !bought.canPurchase(),
                "the full list shows the new purchase awaiting verification and blocks a second purchase");

        Catalog two = new Catalog(ACCOUNT_A, Arrays.asList(new Plan("fixture.pro", "monthly"), new Plan("fixture.plus", "monthly")));
        Offer plus = new Offer("fixture.plus", "monthly", null, "plus-token",
                Collections.singletonList(phase("$9.99", 9990000, "P1M", 0, INFINITE)));
        Summary withForeign = Summary.of(Collections.singletonList(purchase("theirs", PURCHASED, ACCOUNT_B, true)), two);
        State both = State.initial(true, true, true, true).offersLoaded(Arrays.asList(monthly, plus)).purchasesLoaded(withForeign);
        State reread = both.purchasing().requerying().purchasesLoaded(withForeign);
        check(reread.purchasableOffers().equals(Collections.singletonList(plus)),
                "a product held for another Telegram account stays withheld after the re-query");

        State canceled = ready.purchasing().failed(Problem.USER_CANCELED);
        check(canceled.requerying().purchasesLoaded(none()).problem == Problem.NONE, "an old cancellation notice is cleared by the fresh list");
        State pricesFailed = State.initial(true, true, true, true).offersFailed(Problem.OFFLINE).purchasesLoaded(none());
        State pricesStillFailed = pricesFailed.requerying().purchasesLoaded(none());
        check(pricesStillFailed.stage == Stage.UNAVAILABLE && pricesStillFailed.problem == Problem.OFFLINE,
                "a price lookup failure stays visible across a re-query");

        State verifying = ready.restoring();
        check(verifying.requerying() == verifying, "a running verification is never interrupted by a re-query");
        State unlinked = State.initial(true, true, true, false);
        check(unlinked.requerying() == unlinked && State.initial(false, true, true, true).requerying().stage == Stage.DISABLED,
                "unlinked and Direct screens never re-query");
        State loading = State.initial(true, true, true, true);
        check(loading.requerying() == loading, "prices still loading: the coming full query already covers it");
    }

    private static void introDurationBounds() {
        PricingPhase huge = phase("$0.99", 990000, "P999M", Integer.MAX_VALUE, FINITE);
        check(huge.duration() == null, "cycle multiplication cannot overflow into a bogus duration");
        check(phase("$0.99", 990000, "P1M", 999, FINITE).duration().count == 999, "longest sane intro kept");
        check(phase("$0.99", 990000, "P1M", 1000, FINITE).duration() == null, "implausible intro length rejected");
        check(phase("$0.99", 990000, "P1M", -1, FINITE).duration() == null, "negative cycles rejected");
        Offer bogus = offer("monthly", "bogus", "b", huge, phase("$4.99", 4990000, "P1M", 0, INFINITE));
        check(GooglePlayModel.selectOffers(catalog(ACCOUNT_A), Collections.singletonList(bogus)).isEmpty(),
                "offer with an implausible intro phase is never shown");
    }

    private interface Action { void run(); }

    private static boolean rejects(Action action) {
        try { action.run(); return false; }
        catch (RuntimeException expected) { return true; }
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
