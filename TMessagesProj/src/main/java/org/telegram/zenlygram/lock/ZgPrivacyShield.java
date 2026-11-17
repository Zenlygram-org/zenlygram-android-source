/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.lock;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.ui.LaunchActivity;

import java.util.HashSet;
import java.util.WeakHashMap;

/**
 * Keeps locked content out of the app switcher (iOS ChatLockPrivacyShield). Main thread only.
 *
 * <p>While any owner holds the shield ({@link #setRecentsHidden}, e.g. Chat Lock while a locked chat is unlocked):
 * on Android 13+ every activity of the app gets {@code setRecentsScreenshotEnabled(false)}, which blanks the recents
 * thumbnail and still allows screenshots; below Android 13 only FLAG_SECURE is reliable, so the windows get it through
 * Telegram's FlagSecureReason (screenshots of the app are blocked meanwhile). The shield must be up BEFORE the app
 * leaves the foreground, because Android takes the snapshot then: hold it for as long as content is unlocked.
 *
 * <p>{@link #secureWindow} is the FLAG_SECURE helper for screens that must never be captured (PIN screens; the Secret
 * Vault list and vault chats can use {@code new FlagSecureReason(window, condition)} directly).
 */
public final class ZgPrivacyShield {

    private static final HashSet<Object> owners = new HashSet<>();
    private static final WeakHashMap<Activity, FlagSecureReason> legacyReasons = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Boolean> activities = new WeakHashMap<>();
    private static boolean initialized;

    private ZgPrivacyShield() {
    }

    /** Idempotent; called by {@link ZgLockForeground#init()}. */
    @MainThread
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        final Application application = ApplicationLoader.applicationLoaderInstance;
        if (application != null) {
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                    track(activity);
                }

                @Override
                public void onActivityStarted(@NonNull Activity activity) {
                    track(activity);
                }

                @Override
                public void onActivityResumed(@NonNull Activity activity) {
                }

                @Override
                public void onActivityPaused(@NonNull Activity activity) {
                }

                @Override
                public void onActivityStopped(@NonNull Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                    activities.remove(activity);
                    final FlagSecureReason reason = legacyReasons.remove(activity);
                    if (reason != null) {
                        reason.detach();
                    }
                }
            });
        }
        if (LaunchActivity.instance != null) {
            track(LaunchActivity.instance);
        }
    }

    /** Adds or removes {@code owner}'s claim on the shield; the shield is up while any owner claims it. */
    @MainThread
    public static void setRecentsHidden(Object owner, boolean hidden) {
        init();
        final boolean changed = hidden ? owners.add(owner) : owners.remove(owner);
        if (changed) {
            for (Activity activity : activities.keySet().toArray(new Activity[0])) {
                apply(activity);
            }
        }
    }

    public static boolean isRecentsHidden() {
        return !owners.isEmpty();
    }

    /**
     * FLAG_SECURE for a window that shows a PIN screen or other secret UI. Debug builds too: the owner tests the locks on
     * the debug APK, so it must behave like the release one.
     */
    public static void secureWindow(@Nullable Window window) {
        if (window == null) {
            return;
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private static void track(Activity activity) {
        if (activity == null) {
            return;
        }
        if (!activities.containsKey(activity)) {
            activities.put(activity, Boolean.TRUE);
        }
        apply(activity);
    }

    private static void apply(Activity activity) {
        final boolean hide = !owners.isEmpty();
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.setRecentsScreenshotEnabled(!hide);
            } else {
                FlagSecureReason reason = legacyReasons.get(activity);
                if (reason == null) {
                    if (!hide) {
                        return;
                    }
                    reason = new FlagSecureReason(activity.getWindow(), ZgPrivacyShield::isRecentsHidden);
                    legacyReasons.put(activity, reason);
                    reason.attach();
                } else {
                    reason.invalidate();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
