/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.WorkerThread;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.zenlygram.lock.ZgLockBiometrics;
import org.telegram.zenlygram.lock.ZgLockCode;
import org.telegram.zenlygram.lock.ZgLockCrypto;
import org.telegram.zenlygram.lock.ZgLockThrottle;
import org.telegram.zenlygram.lock.ZgMasterPin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which chats are locked, and their pincodes (iOS ChatPincodeManager per-chat part).
 *
 * <p>The locked set is device-wide (all accounts, iOS D6): Telegram peer ids are global, so locking a peer locks it in
 * every account, except where it is that account's own Saved Messages ({@link ZgChatLockSession#isProtected}). It is
 * read from any thread (DialogCell, notificationsQueue, FCM) through a volatile sorted array: O(log n), no locks, no
 * allocation. Chat Lock is "on" while a master pincode exists ({@link ZgMasterPin}).
 *
 * <p>Storage: SharedPreferences {@value #PREFS} (app-private, not backed up, so uninstalling really removes Chat Lock):
 * {@code locked} = sorted comma-separated dialog ids, {@code rec_<dialogId>} = a {@link ZgLockCrypto} record (never the
 * code), {@code meta_<dialogId>} = {@link ZgLockCode#encodeMeta}. Every write is one commit(); a failed write is
 * reported and changes nothing in memory. Biometric slots are {@code chat_<dialogId>}. Nothing secret is logged.
 *
 * <p>Changes are announced on the main thread to {@link Listener}s and as NotificationCenter dialogsNeedReload +
 * updateInterfaces(0) on every activated account, so chat-list rows re-render.
 */
public final class ZgChatLockStore {

    public interface Listener {
        /** Main thread. {@code added} / {@code removed}: dialog ids whose lock state changed. */
        void onLocksChanged(long[] added, long[] removed);
    }

    private static final String PREFS = "zg_chatlock";
    private static final String KEY_VERSION = "v";
    private static final String KEY_LOCKED = "locked";
    private static final String PREFIX_RECORD = "rec_";
    private static final String PREFIX_META = "meta_";

    private static final Object lock = new Object();
    private static volatile long[] locked;
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private ZgChatLockStore() {
    }

    public static String bioSlot(long dialogId) {
        return "chat_" + dialogId;
    }

    /** Whether Chat Lock is on (a master pincode exists). */
    public static boolean isEnabled() {
        return ZgMasterPin.isSet();
    }

    /** Any thread, cheap. */
    public static boolean isLocked(long dialogId) {
        final long[] ids = snapshot();
        return ids.length != 0 && Arrays.binarySearch(ids, dialogId) >= 0;
    }

    /** A copy of the locked ids (sorted). */
    public static long[] getLockedIds() {
        return snapshot().clone();
    }

    public static int getLockedCount() {
        return snapshot().length;
    }

    public static int getType(long dialogId) {
        return ZgLockCode.metaType(prefs().getString(PREFIX_META + dialogId, null));
    }

    public static boolean isBiometricEnabled(long dialogId) {
        return ZgLockCode.metaBiometric(prefs().getString(PREFIX_META + dialogId, null));
    }

    /** Locks {@code dialogId} with {@code code} (or replaces its pincode). Hashes: worker thread. */
    @WorkerThread
    public static boolean setPincode(long dialogId, String code, int type, boolean biometric) {
        final String record = ZgLockCrypto.makeRecord(code);
        if (record == null) {
            return false;
        }
        final boolean added;
        synchronized (lock) {
            final long[] current = snapshot();
            final long[] next = withId(current, dialogId);
            final boolean ok = prefs().edit()
                .putInt(KEY_VERSION, 1)
                .putString(PREFIX_RECORD + dialogId, record)
                .putString(PREFIX_META + dialogId, ZgLockCode.encodeMeta(type, biometric))
                .putString(KEY_LOCKED, encode(next))
                .commit();
            if (!ok) {
                return false;
            }
            added = next != current;
            locked = next;
        }
        ZgLockThrottle.forgetChat(dialogId);
        if (added) {
            notifyChanged(new long[]{dialogId}, new long[0]);
        }
        return true;
    }

    /** Checks a chat's pincode. UNVERIFIABLE when the chat is locked but its record is missing or can't be checked. */
    @WorkerThread
    public static ZgLockCrypto.Verdict verify(long dialogId, String code) {
        final String record = prefs().getString(PREFIX_RECORD + dialogId, null);
        if (record == null) {
            return isLocked(dialogId) ? ZgLockCrypto.Verdict.UNVERIFIABLE : ZgLockCrypto.Verdict.MISMATCH;
        }
        return ZgLockCrypto.verify(record, code);
    }

    /** Unlocks a chat for good (its pincode, options, biometric slot and own throttle go). Messages are untouched. */
    public static boolean removePincode(long dialogId) {
        final boolean removed;
        synchronized (lock) {
            final long[] current = snapshot();
            final long[] next = withoutId(current, dialogId);
            final boolean ok = prefs().edit()
                .remove(PREFIX_RECORD + dialogId)
                .remove(PREFIX_META + dialogId)
                .putString(KEY_LOCKED, encode(next))
                .commit();
            if (!ok) {
                return false;
            }
            removed = next != current;
            locked = next;
        }
        ZgLockBiometrics.deleteSlot(bioSlot(dialogId));
        ZgLockThrottle.forgetChat(dialogId);
        if (removed) {
            notifyChanged(new long[0], new long[]{dialogId});
        }
        return true;
    }

    /**
     * Gives {@code target} the same lock as {@code source} (basic group upgraded to a supergroup). Never Pro-gated:
     * nothing new is being locked. Keeps {@code source} locked too.
     */
    public static boolean copyLock(long source, long target) {
        if (source == target || !isLocked(source) || isLocked(target)) {
            return false;
        }
        final SharedPreferences preferences = prefs();
        final String record = preferences.getString(PREFIX_RECORD + source, null);
        if (record == null) {
            return false;
        }
        final String meta = preferences.getString(PREFIX_META + source, null);
        synchronized (lock) {
            final long[] next = withId(snapshot(), target);
            final SharedPreferences.Editor editor = preferences.edit()
                .putString(PREFIX_RECORD + target, record)
                .putString(KEY_LOCKED, encode(next));
            if (meta != null) {
                // Biometric slots are per credential; the copy starts without one.
                editor.putString(PREFIX_META + target, ZgLockCode.encodeMeta(ZgLockCode.metaType(meta), false));
            }
            if (!editor.commit()) {
                return false;
            }
            locked = next;
        }
        notifyChanged(new long[]{target}, new long[0]);
        return true;
    }

    public static boolean setBiometricEnabled(long dialogId, boolean enabled) {
        final SharedPreferences preferences = prefs();
        if (preferences.getString(PREFIX_RECORD + dialogId, null) == null) {
            return false;
        }
        return preferences.edit().putString(PREFIX_META + dialogId, ZgLockCode.encodeMeta(getType(dialogId), enabled)).commit();
    }

    /**
     * Turns Chat Lock off: the master and every chat's lock, options, biometric slot and throttle are removed. The
     * Secret Vault (its PIN, members, throttle) is never touched. Messages are never deleted.
     */
    public static boolean disableChatLock() {
        final long[] previous;
        synchronized (lock) {
            previous = snapshot();
            final SharedPreferences preferences = prefs();
            final SharedPreferences.Editor editor = preferences.edit();
            final ArrayList<String> keys = new ArrayList<>();
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                final String key = entry.getKey();
                if (key.startsWith(PREFIX_RECORD) || key.startsWith(PREFIX_META)) {
                    keys.add(key);
                }
            }
            for (String key : keys) {
                editor.remove(key);
            }
            editor.putString(KEY_LOCKED, "");
            if (!editor.commit()) {
                return false;
            }
            locked = new long[0];
        }
        ZgMasterPin.remove();
        for (long dialogId : previous) {
            ZgLockBiometrics.deleteSlot(bioSlot(dialogId));
        }
        ZgLockThrottle.resetChatLockAll();
        org.telegram.messenger.zenlygram.customer.ZenlyStats.chatLockRemoved(UserConfig.selectedAccount); // ZENLYGRAM: statistics, chat_lock.lock_removed once when Chat Lock is turned off (recorded only with the data consent)
        if (previous.length > 0) {
            notifyChanged(new long[0], previous);
        } else {
            notifyChanged(new long[0], new long[0]);
        }
        return true;
    }

    /**
     * Recovery after the credentials became unverifiable (Keystore key lost): {@code code} becomes the new master and
     * the pincode of every chat that is still locked. Hashes: worker thread.
     */
    @WorkerThread
    public static boolean replaceAllWithNewCode(String code, int type, boolean biometric) {
        final String record = ZgLockCrypto.makeRecord(code);
        if (record == null) {
            return false;
        }
        // A fresh master (new generation: a vault PIN set with the lost master must not accept this one).
        ZgMasterPin.remove();
        if (!ZgMasterPin.set(code, type, biometric)) {
            return false;
        }
        synchronized (lock) {
            final long[] ids = snapshot();
            final SharedPreferences.Editor editor = prefs().edit();
            for (long dialogId : ids) {
                editor.putString(PREFIX_RECORD + dialogId, record);
                editor.putString(PREFIX_META + dialogId, ZgLockCode.encodeMeta(type, false));
            }
            if (!editor.commit()) {
                return false;
            }
        }
        for (long dialogId : snapshot()) {
            ZgLockBiometrics.deleteSlot(bioSlot(dialogId));
        }
        ZgLockThrottle.resetChatLockAll();
        notifyChanged(new long[0], new long[0]);
        return true;
    }

    /**
     * True when the master or a locked chat can never be checked on this device again (Keystore key lost, damaged or
     * missing record): the PIN screens must show the recovery page instead of a keypad. Cheap, no hashing.
     */
    public static boolean needsRecovery() {
        if (ZgMasterPin.isSet() && ZgMasterPin.isUnverifiable()) {
            return true;
        }
        final long[] ids = snapshot();
        if (ids.length == 0) {
            return false;
        }
        final SharedPreferences preferences = prefs();
        for (long dialogId : ids) {
            final String record = preferences.getString(PREFIX_RECORD + dialogId, null);
            if (record == null || ZgLockCrypto.isUnverifiableQuick(record)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this one chat's pincode can never be checked again. */
    public static boolean isUnverifiable(long dialogId) {
        if (!isLocked(dialogId)) {
            return false;
        }
        final String record = prefs().getString(PREFIX_RECORD + dialogId, null);
        return record == null || ZgLockCrypto.isUnverifiableQuick(record);
    }

    public static void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ---------------------------------------------------------------------------------------------------------

    private static long[] snapshot() {
        long[] ids = locked;
        if (ids == null) {
            synchronized (lock) {
                if (locked == null) {
                    locked = decode(prefs().getString(KEY_LOCKED, null));
                }
                ids = locked;
            }
        }
        return ids;
    }

    private static long[] withId(long[] ids, long id) {
        if (Arrays.binarySearch(ids, id) >= 0) {
            return ids;
        }
        final long[] next = Arrays.copyOf(ids, ids.length + 1);
        next[ids.length] = id;
        Arrays.sort(next);
        return next;
    }

    private static long[] withoutId(long[] ids, long id) {
        final int index = Arrays.binarySearch(ids, id);
        if (index < 0) {
            return ids;
        }
        final long[] next = new long[ids.length - 1];
        System.arraycopy(ids, 0, next, 0, index);
        System.arraycopy(ids, index + 1, next, index, ids.length - index - 1);
        return next;
    }

    private static String encode(long[] ids) {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(ids[i]);
        }
        return builder.toString();
    }

    private static long[] decode(String value) {
        if (value == null || value.isEmpty()) {
            return new long[0];
        }
        final String[] parts = value.split(",");
        long[] ids = new long[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                ids[count] = Long.parseLong(part.trim());
                count++;
            } catch (NumberFormatException ignore) {
                // A damaged entry is skipped; the rest of the set stays locked.
            }
        }
        if (count != ids.length) {
            ids = Arrays.copyOf(ids, count);
        }
        Arrays.sort(ids);
        return ids;
    }

    private static void notifyChanged(long[] added, long[] removed) {
        AndroidUtilities.runOnUIThread(() -> {
            // The session must exist from the first lock on: it relocks on background and adopts chats open right now.
            ZgChatLockSession.getInstance();
            for (Listener listener : listeners) {
                try {
                    listener.onLocksChanged(added, removed);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload);
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, 0);
                }
            }
        });
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
