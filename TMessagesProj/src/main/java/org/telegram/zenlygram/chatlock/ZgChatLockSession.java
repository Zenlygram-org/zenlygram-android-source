/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import android.os.SystemClock;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.zenlygram.lock.ZgLockForeground;
import org.telegram.zenlygram.lock.ZgLockSurfaces;
import org.telegram.zenlygram.lock.ZgPrivacyShield;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

/**
 * Who may see a locked chat right now (iOS ChatLockSession). Main thread, except the static checks marked "any thread".
 *
 * <p>A successful PIN prompt issues a <b>grant</b> for (account, chat), keyed by the account's client user id: pending
 * for {@value #PENDING_GRANT_MS} ms until a screen of that chat <b>holds</b> it, then held while any holder is
 * attached (a gate host in a fragment stack), and revoked {@value #LEAVE_GRACE_MS} ms after the last holder left (a
 * quick re-open needs no PIN; a screen pushed above the chat keeps it). Grants are memory only: process death relocks.
 *
 * <p>{@link #relockAll} (app in the background, screen off: {@link ZgLockForeground}) revokes every grant at once:
 * covers re-appear synchronously, fragments above the lowest relocked chat of each stack are removed, viewers / menus /
 * playback of relocked chats are closed ({@link ZgChatLockGate#onRelock}). On return the visible relocked chat asks
 * again. While any grant lives, the app-switcher snapshot is hidden ({@link ZgPrivacyShield}).
 *
 * <p>The Secret Vault plugs in as a {@link ZgProtectionLayer}.
 */
public final class ZgChatLockSession implements ZgLockForeground.Listener, ZgChatLockStore.Listener {

    public static final int REASON_OPEN_CHAT = 0;
    /** Automatic prompt after a relock; never outranks an explicit open. */
    public static final int REASON_RESUME = 1;
    /** The user tapped Unlock (cover) or opened something that needs the chat (music sheet). */
    public static final int REASON_EXPLICIT = 2;

    public static final int RESULT_UNLOCKED = 0;
    /** The user closed the PIN screen: the asking screen should close too. */
    public static final int RESULT_CANCELLED = 1;
    /** The prompt was taken away (another chat's explicit open, the activity went away): stay covered. */
    public static final int RESULT_INTERRUPTED = 2;

    static final long PENDING_GRANT_MS = 60_000L;
    static final long LEAVE_GRACE_MS = 2_000L;

    public interface UnlockCallback {
        void onResult(int result);
    }

    public interface VisibilityListener {
        void onVisibilityChanged(boolean visible);
    }

    public interface AttachedCheck {
        boolean isAttached();
    }

    /** (client user id of the account, dialog id). */
    public static final class Key {
        public final long userId;
        public final long dialogId;

        public Key(long userId, long dialogId) {
            this.userId = userId;
            this.dialogId = dialogId;
        }

        public static Key of(int account, long dialogId) {
            return new Key(UserConfig.getInstance(account).getClientUserId(), dialogId);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            final Key other = (Key) o;
            return userId == other.userId && dialogId == other.dialogId;
        }

        @Override
        public int hashCode() {
            return (int) (userId ^ (userId >>> 32)) * 31 + (int) (dialogId ^ (dialogId >>> 32));
        }
    }

    private static final class Grant {
        boolean held;
        long deadline;
    }

    private static final class Holder {
        final AttachedCheck check;
        final long registeredAt;

        Holder(AttachedCheck check) {
            this.check = check;
            this.registeredAt = SystemClock.elapsedRealtime();
        }
    }

    private static ZgChatLockSession instance;
    private static volatile ZgProtectionLayer protectionLayer;

    private final HashMap<Key, Grant> grants = new HashMap<>();
    private final HashMap<Key, ArrayList<Holder>> holders = new HashMap<>();
    private final HashMap<Key, ArrayList<VisibilityListener>> visibilityListeners = new HashMap<>();
    private final HashMap<Key, Runnable> pendingTimers = new HashMap<>();
    private final HashMap<Key, Runnable> leaveTimers = new HashMap<>();
    final ZgChatLockPresenter presenter;

    private final NotificationCenter.NotificationCenterDelegate migrationObserver = (id, account, args) -> {
        if (id == NotificationCenter.updateInterfaces && args.length > 0 && args[0] instanceof Integer
            && (((Integer) args[0]) & MessagesController.UPDATE_MASK_CHAT) != 0) {
            carryOverMigratedLocks(account);
        }
    };

