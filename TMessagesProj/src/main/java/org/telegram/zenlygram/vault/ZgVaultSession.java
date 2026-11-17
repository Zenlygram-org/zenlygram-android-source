/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import android.os.SystemClock;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.TopicsFragment;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.zenlygram.chatlock.ZgChatLockSession;
import org.telegram.zenlygram.chatlock.ZgProtectedOverlays;
import org.telegram.zenlygram.lock.ZgLockForeground;
import org.telegram.zenlygram.lock.ZgPrivacyShield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Which accounts have their vault open right now (iOS ChatLockSession vault grants). Main thread, except
 * {@link #isOpen}, which any thread may read. Memory only: process death closes every vault.
 *
 * <p>The PIN opens one account's vault (keyed by its Telegram user id). It stays open while a <b>holder</b> is in one
 * of LaunchActivity's fragment stacks (main, tablet right pane, layers): the vault list of that account, or a chat or
 * topics screen of one of its hidden chats. It closes:
 * <ul>
 *   <li>when the app goes to the background or the screen turns off ({@link ZgLockForeground}; the app's own picker or
 *       camera gets the shared grace), without animation;</li>
 *   <li>{@value #LEAVE_GRACE_MS} ms after the last holder left (a quick way back needs no PIN);</li>
 *   <li>{@value #PENDING_MS} ms after it was opened if nothing ever held it;</li>
 *   <li>after {@value #INACTIVITY_MS} ms without a touch (kept while hidden media plays or the gallery is up);</li>
 *   <li>when the vault is turned off or the account logs out.</li>
 * </ul>
 * Closing covers open hidden chats at once (Chat Lock's gate), closes the gallery / view-once viewer showing a hidden
 * chat's media and stops its playback ({@link ZgProtectedOverlays}), and removes the holders and everything above them
 * from the stacks on the next frame (never during a transition, never the bottom screen of the main stack). While any
 * vault is open the app-switcher thumbnail is hidden ({@link ZgPrivacyShield}) and LaunchActivity's window is
 * FLAG_SECURE, so no screen of an open vault (hidden forums, a hidden chat's profile, media or search) is captured.
 */
public final class ZgVaultSession implements ZgLockForeground.Listener {

    static final long PENDING_MS = 60_000L;
    static final long LEAVE_GRACE_MS = 2_000L;
    static final long INACTIVITY_MS = 300_000L;
    private static final long TICK_MS = 1_000L;
    private static final int EVICT_ATTEMPTS = 10;

    static final int CLOSE_BACKGROUND = 1;
    static final int CLOSE_INACTIVITY = 2;
    static final int CLOSE_LEFT = 3;
    static final int CLOSE_EXPIRED = 4;
    static final int CLOSE_TURNED_OFF = 5;
    static final int CLOSE_LOGOUT = 6;

    private static final class Grant {
        final long uid;
        final int account;
        final long openedAt;
        boolean held;
        long lastHeldAt;

        Grant(long uid, int account, long now) {
            this.uid = uid;
            this.account = account;
            this.openedAt = now;
        }
    }

    private static ZgVaultSession instance;
    /** Sorted user ids with an open vault, for any-thread reads. */
    private static volatile long[] openUids = new long[0];
    private static long lastInteraction = SystemClock.elapsedRealtime();

    private final HashMap<Long, Grant> grants = new HashMap<>();
    private final Runnable ticker = this::tick;
    private boolean tickScheduled;
    /** FLAG_SECURE on the app's windows while any vault is open (per window, so a recreated activity gets its own). */
    private final WeakHashMap<android.view.Window, FlagSecureReason> secureReasons = new WeakHashMap<>();

    @MainThread
    public static ZgVaultSession getInstance() {
        if (instance == null) {
            instance = new ZgVaultSession();
            ZgLockForeground.addListener(instance);
        }
        return instance;
    }

    /** The session if one was ever created (nothing is open otherwise). */
    @Nullable
    static ZgVaultSession peek() {
        return instance;
    }

    private ZgVaultSession() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------------------

    /** Any thread: whether the vault of account slot {@code account} is open. */
    public static boolean isOpen(int account) {
        final long[] uids = openUids;
        if (uids.length == 0 || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        final long uid = UserConfig.getInstance(account).getClientUserId();
        return uid != 0 && Arrays.binarySearch(uids, uid) >= 0;
    }

    static boolean isOpenUid(long uid) {
        return uid != 0 && Arrays.binarySearch(openUids, uid) >= 0;
    }

    /** Any thread: whether the vault of at least one account is open. */
    public static boolean isAnyOpen() {
        return openUids.length > 0;
    }

    /** LaunchActivity.onUserInteraction: feeds the inactivity timer. */
    @MainThread
    public static void noteUserActivity() {
        lastInteraction = SystemClock.elapsedRealtime();
    }

    /** The PIN (or a "Forgot PIN" rung) was proven: opens the vault of {@code account}, pending until a holder shows. */
    @MainThread
    void open(int account) {
        if (!ZgVault.isActive()) {
            return;
        }
        final long uid = ZgVault.uidOf(account);
        if (uid == 0) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        lastInteraction = now;
        if (!grants.containsKey(uid)) {
            grants.put(uid, new Grant(uid, account, now));
        }
        publish();
        scheduleTick();
        afterChange(account);
    }

    @MainThread
    void close(int account, int reason) {
        closeUid(ZgVault.uidOf(account), reason);
    }

    @MainThread
    void closeUid(long uid, int reason) {
        final Grant grant = grants.remove(uid);
        if (grant == null) {
            return;
        }
        publish();
        afterClose(grant, reason);
    }

    @MainThread
    void closeAll(int reason) {
        if (grants.isEmpty()) {
            return;
        }
        final ArrayList<Grant> closed = new ArrayList<>(grants.values());
        grants.clear();
        publish();
        for (Grant grant : closed) {
            afterClose(grant, reason);
        }
    }

    private void publish() {
        final long[] uids = new long[grants.size()];
        int i = 0;
        for (Long uid : grants.keySet()) {
            uids[i++] = uid;
        }
        Arrays.sort(uids);
        openUids = uids;
        ZgPrivacyShield.setRecentsHidden(this, uids.length > 0);
        updateSecureWindows();
    }

    private void updateSecureWindows() {
        try {
            final LaunchActivity activity = LaunchActivity.instance;
            if (activity != null && openUids.length > 0 && activity.getWindow() != null && !secureReasons.containsKey(activity.getWindow())) {
                final FlagSecureReason reason = new FlagSecureReason(activity.getWindow(), ZgVaultSession::isAnyOpen);
                secureReasons.put(activity.getWindow(), reason);
                reason.attach();
            }
            for (FlagSecureReason reason : secureReasons.values()) {
                reason.invalidate();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void afterChange(int account) {
        try {
            // Chat Lock's gates re-check every open chat: hidden chats uncover or cover now.
            ZgChatLockSession.getInstance().notifyProtectionChanged();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (account >= 0 && UserConfig.getInstance(account).isClientActivated()) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    private void afterClose(Grant grant, int reason) {
        afterChange(grant.account);
        // The gallery / view-once viewer of a hidden chat is a window above every fragment, and its voice or music keeps
        // playing (player bar, lock-screen controls): they go with the vault. In the background everything closes.
        final long uid = grant.uid;
        ZgProtectedOverlays.close((account, dialogId) -> !isOpenUid(uid) && ZgVault.uidOf(account) == uid && ZgVault.isHidden(account, dialogId),
            reason == CLOSE_BACKGROUND);
        final boolean animated = reason == CLOSE_INACTIVITY || reason == CLOSE_LEFT || reason == CLOSE_EXPIRED;
        AndroidUtilities.runOnUIThread(() -> evict(grant.uid, grant.account, animated, 0));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Timers
    // ---------------------------------------------------------------------------------------------------------

    private void scheduleTick() {
        if (!tickScheduled && !grants.isEmpty()) {
            tickScheduled = true;
            AndroidUtilities.runOnUIThread(ticker, TICK_MS);
        }
    }

    private void tick() {
        tickScheduled = false;
        final long now = SystemClock.elapsedRealtime();
        for (Grant grant : new ArrayList<>(grants.values())) {
            try {
                final int account = ZgVault.accountOf(grant.uid);
                if (account < 0 || !ZgVault.isActive()) {
                    closeUid(grant.uid, CLOSE_LOGOUT);
                    continue;
                }
                if (hasHolder(account)) {
                    grant.held = true;
                    grant.lastHeldAt = now;
                } else if (grant.held) {
                    if (now - grant.lastHeldAt >= LEAVE_GRACE_MS) {
                        closeUid(grant.uid, CLOSE_LEFT);
                        continue;
                    }
                } else if (now - grant.openedAt >= PENDING_MS) {
                    closeUid(grant.uid, CLOSE_EXPIRED);
                    continue;
                }
                if (now - lastInteraction >= INACTIVITY_MS && !keepsAlive(account)) {
                    closeUid(grant.uid, CLOSE_INACTIVITY);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                closeUid(grant.uid, CLOSE_LEFT);
            }
        }
        scheduleTick();
    }

    /** Hidden media playing (voice, music, round video) or the gallery on screen keep an idle vault open. */
    private static boolean keepsAlive(int account) {
        try {
            if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
                return true;
            }
            final MediaController mediaController = MediaController.getInstance();
            final MessageObject playing = mediaController.getPlayingMessageObject();
            return playing != null && playing.currentAccount == account && !mediaController.isMessagePaused()
                && ZgVault.isHidden(account, playing.getDialogId());
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Holders
    // ---------------------------------------------------------------------------------------------------------

    private static boolean hasHolder(int account) {
        for (INavigationLayout layout : layouts()) {
            if (layout == null) {
                continue;
            }
            final List<BaseFragment> stack = layout.getFragmentStack();
            if (stack == null) {
                continue;
            }
            for (int i = 0; i < stack.size(); i++) {
                if (isHolder(stack.get(i), account)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The vault list of {@code account}, or a chat / topics screen of one of its hidden chats. */
    static boolean isHolder(@Nullable BaseFragment fragment, int account) {
        if (fragment == null || fragment.isFinished || fragment.getCurrentAccount() != account) {
            return false;
        }
        if (fragment instanceof ZgVaultActivity) {
            return true;
        }
        final long dialogId = dialogOf(fragment);
        return dialogId != 0 && ZgVault.isHidden(account, dialogId);
    }

    static long dialogOf(@Nullable BaseFragment fragment) {
        try {
            if (fragment instanceof ChatActivity) {
                return ((ChatActivity) fragment).getDialogId();
            }
            if (fragment instanceof TopicsFragment) {
                return ((TopicsFragment) fragment).getDialogId();
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    private static INavigationLayout[] layouts() {
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null) {
            return new INavigationLayout[0];
        }
        return new INavigationLayout[]{activity.getActionBarLayout(), activity.getRightActionBarLayout(), activity.getLayersActionBarLayout()};
    }

    /**
     * Removes the lowest holder of {@code account} and everything above it from each stack. The bottom screen of the
     * main stack is never removed (it stays covered instead). Waits for running transitions.
     */
    private static void evict(long uid, int account, boolean animated, int attempt) {
        if (isOpenUid(uid) || account < 0) {
            return;
        }
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null) {
            return;
        }
        boolean retry = false;
        for (INavigationLayout layout : layouts()) {
            if (layout == null) {
                continue;
            }
            try {
                final List<BaseFragment> live = layout.getFragmentStack();
                if (live == null || live.isEmpty()) {
                    continue;
                }
                final ArrayList<BaseFragment> stack = new ArrayList<>(live);
                int lowest = -1;
                for (int i = 0; i < stack.size(); i++) {
                    if (isHolder(stack.get(i), account)) {
                        lowest = i;
                        break;
                    }
                }
                if (lowest < 0) {
                    continue;
                }
                if (layout == activity.getActionBarLayout() && lowest == 0) {
                    lowest = 1;
                }
                if (lowest >= stack.size()) {
                    continue;
                }
                if (layout.isTransitionAnimationInProgress()) {
                    retry = true;
                    continue;
                }
                if (animated && lowest == stack.size() - 1 && stack.size() > 1) {
                    stack.get(lowest).finishFragment(true);
                    continue;
                }
                for (int i = stack.size() - 1; i >= lowest; i--) {
                    final BaseFragment fragment = stack.get(i);
                    if (fragment != null && !fragment.isFinished && layout.getFragmentStack().contains(fragment)) {
                        layout.removeFragmentFromStack(fragment, true);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (retry && attempt < EVICT_ATTEMPTS) {
            AndroidUtilities.runOnUIThread(() -> evict(uid, account, animated, attempt + 1), 250);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // ZgLockForeground.Listener
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public void onRelock(int reason) {
        closeAll(CLOSE_BACKGROUND);
    }

    @Override
    public void onForeground() {
    }
}
