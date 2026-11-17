/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRepository;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ViewPagerActivity;

/**
 * The Undo of a task deleted from the Cloud task editor. As on iOS the editor closes first and the delete is held: it
 * is sent only once the Undo goes away. On iOS the board shows that toast; here the editor has no way to ask the board,
 * so it shows the bulletin itself, on the screen that is in front after the editor closed, in that screen's own
 * bulletin place (the board keeps it above its composer).
 *
 * <p>The repository promotes a held delete by itself when its hold runs out or its last owner leaves, so a bulletin
 * that cannot be shown loses nothing: the delete is then simply sent.
 */
final class ZgCloudHeldDelete {

    /** The bulletin ends this long before the hold does, so a last-moment Undo still finds the delete held. */
    private static final long MARGIN_MS = 500;
    /** Not worth showing for less. */
    private static final long MIN_SHOWN_MS = 1200;

    private ZgCloudHeldDelete() {
    }

    /** When a hold taken now runs out, on the {@link SystemClock#elapsedRealtime} clock. */
    static long deadline() {
        return SystemClock.elapsedRealtime() + TasksCloudRepository.HELD_DELETE_TIMEOUT_MS;
    }

    /**
     * Offers Undo for {@code held} until {@code deadline}. Call once the editor has left the stack. Without a screen to
     * show it on, or with too little time left, the delete is released at once.
     */
    static void offerUndo(TasksCloudRepository.HeldDelete held, long deadline) {
        final long left = deadline - SystemClock.elapsedRealtime() - MARGIN_MS;
        final BulletinFactory factory = left < MIN_SHOWN_MS ? null : front();
        if (factory == null) {
            held.dismiss();
            return;
        }
        final Bulletin bulletin = factory.createUndoBulletin(LocaleController.getString(R.string.ZG_tasks_taskDeleted),
            () -> held.undo((ignored, error) -> {
                if (error != null) {
                    // The hold had already ended: the delete was sent, the task cannot come back from here.
                    BulletinFactory.global().createErrorBulletin(ZgCloudText.error(error)).show();
                }
            }),
            held::dismiss);
        bulletin.setDuration((int) left);
        bulletin.show();
    }

    /** Says {@code text} on the screen that is in front now (the editor closed because its task went away). */
    static void notice(CharSequence text) {
        final BulletinFactory factory = front();
        if (factory != null) {
            factory.createSimpleBulletin(R.raw.chats_infotip, text, 4, Bulletin.DURATION_LONG).show();
        }
    }

    /** Bulletins of the screen in front, in that screen's own place; null when there is none. */
    private static BulletinFactory front() {
        final BaseFragment top = LaunchActivity.getSafeLastFragment();
        if (top == null || top.getParentActivity() == null || top.getFragmentView() == null) {
            return null;
        }
        // The main tabs host the board as a page: the bulletin belongs to the page, above its own controls.
        BaseFragment screen = top instanceof ViewPagerActivity ? ((ViewPagerActivity) top).getCurrentVisibleFragment() : top;
        if (screen == null || screen.getFragmentView() == null) {
            screen = top;
        }
        final View view = screen.getFragmentView();
        return view instanceof FrameLayout ? BulletinFactory.of((FrameLayout) view, screen.getResourceProvider()) : BulletinFactory.of(top);
    }
}
