/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.lock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;

/**
 * The Chat Lock master pincode (iOS ChatPincodeManager master account): its presence means Chat Lock is on, it is the
 * "Forgot pincode?" key of every locked chat, and the Secret Vault accepts it as the second rung of its own "Forgot
 * PIN" ladder, but only the very master that existed when the vault PIN was set ({@link #getGeneration()}).
 *
 * <p>Storage: SharedPreferences {@value #PREFS} (app-private, not backed up: Telegram's BackupAgent only backs up
 * saved_tokens), keys {@code master_rec} (a {@link ZgLockCrypto} record, never the code), {@code master_meta}
 * ({@link ZgLockCode#encodeMeta}) and {@code master_gen} (random marker created with a NEW master, deleted with it).
 * The biometric slot is {@link #BIO_SLOT}; it is also the enrollment baseline of the device-owner reset, so it is
 * created whenever strong biometrics exist at set time, whether or not biometric unlock is on.
 *
 * <p>Reads are cheap and thread-safe. {@link #set} and {@link #verify} hash: call them off the main thread. Writes
 * use commit() and report failure, so a failed write is never shown as success.
 */
public final class ZgMasterPin {

    public static final String BIO_SLOT = "master";

    private static final String PREFS = "zg_lock";
    private static final String KEY_RECORD = "master_rec";
    private static final String KEY_META = "master_meta";
    private static final String KEY_GENERATION = "master_gen";

    private ZgMasterPin() {
    }

    public static boolean isSet() {
        return prefs().getString(KEY_RECORD, null) != null;
    }

    /** {@link ZgLockCode#TYPE_PIN} or {@link ZgLockCode#TYPE_TEXT}. */
    public static int getType() {
        return ZgLockCode.metaType(prefs().getString(KEY_META, null));
    }

    public static boolean isBiometricEnabled() {
        return ZgLockCode.metaBiometric(prefs().getString(KEY_META, null));
    }

    /** True when a master exists but can never be checked on this device (Keystore key lost): recovery needed. */
    public static boolean isUnverifiable() {
        return ZgLockCrypto.isUnverifiableQuick(prefs().getString(KEY_RECORD, null));
    }

    /**
     * Stores {@code code} as the master. A master created where there was none gets a new generation marker; re-writing
     * an existing master (e.g. "Use pincode only" after an enrollment change) keeps it.
     */
    @WorkerThread
    public static boolean set(String code, int type, boolean biometric) {
        final String record = ZgLockCrypto.makeRecord(code);
        if (record == null) {
            return false;
        }
        final SharedPreferences preferences = prefs();
        final SharedPreferences.Editor editor = preferences.edit()
            .putString(KEY_RECORD, record)
            .putString(KEY_META, ZgLockCode.encodeMeta(type, biometric));
        if (preferences.getString(KEY_GENERATION, null) == null || preferences.getString(KEY_RECORD, null) == null) {
            editor.putString(KEY_GENERATION, newGeneration());
        }
        return editor.commit();
    }

    /** MISMATCH when no master exists. */
    @WorkerThread
    public static ZgLockCrypto.Verdict verify(String code) {
        final String record = prefs().getString(KEY_RECORD, null);
        if (record == null) {
            return ZgLockCrypto.Verdict.MISMATCH;
        }
        return ZgLockCrypto.verify(record, code);
    }

    public static boolean setBiometricEnabled(boolean enabled) {
        final SharedPreferences preferences = prefs();
        if (preferences.getString(KEY_RECORD, null) == null) {
            return false;
        }
        return preferences.edit().putString(KEY_META, ZgLockCode.encodeMeta(getType(), enabled)).commit();
    }

    /** Deletes the master, its options, its generation marker and its biometric slot. Locked chats are not touched. */
    public static boolean remove() {
        ZgLockBiometrics.deleteSlot(BIO_SLOT);
        return prefs().edit().remove(KEY_RECORD).remove(KEY_META).remove(KEY_GENERATION).commit();
    }

    /**
     * Marker of the current master, or null without one. The vault saves it with its PIN and accepts the master as
     * recovery only while {@code saved.equals(getGeneration())}.
     */
    @Nullable
    public static String getGeneration() {
        final SharedPreferences preferences = prefs();
        return preferences.getString(KEY_RECORD, null) != null ? preferences.getString(KEY_GENERATION, null) : null;
    }

    private static String newGeneration() {
        final byte[] bytes = new byte[16];
        Utilities.random.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
