package org.telegram.messenger.zenlygram.pro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Google Play rail model: no Android, Billing or network types, so it runs on a plain JVM.
 * Nothing here grants Pro. Access only comes from a backend-signed entitlement verified by ZenlyPro;
 * local Play purchase state only decides what to show and which tokens to send for verification.
 */
public final class GooglePlayModel {
    /** Mirrors ProductDetails.RecurrenceMode.INFINITE_RECURRING (Billing 8.0.0). */
    static final int RECURRENCE_INFINITE = 1;
    /** Mirrors Purchase.PurchaseState (Billing 8.0.0). */
    static final int PLAY_PURCHASED = 1, PLAY_PENDING = 2;
    /** Upper bound for an introductory phase length in its own units (e.g. 999 days); longer is treated as malformed. */
    static final int MAX_PHASE_UNITS = 999;
    static final int MAX_TOKEN_LENGTH = 4096;
    private static final Pattern ACCOUNT_ID = Pattern.compile("[a-f0-9]{64}");
    // Play product ids use [a-z0-9_.], base plan ids [a-z0-9-]; the backend owns the actual catalog.
    private static final Pattern CATALOG_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,149}");
    private static final Pattern PERIOD = Pattern.compile("P([1-9][0-9]{0,2})([DWMY])");

    private GooglePlayModel() { }

    /**
     * Whether an APK is the Google Play build, the only one with purchase controls: a release build whose versionCode
     * ends in 1 (the bundle flavor: APP_VERSION_CODE * 10 + 1; the side-load APK ends in 9). A debug build never sells.
     */
    public static boolean playBuild(boolean debugBuild, int versionCode) {
        return !debugBuild && versionCode > 0 && versionCode % 10 == 1;
    }

    public static final class Plan {
        public final String productId, basePlanId;

        public Plan(String productId, String basePlanId) {
            if (!validCatalogId(productId) || !validCatalogId(basePlanId)) throw new IllegalArgumentException("Invalid Google Play plan");
            this.productId = productId;
            this.basePlanId = basePlanId;
        }

        boolean matches(String product, String basePlan) { return productId.equals(product) && basePlanId.equals(basePlan); }
    }

    /** Backend-issued: the obfuscated id bound to the linked account plus the server-configured plans. */
    public static final class Catalog {
        public final String obfuscatedAccountId;
        public final List<Plan> plans;

        public Catalog(String obfuscatedAccountId, List<Plan> plans) {
            if (obfuscatedAccountId == null || !ACCOUNT_ID.matcher(obfuscatedAccountId).matches()) {
                throw new IllegalArgumentException("Invalid obfuscated account id");
            }
            if (plans == null || plans.isEmpty()) throw new IllegalArgumentException("Empty Google Play catalog");
            List<Plan> copy = new ArrayList<>();
            for (Plan plan : plans) {
                if (plan == null) throw new IllegalArgumentException("Invalid Google Play plan");
                for (Plan existing : copy) {
                    if (existing.matches(plan.productId, plan.basePlanId)) throw new IllegalArgumentException("Duplicate Google Play plan");
                }
                copy.add(plan);
            }
            this.obfuscatedAccountId = obfuscatedAccountId;
            this.plans = Collections.unmodifiableList(copy);
        }

        public boolean allowsProduct(String productId) {
            for (Plan plan : plans) if (plan.productId.equals(productId)) return true;
            return false;
        }

        public List<String> productIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (Plan plan : plans) ids.add(plan.productId);
            return Collections.unmodifiableList(new ArrayList<>(ids));
        }
    }

    public enum Unit { DAY, WEEK, MONTH, YEAR }

    public static final class Period {
        public final int count;
        public final Unit unit;

        Period(int count, Unit unit) { this.count = count; this.unit = unit; }

        /** Single-component ISO-8601 periods as returned by Play (P3D, P1W, P1M, P1Y); null otherwise. */
        public static Period parse(String iso) {
            if (iso == null) return null;
            Matcher matcher = PERIOD.matcher(iso);
            if (!matcher.matches()) return null;
            Unit unit;
            switch (matcher.group(2)) {
                case "D": unit = Unit.DAY; break;
                case "W": unit = Unit.WEEK; break;
                case "M": unit = Unit.MONTH; break;
                default: unit = Unit.YEAR;
            }
            return new Period(Integer.parseInt(matcher.group(1)), unit);
        }
    }

    public static final class PricingPhase {
        public final String formattedPrice, billingPeriod;
        public final long priceMicros;
        public final int cycles, recurrence;

        public PricingPhase(String formattedPrice, long priceMicros, String billingPeriod, int cycles, int recurrence) {
            this.formattedPrice = formattedPrice == null ? "" : formattedPrice;
            this.priceMicros = priceMicros;
            this.billingPeriod = billingPeriod;
            this.cycles = cycles;
            this.recurrence = recurrence;
        }

        public boolean isFree() { return priceMicros == 0; }
        public Period period() { return Period.parse(billingPeriod); }

        /**
         * Total length of a finite phase: one billing period times its cycle count, or null when Play
         * reports an implausible length (checked long math, at most MAX_PHASE_UNITS units).
         */
        public Period duration() {
            Period period = period();
            if (period == null || cycles < 0) return null;
            long total = (long) period.count * Math.max(1, cycles);
            return total > MAX_PHASE_UNITS ? null : new Period((int) total, period.unit);
        }

        boolean wellFormed() { return period() != null && priceMicros >= 0 && !formattedPrice.isEmpty() && cycles >= 0; }
    }

    /** One purchasable Play offer (base plan or eligible developer offer) and its localized phases. */
    public static final class Offer {
        public final String productId, basePlanId, offerId, offerToken;
        public final List<PricingPhase> phases;

        public Offer(String productId, String basePlanId, String offerId, String offerToken, List<PricingPhase> phases) {
            this.productId = productId;
            this.basePlanId = basePlanId;
            this.offerId = offerId;
            this.offerToken = offerToken;
            this.phases = phases == null ? Collections.<PricingPhase>emptyList() : Collections.unmodifiableList(new ArrayList<>(phases));
        }

        public PricingPhase recurringPhase() { return phases.isEmpty() ? null : phases.get(phases.size() - 1); }
        public List<PricingPhase> introductoryPhases() { return phases.isEmpty() ? phases : phases.subList(0, phases.size() - 1); }

        /** Auto-renewing only: the final phase must recur forever at a real price. */
        boolean wellFormed() {
            if (offerToken == null || offerToken.isEmpty() || phases.isEmpty()) return false;
            for (PricingPhase phase : phases) if (phase == null || !phase.wellFormed()) return false;
            for (PricingPhase intro : introductoryPhases()) if (intro.duration() == null) return false;
            PricingPhase recurring = recurringPhase();
            return recurring.recurrence == RECURRENCE_INFINITE && recurring.priceMicros > 0;
        }

        long initialPriceMicros() { return phases.get(0).priceMicros; }
    }

    /**
     * Chooses one offer per configured plan, in catalog order. Play returns only offers this Google
     * account is eligible for; the lowest initial price wins, a promotional offer wins a tie, then id.
     * Plans Play did not return (unpublished, other country) are omitted rather than invented.
     */
    public static List<Offer> selectOffers(Catalog catalog, List<Offer> available) {
        List<Offer> selected = new ArrayList<>();
        for (Plan plan : catalog.plans) {
            Offer best = null;
            for (Offer offer : available) {
                if (offer == null || !plan.matches(offer.productId, offer.basePlanId) || !offer.wellFormed()) continue;
                if (best == null || better(offer, best)) best = offer;
            }
            if (best != null) selected.add(best);
        }
        return Collections.unmodifiableList(selected);
    }

    private static boolean better(Offer candidate, Offer current) {
        if (candidate.initialPriceMicros() != current.initialPriceMicros()) return candidate.initialPriceMicros() < current.initialPriceMicros();
        if ((candidate.offerId == null) != (current.offerId == null)) return candidate.offerId != null;
        return candidate.offerId != null && candidate.offerId.compareTo(current.offerId) < 0;
    }

    public enum PurchaseState {
        UNSPECIFIED, PURCHASED, PENDING;

        static PurchaseState fromPlay(int value) {
            if (value == PLAY_PURCHASED) return PURCHASED;
            if (value == PLAY_PENDING) return PENDING;
            return UNSPECIFIED;
        }
    }

    /** Local Play purchase. The token is only ever sent to the backend for verification. */
    public static final class Purchase {
        final String token, obfuscatedAccountId;
        public final List<String> products;
        public final PurchaseState state;
        public final boolean acknowledged;

        public Purchase(String token, List<String> products, int playState, String obfuscatedAccountId, boolean acknowledged) {
            this.token = token;
            this.products = products == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<>(products));
            this.state = PurchaseState.fromPlay(playState);
            this.obfuscatedAccountId = obfuscatedAccountId;
            this.acknowledged = acknowledged;
        }
    }

    public enum Disposition { ATTACH, OWNED, PENDING, OTHER_ACCOUNT, UNSUPPORTED, INVALID }

    /**
     * Never attaches a purchase bound to another account (or to none): the backend refuses silent
     * cross-account transfer, so the client does not even offer it. Pending purchases wait for Play.
     * Acknowledged purchases were already verified by the backend (the client never acknowledges).
     */
    public static Disposition classify(Purchase purchase, Catalog catalog) {
        if (purchase == null || !validToken(purchase.token) || purchase.products.isEmpty()) return Disposition.INVALID;
        for (String product : purchase.products) if (!catalog.allowsProduct(product)) return Disposition.UNSUPPORTED;
        if (!catalog.obfuscatedAccountId.equals(purchase.obfuscatedAccountId)) return Disposition.OTHER_ACCOUNT;
        switch (purchase.state) {
            case PENDING: return Disposition.PENDING;
            case PURCHASED: return purchase.acknowledged ? Disposition.OWNED : Disposition.ATTACH;
            default: return Disposition.INVALID;
        }
    }

    /**
     * Unacknowledged purchases always; already-verified (acknowledged) ones on explicit restore, to
     * re-issue access, or when this binding still holds an unresolved attach operation for that token
     * (pendingTokenHashes): Play may show it acknowledged while this device never adopted the outcome,
     * so the same-key resend lets the backend replay it.
     */
    public static List<Purchase> toAttach(List<Purchase> purchases, Catalog catalog, boolean restore, Set<String> pendingTokenHashes) {
        List<Purchase> result = new ArrayList<>();
        Set<String> tokens = new LinkedHashSet<>();
        for (Purchase purchase : purchases) {
            Disposition disposition = classify(purchase, catalog);
            boolean wanted = disposition == Disposition.ATTACH || (disposition == Disposition.OWNED
                    && (restore || pendingTokenHashes.contains(GooglePlayAttachKeys.tokenHash(purchase.token))));
            if (wanted && tokens.add(purchase.token)) result.add(purchase);
        }
        return Collections.unmodifiableList(result);
    }

    /** Purchased, unacknowledged and well-formed: something the backend may still need to verify. */
    public static boolean needsVerification(Purchase purchase) {
        return purchase != null && purchase.state == PurchaseState.PURCHASED && !purchase.acknowledged
                && validToken(purchase.token) && !purchase.products.isEmpty();
    }

    /** Foreground reconcile: unverified purchases, plus acknowledged ones whose attach some linked binding never resolved. */
    public static boolean awaitsBackend(Purchase purchase, Set<String> pendingTokenHashes) {
        if (needsVerification(purchase)) return true;
        return purchase != null && purchase.state == PurchaseState.PURCHASED && validToken(purchase.token)
                && !purchase.products.isEmpty() && pendingTokenHashes.contains(GooglePlayAttachKeys.tokenHash(purchase.token));
    }

    public static final class Summary {
        public final boolean owned, pending, otherAccount, unverified;
        public final String ownedProductId;
        /** Catalog products this Google account already holds for another Telegram account. */
        public final Set<String> otherAccountProducts;

        Summary(boolean owned, boolean pending, Set<String> otherAccountProducts, boolean unverified, String ownedProductId) {
            this.owned = owned;
            this.pending = pending;
            this.otherAccountProducts = Collections.unmodifiableSet(new LinkedHashSet<>(otherAccountProducts));
            this.otherAccount = !this.otherAccountProducts.isEmpty();
            this.unverified = unverified;
            this.ownedProductId = ownedProductId;
        }

        public static Summary of(List<Purchase> purchases, Catalog catalog) {
            boolean owned = false, pending = false, unverified = false;
            Set<String> other = new LinkedHashSet<>();
            String product = null;
            for (Purchase purchase : purchases) {
                switch (classify(purchase, catalog)) {
                    case OWNED: owned = true; if (product == null) product = purchase.products.get(0); break;
                    case ATTACH: unverified = true; if (product == null) product = purchase.products.get(0); break;
                    case PENDING: pending = true; break;
                    case OTHER_ACCOUNT: other.addAll(purchase.products); break;
                    default: break;
                }
            }
            return new Summary(owned, pending, other, unverified, product);
        }
    }

    /** Backend subscription state. It selects a message only; it never grants access by itself. */
    public enum SubscriptionState {
        ACTIVE, GRACE, CANCELED, PENDING, ON_HOLD, PAUSED, EXPIRED, REVOKED;

        public static SubscriptionState fromWire(String value) {
            if (value == null || !value.matches("[a-z_]{1,16}")) return null;
            try { return valueOf(value.toUpperCase(Locale.US)); }
            catch (IllegalArgumentException e) { return null; }
        }

        int rank() {
            switch (this) {
                case ACTIVE: return 7;
                case GRACE: return 6;
                case CANCELED: return 5;
                case ON_HOLD: return 4;
                case PAUSED: return 3;
                case PENDING: return 2;
                case EXPIRED: return 1;
                default: return 0;
            }
        }
    }

    /** Most favorable result among several restored purchases; null when the list is empty. */
    public static SubscriptionState best(List<SubscriptionState> states) {
        SubscriptionState best = null;
        for (SubscriptionState state : states) if (state != null && (best == null || state.rank() > best.rank())) best = state;
        return best;
    }

    static boolean validToken(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) return false;
        for (int i = 0; i < token.length(); i++) if (token.charAt(i) <= ' ' || token.charAt(i) > '~') return false;
        return true;
    }

    static boolean validCatalogId(String value) { return value != null && CATALOG_ID.matcher(value).matches(); }

    public enum Stage { DISABLED, SIGN_IN_REQUIRED, LINK_REQUIRED, LOADING, UNAVAILABLE, READY, PURCHASING, VERIFYING }

    /** Which Google Play management control the screen may offer; see State.manage(). */
    public enum Manage { NONE, SUBSCRIPTION, GENERIC }

    /** What the checkout does with a Play purchases-updated event; see State.onPurchaseFlow(). */
    public enum FlowStep { FAIL, RESTORE_OWNED, RECONCILE, REQUERY, IGNORE }

    public enum Problem {
        NONE, BILLING_UNSUPPORTED, NOT_CONFIGURED, STORE_UNAVAILABLE, OFFLINE, NO_OFFERS, USER_CANCELED, ALREADY_OWNED,
        ACCOUNT_MISMATCH, PURCHASE_REJECTED, NOTHING_TO_RESTORE, SESSION_CHANGED, RATE_LIMITED, UPDATE_REQUIRED,
        STORAGE_UNAVAILABLE, MONETIZATION_DISABLED, FAILED;

        /** Mirrors BillingClient.BillingResponseCode (Billing 8.0.0) so the mapping is JVM-testable. */
        static final int BILLING_SERVICE_TIMEOUT = -3, BILLING_FEATURE_NOT_SUPPORTED = -2, BILLING_SERVICE_DISCONNECTED = -1,
                BILLING_OK = 0, BILLING_USER_CANCELED = 1, BILLING_SERVICE_UNAVAILABLE = 2, BILLING_UNAVAILABLE = 3,
                BILLING_ITEM_UNAVAILABLE = 4, BILLING_ITEM_ALREADY_OWNED = 7, BILLING_NETWORK_ERROR = 12;

        /** Play Billing response code, from a synchronous launch result or the async purchase listener alike. */
        public static Problem fromBilling(int code) {
            switch (code) {
                case BILLING_OK: return NONE;
                case BILLING_USER_CANCELED: return USER_CANCELED;
                case BILLING_ITEM_ALREADY_OWNED: return ALREADY_OWNED;
                case BILLING_UNAVAILABLE: case BILLING_FEATURE_NOT_SUPPORTED: return BILLING_UNSUPPORTED;
                case BILLING_SERVICE_UNAVAILABLE: case BILLING_SERVICE_DISCONNECTED: case BILLING_SERVICE_TIMEOUT:
                case BILLING_NETWORK_ERROR: return OFFLINE;
                case BILLING_ITEM_UNAVAILABLE: return NO_OFFERS;
                default: return STORE_UNAVAILABLE;
            }
        }

        /** Maps a ZenlyPro.Error code (contract errors plus existing DeviceAuth/transport codes). */
        public static Problem fromServer(String code, int httpStatus) {
            if (code == null) code = "";
            switch (code) {
                case "purchase_account_mismatch": case "account_mismatch": return ACCOUNT_MISMATCH;
                case "invalid_purchase": case "unsupported_product": return PURCHASE_REJECTED;
                case "monetization_disabled": return MONETIZATION_DISABLED;
                case "nothing_to_restore": return NOTHING_TO_RESTORE;
                case "google_not_configured": case "not_configured": return NOT_CONFIGURED;
                case "google_unavailable": return STORE_UNAVAILABLE;
                case "rate_limited": case "idempotency_in_flight": return RATE_LIMITED;
                case "session_changed": case "link_required": return SESSION_CHANGED;
                case "unknown_signing_key": return UPDATE_REQUIRED;
                case "connection_failed": case "service_unavailable": return OFFLINE;
                case "storage_unavailable": return STORAGE_UNAVAILABLE;
                default: return httpStatus == 429 ? RATE_LIMITED : httpStatus == 401 ? SESSION_CHANGED : FAILED;
            }
        }
    }

    /** Immutable checkout screen state. Every transition returns a new value. */
    public static final class State {
        public final Stage stage;
        public final Problem problem;
        public final List<Offer> offers;
        public final Summary purchases;
        public final SubscriptionState confirmed;
        /** Backend catalog and Play connection were both obtained, even if Play returned no offers. */
        public final boolean storeReady;
        /**
         * Play has reported this Google account's purchases since the last reload. Until then nothing
         * may be bought (it could already be owned or pending) and the screen stays loading.
         */
        public final boolean purchasesKnown;
        /**
         * Free access (the verified policy is free): nothing is ever offered or bought; the store is
         * only connected to restore, attach and manage existing purchases. Offers are never kept.
         */
        public final boolean free;

        private State(Stage stage, Problem problem, List<Offer> offers, Summary purchases, SubscriptionState confirmed,
                      boolean storeReady, boolean purchasesKnown, boolean free) {
            this.stage = stage;
            this.problem = problem;
            this.offers = free ? Collections.<Offer>emptyList() : offers;
            this.purchases = purchases;
            this.confirmed = confirmed;
            this.storeReady = storeReady;
            this.purchasesKnown = purchasesKnown;
            this.free = free;
        }

        private static final Summary NO_PURCHASES = new Summary(false, false, Collections.<String>emptySet(), false, null);

        /** The direct APK passes playBuild=false and stays DISABLED for every later event. */
        public static State initial(boolean playBuild, boolean configured, boolean signedIn, boolean linked) {
            Stage stage = !playBuild ? Stage.DISABLED : !signedIn ? Stage.SIGN_IN_REQUIRED : !configured ? Stage.UNAVAILABLE
                    : !linked ? Stage.LINK_REQUIRED : Stage.LOADING;
            Problem problem = playBuild && signedIn && !configured ? Problem.NOT_CONFIGURED : Problem.NONE;
            return new State(stage, problem, Collections.<Offer>emptyList(), NO_PURCHASES, null, false, false, false);
        }

        /**
         * The same screen under free access: offers are dropped and can never return, so a stale
         * Subscribe control cannot start a purchase. A purchase already in Play's sheet keeps its stage
         * and is still attached when it completes (already-paid fulfillment). Idempotent.
         */
        public State freeAccess() {
            if (free) return this;
            boolean idle = storeReady && purchasesKnown && (stage == Stage.READY || stage == Stage.UNAVAILABLE)
                    && (problem == Problem.NONE || problem == Problem.NO_OFFERS);
            Problem value = problem == Problem.NO_OFFERS ? Problem.NONE : problem;
            return new State(idle ? Stage.READY : stage, value, offers, purchases, confirmed, storeReady, purchasesKnown, true);
        }

        /**
         * No store event changes this state: Play is off, signed out, linking is needed, the build has no
         * configuration, or the account session changed (its Play binding is stale; only the user's Try
         * again, which prepares a fresh binding and starts from a new initial state, leaves it).
         */
        private boolean frozen() {
            return stage == Stage.DISABLED || stage == Stage.SIGN_IN_REQUIRED || stage == Stage.LINK_REQUIRED
                    || (stage == Stage.UNAVAILABLE && problem == Problem.NOT_CONFIGURED && !storeReady)
                    || (stage == Stage.UNAVAILABLE && problem == Problem.SESSION_CHANGED && !storeReady);
        }

        private boolean hasOffers() { return !offers.isEmpty(); }
        /** Free access has nothing to sell, so a connected store is simply ready (to restore and manage). */
        private Stage idleStage() { return hasOffers() || free ? Stage.READY : Stage.UNAVAILABLE; }
        private Problem idleProblem() { return hasOffers() || free ? Problem.NONE : Problem.NO_OFFERS; }

        /** Prices loaded; the screen keeps loading until Play has also reported the purchases. Free keeps none. */
        public State offersLoaded(List<Offer> loaded) {
            if (frozen() || loaded == null) return this;
            List<Offer> copy = free ? Collections.<Offer>emptyList() : Collections.unmodifiableList(new ArrayList<>(loaded));
            boolean any = !copy.isEmpty() || free;
            Stage next = !purchasesKnown ? Stage.LOADING : any ? Stage.READY : Stage.UNAVAILABLE;
            Problem problem = !purchasesKnown || any ? Problem.NONE : Problem.NO_OFFERS;
            return new State(next, problem, copy, purchases, confirmed, true, purchasesKnown, free);
        }

        /**
         * Play connected but its price lookup failed: the connection still lets owned purchases be
         * recovered and restored, while the missing prices keep any new purchase unavailable.
         */
        public State offersFailed(Problem failure) {
            if (frozen()) return this;
            Problem value = failure == null || failure == Problem.NONE ? Problem.FAILED : failure;
            if (value == Problem.BILLING_UNSUPPORTED || value == Problem.NOT_CONFIGURED) return failed(value);
            List<Offer> none = Collections.<Offer>emptyList();
            return new State(purchasesKnown ? Stage.UNAVAILABLE : Stage.LOADING, value, none, purchases, confirmed, true, purchasesKnown, free);
        }

        public State purchasesLoaded(Summary summary) {
            if (frozen() || summary == null) return this;
            if (stage == Stage.LOADING && storeReady) {
                return new State(idleStage(), problem != Problem.NONE ? problem : idleProblem(), offers, summary, confirmed, true, true, free);
            }
            return new State(stage, problem, offers, summary, confirmed, storeReady, true, free);
        }

        /** Offers that may be bought: products held by this Google account for another Telegram account are excluded. */
        public List<Offer> purchasableOffers() {
            List<Offer> result = new ArrayList<>();
            for (Offer offer : offers) if (!purchases.otherAccountProducts.contains(offer.productId)) result.add(offer);
            return Collections.unmodifiableList(result);
        }

        public boolean canPurchase() {
            return !free && stage == Stage.READY && storeReady && purchasesKnown && !purchases.owned && !purchases.pending
                    && !purchases.unverified && !purchasableOffers().isEmpty();
        }

        /**
         * Restore loads a fresh server restore context and queries Play again, so it also recovers a failed
         * purchase query. It stays offered without a connected store after a server refusal or outage of
         * the new-purchase prepare (409 monetization_disabled, 503) or when no server Play binding was
         * found (nothing_to_restore). Offline stays retry-only; unsupported billing or a missing
         * configuration offer none.
         */
        public boolean canRestore() {
            if (storeReady) return stage == Stage.READY || stage == Stage.UNAVAILABLE;
            if (stage != Stage.UNAVAILABLE) return false;
            switch (problem) {
                case MONETIZATION_DISABLED: case NOTHING_TO_RESTORE: case STORE_UNAVAILABLE: case FAILED: return true;
                default: return false;
            }
        }

        /** Play purchase sheet or backend verification running. */
        public boolean inFlight() { return stage == Stage.PURCHASING || stage == Stage.VERIFYING; }

        /**
         * Account commands (unlink, trial, link) wait while Play loads (its first purchase query may start
         * an automatic attach) and while a purchase or verification runs.
         */
        public boolean holdsAccountCommands() { return inFlight() || stage == Stage.LOADING; }

        /**
         * A purchases-updated event carries only that purchase flow's purchases, so it never replaces the
         * known inventory. Our own successful purchase, or an outside update on an idle store, is confirmed
         * by a new full purchase query (REQUERY). While a restore verifies, it goes to background
         * reconciliation (never a second attach on this screen); while prices load, the coming full query
         * covers it. Unlinked, signed-out, Direct or unconfigured screens let background reconciliation
         * attach it to the account it is bound to.
         */
        public FlowStep onPurchaseFlow(Problem problem) {
            boolean success = problem == null || problem == Problem.NONE;
            if (frozen()) return success ? FlowStep.RECONCILE : FlowStep.IGNORE;
            if (!success) {
                if (restoresAfter(problem)) return FlowStep.RESTORE_OWNED;
                return stage == Stage.PURCHASING ? FlowStep.FAIL : FlowStep.IGNORE;
            }
            if (stage == Stage.PURCHASING) return FlowStep.REQUERY;
            if (inFlight()) return FlowStep.RECONCILE;
            if (!storeReady) return stage == Stage.LOADING ? FlowStep.IGNORE : FlowStep.RECONCILE;
            return FlowStep.REQUERY;
        }

        /**
         * Before a full purchase re-query (outside update, or our own purchase finished): back to loading
         * with purchases unknown, so nothing can be bought or restored until the full list decides
         * (purchasesLoaded). Prices are kept; an old notice is cleared unless prices themselves failed.
         * A verification in flight, a loading, frozen or disconnected store is returned unchanged.
         */
        public State requerying() {
            if (frozen() || !storeReady || !(stage == Stage.READY || stage == Stage.UNAVAILABLE || stage == Stage.PURCHASING)) return this;
            return new State(Stage.LOADING, hasOffers() ? Problem.NONE : problem, offers, purchases, confirmed, true, false, free);
        }

        /**
         * SUBSCRIPTION: a Google Play subscription is known for this account. GENERIC: Play could not
         * be asked (unlinked, offline, store failure) so a neutral "Google Play subscriptions" link is
         * offered without claiming one exists (Pro access may come from a trial or another rail).
         * NONE: the store was queried and holds nothing, or the screen is busy/not applicable.
         */
        public Manage manage() {
            switch (stage) {
                case DISABLED: case SIGN_IN_REQUIRED: case LOADING: case PURCHASING: case VERIFYING: return Manage.NONE;
                case LINK_REQUIRED: return Manage.GENERIC;
                default: break;
            }
            boolean known = purchases.owned || purchases.pending || purchases.otherAccount || purchases.unverified || confirmed != null;
            if (known) return Manage.SUBSCRIPTION;
            return storeReady && purchasesKnown ? Manage.NONE : Manage.GENERIC;
        }

        /**
         * Reload when Play could not be reached, the account session changed (Try again prepares a fresh
         * Play binding), its purchases are unknown, or it returned no prices. A connected store without
         * prices offers it only while nothing is owned or pending (an owned or pending subscription needs no
         * price reload, whatever the last notice); then whatever ended the last restore or verification
         * keeps Try again available. Without a Play connection it always reconnects. Billing unsupported,
         * missing configuration, an app update required and a device storage failure never offer it. A
         * new purchase refused while free is not retried (no loop); Restore stays and the policy refresh
         * moves the screen to free access.
         */
        public boolean canRetry() {
            if (stage != Stage.UNAVAILABLE || (storeReady && purchasesKnown && hasOffers())) return false;
            if (storeReady && !hasOffers() && (purchases.owned || purchases.pending)) return false;
            switch (problem) {
                case BILLING_UNSUPPORTED: case NOT_CONFIGURED: case UPDATE_REQUIRED: case STORAGE_UNAVAILABLE: return false;
                case OFFLINE: case STORE_UNAVAILABLE: case FAILED: case SESSION_CHANGED: case RATE_LIMITED: case NO_OFFERS:
                    return true;
                case MONETIZATION_DISABLED: return false; // Not retried: the policy refresh moves the screen to free.
                default: return storeReady && !hasOffers() && !free;
            }
        }

        public State purchasing() {
            if (!canPurchase()) return this;
            return new State(Stage.PURCHASING, Problem.NONE, offers, purchases, confirmed, storeReady, purchasesKnown, free);
        }

        /** Play says the subscription is already owned while our purchase is in flight: restore it instead. */
        public boolean restoresAfter(Problem problem) {
            return stage == Stage.PURCHASING && storeReady && problem == Problem.ALREADY_OWNED;
        }

        public State verifying() {
            if (frozen() || !storeReady) return this;
            return new State(Stage.VERIFYING, Problem.NONE, offers, purchases, confirmed, storeReady, purchasesKnown, free);
        }

        /**
         * The user's Restore: one at a time, never during loading, a purchase or another verification.
         * Returns this unchanged when Restore is not available, so a repeated tap starts nothing. The
         * restore stays VERIFYING while its fresh server restore context loads (restoreContextReady).
         */
        public State restoring() {
            if (!canRestore()) return this;
            return new State(Stage.VERIFYING, Problem.NONE, offers, purchases, confirmed, storeReady, purchasesKnown, free);
        }

        /** A running restore obtained its fresh restore context and Play connection; anything else is unchanged. */
        public State restoreContextReady() {
            if (stage != Stage.VERIFYING || storeReady) return this;
            return new State(Stage.VERIFYING, Problem.NONE, offers, purchases, confirmed, true, purchasesKnown, free);
        }

        /** Back at rest after Play or the backend answered: still loading while the full purchase list is unknown. */
        private Stage restingStage() { return purchasesKnown || stage != Stage.LOADING ? idleStage() : Stage.LOADING; }
        private Problem restingProblem(Problem idle) { return restingStage() == Stage.LOADING ? problem : idle; }

        /** Pending is Play's word, not ours: no access until the backend sees a purchased state. */
        public State pending() {
            if (frozen() || !storeReady) return this;
            Summary summary = new Summary(purchases.owned, true, purchases.otherAccountProducts, purchases.unverified, purchases.ownedProductId);
            return new State(restingStage(), restingProblem(idleProblem()), offers, summary, confirmed, storeReady, purchasesKnown, free);
        }

        public State attached(SubscriptionState state) {
            if (frozen() || !storeReady || state == null) return this;
            boolean owned = state != SubscriptionState.EXPIRED && state != SubscriptionState.REVOKED && state != SubscriptionState.PENDING;
            Summary summary = new Summary(owned, state == SubscriptionState.PENDING, purchases.otherAccountProducts, false, purchases.ownedProductId);
            return new State(restingStage(), restingProblem(Problem.NONE), offers, summary, state, storeReady, purchasesKnown, free);
        }

        public State failed(Problem failure) {
            if (frozen()) return this;
            Problem value = failure == null || failure == Problem.NONE ? Problem.FAILED : failure;
            if (value == Problem.BILLING_UNSUPPORTED || value == Problem.NOT_CONFIGURED || value == Problem.SESSION_CHANGED
                    || value == Problem.MONETIZATION_DISABLED) {
                // Unsupported, unconfigured, a stale Play binding after a session change, or a new purchase
                // refused while free: no store action until a fresh start. Known purchases stay for Manage.
                return new State(Stage.UNAVAILABLE, value, Collections.<Offer>emptyList(), purchases, confirmed, false, purchasesKnown, free);
            }
            // Unknown purchases never fall back to a purchasable screen.
            Stage next = storeReady && purchasesKnown ? idleStage() : Stage.UNAVAILABLE;
            return new State(next, value, offers, purchases, confirmed, storeReady, purchasesKnown, free);
        }

        /**
         * Leaves PURCHASING/VERIFYING when Play reported nothing new to verify. Only the full purchase query
         * (purchasesLoaded) marks purchases known; a summary given here may be one flow's partial list.
         */
        public State settle(Summary summary) {
            if (frozen() || !storeReady) return this;
            return new State(restingStage(), restingProblem(idleProblem()), offers, summary == null ? purchases : summary, confirmed,
                    storeReady, purchasesKnown, free);
        }
    }
}
