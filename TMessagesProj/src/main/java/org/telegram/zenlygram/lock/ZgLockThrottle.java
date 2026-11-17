/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.lock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.Map;

/**
 * Brute-force throttle of every Zenlygram lock, a port of iOS ChatLockThrottle. Persisted, so closing and reopening a
 * PIN screen, killing the app or rebooting never hands back a fresh budget. Any thread.
 *
 * <p>Schedule ({@link Scope#chat}, {@link Scope#MASTER}, {@link Scope#VAULT}): failures 1-4 cost nothing, the 5th
 * starts a 30 s window, then 60 / 120 / 300 / 900 s. While a window runs, entries are refused WITHOUT being checked.
 * The count decays after 24 h without a failure (1 h for the vault scopes). {@link Scope#VAULT_SEARCH} (secret code
 * typed into the chat search): 9 free failures, then a flat 60 s, 1 h decay.
 *
 * <p>Shared budget: chat and master failures also count in one global counter, and every chat scope is gated by it,
 * so a PIN reused for several chats gets one budget, and locking a spare chat with a known PIN buys no guesses. A
 * correct chat entry refunds only that chat's own live failures; a correct master entry clears the master, every
 * chat and the global counter ({@link #reset}). The vault scopes are independent and never touched by Chat Lock.
 *
 * <p>Time is {@link SystemClock#elapsedRealtime()} (counts deep sleep, cannot be set by the user, restarts at boot):
 * changing the date never shortens a lockout; after a reboot the last failure is rebased to boot time, so a reboot can
 * at worst restart a pending lockout.
 *
 * <p>Storage: SharedPreferences {@value #PREFS}, keys {@code t.global}, {@code t.master}, {@code t.chat.<dialogId>},
 * {@code t.vault}, {@code t.vaultSearch}; value {@code count:delayMs:anchorMs}. Written with commit().
 */
public final class ZgLockThrottle {

    /** Which credential a failure belongs to. */
    public static final class Scope {
        static final int KIND_CHAT = 0;
        static final int KIND_MASTER = 1;
        static final int KIND_VAULT = 2;
        static final int KIND_VAULT_SEARCH = 3;

        /** The Chat Lock master pincode (settings, "Forgot pincode?", and the vault's master rung). */
        public static final Scope MASTER = new Scope(KIND_MASTER, "t.master");
        /** Every Secret Vault PIN screen. */
        public static final Scope VAULT = new Scope(KIND_VAULT, "t.vault");
        /** The Secret Vault code typed into the chat-list search. */
        public static final Scope VAULT_SEARCH = new Scope(KIND_VAULT_SEARCH, "t.vaultSearch");

        final int kind;
        final String key;

        private Scope(int kind, String key) {
            this.kind = kind;
            this.key = key;
        }

        /** One chat's own pincode (unlock and remove screens). */
        public static Scope chat(long dialogId) {
            return new Scope(KIND_CHAT, CHAT_PREFIX + dialogId);
        }
    }

    private static final String PREFS = "zg_lock_throttle";
    private static final String GLOBAL = "t.global";
    private static final String CHAT_PREFIX = "t.chat.";
    private static final long DECAY_MS = 24L * 60 * 60 * 1000;
    private static final long VAULT_DECAY_MS = 60L * 60 * 1000;
    private static final int VAULT_SEARCH_FREE_ATTEMPTS = 10;
    private static final long VAULT_SEARCH_LOCKOUT_MS = 60_000L;

    private static final Object lock = new Object();

    private ZgLockThrottle() {
    }

    /** Seconds (rounded up) until an entry in {@code scope} will be checked again; 0 when it may be checked now. */
    public static int remainingSeconds(Scope scope) {
        synchronized (lock) {
            final long now = SystemClock.elapsedRealtime();
            long value = pendingMs(scope.key, now);
            if (scope.kind == Scope.KIND_CHAT) {
                value = Math.max(value, pendingMs(GLOBAL, now));
            }
            return value <= 0 ? 0 : (int) ((value + 999) / 1000);
        }
    }

