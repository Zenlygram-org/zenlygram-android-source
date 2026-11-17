/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.cloud;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.customer.ZenlyConsent;
import org.telegram.messenger.zenlygram.customer.ZenlyCustomer;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.messenger.zenlygram.tasks.ZenlyTasksSyncOwner;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRepository;

import java.util.Map;

/**
 * The app's hooks into the authenticated cloud client: the account link and entitlement
 * ({@code org.telegram.messenger.zenlygram.pro}), customer data ({@code .customer}: consent upload, profile, statistics,
 * account deletion) and Cloud Tasks ({@code .tasks}). Upstream files call only this class, one line per hook, so an
 * upstream merge stays easy; the order inside each hook is the one the client was written and tested with.
 * See docs/zenlygram-cloud-android.md.
 * <p>
 * Everything here is a no-op while {@link ZgCloudConfig#AUTHENTICATED_BACKEND} is false. Main thread unless noted.
 * Nothing is sent for an account without its data consent, and nothing at all before the first foreground: the hooks
 * only install local state.
 */
public final class ZgBackend {

    private static boolean started;

    private ZgBackend() {
    }

    public static boolean enabled() {
        return ZgCloudConfig.AUTHENTICATED_BACKEND;
    }

    /** ApplicationLoader.postInitApplication, after every slot's config is loaded. */
    public static void onPostInit() {
        if (!enabled() || started) {
            return;
        }
        started = true;
        try {
            final long[] signedInUserIds = new long[UserConfig.MAX_ACCOUNT_COUNT];
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                final UserConfig slot = UserConfig.getInstance(a);
                signedInUserIds[a] = slot.isClientActivated() ? slot.getClientUserId() : 0;
            }
            // Cloud Tasks stores of users who are no longer signed in go first, before anything opens a store.
            TasksCloudRepository.reconcileAfterLaunch(signedInUserIds);
            // The Pro store / install record and this account's session; then the free-first policy (asynchronous).
            ZenlyPro.get(UserConfig.selectedAccount).snapshot();
            ZenlyPro.refreshPolicy();
            // The app's gate (ZgPro) follows the client's signed decision from here on.
            org.telegram.zenlygram.pro.ZgPro.attachToClient();
            // Customer data: the consent snapshot, the binding listener, the automatic link gate and the statistics gate.
            ZenlyCustomer.onPostInit(signedInUserIds);
            // The one-time import of the old device-wide Tasks board is queued on the device store before the sync
            // owner can run its first copy step, so a device that is already linked copies those rows too (the main
            // screen calls prepare again; the import runs once).
            org.telegram.zenlygram.tasks.ZgTasks.prepare(UserConfig.selectedAccount);
            // Cloud Tasks: the consent gate, the analytics sink and the write fence, then per-account sync and migration.
            ZenlyTasksSyncOwner.onPostInit();
            installDiagnostics();
        } catch (Throwable e) {
            // The cloud client never keeps the app from starting.
            FileLog.e(e);
        }
    }

    // ---- Diagnostics: one content-free line per state change, only while the user has turned logs on ----

    private static final NotificationCenter.NotificationCenterDelegate DIAGNOSTICS = (id, account, args) -> {
        if (id == NotificationCenter.zenlygramConsentChanged) {
            if (args != null && args.length > 0 && args[0] instanceof Integer) {
                logState("consent", (Integer) args[0]);
            }
        } else if (id == NotificationCenter.zenlygramProStatusChanged) {
            logState("link", account);
        } else if (id == NotificationCenter.zenlygramCustomerStateChanged) {
            logState("customer", account);
        } else if (id == NotificationCenter.zenlygramTasksModeChanged) {
            logState("tasks", account);
        }
    };

    private static void installDiagnostics() {
        NotificationCenter.getGlobalInstance().addObserver(DIAGNOSTICS, NotificationCenter.zenlygramConsentChanged);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final NotificationCenter center = NotificationCenter.getInstance(a);
            center.addObserver(DIAGNOSTICS, NotificationCenter.zenlygramProStatusChanged);
            center.addObserver(DIAGNOSTICS, NotificationCenter.zenlygramCustomerStateChanged);
            center.addObserver(DIAGNOSTICS, NotificationCenter.zenlygramTasksModeChanged);
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            logState("launch", a);
        }
    }

    /**
     * What support needs first when "it does not sync": the link, consent and Tasks states of a signed-in slot, as
     * enum names and booleans only (never an id, a token, a code or any content). Written only while logs are enabled
     * (Telegram's debug menu), like every other FileLog line.
     */
    private static void logState(String reason, int account) {
        if (!BuildVars.LOGS_ENABLED || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        try {
            final UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated()) {
                return;
            }
            final ZenlyPro.Snapshot pro = ZenlyPro.get(account).snapshot();
            FileLog.d("zg-backend " + reason + ": slot=" + account
                + " pro=" + pro.status + " linked=" + pro.linked + " configured=" + pro.configured
                + " consent=" + ZenlyConsent.state(config.getClientUserId())
                + " tasksMayRun=" + ZenlyCustomer.tasksMayRun(account)
                + " tasks=" + ZenlyTasksSyncOwner.permission(account)
                + " cutOver=" + ZenlyTasksSyncOwner.cutOver(account).kind);
        } catch (Throwable ignore) {
            // Diagnostics never matter to the app.
        }
    }

    /** LaunchActivity.onResume (through ZgAppUpdate). */
    public static void onAppResumed() {
        if (!enabled() || !started) {
            return;
        }
        try {
            ZenlyPro.refreshPolicy();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    // The entitlement of a linked account, a pending push hint, the push token registration.
                    ZenlyPro.get(a).refreshIfNeeded();
                }
            }
            ZenlyTasksSyncOwner.onAppResumed();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** LaunchActivity.onPause (through ZgAppUpdate): one more Tasks drain, then the sync stops. */
    public static void onAppPaused() {
        if (!enabled() || !started) {
            return;
        }
        try {
            ZenlyTasksSyncOwner.onAppPaused();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** LoginActivity, right after a sign-in completed: the new user's consent and binding state are read. */
    public static void onAuthSuccess(int account) {
        if (!enabled() || !started) {
            return;
        }
        try {
            ZenlyCustomer.onAuthSuccess(account);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** LaunchActivity.switchToAccount, BEFORE UserConfig.selectedAccount changes. */
    public static void onAccountSwitch(int from, int to) {
        if (!enabled() || !started) {
            return;
        }
        try {
            ZenlyCustomer.onAccountSwitch(from, to);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** LaunchActivity.switchToAccount, AFTER the selection changed: the new account's pending hint or stale grant. */
    public static void afterAccountSwitch(int account) {
        if (!enabled() || !started) {
            return;
        }
        try {
            ZenlyPro.get(account).refreshIfNeeded();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * MessagesController.performLogout, BEFORE clearConfig ({@code userId} is the account's Telegram id). Order: the
     * customer layer queues a pending consent withdrawal ahead of the revoke and purges this user's consent, events and
     * customer keys; the user's Cloud Tasks stores are purged; then the binding is revoked and the session dropped.
     * Never cancels a subscription and never removes Vault, Chat Lock or device task data.
     */
    public static void onLogout(int account, long userId) {
        if (!enabled() || !started) {
            return;
        }
        try {
            ZenlyCustomer.onLogout(account, userId);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            if (!TasksCloudRepository.onLogout(account, userId)) {
                FileLog.e("Zenlygram: the Cloud Tasks cleanup marker could not be saved; the cleanup is pending");
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            ZenlyPro.get(account).unlink();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * The extra lines of the logout confirmation: a consent withdrawal that has not reached the server, unsent Cloud
     * Tasks changes, an unfinished copy of the device tasks. Null when there is nothing to say.
     */
    public static CharSequence logoutNotice(Context context, int account) {
        if (!enabled() || !started) {
            return null;
        }
        try {
            final StringBuilder sb = new StringBuilder();
            final String customer = ZenlyCustomer.logoutNotice(account);
            if (!TextUtils.isEmpty(customer)) {
                sb.append(customer);
            }
            final String tasks = ZenlyTasksSyncOwner.logoutNotice(context != null ? context : ApplicationLoader.applicationContext, account);
            if (!TextUtils.isEmpty(tasks)) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(tasks);
            }
            return sb.length() == 0 ? null : sb;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * PushListenerController.sendRegistrationToServer, Firebase tokens only (any thread): registers the token for the
     * linked bindings on this device. Telegram's own registration is not affected.
     */
    public static void onPushToken(String token) {
        if (!enabled() || TextUtils.isEmpty(token)) {
            return;
        }
        try {
            ZenlyPro.onPushToken(token);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * GcmPushListenerService.onMessageReceived, before any Telegram decoding or logging (FCM thread). True when the
     * message was a Zenlygram entitlement hint and was consumed; a hint only triggers an authenticated refresh of the
     * matching linked account, nothing in it grants anything.
     */
    public static boolean onPushHint(Map<String, String> data) {
        if (!enabled() || data == null) {
            return false;
        }
        try {
            return ZenlyPro.onPushHint(data);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }
}
