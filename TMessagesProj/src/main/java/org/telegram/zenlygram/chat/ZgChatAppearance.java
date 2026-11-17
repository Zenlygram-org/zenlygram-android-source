/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chat;

import android.view.View;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.time.FastDateFormat;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

import java.util.ArrayList;

/**
 * Message appearance switches of Settings > Zenlygram > Chats & messages, read by Telegram's chat code
 * (MessageObject, ChatMessageCell, ChatActivity) and the message menu (ZgMessageMenu). Every hook there is a one-line
 * call into this class.
 *
 * <ul>
 *   <li>Hide Reactions (iOS hideReactions): reactions under / inside bubbles are not drawn. Saved Messages tags
 *       (reactions used as tags) stay visible.</li>
 *   <li>Seconds in Messages (iOS secondsInMessages): the time inside chats shows seconds.</li>
 *   <li>Wide posts in channels (iOS wideChannelPosts): see {@link #isWideChannelPost(MessageObject)} and the
 *       geometry notes below.</li>
 *   <li>Channel Bottom Panel (iOS hideChannelBottomButton): the Mute / Unmute panel of a joined channel is hidden.</li>
 * </ul>
 *
 * <p>Message cells re-lay out after a change: the settings rows {@link #bump()} the {@link #generation}, bound
 * cells compare it in ChatMessageCell.setMessageContent and a chat in the back stack re-binds its rows in
 * ChatActivity.onResume. Text layouts kept in MessageObject are rebuilt by MessageObject.checkLayout, which compares
 * the Wide posts state they were made with, and albums are laid out again by {@link #prepare} (ChatActivity
 * .getValidGroupedMessage, which the chat list asks before it uses an album's grid spans). Channel Bottom Panel is
 * re-applied by ChatActivity.onResume (updateBottomOverlay).
 *
 * <h3>Wide posts geometry</h3>
 * Telegram Android draws an incoming bubble's body from 11 dp (the drawable starts at 3 dp, or 9 dp for media, and
 * its first 8 dp, or 2 dp, are the tail and padding) and ends an outgoing bubble's body 11 dp before the right edge.
 * A wide post mirrors that: its bubble drawable may end {@value #WIDE_BUBBLE_INSET_DP} dp before the right edge of
 * the chat (the drawable's last 2 dp are padding), whatever it shows, so text, link previews, photos, videos and
 * albums all share one right edge, 11 dp from the screen edge like the 11 dp on the left. iOS draws wide posts with
 * the same 3 + 6 pt margins (ChatMessageBubbleItemNode: maximumContentWidth = baseWidth - 3 x edgeInset - 6).
 * Only maximum widths change: a short post stays as narrow as its content, as on iOS. The margins below are those
 * insets plus each content's own chrome in Telegram's layout; Telegram's own deductions for an avatar (channels that
 * show author profiles) stay, and the share-button allowances go with the button.
 *
 * <p>Like Telegram, a phone lays text out against the screen width (minus 50 dp in landscape) and media against the
 * short screen side, and a tablet against its chat width in portrait, so a portrait photo never gets taller and line
 * lengths do not change with rotation. Albums keep Telegram's row heights and only grow wider.
 */
public final class ZgChatAppearance {

    /** A wide post's bubble drawable ends this far before the right edge of the chat (its body 11 dp, see above). */
    public static final int WIDE_BUBBLE_INSET_DP = 9;
    /**
     * Wide text posts and rich messages: the text is laid out this much narrower than Telegram's text width base.
     * A text bubble is the text plus 2 x 2 dp (ChatMessageCell.getExtraTextX at the default bubble radius) plus 31 dp,
     * so 9 + 4 + 31. Telegram: 80 dp (rich messages 40 dp), and 14 dp more next to a share button.
     */
    public static final int WIDE_TEXT_MARGIN_DP = 44;
    /** Wide link previews, games and invoices: their bubble is the preview width plus 31 dp, so 9 + 31 (Telegram 80, 100 with a share button). */
    public static final int WIDE_LINK_PREVIEW_MARGIN_DP = 40;
    /**
     * Wide photos, videos and GIFs: the bubble is the media width plus 8 + 9 dp and is drawn from 9 dp (from 3 dp
     * with a caption, then 6 dp wider), so 9 + 17; the photo keeps 13 dp on each side. Telegram: 64 dp, 74 dp next to
     * a share button.
     */
    public static final int WIDE_MEDIA_MARGIN_DP = 26;

