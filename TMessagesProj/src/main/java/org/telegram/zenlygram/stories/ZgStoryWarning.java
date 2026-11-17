/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.stories;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stories.PeerStoriesView;
import org.telegram.ui.Stories.StoriesController;
import org.telegram.ui.Stories.StoryViewer;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

import java.util.HashSet;
import java.util.WeakHashMap;

/**
 * Ask Before Viewing (iOS warnOnStoriesOpen, SGStoryWarningComponent): before a story's author can learn that you
 * viewed it, the viewer pauses on "View Story?" — "Name WILL BE ABLE TO SEE that you viewed their Story." (or "will
 * not be able to see" while Telegram's stealth mode is on). View Story plays it; Close or Back closes the viewer.
 *
 * <p>Telegram for Android sends story views from one place only, PeerStoriesView.StoryItemHolder.checkSendView()
 * (stories.readStories / stories.incrementStoryViews), called on every drawn frame, even while paused. So an overlay
 * alone would not stop the view: the hook there holds it ({@link #holdView}) until the user agrees. A live story's
 * viewer joins the stream (phone.joinGroupCall) as soon as its player is requested, before any "view": that request
 * is held too ({@link #holdLive}). Consent is per author for one opening of the viewer ({@link #onOpen} forgets it);
 * moving on to another author asks again. Own stories and stories of channels you can edit are never held.
 *
 * <p>Hooks (marked ZENLYGRAM): PeerStoriesView.StoryItemHolder.checkSendView, PeerStoriesView.requestVideoPlayer
 * (live branch), StoryViewer.open. The dialog goes through StoryViewer.showDialog, which pauses playback while it is
 * shown (the viewer's own report and delete dialogs work the same way, on phones and tablets). UI thread only.
 */
public final class ZgStoryWarning {

    private static final class Session {
        /** Authors the user agreed to in this opening of the viewer. */
        final HashSet<Long> allowed = new HashSet<>();
        /** The author asked about right now (0 = no question pending or on screen). */
        long asking;
        /** Whether the live player request of {@link #asking} was held and must be repeated after consent. */
        boolean liveHeld;
        /** The question on screen for {@link #asking} (null while it is posted but not shown yet). */
        AlertDialog dialog;
        /** Questions that went away without an answer (dismissed from outside, see {@link #hold}). */
        int lostQuestions;
    }

    private static final WeakHashMap<StoryViewer, Session> sessions = new WeakHashMap<>();

    private ZgStoryWarning() {
    }

    public static boolean isEnabled() {
        return ZgFeatures.STORY_VIEW_WARNING.isPorted() && ZgSettings.warnOnStoriesOpen.get();
    }

    /** StoryViewer.open: a new opening starts without any consent. */
    public static void onOpen(StoryViewer viewer) {
        if (viewer != null) {
            sessions.remove(viewer);
        }
    }

    /**
     * checkSendView: true = do not send the view now (the question is pending or on screen). Called every frame; the
     * switched-off path costs two volatile reads.
     */
    public static boolean holdView(StoryViewer viewer, PeerStoriesView page, int account, long dialogId, boolean active, boolean self, TL_stories.StoryItem storyItem, Theme.ResourcesProvider resourcesProvider) {
        if (!isEnabled() || !active || storyItem == null || storyItem instanceof StoriesController.BotPreview) {
            // Nothing is sent for an inactive page, an upload, or a bot's preview media (BotPreviewsList.markAsRead)
            return false;
        }
        try {
            return hold(viewer, page, account, dialogId, self, resourcesProvider, false);
        } catch (Throwable e) {
            FileLog.e(e);
            // Never send a view because of an error here
            return true;
        }
    }

    /** requestVideoPlayer, live story: true = do not join the stream now; it is requested again after consent. */
    public static boolean holdLive(StoryViewer viewer, PeerStoriesView page, int account, long dialogId, boolean self, Theme.ResourcesProvider resourcesProvider) {
        if (!isEnabled()) {
            return false;
        }
        try {
            return hold(viewer, page, account, dialogId, self, resourcesProvider, true);
        } catch (Throwable e) {
            FileLog.e(e);
            return true;
        }
    }

