package org.telegram.messenger.zenlygram.customer;

import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.pro.ProFunnelSink;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.SettingsActivity;

/**
 * The statistics facade (design 50 §11.1–§11.3; contract §3): typed methods for exactly the taxonomy v1 events, any
 * thread, never throwing, and a no-op unless the account may record ({@link #allowed}: signed in, consent effectively
 * granted with a non-rejected latest grant and no binding loss or stale grant since, no deletion pending — and
 * {@link #install} done). Events are queued before the link and sent after the consents step (ZenlyEventUploader).
 * The facade has no free-form string parameter except {@code productId}, {@code basePlanId}, {@code errorCode} /
 * {@code code} and a tab's screen wire name, each checked against its registry pattern, so no chat, peer, name,
 * message text, search term or exception text can reach an event (contract §6.9).
 * <p>
 * Sessions (§11.2) follow the selected account in the foreground: ZenlyCustomer calls {@link #onForeground},
 * {@link #onBackground}, {@link #onAccountSwitch} and {@link #onLogout}; a grant made while in the foreground starts
 * one too (the consent notification). The screen on top when a session starts is its first {@code screen.view}.
 * Screens (§11.3) come from the fragment stacks of all three layouts ({@link #onTopFragment}), the main tabs
 * ({@link #mainTab}) and dialog show sites ({@link #screen}); Telegram's own screens only as stack roots or tabs, never
 * as pickers. Screens of later packages report themselves through {@link NamedScreen}. Nothing here logs.
 */
public final class ZenlyStats {
    /** A fragment that names its own screen (the Tasks area's screens, the Data & consent screens, the main tabs). */
    public interface NamedScreen {
        /** The screen this fragment shows now, or null when it is not reported. */
        CustomerEvents.Screen zenlyScreen();
    }

    /** The deletion state of the customer coordinator (ZenlyCustomer): nothing is recorded while a deletion is pending. */
    public interface Gate {
        boolean deletionPending(long telegramUserId);
    }

    private static final Object LOCK = new Object();
    /** Null until {@link #install}: nothing is recorded or uploaded. */
    private static volatile Gate gate;
    // Under LOCK.
    private static boolean foreground, backgroundSeen, observing;
    private static int sessionAccount = -1;
    private static long sessionUser;
    private static String sessionScope;
    private static int topAccount = -1;
    private static CustomerEvents.Screen topScreen;

    private ZenlyStats() {}

    // ---- Installation and the recording rule ----

    /**
     * Turns statistics on (ZenlyCustomer.onPostInit, main thread): {@code deletionGate} answers whether a deletion is
     * pending; the Play funnel sink starts mapping through ProFunnel; consent changes are observed so that a grant made
     * in the foreground starts the session. Null turns recording off again.
     */
    public static void install(Gate deletionGate) {
        gate = deletionGate;
        ProFunnelSink.Holder.set(deletionGate == null ? null : FUNNEL);
        if (deletionGate == null) return;
        runOnMain(() -> {
            synchronized (LOCK) {
                if (observing) return;
                observing = true;
            }
            try {
                NotificationCenter.getGlobalInstance().addObserver(CONSENT_OBSERVER, NotificationCenter.zenlygramConsentChanged);
            } catch (RuntimeException unavailable) {
                synchronized (LOCK) {
                    observing = false;
                }
            }
        });
    }

    /** Whether statistics are recorded for the account slot now. */
    public static boolean allowed(int account) {
        long tg = telegramUserId(account);
        return tg > 0 && allowedUser(tg);
    }

