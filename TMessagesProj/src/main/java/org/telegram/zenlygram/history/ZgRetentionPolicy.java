/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

/**
 * The keep rules of Show Deleted Messages and Save Edit History (iOS sgShouldRetain, SGDeletionReplayContext and
 * sgCaptureEditHistory), as plain Java over plain facts so they can be tested on the JVM.
 *
 * <p>Telegram does not say who deleted a message, so only incoming messages of other people are kept, never in chats
 * where the user can delete everyone's messages, never for service or self-destructing content, and never when one
 * batch looks like a history clear (DMs and basic groups: any outgoing message of the chat, or 50+ of its messages).
 */
public final class ZgRetentionPolicy {

    private ZgRetentionPolicy() {
    }

    /** What the keep decision needs to know about one stored message whose deletion arrived. */
    public static final class Candidate {
        /** messages_v2.uid: the dialog id (user id, -chat id, -channel id). */
        public long uid;
        public int mid;
        /** Secret chat dialog. */
        public boolean encrypted;
        /** Sent by this account (the out flag), from any device. */
        public boolean outgoing;
        /** from_id.user_id, 0 if the sender is not a user. */
        public long fromUserId;
        /** TL_messageService (joins, pins, calls, ...). */
        public boolean service;
        /** TL_messageEmpty or an unreadable row. */
        public boolean empty;
        /** Auto-delete timer of the message (ttl_period). */
        public int ttlPeriod;
        /** View-once / self-destructing photo or video (MessageObject.isSecretMedia). */
        public boolean secretMedia;
        /** The user may delete everyone's messages here (channel creator / admin with delete rights, basic-group admin). */
        public boolean canDeleteOthers;
        /** The batch is a history clear for this chat (see {@link #isClearLike}). */
        public boolean clearLike;
    }

    /** Chats whose messages are never kept: Saved Messages, Replies, Verification Codes and Telegram's service accounts. */
    public static boolean isExcludedPeer(long uid, long selfId) {
        return uid == 0
            || uid == selfId
            || uid == 708513L          // Replies (old)
            || uid == 1271266957L      // Replies (UserObject.REPLY_BOT)
            || uid == 489000L          // Verification Codes (UserObject.VERIFY)
            || uid == 333000L          // service accounts (UserObject.isService)
            || uid == 777000L
            || uid == 42777L;
    }

    /** Whether the copy of this deleted message is kept. The caller checks the user's switch and Pro first. */
    public static boolean shouldRetain(Candidate c, long selfId) {
        if (c == null || c.mid <= 0 || c.encrypted || c.empty || c.service) {
            return false;
        }
        if (isExcludedPeer(c.uid, selfId)) {
            return false;
        }
        if (c.outgoing || selfId != 0 && c.fromUserId == selfId) {
            return false;
        }
        if (c.ttlPeriod != 0 || c.secretMedia) {
            return false;
        }
        if (c.canDeleteOthers) {
            return false;
        }
        return !c.clearLike;
    }

    /**
     * The clear-like rule applies to private chats and basic groups only (dialog id of a user or of a basic group);
     * channels and supergroups have no bulk rule, only the per-account cap.
     */
    public static boolean isClearLikeCandidate(long uid, boolean channel, boolean encrypted) {
        return uid != 0 && !channel && !encrypted;
    }

    /** One batch that removes an outgoing message of the chat, or 50+ of its local messages, is a history clear. */
    public static boolean isClearLike(boolean hasOutgoing, int localCount) {
        return hasOutgoing || localCount >= ZgHistorySettings.CLEAR_LIKE_THRESHOLD;
    }

    /**
     * Whether an edit is recorded: the text changed, or the photo / document changed. Webpage previews, locations,
     * polls, paid media and other media without an id have key 0, so link-preview loads, live-location ticks and
     * paid-media reveals are not recorded.
     */
    public static boolean editChanged(String oldText, long oldMediaKey, String newText, long newMediaKey) {
        final String a = oldText == null ? "" : oldText;
        final String b = newText == null ? "" : newText;
        return !a.equals(b) || oldMediaKey != newMediaKey;
    }

    /** The date of a previous version: its edit date if it was already edited, else its send date. */
    public static int versionDate(boolean wasEdited, int editDate, int date) {
        return wasEdited && editDate != 0 ? editDate : date;
    }

    /** Whether a revision captured at {@code capturedAt} is still shown, given the visible cutoff. */
    public static boolean isRevisionVisible(int capturedAt, int cutoff) {
        return capturedAt != 0 && capturedAt > cutoff;
    }
}
