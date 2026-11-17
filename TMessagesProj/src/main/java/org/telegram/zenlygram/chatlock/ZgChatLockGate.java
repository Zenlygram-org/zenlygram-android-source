/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import android.graphics.Canvas;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MediaActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.TopicsFragment;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The single enforcement point of Chat Lock (iOS L3 backstop, ChatLockControllerGate). Instead of intercepting
 * navigation (callers depend on presentFragment's synchronous result), five one-line hooks in BaseFragment let a gate
 * attach to every screen that shows a locked chat, whoever created it: chat-list taps, search, notifications, links,
 * forward / share targets, shortcuts, widgets, bubbles, fragments restored after process death or a theme rebuild.
 *
 * <p>Hosts: ChatActivity (its dialog or its migrated basic group is locked; any mode except the account's own Saved
 * Messages), TopicsFragment (a locked forum) and MediaActivity (the shared media of a locked chat). ProfileActivity
 * hides the shared media and members of a locked chat while it is not unlocked ({@link #profileShowsContent}). Until
 * the content is visible the host carries an opaque
 * {@link ZgChatLockCoverView} that is added before the first frame; the PIN is asked when the host becomes fully
 * visible (or is expanded from a peek); cancel closes the host and anything above it. While covered, a ChatActivity
 * sends no read receipts, reactions, poll votes or mention reads, draws and receives nothing but the cover, starts no
 * raise-to-listen and takes no input focus (hooks in ChatActivity).
 *
 * <p>Hook entry points (all main thread, all safe for any fragment): {@link #onFragmentViewCreated},
 * {@link #onFragmentResume}, {@link #onFragmentFullyVisible}, {@link #onPreviewModeChanged},
 * {@link #onFragmentDestroy}, {@link #isCovered}, {@link #dispatchCoveredTouch}, {@link #dispatchCoveredDraw},
 * {@link #isCoverView}.
 */
public final class ZgChatLockGate implements ZgChatLockSession.VisibilityListener, ZgChatLockCoverView.Delegate {

    private static final WeakHashMap<BaseFragment, ZgChatLockGate> gates = new WeakHashMap<>();
    /** Every ChatActivity / TopicsFragment with a view, locked or not: a chat locked while open adopts a gate. */
    private static final WeakHashMap<BaseFragment, Boolean> candidates = new WeakHashMap<>();
    /** Profiles of locked / hidden chats: they rebuild their rows when the chat unlocks or locks again. */
    private static final WeakHashMap<BaseFragment, ProfileWatch> profileWatches = new WeakHashMap<>();

    private final WeakReference<BaseFragment> host;
    private final int account;
    private final long dialogId;
    final ZgChatLockSession.Key key;
    private int style;
    private ZgChatLockCoverView cover;
    private boolean covered;
    private boolean promptRequested;
    /** Became fully visible at least once: onResume may then re-ask (the first ask comes from full visibility). */
    private boolean shownOnce;
    private Object holderToken;
    private boolean destroyed;

    private ZgChatLockGate(BaseFragment fragment, long dialogId) {
        this.host = new WeakReference<>(fragment);
        this.account = fragment.getCurrentAccount();
        this.dialogId = dialogId;
        this.key = ZgChatLockSession.Key.of(account, dialogId);
        this.style = styleFor(fragment);
        final ZgChatLockSession session = ZgChatLockSession.getInstance();
        session.addVisibilityListener(key, this);
        holderToken = session.hold(key, this::isHostInStack);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hooks (BaseFragment)
    // ---------------------------------------------------------------------------------------------------------

    /** BaseFragment.performCreateViewImpl, after onViewCreated: covers before the first frame (every view rebuild). */
    public static void onFragmentViewCreated(BaseFragment fragment, View view) {
        if (!(fragment instanceof ChatActivity || fragment instanceof TopicsFragment || fragment instanceof MediaActivity)) {
            return;
        }
        try {
            candidates.put(fragment, Boolean.TRUE);
            ZgChatLockGate gate = gates.get(fragment);
            if (gate == null) {
                final long dialogId = resolveDialogId(fragment);
                if (dialogId == 0) {
                    return;
                }
                gate = new ZgChatLockGate(fragment, dialogId);
                gates.put(fragment, gate);
            }
            gate.attachView(view);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** BaseFragment.onResume: a covered top host that owes its prompt asks again. */
    public static void onFragmentResume(BaseFragment fragment) {
        final ZgChatLockGate gate = gates.get(fragment);
        if (gate != null && gate.shownOnce && gate.covered && gate.style == ZgChatLockCoverView.STYLE_INTERACTIVE && fragment.isLastFragment() && !fragment.isInPreviewMode()) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!gate.destroyed && gate.covered && fragment.isLastFragment() && !fragment.isPaused()) {
                    gate.prompt(ZgChatLockSession.REASON_RESUME);
                }
            }, ZgChatLockPresenter.RESUME_GRACE_MS);
        }
    }

    /** BaseFragment.onBecomeFullyVisible: first appearance (synchronous for no-animation opens) asks for the PIN. */
    public static void onFragmentFullyVisible(BaseFragment fragment) {
        final ZgChatLockGate gate = gates.get(fragment);
        if (gate == null) {
            return;
        }
        gate.refreshStyle();
        final boolean first = !gate.shownOnce;
        gate.shownOnce = true;
        if (gate.covered && gate.style == ZgChatLockCoverView.STYLE_INTERACTIVE) {
            gate.prompt(first ? ZgChatLockSession.REASON_OPEN_CHAT : ZgChatLockSession.REASON_RESUME);
        }
        ZgChatLockSession.getInstance().setNeedsHolderCheck(gate.key);
    }

    /** BaseFragment.setInPreviewMode: an expanded peek becomes a pushed chat that asks at once (cancel closes it). */
    public static void onPreviewModeChanged(BaseFragment fragment, boolean preview) {
        final ZgChatLockGate gate = gates.get(fragment);
        if (gate == null) {
            return;
        }
        final int previous = gate.style;
        gate.refreshStyle();
        if (!preview && previous == ZgChatLockCoverView.STYLE_PREVIEW && gate.covered && gate.style == ZgChatLockCoverView.STYLE_INTERACTIVE) {
            gate.prompt(ZgChatLockSession.REASON_OPEN_CHAT);
        }
    }

    /** BaseFragment.onFragmentDestroy: the holder leaves (grant revoked after the leave grace), its prompt ends. */
    public static void onFragmentDestroy(BaseFragment fragment) {
        candidates.remove(fragment);
        final ZgChatLockGate gate = gates.remove(fragment);
        if (gate != null) {
            gate.dispose(fragment);
        }
        final ProfileWatch watch = profileWatches.remove(fragment);
        if (watch != null) {
            ZgChatLockSession.getInstance().removeVisibilityListener(watch.key, watch);
        }
    }

    /**
     * ProfileActivity.updateRowsIds: whether the profile may show its chat's shared media, saved / common tabs and member
     * list now: always for a chat that is neither locked nor hidden in the vault; for one that is, only while its
     * content is visible (unlocked, or its vault open). Such a profile rebuilds its rows when that changes.
     */
    public static boolean profileShowsContent(ProfileActivity profile) {
        if (profile == null) {
            return true;
        }
        try {
            final int account = profile.getCurrentAccount();
            final long dialogId = profile.getDialogId();
            if (dialogId == 0 || !ZgChatLockSession.hidesContentGlobally(account, dialogId)) {
                return true;
            }
            final ZgChatLockSession session = ZgChatLockSession.getInstance();
            ProfileWatch watch = profileWatches.get(profile);
            if (watch == null || watch.key.dialogId != dialogId) {
                if (watch != null) {
                    session.removeVisibilityListener(watch.key, watch);
                }
                watch = new ProfileWatch(profile, ZgChatLockSession.Key.of(account, dialogId));
                profileWatches.put(profile, watch);
                session.addVisibilityListener(watch.key, watch);
            }
            watch.visible = session.isContentVisible(account, dialogId);
            return watch.visible;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private static final class ProfileWatch implements ZgChatLockSession.VisibilityListener {
        final WeakReference<ProfileActivity> profile;
        final ZgChatLockSession.Key key;
        boolean visible;

        ProfileWatch(ProfileActivity profile, ZgChatLockSession.Key key) {
            this.profile = new WeakReference<>(profile);
            this.key = key;
        }

        @Override
        public void onVisibilityChanged(boolean nowVisible) {
            if (nowVisible == visible) {
                return;
            }
            visible = nowVisible;
            AndroidUtilities.runOnUIThread(() -> {
                final ProfileActivity fragment = profile.get();
                if (fragment != null && !fragment.isFinished && fragment.fragmentView != null) {
                    try {
                        fragment.updateListAnimated(false);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
            });
        }
    }

    /** Whether {@code fragment} is behind its Chat Lock cover now (ChatActivity read-receipt and input guards). */
    public static boolean isCovered(BaseFragment fragment) {
        final ZgChatLockGate gate = fragment != null ? gates.get(fragment) : null;
        return gate != null && gate.covered;
    }

    /** ChatActivityFragmentView.dispatchTouchEvent: while covered every touch goes to the cover only. */
    public static boolean dispatchCoveredTouch(BaseFragment fragment, MotionEvent event) {
        final ZgChatLockGate gate = gates.get(fragment);
        if (gate == null || !gate.covered || gate.cover == null || gate.cover.getParent() == null) {
            return false;
        }
        final ZgChatLockCoverView cover = gate.cover;
        final float dx = -cover.getLeft() - cover.getTranslationX();
        final float dy = -cover.getTop() - cover.getTranslationY();
        event.offsetLocation(dx, dy);
        try {
            cover.dispatchTouchEvent(event);
        } finally {
            event.offsetLocation(-dx, -dy);
        }
        return true;
    }

    /** ChatActivityFragmentView.dispatchDraw: while covered only the cover is drawn (no scrim or overlay above it). */
    public static boolean dispatchCoveredDraw(BaseFragment fragment, Canvas canvas) {
        final ZgChatLockGate gate = gates.get(fragment);
        if (gate == null || !gate.covered || gate.cover == null || gate.cover.getParent() == null || !gate.cover.isOpaqueNow()) {
            return false;
        }
        final ZgChatLockCoverView cover = gate.cover;
        canvas.save();
        canvas.translate(cover.getLeft() + cover.getTranslationX(), cover.getTop() + cover.getTranslationY());
        cover.draw(canvas);
        canvas.restore();
        return true;
    }

    /** ChatActivityFragmentView.isFullSizeIgnoreInsetsChild: the cover is measured full size and laid out at 0, 0. */
    public static boolean isCoverView(View child) {
        return child instanceof ZgChatLockCoverView;
    }

    // ---------------------------------------------------------------------------------------------------------
    // From the session
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Relock: keep each relocked host covered in place, remove every fragment above the lowest relocked host of each
     * stack, close galleries / viewers / menus / dialogs / sheets and pause playback of protected chats.
     */
    static void onRelock(Set<ZgChatLockSession.Key> revoked) {
        if (revoked.isEmpty()) {
            // Nothing was unlocked, but playback or a view-once photo of a locked / hidden chat may still run (opened
            // before, from an open vault): it stops too.
            ZgProtectedOverlays.close(ZgProtectedOverlays.PROTECTED, false);
            return;
        }
        final HashMap<INavigationLayout, Integer> lowest = new HashMap<>();
        for (Map.Entry<BaseFragment, ZgChatLockGate> entry : new ArrayList<>(gates.entrySet())) {
            final BaseFragment fragment = entry.getKey();
            final ZgChatLockGate gate = entry.getValue();
            if (fragment == null || gate == null || !gate.covered || !revoked.contains(gate.key)) {
                continue;
            }
            gate.onCovered();
            if (gate.style == ZgChatLockCoverView.STYLE_PREVIEW) {
                fragment.finishPreviewFragment();
                continue;
            }
            final INavigationLayout layout = fragment.getParentLayout();
            if (layout == null) {
                continue;
            }
            final int index = layout.getFragmentStack().indexOf(fragment);
            if (index < 0) {
                continue;
            }
            final Integer current = lowest.get(layout);
            if (current == null || index < current) {
                lowest.put(layout, index);
            }
        }
        for (Map.Entry<INavigationLayout, Integer> entry : lowest.entrySet()) {
            evictAbove(entry.getKey(), entry.getValue(), 0);
        }
        // Something was unlocked: the gallery and article viewer close whatever they show; the view-once viewer and
        // playback of locked / hidden chats close and stop.
        ZgProtectedOverlays.close(ZgProtectedOverlays.PROTECTED, true);
    }

    /** Back from the background: the visible covered chat asks again (the presenter waits for the app passcode). */
    static void onAppForeground() {
        for (Map.Entry<BaseFragment, ZgChatLockGate> entry : new ArrayList<>(gates.entrySet())) {
            final BaseFragment fragment = entry.getKey();
            final ZgChatLockGate gate = entry.getValue();
            if (fragment != null && gate != null && gate.covered && gate.style == ZgChatLockCoverView.STYLE_INTERACTIVE
                && fragment.isLastFragment() && !fragment.isInPreviewMode()) {
                gate.prompt(ZgChatLockSession.REASON_RESUME);
                return;
            }
        }
    }

    /**
     * Chats just locked: open screens of them get a gate (they had none) and, unless they are peeks, an implicit held
     * grant, so the owner is never covered in the face. Returns the keys to grant.
     */
    static List<ZgChatLockSession.Key> implicitGrantKeys(long[] added) {
        final ArrayList<ZgChatLockSession.Key> keys = new ArrayList<>();
        final long[] sorted = added.clone();
        Arrays.sort(sorted);
        for (BaseFragment fragment : new ArrayList<>(candidates.keySet())) {
            if (fragment == null || fragment.isFinished) {
                continue;
            }
            final long dialogId = resolveDialogId(fragment);
            if (dialogId == 0 || Arrays.binarySearch(sorted, dialogId) < 0) {
                continue;
            }
            ZgChatLockGate gate = gates.get(fragment);
            if (gate == null) {
                gate = new ZgChatLockGate(fragment, dialogId);
                gates.put(fragment, gate);
            }
            if (!fragment.isInPreviewMode() && gate.isHostInStack()) {
                keys.add(gate.key);
            }
        }
        return keys;
    }

    /** Lock set changed (after the session updated grants): attach covers that are missing, refresh styles. */
    static void onLocksChanged() {
        for (Map.Entry<BaseFragment, ZgChatLockGate> entry : new ArrayList<>(gates.entrySet())) {
            final BaseFragment fragment = entry.getKey();
            final ZgChatLockGate gate = entry.getValue();
            if (fragment == null || gate == null || gate.destroyed) {
                continue;
            }
            if (gate.cover == null && fragment.fragmentView != null) {
                gate.attachView(fragment.fragmentView);
            } else {
                gate.applyVisibility(false);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // One gate
    // ---------------------------------------------------------------------------------------------------------

    private void attachView(View view) {
        final BaseFragment fragment = host.get();
        if (fragment == null) {
            return;
        }
        refreshStyle();
        if (cover != null && cover.getParent() instanceof ViewGroup) {
            ((ViewGroup) cover.getParent()).removeView(cover);
        }
        cover = null;
        final boolean visible = ZgChatLockSession.getInstance().isContentVisible(account, dialogId);
        if (!(view instanceof FrameLayout)) {
            covered = !visible;
            if (covered) {
                // Nothing to cover with: fail closed.
                FileLog.e("ZgChatLockGate: host view is not a FrameLayout, closing " + fragment.getClass().getSimpleName());
                AndroidUtilities.runOnUIThread(this::closeHost);
            }
            return;
        }
        cover = new ZgChatLockCoverView(view.getContext(), fragment.getResourceProvider(), this);
        cover.setStyle(style);
        cover.setChatTitle(ZgChatLockFlows.displayName(account, dialogId));
        cover.setOccupyStatusBar(!fragment.isInPreviewMode() && style == ZgChatLockCoverView.STYLE_INTERACTIVE);
        ((FrameLayout) view).addView(cover, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.BOTTOM | Gravity.LEFT));
        covered = false;
        if (visible) {
            cover.setVisibility(View.GONE);
        } else {
            covered = true;
            cover.showNow();
        }
    }

    /** Re-applies the session state: cover when not visible, uncover when visible. */
    private void applyVisibility(boolean animated) {
        if (destroyed) {
            return;
        }
        final boolean visible = ZgChatLockSession.getInstance().isContentVisible(account, dialogId);
        if (visible && covered) {
            uncover(animated);
        } else if (!visible && !covered) {
            coverNow();
        }
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        if (destroyed) {
            return;
        }
        if (visible && covered) {
            uncover(true);
        } else if (!visible && !covered) {
            coverNow();
        }
    }

    private void coverNow() {
        final BaseFragment fragment = host.get();
        if (fragment == null) {
            return;
        }
        if (cover == null) {
            if (fragment.fragmentView != null) {
                attachView(fragment.fragmentView);
            } else {
                covered = true;
            }
            onCovered();
            return;
        }
        covered = true;
        cover.showNow();
        onCovered();
        if (fragment.fragmentView != null) {
            fragment.fragmentView.invalidate();
        }
    }

    private void uncover(boolean animated) {
        covered = false;
        if (cover != null) {
            cover.hide(animated);
        }
        final BaseFragment fragment = host.get();
        if (fragment == null) {
            return;
        }
        if (fragment.fragmentView != null) {
            fragment.fragmentView.invalidate();
        }
        if (fragment instanceof ChatActivity) {
            final ChatActivity chat = (ChatActivity) fragment;
            try {
                chat.updateMessagesVisiblePart(false);
                if (!chat.isPaused() && !chat.isInPreviewMode()) {
                    MediaController.getInstance().startRaiseToEarSensors(chat);
                    if (chat.getChatMode() == 0) {
                        // Covered, onResume did not mark the chat as open: its notifications kept coming meanwhile.
                        org.telegram.messenger.NotificationsController.getInstance(chat.getCurrentAccount()).setOpenedDialogId(chat.getDialogId(), chat.getTopicId());
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /** Things a covered host must not keep showing or doing. */
    private void onCovered() {
        final BaseFragment fragment = host.get();
        if (fragment == null) {
            return;
        }
        try {
            fragment.dismissCurrentDialog();
            fragment.clearSheets();
            if (fragment.fragmentView != null) {
                final View focus = fragment.fragmentView.findFocus();
                if (focus != null) {
                    AndroidUtilities.hideKeyboard(focus);
                    focus.clearFocus();
                }
            }
            if (fragment instanceof ChatActivity) {
                final ChatActivity chat = (ChatActivity) fragment;
                MediaController.getInstance().stopRaiseToEarSensors(chat, false, false);
                chat.closeMenu();
                if (!chat.isPaused() && chat.getChatMode() == 0) {
                    // A covered chat is not "open": its new messages notify again (ChatActivity.onResume skips it too).
                    org.telegram.messenger.NotificationsController.getInstance(chat.getCurrentAccount()).setOpenedDialogId(0, 0);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void prompt(int reason) {
        final BaseFragment fragment = host.get();
        if (destroyed || fragment == null || !covered || promptRequested || style == ZgChatLockCoverView.STYLE_PREVIEW) {
            return;
        }
        promptRequested = true;
        ZgChatLockSession.getInstance().requireUnlock(account, dialogId, reason, fragment, result -> {
            promptRequested = false;
            if (destroyed) {
                return;
            }
            if (result == ZgChatLockSession.RESULT_UNLOCKED) {
                applyVisibility(true);
            } else if (result == ZgChatLockSession.RESULT_CANCELLED) {
                closeHost();
            }
            // INTERRUPTED: stay covered; Unlock asks again.
        });
    }

    /** Closes the host and everything above it (cancel on its PIN screen, Close on the cover). */
    private void closeHost() {
        final BaseFragment fragment = host.get();
        if (fragment == null || fragment.isFinished || style == ZgChatLockCoverView.STYLE_PASSIVE) {
            // An embedded host stays covered: closing it would close the screen that embeds it.
            return;
        }
        try {
            if (fragment.isInPreviewMode()) {
                fragment.finishPreviewFragment();
                return;
            }
            final INavigationLayout layout = fragment.getParentLayout();
            if (layout != null) {
                final int index = layout.getFragmentStack().indexOf(fragment);
                if (index >= 0) {
                    evictAbove(layout, index, 0);
                }
            }
            fragment.finishFragment();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void refreshStyle() {
        final BaseFragment fragment = host.get();
        if (fragment == null) {
            return;
        }
        final int newStyle = styleFor(fragment);
        if (newStyle != style) {
            style = newStyle;
            if (cover != null) {
                cover.setStyle(style);
                cover.setOccupyStatusBar(!fragment.isInPreviewMode() && style == ZgChatLockCoverView.STYLE_INTERACTIVE);
            }
        }
    }

    private boolean isHostInStack() {
        final BaseFragment fragment = host.get();
        if (fragment == null || fragment.isFinished || destroyed) {
            return false;
        }
        final INavigationLayout layout = fragment.getParentLayout();
        if (layout != null && layout.getFragmentStack().contains(fragment)) {
            return true;
        }
        // Embedded hosts (an inline forum, a chat inside a container) count while their view is on screen.
        return fragment.fragmentView != null && fragment.fragmentView.isAttachedToWindow();
    }

    private void dispose(BaseFragment fragment) {
        destroyed = true;
        final ZgChatLockSession session = ZgChatLockSession.getInstance();
        session.removeVisibilityListener(key, this);
        session.release(key, holderToken);
        holderToken = null;
        session.presenter.hostDestroyed(fragment);
        if (cover != null) {
            cover.hide(false);
        }
    }

    // ZgChatLockCoverView.Delegate

    @Override
    public void onUnlockClick() {
        prompt(ZgChatLockSession.REASON_EXPLICIT);
    }

    @Override
    public void onCloseClick() {
        closeHost();
    }

    @Override
    public void onPreviewClick() {
        final BaseFragment fragment = host.get();
        if (fragment != null && fragment.isInPreviewMode() && fragment.getParentLayout() != null) {
            try {
                fragment.getParentLayout().expandPreviewFragment();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** The dialog a host shows that needs protection, or 0. */
    private static long resolveDialogId(BaseFragment fragment) {
        final int account = fragment.getCurrentAccount();
        if (fragment instanceof ChatActivity) {
            final ChatActivity chat = (ChatActivity) fragment;
            final long dialogId = chat.getDialogId();
            if (dialogId == 0 || dialogId == UserConfig.getInstance(account).getClientUserId()) {
                return 0;
            }
            if (ZgChatLockSession.hidesContentGlobally(account, dialogId)) {
                return dialogId;
            }
            final long merged = chat.getMergeDialogId();
            if (merged != 0 && ZgChatLockSession.isProtected(account, merged)) {
                return merged;
            }
            return 0;
        }
        if (fragment instanceof TopicsFragment) {
            final long dialogId = ((TopicsFragment) fragment).getDialogId();
            return dialogId != 0 && ZgChatLockSession.hidesContentGlobally(account, dialogId) ? dialogId : 0;
        }
        if (fragment instanceof MediaActivity) {
            // Shared media only (not the stories screens, which also take a dialog id).
            final android.os.Bundle args = fragment.getArguments();
            if (args != null && args.getInt("type", MediaActivity.TYPE_MEDIA) != MediaActivity.TYPE_MEDIA) {
                return 0;
            }
            final long dialogId = ((MediaActivity) fragment).getDialogId();
            if (dialogId == 0 || dialogId == UserConfig.getInstance(account).getClientUserId()) {
                return 0;
            }
            return ZgChatLockSession.hidesContentGlobally(account, dialogId) ? dialogId : 0;
        }
        return 0;
    }

    private static int styleFor(BaseFragment fragment) {
        if (fragment.isInPreviewMode()) {
            return ZgChatLockCoverView.STYLE_PREVIEW;
        }
        // Embedded hosts: a chat inside a container (profile tabs, hashtag search), a forum inline next to the chat list.
        // (Not "missing from the stack": presentFragment creates the view before it adds the fragment.)
        if (fragment instanceof ChatActivity && ((ChatActivity) fragment).isInsideContainer
            || fragment instanceof TopicsFragment && ((TopicsFragment) fragment).parentDialogsActivity != null) {
            return ZgChatLockCoverView.STYLE_PASSIVE;
        }
        return ZgChatLockCoverView.STYLE_INTERACTIVE;
    }

    /** Removes every fragment above {@code index} (the top one without animation). Retries while a transition runs. */
    private static void evictAbove(INavigationLayout layout, int index, int attempt) {
        try {
            if (layout.isTransitionAnimationInProgress()) {
                if (attempt < 5) {
                    AndroidUtilities.runOnUIThread(() -> evictAbove(layout, index, attempt + 1), 250);
                }
                return;
            }
            final List<BaseFragment> stack = new ArrayList<>(layout.getFragmentStack());
            for (int i = stack.size() - 1; i > index; i--) {
                final BaseFragment above = stack.get(i);
                if (above != null && !above.isFinished) {
                    layout.removeFragmentFromStack(above, true);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
