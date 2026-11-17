/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.zenlygram.chatlock.ZgChatLockSession;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.lock.ZgLockBiometrics;
import org.telegram.zenlygram.lock.ZgLockCode;
import org.telegram.zenlygram.lock.ZgLockCrypto;
import org.telegram.zenlygram.lock.ZgLockThrottle;
import org.telegram.zenlygram.lock.ZgMasterPin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Secret Vault's state (iOS SecretVault + ChatPincodeManager vault part): the switch, the vault PIN, which chats
 * each account hides, and the chat-list rows of the hidden chats. Reads are cheap and thread-safe; writes run on the
 * main thread (hashing on the lock worker), are one commit() each and change nothing in memory when they fail.
 *
 * <p>Storage: SharedPreferences {@value #PREFS} (app-private, credential-encrypted storage; Telegram's BackupAgent
 * backs up only saved_tokens, so nothing leaves the device):
 * <ul>
 *   <li>{@code enabled}: the switch. The vault is <b>active</b> while it is on and a PIN exists.</li>
 *   <li>{@code cred}: a {@link ZgLockCrypto} record of the PIN or password (salted PBKDF2 + Keystore HMAC; never the
 *       code), {@code cred_meta} ({@link ZgLockCode#encodeMeta}: type, biometric unlock),
 *       {@code cred_master_gen}: the Chat Lock master generation that existed when the PIN was set (the only master
 *       "Forgot PIN" accepts).</li>
 *   <li>{@code state_v1}: the members and pin records ({@link ZgVaultState}) encrypted by {@link ZgVaultCrypto};
 *       absent while no chat is hidden, so a vault without members never touches the Keystore at startup.</li>
 *   <li>{@code member_count} (non-secret, recovery copy), {@code recovery} (bit {@value #RECOVERY_MEMBERSHIP_LOST}).</li>
 * </ul>
 * Biometric baseline / unlock slot: {@link ZgLockBiometrics} slot {@value #BIO_SLOT}. Throttle: ZgLockThrottle VAULT.
 *
 * <p>Fail-safe rules: an unreadable membership blob is never overwritten (mutations refuse); a blob that is gone for
 * good (Keystore key lost, damaged data) makes the hidden chats visible again and asks for recovery; a credential that
 * can never be checked again asks for recovery instead of a keypad nothing can satisfy. Nothing here ever deletes a
 * chat or a message.
 */
public final class ZgVault {

    public interface Listener {
        /** Main thread: members, the switch or the PIN changed. */
        void onVaultChanged();
    }

    public static final String BIO_SLOT = "vault";
    static final int RECOVERY_MEMBERSHIP_LOST = 2;

    static final int LOAD_OK = 0;
    /** The Keystore refused for now; nothing is hidden meanwhile and nothing may be written. Retried. */
    static final int LOAD_UNAVAILABLE = 1;
    /** The blob is gone for good: nothing is hidden, recovery is offered. */
    static final int LOAD_LOST = 2;

    private static final String PREFS = "zg_secret_vault";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_CRED = "cred";
    private static final String KEY_CRED_META = "cred_meta";
    private static final String KEY_MASTER_GEN = "cred_master_gen";
    private static final String KEY_STATE = "state_v1";
    private static final String KEY_COUNT = "member_count";
    private static final String KEY_RECOVERY = "recovery";

    /** Retries of a busy Keystore: then every 2 minutes for as long as it takes, and at once on the next touch. */
    private static final long[] RETRY_DELAYS_MS = {1_000L, 3_000L, 10_000L, 30_000L, 60_000L, 120_000L};
    private static final long TOUCH_RETRY_MIN_GAP_MS = 2_000L;

    private static final Object lock = new Object();
    private static volatile boolean initialized;
    /** Ported, switched on and a PIN exists: the only flag the hooks read when the vault is off. */
    private static volatile boolean active;
    private static volatile ZgVaultState state = ZgVaultState.EMPTY;
    private static volatile int loadStatus = LOAD_OK;
    private static int retryAttempt;
    private static int retryGeneration;
    private static long lastRetryAt;
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Chat-list rows of the hidden chats per account slot, built by MessagesController.sortDialogs (main thread). */
    @SuppressWarnings("unchecked")
    private static final ArrayList<TLRPC.Dialog>[] dialogs = new ArrayList[UserConfig.MAX_ACCOUNT_COUNT];

    private ZgVault() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Start-up
    // ---------------------------------------------------------------------------------------------------------

    /**
     * ApplicationLoader.postInitApplication (main thread), before the MessagesController instances exist, so the
     * first sortDialogs already hides. Runs in FCM-started processes too. Never throws.
     */
    @MainThread
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!ZgFeatures.SECRET_VAULT.isPorted()) {
            return;
        }
        try {
            refreshActive();
            loadState();
            ZgChatLockSession.setProtectionLayer(ZgVaultLayer.INSTANCE);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void loadState() {
        final String blob = prefs().getString(KEY_STATE, null);
        if (blob == null) {
            state = ZgVaultState.EMPTY;
            loadStatus = LOAD_OK;
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            final ZgVaultCrypto.Result result = ZgVaultCrypto.open(blob);
            if (result.status == ZgVaultCrypto.Status.OK) {
                if (applyDecrypted(result.text)) {
                    return;
                }
                markLost();
                return;
            }
            if (result.status == ZgVaultCrypto.Status.LOST) {
                markLost();
                return;
            }
            if (attempt < 2) {
                SystemClock.sleep(40);
            }
        }
        loadStatus = LOAD_UNAVAILABLE;
        scheduleRetry();
    }

    private static boolean applyDecrypted(@Nullable String text) {
        try {
            state = ZgVaultState.decode(text);
            loadStatus = LOAD_OK;
            return true;
        } catch (Throwable e) {
            FileLog.e("ZgVault: membership unreadable: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static void markLost() {
        state = ZgVaultState.EMPTY;
        loadStatus = LOAD_LOST;
        final SharedPreferences preferences = prefs();
        preferences.edit().putInt(KEY_RECOVERY, preferences.getInt(KEY_RECOVERY, 0) | RECOVERY_MEMBERSHIP_LOST).commit();
        FileLog.e("ZgVault: membership lost, hidden chats are visible until the vault is restored");
    }

    /**
     * The Keystore was busy at start-up: try again in the background (no limit: hidden chats are visible meanwhile
     * and every notification is redacted, {@link #isFailingClosed}), then hide what has to be hidden.
     */
    private static void scheduleRetry() {
        final long delay = RETRY_DELAYS_MS[Math.min(retryAttempt++, RETRY_DELAYS_MS.length - 1)];
        final int generation = ++retryGeneration;
        Utilities.globalQueue.postRunnable(() -> retryLoad(generation), delay);
    }

    /** LaunchActivity.onUserInteraction (the app is used again): a busy Keystore is retried at once. */
    @MainThread
    static void onUserActivity() {
        if (loadStatus != LOAD_UNAVAILABLE) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (now - lastRetryAt < TOUCH_RETRY_MIN_GAP_MS) {
            return;
        }
        lastRetryAt = now;
        final int generation = ++retryGeneration;
        Utilities.globalQueue.postRunnable(() -> retryLoad(generation));
    }

    /**
     * Any thread: the vault hides chats (at least one was hidden when the list was last saved) but the list cannot be
     * read right now: every notification is redacted, since any of them could belong to a hidden chat.
     */
    static boolean isFailingClosed() {
        return active && loadStatus == LOAD_UNAVAILABLE && prefs().getInt(KEY_COUNT, 0) > 0;
    }

    /** Worker thread: one more try to read the membership; the result is applied on the main thread. */
    private static void retryLoad(int generation) {
        final String blob = prefs().getString(KEY_STATE, null);
        final ZgVaultCrypto.Result result = blob != null ? ZgVaultCrypto.open(blob) : new ZgVaultCrypto.Result(ZgVaultCrypto.Status.OK, null);
        AndroidUtilities.runOnUIThread(() -> {
            if (loadStatus != LOAD_UNAVAILABLE) {
                return;
            }
            final boolean busy = blob != null && result.status != ZgVaultCrypto.Status.OK && result.status != ZgVaultCrypto.Status.LOST;
            if (busy) {
                if (generation == retryGeneration) {
                    // The newest try owns the next one (an older one that fails just ends).
                    scheduleRetry();
                }
                return;
            }
            if (blob == null) {
                state = ZgVaultState.EMPTY;
                loadStatus = LOAD_OK;
            } else if (result.status == ZgVaultCrypto.Status.OK) {
                if (!applyDecrypted(result.text)) {
                    markLost();
                }
            } else {
                markLost();
            }
            ZgVaultEffects.onMembershipLoadedLate();
            notifyChanged();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Switches (any thread)
    // ---------------------------------------------------------------------------------------------------------

    public static boolean isPorted() {
        return ZgFeatures.SECRET_VAULT.isPorted();
    }

    /** The settings switch is on (whether or not the vault can be used right now). */
    public static boolean isEnabled() {
        return isPorted() && prefs().getBoolean(KEY_ENABLED, false);
    }

    public static boolean hasCredential() {
        return prefs().getString(KEY_CRED, null) != null;
    }

    /** On and a PIN exists: hidden chats are hidden. */
    public static boolean isActive() {
        return active;
    }

    /** The credential can never be checked on this device again (Keystore key lost, damaged record). */
    public static boolean isCredentialUnverifiable() {
        return ZgLockCrypto.isUnverifiableQuick(prefs().getString(KEY_CRED, null));
    }

    public static boolean isMembershipLost() {
        return (prefs().getInt(KEY_RECOVERY, 0) & RECOVERY_MEMBERSHIP_LOST) != 0;
    }

    /** On, but the PIN or the list of hidden chats cannot be used on this device any more: recovery first. */
    public static boolean needsRecovery() {
        return isEnabled() && (isMembershipLost() || hasCredential() && isCredentialUnverifiable());
    }

    /** The Keystore refused at start-up and is being retried: show "try again in a moment". */
    public static boolean isTemporarilyUnavailable() {
        return loadStatus == LOAD_UNAVAILABLE;
    }

    /** Hiding, unhiding and revealing are possible right now. */
    public static boolean isOperable() {
        return active && loadStatus == LOAD_OK && !needsRecovery();
    }

    /** Settings: the switch reads "on" (and the PIN rows show) only while the vault really works. */
    public static boolean isUsable() {
        return active && !needsRecovery();
    }

    private static void refreshActive() {
        final SharedPreferences preferences = prefs();
        active = isPorted() && preferences.getBoolean(KEY_ENABLED, false) && preferences.getString(KEY_CRED, null) != null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Membership (reads: any thread)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Any thread, cheap (a volatile read when the vault is off, two binary searches otherwise): whether
     * {@code dialogId} is hidden in the vault of account slot {@code account}. Never true for the account's own
     * Saved Messages. Never throws.
     */
    public static boolean isHidden(int account, long dialogId) {
        if (!active || dialogId == 0) {
            return false;
        }
        final ZgVaultState current = state;
        if (current.isEmpty() || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        try {
            final long uid = UserConfig.getInstance(account).getClientUserId();
            return uid != 0 && dialogId != uid && current.isMember(uid, dialogId);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Whether any account hides anything (the hooks' second fast exit). */
    static boolean hasMembers() {
        return active && !state.isEmpty();
    }

    static ZgVaultState snapshot() {
        return state;
    }

    /** The hidden dialog ids of {@code account} (sorted copy). */
    public static long[] getMembers(int account) {
        if (!active) {
            return new long[0];
        }
        return state.members(uidOf(account));
    }

    public static int getMemberCount(int account) {
        return active ? state.count(uidOf(account)) : 0;
    }

    static boolean wasUnpinned(int account, long dialogId) {
        return state.wasUnpinned(uidOf(account), dialogId);
    }

    /** Main thread: the rows of {@code account}'s hidden chats in chat-list order (do not modify). */
    @MainThread
    public static ArrayList<TLRPC.Dialog> getDialogs(int account) {
        final ArrayList<TLRPC.Dialog> list = account >= 0 && account < dialogs.length ? dialogs[account] : null;
        return list != null ? list : new ArrayList<>();
    }

    @MainThread
    static void publishDialogs(int account, @Nullable ArrayList<TLRPC.Dialog> list) {
        if (account >= 0 && account < dialogs.length) {
            dialogs[account] = list != null && !list.isEmpty() ? list : null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Membership (writes: main thread)
    // ---------------------------------------------------------------------------------------------------------

    /** Hides {@code dialogIds} in {@code account} and records {@code unpinnedIds}. False: nothing changed. */
    @MainThread
    static boolean add(int account, long[] dialogIds, long[] unpinnedIds) {
        if (!isOperable()) {
            return false;
        }
        final long uid = uidOf(account);
        if (uid == 0) {
            return false;
        }
        return writeState(state.withMembers(uid, withoutSelf(dialogIds, uid), unpinnedIds));
    }

    /** Unhides {@code dialogIds} (their records go too). Allowed while the vault is operable. */
    @MainThread
    static boolean remove(int account, long[] dialogIds) {
        if (!isOperable()) {
            return false;
        }
        final long uid = uidOf(account);
        return uid != 0 && writeState(state.withoutMembers(uid, dialogIds));
    }

    /**
     * A hidden basic group became a supergroup: {@code targetId} inherits the membership. Never Pro-gated (nothing new
     * is hidden). Allowed whenever the membership is readable.
     */
    @MainThread
    static boolean carryOver(long uid, long sourceId, long targetId) {
        if (!active || loadStatus != LOAD_OK || uid == 0 || targetId == 0) {
            return false;
        }
        final ZgVaultState current = state;
        if (!current.isMember(uid, sourceId) || current.isMember(uid, targetId)) {
            return false;
        }
        return writeState(current.withMembers(uid, new long[]{targetId}, new long[0]));
    }

    /** Logout of one account: forgets its hidden chats (the other accounts keep theirs). */
    @MainThread
    static void forgetAccount(long uid) {
        if (uid == 0 || loadStatus != LOAD_OK) {
            return;
        }
        final ZgVaultState current = state;
        if (current.count(uid) > 0) {
            writeState(current.withoutAccount(uid));
        }
    }

    private static boolean writeState(ZgVaultState next) {
        synchronized (lock) {
            if (loadStatus != LOAD_OK) {
                return false;
            }
            if (next == state) {
                return true;
            }
            String blob = null;
            if (!next.isEmpty()) {
                blob = ZgVaultCrypto.seal(next.encode());
                if (blob == null) {
                    return false;
                }
            }
            final SharedPreferences.Editor editor = prefs().edit();
            if (blob == null) {
                editor.remove(KEY_STATE);
            } else {
                editor.putString(KEY_STATE, blob);
            }
            editor.putInt(KEY_COUNT, next.count());
            if (!editor.commit()) {
                return false;
            }
            state = next;
        }
        notifyChanged();
        return true;
    }

    private static long[] withoutSelf(long[] ids, long uid) {
        int count = 0;
        final long[] out = new long[ids.length];
        for (long id : ids) {
            if (id != 0 && id != uid) {
                out[count++] = id;
            }
        }
        return count == out.length ? out : Arrays.copyOf(out, count);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Credential
    // ---------------------------------------------------------------------------------------------------------

    /** {@link ZgLockCode#TYPE_PIN} or {@link ZgLockCode#TYPE_TEXT}. */
    public static int getCredentialType() {
        return ZgLockCode.metaType(prefs().getString(KEY_CRED_META, null));
    }

    public static boolean isBiometricEnabled() {
        return ZgLockCode.metaBiometric(prefs().getString(KEY_CRED_META, null));
    }

    /** Worker thread. MISMATCH when there is no PIN; UNVERIFIABLE when it can never be checked here again. */
    @WorkerThread
    static ZgLockCrypto.Verdict verifyCredential(String code) {
        final String record = prefs().getString(KEY_CRED, null);
        if (record == null) {
            return ZgLockCrypto.Verdict.MISMATCH;
        }
        return ZgLockCrypto.verify(record, code);
    }

    /** "Forgot PIN" rung 2: the Chat Lock master that already existed when the vault PIN was set (iOS). */
    static boolean isMasterRungAvailable() {
        final String saved = prefs().getString(KEY_MASTER_GEN, null);
        final String current = ZgMasterPin.getGeneration();
        return saved != null && saved.equals(current) && !ZgMasterPin.isUnverifiable();
    }

    static final int SAVE_OK = 0;
    static final int SAVE_FAILED = 1;
    /** The Keystore cannot hold the vault's key: the vault must not turn on. */
    static final int SAVE_NO_KEYSTORE = 2;

    /**
     * Worker thread. Stores a new PIN and turns the vault on. {@code clearMembers}: recovery after the membership was
     * lost (the unreadable blob is dropped). The biometric slot is created whenever strong biometrics exist (it is
     * also the "Forgot PIN" baseline); biometric unlock is on only if {@code biometric}.
     */
    @WorkerThread
    static int saveCredential(String code, int type, boolean biometric, boolean clearMembers) {
        final String record = ZgLockCrypto.makeRecord(code);
        if (record == null) {
            return SAVE_FAILED;
        }
        if (!ZgVaultCrypto.ensureKey()) {
            return SAVE_NO_KEYSTORE;
        }
        final boolean slot = ZgLockBiometrics.isStrongAvailable() && ZgLockBiometrics.createSlot(BIO_SLOT);
        if (!slot) {
            ZgLockBiometrics.deleteSlot(BIO_SLOT);
        }
        final String generation = ZgMasterPin.getGeneration();
        final boolean ok;
        synchronized (lock) {
            final SharedPreferences preferences = prefs();
            final SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_CRED, record)
                .putString(KEY_CRED_META, ZgLockCode.encodeMeta(type, biometric && slot));
            if (generation != null) {
                editor.putString(KEY_MASTER_GEN, generation);
            } else {
                editor.remove(KEY_MASTER_GEN);
            }
            final boolean dropMembers = clearMembers || loadStatus == LOAD_LOST;
            if (dropMembers) {
                editor.remove(KEY_STATE).putInt(KEY_COUNT, 0).putInt(KEY_RECOVERY, 0);
            } else {
                editor.putInt(KEY_RECOVERY, preferences.getInt(KEY_RECOVERY, 0) & ~RECOVERY_MEMBERSHIP_LOST);
            }
            ok = editor.commit();
            if (ok && dropMembers) {
                state = ZgVaultState.EMPTY;
                loadStatus = LOAD_OK;
            }
        }
        if (!ok) {
            return SAVE_FAILED;
        }
        ZgLockThrottle.reset(ZgLockThrottle.Scope.VAULT);
        ZgLockThrottle.reset(ZgLockThrottle.Scope.VAULT_SEARCH);
        AndroidUtilities.runOnUIThread(() -> {
            refreshActive();
            notifyChanged();
        });
        return SAVE_OK;
    }

    /** Turns the vault back on with the PIN that is still stored (proven by the caller). */
    @MainThread
    static boolean reEnable() {
        if (!hasCredential()) {
            return false;
        }
        if (!prefs().edit().putBoolean(KEY_ENABLED, true).commit()) {
            return false;
        }
        refreshActive();
        notifyChanged();
        return true;
    }

    static boolean setBiometricEnabled(boolean enabled) {
        final SharedPreferences preferences = prefs();
        if (preferences.getString(KEY_CRED, null) == null) {
            return false;
        }
        return preferences.edit().putString(KEY_CRED_META, ZgLockCode.encodeMeta(getCredentialType(), enabled)).commit();
    }

    /**
     * Turns the vault off: switch, PIN, members and records go in one commit (all hidden chats show again), then the
     * keys, the biometric slot and the throttles. Returns the members that were hidden per user id (for the caller's
     * pin restore) or null when the commit failed (then nothing changed).
     */
    @MainThread
    @Nullable
    static ZgVaultState clearAll() {
        final ZgVaultState previous;
        synchronized (lock) {
            previous = loadStatus == LOAD_OK ? state : ZgVaultState.EMPTY;
            if (!prefs().edit().clear().commit()) {
                return null;
            }
            state = ZgVaultState.EMPTY;
            loadStatus = LOAD_OK;
            active = false;
        }
        ZgVaultCrypto.deleteKey();
        ZgLockBiometrics.deleteSlot(BIO_SLOT);
        ZgLockThrottle.reset(ZgLockThrottle.Scope.VAULT);
        ZgLockThrottle.reset(ZgLockThrottle.Scope.VAULT_SEARCH);
        notifyChanged();
        return previous;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Listeners
    // ---------------------------------------------------------------------------------------------------------

    public static void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static void notifyChanged() {
        if (!(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())) {
            AndroidUtilities.runOnUIThread(ZgVault::notifyChanged);
            return;
        }
        for (Listener listener : listeners) {
            try {
                listener.onVaultChanged();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    static long uidOf(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return 0;
        }
        return UserConfig.getInstance(account).getClientUserId();
    }

    /** Account slot whose user id is {@code uid}, or -1. */
    static int accountOf(long uid) {
        if (uid == 0) {
            return -1;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated() && config.getClientUserId() == uid) {
                return a;
            }
        }
        return -1;
    }

    static ArrayList<Integer> activatedAccounts() {
        final ArrayList<Integer> list = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                list.add(a);
            }
        }
        return list;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