    /** Records one wrong entry and starts a window when the schedule calls for one. Setting a new code is never throttled. */
    public static void registerFailure(Scope scope) {
        synchronized (lock) {
            final long now = SystemClock.elapsedRealtime();
            final SharedPreferences.Editor editor = prefs().edit();
            switch (scope.kind) {
                case Scope.KIND_CHAT:
                case Scope.KIND_MASTER:
                    recordFailure(editor, scope.key, now, DECAY_MS, false);
                    recordFailure(editor, GLOBAL, now, DECAY_MS, false);
                    break;
                case Scope.KIND_VAULT:
                    recordFailure(editor, scope.key, now, VAULT_DECAY_MS, false);
                    break;
                case Scope.KIND_VAULT_SEARCH:
                default:
                    recordFailure(editor, scope.key, now, VAULT_DECAY_MS, true);
                    break;
            }
            editor.commit();
        }
    }

    /**
     * Counts an entry as a failure BEFORE it is checked (the check takes about a quarter of a second): if the process
     * dies meanwhile (force-stop, swipe from Recents, crash), the guess still counts, so scripting "type, kill, reopen"
     * never escapes the lockout windows. The caller then confirms: {@link #reset} on a correct entry,
     * {@link #cancelAttempt} when the entry could not be checked (not a wrong entry), nothing on a wrong one. Returns a
     * token for {@link #cancelAttempt}.
     */
    public static Object beginAttempt(Scope scope) {
        synchronized (lock) {
            final SharedPreferences preferences = prefs();
            final String[] keys = scope.kind == Scope.KIND_CHAT || scope.kind == Scope.KIND_MASTER
                ? new String[]{scope.key, GLOBAL} : new String[]{scope.key};
            final String[] before = new String[keys.length];
            for (int i = 0; i < keys.length; i++) {
                before[i] = preferences.getString(keys[i], null);
            }
            registerFailure(scope);
            final String[] after = new String[keys.length];
            for (int i = 0; i < keys.length; i++) {
                after[i] = preferences.getString(keys[i], null);
            }
            return new Attempt(keys, before, after);
        }
    }

    /** The entry of {@link #beginAttempt} could not be checked (Keystore busy, record unverifiable): it does not count. */
    public static void cancelAttempt(@Nullable Object token) {
        if (!(token instanceof Attempt)) {
            return;
        }
        final Attempt attempt = (Attempt) token;
        synchronized (lock) {
            final SharedPreferences preferences = prefs();
            final SharedPreferences.Editor editor = preferences.edit();
            boolean changed = false;
            for (int i = 0; i < attempt.keys.length; i++) {
                // Only an entry that nothing else touched since is rolled back.
                if (!TextUtils.equals(preferences.getString(attempt.keys[i], null), attempt.after[i])) {
                    continue;
                }
                if (attempt.before[i] == null) {
                    editor.remove(attempt.keys[i]);
                } else {
                    editor.putString(attempt.keys[i], attempt.before[i]);
                }
                changed = true;
            }
            if (changed) {
                editor.commit();
            }
        }
    }

    private static final class Attempt {
        final String[] keys;
        final String[] before;
        final String[] after;

        Attempt(String[] keys, String[] before, String[] after) {
            this.keys = keys;
            this.before = before;
            this.after = after;
        }
    }

    /**
     * Clears {@code scope} after a correct entry (PIN or biometrics) or a recovery. A chat scope also withdraws only its
     * own still-counted failures from the shared budget; the master clears the master, every chat and the shared
     * budget ({@link #resetChatLockAll()}); a vault scope clears only itself.
     */
    public static void reset(Scope scope) {
        synchronized (lock) {
            switch (scope.kind) {
                case Scope.KIND_CHAT: {
                    final long now = SystemClock.elapsedRealtime();
                    final SharedPreferences.Editor editor = prefs().edit();
                    int refund = 0;
                    final Record own = load(editor, scope.key, now);
                    if (own != null && now - own.anchor <= DECAY_MS) {
                        refund = own.count;
                    }
                    editor.remove(scope.key);
                    if (refund > 0) {
                        final Record global = load(editor, GLOBAL, now);
                        if (global != null) {
                            final int count = Math.max(0, global.count - refund);
                            if (count == 0) {
                                editor.remove(GLOBAL);
                            } else {
                                // Keep the anchor: the remaining window is never shorter than the remaining failures earn.
                                editor.putString(GLOBAL, new Record(count, delayFor(count, false), global.anchor).encode());
                            }
                        }
                    }
                    editor.commit();
                    break;
                }
                case Scope.KIND_MASTER:
                    resetChatLockAll();
                    break;
                default:
                    prefs().edit().remove(scope.key).commit();
                    break;
            }
        }
    }

