/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import android.app.Activity;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.zenlygram.lock.ZgLockForeground;
import org.telegram.zenlygram.lock.ZgPinDialog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Set;

/**
 * One Chat Lock PIN prompt at a time (iOS ChatLockPromptPresenter). Main thread.
 *
 * <ul>
 *   <li>A request for the key already asked for joins it.</li>
 *   <li>While a prompt is on screen, a request for another key ends INTERRUPTED (its screen stays covered with an
 *       Unlock button), except that an explicit open takes down an automatic RESUME prompt and is shown instead.</li>
 *   <li>Before anything is on screen, a newer request replaces an older waiting one, but RESUME never replaces an
 *       explicit open.</li>
 *   <li>Nothing is shown while Telegram's own app passcode is up or the activity is not resumed; after the passcode
 *       goes away the prompt waits {@value #APP_LOCK_SETTLE_MS} ms, RESUME prompts {@value #RESUME_GRACE_MS} ms. A
 *       prompt that never got on screen is given up after {@value #GIVE_UP_MS} ms (INTERRUPTED).</li>
 *   <li>A prompt left up across a background is demoted to RESUME.</li>
 *   <li>The grant is issued before the dialog closes, so a dismissal interrupted by the app passcode still unlocks.</li>
 * </ul>
 */
final class ZgChatLockPresenter {

    static final long RESUME_GRACE_MS = 400;
    static final long APP_LOCK_SETTLE_MS = 350;
    static final long GIVE_UP_MS = 30_000;
    private static final long POLL_MS = 250;

    static final class Request {
        final ZgChatLockSession.Key key;
        final int account;
        final long dialogId;
        int reason;
        WeakReference<BaseFragment> host;
        final ArrayList<ZgChatLockSession.UnlockCallback> callbacks = new ArrayList<>();
        final long createdAt = SystemClock.elapsedRealtime();
        boolean shown;
        ZgPinDialog dialog;

        Request(ZgChatLockSession.Key key, int account, long dialogId, int reason, @Nullable BaseFragment host) {
            this.key = key;
            this.account = account;
            this.dialogId = dialogId;
            this.reason = reason;
            this.host = new WeakReference<>(host);
        }

        @Nullable
        BaseFragment getHost() {
            return host.get();
        }
    }

    private final ZgChatLockSession session;
    private Request current;
    private Request waiting;
    private long passcodeDismissedAt;
    private final Runnable presentRunnable = this::tryPresent;

    private final NotificationCenter.NotificationCenterDelegate passcodeObserver = (id, account, args) -> {
        if (id == NotificationCenter.passcodeDismissed) {
            passcodeDismissedAt = SystemClock.elapsedRealtime();
            setNeedsPresentation();
        }
    };

    ZgChatLockPresenter(ZgChatLockSession session) {
        this.session = session;
        NotificationCenter.getGlobalInstance().addObserver(passcodeObserver, NotificationCenter.passcodeDismissed);
    }

    void request(ZgChatLockSession.Key key, int account, long dialogId, int reason, @Nullable BaseFragment host, ZgChatLockSession.UnlockCallback callback) {
        if (current != null && current.key.equals(key)) {
            current.callbacks.add(callback);
            if (reason != ZgChatLockSession.REASON_RESUME) {
                current.reason = reason;
            }
            if (host != null && (current.getHost() == null || !current.shown)) {
                current.host = new WeakReference<>(host);
            }
            return;
        }
        if (waiting != null && waiting.key.equals(key)) {
            waiting.callbacks.add(callback);
            if (reason != ZgChatLockSession.REASON_RESUME) {
                waiting.reason = reason;
            }
            if (host != null) {
                waiting.host = new WeakReference<>(host);
            }
            return;
        }
        final Request request = new Request(key, account, dialogId, reason, host);
        request.callbacks.add(callback);
        if (current == null) {
            current = request;
            setNeedsPresentation();
            return;
        }
        if (current.reason == ZgChatLockSession.REASON_RESUME && reason != ZgChatLockSession.REASON_RESUME) {
            // An explicit open outranks an automatic resume prompt.
            final Request old = current;
            current = request;
            dismiss(old);
            complete(old, ZgChatLockSession.RESULT_INTERRUPTED);
            setNeedsPresentation();
            return;
        }
        if (current.shown) {
            complete(request, ZgChatLockSession.RESULT_INTERRUPTED);
            return;
        }
        // Nothing is on screen yet: the newer request wins, but RESUME never replaces an explicit open.
        if (reason == ZgChatLockSession.REASON_RESUME && current.reason != ZgChatLockSession.REASON_RESUME) {
            complete(request, ZgChatLockSession.RESULT_INTERRUPTED);
            return;
        }
        if (waiting != null) {
            complete(waiting, ZgChatLockSession.RESULT_INTERRUPTED);
        }
        waiting = request;
    }