    /** MessageObject.GroupedMessages.calculate: Telegram's album width in grid units (of 1000), and the avatar column. */
    private static final int ALBUM_UNITS = 800;
    private static final int ALBUM_GRID_UNITS = 1000;
    private static final int ALBUM_AVATAR_UNITS = 108;

    /** Changes whenever Hide Reactions, Seconds in Messages or Wide posts changes (UI thread only). */
    public static volatile int generation;

    private ZgChatAppearance() {
    }

    /** A switch of this class changed: bound message cells and open chats re-lay out. */
    public static void bump() {
        generation++;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hide Reactions
    // ---------------------------------------------------------------------------------------------------------

    /** Whether the reactions of this message (for an album: of its primary message) are hidden. */
    public static boolean hidesReactions(MessageObject message) {
        if (!ZgSettings.hideReactions.get() || message == null) {
            return false;
        }
        final TLRPC.Message owner = message.messageOwner;
        // Saved Messages tags are stored as reactions: they are the user's own labels, keep them. The dialog check
        // also covers the other messages of an album, whose tags belong to the album's primary message.
        if (owner == null || owner.reactions != null && owner.reactions.reactions_as_tags) {
            return false;
        }
        final int account = accountOf(message);
        return message.getDialogId() != UserConfig.getInstance(account).getClientUserId();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Seconds in Messages
    // ---------------------------------------------------------------------------------------------------------

    /** The formatter of the time inside chats: with seconds while Seconds in Messages is on. */
    public static FastDateFormat messageTimeFormat() {
        final LocaleController locale = LocaleController.getInstance();
        return ZgSettings.secondsInMessages.get() ? locale.getFormatterDayWithSeconds() : locale.getFormatterDay();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Wide posts in channels
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Whether this message is shown as a wide channel post (iOS ChatMessageBubbleItemNode renderWideChannelPosts):
     * a post of a broadcast channel, shown in the channel. Its text, link preview, rich message, poll, file, photo,
     * video, GIF and album may use the chat width, and it has no side buttons: MessageObject.needDrawShareButton is
     * false for it (as on iOS; Share stays in the message menu as Forward) and the AI summary button moves to the
     * message menu ({@link #summaryTarget}). Content with a fixed width (voice, music, location, contact, story,
     * giveaway) and paid media (GroupMedia sizes its height in pixels, so a wider one would only be cropped) keep
     * Telegram's size.
     *
     * <p>Not wide: the admin log, scheduled messages, search results, previews, sponsored messages, the pinned
     * messages list and chat previews (ChatActivity sets {@code MessageObject.zgWideOff} there through {@link #prepare}:
     * the list's side button is the way to a message, a preview is narrower than the screen), stickers, emoji and round videos (no bubble to widen; they keep their side share and
     * comments buttons, the iOS exception), compact mode and supergroups. While the switch is off this is false and
     * every hook keeps Telegram's own path.
     *
     * <p>Called on drawing paths too (through needDrawShareButton): no allocation, and the supergroup and compact
     * answers are cached by MessageObject.
     */
    public static boolean isWideChannelPost(MessageObject message) {
        if (!isWideEnabled() || message == null) {
            return false;
        }
        final TLRPC.Message owner = message.messageOwner;
        // Only broadcast channels have posts (a channel post forwarded elsewhere is not a post there), and a post is
        // never outgoing (MessageObject.isOutOwner), so its bubble is always on the left.
        if (owner == null || !owner.post || owner instanceof TLRPC.TL_messageService || owner.peer_id == null || owner.peer_id.channel_id == 0) {
            return false;
        }
        if (message.zgWideOff || message.eventId != 0 || message.scheduled || message.preview || message.previewForward
            || message.sendPreview || message.isRepostPreview || message.searchType != 0 || message.isSponsored()) {
            return false;
        }
        switch (message.type) {
            case MessageObject.TYPE_STICKER:
            case MessageObject.TYPE_ANIMATED_STICKER:
            case MessageObject.TYPE_EMOJIS:
            case MessageObject.TYPE_ROUND_VIDEO:
            case MessageObject.TYPE_JOINED_CHANNEL:
                return false;
            default:
                break;
        }
        return !message.isRoundVideo() && !message.isSupergroup() && !message.isCompactMode();
    }

    /** The Wide posts in channels switch is on (and the feature is in this build). */
    public static boolean isWideEnabled() {
        return ZgFeatures.WIDE_CHANNEL_POSTS.isPorted() && ZgSettings.wideChannelPosts.get();
    }

    /** Whether an album is laid out as a wide channel post (its messages are posts of one channel). */
    public static boolean isWideChannelGroup(ArrayList<MessageObject> messages) {
        return messages != null && !messages.isEmpty() && isWideChannelPost(messages.get(0));
    }

    /**
     * The width Telegram lays a chat message's text out against (MessageObject.getParentWidth for a message that is
     * not a preview): on tablets the chat width in portrait (a rich message: the current chat width), on a phone the
     * screen width, minus 50 dp in landscape.
     */
    public static int textParentWidth(MessageObject message) {
        if (AndroidUtilities.isTablet()) {
            return message != null && message.type == MessageObject.TYPE_ARTICLE ? MessageObject.getTabletChatWidth() : AndroidUtilities.getMinTabletSide();
        }
        if (AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
            return AndroidUtilities.displaySize.x - AndroidUtilities.dp(50);
        }
        return AndroidUtilities.displaySize.x;
    }

    /** The text width of a wide post laid out against {@code parentWidth} (MessageObject: generatedWithMinSize), before avatar deductions. */
    public static int wideTextWidth(int parentWidth) {
        return parentWidth - AndroidUtilities.dp(WIDE_TEXT_MARGIN_DP);
    }

    /** The text width of a wide post (ChatMessageCell), before avatar deductions: the same as MessageObject lays it out with. */
    public static int wideTextWidth(MessageObject message) {
        return wideTextWidth(textParentWidth(message));
    }

    /** The link preview width of a wide post (ChatMessageCell linkPreviewMaxWidth), before avatar deductions. */
    public static int wideLinkPreviewWidth(MessageObject message) {
        return textParentWidth(message) - AndroidUtilities.dp(WIDE_LINK_PREVIEW_MARGIN_DP);
    }

    /**
     * The width of a wide photo, video or GIF, before avatar deductions: from the side Telegram sizes media against
     * (the short screen side on a phone, the portrait chat width on tablets).
     */
    public static int wideMediaWidth() {
        return mediaSide() - AndroidUtilities.dp(WIDE_MEDIA_MARGIN_DP);
    }

    /**
     * Whether a single photo, video or GIF of a wide post takes {@link #wideMediaWidth()}: landscape and square ones,
     * the media Telegram already shows at almost full width ({@code size} is the photo size Telegram checks there).
     * Portrait media keep Telegram's size, without the share allowance, so they do not get taller; a caption still
     * widens them up to the text width (and crops them), as in Telegram and iOS.
     */
    public static boolean isWideMedia(MessageObject message, TLRPC.PhotoSize size) {
        if (size == null || size.w < size.h || message == null) {
            return false;
        }
        final int type = message.type;
        return (type == MessageObject.TYPE_PHOTO || type == MessageObject.TYPE_EXTENDED_MEDIA_PREVIEW || type == MessageObject.TYPE_VIDEO || type == MessageObject.TYPE_GIF)
            && isWideChannelPost(message);
    }

    /**
     * MessageObject.GroupedMessages.calculate: the chat width (px) a channel album is laid out against while it is a
     * wide post, 0 for Telegram's layout. The short screen side on a phone and the portrait chat width on tablets, as
     * for single media, so the album does not change with rotation.
     */
    public static int wideAlbumBase(ArrayList<MessageObject> messages) {
        return isWideChannelGroup(messages) ? mediaSide() : 0;
    }

    /**
     * MessageObject.GroupedMessages.calculate: the album width in grid units (Telegram: 800 of 1000) of a wide album
     * laid out against {@code base} ({@link #wideAlbumBase}). The album bubble ends at units / 1000 of the chat width,
     * so it ends {@value #WIDE_BUBBLE_INSET_DP} dp before the right edge like the other wide posts (974 units on a
     * 360 dp wide phone, 976 on 411 dp, 980 on 480 dp). Even, like Telegram's 800 and 750: the two-item layout halves
     * it. With an avatar Telegram adds 108 units of avatar column to the album's left items, so the album itself gets
     * 108 units less. The caller scales the height budget with these units, so rows keep Telegram's heights.
     */
    public static int wideAlbumUnits(int base, boolean avatar) {
        final int inset = AndroidUtilities.dp(WIDE_BUBBLE_INSET_DP);
        int units = base > inset * 4 ? (int) ((long) ALBUM_GRID_UNITS * (base - inset) / base) & ~1 : ALBUM_UNITS;
        if (avatar) {
            units -= ALBUM_AVATAR_UNITS;
        }
        return Math.max(ALBUM_UNITS, Math.min(units, ALBUM_GRID_UNITS - 2));
    }

    /**
     * ChatActivity.getValidGroupedMessage, which the chat list asks for every message before it lays out or binds it:
     * <ul>
     *   <li>{@code standardWidth} (the pinned messages list, search results and chat previews: their side buttons
     *       open a message, a preview is narrower than the screen): the message, and its album, keep Telegram's layout
     *       ({@code MessageObject.zgWideOff}); otherwise they follow the switch again. Every chat sets the flag for
     *       the messages it binds, because chats of one dialog share MessageObjects (replaceMessagesObjects after an
     *       edit). When it flips while the switch is on, the {@link #generation} moves, so cells laid out with the
     *       other width (and chats in the back stack, on resume) lay out again;</li>
     *   <li>an album laid out with another Wide posts state (the switch changed while the chat was in the back stack,
     *       or the cases above) is laid out again before its grid spans are used.</li>
     * </ul>
     * No allocation; while the switch is off this only compares the album's state.
     */
    public static void prepare(MessageObject message, LongSparseArray<MessageObject.GroupedMessages> groups, boolean standardWidth) {
        if (message == null) {
            return;
        }
        boolean flipped = message.zgWideOff != standardWidth;
        message.zgWideOff = standardWidth;
        final long groupId = message.getGroupId();
        if (groupId != 0 && groups != null) {
            flipped |= checkAlbum(groups.get(groupId), standardWidth);
        }
        if (flipped && isWideEnabled() && isChannelPost(message)) {
            bump();
        }
    }

    /** A post of a broadcast channel: the only messages the Wide posts flag changes anything for. */
    private static boolean isChannelPost(MessageObject message) {
        final TLRPC.Message owner = message.messageOwner;
        return owner != null && owner.post && owner.peer_id != null && owner.peer_id.channel_id != 0;
    }

    /** Returns whether the Wide posts flag of a message of the album flipped. */
    private static boolean checkAlbum(MessageObject.GroupedMessages group, boolean standardWidth) {
        if (group == null || group.messages.size() < 2) {
            return false;
        }
        boolean flipped = false;
        for (int i = 0, n = group.messages.size(); i < n; i++) {
            final MessageObject m = group.messages.get(i);
            if (m != null && m.zgWideOff != standardWidth) {
                m.zgWideOff = standardWidth;
                flipped = true;
            }
        }
        final int base = group.isDocuments ? 0 : wideAlbumBase(group.messages);
        if (base != group.zgWideBase) {
            group.calculate();
            if (group.isDocuments) {
                // calculate() returns from its documents branch before the Wide posts hook
                group.zgWideBase = 0;
            }
            group.cachedWidthForCaption = -1;
        }
        return flipped;
    }

    /**
     * Wide posts have no side AI summary button (there is no room beside them), so ZgMessageMenu offers "AI Summary"
     * in the message menu: the message of the selection whose summary Telegram can show and that is not shown yet, or
     * null. The open summary is closed from its header, as in Telegram.
     */
    public static MessageObject summaryTarget(MessageObject selected, MessageObject.GroupedMessages group) {
        if (isSummaryClosed(selected)) {
            return selected;
        }
        if (group != null && selected != null && isWideChannelPost(selected)) {
            for (int i = 0, n = group.messages.size(); i < n; i++) {
                final MessageObject m = group.messages.get(i);
                if (m != selected && isSummaryClosed(m)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static boolean isSummaryClosed(MessageObject message) {
        return message != null && message.messageOwner != null && !message.messageOwner.summarizedOpen
            && isWideChannelPost(message) && TranslateController.isSummarizable(message);
    }

    /**
     * Opens the AI summary of {@code message}: through its cell, exactly as Telegram's side button does, or, when the
     * message has no cell on screen (an album whose first item is scrolled away), with the same steps without one; the
     * album's visible cells and later binds then show it (ChatActivity.updateMessageTranslation / the bind).
     */
    public static boolean openSummary(ChatActivity chat, MessageObject message) {
        if (chat == null || message == null || message.messageOwner == null) {
            return false;
        }
        try {
            final RecyclerListView list = chat.getChatListView();
            if (list != null) {
                for (int i = 0, n = list.getChildCount(); i < n; i++) {
                    final View child = list.getChildAt(i);
                    if (child instanceof ChatMessageCell) {
                        final ChatMessageCell cell = (ChatMessageCell) child;
                        if (cell.getMessageObject() == message && cell.getDelegate() != null) {
                            cell.getDelegate().didPressSummarize(cell, false);
                            return true;
                        }
                    }
                }
            }
            if (message.messageOwner.summarizedOpen) {
                return false;
            }
            final int account = chat.getCurrentAccount();
            message.messageOwner.summarizedOpen = true;
            MessagesStorage.getInstance(account).updateMessageCustomParams(message.getDialogId(), message.messageOwner);
            // Loads the summary when it is not there yet; it then posts messageTranslated itself.
            MessagesController.getInstance(account).getTranslateController().checkTranslation(message, true);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslated, message);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    private static int mediaSide() {
        return AndroidUtilities.isTablet() ? AndroidUtilities.getMinTabletSide() : Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
    }

    private static int accountOf(MessageObject message) {
        final int account = message.currentAccount;
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT ? account : UserConfig.selectedAccount;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Channel Bottom Panel
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Whether ChatActivity hides the bottom Mute / Unmute panel of this channel: Channel Bottom Panel is off, the
     * chat is a broadcast channel opened normally, and the user is a plain subscriber (admins keep their panel
     * and the field they edit posts in). The Join button of channels the user has not joined is never hidden.
     */
    public static boolean hidesChannelPanel(TLRPC.Chat chat, boolean defaultMode) {
        return defaultMode && chat != null && ZgSettings.hideChannelBottomButton.get()
            && ChatObject.isChannelAndNotMegaGroup(chat) && !chat.gigagroup && !ChatObject.isCommunity(chat)
            && !ChatObject.isNotInChat(chat) && !ChatObject.canWriteToChat(chat) && !ChatObject.hasAdminRights(chat);
    }
}