    /** The recording rule for a Telegram user (asked again on the store's thread before every write). */
    static boolean allowedUser(long telegramUserId) {
        Gate current = gate;
        if (current == null || telegramUserId <= 0) return false;
        try {
            return CustomerEvents.recordable(true, ZenlyConsent.snapshot(telegramUserId), current.deletionPending(telegramUserId));
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** The raw Telegram user id of a signed-in (activated) account slot, else 0. */
    static long telegramUserId(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return 0;
        try {
            UserConfig config = UserConfig.getInstance(account);
            return config.isClientActivated() ? Math.max(0, config.getClientUserId()) : 0;
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    /**
     * Purge (withdrawal, decline, rejected grant, binding loss, logout, deletion, remote withdrawal): every queued event
     * and session of {@code telegramUserId} in every scope; an open session of it is dropped silently.
     */
    public static void forget(long telegramUserId) {
        try {
            synchronized (LOCK) {
                if (sessionUser == telegramUserId) clearSessionLocked();
            }
            ZenlyEventStore store = ZenlyEventStore.get();
            if (store != null) store.deleteAll(telegramUserId);
        } catch (RuntimeException ignored) {
            // Statistics never break the caller.
        }
    }

    // ---- Sessions (design 50 §11.2) ----

    /** ForegroundDetector.onBecameForeground: the selected account's session starts ({@code launch} the first time). */
    public static void onForeground() {
        try {
            CustomerEvents.SessionTrigger trigger;
            synchronized (LOCK) {
                foreground = true;
                trigger = backgroundSeen ? CustomerEvents.SessionTrigger.FOREGROUND : CustomerEvents.SessionTrigger.LAUNCH;
            }
            startSession(UserConfig.selectedAccount, trigger);
        } catch (RuntimeException ignored) {
            // Statistics never break the caller.
        }
    }

    /** ForegroundDetector.onBecameBackground: the open session ends ({@code background}). */
    public static void onBackground() {
        try {
            synchronized (LOCK) {
                foreground = false;
                backgroundSeen = true;
            }
            endSession(CustomerEvents.SessionEndReason.BACKGROUND);
        } catch (RuntimeException ignored) {
            // Statistics never break the caller.
        }
    }

    /** LaunchActivity.switchToAccount: the old account's session ends and, in the foreground, the new one's starts. */
    public static void onAccountSwitch(int previousAccount, int nextAccount) {
        try {
            boolean inForeground;
            synchronized (LOCK) {
                inForeground = foreground;
            }
            endSession(CustomerEvents.SessionEndReason.ACCOUNT_SWITCH);
            if (inForeground) startSession(nextAccount, CustomerEvents.SessionTrigger.ACCOUNT_SWITCH);
        } catch (RuntimeException ignored) {
            // Statistics never break the caller.
        }
    }

    /** Logout of {@code account} (before its data is purged): its session ends ({@code logout}). */
    public static void onLogout(int account) {
        try {
            boolean open;
            synchronized (LOCK) {
                open = sessionAccount == account;
            }
            if (open) endSession(CustomerEvents.SessionEndReason.LOGOUT);
        } catch (RuntimeException ignored) {
            // Statistics never break the caller.
        }
    }

    /**
     * The account's consent changed (also observed from {@code zenlygramConsentChanged}): a grant while the selected
     * account is in the foreground starts its session ({@code launch} / {@code foreground} by the process flag); an end
     * of recording drops its open session silently (the queue goes with the purge).
     */
    public static void onConsentChanged(int account) {
        try {
            if (!allowed(account)) {
                synchronized (LOCK) {
                    if (sessionAccount == account) clearSessionLocked();
                }
                return;
            }
            CustomerEvents.SessionTrigger trigger;
            synchronized (LOCK) {
                if (!foreground || account != UserConfig.selectedAccount || sessionAccount == account) return;
                trigger = backgroundSeen ? CustomerEvents.SessionTrigger.FOREGROUND : CustomerEvents.SessionTrigger.LAUNCH;
            }
            startSession(account, trigger);
        } catch (RuntimeException ignored) {
            // Statistics never break the caller.
        }
    }

    private static void startSession(int account, CustomerEvents.SessionTrigger trigger) {
        final long tg = telegramUserId(account);
        String scope = ZenlyPro.configuredStorageToken();
        ZenlyEventStore store = ZenlyEventStore.get();
        if (tg <= 0 || scope == null || store == null || !allowedUser(tg)) return;
        CustomerEvents.Screen visible;
        boolean endOther;
        synchronized (LOCK) {
            if (sessionAccount == account && sessionUser == tg && scope.equals(sessionScope)) return;
            endOther = sessionAccount >= 0;
        }
        if (endOther) endSession(CustomerEvents.SessionEndReason.ACCOUNT_SWITCH);
        synchronized (LOCK) {
            sessionAccount = account;
            sessionUser = tg;
            sessionScope = scope;
            visible = topAccount == account ? topScreen : null;
        }
        store.startSession(scope, tg, trigger, visible, ZenlyCustomerDevice.context(), nowSeconds(), SystemClock.elapsedRealtime(),
                () -> allowedUser(tg));
    }

    private static void endSession(CustomerEvents.SessionEndReason reason) {
        final long tg;
        String scope;
        synchronized (LOCK) {
            if (sessionAccount < 0) return;
            tg = sessionUser;
            scope = sessionScope;
            clearSessionLocked();
        }
        ZenlyEventStore store = ZenlyEventStore.get();
        if (store == null) return;
        store.endSession(scope, tg, reason, ZenlyCustomerDevice.context(), nowSeconds(), SystemClock.elapsedRealtime(), () -> allowedUser(tg));
    }

    private static void clearSessionLocked() {
        sessionAccount = -1;
        sessionUser = 0;
        sessionScope = null;
    }

    // ---- Screens (design 50 §11.3) ----

    /**
     * A fragment-stack listener of any layout reports its top fragment; {@code stackRoot} = it is index 0 of its
     * layout's stack.
     */
    public static void onTopFragment(int account, BaseFragment top, boolean stackRoot) {
        try {
            shown(account, screenOf(top, stackRoot));
        } catch (RuntimeException ignored) {
            // Statistics never break the caller.
        }
    }

    /** MainTabsActivity shows a tab ({@code zenlyTabScreen()}: a screen wire name, or null for a tab that is not reported). */
    public static void mainTab(int account, String screenWire) {
        try {
            shown(account, CustomerEvents.Screen.of(screenWire));
        } catch (RuntimeException ignored) {
            // Statistics never break the caller.
        }
    }

    /** A screen shown outside the fragment stacks (a dialog: vault.unlock, chat_lock.unlock, edit_history.message; Tasks views). */
    public static void screen(int account, CustomerEvents.Screen screen) {
        if (screen != null) record(account, CustomerEvents.screenView(screen));
    }

    /**
     * The screen a fragment-stack top reports (design 50 §11.3; classes compared exactly, never by name): the Zenlygram
     * screens wherever they sit; {@code DialogsActivity} → tg.chat_list, {@code ContactsActivity} → tg.contacts,
     * {@code CallLogActivity} → tg.calls, {@code SettingsActivity} → tg.settings only as a stack root that is not a
     * picker; every other fragment (chats, profiles, media, search, …) → null.
     */
    public static CustomerEvents.Screen screenOf(BaseFragment top, boolean stackRoot) {
        if (top == null) return null;
        if (top instanceof NamedScreen) return CustomerEvents.stackScreen(((NamedScreen) top).zenlyScreen(), stackRoot, false);
        // ZENLYGRAM 13.2.1: the Zenlygram screens (org.telegram.zenlygram.*) name themselves through NamedScreen above;
        // only the Telegram-native roots are matched by class here.
        Class<?> type = top.getClass();
        Bundle arguments = top.getArguments();
        if (type == DialogsActivity.class) {
            return CustomerEvents.stackScreen(CustomerEvents.Screen.TG_CHAT_LIST, stackRoot, flag(arguments, "onlySelect"));
        }
        if (type == ContactsActivity.class) {
            boolean picker = flag(arguments, "returnAsResult") || flag(arguments, "destroyAfterSelect");
            return CustomerEvents.stackScreen(CustomerEvents.Screen.TG_CONTACTS, stackRoot, picker);
        }
        if (type == CallLogActivity.class) return CustomerEvents.stackScreen(CustomerEvents.Screen.TG_CALLS, stackRoot, false);
        if (type == SettingsActivity.class) return CustomerEvents.stackScreen(CustomerEvents.Screen.TG_SETTINGS, stackRoot, false);
        return null;
    }

    /** {@code pro.screen_view.source} of the fragment the Pro screen was opened from (design 50 §11.4). */
    public static CustomerEvents.ProSource proSourceOf(BaseFragment previous) {
        ProFunnel.Origin origin = ProFunnel.Origin.OTHER;
        // ZENLYGRAM 13.2.1: a Zenlygram screen is known by the screen it names (NamedScreen), not by its class.
        CustomerEvents.Screen named = previous instanceof NamedScreen ? ((NamedScreen) previous).zenlyScreen() : null;
        if (named == CustomerEvents.Screen.ZG_HUB || named == CustomerEvents.Screen.ZG_PREFERENCES || previous instanceof SettingsActivity) {
            origin = ProFunnel.Origin.SETTINGS;
        } else if (named != null && named.wire.startsWith("tasks.")) {
            origin = ProFunnel.Origin.TASKS;
        } else if (named == CustomerEvents.Screen.CHAT_LOCK_SETTINGS || named == CustomerEvents.Screen.EDIT_HISTORY_SETTINGS
                || named == CustomerEvents.Screen.VAULT_CHATS || named == CustomerEvents.Screen.VAULT_SETTINGS || previous instanceof ChatActivity) {
            origin = ProFunnel.Origin.FEATURE_GATE;
        }
        return ProFunnel.sourceOf(origin);
    }

    private static void shown(int account, CustomerEvents.Screen screen) {
        synchronized (LOCK) {
            topAccount = account;
            topScreen = screen;
        }
        if (screen != null) record(account, CustomerEvents.screenView(screen));
    }

    private static boolean flag(Bundle arguments, String key) {
        return arguments != null && arguments.getBoolean(key, false);
    }

    // ---- Typed events (design 50 §11.1) ----

    public static void featureToggle(int account, CustomerEvents.Feature feature, boolean enabled) {
        if (feature != null) record(account, CustomerEvents.featureToggle(feature, enabled));
    }

    /** At most once per feature per session. */
    public static void featureUsed(int account, CustomerEvents.Feature feature) {
        if (feature != null) record(account, CustomerEvents.featureUsed(feature));
    }

    public static void taskCreated(int account, CustomerEvents.TasksScope scope, boolean hasDue, int priority, boolean inList) {
        if (scope != null) record(account, CustomerEvents.taskCreated(scope, hasDue, priority, inList));
    }

    public static void taskCompleted(int account, CustomerEvents.TasksScope scope, boolean reopened) {
        if (scope != null) record(account, CustomerEvents.taskCompleted(scope, reopened));
    }

    public static void taskDeleted(int account, CustomerEvents.TasksScope scope) {
        if (scope != null) record(account, CustomerEvents.taskDeleted(scope));
    }

    public static void listCreated(int account, CustomerEvents.TasksScope scope) {
        if (scope != null) record(account, CustomerEvents.listCreated(scope));
    }

    public static void listDeleted(int account, CustomerEvents.TasksScope scope, int tasks) {
        if (scope != null) record(account, CustomerEvents.listDeleted(scope, tasks));
    }

    public static void tasksShared(int account, CustomerEvents.SharedAction action) {
        if (action != null) record(account, CustomerEvents.tasksShared(action));
    }

    public static void tasksMigration(int account, CustomerEvents.MigrationPhase phase, Integer lists, Integer tasks, Integer skipped, Long durationS,
            String errorCode) {
        if (phase != null) record(account, CustomerEvents.tasksMigration(phase, lists, tasks, skipped, durationS, errorCode));
    }

    /** One Tasks sync issue, aggregated per session per kind into {@code tasks.sync_issue{kind, count}}. */
    public static void tasksSyncIssue(int account, CustomerEvents.SyncIssueKind kind) {
        if (kind == null) return;
        try {
            final long tg = telegramUserId(account);
            String scope = ZenlyPro.configuredStorageToken();
            ZenlyEventStore store = ZenlyEventStore.get();
            if (tg <= 0 || scope == null || store == null || !allowedUser(tg)) return;
            store.countSyncIssue(scope, tg, kind, 1, () -> allowedUser(tg));
        } catch (RuntimeException ignored) {
            // Statistics never break the caller.
        }
    }

    /** Method {@code biometric}, else {@code password}. */
    public static void vaultUnlocked(int account, boolean biometric) { record(account, CustomerEvents.vaultUnlocked(biometric)); }

    public static void vaultChatAdded(int account) { record(account, CustomerEvents.vaultChatAdded()); }

    public static void vaultChatRemoved(int account) { record(account, CustomerEvents.vaultChatRemoved()); }

    /** Method {@code password}; {@code biometricEnabled} = biometric unlock is on for the lock. */
    public static void chatLockSet(int account, boolean biometricEnabled) { record(account, CustomerEvents.chatLockSet(biometricEnabled)); }

    public static void chatLockUnlocked(int account, boolean biometric) { record(account, CustomerEvents.chatLockUnlocked(biometric)); }

    public static void chatLockRemoved(int account) { record(account, CustomerEvents.chatLockRemoved()); }

    /** Kind {@code deleted} for a kept deleted message, else {@code edited}. */
    public static void editHistoryViewed(int account, boolean deleted) { record(account, CustomerEvents.editHistoryViewed(deleted)); }

    /** Mode {@code paid} when purchases are offered, else {@code free}. */
    public static void proScreenView(int account, CustomerEvents.ProSource source, boolean paid) {
        if (source != null) record(account, CustomerEvents.proScreenView(source, ProFunnel.mode(paid)));
    }

    public static void proPlanSelect(int account, String productId, CustomerEvents.Rail rail, String basePlanId) {
        if (rail != null) record(account, CustomerEvents.proPlanSelect(productId, rail, basePlanId));
    }

    public static void proPurchaseStart(int account, String productId, CustomerEvents.Rail rail, String basePlanId) {
        if (rail != null) record(account, CustomerEvents.proPurchaseStart(productId, rail, basePlanId));
    }

    public static void proPurchaseSuccess(int account, String productId, CustomerEvents.Rail rail, String basePlanId, boolean trial) {
        if (rail != null) record(account, CustomerEvents.proPurchaseSuccess(productId, rail, basePlanId, trial));
    }

    public static void proPurchaseFail(int account, String productId, CustomerEvents.Rail rail, String basePlanId, CustomerEvents.FailReason reason,
            String errorCode) {
        if (rail != null && reason != null) record(account, CustomerEvents.proPurchaseFail(productId, rail, basePlanId, reason, errorCode));
    }

    public static void proRestore(int account, CustomerEvents.Rail rail, CustomerEvents.RestoreOutcome outcome, String errorCode) {
        if (rail != null && outcome != null) record(account, CustomerEvents.proRestore(rail, outcome, errorCode));
    }

    public static void proTrialStart(int account, CustomerEvents.TrialOutcome outcome, String errorCode) {
        if (outcome != null) record(account, CustomerEvents.proTrialStart(outcome, errorCode));
    }

    /** End of one bind attempt; {@code durationS} < 0 = unknown (omitted). */
    public static void linkAttempt(int account, boolean auto, boolean attest, boolean success, String errorCode, long durationS) {
        record(account, CustomerEvents.linkAttempt(auto, attest, success, errorCode, durationS));
    }

    /** One error, aggregated per (domain, code) per session into {@code error.occurred{domain, code, count}}. */
    public static void error(int account, CustomerEvents.ErrorDomain domain, String code) {
        if (domain == null) return;
        try {
            final long tg = telegramUserId(account);
            String scope = ZenlyPro.configuredStorageToken();
            ZenlyEventStore store = ZenlyEventStore.get();
            if (tg <= 0 || scope == null || store == null || !allowedUser(tg)) return;
            store.countError(scope, tg, domain, ErrorCodes.normalize(code), () -> allowedUser(tg));
        } catch (RuntimeException ignored) {
            // Statistics never break the caller.
        }
    }

    private static void record(int account, CustomerEvents.Event event) {
        if (event == null) return;
        try {
            final long tg = telegramUserId(account);
            if (tg <= 0 || !allowedUser(tg)) return;
            String scope = ZenlyPro.configuredStorageToken();
            ZenlyEventStore store = ZenlyEventStore.get();
            if (scope == null || store == null) return;
            store.record(scope, tg, event, ZenlyCustomerDevice.context(), nowSeconds(), SystemClock.elapsedRealtime(), () -> allowedUser(tg));
        } catch (RuntimeException ignored) {
            // Statistics never break the caller.
        }
    }

    // ---- Plumbing ----

    /** Event {@code ts} and {@code sent_at} use the device clock (the server corrects the batch's skew, contract §3.5). */
    private static long nowSeconds() { return System.currentTimeMillis() / 1000L; }

    private static void runOnMain(Runnable work) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            work.run();
        } else {
            AndroidUtilities.runOnUIThread(work);
        }
    }

    private static final NotificationCenter.NotificationCenterDelegate CONSENT_OBSERVER = (id, account, args) -> {
        if (id == NotificationCenter.zenlygramConsentChanged && args != null && args.length > 0 && args[0] instanceof Integer) {
            onConsentChanged((Integer) args[0]);
        }
    };

    /** The Play funnel ({@code rail: google}) through ProFunnel; GooglePlayCheckout reports to it via ProFunnelSink.Holder. */
    private static final ProFunnelSink FUNNEL = new ProFunnelSink() {
        @Override public void purchaseStart(int account, String productId, String basePlanId) {
            proPurchaseStart(account, productId, CustomerEvents.Rail.GOOGLE, basePlanId);
        }

        @Override public void purchaseSuccess(int account, String productId, String basePlanId, boolean trial) {
            proPurchaseSuccess(account, productId, CustomerEvents.Rail.GOOGLE, basePlanId, trial);
        }

        @Override public void purchaseFail(int account, String productId, String basePlanId, String problemName, String serverCode) {
            proPurchaseFail(account, productId, CustomerEvents.Rail.GOOGLE, basePlanId, ProFunnel.reason(problemName), ProFunnel.serverCode(serverCode));
        }

        @Override public void restore(int account, String outcome, String serverCode) {
            proRestore(account, CustomerEvents.Rail.GOOGLE, ProFunnel.restoreOutcome(outcome), ProFunnel.serverCode(serverCode));
        }
    };
}