    private static boolean hold(StoryViewer viewer, PeerStoriesView page, int account, long dialogId, boolean self, Theme.ResourcesProvider resourcesProvider, boolean live) {
        if (viewer == null || page == null || dialogId == 0 || self) {
            return false;
        }
        if (dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        final StoriesController storiesController = MessagesController.getInstance(account).getStoriesController();
        if (dialogId < 0 && storiesController.canEditStories(dialogId)) {
            // Stories of your own channel
            return false;
        }
        Session session = sessions.get(viewer);
        if (session == null) {
            session = new Session();
            sessions.put(viewer, session);
        }
        if (session.allowed.contains(dialogId)) {
            return false;
        }
        if (!viewer.isShown()) {
            // Closing: nothing to ask, nothing to send
            return true;
        }
        if (live) {
            session.liveHeld = true;
        }
        if (session.asking == dialogId) {
            final AlertDialog shown = session.dialog;
            if (shown == null || shown.isShowing() && !shown.isDismissed()) {
                // Posted or on screen
                return true;
            }
            // Gone without an answer (StoryViewer.dismissVisibleDialogs): ask again, or leave if it keeps going away
            if (!shown.isDismissed()) {
                // Hidden but not dismissed: dismiss it outside the draw pass so StoryViewer.currentDialog is cleared
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        shown.dismiss();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
            }
            session.dialog = null;
            session.asking = 0;
            if (++session.lostQuestions > 2) {
                session.liveHeld = false;
                viewer.close(true);
                return true;
            }
        }
        ask(viewer, page, account, dialogId, resourcesProvider, session);
        return true;
    }

    /** Shows the question after the current draw pass. */
    private static void ask(StoryViewer viewer, PeerStoriesView page, int account, long dialogId, Theme.ResourcesProvider resourcesProvider, Session session) {
        session.asking = dialogId;
        AndroidUtilities.runOnUIThread(() -> {
            if (sessions.get(viewer) != session || session.asking != dialogId) {
                return;
            }
            if (!viewer.isShown() || page.getCurrentPeer() != dialogId) {
                // Closed, or moved on to another author meanwhile: the next frame asks for the right one
                session.asking = 0;
                session.liveHeld = false;
                return;
            }
            AlertDialog dialog = null;
            try {
                dialog = buildDialog(viewer, page, account, dialogId, resourcesProvider, session);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (dialog == null || !viewer.showDialog(dialog) || !dialog.isShowing()) {
                // No question means no consent: leave without sending anything. AlertDialog.show() returns quietly for
                // an activity that is not running; dismiss() then clears StoryViewer.currentDialog, which would
                // otherwise keep this viewer paused in its next opening too.
                if (dialog != null) {
                    try {
                        dialog.dismiss();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
                session.asking = 0;
                session.liveHeld = false;
                viewer.close(true);
                return;
            }
            session.dialog = dialog;
        });
    }

    private static AlertDialog buildDialog(StoryViewer viewer, PeerStoriesView page, int account, long dialogId, Theme.ResourcesProvider resourcesProvider, Session session) {
        String name = DialogObject.getName(account, dialogId);
        if (TextUtils.isEmpty(name)) {
            name = getString(R.string.ZG_Stories_Warning_Author);
        }
        final AlertDialog dialog = new AlertDialog.Builder(page.getContext(), resourcesProvider)
            .setTitle(getString(R.string.ZG_Stories_Warning_ViewStory))
            .setMessage(formatString(isStealthModeActive(account) ? R.string.ZG_Stories_Warning_NoticeStealth : R.string.ZG_Stories_Warning_Notice, name))
            .setPositiveButton(getString(R.string.ViewStory), (d, which) -> onAllowed(viewer, page, dialogId, session))
            .setNegativeButton(getString(R.string.Close), (d, which) -> onDeclined(viewer, session))
            // The story behind stays mostly hidden, close to the dark blur of iOS
            .setDimAlpha(0.9f)
            .create();
        // Back = Close; StoryViewer.showDialog owns the dismiss listener, so the cancel listener is used here
        dialog.setOnCancelListener(d -> onDeclined(viewer, session));
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    private static void onAllowed(StoryViewer viewer, PeerStoriesView page, long dialogId, Session session) {
        if (sessions.get(viewer) != session) {
            return;
        }
        session.allowed.add(dialogId);
        session.asking = 0;
        session.dialog = null;
        final boolean repeatLive = session.liveHeld;
        session.liveHeld = false;
        try {
            if (repeatLive && page.getCurrentPeer() == dialogId) {
                // updatePosition() requests the live player again (requestVideoPlayer)
                page.updatePosition();
            }
            viewer.updatePlayingMode();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void onDeclined(StoryViewer viewer, Session session) {
        if (sessions.get(viewer) != session || session.asking == 0) {
            return;
        }
        session.asking = 0;
        session.dialog = null;
        session.liveHeld = false;
        try {
            if (viewer.isShown()) {
                viewer.close(true);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean isStealthModeActive(int account) {
        final TL_stories.TL_storiesStealthMode stealthMode = MessagesController.getInstance(account).getStoriesController().getStealthMode();
        return stealthMode != null && ConnectionsManager.getInstance(account).getCurrentTime() < stealthMode.active_until_date;
    }
}
