/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.zenlygram.core.ZgFeatures;

/**
 * Notification redaction of locked chats (iOS ChatLockNotificationStrings + the NSE redaction). Thread-safe: called
 * on Telegram's notificationsQueue, including for FCM-only messages.
 *
 * <p>For a redacted dialog NotificationsController shows the hidden name (NotificationHiddenName /
 * NotificationHiddenChatName) and "Message in a locked chat": no sender, avatar, picture, voice, reply, mark-as-read,
 * copy-code or inline buttons, conversation shortcut, bubble or popup. Sound, vibration and channel are unchanged, and
 * a tap opens the chat behind its gate. Hiding previews is a privacy setting: never Pro-gated.
 */
public final class ZgChatLockNotifications {

    private ZgChatLockNotifications() {
    }

    /** Any thread: whether notifications of {@code dialogId} in {@code account} must hide everything. */
    public static boolean redacts(int account, long dialogId) {
        if (!ZgFeatures.CHAT_LOCK.isPorted()) {
            return false;
        }
        if (ZgChatLockSession.hidesContentGlobally(account, dialogId)) {
            return true;
        }
        final ZgProtectionLayer layer = ZgChatLockSession.getProtectionLayer();
        try {
            return layer != null && layer.failsClosed();
        } catch (Throwable e) {
            return true;
        }
    }

    /** The body of a redacted notification line. */
    public static String redactedText() {
        return LocaleController.getString(R.string.ZG_ChatLock_Notification_RedactedText);
    }
}
