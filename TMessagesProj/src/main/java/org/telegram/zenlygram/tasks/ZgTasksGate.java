/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks;

import androidx.annotation.UiThread;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.zenlygram.pro.ZgPro;
import org.telegram.zenlygram.pro.ZgProGate;

/**
 * The one CREATE gate of Tasks (iOS sgEntitlementGate(.createTask)). Only two paths ask it: the quick-add submit
 * (after the title check, before anything is written) and New List (before the name alert and again right before the
 * write). Opening, reading, editing, completing, deleting, Undo, renaming and deleting lists are never gated.
 *
 * <p>While Zenlygram Pro is free (this build) it always answers true and shows nothing.
 */
public final class ZgTasksGate {

    private ZgTasksGate() {
    }

    /** True: go ahead. False: stop and keep the user's draft (the Pro screen was opened if Pro is needed). */
    @UiThread
    public static boolean allowCreate(BaseFragment fragment) {
        return ZgProGate.check(fragment, ZgPro.GatedAction.CREATE_TASK);
    }
}
