/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.calls;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.os.Looper;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

import java.lang.ref.WeakReference;

/**
 * Call Confirmation (iOS confirmCalls, AccountContextImpl.requestCall): every outgoing private voice or video call
 * first asks "Make a Call?" / "Make a Video Call?" with Telegram's "Are you sure you want to call **Name**?".
 * Cancel or Back does nothing; Call goes on with Telegram's normal flow.
 *
 * <p>One hook in VoIPHelper.initiateCall covers every entry point (chat header, profile, call log, phone-row menu,
 * call messages, contacts app, t.me / tg: links), after Telegram's own checks (frozen account, calls forbidden by
 * privacy, offline) and after the first-call microphone / camera permission dialog. Not confirmed, as on iOS: group
 * calls, video chats, live streams and conference calls (no user), and the retries inside the call screen. While
 * another call is running Telegram asks "end the current call?" itself, so Zenlygram does not ask a second time.
 * The tg://call dialog (AlertsCreator.createCallDialogAlert) already is a confirmation: it continues through
 * {@link #runConfirmed}.
 *
 * <p>Fails safe: if the dialog cannot be shown, no call starts. Main thread only.
 */
public final class ZgCallConfirmation {

    /** True only while a confirmed call start runs (set and cleared on the main thread). */
    private static boolean bypass;
    /** The dialog on screen and its activity, so a double tap does not stack a second one. */
    private static WeakReference<AlertDialog> showing;
    private static WeakReference<Activity> showingActivity;

    private ZgCallConfirmation() {
    }

    public static boolean isEnabled() {
        return ZgFeatures.CALL_CONFIRMATION.isPorted() && ZgSettings.confirmCalls.get();
    }

    /**
     * Asks before a private call. Returns false when the call goes on now (switch off, confirmed, no user, a call is
     * running), true when it waits for the answer: {@code proceed} then runs, confirmed, after "Call".
     */
    public static boolean intercept(Activity activity, AccountInstance accountInstance, TLRPC.User user, boolean videoCall, Runnable proceed) {
        if (user == null || proceed == null || !isEnabled()) {
            return false;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Never expected (call starts come from taps and permission results); ask from the main thread instead
            AndroidUtilities.runOnUIThread(proceed);
            return true;
        }
        if (bypass) {
            return false;
        }
        if (UserObject.isUserSelf(user) || accountInstance != null && user.id == UserConfig.getInstance(accountInstance.getCurrentAccount()).getClientUserId()) {
            return false;
        }
        if (VoIPService.getSharedInstance() != null || VoIPService.callIShouldHavePutIntoIntent != null) {
            // Telegram shows its own "end current call and call X?" alert or opens the running call
            return false;
        }
        final AlertDialog current = showing != null ? showing.get() : null;
        final Activity currentActivity = showingActivity != null ? showingActivity.get() : null;
        // Only a dialog of this very activity counts: one left over from a recreated activity must not block calls
        if (current != null && currentActivity == activity && current.isShowing() && !current.isDismissed()) {
            return true;
        }
        if (activity == null || activity.isFinishing()) {
            return true;
        }
        try {
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(getString(videoCall ? R.string.ZG_CallConfirmation_Video_Title : R.string.ZG_CallConfirmation_Audio_Title))
                .setMessage(AndroidUtilities.replaceTags(formatString(videoCall ? R.string.VideoCallAlert : R.string.CallAlert, UserObject.getUserName(user))))
                .setPositiveButton(getString(videoCall ? R.string.VideoCall : R.string.Call), (d, which) -> runConfirmed(proceed))
                .setNegativeButton(getString(R.string.Cancel), null)
                .create();
            showing = new WeakReference<>(dialog);
            showingActivity = new WeakReference<>(activity);
            // AlertDialog.show() does nothing for an activity that is not running: then no call starts either
            dialog.show();
        } catch (Throwable e) {
            // No dialog means no consent: the call does not start
            FileLog.e(e);
            showing = null;
            showingActivity = null;
        }
        return true;
    }

    /** Runs a call start the user has already confirmed (in our dialog or in Telegram's tg://call dialog). */
    public static void runConfirmed(Runnable start) {
        if (start == null) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(() -> runConfirmed(start));
            return;
        }
        final boolean previous = bypass;
        bypass = true;
        try {
            start.run();
        } finally {
            bypass = previous;
        }
    }
}
