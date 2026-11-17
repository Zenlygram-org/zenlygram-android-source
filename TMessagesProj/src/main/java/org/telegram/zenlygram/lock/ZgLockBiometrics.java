/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.lock;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.security.Key;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Biometric unlock and device-owner proof for every Zenlygram lock (androidx.biometric 1.1.0, already a Telegram
 * dependency). Only Class 3 biometrics (BIOMETRIC_STRONG) can unlock a credential.
 *
 * <p>Every credential that allows biometrics owns a <em>slot</em>: an AndroidKeyStore AES key
 * {@code zg_lock_bio_v1_<slot>} that needs a strong biometric for every use and is invalidated by Android when a
 * fingerprint or face is enrolled or removed. That makes the slot the enrollment baseline (iOS LAContext domain state):
 * <ul>
 *   <li>{@link Enrollment#UNCHANGED}: the key works; a successful prompt with it proves the enrolled owner.</li>
 *   <li>{@link Enrollment#CHANGED}: the key was invalidated (KeyPermanentlyInvalidatedException, also after the screen
 *       lock was removed): no biometric prompt; ask for the code, then let the user choose (re-create the slot or
 *       turn biometrics off).</li>
 *   <li>{@link Enrollment#MISSING}: no key for the slot (never created, or deleted by the system).</li>
 *   <li>{@link Enrollment#UNAVAILABLE}: no strong biometric is usable on this device right now.</li>
 * </ul>
 * A prompt result maps user cancel, too many failed matches and a hardware lockout to {@link Result#CANCELLED}, never
 * to a wrong entry. {@link #isAuthenticating()} is true while a prompt is up. A prompt never stops the activity, except
 * the device screen-lock screen of {@link #authenticateDeviceOwner} on Android 10 and below
 * ({@link #isDeviceCredentialScreenPossible()}), so only that "background" gets a grace ({@link ZgLockForeground}).
 * A relock while a prompt is up cancels it, and a result that arrives after a relock is never SUCCESS.
 *
 * <p>Prompts need a FragmentActivity whose state is not saved (LaunchActivity and BubbleActivity are); otherwise the
 * callback answers {@link Result#UNAVAILABLE} at once.
 */
public final class ZgLockBiometrics {

    public enum Enrollment {
        UNCHANGED,
        CHANGED,
        MISSING,
        UNAVAILABLE
    }

    public enum Result {
        SUCCESS,
        CANCELLED,
        ENROLLMENT_CHANGED,
        UNAVAILABLE
    }

    public interface Callback {
        void onResult(Result result);
    }

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String SLOT_PREFIX = "zg_lock_bio_v1_";
    private static final String TRANSFORMATION = KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_CBC + "/" + KeyProperties.ENCRYPTION_PADDING_PKCS7;
    /** A forgotten flag never blocks relocking for longer than this. */
    private static final long AUTHENTICATING_TIMEOUT_MS = 180_000L;

    private static boolean authenticating;
    private static long authenticatingSince;
    /** The running prompt asks for the device screen lock and may show it as an activity (Android 10 and below). */
    private static boolean deviceCredentialPrompt;
    @Nullable
    private static BiometricPrompt runningPrompt;
    /** Ends the running prompt's callback as CANCELLED (androidx may not report a cancel it was asked for). */
    @Nullable
    private static Runnable runningCancel;

    private ZgLockBiometrics() {
    }

    /** Whether a Class 3 biometric is enrolled and usable now. */
    public static boolean isStrongAvailable() {
        try {
            return BiometricManager.from(ApplicationLoader.applicationContext).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Whether the device has a secure screen lock, i.e. whether {@link #authenticateDeviceOwner} can prove anything. */
    public static boolean canProveDeviceOwner() {
        try {
            final KeyguardManager keyguardManager = (KeyguardManager) ApplicationLoader.applicationContext.getSystemService(Context.KEYGUARD_SERVICE);
            return keyguardManager != null && keyguardManager.isDeviceSecure();
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Slots
    // ---------------------------------------------------------------------------------------------------------

    /**
     * (Re)creates the slot key: the current enrollment becomes its baseline. Returns false when strong biometrics are
     * unavailable or the Keystore refuses (then biometrics must stay off for that credential). Takes a few ms to a few
     * hundred ms; may run on any thread.
     */
    public static boolean createSlot(String slot) {
        if (!isStrongAvailable()) {
            return false;
        }
        try {
            final KeyStore keyStore = loadKeyStore();
            if (keyStore.containsAlias(alias(slot))) {
                keyStore.deleteEntry(alias(slot));
            }
            final KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias(slot), KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(true);
            if (Build.VERSION.SDK_INT >= 24) {
                builder.setInvalidatedByBiometricEnrollment(true);
            }
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            }
            final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(builder.build());
            generator.generateKey();
            return initCipher(slot) != null;
        } catch (Throwable e) {
            FileLog.e("ZgLockBiometrics: slot not created: " + e.getClass().getSimpleName());
            deleteSlot(slot);
            return false;
        }
    }

    public static void deleteSlot(String slot) {
        try {
            final KeyStore keyStore = loadKeyStore();
            if (keyStore.containsAlias(alias(slot))) {
                keyStore.deleteEntry(alias(slot));
            }
        } catch (Throwable e) {
            FileLog.e("ZgLockBiometrics: slot not deleted: " + e.getClass().getSimpleName());
        }
    }

    /** Compares the device's current enrollment with the slot's baseline without showing anything. */
    public static Enrollment enrollment(String slot) {
        if (!isStrongAvailable()) {
            return Enrollment.UNAVAILABLE;
        }
        try {
            return initCipher(slot) != null ? Enrollment.UNCHANGED : Enrollment.MISSING;
        } catch (KeyPermanentlyInvalidatedException e) {
            return Enrollment.CHANGED;
        } catch (Throwable e) {
            return Enrollment.UNAVAILABLE;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Prompts
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Asks for a strong biometric bound to {@code slot}. SUCCESS only after the Keystore accepted the authenticated key,
     * so a spoofed callback cannot unlock. ENROLLMENT_CHANGED is answered before anything is shown.
     */
    @MainThread
    public static void authenticate(@Nullable Activity activity, String slot, CharSequence title, @Nullable CharSequence subtitle, CharSequence negativeText, Callback callback) {
        final FragmentActivity fragmentActivity = activity instanceof FragmentActivity ? (FragmentActivity) activity : null;
        if (fragmentActivity == null || fragmentActivity.isFinishing() || fragmentActivity.getSupportFragmentManager().isStateSaved() || !isStrongAvailable()) {
            callback.onResult(Result.UNAVAILABLE);
            return;
        }
        final Cipher cipher;
        try {
            cipher = initCipher(slot);
        } catch (KeyPermanentlyInvalidatedException e) {
            callback.onResult(Result.ENROLLMENT_CHANGED);
            return;
        } catch (Throwable e) {
            callback.onResult(Result.UNAVAILABLE);
            return;
        }
        if (cipher == null) {
            callback.onResult(Result.ENROLLMENT_CHANGED);
            return;
        }
        final boolean[] finished = new boolean[1];
        final int relocks = ZgLockForeground.getRelockCount();
        final Callback once = result -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            setAuthenticating(false, false, null);
            // The app was left (or the screen went off) while the prompt was up: nothing unlocks after that.
            callback.onResult(result == Result.SUCCESS && relocks != ZgLockForeground.getRelockCount() ? Result.CANCELLED : result);
        };
        try {
            final BiometricPrompt prompt = new BiometricPrompt(fragmentActivity, ContextCompat.getMainExecutor(fragmentActivity), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    once.onResult(Result.CANCELLED);
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    boolean proven = false;
                    try {
                        final BiometricPrompt.CryptoObject cryptoObject = result.getCryptoObject();
                        final Cipher authenticated = cryptoObject != null ? cryptoObject.getCipher() : null;
                        proven = authenticated != null && authenticated.doFinal(new byte[16]) != null;
                    } catch (Throwable e) {
                        FileLog.e("ZgLockBiometrics: authenticated key refused: " + e.getClass().getSimpleName());
                    }
                    once.onResult(proven ? Result.SUCCESS : Result.CANCELLED);
                }

                @Override
                public void onAuthenticationFailed() {
                    // One wrong finger: the system prompt stays up and counts its own attempts.
                }
            });
            final BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(negativeText)
                .setConfirmationRequired(false)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
            if (subtitle != null) {
                info.setSubtitle(subtitle);
            }
            setAuthenticating(true, false, prompt);
            runningCancel = () -> once.onResult(Result.CANCELLED);
            prompt.authenticate(info.build(), new BiometricPrompt.CryptoObject(cipher));
        } catch (Throwable e) {
            FileLog.e("ZgLockBiometrics: prompt failed: " + e.getClass().getSimpleName());
            once.onResult(Result.UNAVAILABLE);
        }
    }

    /**
     * Device-owner proof for recovery actions only (Chat Lock reset): the screen lock, or a biometric of any class.
     * With {@code credentialOnly} (enrollment changed) Android 11+ asks for the screen lock only; older versions cannot
     * restrict androidx.biometric to it and also accept biometrics. UNAVAILABLE when the device has no secure lock.
     */
    @MainThread
    public static void authenticateDeviceOwner(@Nullable Activity activity, CharSequence title, @Nullable CharSequence subtitle, boolean credentialOnly, Callback callback) {
        final FragmentActivity fragmentActivity = activity instanceof FragmentActivity ? (FragmentActivity) activity : null;
        if (fragmentActivity == null || fragmentActivity.isFinishing() || fragmentActivity.getSupportFragmentManager().isStateSaved() || !canProveDeviceOwner()) {
            callback.onResult(Result.UNAVAILABLE);
            return;
        }
        final boolean[] finished = new boolean[1];
        final int relocks = ZgLockForeground.getRelockCount();
        final boolean credentialActivity = Build.VERSION.SDK_INT < 30;
        final Callback once = result -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            setAuthenticating(false, false, null);
            if (credentialActivity) {
                ZgLockForeground.noteExternalActivityResult();
            }
            callback.onResult(result == Result.SUCCESS && relocks != ZgLockForeground.getRelockCount() ? Result.CANCELLED : result);
        };
        try {
            final BiometricPrompt prompt = new BiometricPrompt(fragmentActivity, ContextCompat.getMainExecutor(fragmentActivity), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    once.onResult(errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT ? Result.UNAVAILABLE : Result.CANCELLED);
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    once.onResult(Result.SUCCESS);
                }

                @Override
                public void onAuthenticationFailed() {
                }
            });
            final int authenticators = credentialOnly && Build.VERSION.SDK_INT >= 30
                ? BiometricManager.Authenticators.DEVICE_CREDENTIAL
                : BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
            final BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setConfirmationRequired(false)
                .setAllowedAuthenticators(authenticators);
            if (subtitle != null) {
                info.setSubtitle(subtitle);
            }
            setAuthenticating(true, credentialActivity, credentialActivity ? null : prompt);
            runningCancel = credentialActivity ? null : () -> once.onResult(Result.CANCELLED);
            prompt.authenticate(info.build());
        } catch (Throwable e) {
            FileLog.e("ZgLockBiometrics: device-owner prompt failed: " + e.getClass().getSimpleName());
            once.onResult(Result.UNAVAILABLE);
        }
    }

    /** True while a biometric or device-credential prompt of a Zenlygram lock is up (main thread). */
    public static boolean isAuthenticating() {
        if (authenticating && SystemClock.elapsedRealtime() - authenticatingSince > AUTHENTICATING_TIMEOUT_MS) {
            authenticating = false;
            deviceCredentialPrompt = false;
            runningPrompt = null;
        }
        return authenticating;
    }

    /**
     * Main thread: a device-owner prompt is up on Android 10 or below, where the screen-lock screen is an activity that
     * stops the app's own (the only prompt that does).
     */
    public static boolean isDeviceCredentialScreenPossible() {
        return isAuthenticating() && deviceCredentialPrompt;
    }

    /** ZgLockForeground, on every relock: a running biometric prompt is cancelled (its result could not unlock anyway). */
    @MainThread
    static void onRelock() {
        final BiometricPrompt prompt = runningPrompt;
        final Runnable cancel = runningCancel;
        runningPrompt = null;
        runningCancel = null;
        if (prompt != null) {
            try {
                prompt.cancelAuthentication();
            } catch (Throwable e) {
                FileLog.e("ZgLockBiometrics: cancel failed: " + e.getClass().getSimpleName());
            }
        }
        if (cancel != null) {
            cancel.run();
        }
    }

    private static void setAuthenticating(boolean value, boolean deviceCredential, @Nullable BiometricPrompt prompt) {
        authenticating = value;
        deviceCredentialPrompt = value && deviceCredential;
        runningPrompt = value ? prompt : null;
        if (!value) {
            runningCancel = null;
        }
        authenticatingSince = SystemClock.elapsedRealtime();
    }

    // ---------------------------------------------------------------------------------------------------------

    private static String alias(String slot) {
        return SLOT_PREFIX + slot;
    }

    private static KeyStore loadKeyStore() throws Exception {
        final KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        return keyStore;
    }

    /** A cipher ready for an authenticated operation, null when the slot has no key. Throws when invalidated. */
    @Nullable
    private static Cipher initCipher(String slot) throws Exception {
        final KeyStore keyStore = loadKeyStore();
        final Key key = keyStore.getKey(alias(slot), null);
        if (!(key instanceof SecretKey)) {
            return null;
        }
        final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher;
    }
}
