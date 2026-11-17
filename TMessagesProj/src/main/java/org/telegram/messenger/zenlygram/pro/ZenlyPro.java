package org.telegram.messenger.zenlygram.pro;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.ZenlySurfaces;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Account-scoped Zenlygram Pro. Gates are synchronous and offline; network requests run on
 * single-thread worker lanes and callbacks run on the UI thread. The Google Play rail only sends
 * purchase tokens for backend verification; access always comes from the signed entitlement adopted
 * here. Existing vault/chat/task content must never depend on canCreate().
 *
 * Free-first: an installation-wide signed monetization policy decides the mode. Until a verified
 * owner-enabled paid policy arrives every account is FREE (all local Pro capabilities, no deadline,
 * no trial or purchase). In paid mode only genuine account_ent/account_until claims grant access.
 *
 * Origin-scoped (contract §6.10): the configured backend origin is canonical (ProOrigin) and every
 * persisted Pro record (install record, sessions, the accepted policy) lives in that origin's own
 * store (ProStore); configuring another origin swaps the store and never publishes the previous
 * origin's sessions or claims. Pre-origin files are never read. No readiness hold: a configured
 * origin with pinned keys is usable at once.
 */
public final class ZenlyPro {
    public enum Capability {
        VAULT("vault"), CHAT_LOCK("chatlock"), TASKS("tasks"), EDIT_HISTORY("edithistory");
        final String wire;
        Capability(String wire) { this.wire = wire; }
    }

