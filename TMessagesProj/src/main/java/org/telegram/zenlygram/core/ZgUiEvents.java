/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.core;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;

/**
 * One global event for Zenlygram settings that open screens apply live (Hide Stories, the "All Chats" title, Show
 * Contacts Tab).
 *
 * <p>The id is in Zenlygram's own block (0x5A47xxxx, like ZgTasksPrefs.tabVisibilityChanged): NotificationCenter keeps
 * observers in SparseArrays, so any int works and NotificationCenter.java needs no new event.
 */
public final class ZgUiEvents {

    /** Posted on {@code NotificationCenter.getGlobalInstance()}, UI thread; args[0] = the ZgSettings key that changed. */
    public static final int settingsChanged = 0x5A470301;

    private ZgUiEvents() {
    }

    public static void post(String key) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(settingsChanged, key));
    }
}