    /**
     * Clears the master, every chat scope and the shared budget (a correct master entry, the device-owner "Reset Chat
     * Lock", turning Chat Lock off). Never touches the vault scopes.
     */
    public static void resetChatLockAll() {
        synchronized (lock) {
            final SharedPreferences preferences = prefs();
            final SharedPreferences.Editor editor = preferences.edit();
            editor.remove(Scope.MASTER.key);
            editor.remove(GLOBAL);
            final ArrayList<String> keys = new ArrayList<>();
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (entry.getKey().startsWith(CHAT_PREFIX)) {
                    keys.add(entry.getKey());
                }
            }
            for (String key : keys) {
                editor.remove(key);
            }
            editor.commit();
        }
    }

    /** Removes one chat's own record without refunding anything (its lock was removed). */
    public static void forgetChat(long dialogId) {
        synchronized (lock) {
            prefs().edit().remove(CHAT_PREFIX + dialogId).commit();
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private static final class Record {
        final int count;
        final long delay;
        final long anchor;

        Record(int count, long delay, long anchor) {
            this.count = count;
            this.delay = delay;
            this.anchor = anchor;
        }

        String encode() {
            return count + ":" + delay + ":" + anchor;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Reads a record so that {@code now - anchor} is a lower bound of the time since its last failure: an anchor in
     * the future can only mean a reboot, so it is rebased to boot time (0) and written back (through {@code editor},
     * or at once when there is none). A record without a usable anchor restarts its window now.
     */
    @Nullable
    private static Record load(@Nullable SharedPreferences.Editor editor, String key, long now) {
        final String value = prefs().getString(key, null);
        if (value == null) {
            return null;
        }
        int count = 0;
        long delay = 0;
        long anchor = -1;
        try {
            final String[] parts = value.split(":");
            count = Integer.parseInt(parts[0]);
            delay = Long.parseLong(parts[1]);
            anchor = parts.length > 2 ? Long.parseLong(parts[2]) : -1;
        } catch (Throwable ignore) {
        }
        Record record;
        if (anchor < 0) {
            record = new Record(Math.max(count, 0), Math.max(delay, 0), now);
            store(editor, key, record);
        } else if (anchor > now) {
            record = new Record(Math.max(count, 0), Math.max(delay, 0), 0);
            store(editor, key, record);
        } else {
            record = new Record(Math.max(count, 0), Math.max(delay, 0), anchor);
        }
        return record;
    }

    private static void store(@Nullable SharedPreferences.Editor editor, String key, Record record) {
        if (editor != null) {
            editor.putString(key, record.encode());
        } else {
            prefs().edit().putString(key, record.encode()).commit();
        }
    }

    private static long pendingMs(String key, long now) {
        final Record record = load(null, key, now);
        if (record == null || record.delay <= 0) {
            return 0;
        }
        return record.delay - (now - record.anchor);
    }

    private static void recordFailure(SharedPreferences.Editor editor, String key, long now, long decay, boolean searchProfile) {
        int count = 0;
        final Record record = load(editor, key, now);
        if (record != null && now - record.anchor <= decay) {
            count = record.count;
        }
        count++;
        editor.putString(key, new Record(count, delayFor(count, searchProfile), now).encode());
    }

    private static long delayFor(int count, boolean searchProfile) {
        if (searchProfile) {
            return count < VAULT_SEARCH_FREE_ATTEMPTS ? 0 : VAULT_SEARCH_LOCKOUT_MS;
        }
        if (count < 5) {
            return 0;
        }
        switch (count) {
            case 5:
                return 30_000L;
            case 6:
                return 60_000L;
            case 7:
                return 120_000L;
            case 8:
                return 300_000L;
            default:
                return 900_000L;
        }
    }
}
