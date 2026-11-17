package org.telegram.messenger.zenlygram.customer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.pro.CustomerApi;
import org.telegram.messenger.zenlygram.pro.ProAutoLink;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRepository;
import org.telegram.ui.Components.ForegroundDetector;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The customer-data coordinator (design 50 §10, §12; contract §6.4–§6.8): the lifecycle, the per-account upload order of
 * CustomerPipeline (link → consents → profile sync → events → in-process confirmation → Tasks readiness), the binding
 * listener, withdrawal, deletion and logout. ZenlyConsent holds the consent state, CustomerApi (inside ZenlyPro) sends,
 * ZenlyEventUploader flushes statistics, ProAutoLink links; this class decides when.
 * <p>
 * Lifecycle: {@link #onPostInit} (ApplicationLoader) loads the consent store and this store, feeds every recorded binding
 * loss to the consent snapshot, stores needs_reconsent for every grant whose text this build renders with another hash
 * (ZenlyConsent.normaliseChangedTexts), purges the customer data of Telegram users no longer signed in, runs again every
 * event purge the stored consent state asks for (a withdrawal, a binding loss or stale grant, a rejected grant) and, for a
 * withdrawn account, the cloud Tasks discard and the removal of the profile receipt, and installs the seams
 * (consent provider and binding listener of ZenlyPro, ProAutoLink's gate and listener, ZenlyStats' deletion gate, the
 * event queue listener, the observers). Nothing is sent before the first foreground of this process and its first idle
 * (8 s); a push-started process never links, syncs or flushes. The one exception is the pre-revoke send of pending
 * withdrawals at a logout ({@link #onLogout}) or a user Unlink ({@link #onUserUnlink}).
 * <p>
 * Threads: the main thread runs everything here; {@link #hasEffectiveGrant}, {@link #linkGrantJson},
 * {@link #deletionPending} and {@link #tasksMayRun} are read on any thread and only read immutable or volatile state.
 * Stores: {@code SharedPreferences("zenlygram_customer")} holds {@code binding.v1.<tg>@<authority>} and
 * {@code delete.v1.<tg>@<authority>} (beside ProAutoLink's {@code autolink.v1.*}), each written with {@code commit()}.
 * Nothing here logs.
 */
public final class ZenlyCustomer {
    static final String PREFERENCES = "zenlygram_customer";
    static final String BINDING_PREFIX = "binding.v1.", DELETE_PREFIX = "delete.v1.", AUTOLINK_PREFIX = "autolink.v1.";
    /** The first idle after the first foreground (contract §6.5, iOS SGEntitlementScheduler.firstIdleDelay). */
    static final long FIRST_IDLE_MS = 8_000;
    /** The coordinator re-checks at least this often while it waits for something. */
    private static final long MAX_WAKE_MS = 3_600_000;
    private static final long MIN_WAKE_MS = 250;
    private static final int SLOTS = UserConfig.MAX_ACCOUNT_COUNT;

    /** The deletion screen hears every state of a run (main thread). */
    public interface DeleteListener {
        void onDeleteState(int account, DeleteFlow.Outcome outcome);
    }

    /** GET /v1/profile/me: the answer body (one JSON object) or a stable error code. */
    public static final class MyData {
        /** The 200 body, or null. */
        public final String body;
        /** Null for a body; else the answer's or a local stable code (link_required, rate_limited, connection_failed, …). */
        public final String error;
        public final int retryAfterSeconds;

        MyData(String body, String error, int retryAfterSeconds) {
            this.body = body;
            this.error = error;
            this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
        }
    }

    public interface MyDataCallback {
        void done(MyData data);
    }

    // ---- State (main thread unless noted) ----

    private static boolean started, foreground, foregroundSeen, firstIdlePassed;
    private static Runnable firstIdle;
    private static volatile Store store;
    private static final Slot[] SLOT = new Slot[SLOTS];
    /** Per slot: the Telegram user whose Tasks may run (0 = none). Any-thread reads. */
    private static final AtomicLongArray READY = new AtomicLongArray(SLOTS);
    /** The effective consent state last seen per Telegram user (withdrawal / re-grant transitions). */
    private static final HashMap<Long, ConsentMachine.State> SEEN = new HashMap<>();
    private static final CustomerPipeline.Admission ADMISSION = new CustomerPipeline.Admission(CustomerBackoff.INSTALL_BUDGET);
    private static final Random RANDOM = new SecureRandom();
    private static final boolean[] DIRTY = new boolean[SLOTS];
    private static int depth;

    private ZenlyCustomer() {}

    /** One account slot's in-process facts (reset when its user or binding changes). */
    private static final class Slot {
        final int account;
        long tg;
        /** The binding the facts below belong to (null = unlinked). */
        String bindingId;
        /** Fences callbacks of an earlier user or binding. */
        long epoch;
        boolean busy;
        boolean confirmed, confirmResent, confirmFlushTried, resendGrant, reconcileUsed;
        String droppedDigest, seenDigest;
        long seenDigestMs = -1;
        CustomerBackoff.State consents = CustomerBackoff.State.IDLE, sync = CustomerBackoff.State.IDLE;
        boolean flushWanted;
        long flushRetryAtMs, nextFlushMs;
        Runnable wake;
        Deletion deletion;

        Slot(int account) { this.account = account; }

        /** Another binding (or none): nothing learned of the previous one counts. */
        void rebind(String id) {
            bindingId = id;
            epoch++;
            busy = false;
            confirmed = confirmResent = confirmFlushTried = resendGrant = false;
            droppedDigest = seenDigest = null;
            seenDigestMs = -1;
            consents = sync = CustomerBackoff.State.IDLE;
            flushRetryAtMs = 0;
        }

        /** Another user (or none). */
        void reset(long user) {
            rebind(null);
            tg = user;
            reconcileUsed = false;
            flushWanted = false;
            nextFlushMs = 0;
            if (deletion != null) deletion.cancel();
            deletion = null;
        }
    }

    // ---- Lifecycle entry points ----

    /**
     * ApplicationLoader.postInitApplication (main thread; reposts itself otherwise), with the raw Telegram ids of the
     * signed-in accounts. Local work only.
     */
    public static void onPostInit(final long[] signedInUserIds) {
        if (!onMain(() -> onPostInit(signedInUserIds)) || started) return;
        Store current = store();
        if (current == null) return;
        ZenlyConsent.load();
        String authority = ZenlyPro.authority();
        long now = ZenlyPro.trustedNowSeconds();
        reconcileSignedOut(current, signedInUserIds);
        for (int account = 0; account < SLOTS; account++) {
            long tg = activatedUser(account);
            if (tg <= 0) continue;
            if (authority != null) {
                ZenlyConsent.setBindingLoss(tg, current.binding(tg, authority, now).consentLoss());
                DeleteFlow.Record record = current.deletion(tg, authority);
                // A deletion that completed while its confirmation was on screen when the process ended.
                if (record != null && record.state == DeleteFlow.State.DONE) completeErasure(account, tg, authority, false);
            }
        }
        // A grant whose text this build renders with another hash (the operator values changed) is stored as
        // needs_reconsent for every stored account before SEEN, the purges below and every gate read it; after the
        // losses were fed, so a grant whose link ended keeps that notice and its purge.
        ZenlyConsent.normaliseChangedTexts();
        for (int account = 0; account < SLOTS; account++) {
            long tg = activatedUser(account);
            if (tg <= 0) continue;
            ConsentSnapshot snapshot = ZenlyConsent.snapshot(tg);
            ConsentMachine.State state = snapshot.effectiveState();
            SEEN.put(tg, state);
            // A withdrawal, a binding loss, a stale or a rejected grant commits at once, but the event purge it asks for
            // (and a withdrawal's Tasks discard) runs later on other threads: a process that ended first left it undone,
            // and a later grant and link would upload what it drops (contract §6.7). Each is idempotent and nothing is
            // recorded meanwhile, so it runs again at every launch while the stored state asks for it.
            if (eventsPurged(snapshot)) purgeEvents(account, tg);
            if (withdrawn(state)) {
                discardTasks(account, tg);
                forgetProfileSync(CustomerApi.forAccount(account), tg);
            }
        }
        ZenlyPro.setConsentProvider(ZenlyCustomer::linkGrantJson);
        ZenlyPro.setBindingListener(BINDING);
        ProAutoLink.setGate(ZenlyCustomer::effectiveGrantAt);
        ProAutoLink.setListener(AUTO_LINK);
        ZenlyStats.install(ZenlyCustomer::deletionPending);
        ZenlyEventUploader.setQueueListener((account, queued) -> {
            if (!validSlot(account)) return;
            slot(account).flushWanted = true;
            evaluate(account);
        });
        NotificationCenter.getGlobalInstance().addObserver(OBSERVER, NotificationCenter.zenlygramConsentChanged);
        for (int account = 0; account < SLOTS; account++) {
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(OBSERVER, NotificationCenter.mainUserInfoChanged);
            center.addObserver(OBSERVER, NotificationCenter.currentUserPremiumStatusChanged);
            center.addObserver(OBSERVER, NotificationCenter.zenlygramProStatusChanged);
        }
        started = true;
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector != null) {
            detector.addListener(FOREGROUND);
            if (detector.isForeground()) onForeground();
        }
    }

    /** LoginActivity: {@code account} signed in (main thread). */
    public static void onAuthSuccess(final int account) {
        if (!onMain(() -> onAuthSuccess(account)) || !validSlot(account)) return;
        long tg = activatedUser(account);
        if (tg <= 0) return;
        Store current = store();
        String authority = ZenlyPro.authority();
        if (current != null && authority != null) {
            ZenlyConsent.setBindingLoss(tg, current.binding(tg, authority, ZenlyPro.trustedNowSeconds()).consentLoss());
        }
        SEEN.put(tg, ZenlyConsent.snapshot(tg).effectiveState());
        slot(account).reset(tg);
        evaluate(account);
    }

    /** LaunchActivity.switchToAccount: the session follows the selected account; both accounts are re-evaluated. */
    public static void onAccountSwitch(final int previousAccount, final int nextAccount) {
        if (!onMain(() -> onAccountSwitch(previousAccount, nextAccount))) return;
        ZenlyStats.onAccountSwitch(previousAccount, nextAccount);
        evaluate(previousAccount);
        evaluate(nextAccount);
    }

    /**
     * MessagesController.performLogout, before ZenlyPro.unlink() (any thread; the final send is queued at once, the purge
     * runs on the main thread): the session ends; when linked, the pending withdrawals are queued on the user lane ahead
     * of the bind/revoke that unlink() queues; then the consent record, outbox, rejected entries and every acked set, the
     * event rows of the user in every origin, and the autolink, binding and delete keys go.
     */
    public static void onLogout(final int account, final long telegramUserId) {
        if (!validSlot(account) || telegramUserId <= 0) return;
        queueFinalWithdrawals(account, telegramUserId);
        Runnable purge = () -> {
            ZenlyStats.onLogout(account);
            purge(account, telegramUserId);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            purge.run();
        } else {
            AndroidUtilities.runOnUIThread(purge);
        }
    }

    /**
     * ZenlyProActivity's Unlink, right before ZenlyPro.unlink() (any thread; queued at once, never re-posted, so it stays
     * ahead of the bind/revoke that unlink() queues): when linked, the user's pending withdrawals are queued on the user
     * lane, as at a logout (design 50 §7.4; iOS's logout final send runs at a user Unlink too). Nothing is purged: the
     * consent record keeps its outbox, so a withdrawal this send delivered goes again after the next link and is answered
     * duplicate.
     */
    public static void onUserUnlink(final int account) {
        long tg = activatedUser(account);
        if (tg > 0) queueFinalWithdrawals(account, tg);
    }

    /** The account's consent changed (also observed from {@code zenlygramConsentChanged}). */
    public static void onConsentChanged(final int account) {
        if (!onMain(() -> onConsentChanged(account)) || !validSlot(account)) return;
        batch(() -> {
            long tg = activatedUser(account);
            if (tg > 0) consentTransition(account, tg);
            evaluate(account);
        });
    }

    private static void onForeground() {
        foreground = true;
        ZenlyStats.onForeground();
        ProAutoLink.onForeground();
        if (!foregroundSeen) {
            foregroundSeen = true;
            firstIdle = () -> {
                firstIdle = null;
                firstIdlePassed = true;
                for (int account = 0; account < SLOTS; account++) {
                    if (activatedUser(account) > 0) slot(account).flushWanted = true; // once after first idle
                }
                evaluateAll();
            };
            AndroidUtilities.runOnUIThread(firstIdle, FIRST_IDLE_MS);
        }
        evaluateAll();
    }

    private static void onBackground() {
        foreground = false;
        ZenlyStats.onBackground();
        ProAutoLink.onBackground();
        if (firstIdlePassed) {
            for (int account = 0; account < SLOTS; account++) {
                if (activatedUser(account) > 0) slot(account).flushWanted = true; // flush on background entry
            }
        }
        evaluateAll();
    }

    // ---- User actions (main thread) ----

    /** {@link #withdraw(int, String)} in the app's text language. */
    public static boolean withdraw(int account) { return withdraw(account, null); }

    /**
     * The confirmed withdrawal (design 50 §12; the UI asked TasksCloudRepository.unsentChangeCount and confirmed first):
     * the withdraw event and state withdrawn are committed synchronously, then the event queue goes, unsent cloud Tasks
     * changes are discarded, automatic linking forgets the user, and the withdrawal is uploaded when linked (rule 1). The
     * binding is kept. True when the account is withdrawn now; Data and consent follow {@code zenlygramConsentChanged}.
     */
    public static boolean withdraw(int account, String textLocale) {
        if (!validSlot(account)) return false;
        long tg = activatedUser(account);
        if (tg <= 0) return false;
        boolean withdrawn = textLocale == null ? ZenlyConsent.withdraw(account) : ZenlyConsent.withdraw(account, textLocale);
        if (!withdrawn) return false;
        batch(() -> {
            consentTransition(account, tg);
            evaluate(account);
        });
        return true;
    }

    // ZENLYGRAM 13.2.1: "Try again" on a withdrawal that has not reached the server (Data & consent and the wall's
    // notice, org.telegram.zenlygram.customer). iOS sends a user-initiated withdrawal without waiting out the back-off;
    // without this the button could only wait for the same timer the coordinator already runs.
    /**
     * The user asks to send the pending withdrawal of {@code account} now (main thread): the consents back-off of this
     * process is forgotten and the account is evaluated at once. The install budget, the first idle, the route's own
     * cooldown and "one call per account at a time" still apply. Nothing happens without a pending withdrawal.
     */
    public static void retryWithdrawal(final int account) {
        if (!onMain(() -> retryWithdrawal(account)) || !validSlot(account)) return;
        long tg = activatedUser(account);
        if (tg <= 0 || !ZenlyConsent.pendingWithdraw(tg)) return;
        slot(account).consents = CustomerBackoff.State.IDLE;
        evaluate(account);
    }

    /**
     * "Delete my Zenlygram account and data" confirmed, or Retry (main thread). Never needs a grant and sends nothing
     * else first; the screen hears every state through {@code ui} (null: a completed deletion finishes silently).
     */
    public static void deleteAccount(final int account, final DeleteListener ui) {
        if (!onMain(() -> deleteAccount(account, ui)) || !validSlot(account)) return;
        long tg = activatedUser(account);
        String authority = ZenlyPro.authority();
        Store current = store();
        if (tg <= 0 || authority == null || current == null) {
            if (ui != null) ui.onDeleteState(account, DeleteFlow.Outcome.NOT_LINKED);
            return;
        }
        Slot s = slot(account);
        if (s.tg != tg) s.reset(tg);
        if (s.deletion != null && s.deletion.inFlight) {
            s.deletion.listener = ui;
            return;
        }
        if (s.deletion != null) s.deletion.cancel();
        CustomerApi api = CustomerApi.forAccount(account);
        s.deletion = new Deletion(account, tg, authority, api, current, EFFECTS, ui);
        CustomerApi.Identity identity = api.identity();
        s.deletion.start(identity.isLinked() && identity.telegramUserId == tg, UUID.randomUUID().toString(), ZenlyPro.trustedNowSeconds());
    }

    /** The done confirmation was dismissed: the consent record and the deletion record go (state none → the wall). */
    public static void acknowledgeDeletion(final int account) {
        if (!onMain(() -> acknowledgeDeletion(account)) || !validSlot(account)) return;
        long tg = activatedUser(account);
        String authority = ZenlyPro.authority();
        Store current = store();
        if (tg <= 0 || authority == null || current == null) return;
        DeleteFlow.Record record = current.deletion(tg, authority);
        if (record != null && record.state == DeleteFlow.State.DONE) finishErasure(account, tg);
    }

    /** The account's deletion record at the configured origin, or null (Data and consent, the wall's decline page). */
    public static DeleteFlow.Record deletionState(int account) {
        long tg = activatedUser(account);
        String authority = ZenlyPro.authority();
        Store current = store();
        return tg <= 0 || authority == null || current == null ? null : current.deletion(tg, authority);
    }

    /** "My data": GET /v1/profile/me on the account's own binding (main thread; {@code done} on the main thread). */
    public static void loadMyData(final int account, final MyDataCallback done) {
        if (done == null || !onMain(() -> loadMyData(account, done))) return;
        long tg = validSlot(account) ? activatedUser(account) : 0;
        if (tg <= 0) {
            done.done(new MyData(null, "login_required", 0));
            return;
        }
        CustomerApi api = CustomerApi.forAccount(account);
        CustomerApi.Identity identity = api.identity();
        if (!identity.isLinked() || identity.telegramUserId != tg) {
            done.done(new MyData(null, "link_required", 0));
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        if (!ADMISSION.admit(CustomerOutcome.Route.ME, true, nowMs)) {
            long wait = ADMISSION.waitMs(CustomerOutcome.Route.ME, true, nowMs);
            done.done(new MyData(null, "rate_limited", (int) Math.max(1, Math.min(CustomerBackoff.MAX_SECONDS, (wait + 999) / 1000))));
            return;
        }
        api.getProfileMe(identity, reply -> {
            if (reply.kind == CustomerApi.Kind.OK) {
                done.done(new MyData(reply.body, null, 0));
            } else {
                done.done(new MyData(null, reply.error != null ? reply.error : ErrorCodes.UNKNOWN, reply.retryAfterSeconds));
            }
        });
    }

    // ---- Readers (any thread) ----

    /**
     * The account may link automatically and record (design 50 §10): effectively granted (no binding loss or stale grant
     * since, not retired), the latest grant not rejected, no deletion pending.
     */
    public static boolean hasEffectiveGrant(long telegramUserId) {
        return telegramUserId > 0 && ZenlyConsent.snapshot(telegramUserId).recordingAllowed() && !deletionPending(telegramUserId);
    }

    /**
     * ZenlyPro's ConsentProvider: the exact latest grant JSON for {@code bind/start}, or null — not effectively granted,
     * rejected, a withdrawal pending, or a deletion pending (contract §2.2.1, design 50 §8.1).
     */
    public static String linkGrantJson(long telegramUserId) {
        if (telegramUserId <= 0 || deletionPending(telegramUserId)) return null;
        return ZenlyConsent.grantJsonIfGranted(telegramUserId);
    }

    /**
     * The Tasks readiness seam (contract §6.4 step 6): linked, the grant acked for this binding, the profile settled and not
     * stopped, no deletion pending, and a 2xx of this binding confirmed in this process; a withdrawal or a deletion takes it
     * away at once. {@code zenlygramCustomerStateChanged} is posted on the account's instance whenever it flips.
     */
    public static boolean tasksMayRun(int account) {
        if (!validSlot(account)) return false;
        long ready = READY.get(account);
        if (ready <= 0) return false;
        long tg = activatedUser(account);
        return tg == ready && ZenlyConsent.snapshot(tg).recordingAllowed() && !deletionPending(tg);
    }

    /** A deletion of the user runs, waits for a retry or completed (ZenlyStats' gate: nothing is recorded meanwhile). */
    public static boolean deletionPending(long telegramUserId) {
        if (telegramUserId <= 0) return false;
        String authority = ZenlyPro.authority();
        Store current = store();
        return authority != null && current != null && current.blocks(telegramUserId, authority);
    }

    /**
     * LogoutActivity: the warning the logout dialog appends while a withdrawal of the account has not reached the server
     * ({@code ZenlyLogoutWithdrawPending}), else null.
     */
    public static String logoutNotice(int account) {
        long tg = validSlot(account) ? activatedUser(account) : 0;
        if (tg <= 0 || !ZenlyConsent.pendingWithdraw(tg)) return null;
        try {
            // ZENLYGRAM 13.2.1: by id, through the app's string table (a release build has no string resources to look up by name).
            return LocaleController.getString(org.telegram.messenger.R.string.ZenlyLogoutWithdrawPending);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    // ---- The pipeline (design 50 §10) ----

    private static void evaluateAll() {
        for (int account = 0; account < SLOTS; account++) {
            if (activatedUser(account) > 0 || (SLOT[account] != null && SLOT[account].tg != 0)) evaluate(account);
        }
    }

    /** One evaluation of {@code account} (deferred while another runs, so one account is evaluated at a time). */
    private static void evaluate(int account) {
        if (!started || !validSlot(account)) return;
        DIRTY[account] = true;
        if (depth == 0) drain();
    }

    private static void batch(Runnable work) {
        depth++;
        try {
            work.run();
        } catch (RuntimeException unexpected) {
            // The customer engine never breaks its caller; the next evaluation point retries.
        } finally {
            depth--;
        }
        if (depth == 0) drain();
    }

    private static void drain() {
        for (int round = 0; round < 8; round++) {
            boolean any = false;
            for (int account = 0; account < SLOTS; account++) {
                if (!DIRTY[account]) continue;
                DIRTY[account] = false;
                any = true;
                depth++;
                try {
                    evaluateOnce(account);
                } catch (RuntimeException unexpected) {
                    // As above: one account's failure never stops the others.
                } finally {
                    depth--;
                }
            }
            if (!any) return;
        }
        // Still dirty after eight rounds (a chain of synchronous answers): continue on the next main-loop turn.
        AndroidUtilities.runOnUIThread(() -> {
            if (depth == 0) drain();
        });
    }

    private static void evaluateOnce(int account) {
        Slot s = slot(account);
        long tg = activatedUser(account);
        if (tg <= 0) {
            if (s.tg != 0) s.reset(0);
            arm(account, s, -1);
            publishReady(account, 0);
            return;
        }
        if (s.tg != tg) s.reset(tg);
        String authority = ZenlyPro.authority();
        CustomerApi api = CustomerApi.forAccount(account);
        CustomerApi.Identity identity = api.identity();
        boolean linked = authority != null && identity.isLinked() && identity.telegramUserId == tg;
        String bindingId = linked ? identity.bindingId : null;
        if (!same(bindingId, s.bindingId)) s.rebind(bindingId);
        ConsentSnapshot snapshot = ZenlyConsent.snapshot(tg);
        long nowMs = SystemClock.elapsedRealtime();
        boolean deletion = deletionPending(tg);

        ProfileSnapshot profile = null;
        String stored = null;
        ProfileCadence.Decision cadence = null;
        if (linked) {
            stored = api.profileDigest(identity);
            profile = ZenlyProfileReader.read(account);
            if (profile != null && profile.telegramUserId == tg) {
                cadence = cadence(s, api, identity, stored, profile.digest(), nowMs);
            } else {
                profile = null;
            }
        }
        boolean open = snapshot.recordingAllowed() && linked && !deletion && snapshot.record.grant != null
                && snapshot.isAcked(authority, snapshot.record.grant.consentId);
        long wake = -1;
        if (open && foreground && firstIdlePassed && !s.flushWanted) {
            // Every flush_interval_s in the foreground (design 50 §11.6).
            long interval = ZenlyEventUploader.flushIntervalSeconds(account) * 1000L;
            if (s.nextFlushMs == 0) s.nextFlushMs = nowMs + interval;
            if (nowMs >= s.nextFlushMs) {
                s.flushWanted = true;
            } else {
                wake = earliest(wake, s.nextFlushMs - nowMs);
            }
        }
        CustomerPipeline.Facts f = facts(api, identity, tg, authority, snapshot, deletion, s.confirmed, s.resendGrant, s.droppedDigest,
                stored, cadence != null && cadence.action == ProfileCadence.Action.SEND && s.sync.ready(nowMs),
                s.flushWanted && nowMs >= s.flushRetryAtMs);
        CustomerPipeline.Step step = CustomerPipeline.next(f);
        publishReady(account, CustomerPipeline.tasksMayRun(f) ? tg : 0);
        if (cadence != null && cadence.action == ProfileCadence.Action.WAIT) wake = earliest(wake, cadence.waitMs);
        if (s.flushWanted && nowMs < s.flushRetryAtMs) wake = earliest(wake, s.flushRetryAtMs - nowMs);
        if (s.busy || !firstIdlePassed) {
            arm(account, s, s.busy ? -1 : wake);
            return;
        }
        switch (step) {
            case AUTO_LINK:
                ProAutoLink.consider(account);
                break;
            case UPLOAD_CONSENTS:
                wake = earliest(wake, sendConsents(account, s, api, identity, tg, authority, snapshot, false));
                break;
            case PROFILE_SYNC:
                if (profile != null) wake = earliest(wake, sendSync(account, s, api, identity, tg, profile, stored == null));
                break;
            case EVENTS_FLUSH:
                flush(account, s, false);
                break;
            case CONFIRM:
                wake = earliest(wake, confirm(account, s, api, identity, tg, authority, snapshot, nowMs));
                break;
            default:
                break;
        }
        arm(account, s, s.busy ? -1 : wake);
    }

    /**
     * The pipeline facts of one account from its consent snapshot and its binding's bookkeeping (package-private:
     * ZenlyCustomerRuntimeTest checks the gating over a stub CustomerApi boundary).
     */
    static CustomerPipeline.Facts facts(CustomerApi api, CustomerApi.Identity identity, long tg, String authority, ConsentSnapshot snapshot,
            boolean deletionPending, boolean confirmed, boolean resendGrant, String droppedDigest, String storedDigest, boolean profileDue,
            boolean flushDue) {
        CustomerPipeline.Facts f = CustomerPipeline.Facts.of(snapshot, authority);
        f.linked = authority != null && identity.isLinked() && identity.telegramUserId == tg;
        f.deletionPending = deletionPending;
        f.resendGrant = resendGrant;
        f.profileStopped = f.linked && api.profileStopped(identity);
        f.profileSettled = f.linked && ProfileCadence.settled(storedDigest, droppedDigest);
        f.profileDue = f.linked && profileDue;
        f.flushDue = f.linked && flushDue;
        f.confirmed = f.linked && confirmed;
        return f;
    }

    private static ProfileCadence.Decision cadence(Slot s, CustomerApi api, CustomerApi.Identity identity, String stored, String current,
            long nowMs) {
        if (stored != null && !stored.equals(current)) {
            if (!current.equals(s.seenDigest)) {
                s.seenDigest = current;
                s.seenDigestMs = nowMs;
            }
        } else {
            s.seenDigest = null;
            s.seenDigestMs = -1;
        }
        ProfileCadence.Facts c = new ProfileCadence.Facts();
        c.storedDigest = stored;
        c.currentDigest = current;
        c.droppedDigest = s.droppedDigest;
        c.changeSeenMs = s.seenDigestMs;
        c.nowMs = nowMs;
        c.syncedAtS = api.profileSyncedAt(identity);
        c.nowS = ZenlyPro.trustedNowSeconds();
        c.minIntervalS = api.profileMinIntervalSeconds(identity);
        c.foreground = foreground;
        c.firstIdlePassed = firstIdlePassed;
        return ProfileCadence.decide(c);
    }

    // ---- Sends (each returns the wait in ms when it could not send now, else -1) ----

    private static long sendConsents(final int account, final Slot s, CustomerApi api, final CustomerApi.Identity identity, final long tg,
            final String authority, ConsentSnapshot snapshot, final boolean confirming) {
        final List<ConsentEvent> batch = CustomerPipeline.consentsBatch(snapshot, authority, s.resendGrant || confirming);
        if (batch.isEmpty()) {
            if (s.resendGrant) {
                s.resendGrant = false; // Nothing can be sent again (no grant event): the pipeline moves on.
                evaluate(account);
            }
            return -1;
        }
        long nowMs = SystemClock.elapsedRealtime();
        if (!s.consents.ready(nowMs)) return s.consents.waitMs(nowMs);
        long wait = admit(account, CustomerOutcome.Route.CONSENTS, nowMs);
        if (wait != 0) return wait;
        byte[] body;
        try {
            body = ConsentEvent.requestBody(batch).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            return -1;
        }
        s.busy = true;
        final long epoch = s.epoch;
        api.postConsents(identity, body, reply -> answer(account, s, epoch,
                () -> consentsAnswered(account, s, identity, tg, authority, batch, confirming, reply)));
        return -1;
    }

    private static void consentsAnswered(int account, Slot s, CustomerApi.Identity identity, long tg, String authority, List<ConsentEvent> batch,
            boolean confirming, CustomerApi.Reply reply) {
        CustomerOutcome outcome = outcomeOf(CustomerOutcome.Route.CONSENTS, reply);
        long nowMs = SystemClock.elapsedRealtime();
        switch (outcome.kind) {
            case OK: {
                ConsentMachine.Answer answer = ConsentMachine.Answer.fromTree(parse(reply.body));
                ConsentMachine.Step step = ZenlyConsent.applyConsentsAnswer(tg, authority, answer);
                if (step == null) {
                    s.consents = s.consents.failed(0, RANDOM.nextDouble(), nowMs); // Not committed: sent again later.
                    break;
                }
                s.consents = CustomerBackoff.State.IDLE;
                if (step.purgeEvents) purgeEvents(account, tg);
                ConsentEvent grant = step.snapshot.latestGrantEvent();
                if (grant != null && CustomerPipeline.ids(batch).contains(grant.consentId)) s.resendGrant = false;
                if (confirming) s.confirmResent = true;
                if (answer.effective != null && "granted".equals(answer.effective.state) && step.snapshot.recordingAllowed()) s.confirmed = true;
                break;
            }
            case REJECTED: {
                ConsentMachine.Step step = ZenlyConsent.markRejected(tg, CustomerPipeline.ids(batch), outcome.code);
                if (step != null && step.purgeEvents) purgeEvents(account, tg);
                s.resendGrant = false;
                break;
            }
            case STOP_PROFILE:
                CustomerApi.forAccount(account).markProfileStopped(identity);
                break;
            case UNAUTHORIZED:
            case ACCOUNT_ERASED:
            case STALE:
                // The binding listener (a real 401) or the next evaluation (another identity) takes it from here.
                break;
            case RATE_LIMITED:
            case RETRY_SOON:
            case UNAVAILABLE:
            case BACKOFF:
                s.consents = s.consents.after(outcome, reply.retryAfterSeconds, RANDOM.nextDouble(), nowMs);
                break;
            default:
                // DROP, TOO_LARGE, CONSENT_REQUIRED: never a consents answer in practice; entries stay and back off.
                s.consents = s.consents.failed(reply.retryAfterSeconds, RANDOM.nextDouble(), nowMs);
                break;
        }
        countError(account, CustomerEvents.ErrorDomain.CONSENT_UPLOAD, outcome);
    }

    private static long sendSync(final int account, final Slot s, final CustomerApi api, final CustomerApi.Identity identity, final long tg,
            ProfileSnapshot profile, final boolean first) {
        long nowMs = SystemClock.elapsedRealtime();
        if (!s.sync.ready(nowMs)) return s.sync.waitMs(nowMs);
        long wait = admit(account, CustomerOutcome.Route.SYNC, nowMs);
        if (wait != 0) return wait;
        final String digest = profile.digest();
        byte[] body = profile.body(ZenlyPro.trustedNowSeconds());
        // The grant this sync goes out under: it must still be the effective one when the worker signs it and when its
        // answer is recorded (a withdrawal while it waits there or travels).
        ConsentMachine.Grant sentUnder = ZenlyConsent.snapshot(tg).latestGrant();
        final String grant = sentUnder == null ? null : sentUnder.consentId;
        s.busy = true;
        final long epoch = s.epoch;
        api.postProfileSync(identity, body, () -> stillGranted(tg, grant),
                reply -> answer(account, s, epoch, () -> syncAnswered(account, s, api, identity, tg, grant, digest, first, reply)));
        return -1;
    }

    private static void syncAnswered(int account, Slot s, CustomerApi api, CustomerApi.Identity identity, long tg, String grant, String digest,
            boolean first, CustomerApi.Reply reply) {
        // Refused on the worker before it was signed: nothing was sent, so nothing backs off or counts, and the change that
        // refused it (a withdrawal, deletion, binding loss or logout) takes it from here.
        if (reply.kind == CustomerApi.Kind.NOT_ALLOWED) return;
        CustomerOutcome outcome = outcomeOf(CustomerOutcome.Route.SYNC, reply);
        long nowMs = SystemClock.elapsedRealtime();
        switch (outcome.kind) {
            case OK: {
                s.sync = CustomerBackoff.State.IDLE;
                // Sent under a grant that is no longer the effective one (withdrawn here or elsewhere, rejected, replaced,
                // ended by a binding loss, or a deletion began): the server deletes that profile or never keeps it, so this
                // answer records no receipt, confirms nothing and triggers no flush; a new grant syncs first (contract §6.4).
                if (!stillGranted(tg, grant)) break;
                int minInterval = CustomerApi.DEFAULT_MIN_INTERVAL_SECONDS;
                Map<String, Object> body = CustomerJson.object(parse(reply.body));
                Long named = body == null ? null : CustomerJson.integral(body.get("min_interval_s"));
                if (named != null && named > 0) minInterval = (int) Math.min(named, Integer.MAX_VALUE);
                // "ingest": "paused" is a success too (contract §2.4.3).
                api.recordProfileSync(identity, digest, ZenlyPro.trustedNowSeconds(), minInterval);
                s.seenDigest = null;
                s.seenDigestMs = -1;
                s.confirmed = true;
                if (first) s.flushWanted = true; // A flush after the first profile sync of a binding.
                break;
            }
            case CONSENT_REQUIRED:
                if (!reconcile(account, s, tg, outcome.reason, effectiveAtOf(reply.body))) {
                    s.sync = s.sync.failed(reply.retryAfterSeconds, RANDOM.nextDouble(), nowMs);
                }
                break;
            case STOP_PROFILE:
                api.markProfileStopped(identity);
                break;
            case DROP:
            case REJECTED:
            case TOO_LARGE:
                s.droppedDigest = digest; // Not sent again with the same digest in this process.
                break;
            case UNAUTHORIZED:
            case ACCOUNT_ERASED:
            case STALE:
                break;
            case RATE_LIMITED:
            case RETRY_SOON:
            case UNAVAILABLE:
            case BACKOFF:
                s.sync = s.sync.after(outcome, reply.retryAfterSeconds, RANDOM.nextDouble(), nowMs);
                break;
            default:
                s.sync = s.sync.failed(reply.retryAfterSeconds, RANDOM.nextDouble(), nowMs);
                break;
        }
        countError(account, CustomerEvents.ErrorDomain.PROFILE_SYNC, outcome);
    }

    private static void flush(final int account, final Slot s, final boolean confirming) {
        s.busy = true;
        final long epoch = s.epoch;
        ZenlyEventUploader.flush(account, account == UserConfig.selectedAccount,
                result -> answer(account, s, epoch, () -> flushed(account, s, confirming, result)));
    }

    private static void flushed(int account, Slot s, boolean confirming, ZenlyEventUploader.Result result) {
        long nowMs = SystemClock.elapsedRealtime();
        s.nextFlushMs = nowMs + ZenlyEventUploader.flushIntervalSeconds(account) * 1000L;
        if (confirming) s.confirmFlushTried = true;
        if (result.skipped != null) {
            switch (result.skipped) {
                case EMPTY:
                case PAUSED:
                    s.confirmFlushTried = true; // Nothing to flush: CONFIRM sends the grant once more instead.
                    s.flushWanted = false;
                    break;
                case NOT_ALLOWED:
                case NOT_LINKED:
                    s.flushWanted = false;
                    break;
                case IN_FLIGHT:
                    // An earlier batch (of a previous binding) is still out: look again shortly, never in a tight loop.
                    s.flushRetryAtMs = nowMs + 1_000;
                    break;
                default: // BACKOFF, TOO_SOON, BUDGET
                    s.flushRetryAtMs = nowMs + Math.max(1_000, result.retryInMs);
                    break;
            }
            return;
        }
        CustomerOutcome outcome = result.outcome;
        switch (outcome.kind) {
            case OK:
                s.confirmed = true;
                s.flushWanted = result.queued >= EventQueuePolicy.FLUSH_THRESHOLD; // Still ≥ 50 queued: the next batch.
                break;
            case CONSENT_REQUIRED:
                // When the reconcile sends the grant once more, the flush is tried once again after it (contract §6.3).
                s.flushWanted = reconcile(account, s, result.telegramUserId, outcome.reason,
                        result.reply == null ? 0 : effectiveAtOf(result.reply.body)) && s.resendGrant;
                break;
            case STOP_PROFILE: {
                s.flushWanted = false;
                CustomerApi api = CustomerApi.forAccount(account);
                CustomerApi.Identity identity = api.identity();
                if (identity.isLinked() && identity.telegramUserId == result.telegramUserId) api.markProfileStopped(identity);
                break;
            }
            case UNAUTHORIZED:
            case ACCOUNT_ERASED:
            case STALE:
                s.flushWanted = false;
                break;
            case DROP:
            case REJECTED:
                // The batch is gone (ZenlyEventStore counted it); what is left goes with the next trigger.
                s.flushWanted = result.queued >= EventQueuePolicy.FLUSH_THRESHOLD;
                break;
            default: // TOO_LARGE (halved), RATE_LIMITED, RETRY_SOON, UNAVAILABLE, BACKOFF: the uploader's backoff
                s.flushRetryAtMs = nowMs + Math.max(1_000, result.retryInMs);
                break;
        }
        if (outcome.kind != CustomerOutcome.Kind.DROP && outcome.kind != CustomerOutcome.Kind.REJECTED) {
            countError(account, CustomerEvents.ErrorDomain.EVENTS_UPLOAD, outcome);
        }
    }

    /**
     * Rule 7: an events flush when events may be queued (its 2xx confirms), else the acked grant once more (a
     * {@code duplicate} with effective state granted confirms).
     */
    private static long confirm(int account, Slot s, CustomerApi api, CustomerApi.Identity identity, long tg, String authority,
            ConsentSnapshot snapshot, long nowMs) {
        if (!s.confirmFlushTried && nowMs >= s.flushRetryAtMs) {
            flush(account, s, true);
            return -1;
        }
        if (!s.confirmResent) return sendConsents(account, s, api, identity, tg, authority, snapshot, true);
        return -1;
    }

    /**
     * A 403 {@code consent_required} on profile/sync or profile/events (contract §6.3): the consent machine decides;
     * {@code withdrawn} newer than the grant → withdrawn_remote (purge, Tasks discard via the transition); otherwise the
     * grant is sent again once per process per account. False when nothing more may be tried now (back off).
     */
    private static boolean reconcile(int account, Slot s, long tg, String reason, long effectiveAt) {
        ConsentMachine.Step step = ZenlyConsent.applyConsentRequired(tg, reason, effectiveAt);
        if (step == null) return false;
        if (step.purgeEvents) purgeEvents(account, tg);
        if (step.reuploadGrant || step.uploadThenRetry) {
            if (s.reconcileUsed) return false;
            s.reconcileUsed = true;
            s.resendGrant = true;
        }
        return true;
    }

    /** The install budget (and other accounts' sync spacing): 0 when taken now, else the wait in ms. */
    private static long admit(int account, CustomerOutcome.Route route, long nowMs) {
        boolean selected = account == UserConfig.selectedAccount;
        if (ADMISSION.admit(route, selected, nowMs)) return 0;
        return Math.max(1, ADMISSION.waitMs(route, selected, nowMs));
    }

    /** Runs a call's answer on the main thread unless its slot moved to another user or binding meanwhile. */
    private static void answer(int account, Slot s, long epoch, Runnable handle) {
        if (s.epoch != epoch) return;
        s.busy = false;
        batch(() -> {
            handle.run();
            evaluate(account);
        });
    }

    private static void arm(final int account, final Slot s, long waitMs) {
        if (s.wake != null) {
            AndroidUtilities.cancelRunOnUIThread(s.wake);
            s.wake = null;
        }
        if (waitMs < 0) return;
        long delay = Math.max(MIN_WAKE_MS, Math.min(waitMs, MAX_WAKE_MS));
        Runnable wake = new Runnable() {
            @Override public void run() {
                if (s.wake != this) return;
                s.wake = null;
                evaluate(account);
            }
        };
        s.wake = wake;
        AndroidUtilities.runOnUIThread(wake, delay);
    }

    private static void publishReady(int account, long readyUser) {
        long before = READY.getAndSet(account, readyUser);
        if (before != readyUser) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.zenlygramCustomerStateChanged);
        }
    }

    // ---- Consent transitions ----

    /**
     * Withdrawal (local or remote) and re-grant as Tasks and statistics see them: entering withdrawn / withdrawn_remote
     * purges the event queue, forgets automatic linking, discards unsent cloud Tasks changes and forgets the binding's
     * profile receipt; leaving it with a usable grant lets Tasks insert again. Idempotent per transition;
     * {@link #onPostInit} runs the purge, the discard and the receipt's removal again for an account found withdrawn.
     */
    private static void consentTransition(int account, long tg) {
        ConsentSnapshot snapshot = ZenlyConsent.snapshot(tg);
        ConsentMachine.State now = snapshot.effectiveState();
        ConsentMachine.State before = SEEN.put(tg, now);
        boolean was = withdrawn(before);
        boolean is = withdrawn(now);
        if (is && !was && before != null) {
            purgeEvents(account, tg);
            ProAutoLink.forget(tg);
            discardTasks(account, tg);
            forgetProfileSync(CustomerApi.forAccount(account), tg);
        } else if (was && snapshot.recordingAllowed()) {
            try {
                TasksCloudRepository.forAccount(account).resumeAfterConsentRegrant();
            } catch (RuntimeException notSignedIn) {
                // The slot holds another user now: nothing of this one runs.
            }
        }
    }

    /** Withdrawn here or elsewhere: nothing of the user may be recorded or uploaded until a new grant. */
    private static boolean withdrawn(ConsentMachine.State state) {
        return state == ConsentMachine.State.WITHDRAWN || state == ConsentMachine.State.WITHDRAWN_REMOTE;
    }

    /**
     * The stored state ended recording with a purge of the event queue (ConsentMachine's purgeEvents steps): withdrawn
     * here or elsewhere, the link ended (a binding loss after the grant, a grant bind/start refused as stale, an erased
     * account's grant) or the latest grant rejected. A re-consent request keeps the queue, as its step does.
     */
    private static boolean eventsPurged(ConsentSnapshot snapshot) {
        return withdrawn(snapshot.effectiveState()) || snapshot.notice() == ConsentMachine.Notice.LINK_ENDED || snapshot.latestRejected();
    }

    /**
     * {@code grant} (a consent id) is still the user's effective grant: not withdrawn here or elsewhere, rejected, ended by
     * a binding loss or replaced by a newer grant, and no deletion pending. Asked on the customer worker right before a
     * profile sync sent under it is signed, and again when its answer arrives (any thread: published state only).
     */
    private static boolean stillGranted(long tg, String grant) {
        ConsentSnapshot now = ZenlyConsent.snapshot(tg);
        return grant != null && now.recordingAllowed() && now.record.grant != null && grant.equals(now.record.grant.consentId)
                && !deletionPending(tg);
    }

    /**
     * A withdrawal, here or elsewhere, deletes the profile on the server (contract §9.3): the binding forgets its profile
     * receipt, so a later grant syncs the profile again before events or Tasks resume (contract §6.4; iOS
     * clearProfileSyncReceipt). Unlinked: nothing to forget, a new binding always syncs first.
     */
    private static void forgetProfileSync(CustomerApi api, long tg) {
        try {
            CustomerApi.Identity identity = api.identity();
            if (identity.isLinked() && identity.telegramUserId == tg) api.clearProfileSync(identity);
        } catch (RuntimeException storage) {
            // Not removed: the next launch tries again while the account stays withdrawn.
        }
    }

    /**
     * TasksCloudRepository.onConsentWithdrawn waits up to 2 s for the discard on the cloud executor, so it runs on a
     * short-lived worker; the Tasks gate is already FORBIDDEN (the consent snapshot was published first).
     */
    private static void discardTasks(final int account, final long tg) {
        Thread worker = new Thread(() -> {
            try {
                TasksCloudRepository.onConsentWithdrawn(account, tg);
            } catch (RuntimeException ignored) {
                // Not linked under the configured origin, or storage: nothing of it can be uploaded either.
            }
        }, "ZenlygramTasksWithdraw");
        worker.setDaemon(true);
        worker.start();
    }

    private static void purgeEvents(int account, long tg) {
        ZenlyStats.forget(tg);
        if (validSlot(account) && activatedUser(account) == tg) {
            ZenlyEventUploader.forget(account);
            if (SLOT[account] != null) SLOT[account].flushWanted = false;
        }
    }

    // ---- Binding listener (design 50 §7.6, integration A3) ----

    private static final ZenlyPro.BindingListener BINDING = new ZenlyPro.BindingListener() {
        @Override public void onBindingAdopted(int account, long telegramUserId, String authority, String bindingId) {
            batch(() -> adopted(account, telegramUserId, authority, bindingId));
        }

        @Override public void onBindingLost(int account, long telegramUserId, String authority, String code, String reason) {
            batch(() -> lost(account, telegramUserId, authority, code, reason));
        }
    };

    private static void adopted(int account, long tg, String authority, String bindingId) {
        Store current = store();
        if (tg <= 0 || authority == null || bindingId == null || current == null) return;
        long now = ZenlyPro.trustedNowSeconds();
        DeleteFlow.Record record = current.deletion(tg, authority);
        if (record != null && record.state == DeleteFlow.State.DONE) finishErasure(account, tg);
        CustomerPipeline.Binding stored = current.binding(tg, authority, now);
        // Every new binding uploads its grant again (contract §6.4 step 3, "always after a link").
        if (stored.isNewBinding(bindingId)) ZenlyConsent.clearAcked(tg, authority);
        CustomerPipeline.Binding next = stored.adopted(bindingId);
        current.saveBinding(tg, authority, next);
        // The loss ends here, but a grant it ended stays needs_reconsent: stored when the loss was fed, else by this
        // feed in the same commit as the cleared loss (if that commit fails, the loss stays in memory;
        // ConsentMachine.bindingLoss). Nothing is uploaded or recorded under that grant on this binding; only a new
        // grant lifts it (compliance X-7(b)).
        if (authority.equals(ZenlyPro.authority())) ZenlyConsent.setBindingLoss(tg, next.consentLoss());
        evaluate(account);
    }

    private static void lost(int account, long tg, String authority, String code, String reason) {
        Store current = store();
        if (tg <= 0 || authority == null || current == null) return;
        DeleteFlow.Record record = current.deletion(tg, authority);
        Slot s = validSlot(account) ? slot(account) : null;
        if (s != null && s.deletion != null && s.deletion.tg == tg && s.deletion.bindingLost(reason)) return;
        DeleteFlow.Decision done = DeleteFlow.bindingLost(record, reason);
        if (done != null) {
            // A deletion of this user completed, with no run of this process to report it: finish it silently.
            current.saveDeletion(tg, authority, done.record);
            completeErasure(account, tg, authority, false);
            return;
        }
        if (CustomerOutcome.ACCOUNT_ERASED_REASON.equals(reason)) {
            // The account was erased (the web page, an admin, another device): the §12 done sequence without a dialog.
            completeErasure(account, tg, authority, false);
            return;
        }
        long now = ZenlyPro.trustedNowSeconds();
        // Never before the latest grant's decided_at: a grant nextInstant placed ahead of the clock is ended by this loss too.
        long lostAt = ConsentMachine.lossInstant(ZenlyConsent.snapshot(tg), now);
        CustomerPipeline.Binding next = current.binding(tg, authority, now).lost(lostAt, code);
        current.saveBinding(tg, authority, next);
        if (authority.equals(ZenlyPro.authority())) {
            ConsentMachine.Step step = ZenlyConsent.setBindingLoss(tg, next.consentLoss());
            if (step != null && step.purgeEvents) purgeEvents(account, tg);
        }
        evaluate(account);
    }

    // ---- Automatic link (design 50 §8) ----

    private static long effectiveGrantAt(int account) {
        long tg = activatedUser(account);
        if (!hasEffectiveGrant(tg)) return 0;
        ConsentMachine.Grant grant = ZenlyConsent.snapshot(tg).latestGrant();
        return grant == null ? 0 : Math.max(1, grant.decidedAt);
    }

    private static final ProAutoLink.Listener AUTO_LINK = new ProAutoLink.Listener() {
        @Override public void onAttempt(int account, boolean success, String errorCode, long durationSeconds) {
            ZenlyStats.linkAttempt(account, true, true, success, errorCode, durationSeconds);
        }

        @Override public void onConsentStale(int account, long telegramUserId, String authority, String sentGrantJson) {
            batch(() -> consentStale(account, telegramUserId, authority, sentGrantJson));
        }
    };

    /**
     * bind/start refused the grant {@code sentGrantJson} as {@code consent_stale} (automatic or manual link): that exact
     * grant becomes needs_reconsent, {@code stale_at} (its recorded_at) is persisted and fed to the consent snapshot like a
     * loss, and the event queue goes.
     */
    private static void consentStale(int account, long tg, String authority, String sentGrantJson) {
        if (tg <= 0) return;
        ZenlyConsent.markConsentStale(tg, sentGrantJson);
        Store current = store();
        long refused = recordedAt(sentGrantJson);
        if (current != null && authority != null && refused > 0) {
            CustomerPipeline.Binding next = current.binding(tg, authority, ZenlyPro.trustedNowSeconds()).stale(refused);
            current.saveBinding(tg, authority, next);
            if (authority.equals(ZenlyPro.authority())) ZenlyConsent.setBindingLoss(tg, next.consentLoss());
        }
        purgeEvents(account, tg);
        evaluate(account);
    }

    // ---- Deletion (design 50 §12) ----

    private static final Deletion.Effects EFFECTS = new Deletion.Effects() {
        @Override public void erased(int account, long tg, String authority, boolean confirmation) {
            completeErasure(account, tg, authority, confirmation);
        }

        @Override public void schedule(Runnable work, long delayMs) { AndroidUtilities.runOnUIThread(work, delayMs); }

        @Override public void cancel(Runnable work) { AndroidUtilities.cancelRunOnUIThread(work); }

        @Override public void changed(int account, long tg) { deletionChanged(account); }
    };

    /**
     * The §12 done sequence: the current origin's session without bind/revoke, the cloud Tasks of the user, the event
     * queue, automatic linking and the binding record; then the consent record — at once, or (with a confirmation on
     * screen) when it is dismissed ({@link #acknowledgeDeletion}).
     */
    private static void completeErasure(int account, long tg, String authority, boolean confirmation) {
        if (validSlot(account)) CustomerApi.forAccount(account).forgetSessionAfterErasure(tg);
        try {
            if (validSlot(account)) TasksCloudRepository.onAccountErased(account, tg);
        } catch (RuntimeException storage) {
            // The cloud wipe obligation is durable inside the repository; nothing else depends on it here.
        }
        purgeEvents(account, tg);
        ProAutoLink.forget(tg);
        Store current = store();
        if (current != null && authority != null) current.saveBinding(tg, authority, null);
        if (!confirmation) finishErasure(account, tg);
        deletionChanged(account);
    }

    /** The consent record and every customer key of the user go (state none: the wall or prompt shows again). */
    private static void finishErasure(int account, long tg) {
        Store current = store();
        if (current != null) current.forget(tg);
        ZenlyConsent.forget(tg);
        SEEN.remove(tg);
        if (validSlot(account) && SLOT[account] != null && SLOT[account].tg == tg) SLOT[account].reset(tg);
        deletionChanged(account);
    }

    private static void deletionChanged(int account) {
        if (!validSlot(account)) return;
        ZenlyStats.onConsentChanged(account); // Recording follows the deletion state too.
        evaluate(account);
    }

    /**
     * One deletion run of an account (main thread; package-private with its effects behind {@link Effects}, so
     * ZenlyCustomerRuntimeTest drives it over the real CustomerApi on a stub boundary and a redirected store). The record
     * is committed before the first send; every attempt and retry sends its key.
     */
    static final class Deletion {
        interface Effects {
            /** Erased: the done sequence ({@code confirmation}: a screen shows it and acknowledges later). */
            void erased(int account, long tg, String authority, boolean confirmation);

            /** Runs {@code work} on the main thread after {@code delayMs}. */
            void schedule(Runnable work, long delayMs);

            void cancel(Runnable work);

            /** The deletion state of the account changed (recording, auto-link and the pipeline read it). */
            void changed(int account, long tg);
        }

        final int account;
        final long tg;
        final String authority;
        final CustomerApi api;
        final Store store;
        final Effects effects;
        DeleteListener listener;
        boolean inFlight;
        int conflictRetries, unavailableRetries;
        private Runnable retry;
        private boolean cancelled;

        Deletion(int account, long tg, String authority, CustomerApi api, Store store, Effects effects, DeleteListener listener) {
            this.account = account;
            this.tg = tg;
            this.authority = authority;
            this.api = api;
            this.store = store;
            this.effects = effects;
            this.listener = listener;
        }

        void start(boolean linked, String newKey, long now) {
            DeleteFlow.Record existing = store.deletion(tg, authority);
            DeleteFlow.Decision d = DeleteFlow.begin(existing, linked, newKey, now);
            switch (d.outcome) {
                case SEND:
                    if (!store.saveDeletion(tg, authority, d.record)) {
                        report(DeleteFlow.Outcome.UNAVAILABLE); // Never sent without its record on disk.
                        return;
                    }
                    effects.changed(account, tg);
                    send();
                    return;
                case NOT_LINKED:
                    if (existing != null) {
                        store.saveDeletion(tg, authority, null);
                        effects.changed(account, tg);
                    }
                    report(DeleteFlow.Outcome.NOT_LINKED);
                    return;
                case FAILED:
                    store.saveDeletion(tg, authority, d.record);
                    effects.changed(account, tg);
                    report(DeleteFlow.Outcome.FAILED);
                    return;
                default: // DONE: shown again until acknowledged.
                    report(d.outcome);
            }
        }

        private void send() {
            DeleteFlow.Record record = store.deletion(tg, authority);
            if (cancelled || record == null || record.state != DeleteFlow.State.PENDING) return;
            CustomerApi.Identity identity = api.identity();
            if (!identity.isLinked() || identity.telegramUserId != tg) {
                handle(record, DeleteFlow.Answer.local(0, "link_required", 0));
                return;
            }
            final String key = record.key;
            inFlight = true;
            api.deleteAccount(identity, key, reply -> {
                inFlight = false;
                if (cancelled) return;
                DeleteFlow.Record current = store.deletion(tg, authority);
                if (current == null || !current.key.equals(key) || current.state != DeleteFlow.State.PENDING) {
                    // Completed or removed meanwhile (the binding listener hears the 401 before this reply, a logout): its own
                    // path reported it.
                    return;
                }
                handle(current, answerOf(reply));
            });
        }

        private void handle(DeleteFlow.Record record, DeleteFlow.Answer answer) {
            DeleteFlow.Decision d = DeleteFlow.answered(record, answer, conflictRetries, unavailableRetries);
            store.saveDeletion(tg, authority, d.record);
            switch (d.outcome) {
                case DONE:
                    if (record.state == DeleteFlow.State.PENDING) effects.erased(account, tg, authority, listener != null);
                    report(DeleteFlow.Outcome.DONE);
                    return;
                case RETRY:
                    if (DeleteFlow.isConflictRetry(answer)) {
                        conflictRetries++;
                    } else {
                        unavailableRetries++;
                    }
                    retry = () -> {
                        retry = null;
                        send();
                    };
                    effects.schedule(retry, d.retryInSeconds * 1000L);
                    report(DeleteFlow.Outcome.RETRY);
                    return;
                case FAILED:
                case NOT_LINKED:
                    effects.changed(account, tg);
                    report(d.outcome);
                    return;
                default: // UNAVAILABLE, RATE_LIMITED, OFFLINE: still pending; the screen offers Retry (same key).
                    report(d.outcome);
            }
        }

        /** A binding loss of this user: completes the deletion when it may have been processed. */
        boolean bindingLost(String reason) {
            DeleteFlow.Decision d = DeleteFlow.bindingLost(store.deletion(tg, authority), reason);
            if (d == null) return false;
            store.saveDeletion(tg, authority, d.record);
            if (retry != null) {
                effects.cancel(retry);
                retry = null;
            }
            effects.erased(account, tg, authority, listener != null);
            report(DeleteFlow.Outcome.DONE);
            return true;
        }

        void cancel() {
            cancelled = true;
            if (retry != null) {
                effects.cancel(retry);
                retry = null;
            }
        }

        private void report(DeleteFlow.Outcome outcome) {
            DeleteListener ui = listener;
            if (ui != null) ui.onDeleteState(account, outcome);
        }

        /** The deletion view of a CustomerApi reply. */
        static DeleteFlow.Answer answerOf(CustomerApi.Reply reply) {
            switch (reply.kind) {
                case STALE:
                    return DeleteFlow.Answer.stale(reply.possiblySent);
                case OFFLINE:
                    return DeleteFlow.Answer.offline(reply.possiblySent);
                case LOCAL:
                    return DeleteFlow.Answer.local(reply.status, reply.error, reply.retryAfterSeconds);
                default:
                    if (reply.status == 0) {
                        // A local cooldown or bound (RATE_LIMITED / TOO_LARGE), or an answer that could not be read.
                        if (reply.kind == CustomerApi.Kind.UNAVAILABLE) return DeleteFlow.Answer.offline(reply.possiblySent);
                        return DeleteFlow.Answer.local(0, reply.error, reply.retryAfterSeconds);
                    }
                    return DeleteFlow.Answer.answered(reply.status, reply.error, reply.reason, reply.retryAfterSeconds);
            }
        }
    }

    // ---- Logout and launch reconcile ----

    /**
     * The pending withdrawals of the user, queued on the user lane before unlink() queues bind/revoke (design 50 §7.4): at a
     * logout ({@link #onLogout}) and at a user Unlink ({@link #onUserUnlink}).
     */
    private static void queueFinalWithdrawals(int account, long tg) {
        try {
            String authority = ZenlyPro.authority();
            if (authority == null) return;
            List<List<ConsentEvent>> batches = CustomerPipeline.finalBatches(ZenlyConsent.snapshot(tg), authority, CustomerApi.MAX_FINAL_BODIES);
            if (batches.isEmpty()) return;
            CustomerApi api = CustomerApi.forAccount(account);
            CustomerApi.Identity identity = api.identity();
            if (!identity.isLinked() || identity.telegramUserId != tg) return;
            ArrayList<byte[]> bodies = new ArrayList<>(batches.size());
            for (List<ConsentEvent> batch : batches) bodies.add(ConsentEvent.requestBody(batch).getBytes(StandardCharsets.UTF_8));
            api.queueFinal(identity, bodies);
        } catch (RuntimeException ignored) {
            // Best effort before the revoke; the web deletion page covers a failed send.
        }
    }

    /** Logout of the user on {@code account}: every customer trace of it goes (main thread). */
    private static void purge(int account, long tg) {
        ZenlyStats.forget(tg);
        ZenlyEventUploader.forget(account);
        ProAutoLink.forget(tg);
        Store current = store();
        if (current != null) current.forget(tg);
        ZenlyConsent.forget(tg);
        SEEN.remove(tg);
        if (SLOT[account] != null) {
            arm(account, SLOT[account], -1);
            SLOT[account].reset(0);
        }
        publishReady(account, 0);
    }

    /** Launch: the same purge for every Telegram user with local customer data that is no longer signed in. */
    private static void reconcileSignedOut(Store current, long[] signedIn) {
        if (signedIn == null) return;
        HashSet<Long> live = new HashSet<>();
        for (long id : signedIn) {
            if (id > 0) live.add(id);
        }
        for (int account = 0; account < SLOTS; account++) {
            long tg = activatedUser(account);
            if (tg > 0) live.add(tg);
        }
        TreeSet<Long> owners = new TreeSet<>(current.owners());
        for (long id : ZenlyConsent.telegramUserIds()) owners.add(id);
        for (Long id : owners) {
            if (live.contains(id)) continue;
            ZenlyStats.forget(id);
            ProAutoLink.forget(id);
            current.forget(id);
            ZenlyConsent.forget(id);
        }
    }

    // ---- Observers ----

    private static final NotificationCenter.NotificationCenterDelegate OBSERVER = (id, account, args) -> {
        if (id == NotificationCenter.zenlygramConsentChanged) {
            if (args != null && args.length > 0 && args[0] instanceof Integer) onConsentChanged((Integer) args[0]);
        } else if (id == NotificationCenter.mainUserInfoChanged || id == NotificationCenter.currentUserPremiumStatusChanged
                || id == NotificationCenter.zenlygramProStatusChanged) {
            evaluate(account);
        }
    };

    private static final ForegroundDetector.Listener FOREGROUND = new ForegroundDetector.Listener() {
        @Override public void onBecameForeground() { onForeground(); }

        @Override public void onBecameBackground() { onBackground(); }
    };

    // ---- The customer store (SharedPreferences "zenlygram_customer") ----

    /**
     * {@code binding.v1.<tg>@<authority>} and {@code delete.v1.<tg>@<authority>}, written with {@code commit()} on the main
     * thread; reads on any thread. Package-private constructor over any Context (ZenlyCustomerRuntimeTest redirects it).
     */
    static final class Store {
        private final SharedPreferences preferences;
        private final Object lock = new Object();
        /** delete key → blocks (pending or done; an unreadable record blocks too); null = reload. */
        private volatile Map<String, Boolean> blocking;

        Store(Context context) {
            if (context == null) throw new IllegalArgumentException("context");
            preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        }

        static String bindingKey(long tg, String authority) { return BINDING_PREFIX + tg + "@" + authority; }

        static String deleteKey(long tg, String authority) { return DELETE_PREFIX + tg + "@" + authority; }

        /** The binding record; none when absent; an unreadable one reads as a loss at {@code now} (fail closed). */
        CustomerPipeline.Binding binding(long tg, String authority, long now) {
            String value = preferences.getString(bindingKey(tg, authority), null);
            if (value == null) return CustomerPipeline.Binding.NONE;
            CustomerPipeline.Binding binding = CustomerPipeline.Binding.fromTree(parse(value));
            return binding != null ? binding : CustomerPipeline.Binding.unreadable(now);
        }

        /** Stores {@code binding} (null or empty removes the key); false when the commit failed. */
        boolean saveBinding(long tg, String authority, CustomerPipeline.Binding binding) {
            synchronized (lock) {
                SharedPreferences.Editor editor = preferences.edit();
                String key = bindingKey(tg, authority);
                if (binding == null || binding.isEmpty()) {
                    editor.remove(key);
                } else {
                    editor.putString(key, binding.json());
                }
                return editor.commit();
            }
        }

        /** The deletion record, or null; an unreadable one reads as pending and possibly processed (fail closed). */
        DeleteFlow.Record deletion(long tg, String authority) {
            String value = preferences.getString(deleteKey(tg, authority), null);
            if (value == null) return null;
            DeleteFlow.Record record = DeleteFlow.Record.fromTree(parse(value));
            return record != null ? record : DeleteFlow.Record.unreadable(UUID.randomUUID().toString(), ZenlyPro.trustedNowSeconds());
        }

        /** Stores {@code record} (null removes the key) with commit(); false when the commit failed. */
        boolean saveDeletion(long tg, String authority, DeleteFlow.Record record) {
            synchronized (lock) {
                SharedPreferences.Editor editor = preferences.edit();
                String key = deleteKey(tg, authority);
                if (record == null) {
                    editor.remove(key);
                } else {
                    editor.putString(key, record.json());
                }
                boolean committed = editor.commit();
                blocking = null;
                return committed;
            }
        }

        /** Whether a deletion record of the user at {@code authority} blocks recording, linking and the pipeline. */
        boolean blocks(long tg, String authority) {
            Map<String, Boolean> cache = blocking;
            if (cache == null) {
                synchronized (lock) {
                    cache = blocking;
                    if (cache == null) {
                        HashMap<String, Boolean> loaded = new HashMap<>();
                        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                            if (!entry.getKey().startsWith(DELETE_PREFIX)) continue;
                            Object value = entry.getValue();
                            DeleteFlow.Record record = value instanceof String ? DeleteFlow.Record.fromTree(parse((String) value)) : null;
                            loaded.put(entry.getKey(), record == null || record.blocks());
                        }
                        blocking = cache = Collections.unmodifiableMap(loaded);
                    }
                }
            }
            Boolean value = cache.get(deleteKey(tg, authority));
            return value != null && value;
        }

        /** Every binding and delete key of the user at every origin; false when the commit failed. */
        boolean forget(long tg) {
            synchronized (lock) {
                SharedPreferences.Editor editor = preferences.edit();
                for (String key : preferences.getAll().keySet()) {
                    if ((key.startsWith(BINDING_PREFIX) || key.startsWith(DELETE_PREFIX)) && owner(key) == tg) editor.remove(key);
                }
                boolean committed = editor.commit();
                blocking = null;
                return committed;
            }
        }

        /** The Telegram users a binding, delete or autolink key names. */
        Set<Long> owners() {
            TreeSet<Long> owners = new TreeSet<>();
            for (String key : preferences.getAll().keySet()) {
                long owner = owner(key);
                if (owner > 0) owners.add(owner);
            }
            return owners;
        }

        /** The Telegram id of a {@code <prefix><tg>@<authority>} key, else 0. */
        static long owner(String key) {
            String rest;
            if (key.startsWith(BINDING_PREFIX)) {
                rest = key.substring(BINDING_PREFIX.length());
            } else if (key.startsWith(DELETE_PREFIX)) {
                rest = key.substring(DELETE_PREFIX.length());
            } else if (key.startsWith(AUTOLINK_PREFIX)) {
                rest = key.substring(AUTOLINK_PREFIX.length());
            } else {
                return 0;
            }
            int at = rest.indexOf('@');
            if (at <= 0 || at > 16 || at == rest.length() - 1 || rest.charAt(0) == '0') return 0;
            long value = 0;
            for (int i = 0; i < at; i++) {
                char c = rest.charAt(i);
                if (c < '0' || c > '9') return 0;
                value = value * 10 + (c - '0');
            }
            return value <= ConsentEvent.MAX_TELEGRAM_USER_ID ? value : 0;
        }
    }

    // ---- Plumbing ----

    /** The process's store, or null before the application context exists. */
    private static Store store() {
        Store current = store;
        if (current != null) return current;
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return null;
        synchronized (ZenlyCustomer.class) {
            if (store == null) store = new Store(context);
            return store;
        }
    }

    private static Slot slot(int account) {
        Slot s = SLOT[account];
        if (s == null) SLOT[account] = s = new Slot(account);
        return s;
    }

    private static boolean validSlot(int account) { return account >= 0 && account < SLOTS; }

    /** The raw Telegram id of an activated slot, else 0. */
    static long activatedUser(int account) {
        if (!validSlot(account)) return 0;
        try {
            UserConfig config = UserConfig.getInstance(account);
            return config.isClientActivated() ? Math.max(0, config.getClientUserId()) : 0;
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    /** Runs on the main thread: true when already there; otherwise reposts {@code again} and returns false. */
    private static boolean onMain(Runnable again) {
        if (Looper.myLooper() == Looper.getMainLooper()) return true;
        AndroidUtilities.runOnUIThread(again);
        return false;
    }

    private static long earliest(long a, long b) {
        if (b < 0) return a;
        if (a < 0) return b;
        return Math.min(a, b);
    }

    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }

    /** The route outcome of a CustomerApi reply (an unreadable 2xx counts as a transport failure). */
    static CustomerOutcome outcomeOf(CustomerOutcome.Route route, CustomerApi.Reply reply) {
        if (reply.kind == CustomerApi.Kind.STALE) return CustomerOutcome.of(route, 0, "session_changed", null, false);
        boolean unreadable = reply.kind == CustomerApi.Kind.UNAVAILABLE && reply.status >= 200 && reply.status < 300;
        boolean transport = reply.kind == CustomerApi.Kind.OFFLINE || unreadable;
        return CustomerOutcome.of(route, reply.status, reply.error, reply.reason, transport);
    }

    private static void countError(int account, CustomerEvents.ErrorDomain domain, CustomerOutcome outcome) {
        if (outcome.kind == CustomerOutcome.Kind.OK || outcome.kind == CustomerOutcome.Kind.STALE) return;
        ZenlyStats.error(account, domain, outcome.errorCode());
    }

    /** {@code consent.effective_at} of a 403 consent_required envelope (epoch seconds), else 0. */
    private static long effectiveAtOf(String body) {
        Map<String, Object> envelope = CustomerJson.object(parse(body));
        Map<String, Object> consent = envelope == null ? null : CustomerJson.object(envelope.get("consent"));
        long at = consent == null ? -1 : CustomerJson.parseInstant(CustomerJson.string(consent.get("effective_at")));
        return Math.max(0, at);
    }

    /** {@code recorded_at} of a grant ConsentEvent JSON (epoch seconds), else 0. */
    private static long recordedAt(String grantJson) {
        Map<String, Object> event = CustomerJson.object(parse(grantJson));
        long at = event == null ? -1 : CustomerJson.parseInstant(CustomerJson.string(event.get("recorded_at")));
        return Math.max(0, at);
    }

    /** A JSON object or array as plain values (Map / List / String / Integer / Long / Double / Boolean / null), or null. */
    static Object parse(String text) {
        if (text == null) return null;
        try {
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) return plain(new JSONObject(trimmed));
            if (trimmed.startsWith("[")) return plain(new JSONArray(trimmed));
        } catch (JSONException unreadable) {
            // Unreadable values read as absent.
        }
        return null;
    }

    private static Object plain(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                map.put(key, plain(object.opt(key)));
            }
            return map;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            ArrayList<Object> list = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) list.add(plain(array.opt(i)));
            return list;
        }
        return value == JSONObject.NULL ? null : value;
    }
}
