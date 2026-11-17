/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlist;

import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * Settings > Zenlygram > Stories, read by the upstream hooks (marked ZENLYGRAM). Both values are read live.
 * A feature that is not ported always gives the stock answer.
 */
public final class ZgStories {

    private ZgStories() {
    }

    /**
     * Hide Stories: no stories row and no story camera button in the chat list (main list and archive), like iOS.
     * Story rings on avatars, profile stories and the story viewer stay.
     */
    public static boolean hideStories() {
        return ZgFeatures.HIDE_STORIES.isPorted() && ZgSettings.hideStories.get();
    }

    /** Repost to Story: false removes the first "Repost to Story" cell of share sheets and of the system share picker. */
    public static boolean showRepostToStory() {
        return !ZgFeatures.REPOST_TO_STORY.isPorted() || ZgSettings.showRepostToStoryV2.get();
    }
}
