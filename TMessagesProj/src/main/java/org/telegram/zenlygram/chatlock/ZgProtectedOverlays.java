/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import androidx.annotation.MainThread;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ArticleViewer;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.SecretMediaViewer;

/**
 * Closes what can keep a protected chat's content on screen or audible outside the chat screen itself, when Chat Lock
 * relocks or a Secret Vault closes. Fragments are evicted by their own owners; this covers the separate windows and the
 * player:
 * <ul>
 *   <li>PhotoViewer (its own window, above every fragment) when it shows a matching chat's media, or always with
 *       {@code closeViewers} (the app went to the background while something was unlocked);</li>
 *   <li>the view-once viewer of a matching chat (closing it destroys the media, so only for matching chats);</li>
 *   <li>ArticleViewer, only with {@code closeViewers} (it does not tell which chat it was opened from);</li>
 *   <li>playback of a matching chat: stopped for good (MediaController.cleanupPlayer), so the player bar, the music
 *       sheet (AudioPlayerAlert dismisses itself), the round-video bubble and the media notification / lock-screen
 *       controls go with it, and nobody can press Play to hear it again.</li>
 * </ul>
 * Main thread. Never deletes anything.
 */
public final class ZgProtectedOverlays {

    public interface Matcher {
        /** Whether the content of {@code dialogId} in account slot {@code account} must not stay on screen or play. */
        boolean matches(int account, long dialogId);
    }

    /** Chat-Locked or vault-hidden chats, whatever the session state (the matcher of a relock). */
    public static final Matcher PROTECTED = (account, dialogId) -> dialogId != 0 && ZgChatLockSession.hidesContentGlobally(account, dialogId);

    private ZgProtectedOverlays() {
    }

    @MainThread
    public static void close(Matcher matcher, boolean closeViewers) {
        try {
            if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
                final PhotoViewer viewer = PhotoViewer.getInstance();
                if (closeViewers || matcher.matches(viewer.zgGetShownAccount(), viewer.zgGetShownDialogId())) {
                    viewer.closePhoto(false, true);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            if (SecretMediaViewer.hasInstance() && SecretMediaViewer.getInstance().isVisible()) {
                final MessageObject message = SecretMediaViewer.getInstance().getCurrentMessageObject();
                if (message != null && matcher.matches(message.currentAccount, message.getDialogId())) {
                    SecretMediaViewer.getInstance().closePhoto(false, false);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (closeViewers) {
            try {
                if (ArticleViewer.hasInstance() && ArticleViewer.getInstance().isVisible()) {
                    ArticleViewer.getInstance().close(false, true);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        stopPlayback(matcher);
    }

    /**
     * Stops playback of a matching chat. A voice message that interrupted music resumes that music on cleanup
     * (MediaController.restoreMusicPlaylistState), so the check repeats a few times.
     */
    @MainThread
    public static void stopPlayback(Matcher matcher) {
        try {
            final MediaController controller = MediaController.getInstance();
            for (int i = 0; i < 3; i++) {
                final MessageObject playing = controller.getPlayingMessageObject();
                if (playing == null || !matcher.matches(playing.currentAccount, playing.getDialogId())) {
                    return;
                }
                controller.cleanupPlayer(true, true);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