    @MainThread
    public static ZgChatLockSession getInstance() {
        if (instance == null) {
            instance = new ZgChatLockSession();
        }
        return instance;
    }

    private ZgChatLockSession() {
        presenter = new ZgChatLockPresenter(this);
        ZgLockForeground.addListener(this);
        ZgChatLockStore.addListener(this);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).addObserver(migrationObserver, NotificationCenter.updateInterfaces);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Static checks (any thread)
    // ---------------------------------------------------------------------------------------------------------

    /** Any thread: {@code dialogId} is Chat-Locked and is not this account's own Saved Messages. */
    public static boolean isProtected(int account, long dialogId) {
        if (dialogId == 0 || !ZgChatLockStore.isLocked(dialogId)) {
            return false;
        }
        return dialogId != UserConfig.getInstance(account).getClientUserId();
    }

    /**
     * Any thread: whether surfaces outside the chat itself (chat-list text, notifications, search, widgets, PiP) must
     * hide this chat's content, whatever the session state: Chat-Locked, or hidden in the Secret Vault.
     */
    public static boolean hidesContentGlobally(int account, long dialogId) {
        if (isProtected(account, dialogId)) {
            return true;
        }
        final ZgProtectionLayer layer = protectionLayer;
        if (layer == null || dialogId == 0) {
            return false;
        }
        try {
            return layer.isHidden(account, dialogId);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Main thread. The Secret Vault registers itself here; null removes it. */
    @MainThread
    public static void setProtectionLayer(@Nullable ZgProtectionLayer layer) {
        protectionLayer = layer;
    }

    @Nullable
    public static ZgProtectionLayer getProtectionLayer() {
        return protectionLayer;
    }

    /** Any thread: whether the vault shows its hidden chats' rows now (its list is open). */
    static boolean vaultShowsRows(int account) {
        final ZgProtectionLayer layer = protectionLayer;
        if (layer == null) {
            return false;
        }
        try {
            return layer.showsRows(account);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Any thread: whether the vault hides this chat (Chat Lock then never names it). */
    static boolean isVaultHidden(int account, long dialogId) {
        final ZgProtectionLayer layer = protectionLayer;
        if (layer == null) {
            return false;
        }
        try {
            return layer.isHidden(account, dialogId);
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Visibility (main thread)
    // ---------------------------------------------------------------------------------------------------------

    /** Lock layer: not locked, or granted. Vault layer: not hidden, or that account's vault is open. */
    public boolean isContentVisible(int account, long dialogId) {
        if (isProtected(account, dialogId) && !hasGrant(Key.of(account, dialogId))) {
            return false;
        }
        final ZgProtectionLayer layer = protectionLayer;
        if (layer != null && isVaultHidden(account, dialogId)) {
            try {
                return layer.isOpen(account);
            } catch (Throwable e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Completes at once with UNLOCKED when the content is already visible; otherwise asks (vault PIN first for a
     * vaulted chat, then the chat's pincode), one prompt at a time ({@link ZgChatLockPresenter}).
     */
    public void requireUnlock(int account, long dialogId, int reason, @Nullable BaseFragment host, UnlockCallback callback) {
        if (isContentVisible(account, dialogId)) {
            callback.onResult(RESULT_UNLOCKED);
            return;
        }
        final ZgProtectionLayer layer = protectionLayer;
        if (layer != null && isVaultHidden(account, dialogId) && !safeIsOpen(layer, account)) {
            if (reason == REASON_RESUME) {
                callback.onResult(RESULT_INTERRUPTED);
                return;
            }
            layer.requireOpen(account, host, opened -> {
                if (!opened) {
                    callback.onResult(RESULT_CANCELLED);
                } else {
                    requireUnlock(account, dialogId, reason, host, callback);
                }
            });
            return;
        }
        presenter.request(Key.of(account, dialogId), account, dialogId, reason, host, callback);
    }

    private static boolean safeIsOpen(ZgProtectionLayer layer, int account) {
        try {
            return layer.isOpen(account);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Issues a pending grant (the prompt succeeded). A holder attaching makes it held. */
    void grant(Key key) {
        Grant grant = grants.get(key);
        if (grant == null || !grant.held) {
            if (grant == null) {
                grant = new Grant();
                grants.put(key, grant);
            }
            if (hasAttachedHolder(key)) {
                grant.held = true;
                cancelTimer(pendingTimers, key);
            } else {
                grant.deadline = SystemClock.elapsedRealtime() + PENDING_GRANT_MS;
                cancelTimer(pendingTimers, key);
                final Runnable timer = () -> {
                    pendingTimers.remove(key);
                    final Grant current = grants.get(key);
                    if (current != null && !current.held) {
                        revoke(key);
                    }
                };
                pendingTimers.put(key, timer);
                AndroidUtilities.runOnUIThread(timer, PENDING_GRANT_MS + 50);
            }
        }
        updateShield();
        broadcast(key);
    }

    /**
     * Registers a screen that shows {@code key}'s content. It counts as attached for the leave grace after
     * registering (push animations), then while {@code check} says so. Returns a token for {@link #release}.
     */
    public Object hold(Key key, AttachedCheck check) {
        ArrayList<Holder> list = holders.get(key);
        if (list == null) {
            list = new ArrayList<>();
            holders.put(key, list);
        }
        final Holder holder = new Holder(check);
        list.add(holder);
        final Grant grant = grants.get(key);
        if (grant != null && !grant.held) {
            grant.held = true;
            cancelTimer(pendingTimers, key);
        }
        cancelTimer(leaveTimers, key);
        AndroidUtilities.runOnUIThread(() -> setNeedsHolderCheck(key), LEAVE_GRACE_MS + 100);
        return holder;
    }

    public void release(Key key, @Nullable Object token) {
        final ArrayList<Holder> list = holders.get(key);
        if (list != null) {
            list.remove(token);
            if (list.isEmpty()) {
                holders.remove(key);
            }
        }
        setNeedsHolderCheck(key);
    }

    /** Re-evaluates a held grant; it is revoked after the leave grace without an attached holder. */
    void setNeedsHolderCheck(Key key) {
        final Grant grant = grants.get(key);
        if (grant == null || !grant.held) {
            return;
        }
        if (hasAttachedHolder(key)) {
            cancelTimer(leaveTimers, key);
            return;
        }
        if (leaveTimers.containsKey(key)) {
            return;
        }
        final Runnable timer = () -> {
            leaveTimers.remove(key);
            final Grant current = grants.get(key);
            if (current != null && current.held && !hasAttachedHolder(key)) {
                revoke(key);
            }
        };
        leaveTimers.put(key, timer);
        AndroidUtilities.runOnUIThread(timer, LEAVE_GRACE_MS);
    }

    boolean hasGrant(Key key) {
        final Grant grant = grants.get(key);
        if (grant == null) {
            return false;
        }
        if (!grant.held) {
            return SystemClock.elapsedRealtime() < grant.deadline;
        }
        if (!leaveTimers.containsKey(key) && !hasAttachedHolder(key)) {
            setNeedsHolderCheck(key);
        }
        return true;
    }

    private boolean hasAttachedHolder(Key key) {
        final ArrayList<Holder> list = holders.get(key);
        if (list == null) {
            return false;
        }
        final long now = SystemClock.elapsedRealtime();
        for (Holder holder : list) {
            try {
                if (now - holder.registeredAt < LEAVE_GRACE_MS || holder.check.isAttached()) {
                    return true;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return false;
    }

    private void revoke(Key key) {
        if (grants.remove(key) == null) {
            return;
        }
        cancelTimer(pendingTimers, key);
        cancelTimer(leaveTimers, key);
        updateShield();
        broadcast(key);
    }

    public void addVisibilityListener(Key key, VisibilityListener listener) {
        ArrayList<VisibilityListener> list = visibilityListeners.get(key);
        if (list == null) {
            list = new ArrayList<>();
            visibilityListeners.put(key, list);
        }
        if (!list.contains(listener)) {
            list.add(listener);
        }
    }

    public void removeVisibilityListener(Key key, VisibilityListener listener) {
        final ArrayList<VisibilityListener> list = visibilityListeners.get(key);
        if (list != null) {
            list.remove(listener);
            if (list.isEmpty()) {
                visibilityListeners.remove(key);
            }
        }
    }

    private void broadcast(Key key) {
        final ArrayList<VisibilityListener> list = visibilityListeners.get(key);
        if (list == null || list.isEmpty()) {
            return;
        }
        final int account = accountOf(key);
        // An account that is gone (logged out) never sees anything (fail closed).
        final boolean visible = account >= 0 && isContentVisible(account, key.dialogId);
        for (VisibilityListener listener : new ArrayList<>(list)) {
            try {
                listener.onVisibilityChanged(visible);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /** Main thread: at least one locked chat is unlocked in this session. */
    public boolean hasLiveGrants() {
        return !grants.isEmpty();
    }

    /**
     * Main thread. For the protection layer (Secret Vault): it hid or unhid chats, or a vault opened or closed.
     * Every gate re-evaluates its cover.
     */
    public void notifyProtectionChanged() {
        for (Key key : new ArrayList<>(visibilityListeners.keySet())) {
            broadcast(key);
        }
        ZgChatLockGate.onLocksChanged();
    }

    private void updateShield() {
        ZgPrivacyShield.setRecentsHidden(this, !grants.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Relock
    // ---------------------------------------------------------------------------------------------------------

    /** Revokes every grant, re-covers, evicts screens above relocked chats and tears down overlays. */
    public void relockAll(int reason) {
        final HashSet<Key> revoked = new HashSet<>(grants.keySet());
        grants.clear();
        for (Runnable timer : pendingTimers.values()) {
            AndroidUtilities.cancelRunOnUIThread(timer);
        }
        pendingTimers.clear();
        for (Runnable timer : leaveTimers.values()) {
            AndroidUtilities.cancelRunOnUIThread(timer);
        }
        leaveTimers.clear();
        updateShield();
        for (Key key : revoked) {
            broadcast(key);
        }
        presenter.onRelock();
        try {
            ZgChatLockGate.onRelock(revoked);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onRelock(int reason) {
        relockAll(reason);
    }

    @Override
    public void onForeground() {
        ZgChatLockGate.onAppForeground();
        presenter.setNeedsPresentation();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lock-set changes
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public void onLocksChanged(long[] added, long[] removed) {
        if (added.length > 0) {
            // The owner just locked a chat that is on screen: never cover it in their face.
            for (Key key : ZgChatLockGate.implicitGrantKeys(added)) {
                Grant grant = grants.get(key);
                if (grant == null) {
                    grant = new Grant();
                    grants.put(key, grant);
                }
                grant.held = true;
                cancelTimer(pendingTimers, key);
            }
            updateShield();
        }
        if (removed.length > 0) {
            final HashSet<Long> removedIds = new HashSet<>();
            for (long id : removed) {
                removedIds.add(id);
            }
            final Iterator<Map.Entry<Key, Grant>> iterator = grants.entrySet().iterator();
            while (iterator.hasNext()) {
                final Key key = iterator.next().getKey();
                if (removedIds.contains(key.dialogId)) {
                    iterator.remove();
                    cancelTimer(pendingTimers, key);
                    cancelTimer(leaveTimers, key);
                }
            }
            updateShield();
            presenter.peersUnlocked(removedIds);
        }
        final HashSet<Long> affected = new HashSet<>();
        for (long id : added) {
            affected.add(id);
        }
        for (long id : removed) {
            affected.add(id);
        }
        for (Key key : new ArrayList<>(visibilityListeners.keySet())) {
            if (affected.isEmpty() || affected.contains(key.dialogId)) {
                broadcast(key);
            }
        }
        ZgChatLockGate.onLocksChanged();
        if (added.length > 0 || removed.length > 0) {
            // Outside the app at once, not at the chat's next message: the shade (redacted or restored), the widgets
            // and the conversation shortcuts (they name the chat; a new one is never made for a locked chat).
            ZgLockSurfaces.repostNotifications();
            ZgLockSurfaces.refreshWidgets();
            ZgLockSurfaces.removeConversationShortcuts(added);
        }
    }

    /** A locked basic group that became a supergroup: its new channel id inherits the lock (iOS copyLock). */
    private void carryOverMigratedLocks(int account) {
        if (ZgChatLockStore.getLockedCount() == 0) {
            return;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        for (long dialogId : ZgChatLockStore.getLockedIds()) {
            if (dialogId >= 0) {
                continue;
            }
            final TLRPC.Chat chat = controller.getChat(-dialogId);
            if (chat != null && chat.migrated_to != null && chat.migrated_to.channel_id != 0) {
                final long target = -chat.migrated_to.channel_id;
                if (!ZgChatLockStore.isLocked(target)) {
                    ZgChatLockStore.copyLock(dialogId, target);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Account index whose client user id is {@code key.userId}, or -1. */
    static int accountOf(Key key) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated() && config.getClientUserId() == key.userId) {
                return a;
            }
        }
        return -1;
    }

    private static void cancelTimer(HashMap<Key, Runnable> timers, Key key) {
        final Runnable timer = timers.remove(key);
        if (timer != null) {
            AndroidUtilities.cancelRunOnUIThread(timer);
        }
    }
}
