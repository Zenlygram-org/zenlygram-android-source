/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.lock;

import android.content.Intent;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.Components.ForegroundDetector;

import java.util.ArrayList;

/**
 * When Zenlygram locks must relock: the shared "app left / device locked" signal of Chat Lock and the Secret Vault.
 * Main thread only.
 *
 * <p>Relock reasons (iOS didEnterBackground / protectedDataWillBecomeUnavailable):
 * <ul>
 *   <li>{@link #RELOCK_BACKGROUND}: every activity of the app stopped (ForegroundDetector), so not on onPause:
 *       permission dialogs, the notification shade, BiometricPrompt, multi-window focus changes and in-app overlays
 *       keep the grants;</li>
 *   <li>{@link #RELOCK_SCREEN_OFF}: the screen turned off (Telegram's ScreenReceiver, NotificationCenter
 *       screenStateChanged), always, whatever else is going on.</li>
 * </ul>
 * A biometric prompt never stops the activity (a system dialog on Android 9+, an in-app dialog below), so a background
 * seen while one is up means the user really left: it relocks like any other. Only the device screen-lock screen of
 * a recovery prompt on Android 10 and below is an activity of its own
 * ({@link ZgLockBiometrics#isDeviceCredentialScreenPossible()}): then, and for {@value #EXTERNAL_RESULT_GRACE_MS} ms
 * after the app's own startActivityForResult of a picker or the camera (hooked in LaunchActivity), a background
 * schedules the relock at the end of the grace instead of relocking at once; coming back first cancels it. Leaving
 * through the share sheet, a browser or any other app relocks at once. A prompt result that arrives after a relock
 * never unlocks anything ({@link #getRelockCount()}).
 *
 * <pre>
 * ZgLockForeground.init(); // idempotent
 * ZgLockForeground.addListener(new ZgLockForeground.Listener() {
 *     public void onRelock(int reason) { closeEverything(); }
 *     public void onForeground() { promptForTheVisibleLockedScreen(); }
 * });
 * </pre>
 */
public final class ZgLockForeground {

    public interface Listener {
        /** Revoke every unlock now (synchronously: the recents snapshot may follow). */
        void onRelock(int reason);

        /** An activity of the app started again after a background. */
        void onForeground();
    }

    public static final int RELOCK_BACKGROUND = 1;
    public static final int RELOCK_SCREEN_OFF = 2;

    /** Owner decision (spec open question 2): no relock while the app's own picker / camera is out, for this long. */
    public static final long EXTERNAL_RESULT_GRACE_MS = 120_000L;

    private static final ArrayList<Listener> listeners = new ArrayList<>();
    private static boolean initialized;
    private static long externalGraceDeadline;
    private static Runnable pendingGraceRelock;
    /** Incremented by every relock: a prompt started before it must not unlock after it. */
    private static int relockCount;

    private static final NotificationCenter.NotificationCenterDelegate screenObserver = (id, account, args) -> {
        if (id == NotificationCenter.screenStateChanged && !ApplicationLoader.isScreenOn) {
            cancelPendingGraceRelock();
            externalGraceDeadline = 0;
            dispatchRelock(RELOCK_SCREEN_OFF);
        }
    };

    private ZgLockForeground() {
    }

    /** Starts listening. Idempotent; call on the main thread before the first unlock is granted. */
    @MainThread
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        final ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector != null) {
            detector.addListener(new ForegroundDetector.Listener() {
                @Override
                public void onBecameForeground() {
                    cancelPendingGraceRelock();
                    for (Listener listener : new ArrayList<>(listeners)) {
                        try {
                            listener.onForeground();
                        } catch (Throwable e) {
                            FileLog.e(e);
                        }
                    }
                }

                @Override
                public void onBecameBackground() {
                    onBackground();
                }
            });
        } else {
            FileLog.e("ZgLockForeground: no ForegroundDetector");
        }
        NotificationCenter.getGlobalInstance().addObserver(screenObserver, NotificationCenter.screenStateChanged);
        ZgPrivacyShield.init();
    }

    @MainThread
    public static void addListener(Listener listener) {
        init();
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    @MainThread
    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Whether at least one activity of the app is started. */
    public static boolean isForeground() {
        final ForegroundDetector detector = ForegroundDetector.getInstance();
        return detector == null || detector.isForeground();
    }

    /** Main thread: how many relocks happened so far (compare before and after an asynchronous prompt). */
    public static int getRelockCount() {
        return relockCount;
    }

    /**
     * LaunchActivity.startActivityForResult hook: the app's own picker, camera or screen-lock check starts. Anything
     * else started for a result (the share sheet, Telegram's other external flows) gets no grace: leaving through it
     * relocks.
     */
    @MainThread
    public static void noteExternalActivityStarted(@Nullable Intent intent) {
        if (isGraceIntent(intent)) {
            externalGraceDeadline = SystemClock.elapsedRealtime() + EXTERNAL_RESULT_GRACE_MS;
        }
    }

    /** LaunchActivity.onActivityResult hook: the external flow returned. */
    @MainThread
    public static void noteExternalActivityResult() {
        externalGraceDeadline = 0;
    }

    /** A system picker, the camera or the device screen-lock check (also inside a chooser that wraps one of them). */
    static boolean isGraceIntent(@Nullable Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        if (Intent.ACTION_CHOOSER.equals(action)) {
            Intent inner = null;
            try {
                inner = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            } catch (Throwable ignore) {
            }
            if (inner == null) {
                return false;
            }
            action = inner.getAction();
        }
        if (action == null) {
            return false;
        }
        switch (action) {
            case Intent.ACTION_GET_CONTENT:
            case Intent.ACTION_OPEN_DOCUMENT:
            case Intent.ACTION_OPEN_DOCUMENT_TREE:
            case Intent.ACTION_CREATE_DOCUMENT:
            case Intent.ACTION_PICK:
            case MediaStore.ACTION_IMAGE_CAPTURE:
            case MediaStore.ACTION_VIDEO_CAPTURE:
            case "android.provider.action.PICK_IMAGES":
            case "android.app.action.CONFIRM_DEVICE_CREDENTIAL":
            case "android.app.action.CONFIRM_DEVICE_CREDENTIAL_WITH_USER":
                return true;
            default:
                return false;
        }
    }

    private static void onBackground() {
        final long now = SystemClock.elapsedRealtime();
        if (ZgLockBiometrics.isDeviceCredentialScreenPossible()) {
            // The screen-lock screen of a recovery prompt (Android 10 and below) is another activity: not a real leave.
            externalGraceDeadline = Math.max(externalGraceDeadline, now + EXTERNAL_RESULT_GRACE_MS);
        }
        if (now < externalGraceDeadline) {
            cancelPendingGraceRelock();
            pendingGraceRelock = () -> {
                pendingGraceRelock = null;
                if (!isForeground()) {
                    dispatchRelock(RELOCK_BACKGROUND);
                }
            };
            AndroidUtilities.runOnUIThread(pendingGraceRelock, externalGraceDeadline - now);
            return;
        }
        dispatchRelock(RELOCK_BACKGROUND);
    }

    private static void cancelPendingGraceRelock() {
        if (pendingGraceRelock != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingGraceRelock);
            pendingGraceRelock = null;
        }
    }

    private static void dispatchRelock(int reason) {
        relockCount++;
        ZgLockBiometrics.onRelock();
        for (Listener listener : new ArrayList<>(listeners)) {
            try {
                listener.onRelock(reason);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }
}