    public enum Status { FREE, UNCONFIGURED, LOCAL_TRIAL, ACTIVE, EXPIRED, RECONNECT, LINK_REQUIRED }
    public enum LinkState { PENDING, CONFIRMED, DENIED, EXPIRED, CONSUMED }

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(Error error);
    }

    /**
     * Binding changes of one account slot at one origin (authority = ZenlyPro.authority() then), on the
     * main thread outside the Pro lock. Adopted: a polled session was saved (manual or headless link).
     * Lost: a server 401 cleared this binding's refresh token; code is its error code and reason its
     * body's reason (e.g. account_erased) or null. Neither fires for unlink (user or logout), the local
     * rotation give-up or forgetSessionAfterErasure.
     */
    public interface BindingListener {
        void onBindingAdopted(int account, long telegramUserId, String authority, String bindingId);
        void onBindingLost(int account, long telegramUserId, String authority, String code, String reason);
    }

    /**
     * The installation's consent seam for linking (ZenlyCustomer, design 50 §8.1). Read on the thread that starts a
     * link, before its bind/start is queued, and again on its lane right before the body is built (a manual link can
     * wait there behind a logout's or an Unlink's final send and bind/revoke); the grant of that second read travels
     * only when the first read gave one too (ProLinkRequests.consentAfterWait), and one attempt sends that one grant.
     */
    public interface ConsentProvider {
        /**
         * The exact JSON of telegramUserId's latest grant ConsentEvent when it may travel with bind/start
         * (contract §2.2.1: effective grant, not rejected, no deletion, binding loss or withdraw pending), else
         * null. Any thread; reads only the immutable consent snapshot and never blocks.
         */
        String grantJson(long telegramUserId);
    }

    public static final class Error extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code, message;
        public final int httpStatus, retryAfterSeconds;
        Error(String code, String message, int httpStatus, int retryAfterSeconds) {
            super(message);
            this.code = code;
            this.message = message;
            this.httpStatus = httpStatus;
            this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
        }
    }

    public static final class Snapshot {
        public final Status status;
        public final long untilEpochSeconds;
        public final boolean linked, configured;
        /** The verified policy is paid: subscription offers and trials may be shown. Never charges anyone by itself. */
        public final boolean purchasesAvailable;
        /**
         * A genuine paid right of this account ran out (ProPolicy.lapsed). False for an account that never paid, so
         * the screen can tell "subscription ended" from "not active". Display only; it never grants or blocks.
         */
        public final boolean lapsed;
        public final String message;
        Snapshot(Status status, long until, boolean linked, boolean configured, boolean purchasesAvailable, boolean lapsed,
                 String message) {
            this.status = status;
            this.untilEpochSeconds = until;
            this.linked = linked;
            this.configured = configured;
            this.purchasesAvailable = purchasesAvailable;
            this.lapsed = lapsed;
            this.message = message;
        }
    }

    public static final class LinkRequest {
        public final String code, deepLink, pollToken;
        public final long expiresAtEpochSeconds;
        /** Telegram-attested linking offer of this bind (bind-attest v1); null means link by code only. */
        public final ProBindAttest.Offer attest;
        /** Started by beginLinkHeadless (ProAutoLink): never shown, never changes the screen message. */
        public final boolean headless;
        private final long telegramId, generation, attempt;
        private final String registeredKey, deviceId;
        LinkRequest(String code, String deepLink, String pollToken, long expiresAt, ProBindAttest.Offer attest, long telegramId,
                    long generation, long attempt, String registeredKey, String deviceId, boolean headless) {
            this.code = code;
            this.deepLink = deepLink;
            this.pollToken = pollToken;
            this.expiresAtEpochSeconds = expiresAt;
            this.attest = attest;
            this.headless = headless;
            this.telegramId = telegramId;
            this.generation = generation;
            this.attempt = attempt;
            this.registeredKey = registeredKey;
            this.deviceId = deviceId;
        }
    }

    public static final class LinkResult {
        public final LinkState state;
        public final Snapshot snapshot;
        public final boolean mergeConflict;
        LinkResult(LinkState state, Snapshot snapshot, boolean mergeConflict) {
            this.state = state;
            this.snapshot = snapshot;
            this.mergeConflict = mergeConflict;
        }
    }

    /** Google Play binding: purchase tokens are attached only under the account that prepared them. */
    public static final class PlayAccount {
        public final GooglePlayModel.Catalog catalog;
        private final long telegramId;
        private final String accountId;
        PlayAccount(GooglePlayModel.Catalog catalog, long telegramId, String accountId) {
            this.catalog = catalog;
            this.telegramId = telegramId;
            this.accountId = accountId;
        }
    }

    /** Values are public configuration; never put a backend private key or shared secret here. */
    public static final class Config {
        /** Canonical "https://host[:port]/v1" (ProOrigin), or "" when no origin is configured. */
        public final String apiUrl;
        /** "host" or "host:port" of apiUrl; "" when no origin is configured. */
        public final String authority;
        /** Lowercase hex SHA-256 of authority (of ProOrigin.UNCONFIGURED without an origin): this origin's storage scope. */
        public final String storageToken;
        public final Map<String, String> pinnedPublicKeys;
        /** Explicit test-only hold (authHeldForTesting); false for every configuration production builds. */
        final boolean authHeld;
        private final Map<String, PublicKey> parsedKeys;

        public Config(String apiUrl, Map<String, String> pinnedPublicKeys) { this(apiUrl, pinnedPublicKeys, false); }

        private Config(String apiUrl, Map<String, String> pinnedPublicKeys, boolean authHeld) {
            String canonical;
            try { canonical = ProOrigin.canonicalApiUrl(apiUrl); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Pro API must be an HTTPS /v1 endpoint"); }
            this.apiUrl = canonical;
            this.authority = ProOrigin.authority(canonical);
            this.storageToken = ProOrigin.storageToken(authority);
            this.authHeld = authHeld;
            this.pinnedPublicKeys = Collections.unmodifiableMap(new HashMap<>(pinnedPublicKeys));
            Map<String, PublicKey> parsed = new HashMap<>();
            try {
                for (Map.Entry<String, String> entry : pinnedPublicKeys.entrySet()) {
                    if (entry.getKey().isEmpty()) throw new IllegalArgumentException();
                    String value = entry.getValue().replace("-----BEGIN PUBLIC KEY-----", "")
                            .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
                    parsed.put(entry.getKey(), ProCrypto.publicKey(Base64.decode(value, Base64.DEFAULT)));
                }
            } catch (Exception e) { throw new IllegalArgumentException("Invalid pinned Pro public key"); }
            this.parsedKeys = Collections.unmodifiableMap(parsed);
        }

        /**
         * Instrumentation only. Production never constructs a held configuration. The same origin and
         * pins, but every authenticated request is refused locally with not_configured before anything
         * is signed (linking, refresh, trial, Google Play, Tasks), the Tasks principal is null, the
         * automatic and push-hint refreshes and push registration treat the slot as unlinked, and
         * unlink skips its bind/revoke (the local delete still happens).
         */
        public static Config authHeldForTesting(String apiUrl, Map<String, String> pinnedPublicKeys) {
            return new Config(apiUrl, pinnedPublicKeys, true);
        }

        public boolean isConfigured() { return !apiUrl.isEmpty() && !parsedKeys.isEmpty(); }
    }

    private static final Object LOCK = new Object();
    /** User lane: manual link, entitlement, trial, push token, Google Play, Tasks. */
    private static final ExecutorService WORKER = lane("ZenlygramPro");
    /** Headless (automatic) linking: bind/start, bind/attest, bind/poll never queue behind user actions. */
    private static final ExecutorService LINK_WORKER = lane("ZenlygramAutoLink");
    /** Customer-data routes (profile/consents, sync, me, events) never delay user actions or linking. */
    private static final ExecutorService CUSTOMER_WORKER = lane("ZenlygramCustomer");
    private static final Map<Integer, ZenlyPro> INSTANCES = new HashMap<>();
    /** Written only under LOCK; volatile so the lock-free origin accessors read the current one. */
    private static volatile Config config;
    private static ProStore store;
    private static JSONObject install;
    private static boolean initializationAttempted;
    private static long lastClockPersist;
    private static boolean clockAnchoredInThisProcess;
    /**
     * Mirror of the configured origin's install.server_offset (written under LOCK) for the lock-free
     * trustedNowSeconds(); 0 before any validated server_time and after an origin change.
     */
    private static volatile long serverOffsetSeconds;
    /** Server 429 cooldowns per trust domain + route, shared by all accounts of this installation. */
    private static ProCooldowns cooldowns = ProCooldowns.NONE;
    private static String initializationError;
    /** Latest Firebase token from this process (memory only; persisted sessions keep a fingerprint). */
    private static String pushToken;
    private static final String PUSH_REGISTERED = "push_token_sha256";
    /** Customer-data bookkeeping of this binding (CustomerApi.Bookkeeping); a relink starts without any. */
    private static final String PROFILE_DIGEST = "profile_sync_sha256", PROFILE_SYNCED_AT = "profile_synced_at",
            PROFILE_MIN_INTERVAL = "profile_min_interval_s", PROFILE_STOPPED = "profile_stopped";
    /** The installation's binding listener (ZenlyCustomer); null until installed. */
    private static volatile BindingListener bindingListener;
    /** The installation's consent seam (ZenlyCustomer); null until installed: links then carry no consent. */
    private static volatile ConsentProvider consentProvider;
    /** Pending Google attach Idempotency-Keys of this binding (token hash -> key), see GooglePlayAttachKeys. */
    private static final String GOOGLE_ATTACH_KEYS = "google_attach_keys";
    private static final java.security.SecureRandom ATTACH_RANDOM = new java.security.SecureRandom();
    private static final GooglePlayAttachKeys.Entropy ATTACH_ENTROPY = ATTACH_RANDOM::nextBytes;
    /**
     * Per-binding "fresh refresh owed" sequence in the encrypted session (survives process death),
     * bumped by every push hint, by every Google attach before its token is sent, and by every
     * replayed trial answer. consumed records the value captured when a later-successful refresh (or
     * adoption of a fresh attach grant) STARTED, so anything arriving meanwhile stays owed.
     */
    private static final String HINT_RECEIVED = "hint_seq_received", HINT_CONSUMED = "hint_seq_consumed";
    /** Bounded wait on the FCM thread (FCM allows ~20 s per message); never used on the UI thread. */
    private static final long HINT_WAIT_MS = 10000;
    /**
     * Installation-wide free-first policy of the Android line (ProMonetization: ver 4; the server's contract file
     * resources/contracts/monetization-policy.v4.json): the exact verified signed token is persisted in the install
     * record and reverified with the pinned keys on load; until one is accepted the install is FREE. Network
     * failures keep the last verified mode. The token is also the only store of the local grace length
     * (ProMonetization.Policy.localGraceDays, read from its verified claims): nothing else in the record holds it.
     *
     * The Android line's keys in the install record are its own. What a build of the general line stored is not
     * carried over: its policy token and its owed notice (LEGACY_POLICY_KEYS) are never read and are removed at a
     * load; its grace start mark (LEGACY_FIRST_PAID) is kept and read for ONE thing only, "this install was once
     * under a paid policy" (everUnderPaidPolicyLocked: a purchase it opened then may still be owed its attach).
     * So this build is free until its first verified Android policy, and its local grace counts from the first
     * PAID Android policy it adopts.
     */
    private static final String POLICY_KEY = "monetization_policy_v4", POLICY_PATH = ProMonetization.POLICY_PATH;
    private static final String[] LEGACY_POLICY_KEYS = {"monetization_policy", "paid_notice"};
    private static final String LEGACY_FIRST_PAID = "first_paid";
    /**
     * Install record, minted once and never re-minted (iOS entitlement §5.1.2): the trusted time of this install's
     * first evaluation under an adopted PAID policy. The paid-mode local grace starts at the later of first_launch
     * and this mark (ProPolicy.graceStart), whatever its length is or becomes: it is minted under a policy of
     * length 0 too, so a later policy with a longer grace counts from the same start. Free mode neither reads nor
     * mints it.
     */
    private static final String FIRST_PAID = "first_paid_v4";
    /** Install record: the one-time paid-mode notice is owed (ProMonetization.noticeOwed) until takePaidModeNotice(). */
    private static final String PAID_NOTICE = "paid_notice_v4";
    /** The mark of this process when its write failed: the grace still counts from now, and the next launch mints again. */
    private static long firstPaidUnsaved;
    private static ProMonetization.Policy policy = ProMonetization.INITIAL;
    private static ProMonetization.Refresh policyRefresh = ProMonetization.Refresh.IDLE;
    /** Own thread: the public policy GET never waits behind (or delays) account requests. */
    private static final ExecutorService POLICY_WORKER = lane("ZenlygramPolicy");

    /** One daemon worker thread (created with its first task); each lane runs its work in submission order. */
    private static ExecutorService lane(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private final int account;
    private long telegramId, generation;
    private long linkAttempt;
    /** The current linkAttempt was taken by beginLinkHeadless (ProAutoLink); false for a manual one. */
    private boolean headlessAttempt;
    /** bind/start of the current linkAttempt is in flight: its LinkRequest does not exist yet. */
    private boolean linkStarting;
    /** Binding-loss reports bindingLostLocked posted that the listener has not received yet (guarded by LOCK). */
    private int lossesUndelivered;
    private JSONObject session;
    private ProPolicy.Claims claims;
    private LinkRequest activeLink;
    private String lastMessage = "";
    private long lastRefreshAttemptElapsed = -300000;
    private ProPushHint.Throttle hintThrottle = ProPushHint.Throttle.IDLE;
    private ProPushToken.Schedule pushSchedule = ProPushToken.Schedule.IDLE;
    private boolean pushInFlight;
    /** Last published access decision of this slot and its single pending deadline timer. */
    private String accessSignature;
    private Runnable accessTimer;
    /** Last published Pro status as shown to the user (state, signed until, linked, configured). */
    private String statusSignature;

    private ZenlyPro(int account) { this.account = account; }

    public static ZenlyPro get(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) throw new IllegalArgumentException("Invalid Telegram account");
        synchronized (LOCK) {
            ZenlyPro result = INSTANCES.get(account);
            if (result == null) { result = new ZenlyPro(account); INSTANCES.put(account, result); }
            return result;
        }
    }

    /**
     * Another origin (storage token) swaps the store: the previous origin's install record, policy,
     * cooldowns, clock offset, sessions and claims are dropped from memory (never deleted) and the new
     * origin's store is opened lazily. The same origin keeps its store and reverifies with the new keys.
     */
    public static void configure(Config value) {
        if (value == null) throw new IllegalArgumentException("Missing Pro configuration");
        synchronized (LOCK) {
            String old = config == null ? null : config.storageToken; // configure() may be the first ZenlyPro call.
            boolean originChanged = !value.storageToken.equals(old);
            config = value;
            if (originChanged) {
                store = null;
                install = null;
                initializationAttempted = false;
                initializationError = null;
                policy = ProMonetization.INITIAL;
                policyRefresh = ProMonetization.Refresh.IDLE;
                cooldowns = ProCooldowns.NONE;
                firstPaidUnsaved = 0;
                serverOffsetSeconds = 0;
                // The clock bookkeeping belongs to the install record just dropped.
                clockAnchoredInThisProcess = false;
                lastClockPersist = 0;
            }
            if (install != null) loadPolicyLocked(); // Reverified with the new key ring; the stored token is kept.
            for (ZenlyPro pro : INSTANCES.values()) {
                if (originChanged) {
                    // Nothing of the previous origin is published meanwhile; loadLocked() re-reads from the new store.
                    pro.telegramId = -1;
                    pro.session = null;
                    pro.claims = null;
                }
                pro.generation++;
                pro.hintThrottle = ProPushHint.Throttle.IDLE; // Its trailing runnable is now dropped by the generation guard.
                pro.activeLink = null;
                pro.lastMessage = "";
                pro.reverifyLocked();
                pro.accessChangedLocked();
            }
        }
    }

    // ---- Origin and trusted clock: lock-free (volatile reads only, never LOCK), any thread, inside other locks ----

    /** Storage token of the configured origin (of ProOrigin.UNCONFIGURED without one): always 64 lowercase hex. */
    public static String storageToken() { return currentConfig().storageToken; }

    /** Storage token of a usable configured origin (an https /v1 origin with pinned keys), else null. */
    public static String configuredStorageToken() {
        Config current = currentConfig();
        return current.isConfigured() ? current.storageToken : null;
    }

    /** "host" or "host:port" of a usable configured origin (an https /v1 origin with pinned keys), else null. */
    public static String authority() {
        Config current = currentConfig();
        return current.isConfigured() ? current.authority : null;
    }

    /** This build's versionName as sent to the backend: at most 32 chars, "android" when unknown. */
    public static String appVersionName() {
        try {
            Context context = ApplicationLoader.applicationContext;
            String value = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return value == null ? "android" : value.substring(0, Math.min(32, value.length()));
        } catch (Exception e) { return "android"; }
    }

    /**
     * Epoch seconds: the wall clock corrected by the offset of the configured origin's last validated
     * server_time (persisted in its install record); equal to the wall clock before any server contact.
     */
    public static long trustedNowSeconds() { return System.currentTimeMillis() / 1000L + serverOffsetSeconds; }

    /** The configuration in effect: the configured one, else the packaged one initializeLocked() adopts (not published here). */
    private static Config currentConfig() {
        Config current = config;
        return current != null ? current : resourceConfig();
    }

    public Status status() { return snapshot().status; }

    public Snapshot snapshot() {
        synchronized (LOCK) {
            loadLocked();
            ProPolicy.Decision decision = decisionLocked();
            String message = initializationError != null ? initializationError : lastMessage;
            return new Snapshot(Status.valueOf(decision.state.name()), decision.displayedUntil(),
                    session != null && !session.optString("refresh_token").isEmpty(), config.isConfigured(),
                    ProMonetization.allowsNewPurchase(policy.mode), ProPolicy.lapsed(claims, nowLocked()), message);
        }
    }

    /** This account's signed decision for creating new material of {@code capability} (free mode: always). Any thread. */
    public boolean canCreate(Capability capability) {
        if (capability == null) return false;
        synchronized (LOCK) { loadLocked(); return decisionLocked().grants(capability.wire); }
    }

    /**
     * Whether the SERVER would accept a create of {@code capability} from this account: the policy is free, or the
     * account holds a genuine grant that lists it. The paid-mode local grace is this device's own allowance and no
     * grant the server knows, so material that is created on the server (synced Tasks) asks this, not
     * {@link #canCreate}. Any thread.
     */
    public boolean serverGrants(Capability capability) {
        if (capability == null) return false;
        synchronized (LOCK) {
            loadLocked();
            return ProPolicy.serverGrants(decisionLocked(), capability.wire);
        }
    }

    /** The verified policy this installation decides by: for display only (the Pro screen's policy line). */
    public static final class PolicyInfo {
        /** False until a signed policy was adopted: the installation is free. */
        public final boolean adopted;
        public final boolean paid;
        public final long revision, issuedAtEpochSeconds;
        PolicyInfo(boolean adopted, boolean paid, long revision, long issuedAt) {
            this.adopted = adopted;
            this.paid = paid;
            this.revision = revision;
            this.issuedAtEpochSeconds = issuedAt;
        }
    }

    public static PolicyInfo policyInfo() {
        synchronized (LOCK) {
            initializeLocked();
            return new PolicyInfo(policy.token != null, policy.mode == ProPolicy.Mode.PAID, Math.max(0, policy.revision), policy.issuedAt);
        }
    }

    /** EDIT_HISTORY grants revealing captured versions; recording itself must remain free. */
    public boolean canRevealEditHistory() { return canCreate(Capability.EDIT_HISTORY); }

    /** Installs the installation's consent seam (ZenlyCustomer, at process start; null removes it). */
    public static void setConsentProvider(ConsentProvider provider) { consentProvider = provider; }

    /** The provider's grant for telegramUserId when it can travel inside bind/start (ProLinkRequests), else null. */
    private static String linkConsent(long telegramUserId) {
        ConsentProvider provider = consentProvider;
        if (provider == null || telegramUserId <= 0) return null;
        try {
            return ProLinkRequests.usableConsent(provider.grantJson(telegramUserId));
        } catch (RuntimeException unreadable) {
            return null; // Never block linking on the consent seam: the grant is uploaded after the link.
        }
    }

    public void beginLink(Callback<LinkRequest> callback) {
        final Stamp stamp;
        final long attempt;
        synchronized (LOCK) {
            stamp = capture();
            attempt = ++linkAttempt;
            activeLink = null;
            headlessAttempt = false;
            linkStarting = true;
        }
        // The consent is read here, before the request is queued, and again once it runs on WORKER (startLink).
        startLink(WORKER, callback, stamp, attempt, linkConsent(stamp.telegramId), false);
    }

    /**
     * Automatic link (ProAutoLink, design 50 §8.1): bind/start on LINK_WORKER with the same validation and
     * fencing as beginLink and never a screen message. While manualLinkPending() it refuses with link_busy
     * and leaves the link attempt alone, so a manual link (e.g. waiting while the user is in the bot chat)
     * survives; a later manual beginLink() supersedes this one (link_changed).
     */
    void beginLinkHeadless(Callback<LinkRequest> callback) {
        final Stamp stamp;
        final long attempt;
        synchronized (LOCK) {
            stamp = capture();
            if (manualLinkPendingLocked()) {
                fail(callback, new Error("link_busy", "A link is already in progress.", 0, 0));
                return;
            }
            attempt = ++linkAttempt;
            activeLink = null;
            headlessAttempt = true;
            linkStarting = true;
        }
        startLink(LINK_WORKER, callback, stamp, attempt, linkConsent(stamp.telegramId), true);
    }

    /**
     * bind/start of one link attempt. queuedConsent is the grant read when the attempt started; once the operation runs
     * on its lane the grant is read again, and only that second read travels, and only when both gave one
     * (ProLinkRequests.consentAfterWait). A 409 consent_stale to a body carrying consent is reported with that exact
     * consent (ProAutoLink.consentStaleReported), so the grant is never used again; a body refused for its
     * consent alone (ProLinkRequests.retryWithoutConsent: 422 invalid_consent, and 409 consent_stale for a manual
     * link only) is sent once more without it.
     */
    private void startLink(ExecutorService lane, Callback<LinkRequest> callback, Stamp stamp, long attempt, String queuedConsent,
                           boolean headless) {
        async(lane, callback, starting(attempt, () -> {
            String key, device;
            Config configuration;
            synchronized (LOCK) {
                requireCurrentLocked(stamp, false);
                requireLinkAttemptLocked(attempt);
                requireConfiguredLocked();
                key = store.publicKey();
                device = install.getString("device_id");
                configuration = config;
                activeLink = null;
            }
            // Read again now that the wait on the lane is over (a manual link waits on WORKER behind a logout's or an
            // Unlink's final send and bind/revoke): a withdrawal made meanwhile sends no consent, and a grant made
            // meanwhile is uploaded after the link instead (as iOS's beginBind after its revoke wait).
            final String consent = ProLinkRequests.consentAfterWait(queuedConsent, linkConsent(stamp.telegramId));
            // src identifies the real caller; the backend applies strict in-app bot copy for android
            // (no external purchase steering). Never impersonate another client.
            String name = Build.MANUFACTURER + " " + Build.MODEL;
            Map<String, String> headers = Collections.singletonMap("X-Device-Id", device);
            ProTransport.Response response = requestBytes(configuration, "bind/start", "POST",
                    ProLinkRequests.bindStartBody(device, key, Build.VERSION.RELEASE, appVersionName(), name, consent), headers);
            String refused = bodyString(response.body, "error");
            if (consent != null && response.status == 409 && "consent_stale".equals(refused)) {
                ProAutoLink.consentStaleReported(account, stamp.telegramId, configuration.authority, consent);
            }
            if (consent != null && ProLinkRequests.retryWithoutConsent(response.status, refused, !headless)) {
                synchronized (LOCK) {
                    requireCurrentLocked(stamp, false);
                    requireLinkAttemptLocked(attempt);
                }
                response = requestBytes(configuration, "bind/start", "POST",
                        ProLinkRequests.bindStartBody(device, key, Build.VERSION.RELEASE, appVersionName(), name, null), headers);
            }
            synchronized (LOCK) {
                requireCurrentLocked(stamp, false);
                requireLinkAttemptLocked(attempt);
                successfulLocked(response, null, !headless);
                JSONObject body = response.body;
                String code = body.getString("code"), poll = body.getString("poll_token");
                long expires = ProPolicy.utc(body.getString("expires_at"));
                if (code.length() != 8 || !poll.matches("[a-fA-F0-9]{64}") || expires <= 0) throw malformed();
                String deepLink = body.isNull("deeplink") ? null : body.optString("deeplink", null);
                if (deepLink != null) {
                    URI uri = new URI(deepLink);
                    if (!"https".equals(uri.getScheme()) || !"t.me".equalsIgnoreCase(uri.getHost())
                            || uri.getRawUserInfo() != null) throw malformed();
                }
                JSONObject offer = body.optJSONObject("attest");
                ProBindAttest.Offer attest = offer == null ? null : ProBindAttest.Offer.of(offer.optString("bot_id", null),
                        offer.optString("bot_username", null), offer.optString("start_param", null), code);
                LinkRequest link = new LinkRequest(code, deepLink, poll, expires, attest, stamp.telegramId, stamp.generation, attempt,
                        key, device, headless);
                activeLink = link;
                return link;
            }
        }));
    }

    /** operation, after which attempt's bind/start is no longer in flight (manualLinkPending), whatever its outcome. */
    private <T> Operation<T> starting(long attempt, Operation<T> operation) {
        return () -> {
            try {
                return operation.run();
            } finally {
                synchronized (LOCK) { if (linkAttempt == attempt) linkStarting = false; }
            }
        };
    }

    /** Cancel pending callbacks immediately when the linking UI closes. The server code expires. */
    public void cancelLink() {
        synchronized (LOCK) {
            linkAttempt++;
            activeLink = null;
            linkStarting = false;
        }
    }

    /** A manual (Pro screen) link of this slot is starting or waiting for its confirmation (design 50 §8.1). */
    public boolean manualLinkPending() {
        synchronized (LOCK) {
            loadLocked();
            return manualLinkPendingLocked();
        }
    }

    private boolean manualLinkPendingLocked() {
        return (activeLink != null && !activeLink.headless) || (linkStarting && !headlessAttempt);
    }

    /**
     * A server 401 cleared this slot's refresh token and the binding listener has not run for it yet: ProAutoLink starts
     * no automatic link of the slot meanwhile (design 50 §8.2), so none carries the grant that 401 ended. The mark lives
     * in memory only.
     */
    public boolean bindingLossPending() {
        synchronized (LOCK) {
            return lossesUndelivered > 0;
        }
    }

    /**
     * Ends this slot's headless attempt when it is still the current one (ProAutoLink: the attempt ended or its user was
     * forgotten), dropping its poll token; a manual link is never touched.
     */
    void cancelHeadlessLink() {
        synchronized (LOCK) {
            if (!headlessAttempt || (activeLink == null && !linkStarting)) return;
            linkAttempt++;
            activeLink = null;
            linkStarting = false;
        }
    }

    /** A usable configured origin that is not test-held, so an automatic link may sign (lock-free). */
    static boolean linkingConfigured() {
        Config current = currentConfig();
        return current.isConfigured() && !current.authHeld;
    }

    /**
     * POST /v1/bind/attest: the Telegram-signed {@code initData} of this link's Telegram account,
     * authenticated by this link's poll token and the device proof of the key that started it. On
     * success the bind is confirmed and the caller polls at once for the session. Any error means
     * the caller falls back to linking by code; it never changes Pro state or the screen message.
     */
    public void attestLink(LinkRequest link, String initData, Callback<LinkRequest> callback) {
        attest(WORKER, link, initData, callback, false);
    }

    /**
     * Headless bind/attest (ProAutoLink, LINK_WORKER), as attestLink, except that an answer that does not confirm
     * keeps the backend's own error (bind_conflict, bind_paused, rate_limited, attest_*) with its status and
     * Retry-After instead of attest_unavailable.
     */
    void attestLinkHeadless(LinkRequest link, String initData, Callback<LinkRequest> callback) {
        attest(LINK_WORKER, link, initData, callback, true);
    }

    private void attest(ExecutorService lane, LinkRequest link, String initData, Callback<LinkRequest> callback, boolean headless) {
        async(lane, callback, () -> {
            Config configuration;
            synchronized (LOCK) {
                requireLinkLocked(link, headless);
                requireConfiguredLocked();
                if (link.attest == null) throw attestUnavailable(0, 0);
                if (!link.registeredKey.equals(store.publicKey())) throw new Error("device_key_changed", "Get a new linking code.", 0, 0);
                configuration = config;
            }
            byte[] body = ProBindAttest.body(link.pollToken, initData);
            if (body == null) throw attestUnavailable(0, 0);
            for (int attempt = 0; attempt < 2; attempt++) {
                Map<String, String> headers;
                synchronized (LOCK) {
                    requireLinkLocked(link, headless);
                    headers = attestHeadersLocked(link, body);
                }
                ProTransport.Response response = requestBytes(configuration, ProBindAttest.ROUTE, "POST", body, headers);
                // request() adopted the server_time of a clock_skew answer: one retry with a fresh proof.
                if (attempt == 0 && response.status == 401 && "clock_skew".equals(response.body.optString("error"))) continue;
                synchronized (LOCK) {
                    requireLinkLocked(link, headless);
                    if (!ProBindAttest.confirmed(response.status, response.body.optString("state"))) {
                        throw headless ? attestRefused(response) : attestUnavailable(response.status, response.retryAfter);
                    }
                    return link;
                }
            }
            throw headless ? new Error("clock_skew", "Check this device's date and time.", 401, 0) : attestUnavailable(401, 0);
        });
    }

    /** A headless attest the server did not confirm: its own error code, status and Retry-After. */
    private static Error attestRefused(ProTransport.Response response) {
        String code = bodyString(response.body, "error");
        if (code == null) code = response.status >= 200 && response.status < 300 ? "invalid_response" : "service_unavailable";
        return new Error(code, "Link with the code instead.", response.status, response.retryAfter);
    }

    /** X-Device-Id/Nonce/Ts/Proof of the bind's own key over the attest path and the exact body; no X-Device-Sig. */
    private static Map<String, String> attestHeadersLocked(LinkRequest link, byte[] body) throws Exception {
        String nonce = ProStore.randomHex(16);
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L + install.optLong("server_offset"));
        byte[] message = ProBindAttest.proofMessage(link.deviceId, Base64.decode(link.registeredKey, Base64.DEFAULT), nonce, timestamp, body);
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Device-Id", link.deviceId);
        headers.put("X-Device-Nonce", nonce);
        headers.put("X-Device-Ts", timestamp);
        headers.put("X-Device-Proof", store.signMessage(message));
        return headers;
    }

    private static Error attestUnavailable(int status, int retryAfter) {
        return new Error("attest_unavailable", "Link with the code instead.", status, retryAfter);
    }

    /** Caller polls while its linking screen is visible; respect Error.retryAfterSeconds. */
    public void pollLink(LinkRequest link, Callback<LinkResult> callback) {
        poll(WORKER, link, callback, false);
    }

    /**
     * Headless bind/poll (ProAutoLink, LINK_WORKER): the same Telegram-id check, signature check and adoption as
     * pollLink (session save, push registration, binding listener); never a screen message.
     */
    void pollLinkHeadless(LinkRequest link, Callback<LinkResult> callback) {
        poll(LINK_WORKER, link, callback, true);
    }

    private void poll(ExecutorService lane, LinkRequest link, Callback<LinkResult> callback, boolean headless) {
        async(lane, callback, () -> {
            Config configuration;
            synchronized (LOCK) {
                loadLocked();
                requireLinkLocked(link, headless);
                requireConfiguredLocked();
                configuration = config;
            }
            ProTransport.Response response = request(configuration, "bind/poll", "POST",
                    new JSONObject().put("poll_token", link.pollToken), Collections.emptyMap());
            synchronized (LOCK) {
                requireLinkLocked(link, headless);
                successfulLocked(response, null, !headless);
                LinkState state;
                try { state = LinkState.valueOf(response.body.getString("state").toUpperCase(Locale.US)); }
                catch (Exception e) { throw malformed(); }
                if (state != LinkState.CONFIRMED) {
                    if (state != LinkState.PENDING) activeLink = null;
                    return new LinkResult(state, snapshot(), false);
                }
                JSONObject backendAccount = response.body.getJSONObject("account");
                if (backendAccount.getLong("tg_user_id") != telegramId) {
                    activeLink = null;
                    throw new Error("account_mismatch", "Confirm the link using this Telegram account.", 0, 0);
                }
                if (!link.registeredKey.equals(store.publicKey())) throw new Error("device_key_changed", "Get a new linking code.", 0, 0);
                String accountId = backendAccount.getString("id");
                String bindingId = response.body.getJSONObject("device").getString("id");
                String token = response.body.getString("entitlement");
                ProPolicy.Claims verified = verifyLocked(token, accountId, bindingId);
                JSONObject fresh = new JSONObject().put("telegram_id", telegramId).put("account_id", accountId)
                        .put("binding_id", bindingId).put("refresh_token", response.body.getString("refresh_token"))
                        .put("entitlement", token).put("last_rotation", nowLocked());
                if (fresh.getString("refresh_token").isEmpty()) throw malformed();
                saveLocked(fresh);
                claims = verified;
                generation++;
                hintThrottle = ProPushHint.Throttle.IDLE;
                accessChangedLocked();
                activeLink = null;
                lastMessage = "";
                pushSchedule = ProPushToken.Schedule.IDLE;
                registerPushTokenLocked(); // New binding: queued after this result, never before it.
                bindingAdoptedLocked(bindingId); // Posted now: the listener learns the binding before this link's callback.
                // A manual link success ends this account's Unlink suppression and attest streak (design 50 §8.2, §8.4).
                if (!headless) ProAutoLink.manualLinkAdopted(account, telegramId, config.authority);
                return new LinkResult(state, snapshot(), response.body.optBoolean("merge_conflict", false));
            }
        });
    }

    public void startTrial(Callback<Snapshot> callback) {
        final Stamp stamp = capture();
        async(callback, () -> {
            String operation;
            synchronized (LOCK) {
                requireSessionLocked(stamp);
                requirePurchasesAllowedLocked(); // Free: no trial start, not even from a screen opened while paid.
                operation = session.optString("trial_operation");
                if (operation.isEmpty()) {
                    operation = java.util.UUID.randomUUID().toString();
                    JSONObject updated = copy(session).put("trial_operation", operation);
                    saveLocked(updated);
                }
            }
            ProTransport.Response response = authenticated(stamp, "trial/start", "POST", new JSONObject(), operation);
            final long owed;
            synchronized (LOCK) {
                requireCurrentLocked(stamp, true);
                successfulLocked(response, stamp);
                if (adoptLocked(response.body.getString("entitlement"), null, response.replayed)) return snapshot();
                if (!response.replayed) return snapshot(); // An older fresh grant changed nothing.
                JSONObject updated = copy(session);
                owed = oweRefreshLocked(updated);
                saveLocked(updated);
            }
            adoptCurrentEntitlement(stamp, owed); // The durable trial key replayed: its stored grant may be stale.
            synchronized (LOCK) { return snapshot(); }
        });
    }

    /** GET normally; rotates every 30 days, retaining the old signed grant on any failure. */
    public void refresh(Callback<Snapshot> callback) {
        final Stamp stamp = capture();
        async(callback, () -> {
            boolean rotate;
            final long hintSequence;
            synchronized (LOCK) {
                requireSessionLocked(stamp);
                // Only hints received before this request can be satisfied by its answer.
                hintSequence = session.optLong(HINT_RECEIVED, 0);
                long started = session.optLong("rotation_started");
                if (started > 0 && nowLocked() - started >= 45) {
                    clearRefreshLocked(null, null); // Local give-up without a server answer: no binding-loss report.
                    throw new Error("link_required", "Link this device again to refresh Pro.", 0, 0);
                }
                rotate = started > 0 || nowLocked() - session.optLong("last_rotation") >= 30 * ProPolicy.DAY;
                if (rotate && started == 0) saveLocked(copy(session).put("rotation_started", nowLocked()));
            }
            ProTransport.Response response = authenticated(stamp, rotate ? "entitlement/refresh" : "entitlement",
                    rotate ? "POST" : "GET", rotate ? new JSONObject().put("app_version", appVersionName())
                            .put("os_version", Build.VERSION.RELEASE) : null, null);
            synchronized (LOCK) {
                requireCurrentLocked(stamp, true);
                successfulLocked(response, stamp);
                String rotated = rotate ? response.body.getString("refresh_token") : null;
                if (rotated != null && rotated.isEmpty()) throw malformed();
                adoptLocked(response.body.getString("entitlement"), rotated, false);
                ProPushHint.Pending hints = hintsLocked();
                ProPushHint.Pending consumed = hints.consumedThrough(hintSequence);
                if (consumed != hints) saveLocked(copy(session).put(HINT_CONSUMED, consumed.consumed));
                return snapshot();
            }
        });
    }

    /**
     * Safe to call on foreground. Offline failures are retried no more than once per five minutes. The
     * installation-wide policy is refreshed separately (refreshPolicy), independent of any binding.
     */
    public void refreshIfNeeded() {
        final boolean sold;
        synchronized (LOCK) {
            loadLocked();
            // Handler delays pause in deep sleep, so a deadline may have passed unobserved: re-check now.
            accessChangedLocked();
            refreshEntitlementIfDueLocked();
            boolean linked = config.isConfigured() && !config.authHeld && session != null && !session.optString("refresh_token").isEmpty();
            if (linked) registerPushTokenLocked(); // Foreground retry for a missed or failed registration.
            sold = everUnderPaidPolicyLocked();
        }
        // Outside LOCK. Purchases may belong to any linked account, not only the selected one;
        // reconciliation re-enters ZenlyPro only through queued async operations. An installation that never
        // decided under a paid policy never opened a purchase, so it has nothing in Play to reconcile and does not
        // ask Play at all; one that did keeps reconciling while free (a paid, unattached purchase is still owed).
        if (sold) GooglePlaySync.onForeground();
    }

    /**
     * The policy is paid now, or this installation has decided under a paid one before: its start mark is minted
     * then, under the Android line or, by a build of the general line, under that one (LEGACY_FIRST_PAID).
     */
    private static boolean everUnderPaidPolicyLocked() {
        return policy.mode == ProPolicy.Mode.PAID || firstPaidUnsaved > 0
                || (install != null && (install.optLong(FIRST_PAID) > 0 || install.optLong(LEGACY_FIRST_PAID) > 0));
    }

    /**
     * Starts the bounded entitlement refresh when due. An owed refresh (a push hint or a Google attach
     * whose fresh grant was never adopted: process died, offline, lost answer) and, in paid mode, a
     * legacy entitlement without genuine account claims override the daily skip of a recent grant.
     */
    private void refreshEntitlementIfDueLocked() {
        // A test-only held configuration treats the slot as unlinked: no automatic refresh is started.
        boolean linked = config.isConfigured() && !config.authHeld && session != null && !session.optString("refresh_token").isEmpty();
        long elapsed = SystemClock.elapsedRealtime();
        boolean owed = linked && (hintsLocked().pending() || ProMonetization.accountRefreshDue(policy.mode, claims));
        long grantAge = claims == null ? 0 : nowLocked() - claims.issuedAt;
        if (ProPushHint.foregroundRefreshDue(linked, owed, elapsed - lastRefreshAttemptElapsed, claims != null, grantAge)) {
            lastRefreshAttemptElapsed = elapsed;
            refresh(null);
        }
    }

    /**
     * FCM data hint (see ProPushHint). Thread-safe; call before Telegram push decoding. Returns true
     * when the push is Zenlygram's, so the caller stops processing it, even if the hint is ignored.
     * It never grants: the matching linked account only runs the existing authenticated refresh.
     * Hints within a minute coalesce into one trailing refresh. Off the main thread (the FCM worker)
     * it waits at most HINT_WAIT_MS for the immediate refresh so a cold-woken process is not
     * reclaimed mid-request; it never blocks the UI thread. A persisted pending flag makes the next
     * foreground refresh anyway if the process still dies first.
     */
    public static boolean onPushHint(Map<String, String> data) {
        if (!ProPushHint.isHint(data)) return false;
        String accountId = ProPushHint.accountId(data);
        if (accountId == null) return true;
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger outstanding = new AtomicInteger(1);
        final Runnable done = () -> { if (outstanding.decrementAndGet() == 0) finished.countDown(); };
        // A cold FCM wake runs before Telegram's post-init; UserConfig must be loaded before matching.
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.postInitApplication();
            for (int index = 0; index < UserConfig.MAX_ACCOUNT_COUNT; index++) {
                outstanding.incrementAndGet();
                if (!get(index).refreshForHint(accountId, done)) done.run();
            }
            done.run();
        });
        if (Looper.myLooper() != Looper.getMainLooper()) {
            try { finished.await(HINT_WAIT_MS, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return true;
    }

    /** Returns true when an immediate refresh was started; done then runs when it finishes. */
    private boolean refreshForHint(String accountId, Runnable done) {
        synchronized (LOCK) {
            loadLocked();
            if (!config.isConfigured() || config.authHeld || session == null || session.optString("refresh_token").isEmpty()
                    || !accountId.equals(session.optString("account_id"))) return false;
            try { saveLocked(copy(session).put(HINT_RECEIVED, hintsLocked().received().received)); }
            catch (Exception ignored) { /* the in-process refresh below still runs */ }
            long elapsed = SystemClock.elapsedRealtime();
            long delay = hintThrottle.delayFor(elapsed);
            if (delay < 0) return false; // Coalesced into the already scheduled trailing refresh.
            if (delay > 0) {
                hintThrottle = hintThrottle.trailingScheduled();
                final long identity = telegramId, expectedGeneration = generation;
                AndroidUtilities.runOnUIThread(() -> trailingHintRefresh(identity, expectedGeneration, accountId), delay);
                return false;
            }
            hintThrottle = hintThrottle.refreshed(elapsed);
            lastRefreshAttemptElapsed = elapsed;
            refresh(new Callback<Snapshot>() {
                @Override public void onSuccess(Snapshot value) { done.run(); }
                @Override public void onError(Error error) { done.run(); }
            });
            return true;
        }
    }

    /** Trailing edge of the hint throttle; dropped if the slot's identity or binding changed meanwhile. */
    private void trailingHintRefresh(long identity, long expectedGeneration, String accountId) {
        synchronized (LOCK) {
            loadLocked();
            if (telegramId != identity || generation != expectedGeneration || !hintThrottle.trailingScheduled || session == null
                    || session.optString("refresh_token").isEmpty() || !accountId.equals(session.optString("account_id"))) return;
            long elapsed = SystemClock.elapsedRealtime();
            hintThrottle = hintThrottle.refreshed(elapsed);
            lastRefreshAttemptElapsed = elapsed;
            refresh(null);
        }
    }

    /**
     * Firebase token callback. Thread-safe; ignores null/empty/invalid tokens (it never clears a
     * registration). The token is registered to every exact currently linked binding, each through
     * its own authenticated session; bindings of other identities are never touched.
     */
    public static void onPushToken(String fcmToken) {
        if (!ProPushToken.valid(fcmToken)) return;
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.postInitApplication();
            synchronized (LOCK) { pushToken = fcmToken; }
            for (int index = 0; index < UserConfig.MAX_ACCOUNT_COUNT; index++) {
                ZenlyPro pro = get(index);
                synchronized (LOCK) {
                    pro.loadLocked();
                    pro.registerPushTokenLocked();
                }
            }
        });
    }

    /** POST device/push-token for this binding when its registered fingerprint is stale. No rotation, no grant. */
    private void registerPushTokenLocked() {
        final String token = pushToken;
        if (pushInFlight || token == null || config == null || !config.isConfigured() || config.authHeld || session == null
                || session.optString("refresh_token").isEmpty()) return;
        if (!ProPushToken.needsRegistration(token, session.optString(PUSH_REGISTERED, null))) return;
        final String fingerprint = ProPushToken.fingerprint(token);
        if (!pushSchedule.allows(fingerprint, SystemClock.elapsedRealtime())) return;
        pushInFlight = true;
        final Stamp queued = new Stamp(telegramId, generation, session.optString("refresh_token"));
        WORKER.execute(() -> {
            String failure = null;
            int retryAfter = 0;
            try {
                final Stamp stamp;
                synchronized (LOCK) { stamp = restampLocked(queued); requireSessionLocked(stamp); }
                ProTransport.Response response = authenticated(stamp, "device/push-token", "POST",
                        new JSONObject().put("push_token", token), null);
                synchronized (LOCK) {
                    requireCurrentLocked(stamp, true);
                    successfulLocked(response, stamp);
                    if (!Boolean.TRUE.equals(response.body.opt("ok"))) throw malformed();
                    saveLocked(copy(session).put(PUSH_REGISTERED, fingerprint));
                }
            } catch (Error error) {
                failure = error.httpStatus == 422 ? "rejected" : error.code;
                retryAfter = error.retryAfterSeconds;
            } catch (Exception error) {
                failure = "connection_failed";
            }
            synchronized (LOCK) {
                pushInFlight = false;
                if (failure == null || "session_changed".equals(failure) || "link_required".equals(failure)) {
                    // Registered, or the slot's identity/binding moved on and decides anew.
                    pushSchedule = ProPushToken.Schedule.IDLE;
                } else if ("rejected".equals(failure)) {
                    pushSchedule = pushSchedule.rejectedToken(fingerprint);
                } else {
                    pushSchedule = pushSchedule.failed(SystemClock.elapsedRealtime(), retryAfter);
                }
                loadLocked();
                registerPushTokenLocked(); // A newer token or a new identity in this slot.
            }
        });
    }

    /** Token hashes of this binding's unresolved Google attach operations (local read, no network). */
    public Set<String> pendingGoogleAttaches() {
        synchronized (LOCK) {
            loadLocked();
            if (session == null) return Collections.emptySet();
            return GooglePlayAttachKeys.decode(session.optString(GOOGLE_ATTACH_KEYS, null)).pendingTokenHashes();
        }
    }

    /**
     * Linked session required; the backend returns this account's obfuscated id and its configured plans.
     * restore=true ({"purpose":"restore"}, every mode) only reads the existing server binding and the
     * historical products, for Restore and background reconciliation; it never creates a binding, and
     * answers nothing_to_restore without one. restore=false prepares a new purchase: paid mode only.
     */
    public void prepareGooglePlay(boolean restore, Callback<PlayAccount> callback) {
        final Stamp queued = capture();
        async(callback, () -> {
            final Stamp stamp;
            synchronized (LOCK) {
                stamp = restampLocked(queued);
                requireSessionLocked(stamp);
                if (!restore) requirePurchasesAllowedLocked();
            }
            JSONObject body = restore ? new JSONObject().put("purpose", "restore") : new JSONObject();
            // Fresh random 128-bit Idempotency-Key per prepare operation (google-play.json); reused only by
            // authenticated()'s clock_skew retry, which signs fresh device headers.
            ProTransport.Response response = authenticated(stamp, "google/prepare", "POST", body, ProStore.randomHex(16));
            synchronized (LOCK) {
                requireCurrentLocked(stamp, true);
                successfulLocked(response, stamp);
                GooglePlayModel.Catalog catalog;
                try {
                    JSONArray products = response.body.getJSONArray("products");
                    List<GooglePlayModel.Plan> plans = new ArrayList<>();
                    for (int i = 0; i < products.length(); i++) {
                        JSONObject item = products.getJSONObject(i);
                        plans.add(new GooglePlayModel.Plan(item.getString("product_id"), item.getString("base_plan_id")));
                    }
                    catalog = new GooglePlayModel.Catalog(response.body.getString("obfuscated_account_id"), plans);
                } catch (Exception e) { throw malformed(); }
                return new PlayAccount(catalog, stamp.telegramId, session.getString("account_id"));
            }
        });
    }

    /**
     * Sends one Play purchase token for backend verification and acknowledgement, in the commit order
     * of GooglePlayAttachFlow: the Idempotency-Key (by token hash) and an owed fresh signed refresh are
     * persisted together in this binding's encrypted session BEFORE the request. Automatic retries
     * (lost response, foreground/background sync, process restart, a short bounded wait on
     * idempotency_in_flight) reuse the key until a definitive server outcome; newOperation=true
     * (explicit restore) starts a fresh operation. Each HTTP attempt gets a fresh signed nonce. A
     * fresh answer's grant is adopted before the key is completed; a replayed answer only proves the
     * attach committed: its stored entitlement is never adopted and its state is not reported (null);
     * the current signed entitlement is fetched instead. Only signed entitlements change access.
     */
    public void attachGooglePlay(PlayAccount binding, String purchaseToken, boolean newOperation,
                                 Callback<GooglePlayModel.SubscriptionState> callback) {
        if (!GooglePlayModel.validToken(purchaseToken)) {
            fail(callback, new Error("invalid_purchase", "Google Play returned an invalid purchase.", 0, 0));
            return;
        }
        final Stamp queued = capture();
        final String tokenHash = GooglePlayAttachKeys.tokenHash(purchaseToken);
        async(callback, () -> {
            final Stamp stamp;
            synchronized (LOCK) {
                // Same identity/binding as when queued; a refresh token rotated meanwhile is picked up.
                stamp = restampLocked(queued);
                requireSessionLocked(stamp);
                requirePlayAccountLocked(binding);
            }
            return GooglePlayAttachFlow.attach(new GoogleAttach(stamp, binding, purchaseToken), tokenHash, newOperation, ATTACH_ENTROPY);
        });
    }

    /**
     * ZenlyPro side of GooglePlayAttachFlow for one attach. Every step re-checks this slot's Stamp and
     * the Play binding under LOCK, so a stale completion never touches a newer identity or binding;
     * record changes are one locked read-modify-write of the current session (keeping a grant
     * adopted or a push hint received meanwhile).
     */
    private final class GoogleAttach implements GooglePlayAttachFlow.Boundary<GooglePlayModel.SubscriptionState> {
        private final Stamp stamp;
        private final PlayAccount binding;
        private final String purchaseToken;
        private ProTransport.Response response;

        GoogleAttach(Stamp stamp, PlayAccount binding, String purchaseToken) {
            this.stamp = stamp;
            this.binding = binding;
            this.purchaseToken = purchaseToken;
        }

        private void requireBoundLocked() throws Error {
            requireCurrentLocked(stamp, true);
            requirePlayAccountLocked(binding);
        }

        @Override public void preflight() throws Exception {
            synchronized (LOCK) { requireRouteOpenLocked(ProCooldowns.scope(config.apiUrl, "google/attach")); }
        }

        @Override public GooglePlayAttachRecord update(GooglePlayAttachFlow.Change change) throws Exception {
            synchronized (LOCK) {
                requireBoundLocked();
                GooglePlayAttachRecord current = GooglePlayAttachRecord.of(session.optString(GOOGLE_ATTACH_KEYS, null),
                        session.optLong(HINT_RECEIVED, 0), session.optLong(HINT_CONSUMED, 0));
                GooglePlayAttachRecord next = change.apply(current);
                if (next == current) return next;
                JSONObject updated = copy(session).put(GOOGLE_ATTACH_KEYS, next.encodedKeys())
                        .put(HINT_RECEIVED, next.received()).put(HINT_CONSUMED, next.consumed());
                saveLocked(updated);
                return next;
            }
        }

        @Override public GooglePlayAttachFlow.Reply send(String idempotencyKey) throws Exception {
            for (int wait = 0; ; wait++) {
                response = authenticated(stamp, "google/attach", "POST", new JSONObject().put("purchase_token", purchaseToken), idempotencyKey);
                long delay = GooglePlayAttachKeys.inFlightRetryDelayMs(wait, response.status,
                        response.body.optString("error", null), response.retryAfter);
                if (delay < 0) break;
                Thread.sleep(delay); // Bounded (a few seconds) on the Pro worker, never the UI thread.
                synchronized (LOCK) { requireBoundLocked(); }
            }
            return new GooglePlayAttachFlow.Reply(response.status, response.body.optString("error", null), response.replayed);
        }

        @Override public GooglePlayModel.SubscriptionState adoptFresh() throws Exception {
            synchronized (LOCK) {
                requireBoundLocked();
                GooglePlayModel.SubscriptionState state = GooglePlayModel.SubscriptionState.fromWire(response.body.optString("state", null));
                if (state == null || !(response.body.opt("acknowledged") instanceof Boolean)) throw malformed();
                adoptLocked(response.body.getString("entitlement"), null, false);
                return state;
            }
        }

        @Override public void adoptCurrent() throws Exception {
            synchronized (LOCK) { requireBoundLocked(); }
            fetchCurrentEntitlement(stamp);
        }

        @Override public void reject() throws Exception {
            synchronized (LOCK) {
                requireBoundLocked();
                successfulLocked(response, stamp); // Throws the mapped error (401 also clears the refresh token).
            }
        }
    }

    /**
     * Fresh authenticated GET after a replay. owedSequence was persisted before this request, so if
     * the GET fails or the process dies the next foreground still refreshes despite the daily skip;
     * it is consumed only after a successful fresh answer.
     */
    private void adoptCurrentEntitlement(Stamp stamp, long owedSequence) throws Exception {
        fetchCurrentEntitlement(stamp);
        synchronized (LOCK) {
            requireCurrentLocked(stamp, true);
            ProPushHint.Pending owed = hintsLocked();
            ProPushHint.Pending consumed = owed.consumedThrough(owedSequence);
            if (consumed != owed) saveLocked(copy(session).put(HINT_CONSUMED, consumed.consumed));
        }
    }

    /** Fetches, verifies and adopts the current signed entitlement; a replayed answer is never accepted. */
    private void fetchCurrentEntitlement(Stamp stamp) throws Exception {
        ProTransport.Response fresh = authenticated(stamp, "entitlement", "GET", null, null);
        synchronized (LOCK) {
            requireCurrentLocked(stamp, true);
            successfulLocked(fresh, stamp);
            if (fresh.replayed) throw malformed();
            adoptLocked(fresh.body.getString("entitlement"), null, false);
        }
    }

    /** Records that a fresh refresh is owed (same durable sequence as push hints); returns its number. */
    private long oweRefreshLocked(JSONObject updated) throws Exception {
        ProPushHint.Pending owed = hintsLocked().received();
        updated.put(HINT_RECEIVED, owed.received);
        return owed.received;
    }

    /** This binding's persisted hint sequence; a fresh session (relink, other identity) starts with none. */
    private ProPushHint.Pending hintsLocked() {
        return session == null ? ProPushHint.Pending.NONE
                : ProPushHint.Pending.of(session.optLong(HINT_RECEIVED, 0), session.optLong(HINT_CONSUMED, 0));
    }

    /** Same identity and generation as when queued; picks up a refresh token rotated meanwhile. */
    private Stamp restampLocked(Stamp queued) throws Error {
        loadLocked();
        if (queued.telegramId <= 0 || telegramId != queued.telegramId || generation != queued.generation || session == null) {
            throw new Error("session_changed", "The account session changed. Try again.", 0, 0);
        }
        return new Stamp(telegramId, generation, session.optString("refresh_token"));
    }

    /** Local logout is immediate. Revocation never cancels a subscription or removes feature data. */
    public void unlink() { unlink(null); }

    public void unlink(Callback<Snapshot> callback) {
        final Stamp old;
        final Config configuration;
        final String device;
        synchronized (LOCK) {
            loadLocked();
            old = capture();
            configuration = config;
            device = install == null ? "" : install.optString("device_id");
            generation++;
            hintThrottle = ProPushHint.Throttle.IDLE;
            activeLink = null;
            session = null;
            claims = null;
            accessChangedLocked();
            try {
                if (store != null && telegramId > 0) store.delete(accountFile());
            } catch (Exception e) {
                lastMessage = "Could not remove the saved Pro session. Try again.";
                fail(callback, new Error("storage_unavailable", lastMessage, 0, 0));
                return;
            }
            // Enqueue under the lock so a subsequent beginLink() cannot overtake revocation. A test-only held
            // configuration revokes nothing remotely; the local delete above already happened.
            WORKER.execute(() -> {
                if (configuration.isConfigured() && !configuration.authHeld && !old.refreshToken.isEmpty() && !device.isEmpty()) {
                    try {
                        Map<String, String> headers;
                        synchronized (LOCK) { headers = signedHeadersLocked(device, old.refreshToken); }
                        request(configuration, "bind/revoke", "POST", new JSONObject(), headers);
                    } catch (Exception ignored) { /* best effort; local credentials are already gone */ }
                }
                succeed(callback, snapshot());
            });
        }
    }

    private static final class Stamp {
        final long telegramId, generation;
        final String refreshToken;
        Stamp(long telegramId, long generation, String refreshToken) {
            this.telegramId = telegramId; this.generation = generation; this.refreshToken = refreshToken;
        }
    }

    private Stamp capture() {
        synchronized (LOCK) {
            loadLocked();
            return new Stamp(telegramId, generation, session == null ? "" : session.optString("refresh_token"));
        }
    }

    // ---- Tasks cloud (TasksApi only) ------------------------------------------------------

    /**
     * Package-private bridge for TasksApi: the named Tasks routes (sync + realtime metadata/auth) on this slot's own linked
     * binding. No credential, signer, header or path surface leaves ZenlyPro; Tasks errors are never
     * fed to successfulLocked (403 tasks_account_not_linked changes nothing here).
     */
    TasksApi.Boundary tasksBoundary() { return new TasksBoundary(); }

    /** At most one rotation-resolving refresh per minute started by Tasks or customer-data calls. */
    private static final long TASKS_ROTATION_KICK_MS = 60000;

    private static final class TasksAttempt {
        final Stamp stamp;
        final String principal;
        final Config configuration;
        final Map<String, String> headers;
        TasksAttempt(Stamp stamp, String principal, Config configuration, Map<String, String> headers) {
            this.stamp = stamp;
            this.principal = principal;
            this.configuration = configuration;
            this.headers = headers;
        }
    }

    private final class TasksBoundary implements TasksApi.Boundary {
        @Override public TasksApi.Identity identity() {
            synchronized (LOCK) {
                loadLocked();
                return currentTasksIdentityLocked();
            }
        }

        @Override public Object capture(TasksApi.Identity expected) throws TasksApi.Stop {
            synchronized (LOCK) {
                loadLocked();
                if (!expected.equals(currentTasksIdentityLocked())) throw TasksApi.stopFor("session_changed", 0);
                return new Stamp(telegramId, generation, session.optString("refresh_token"));
            }
        }

        @Override public void execute(Runnable work) { WORKER.execute(work); }

        @Override public void deliver(Runnable callback) { AndroidUtilities.runOnUIThread(callback); }

        @Override public Object prepare(Object ticket, TasksApi.Request<?> request) throws TasksApi.Stop {
            synchronized (LOCK) {
                try {
                    // Same identity and generation as at call time; the freshest (possibly rotated) token.
                    Stamp stamp = restampLocked((Stamp) ticket);
                    requireSessionLocked(stamp);
                    if (request.principal == null || !request.principal.equals(session.optString("account_id"))) {
                        throw TasksApi.stopFor("principal_mismatch", 0);
                    }
                    // An unfinished rotation (lost answer, process death) leaves a token the server may
                    // already have replaced: never resend it from Tasks. The existing refresh resolves it.
                    if (session.optLong("rotation_started") > 0) {
                        long elapsed = SystemClock.elapsedRealtime();
                        if (elapsed - lastRefreshAttemptElapsed >= TASKS_ROTATION_KICK_MS) {
                            lastRefreshAttemptElapsed = elapsed;
                            refresh(null);
                        }
                        throw TasksApi.stopFor("rotation_pending", 0);
                    }
                    requireRouteOpenLocked(ProCooldowns.scope(config.apiUrl, request.route));
                    Map<String, String> headers = signedHeadersLocked(install.getString("device_id"), stamp.refreshToken);
                    if (request.idempotencyKey != null) headers.put("Idempotency-Key", request.idempotencyKey);
                    TasksApi.putWireHeader(request, headers); // x-wire-negotiation: X-Tasks-Wire: 4, exactly once.
                    return new TasksAttempt(stamp, request.principal, config, headers);
                } catch (TasksApi.Stop stop) {
                    throw stop;
                } catch (Error error) {
                    throw TasksApi.stopFor(error.code, error.retryAfterSeconds);
                } catch (Exception storage) {
                    throw TasksApi.stopFor("storage_unavailable", 0);
                }
            }
        }

        @Override public ProTransport.TasksReply send(Object attempt, TasksApi.Request<?> request) throws Exception {
            TasksAttempt signed = (TasksAttempt) attempt;
            return ProTransport.tasks(signed.configuration.apiUrl, request.route + request.query, request.method,
                    request.body, signed.headers, request.maxBytes, request.timeoutMs);
        }

        @Override public String apiBaseUrl(Object attempt) { return ((TasksAttempt) attempt).configuration.apiUrl; }

        @Override public void settle(Object attempt, TasksApi.Request<?> request, long serverTimeSeconds, int retryAfter) {
            String scope = ProCooldowns.scope(((TasksAttempt) attempt).configuration.apiUrl, request.route);
            synchronized (LOCK) {
                // Ignores 0; only validated envelopes reach here, and only the configured origin's move its clock.
                if (currentOriginLocked(((TasksAttempt) attempt).configuration)) noteServerTimeLocked(serverTimeSeconds);
                if (retryAfter >= 0) cooldowns = cooldowns.throttled(scope, SystemClock.elapsedRealtime(), retryAfter);
            }
        }

        /** Exact credential of the attempt and its principal are still this slot's current binding. */
        private void requireAttemptLocked(TasksAttempt attempt) throws Error {
            requireCurrentLocked(attempt.stamp, true);
            if (!attempt.principal.equals(session.optString("account_id"))) {
                throw new Error("session_changed", "The account session changed. Try again.", 0, 0);
            }
        }

        @Override public void confirm(Object attempt) throws TasksApi.Stop {
            synchronized (LOCK) {
                try { requireAttemptLocked((TasksAttempt) attempt); }
                catch (Error error) { throw TasksApi.stopFor(error.code, error.retryAfterSeconds); }
            }
        }

        @Override public void invalidate(Object attempt, String code, String reason) throws TasksApi.Stop {
            synchronized (LOCK) {
                try {
                    requireAttemptLocked((TasksAttempt) attempt); // Only the exact credential and principal sent.
                    // Existing real-401 handling: verified JWS and offline grant kept. The binding loss reports the
                    // code and reason of the answer (or of the batch item) TasksApi classified.
                    clearRefreshLocked(code == null ? "session_invalid" : code, reason);
                    lastMessage = "Link this device again to refresh Pro.";
                } catch (Error error) {
                    throw TasksApi.stopFor(error.code, error.retryAfterSeconds);
                } catch (Exception storage) {
                    throw TasksApi.stopFor("storage_unavailable", 0);
                }
            }
        }
    }

    /** Telegram user, linked principal (null without a usable session or while test-held) and generation of this slot. */
    private TasksApi.Identity currentTasksIdentityLocked() {
        String principal = null;
        if (config.isConfigured() && !config.authHeld && store != null && session != null && !session.optString("refresh_token").isEmpty()
                && !session.optString("account_id").isEmpty()) {
            principal = session.optString("account_id");
        }
        return new TasksApi.Identity(telegramId, principal, generation);
    }

    // ---- Customer data (CustomerApi only) -------------------------------------------------

    /**
     * Installs the installation's binding listener (ZenlyCustomer, at process start; null removes it).
     * Delivered on the main thread outside LOCK; see BindingListener.
     */
    public static void setBindingListener(BindingListener listener) { bindingListener = listener; }

    /**
     * Package-private bridge for CustomerApi: the five customer routes on this slot's own linked binding,
     * its session bookkeeping, the final send (logout or user Unlink) and the forget after an erasure. No credential,
     * signer, header or path surface leaves ZenlyPro; customer errors are never fed to successfulLocked.
     */
    CustomerApi.Boundary customerBoundary() { return new CustomerBoundary(); }

    /** The slot's stamp and the binding the call was made for. */
    private static final class CustomerTicket {
        final Stamp stamp;
        final String principal, bindingId;
        CustomerTicket(Stamp stamp, String principal, String bindingId) {
            this.stamp = stamp;
            this.principal = principal;
            this.bindingId = bindingId;
        }
    }

    /** One signed attempt; ticket is null for a final send, at a logout or a user Unlink (nothing of its answer is applied). */
    private static final class CustomerAttempt {
        final CustomerTicket ticket;
        final Config configuration;
        final String route, method;
        final byte[] body;
        final Map<String, String> headers;
        CustomerAttempt(CustomerTicket ticket, Config configuration, String route, String method, byte[] body, Map<String, String> headers) {
            this.ticket = ticket;
            this.configuration = configuration;
            this.route = route;
            this.method = method;
            this.body = body;
            this.headers = headers;
        }
    }

    /** What the final send (logout or user Unlink) keeps after unlink() deleted the session: origin, device id, refresh token. */
    private static final class FinalCapture {
        final Config configuration;
        final String device, refreshToken;
        FinalCapture(Config configuration, String device, String refreshToken) {
            this.configuration = configuration;
            this.device = device;
            this.refreshToken = refreshToken;
        }
    }

    private final class CustomerBoundary implements CustomerApi.Boundary {
        @Override public CustomerApi.Identity identity() {
            synchronized (LOCK) {
                loadLocked();
                return currentCustomerIdentityLocked();
            }
        }

        @Override public Object capture(CustomerApi.Identity expected) throws CustomerApi.Stop {
            synchronized (LOCK) {
                loadLocked();
                if (expected == null || !expected.isLinked() || !expected.equals(currentCustomerIdentityLocked())) {
                    throw CustomerApi.stopFor("session_changed", 0);
                }
                return new CustomerTicket(new Stamp(telegramId, generation, session.optString("refresh_token")),
                        expected.principal, expected.bindingId);
            }
        }

        @Override public void execute(CustomerApi.Lane lane, Runnable work) {
            (lane == CustomerApi.Lane.USER ? WORKER : CUSTOMER_WORKER).execute(work);
        }

        @Override public void deliver(Runnable callback) { AndroidUtilities.runOnUIThread(callback); }

        @Override public Object prepare(Object ticket, String route, String method, byte[] body, String idempotencyKey)
                throws CustomerApi.Stop {
            synchronized (LOCK) {
                try {
                    CustomerTicket captured = (CustomerTicket) ticket;
                    // Same identity and generation as at call time; the freshest (possibly rotated) token. A held,
                    // unconfigured or signed-out slot or a missing link is refused here, before anything is signed.
                    Stamp stamp = restampLocked(captured.stamp);
                    requireSessionLocked(stamp);
                    CustomerTicket current = new CustomerTicket(stamp, captured.principal, captured.bindingId);
                    requireBindingLocked(current);
                    // An unfinished rotation (lost answer, process death) leaves a token the server may already
                    // have replaced: never resend it. The existing refresh resolves it.
                    if (session.optLong("rotation_started") > 0) {
                        long elapsed = SystemClock.elapsedRealtime();
                        if (elapsed - lastRefreshAttemptElapsed >= TASKS_ROTATION_KICK_MS) {
                            lastRefreshAttemptElapsed = elapsed;
                            refresh(null);
                        }
                        throw CustomerApi.stopFor("rotation_pending", 0);
                    }
                    // Install-wide: the server counts per route and presented device, not per account.
                    requireRouteOpenLocked(ProCooldowns.scope(config.apiUrl, route));
                    Map<String, String> headers = signedHeadersLocked(install.getString("device_id"), stamp.refreshToken);
                    if (idempotencyKey != null && CustomerApi.ROUTE_DELETE.equals(route)) headers.put("Idempotency-Key", idempotencyKey);
                    return new CustomerAttempt(current, config, route, method, body, headers);
                } catch (CustomerApi.Stop stop) {
                    throw stop;
                } catch (Error error) {
                    throw CustomerApi.stopFor(error.code, error.retryAfterSeconds);
                } catch (Exception storage) {
                    throw CustomerApi.stopFor("storage_unavailable", 0);
                }
            }
        }

        @Override public ProTransport.CustomerReply send(Object attempt) throws Exception {
            CustomerAttempt signed = (CustomerAttempt) attempt;
            return ProTransport.customer(signed.configuration.apiUrl, signed.route, signed.method, signed.body, signed.headers,
                    ProTransport.CUSTOMER_MAX_BYTES);
        }

        @Override public void settle(Object attempt, long serverTimeSeconds, int retryAfterSeconds) {
            CustomerAttempt signed = (CustomerAttempt) attempt;
            String scope = ProCooldowns.scope(signed.configuration.apiUrl, signed.route);
            synchronized (LOCK) {
                // Ignores 0; only the configured origin's answers move its clock (and serverOffsetSeconds).
                if (currentOriginLocked(signed.configuration)) noteServerTimeLocked(serverTimeSeconds);
                if (retryAfterSeconds >= 0) cooldowns = cooldowns.throttled(scope, SystemClock.elapsedRealtime(), retryAfterSeconds);
            }
        }

        @Override public void confirm(Object attempt) throws CustomerApi.Stop {
            synchronized (LOCK) {
                try { requireCustomerAttemptLocked((CustomerAttempt) attempt); }
                catch (Error error) { throw CustomerApi.stopFor(error.code, error.retryAfterSeconds); }
            }
        }

        @Override public void invalidate(Object attempt, String code, String reason) throws CustomerApi.Stop {
            synchronized (LOCK) {
                try {
                    requireCustomerAttemptLocked((CustomerAttempt) attempt); // Only the exact credential and binding sent.
                    clearRefreshLocked(code == null ? "session_invalid" : code, reason); // Verified JWS and offline grant kept.
                    lastMessage = "Link this device again to refresh Pro.";
                } catch (Error error) {
                    throw CustomerApi.stopFor(error.code, error.retryAfterSeconds);
                } catch (Exception storage) {
                    throw CustomerApi.stopFor("storage_unavailable", 0);
                }
            }
        }

        @Override public Object captureFinal(CustomerApi.Identity expected) throws CustomerApi.Stop {
            synchronized (LOCK) {
                try {
                    loadLocked();
                    if (expected == null || !expected.isLinked() || !expected.equals(currentCustomerIdentityLocked())) {
                        throw CustomerApi.stopFor("session_changed", 0);
                    }
                    requireConfiguredLocked(); // A test-only held configuration never signs.
                    String token = session.optString("refresh_token");
                    if (token.isEmpty()) throw CustomerApi.stopFor("link_required", 0);
                    return new FinalCapture(config, install.getString("device_id"), token);
                } catch (CustomerApi.Stop stop) {
                    throw stop;
                } catch (Error error) {
                    throw CustomerApi.stopFor(error.code, error.retryAfterSeconds);
                } catch (Exception storage) {
                    throw CustomerApi.stopFor("storage_unavailable", 0);
                }
            }
        }

        @Override public Object signFinal(Object captured, byte[] body) throws Exception {
            FinalCapture capture = (FinalCapture) captured;
            synchronized (LOCK) {
                // Signed by this origin's device identity only: never after the origin changed (nor while held).
                if (!currentOriginLocked(capture.configuration) || config.authHeld || store == null || install == null
                        || !capture.device.equals(install.optString("device_id"))) {
                    throw new Error("session_changed", "The account session changed. Try again.", 0, 0);
                }
                requireRouteOpenLocked(ProCooldowns.scope(capture.configuration.apiUrl, CustomerApi.ROUTE_CONSENTS));
                return new CustomerAttempt(null, capture.configuration, CustomerApi.ROUTE_CONSENTS, "POST", body,
                        signedHeadersLocked(capture.device, capture.refreshToken));
            }
        }

        @Override public CustomerApi.Bookkeeping bookkeeping(CustomerApi.Identity expected) {
            synchronized (LOCK) {
                loadLocked();
                if (expected == null || !expected.isLinked() || !expected.equals(currentCustomerIdentityLocked())) return null;
                return bookkeepingLocked();
            }
        }

        @Override public boolean updateBookkeeping(CustomerApi.Identity expected, CustomerApi.BookkeepingChange change) {
            synchronized (LOCK) {
                loadLocked();
                if (expected == null || !expected.isLinked() || !expected.equals(currentCustomerIdentityLocked())) return false;
                CustomerApi.Bookkeeping current = bookkeepingLocked();
                CustomerApi.Bookkeeping next = change.apply(current);
                if (next == null) return false;
                if (next == current) return true;
                try {
                    JSONObject updated = copy(session);
                    if (next.digest == null) updated.remove(PROFILE_DIGEST);
                    else updated.put(PROFILE_DIGEST, next.digest);
                    updated.put(PROFILE_SYNCED_AT, next.syncedAt).put(PROFILE_MIN_INTERVAL, next.minIntervalSeconds);
                    if (next.stopped) updated.put(PROFILE_STOPPED, true);
                    else updated.remove(PROFILE_STOPPED);
                    saveLocked(updated);
                    return true;
                } catch (Exception storage) {
                    return false;
                }
            }
        }

        @Override public boolean forgetSession(long telegramUserId) {
            synchronized (LOCK) {
                loadLocked();
                if (telegramUserId <= 0 || store == null) return false;
                if (telegramId == telegramUserId) {
                    // As unlink(), but no bind/revoke: the server binding was erased with the account.
                    generation++;
                    hintThrottle = ProPushHint.Throttle.IDLE;
                    activeLink = null;
                    session = null;
                    claims = null;
                    accessChangedLocked();
                }
                try {
                    store.delete("account-" + telegramUserId);
                    return true;
                } catch (Exception storage) {
                    return false;
                }
            }
        }
    }

    /** Telegram user, linked principal and binding (null without a usable session or while test-held) and generation of this slot. */
    private CustomerApi.Identity currentCustomerIdentityLocked() {
        String principal = null, bindingId = null;
        if (config.isConfigured() && !config.authHeld && store != null && install != null && session != null
                && !session.optString("refresh_token").isEmpty() && !session.optString("account_id").isEmpty()
                && !session.optString("binding_id").isEmpty()) {
            principal = session.optString("account_id");
            bindingId = session.optString("binding_id");
        }
        return new CustomerApi.Identity(telegramId, principal, bindingId, generation);
    }

    /** The session still holds the ticket's binding (a relink also bumps the generation; this is the defensive check). */
    private void requireBindingLocked(CustomerTicket ticket) throws Error {
        if (session == null || !ticket.principal.equals(session.optString("account_id"))
                || !ticket.bindingId.equals(session.optString("binding_id"))) {
            throw new Error("session_changed", "The account session changed. Try again.", 0, 0);
        }
    }

    /** Exact credential and binding of the attempt are still this slot's current ones (never true for a final send). */
    private void requireCustomerAttemptLocked(CustomerAttempt attempt) throws Error {
        if (attempt.ticket == null) throw new Error("session_changed", "The account session changed. Try again.", 0, 0);
        requireCurrentLocked(attempt.ticket.stamp, true);
        requireBindingLocked(attempt.ticket);
    }

    private CustomerApi.Bookkeeping bookkeepingLocked() {
        String digest = session.optString(PROFILE_DIGEST);
        return new CustomerApi.Bookkeeping(digest.isEmpty() ? null : digest, session.optLong(PROFILE_SYNCED_AT, 0),
                session.optInt(PROFILE_MIN_INTERVAL, CustomerApi.DEFAULT_MIN_INTERVAL_SECONDS), session.optBoolean(PROFILE_STOPPED, false));
    }

    /** A polled session of this slot was saved: the listener learns the new binding (main thread, outside LOCK). */
    private void bindingAdoptedLocked(String bindingId) {
        final BindingListener listener = bindingListener;
        if (listener == null || config == null) return;
        final int slot = account;
        final long identity = telegramId;
        final String authority = config.authority;
        AndroidUtilities.runOnUIThread(() -> listener.onBindingAdopted(slot, identity, authority, bindingId));
    }

    /**
     * A server 401 cleared this slot's binding: code and reason as stable codes (main thread, outside LOCK). Counted in
     * the caller's LOCK section, the one that saved the session without its refresh token, so bindingLossPending() holds
     * until the delivery runs; the delivery lowers it before the listener runs.
     */
    private void bindingLostLocked(String code, String reason) {
        final BindingListener listener = bindingListener;
        if (listener == null || config == null) return;
        lossesUndelivered++;
        final int slot = account;
        final long identity = telegramId;
        final String authority = config.authority;
        final String lostCode = CustomerApi.wireCode(code) == null ? "session_invalid" : code;
        final String lostReason = CustomerApi.wireCode(reason);
        AndroidUtilities.runOnUIThread(() -> {
            synchronized (LOCK) { if (lossesUndelivered > 0) lossesUndelivered--; }
            listener.onBindingLost(slot, identity, authority, lostCode, lostReason);
        });
    }

    /** The key's value when it is a JSON string, else null (never the "null" text of a JSON null). */
    private static String bodyString(JSONObject body, String key) {
        Object value = body == null ? null : body.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private ProTransport.Response authenticated(Stamp stamp, String path, String method, JSONObject body, String idempotency) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            Config configuration;
            Map<String, String> headers;
            synchronized (LOCK) {
                requireSessionLocked(stamp);
                configuration = config;
                headers = signedHeadersLocked(install.getString("device_id"), stamp.refreshToken);
                if (idempotency != null) headers.put("Idempotency-Key", idempotency);
            }
            ProTransport.Response response = request(configuration, path, method, body, headers);
            if (attempt == 0 && response.status == 401 && "clock_skew".equals(response.body.optString("error"))
                    && ProPolicy.utc(response.body.optString("server_time")) > 0) continue;
            return response;
        }
        throw new Error("clock_skew", "Check this device's date and time.", 401, 0);
    }

    private static ProTransport.Response request(Config configuration, String path, String method, JSONObject body, Map<String, String> headers) throws Exception {
        return requestBytes(configuration, path, method, body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), headers);
    }

    private static ProTransport.Response requestBytes(Config configuration, String path, String method, byte[] body, Map<String, String> headers) throws Exception {
        String scope = ProCooldowns.scope(configuration.apiUrl, path);
        synchronized (LOCK) { requireRouteOpenLocked(scope); }
        ProTransport.Response response = ProTransport.requestBytes(configuration.apiUrl, path, method, body, headers);
        synchronized (LOCK) {
            // A replayed (stored) answer carries a historical server_time; never let it move the clock. Nor an
            // answer of an origin replaced meanwhile: it never moves the current origin's clock.
            if (ProReplayPolicy.trustsServerTime(response.replayed) && currentOriginLocked(configuration)) {
                noteServerTimeLocked(ProPolicy.utc(response.body.optString("server_time")));
            }
            if (response.status == 429) cooldowns = cooldowns.throttled(scope, SystemClock.elapsedRealtime(), response.retryAfter);
        }
        return response;
    }

    /** A route (scope) still inside a server Retry-After window is refused locally; the server stays authoritative. */
    private static void requireRouteOpenLocked(String scope) throws Error {
        long remaining = cooldowns.remainingMs(scope, SystemClock.elapsedRealtime());
        if (remaining > 0) throw new Error("rate_limited", "Please wait before trying again.", 429, (int) (remaining / 1000 + 1));
    }

    private static Map<String, String> signedHeadersLocked(String device, String token) throws Exception {
        String nonce = ProStore.randomHex(16);
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L + install.optLong("server_offset"));
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-Device-Id", device);
        headers.put("X-Device-Nonce", nonce);
        headers.put("X-Device-Ts", timestamp);
        headers.put("X-Device-Sig", store.sign(device, nonce, timestamp));
        return headers;
    }

    private void successfulLocked(ProTransport.Response response, Stamp stamp) throws Exception {
        successfulLocked(response, stamp, true);
    }

    /** present = false (headless linking): the mapped Error is thrown without touching the screen message. */
    private void successfulLocked(ProTransport.Response response, Stamp stamp, boolean present) throws Exception {
        if (response.status >= 200 && response.status < 300) return;
        String code = response.body.optString("error", "service_unavailable");
        if (stamp != null && response.status == 401 && !"clock_skew".equals(code) && !"nonce_replayed".equals(code)) {
            requireCurrentLocked(stamp, true);
            // Preserve verified JWS and the paying user's offline grant. The binding loss names the 401's own code.
            String lost = bodyString(response.body, "error");
            clearRefreshLocked(lost == null ? "session_invalid" : lost, bodyString(response.body, "reason"));
        }
        String message;
        switch (code) {
            case "trial_already_used": message = "This account has already used its free trial."; break;
            case "trial_device_cap": message = "This device has used its available account trials."; break;
            case "trial_device_blocked": message = "Trials are unavailable on this device."; break;
            case "trial_paused": message = "Free trials are temporarily unavailable."; break;
            case "bind_paused": message = "Account linking is temporarily unavailable."; break;
            case "rate_limited": message = "Please wait before trying again."; break;
            case "purchase_account_mismatch": message = "This purchase belongs to another Telegram account."; break;
            case "invalid_purchase": case "unsupported_product": message = "Google Play could not verify this purchase."; break;
            case "google_unavailable": case "google_not_configured": message = "Google Play purchases are unavailable right now."; break;
            case "clock_skew": message = "Check this device's date and time."; break;
            case "monetization_disabled":
                message = "Pro is free right now. No purchase or trial is needed.";
                refreshPolicy(); // The server is already free: catch up with its signed policy.
                break;
            default: message = response.status == 401 ? "Link this device again to refresh Pro." : "Pro could not connect. Try again later.";
        }
        if (present) lastMessage = message;
        throw new Error(code, message, response.status, response.retryAfter);
    }

    /**
     * Adopts a signed grant as current access when ProReplayPolicy allows it (never a replayed one,
     * never one older than the current grant). Returns whether it became current.
     */
    private boolean adoptLocked(String token, String rotated, boolean replayed) throws Exception {
        // Persist rotation even when a newly rotated signing key is unknown to this app version.
        if (rotated != null) {
            JSONObject updated = copy(session).put("refresh_token", rotated).put("last_rotation", nowLocked());
            updated.remove("rotation_started");
            saveLocked(updated);
        }
        if (!ProReplayPolicy.adoptsBody(replayed)) return false; // Not even verified: the caller fetches the current grant.
        ProPolicy.Claims verified = verifyLocked(token, session.getString("account_id"), session.getString("binding_id"));
        // Out-of-order delivery must never replace a newer signed grant (e.g. one issued after a revocation).
        if (!ProReplayPolicy.acceptsGrant(verified.issuedAt, claims == null ? -1 : claims.issuedAt)) {
            lastMessage = "";
            return false;
        }
        JSONObject updated = copy(session).put("entitlement", token);
        saveLocked(updated);
        claims = verified;
        accessChangedLocked();
        lastMessage = "";
        return true;
    }

    private ProPolicy.Claims verifyLocked(String jws, String accountId, String bindingId) throws Exception {
        String[] parts = jws.split("\\.", -1);
        if (parts.length != 3 || jws.length() > 32768) throw malformed();
        for (String part : parts) if (!part.matches("[A-Za-z0-9_-]+")) throw malformed();
        JSONObject header = new JSONObject(new String(Base64.decode(parts[0], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
        if (!"ES256".equals(header.optString("alg")) || (header.has("typ") && !"JWT".equals(header.optString("typ")))
                || header.has("crit") || header.has("b64")) throw malformed();
        PublicKey publicKey = config.parsedKeys.get(header.optString("kid"));
        if (publicKey == null) throw new Error("unknown_signing_key", "Update Zenlygram to refresh Pro. Your saved access is preserved.", 0, 0);
        byte[] signature = Base64.decode(parts[2], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (!ProCrypto.verify(publicKey, parts[0], parts[1], signature)) throw malformed();
        JSONObject payload = new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
        // Purpose separation: a monetization policy (aud) signed by the same key ring is never an entitlement.
        if (!"zenlygram".equals(payload.optString("iss")) || payload.has("aud")) throw malformed();
        Set<String> capabilities = new HashSet<>();
        JSONArray entitlement = payload.optJSONArray("ent");
        if (entitlement != null) for (int i = 0; i < entitlement.length(); i++) capabilities.add(entitlement.getString(i));
        ProPolicy.Claims verified = new ProPolicy.Claims(payload.getString("sub"), payload.getString("dev"),
                payload.getLong("tgid"), payload.getLong("iat"), payload.getLong("exp"),
                ProPolicy.utc(payload.optString("until")), payload.optLong("grace_days", 14), capabilities,
                ProPolicy.AccountGrant.parse(typed(payload)));
        if (!verified.belongsTo(telegramId, accountId, bindingId)) {
            throw new Error("account_mismatch", "This Pro session belongs to another Telegram account. Link this account again.", 0, 0);
        }
        return verified;
    }

    private void loadLocked() {
        initializeLocked();
        long current = UserConfig.getInstance(account).getClientUserId();
        if (current == telegramId) return;
        telegramId = current;
        generation++;
        activeLink = null;
        session = null;
        claims = null;
        lastMessage = "";
        pushSchedule = ProPushToken.Schedule.IDLE;
        hintThrottle = ProPushHint.Throttle.IDLE;
        if (current > 0 && store != null) {
            try {
                session = store.read(accountFile());
                if (session != null && session.getLong("telegram_id") != current) throw malformed();
                reverifyLocked();
            } catch (Exception e) {
                session = null;
                claims = null;
                lastMessage = "The saved Pro session could not be opened. Link this account again.";
            }
        }
        accessChangedLocked(); // Identity changed: observers re-read this slot's access.
    }

    private void reverifyLocked() {
        claims = null;
        if (session == null || config == null || !config.isConfigured()) return;
        try { claims = verifyLocked(session.getString("entitlement"), session.getString("account_id"), session.getString("binding_id")); }
        catch (Exception e) { lastMessage = "Reconnect or update Zenlygram to verify the saved Pro session."; }
    }

    private void requireConfiguredLocked() throws Error {
        if (store == null || install == null) throw new Error("storage_unavailable", "Secure Pro storage is unavailable on this device.", 0, 0);
        // A test-only held configuration (authHeldForTesting) is refused here, before anything is signed.
        if (!config.isConfigured() || config.authHeld) throw new Error("not_configured", "Pro linking is not configured in this build.", 0, 0);
        if (telegramId <= 0) throw new Error("login_required", "Sign in to Telegram first.", 0, 0);
    }

    private void requireCurrentLocked(Stamp stamp, boolean checkToken) throws Error {
        loadLocked();
        if (stamp.telegramId <= 0 || telegramId != stamp.telegramId || generation != stamp.generation
                || (checkToken && (session == null || !stamp.refreshToken.equals(session.optString("refresh_token"))))) {
            throw new Error("session_changed", "The account session changed. Try again.", 0, 0);
        }
    }

    private void requireSessionLocked(Stamp stamp) throws Error {
        requireCurrentLocked(stamp, true);
        requireConfiguredLocked();
        if (stamp.refreshToken.isEmpty()) throw new Error("link_required", "Link this Telegram account to Pro first.", 0, 0);
    }

    /** The link is still this slot's current one, and of the caller's kind (manual or headless). */
    private void requireLinkLocked(LinkRequest link, boolean headless) throws Error {
        loadLocked();
        if (link == null || link != activeLink || link.headless != headless || telegramId != link.telegramId
                || generation != link.generation || link.attempt != linkAttempt) {
            throw new Error("link_changed", "Get a new linking code.", 0, 0);
        }
    }

    private void requirePlayAccountLocked(PlayAccount binding) throws Error {
        if (binding == null || binding.telegramId != telegramId || session == null
                || !binding.accountId.equals(session.optString("account_id"))) {
            throw new Error("session_changed", "The account session changed. Try again.", 0, 0);
        }
    }

    private void requireLinkAttemptLocked(long attempt) throws Error {
        if (attempt != linkAttempt) throw new Error("link_changed", "Get a new linking code.", 0, 0);
    }

    /**
     * Encrypted atomic write of this binding's session; memory changes only after it succeeds. A device
     * storage failure is reported as storage_unavailable (never as a connection problem); a ZenlyPro
     * Error passes through unchanged. Whatever was on disk before stays authoritative.
     */
    private void saveLocked(JSONObject value) throws Exception {
        try {
            store.write(accountFile(), value);
        } catch (Error error) {
            throw error;
        } catch (Exception storage) {
            throw new Error("storage_unavailable", "Secure Pro storage is unavailable on this device.", 0, 0);
        }
        session = value;
    }

    /**
     * Drops this binding's refresh token (the verified JWS and offline grant stay). serverCode is the
     * error code of the server 401 that caused it (reason: its body's reason, or null); only then is
     * the binding loss reported. Null serverCode = the local rotation give-up: no server answer, no report.
     */
    private void clearRefreshLocked(String serverCode, String reason) throws Exception {
        JSONObject updated = copy(session);
        updated.remove("refresh_token");
        updated.remove("rotation_started");
        saveLocked(updated);
        statusChangedLocked(decisionLocked()); // A background 401 unlinks: the Pro screen must show it now.
        if (serverCode != null) bindingLostLocked(serverCode, reason);
    }

    private String accountFile() { return "account-" + telegramId; }

    /**
     * The decision under the policy in effect: its mode and its local grace length, both from the last accepted
     * signed token (ProMonetization.Policy), so a newly accepted policy changes the answer of the next call.
     */
    private ProPolicy.Decision decisionLocked() {
        long now = nowLocked();
        return ProPolicy.evaluate(claims, now, graceStartLocked(now), config.isConfigured(), policy.mode, policy.localGraceDays);
    }

    /**
     * Start of the paid-mode local grace: the later of first_launch and the first adopted paid policy (0 without a
     * record), never after {@code now}. The mark is minted from trusted time; a later validated server time may
     * move trusted time back behind it (the clock ran ahead at the mint), and the grace must not be closed until
     * time catches up with the mark.
     */
    private static long graceStartLocked(long now) {
        if (install == null) return 0;
        long firstPaid = install.optLong(FIRST_PAID);
        return ProPolicy.graceStart(install.optLong("first_launch"), firstPaid > 0 ? firstPaid : firstPaidUnsaved, now);
    }

    /**
     * Mints first_paid once, on the first evaluation under an adopted paid policy (its adoption, or the load of a
     * stored paid policy whose record predates the mark). A failed write keeps the mark for this process only.
     */
    private static void mintFirstPaidLocked() {
        if (install == null || store == null || policy.mode != ProPolicy.Mode.PAID) return;
        if (install.optLong(FIRST_PAID) > 0 || firstPaidUnsaved > 0) return;
        long now = nowLocked(); // May persist the clock mark: read before the record is copied.
        try {
            JSONObject updated = copy(install).put(FIRST_PAID, now);
            store.write("install", updated);
            install = updated;
        } catch (Exception e) { firstPaidUnsaved = now; }
    }

    /**
     * The one-time "Zenlygram Pro is now a subscription" notice (iOS takePaidModeNotice): true exactly once after
     * an adopted free policy gave way to a paid one, and only while the policy is still paid. Asked by the app's
     * create gate alone, when it is about to show the Pro screen to an account that never paid. Any thread.
     */
    public static boolean takePaidModeNotice() {
        synchronized (LOCK) {
            initializeLocked();
            if (install == null || store == null || policy.mode != ProPolicy.Mode.PAID || !install.optBoolean(PAID_NOTICE)) return false;
            try {
                JSONObject updated = copy(install);
                updated.remove(PAID_NOTICE);
                install = updated; // Shown now: never twice in this process, whatever the write does.
                store.write("install", updated);
            } catch (Exception ignored) { /* at worst it is shown once more after a restart */ }
            return true;
        }
    }

    /**
     * After a committed access-relevant change, or at a timer deadline: publishes
     * zenlygramAccessChanged (via ZenlySurfaces, UI thread, no payload) only if this slot's decision
     * (identity, generation, state, granted capabilities) actually changed, then re-arms the single
     * deadline timer at the next ProPolicy boundary on trusted time (signed until, exp + grace, the
     * start and end of the local grace at the accepted policy's length), bounded by ProAccessSchedule.MAX_WAIT.
     */
    private void accessChangedLocked() {
        ProPolicy.Decision decision = decisionLocked();
        StringBuilder signature = new StringBuilder().append(telegramId).append('|').append(generation).append('|').append(decision.state);
        for (Capability capability : Capability.values()) signature.append(decision.grants(capability.wire) ? '1' : '0');
        String current = signature.toString();
        boolean changed = accessSignature != null && !current.equals(accessSignature);
        accessSignature = current;
        long now = nowLocked();
        long next = ProAccessSchedule.nextBoundary(claims, now, graceStartLocked(now), policy.mode, policy.localGraceDays);
        scheduleAccessCheckLocked(ProAccessSchedule.delaySeconds(next, now) * 1000L);
        if (changed) ZenlySurfaces.onAccessChanged(account);
        statusChangedLocked(decision);
    }

    /**
     * Publishes zenlygramProStatusChanged (UI thread, no payload) when anything the Pro status shows
     * changed: identity, access state, signed until, linked or configured. Presentation only; privacy
     * side effects stay on zenlygramAccessChanged, so a renewed expiry date never closes locked media.
     */
    private void statusChangedLocked(ProPolicy.Decision decision) {
        long until = decision.displayedUntil();
        boolean linked = session != null && !session.optString("refresh_token").isEmpty();
        String current = telegramId + "|" + generation + "|" + decision.state + "|" + until + "|" + linked + "|" + config.isConfigured()
                + "|" + policy.mode;
        boolean changed = statusSignature != null && !current.equals(statusSignature);
        statusSignature = current;
        if (!changed) return;
        final int slot = account;
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(slot).postNotificationName(NotificationCenter.zenlygramProStatusChanged));
    }

    /** One cancellable timer per slot; a superseded timer (newer transition re-armed) does nothing. */
    private void scheduleAccessCheckLocked(long delayMs) {
        if (accessTimer != null) AndroidUtilities.cancelRunOnUIThread(accessTimer);
        final Runnable timer = new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    if (accessTimer != this) return;
                    accessTimer = null;
                    long before = generation;
                    loadLocked(); // An identity change here already re-evaluated and re-armed.
                    if (generation == before) accessChangedLocked();
                }
            }
        };
        accessTimer = timer;
        AndroidUtilities.runOnUIThread(timer, delayMs);
    }

    /** Opens the configured origin's own store and install record (a new origin gets a new device_id, first_launch and clock mark). */
    private static void initializeLocked() {
        if (config == null) config = resourceConfig();
        if (initializationAttempted) return;
        initializationAttempted = true;
        try {
            store = new ProStore(ApplicationLoader.applicationContext, config.storageToken);
            install = store.read("install");
            if (install == null) {
                long now = System.currentTimeMillis() / 1000L;
                install = new JSONObject().put("first_launch", now).put("device_id", ProStore.randomHex(32))
                        .put("clock_mark", now).put("clock_elapsed", SystemClock.elapsedRealtime())
                        .put("clock_boot", bootCount()).put("clock_boot_wall", System.currentTimeMillis() - SystemClock.elapsedRealtime());
                store.write("install", install);
            }
            if (!install.getString("device_id").matches("[a-f0-9]{64}") || install.getLong("first_launch") <= 0) throw malformed();
            serverOffsetSeconds = install.optLong("server_offset");
            loadPolicyLocked();
        } catch (Exception e) {
            install = null;
            store = null;
            serverOffsetSeconds = 0;
            initializationError = "Secure Pro storage is unavailable. Existing protected content remains accessible.";
        }
    }

    // ---- Free-first monetization policy ---------------------------------------------------

    /**
     * Fetches GET /v1/monetization asynchronously on launch, foreground and when the Pro screen opens,
     * independent of any account binding: no credentials or device headers, one request in flight,
     * attempts at least a minute apart, bounded timeouts and body. It asks for the Android line of the policy
     * (ProMonetization.POLICY_PATH, ACCEPT: ver 4) and reads nothing else. Only a verified signed policy that is newer than
     * the current one changes the mode or the local grace length; any failure, a refused token included,
     * keeps the last verified policy.
     */
    public static void refreshPolicy() {
        final Config configuration;
        final String scope;
        synchronized (LOCK) {
            initializeLocked();
            if (store == null || install == null || !config.isConfigured()) return;
            long elapsed = SystemClock.elapsedRealtime();
            scope = ProCooldowns.scope(config.apiUrl, POLICY_PATH);
            if (cooldowns.remainingMs(scope, elapsed) > 0) return;
            ProMonetization.Refresh started = policyRefresh.start(elapsed);
            if (started == null) return;
            policyRefresh = started;
            configuration = config;
        }
        POLICY_WORKER.execute(() -> {
            try {
                ProTransport.Text response = ProTransport.getPublic(configuration.apiUrl, POLICY_PATH, ProMonetization.ACCEPT,
                        ProMonetization.MAX_RESPONSE_BYTES, ProMonetization.REQUEST_TIMEOUT_MS);
                synchronized (LOCK) {
                    if (response.status == 429) cooldowns = cooldowns.throttled(scope, SystemClock.elapsedRealtime(), response.retryAfter);
                    if (response.status == 200 && configuration == config) acceptPolicyLocked(response.body);
                }
            } catch (Exception ignored) {
                // Offline, 503 monetization_unavailable, or an unverifiable answer: the last verified mode stays.
            } finally {
                synchronized (LOCK) { policyRefresh = policyRefresh.finish(); }
            }
        });
    }

    /**
     * Verifies and adopts one GET /v1/monetization response body exactly like a fetched one (pinned
     * keys, purpose, fetch-time limits, ordering, persistence). Used by refreshPolicy and by the
     * native instrumentation smoke test, which feeds backend-issued envelopes; it can only adopt
     * what the configured pinned key ring signed.
     */
    public static void acceptPolicyResponse(String body) throws Error {
        synchronized (LOCK) {
            initializeLocked();
            acceptPolicyLocked(body);
        }
    }

    private static void acceptPolicyLocked(String body) throws Error {
        if (store == null || install == null) throw storageUnavailable();
        ProMonetization.Policy next;
        try {
            if (body == null || body.getBytes(StandardCharsets.UTF_8).length > ProMonetization.MAX_RESPONSE_BYTES) {
                throw new ProMonetization.Rejected(ProMonetization.Reason.MALFORMED);
            }
            Map<String, Object> envelope;
            try { envelope = POLICY_JSON.object(body.getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new ProMonetization.Rejected(ProMonetization.Reason.MALFORMED); }
            String token = ProMonetization.policyToken(envelope);
            long now = ProMonetization.policyNow(System.currentTimeMillis() / 1000L, policy);
            next = ProMonetization.accept(policy, ProMonetization.verifyFetched(token, config.parsedKeys, POLICY_JSON, now));
        } catch (ProMonetization.Rejected rejected) {
            throw new Error("policy_" + rejected.reason.name().toLowerCase(Locale.US),
                    "Pro could not verify the access policy. Your current access is unchanged.", 0, 0);
        }
        if (next == policy) return;
        long adoptedAt = nowLocked(); // May persist the clock mark: read before the record is copied.
        try {
            JSONObject updated = copy(install).put(POLICY_KEY, next.token);
            // The first adopted paid policy starts the local grace (at any length, 0 included: the start is minted
            // once and a later length counts from it); the flip from an adopted free policy owes its notice.
            if (next.mode == ProPolicy.Mode.PAID && updated.optLong(FIRST_PAID) <= 0) {
                updated.put(FIRST_PAID, firstPaidUnsaved > 0 ? firstPaidUnsaved : adoptedAt);
            }
            if (ProMonetization.noticeOwed(policy, next, updated.optBoolean(PAID_NOTICE))) updated.put(PAID_NOTICE, true);
            else updated.remove(PAID_NOTICE);
            store.write("install", updated);
            install = updated;
        } catch (Exception e) { throw storageUnavailable(); }
        ProPolicy.Mode before = policy.mode;
        policy = next;
        policyChangedLocked(before);
    }

    /** The stored token reverified with the current key ring; INITIAL (free) when absent or unverifiable. */
    private static void loadPolicyLocked() {
        policy = ProMonetization.INITIAL;
        dropGeneralLineLocked();
        String token = install == null || !install.has(POLICY_KEY) ? null : install.optString(POLICY_KEY, null);
        if (token == null || config == null) return;
        try { policy = ProMonetization.verifyStored(token, config.parsedKeys, POLICY_JSON); }
        catch (Exception ignored) { /* e.g. its key is no longer pinned: free until a newer one verifies */ }
        mintFirstPaidLocked(); // A paid policy stored before the mark existed starts its grace at this first evaluation.
    }

    /** Removes what a build of the general line left in the install record (see POLICY_KEY). Best effort. */
    private static void dropGeneralLineLocked() {
        if (install == null || store == null) return;
        boolean found = false;
        for (String key : LEGACY_POLICY_KEYS) found |= install.has(key);
        if (!found) return;
        try {
            JSONObject updated = copy(install);
            for (String key : LEGACY_POLICY_KEYS) updated.remove(key);
            store.write("install", updated);
            install = updated;
        } catch (Exception ignored) { /* the keys are never read; the next load tries again */ }
    }

    /**
     * A newly accepted policy applies at once: every slot re-decides under it (its mode and its local grace
     * length: a shorter one can end a running grace, a longer one can reopen an ended one), re-publishes its
     * access and status (the "free until" date is part of the status) and re-arms its deadline timer at the
     * new boundary. When paid was just enabled, a slot holding only a legacy entitlement (no genuine account
     * claims) requests its bounded refresh right away.
     */
    private static void policyChangedLocked(ProPolicy.Mode before) {
        boolean enabledPaid = before != ProPolicy.Mode.PAID && policy.mode == ProPolicy.Mode.PAID;
        for (ZenlyPro pro : INSTANCES.values()) {
            pro.accessChangedLocked();
            if (enabledPaid && ProMonetization.accountRefreshDue(policy.mode, pro.claims)) pro.refreshEntitlementIfDueLocked();
        }
    }

    /** Free mode: no trial start and no new-purchase prepare, checked under LOCK right before the request. */
    private static void requirePurchasesAllowedLocked() throws Error {
        if (!ProMonetization.allowsNewPurchase(policy.mode)) {
            throw new Error("monetization_disabled", "Pro is free right now. No purchase or trial is needed.", 409, 0);
        }
    }

    private static Error storageUnavailable() {
        return new Error("storage_unavailable", "Secure Pro storage is unavailable on this device.", 0, 0);
    }

    /** Android's org.json, normalized to the typed values ProMonetization and ProPolicy.AccountGrant read. */
    private static final ProMonetization.Decoder POLICY_JSON = utf8 -> {
        JSONTokener tokener = new JSONTokener(new String(utf8, StandardCharsets.UTF_8));
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("Not one JSON object");
        return typed((JSONObject) value);
    };

    private static Map<String, Object> typed(JSONObject object) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, typedValue(object.get(key)));
        }
        return result;
    }

    private static Object typedValue(Object value) throws Exception {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return typed((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> items = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) items.add(typedValue(array.get(i)));
            return items;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number) return ((Number) value).doubleValue();
        return value; // String or Boolean
    }

    private static Config resourceConfig() {
        // ZENLYGRAM 13.2.1: the packaged origin and pinned keys are ZgCloudConfig's, the one source the public policies
        // use too. They are not string resources here: a release build moves strings out of the APK resources
        // (TelegramStringsTask), so a lookup by name would find nothing.
        try {
            return new Config(org.telegram.zenlygram.cloud.ZgCloudConfig.BASE_URL, org.telegram.zenlygram.cloud.ZgCloudConfig.pinnedKeys());
        } catch (Exception e) { return new Config("", Collections.emptyMap()); }
    }

    private static long nowLocked() {
        long wall = System.currentTimeMillis() / 1000L;
        if (install == null) return wall;
        long elapsed = SystemClock.elapsedRealtime();
        int boot = bootCount();
        boolean sameBoot = clockAnchoredInThisProcess || (boot >= 0 ? boot == install.optInt("clock_boot", -1)
                : Math.abs((System.currentTimeMillis() - elapsed) - install.optLong("clock_boot_wall")) < 120000);
        long now = ProPolicy.effectiveNow(wall, install.optLong("clock_mark"), install.optLong("clock_elapsed"), elapsed, sameBoot);
        // Persist the offline high-water mark periodically; a reboot cannot reset the last known time.
        if (!clockAnchoredInThisProcess || elapsed - lastClockPersist > 60000) {
            long checkpoint = now;
            // Do not repeatedly promote a fast wall clock's allowance into the trusted mark.
            // Otherwise every check could advance the mark by another two days.
            if (sameBoot && elapsed >= install.optLong("clock_elapsed")) {
                checkpoint = ProPolicy.saturatingAdd(install.optLong("clock_mark"),
                        (elapsed - install.optLong("clock_elapsed")) / 1000L);
            }
            try { setClockLocked(Math.max(install.optLong("clock_mark"), checkpoint)); }
            catch (Exception ignored) { }
        }
        return now;
    }

    /** The answer came from the origin still configured (same storage token). */
    private static boolean currentOriginLocked(Config from) {
        return config != null && from != null && config.storageToken.equals(from.storageToken);
    }

    private static void noteServerTimeLocked(long time) {
        if (install == null || time <= 0) return;
        try {
            long wall = System.currentTimeMillis() / 1000L;
            install.put("server_offset", time - wall);
            serverOffsetSeconds = time - wall;
            long existing = nowLocked();
            // Reject catastrophic forward server clock jumps, but permit recovery from a bad mark.
            if (time <= Math.max(existing, wall) + ProPolicy.CLOCK_TOLERANCE
                    && (time > existing || time < existing - ProPolicy.CLOCK_TOLERANCE)) setClockLocked(time);
        } catch (Exception ignored) { }
    }

    private static void setClockLocked(long time) throws Exception {
        JSONObject updated = copy(install).put("clock_mark", time).put("clock_elapsed", SystemClock.elapsedRealtime())
                .put("clock_boot", bootCount()).put("clock_boot_wall", System.currentTimeMillis() - SystemClock.elapsedRealtime());
        store.write("install", updated);
        install = updated;
        lastClockPersist = SystemClock.elapsedRealtime();
        clockAnchoredInThisProcess = true;
    }

    private static int bootCount() {
        try { return Settings.Global.getInt(ApplicationLoader.applicationContext.getContentResolver(), "boot_count", -1); }
        catch (Exception e) { return -1; }
    }

    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static Error malformed() { return new Error("invalid_response", "Pro could not verify the server response. Saved access is preserved.", 0, 0); }
    private interface Operation<T> { T run() throws Exception; }

    private static <T> void async(Callback<T> callback, Operation<T> operation) { async(WORKER, callback, operation); }

    /** Runs operation on lane (WORKER, LINK_WORKER or CUSTOMER_WORKER); its outcome reaches callback on the UI thread. */
    private static <T> void async(ExecutorService lane, Callback<T> callback, Operation<T> operation) {
        lane.execute(() -> {
            try { succeed(callback, operation.run()); }
            catch (Error error) { fail(callback, error); }
            catch (Exception error) { fail(callback, new Error("connection_failed", "Pro could not connect securely. Try again later.", 0, 0)); }
        });
    }

    private static <T> void succeed(Callback<T> callback, T value) {
        if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.onSuccess(value));
    }

    private static void fail(Callback<?> callback, Error error) {
        if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.onError(error));
    }
}