    /** A request's host went away: its prompt is dropped if nobody else waits for that key. */
    void hostDestroyed(BaseFragment host) {
        if (waiting != null && waiting.getHost() == host) {
            final Request old = waiting;
            waiting = null;
            complete(old, ZgChatLockSession.RESULT_INTERRUPTED);
        }
        if (current != null && current.getHost() == host) {
            final Request old = current;
            current = null;
            dismiss(old);
            complete(old, ZgChatLockSession.RESULT_INTERRUPTED);
            promoteWaiting();
        }
    }

    /** Locks were removed (e.g. "Forgot pincode?" with the master): whoever waits for those chats may go on. */
    void peersUnlocked(Set<Long> dialogIds) {
        if (waiting != null && dialogIds.contains(waiting.dialogId)) {
            final Request old = waiting;
            waiting = null;
            complete(old, ZgChatLockSession.RESULT_UNLOCKED);
        }
        if (current != null && dialogIds.contains(current.dialogId)) {
            final Request old = current;
            current = null;
            if (old.dialog != null) {
                old.dialog.finish();
            }
            complete(old, ZgChatLockSession.RESULT_UNLOCKED);
            promoteWaiting();
        }
    }

    /** A prompt left up across a background becomes an automatic one. */
    void onRelock() {
        if (current != null) {
            current.reason = ZgChatLockSession.REASON_RESUME;
        }
        if (waiting != null) {
            waiting.reason = ZgChatLockSession.REASON_RESUME;
        }
    }

    /** True when a prompt for {@code key} is on screen or waiting. */
    boolean isAsking(ZgChatLockSession.Key key) {
        return current != null && current.key.equals(key) || waiting != null && waiting.key.equals(key);
    }

    void setNeedsPresentation() {
        AndroidUtilities.cancelRunOnUIThread(presentRunnable);
        if (current == null || current.shown) {
            return;
        }
        final long delay = current.reason == ZgChatLockSession.REASON_RESUME ? RESUME_GRACE_MS : 0;
        AndroidUtilities.runOnUIThread(presentRunnable, delay);
    }

    // ---------------------------------------------------------------------------------------------------------

    private void tryPresent() {
        final Request request = current;
        if (request == null || request.shown) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        final BaseFragment host = request.getHost();
        final Activity activity = host != null ? host.getParentActivity() : null;
        if (now - request.createdAt > GIVE_UP_MS || host == null || host.isFinished || activity == null || activity.isFinishing()) {
            current = null;
            complete(request, ZgChatLockSession.RESULT_INTERRUPTED);
            promoteWaiting();
            return;
        }
        if (session.isContentVisible(request.account, request.dialogId)) {
            current = null;
            complete(request, ZgChatLockSession.RESULT_UNLOCKED);
            promoteWaiting();
            return;
        }
        // The host's own pause state, not ApplicationLoader.mainInterfacePaused: only LaunchActivity sets that (bubbles).
        if (host.isPaused() || SharedConfig.isWaitingForPasscodeEnter || !ZgLockForeground.isForeground()) {
            AndroidUtilities.runOnUIThread(presentRunnable, POLL_MS);
            return;
        }
        final long settle = passcodeDismissedAt + APP_LOCK_SETTLE_MS - now;
        if (settle > 0) {
            AndroidUtilities.runOnUIThread(presentRunnable, settle);
            return;
        }
        request.shown = true;
        final ZgPinDialog dialog;
        try {
            dialog = ZgChatLockFlows.showUnlockPrompt(activity, host, request.account, request.dialogId, result -> onPromptResult(request, result));
        } catch (Throwable e) {
            FileLog.e(e);
            current = null;
            complete(request, ZgChatLockSession.RESULT_INTERRUPTED);
            promoteWaiting();
            return;
        }
        if (dialog == null) {
            current = null;
            complete(request, ZgChatLockSession.RESULT_INTERRUPTED);
            promoteWaiting();
            return;
        }
        request.dialog = dialog;
    }

    /** From the PIN dialog: UNLOCKED (the grant must already be issued), CANCELLED or INTERRUPTED. */
    private void onPromptResult(Request request, int result) {
        if (current != request) {
            return;
        }
        current = null;
        request.dialog = null;
        complete(request, result);
        promoteWaiting();
    }

    private void promoteWaiting() {
        if (current == null && waiting != null) {
            current = waiting;
            waiting = null;
        }
        setNeedsPresentation();
    }

    private void dismiss(Request request) {
        final ZgPinDialog dialog = request.dialog;
        request.dialog = null;
        if (dialog != null) {
            dialog.setOnInterruptedListener(null);
            dialog.finish();
        }
    }

    private static void complete(Request request, int result) {
        for (ZgChatLockSession.UnlockCallback callback : new ArrayList<>(request.callbacks)) {
            try {
                callback.onResult(result);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        request.callbacks.clear();
    }
}
