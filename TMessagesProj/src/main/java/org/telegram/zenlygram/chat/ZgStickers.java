/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chat;

import org.telegram.messenger.MessageObject;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.history.ZgKeptDeleted;

/**
 * Stickers in the chat history (Settings > Zenlygram > Stickers), read by the ZENLYGRAM hooks in ChatMessageCell.
 *
 * <p>STICKER_SIZE scales the box Telegram fits a sticker into (setMessageContent), so stickers, animated emoji and
 * dice shrink to the chosen percentage of Telegram's own size; it never enlarges. STICKER_TIMESTAMP skips the time /
 * status block (drawTime) under stickers. Both are device-wide and allocation-free.
 */
public final class ZgStickers {

    /** The smallest sticker size. Below it stickers are unreadable, and 0 % would give 0x0 decode sizes. */
    public static final int MIN_SIZE_PERCENT = 20;

    private ZgStickers() {
    }

    /** STICKER_SIZE: the factor for Telegram's sticker bounds; 1 while the feature is off or at 100 %. */
    public static float sizeScale() {
        if (!ZgFeatures.STICKER_SIZE.isPorted()) {
            return 1f;
        }
        final int percent = Math.max(MIN_SIZE_PERCENT, Math.min(100, ZgSettings.stickerSize.get()));
        return percent >= 100 ? 1f : percent / 100f;
    }

    /**
     * STICKER_TIMESTAMP: whether the time block of this message is skipped. Only for sticker-like messages that are
     * sent: the clock of a message being sent, the error badge (needed to retry), the time of a scheduled message
     * (the schedule time) and the time of a kept copy of a deleted sticker (it carries the deletion mark, Show Deleted
     * Messages) stay visible.
     */
    public static boolean hideTime(MessageObject messageObject) {
        return ZgFeatures.STICKER_TIMESTAMP.isPorted() && !ZgSettings.stickerTimestamp.get()
            && messageObject != null && messageObject.isAnyKindOfSticker()
            && !messageObject.isSending() && !messageObject.isSendError() && !messageObject.isEditing()
            && !messageObject.scheduled && !ZgKeptDeleted.isKept(messageObject);
    }
}
